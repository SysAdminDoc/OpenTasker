package com.opentasker.core.contexts

import android.content.Intent
import com.opentasker.core.data.JsonNesting
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * The app-owned boundary for a de-googled push distributor.
 *
 * The official UnifiedPush service and the legacy explicit-broadcast receiver publish through this
 * boundary. The legacy receiver authenticates the per-install token before this bus sees its
 * payload; the official connector authenticates and decrypts its bytes message first. Message
 * content is intentionally not copied into [ContextEvent.metadata]; event filters can match the
 * topic, title, event ID, and payload size without putting a remote message in logs.
 */
object PushContextEvents {
    const val ACTION_PUSH_EVENT = "com.opentasker.action.PUSH_EVENT"
    /** ntfy's documented action for a notification button that sends a broadcast. */
    const val ACTION_NTFY_USER_ACTION = "io.heckel.ntfy.USER_ACTION"
    /** ntfy's documented action for the incoming-message automation broadcast. */
    const val ACTION_NTFY_MESSAGE_RECEIVED = "io.heckel.ntfy.MESSAGE_RECEIVED"
    const val EVENT_PUSH = "push"
    const val EXTRA_TOKEN = "com.opentasker.extra.PUSH_TOKEN"
    const val EXTRA_TOPIC = "com.opentasker.extra.PUSH_TOPIC"
    const val EXTRA_EVENT_ID = "com.opentasker.extra.PUSH_EVENT_ID"
    const val EXTRA_TITLE = "com.opentasker.extra.PUSH_TITLE"
    const val EXTRA_MESSAGE = "com.opentasker.extra.PUSH_MESSAGE"

    // These names are the ntfy Android broadcast contract. Keep them unprefixed when parsing so
    // an ntfy `broadcast` action can target ACTION_PUSH_EVENT directly without a relay app.
    const val NTFY_EXTRA_ID = "id"
    const val NTFY_EXTRA_BASE_URL = "base_url"
    const val NTFY_EXTRA_TOPIC = "topic"
    const val NTFY_EXTRA_MUTED = "muted"
    const val NTFY_EXTRA_MUTED_STRING = "muted_str"
    const val NTFY_EXTRA_TIME = "time"
    const val NTFY_EXTRA_TITLE = "title"
    const val NTFY_EXTRA_MESSAGE = "message"
    const val NTFY_EXTRA_MESSAGE_BYTES = "message_bytes"
    const val NTFY_EXTRA_ENCODING = "encoding"
    const val NTFY_EXTRA_CONTENT_TYPE = "content_type"
    const val NTFY_EXTRA_TAGS = "tags"
    const val NTFY_EXTRA_TAGS_MAP = "tags_map"
    const val NTFY_EXTRA_PRIORITY = "priority"
    const val NTFY_EXTRA_CLICK = "click"
    const val NTFY_EXTRA_ATTACHMENT_NAME = "attachment_name"
    const val NTFY_EXTRA_ATTACHMENT_TYPE = "attachment_type"
    const val NTFY_EXTRA_ATTACHMENT_SIZE = "attachment_size"
    const val NTFY_EXTRA_ATTACHMENT_EXPIRES = "attachment_expires"
    const val NTFY_EXTRA_ATTACHMENT_URL = "attachment_url"

    const val MAX_TOPIC_CHARS = 160
    const val MAX_EVENT_ID_CHARS = 128
    const val MAX_TITLE_CHARS = 160
    const val MAX_MESSAGE_BYTES = 8 * 1024
    /** AND_3.1.0 bounds the encrypted UnifiedPush bytes message at 4096 bytes. */
    const val MAX_UNIFIED_PUSH_MESSAGE_BYTES = 4 * 1024
    /** An ntfy message nests four levels at most (`actions[].headers`). */
    const val MAX_UNIFIED_PUSH_MESSAGE_DEPTH = 32
    const val PENDING_PULSE_REPLAY_MS = 30_000L

    private val pushEvents = MutableSharedFlow<ContextEvent>(extraBufferCapacity = 32)
    private val pendingPulse = AtomicReference<PendingPushPulse?>(null)
    private val seenEventIds = ConcurrentHashMap<String, Long>()

    /** A flow with a short replay window for a distributor delivery racing engine startup. */
    val events: Flow<ContextEvent> = flow {
        val now = System.currentTimeMillis()
        pendingPulse.get()
            ?.takeIf { now - it.observedAtMs <= PENDING_PULSE_REPLAY_MS }
            ?.let { emit(it.event) }
        emitAll(pushEvents.asSharedFlow())
    }

    /**
     * Parses, authenticates, redacts, and publishes one distributor delivery.
     *
     * Delivery is at-least-once: the distributor may retry a broadcast, but a repeated
     * topic/event-id pair is suppressed for [PENDING_PULSE_REPLAY_MS]. No network retry occurs in
     * this receiver; retry responsibility stays with the distributor/bridge.
     */
    fun publishFromIntent(
        intent: Intent,
        expectedToken: String,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean {
        val delivery = intent.toPushDelivery() ?: return false
        return publishDelivery(delivery, expectedToken, nowMs)
    }

    fun publishDelivery(
        delivery: PushDelivery,
        expectedToken: String,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean {
        val event = parseDelivery(delivery, expectedToken, nowMs) ?: return false
        return publishEvent(event, nowMs)
    }

    /**
     * Parses the standard ntfy/UnifiedPush JSON bytes message after the connector has decrypted
     * it. The connector has already authenticated the distributor and acknowledged the message;
     * this method only applies OpenTasker's payload contract and event safeguards.
     */
    fun publishUnifiedPushMessage(
        content: ByteArray,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean {
        val event = parseUnifiedPushMessage(content, nowMs) ?: return false
        return publishEvent(event, nowMs)
    }

    fun parseUnifiedPushMessage(
        content: ByteArray,
        nowMs: Long = System.currentTimeMillis(),
    ): ContextEvent? {
        if (content.isEmpty() || content.size > MAX_UNIFIED_PUSH_MESSAGE_BYTES) return null
        val text = String(content, StandardCharsets.UTF_8)
        if (!JsonNesting.within(text, MAX_UNIFIED_PUSH_MESSAGE_DEPTH)) return null
        val root = runCatching { Json.parseToJsonElement(text) }.getOrNull() as? JsonObject ?: return null
        val topic = root.stringValue("topic") ?: return null
        val eventId = root.stringValue("id", "event_id", "eventId") ?: return null
        val title = root.stringValue("title").orEmpty()
        val message = root.stringValue("message", "body").orEmpty()
        return parseFields(
            PushDelivery(
                token = "",
                topic = topic,
                eventId = eventId,
                title = title,
                message = message,
                baseUrl = root.stringValue("base_url").orEmpty(),
                muted = root.stringValue("muted").orEmpty(),
                mutedString = root.stringValue("muted_str").orEmpty(),
                time = root.stringValue("time").orEmpty(),
                encoding = root.stringValue("encoding").orEmpty(),
                contentType = root.stringValue("content_type").orEmpty(),
                tags = root.stringValue("tags").orEmpty(),
                tagsMap = root.stringValue("tags_map").orEmpty(),
                priority = root.stringValue("priority").orEmpty(),
                attachmentName = root.stringValue("attachment_name").orEmpty(),
                attachmentType = root.stringValue("attachment_type").orEmpty(),
                attachmentSize = root.stringValue("attachment_size").orEmpty(),
                attachmentExpires = root.stringValue("attachment_expires").orEmpty(),
            ),
            nowMs,
        )
    }

    private fun publishEvent(event: ContextEvent, nowMs: Long): Boolean {
        val dedupeKey = "${event.metadata["topic"]}\u0000${event.metadata["eventId"]}"
        pruneSeen(nowMs)
        if (seenEventIds.putIfAbsent(dedupeKey, nowMs) != null) return false

        pendingPulse.set(PendingPushPulse(event, nowMs))
        return pushEvents.tryEmit(event)
    }

    fun parseIntent(
        intent: Intent,
        expectedToken: String,
        nowMs: Long = System.currentTimeMillis(),
    ): ContextEvent? = intent.toPushDelivery()?.let { parseDelivery(it, expectedToken, nowMs) }

    fun parseDelivery(
        delivery: PushDelivery,
        expectedToken: String,
        nowMs: Long = System.currentTimeMillis(),
    ): ContextEvent? {
        if (expectedToken.isBlank() || !constantTimeEquals(delivery.token, expectedToken)) return null
        return parseFields(delivery, nowMs)
    }

    private fun parseFields(
        delivery: PushDelivery,
        nowMs: Long,
    ): ContextEvent? {
        val topic = delivery.topic.trim()
        val eventId = delivery.eventId.trim()
        val title = sanitizeText(delivery.title, MAX_TITLE_CHARS)
        val messageBytes = delivery.messageBytes ?: delivery.message.toByteArray(StandardCharsets.UTF_8).size
        if (topic.isBlank() || topic.length > MAX_TOPIC_CHARS) return null
        if (eventId.isBlank() || eventId.length > MAX_EVENT_ID_CHARS) return null
        if (messageBytes !in 0..MAX_MESSAGE_BYTES) return null

        return buildEvent(
            topic = topic,
            eventId = eventId,
            title = title,
            messageBytes = messageBytes,
            nowMs = nowMs,
            ntfyMetadata = delivery.ntfyMetadata(),
        )
    }

    private fun JsonObject.stringValue(vararg names: String): String? =
        names.asSequence()
            .mapNotNull { name -> (this[name] as? JsonPrimitive)?.contentOrNull }
            .firstOrNull()

    fun buildEvent(
        topic: String,
        eventId: String,
        title: String = "",
        messageBytes: Int = 0,
        nowMs: Long = System.currentTimeMillis(),
        ntfyMetadata: Map<String, String> = emptyMap(),
    ): ContextEvent = ContextEvent(
        type = "event",
        matched = true,
        metadata = buildMap {
            put("event", EVENT_PUSH)
            put("topic", topic)
            put("eventId", eventId)
            put(NTFY_EXTRA_ID, eventId)
            if (title.isNotBlank()) put("title", title)
            put("payloadBytes", messageBytes.toString())
            put("observedAtEpochMs", nowMs.toString())
            ntfyMetadata.forEach { (key, value) ->
                if (value.isNotBlank()) put(key, value)
            }
        },
    )

    private fun Intent.toPushDelivery(): PushDelivery? {
        if (action !in setOf(ACTION_PUSH_EVENT, ACTION_NTFY_USER_ACTION, ACTION_NTFY_MESSAGE_RECEIVED)) {
            return null
        }
        return PushDelivery(
            token = firstExtraString(EXTRA_TOKEN),
            topic = firstExtraString(EXTRA_TOPIC, NTFY_EXTRA_TOPIC),
            eventId = firstExtraString(EXTRA_EVENT_ID, NTFY_EXTRA_ID),
            title = firstExtraString(EXTRA_TITLE, NTFY_EXTRA_TITLE),
            message = firstExtraString(EXTRA_MESSAGE, NTFY_EXTRA_MESSAGE),
            messageBytes = runCatching { extras?.get(NTFY_EXTRA_MESSAGE_BYTES) }.getOrNull().byteCount(),
            baseUrl = firstExtraString(NTFY_EXTRA_BASE_URL),
            muted = firstExtraString(NTFY_EXTRA_MUTED),
            mutedString = firstExtraString(NTFY_EXTRA_MUTED_STRING),
            time = firstExtraString(NTFY_EXTRA_TIME),
            encoding = firstExtraString(NTFY_EXTRA_ENCODING),
            contentType = firstExtraString(NTFY_EXTRA_CONTENT_TYPE),
            tags = firstExtraString(NTFY_EXTRA_TAGS),
            tagsMap = firstExtraString(NTFY_EXTRA_TAGS_MAP),
            priority = firstExtraString(NTFY_EXTRA_PRIORITY),
            attachmentName = firstExtraString(NTFY_EXTRA_ATTACHMENT_NAME),
            attachmentType = firstExtraString(NTFY_EXTRA_ATTACHMENT_TYPE),
            attachmentSize = firstExtraString(NTFY_EXTRA_ATTACHMENT_SIZE),
            attachmentExpires = firstExtraString(NTFY_EXTRA_ATTACHMENT_EXPIRES),
        )
    }

    /**
     * Every read is guarded individually: a sender can poison one key and leave the rest readable,
     * and the delivery is still worth parsing from what does read. `extras` itself can throw.
     */
    private fun Intent.firstExtraString(vararg names: String): String =
        names.asSequence()
            .mapNotNull { name ->
                runCatching { extras?.get(name) }.getOrNull().extraString()?.takeIf(String::isNotBlank)
            }
            .firstOrNull()
            .orEmpty()

    private fun Any?.extraString(): String? = when (this) {
        null -> null
        is CharSequence -> toString()
        is Boolean,
        is Number,
        -> toString()
        else -> null
    }

    private fun Any?.byteCount(): Int? = when (this) {
        is ByteArray -> size
        is String -> toByteArray(StandardCharsets.UTF_8).size
        else -> null
    }

    private fun PushDelivery.ntfyMetadata(): Map<String, String> = buildMap {
        putIfPresent(NTFY_EXTRA_BASE_URL, baseUrl, 512)
        putIfPresent(NTFY_EXTRA_MUTED, muted, 16)
        putIfPresent(NTFY_EXTRA_MUTED_STRING, mutedString, 16)
        putIfPresent(NTFY_EXTRA_TIME, time, 32)
        putIfPresent(NTFY_EXTRA_ENCODING, encoding, 32)
        putIfPresent(NTFY_EXTRA_CONTENT_TYPE, contentType, 128)
        putIfPresent(NTFY_EXTRA_TAGS, tags, 512)
        putIfPresent(NTFY_EXTRA_TAGS_MAP, tagsMap, 512)
        putIfPresent(NTFY_EXTRA_PRIORITY, priority, 32)
        putIfPresent(NTFY_EXTRA_ATTACHMENT_NAME, attachmentName, 256)
        putIfPresent(NTFY_EXTRA_ATTACHMENT_TYPE, attachmentType, 128)
        putIfPresent(NTFY_EXTRA_ATTACHMENT_SIZE, attachmentSize, 32)
        putIfPresent(NTFY_EXTRA_ATTACHMENT_EXPIRES, attachmentExpires, 32)
    }

    private fun MutableMap<String, String>.putIfPresent(key: String, value: String, maxChars: Int) {
        sanitizeText(value, maxChars).takeIf(String::isNotBlank)?.let { put(key, it) }
    }

    internal fun resetForTests() {
        pendingPulse.set(null)
        seenEventIds.clear()
    }

    private fun pruneSeen(nowMs: Long) {
        seenEventIds.entries.removeIf { nowMs - it.value > PENDING_PULSE_REPLAY_MS }
        if (seenEventIds.size > MAX_SEEN_EVENT_IDS) {
            seenEventIds.entries
                .sortedBy { it.value }
                .take(seenEventIds.size - MAX_SEEN_EVENT_IDS)
                .forEach { seenEventIds.remove(it.key, it.value) }
        }
    }

    private fun sanitizeText(value: String?, maxChars: Int): String =
        value
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?.take(maxChars)
            .orEmpty()

    internal fun constantTimeEquals(left: String, right: String): Boolean =
        MessageDigest.isEqual(
            left.toByteArray(StandardCharsets.UTF_8),
            right.toByteArray(StandardCharsets.UTF_8),
        )

    private const val MAX_SEEN_EVENT_IDS = 512
}

data class PushDelivery(
    val token: String,
    val topic: String,
    val eventId: String,
    val title: String = "",
    val message: String = "",
    val messageBytes: Int? = null,
    val baseUrl: String = "",
    val muted: String = "",
    val mutedString: String = "",
    val time: String = "",
    val encoding: String = "",
    val contentType: String = "",
    val tags: String = "",
    val tagsMap: String = "",
    val priority: String = "",
    val attachmentName: String = "",
    val attachmentType: String = "",
    val attachmentSize: String = "",
    val attachmentExpires: String = "",
)

private data class PendingPushPulse(
    val event: ContextEvent,
    val observedAtMs: Long,
)
