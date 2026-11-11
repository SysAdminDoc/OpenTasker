package com.opentasker.core.contexts

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Parcelable
import com.opentasker.core.platform.ForeignParcels
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

enum class SharePublishResult {
    ACCEPTED,
    INVALID_INPUT,
    URI_NOT_READABLE,
}

/**
 * Sanitizes Android Sharesheet deliveries before they enter the automation engine.
 *
 * No URI is opened here and no arbitrary Parcelable is retained. The resulting event contains
 * only bounded strings, so a task can safely consume share_text/share_uri/share_uris/share_mime.
 */
object ShareContextEvents {
    const val EVENT_SHARE = "share"

    const val MAX_TEXT_CHARS = 16 * 1024
    const val MAX_URI_CHARS = 2 * 1024
    const val MAX_MIME_CHARS = 128
    const val MAX_URIS = 16
    const val MAX_TOTAL_CHARS = 64 * 1024
    const val PENDING_PULSE_REPLAY_MS = 30_000L

    private val shareEvents = MutableSharedFlow<ContextEvent>(extraBufferCapacity = 32)
    private val pendingPulse = AtomicReference<PendingSharePulse?>(null)

    /** Replays a share briefly so a Sharesheet launch can race starting the foreground engine. */
    val events: Flow<ContextEvent> = flow {
        val now = System.currentTimeMillis()
        pendingPulse.get()
            ?.takeIf { now - it.observedAtMs <= PENDING_PULSE_REPLAY_MS }
            ?.let { emit(it.event) }
        emitAll(shareEvents.asSharedFlow())
    }

    /**
     * Parses and publishes a Sharesheet intent without retaining Android framework objects.
     * This overload is kept for pure callers that have already handled URI readability.
     */
    fun publishFromIntent(intent: Intent, nowMs: Long = System.currentTimeMillis()): Boolean {
        return publishFromIntentResult(context = null, intent = intent, nowMs = nowMs) == SharePublishResult.ACCEPTED
    }

    /**
     * Parses, checks temporary content-URI access, and publishes one Sharesheet delivery.
     * Readability is checked before the event enters the engine so a missing grant becomes
     * visible feedback instead of a later opaque task failure.
     */
    fun publishFromIntent(context: Context, intent: Intent, nowMs: Long = System.currentTimeMillis()): SharePublishResult =
        publishFromIntentResult(context, intent, nowMs)

    private fun publishFromIntentResult(
        context: Context?,
        intent: Intent,
        nowMs: Long,
    ): SharePublishResult {
        val event = parseIntent(intent, nowMs) ?: return SharePublishResult.INVALID_INPUT
        if (context != null && event.containsUnreadableContentUri(context)) {
            return SharePublishResult.URI_NOT_READABLE
        }
        pendingPulse.set(PendingSharePulse(event, nowMs))
        shareEvents.tryEmit(event)
        return SharePublishResult.ACCEPTED
    }

    fun parseIntent(intent: Intent, nowMs: Long = System.currentTimeMillis()): ContextEvent? {
        return runCatching {
            // A share's text and Uris are Android's own types, so nothing a real share sends needs OpenTasker's loader.
            val extras = ForeignParcels.guard(intent).extras
            parseInput(
                ShareInput(
                    action = intent.action,
                    mime = intent.type,
                    textValue = extras?.takeIf { it.containsKey(Intent.EXTRA_TEXT) }
                        ?.get(Intent.EXTRA_TEXT),
                    streamValue = extras?.get(Intent.EXTRA_STREAM),
                    dataUriValue = intent.data,
                ),
                nowMs,
            )
        }.getOrNull()
    }

    internal fun parseInput(input: ShareInput, nowMs: Long = System.currentTimeMillis()): ContextEvent? {
        if (input.action != Intent.ACTION_SEND && input.action != Intent.ACTION_SEND_MULTIPLE) return null

        val text = readTextValue(input.textValue)
        if (input.textValue != null && text == null) return null
        val streamValue = input.streamValue
        val streamValues = when (input.action) {
                Intent.ACTION_SEND -> when (streamValue) {
                    null -> emptyList()
                    else -> listOf(streamValue)
                }
                Intent.ACTION_SEND_MULTIPLE -> when (streamValue) {
                    null -> emptyList()
                    is ArrayList<*> -> {
                        if (streamValue.size > MAX_URIS) return null
                        streamValue.map { value ->
                            value.takeIf { it is Uri || it is String } ?: return null
                        }
                    }
                    else -> return null
                }
                else -> emptyList()
            }
        if (streamValues.size > MAX_URIS) return null

        val sanitizedStreamUris = streamValues.map { sanitizeUriValue(it) ?: return null }
        val dataUri = input.dataUriValue?.let { sanitizeUriValue(it) ?: return null }
        val sanitizedUris = (sanitizedStreamUris + listOfNotNull(dataUri))
            .distinct()
        if (sanitizedUris.size > MAX_URIS) return null

        val uriValues = sanitizedUris.toMutableList()
        if (uriValues.isEmpty() && text.isUrlShare()) uriValues += requireNotNull(text)
        if (text.isNullOrBlank() && uriValues.isEmpty()) return null

        val mime = input.mime?.trim().orEmpty()
        if (mime.length > MAX_MIME_CHARS || mime.any(Char::isISOControl)) return null
        val totalChars = text.orEmpty().length + uriValues.sumOf(String::length) + mime.length
        if (totalChars > MAX_TOTAL_CHARS) return null

        return ContextEvent(
            type = "event",
            matched = true,
            metadata = buildMap {
                put("event", EVENT_SHARE)
                if (mime.isNotBlank()) put("mime", mime)
                if (!text.isNullOrBlank()) put("text", text)
                uriValues.firstOrNull()?.let { put("uri", it) }
                if (uriValues.isNotEmpty()) put("uris", uriValues.joinToString("\n"))
                put("count", uriValues.size.toString())
                put("multiple", (uriValues.size > 1).toString())
                put("observedAtEpochMs", nowMs.toString())
            },
        )
    }

    private fun readTextValue(raw: Any?): String? {
        if (raw == null) return null
        // String is the portable share representation. CharSequences backed by Parcelable can
        // execute class-loading/unparceling code, so reject them with every other Parcelable.
        val value = when (raw) {
            is String -> raw
            is CharSequence -> if (raw is Parcelable) return null else raw.toString()
            else -> return null
        }
        if (value.length > MAX_TEXT_CHARS || value.any { it.isISOControl() && it !in "\r\n\t" }) return null
        return value.replace(Regex("\\s+"), " ").trim()
    }

    private fun sanitizeUriValue(raw: Any?): String? {
        val value = when (raw) {
            is Uri -> raw.toString()
            is String -> raw
            else -> return null
        }.trim()
        if (value.isBlank() || value.length > MAX_URI_CHARS || value.any(Char::isISOControl)) return null
        return value
    }

    private fun String?.isUrlShare(): Boolean =
        this?.let {
            (it.startsWith("https://", ignoreCase = true) || it.startsWith("http://", ignoreCase = true)) &&
                !it.contains(' ')
        } == true

    internal fun resetForTests() {
        pendingPulse.set(null)
    }

    private fun ContextEvent.containsUnreadableContentUri(context: Context): Boolean =
        containsUnreadableContentUri { rawUri ->
            runCatching {
                context.contentResolver.openInputStream(Uri.parse(rawUri))?.use { true } ?: false
            }.getOrDefault(false)
        }

    internal fun ContextEvent.containsUnreadableContentUri(isReadable: (String) -> Boolean): Boolean =
        metadata["uris"].orEmpty()
            .lineSequence()
            .filter(String::isNotBlank)
            .filter { rawUri -> rawUri.substringBefore(':').equals("content", ignoreCase = true) }
            .any { rawUri -> !isReadable(rawUri) }
}

internal data class ShareInput(
    val action: String?,
    val mime: String? = null,
    val textValue: Any? = null,
    val streamValue: Any? = null,
    val dataUriValue: Any? = null,
)

private data class PendingSharePulse(
    val event: ContextEvent,
    val observedAtMs: Long,
)
