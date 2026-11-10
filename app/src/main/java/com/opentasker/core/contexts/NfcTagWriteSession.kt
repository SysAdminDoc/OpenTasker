package com.opentasker.core.contexts

import android.content.Context
import android.content.Intent
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable
import android.os.Build
import androidx.annotation.StringRes
import com.opentasker.app.R
import com.opentasker.core.logging.AppLogger
import java.io.IOException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

data class NfcTagWritePlan(
    val text: String,
    val estimatedBytes: Int,
)

data class NfcTagWriteResult(
    val success: Boolean,
    @get:StringRes val messageRes: Int,
    val messageArgs: List<Any> = emptyList(),
) {
    fun message(context: Context): String = context.getString(messageRes, *messageArgs.toTypedArray())
}

/**
 * Connect, write and format raise a bare IOException both for a tag that left mid-write and for one
 * that refused the write (only reads raise TagLostException), so the copy covers both.
 */
@StringRes
internal fun nfcWriteFailureRes(error: Throwable): Int =
    if (error is IOException) R.string.nfc_write_io_failed else R.string.nfc_write_failed

object NfcTagWritePlanner {
    private const val MAX_TEXT_CHARS = 120
    private const val DEFAULT_TEXT = "OpenTasker NFC trigger"

    fun planTextRecord(label: String): NfcTagWritePlan {
        val text = normalizeLabel(label).ifBlank { DEFAULT_TEXT }
        val languageBytes = "en".toByteArray(Charsets.US_ASCII).size
        val textBytes = text.toByteArray(Charsets.UTF_8).size
        return NfcTagWritePlan(
            text = text,
            estimatedBytes = 1 + languageBytes + textBytes,
        )
    }

    fun normalizeLabel(label: String): String =
        label
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(MAX_TEXT_CHARS)
}

object NfcTagWriteSession {
    private val writeResults = MutableSharedFlow<NfcTagWriteResult>(extraBufferCapacity = 8)

    // An armed write must not linger: it overwrites (or formats) the next scanned tag, so it
    // expires after ARM_TIMEOUT_MS and is disarmed when the editor dialog closes.
    private const val ARM_TIMEOUT_MS = 60_000L

    @Volatile
    private var pendingPlan: NfcTagWritePlan? = null

    @Volatile
    private var armedAtElapsedMs: Long = 0L

    val results: SharedFlow<NfcTagWriteResult> = writeResults.asSharedFlow()

    /** True while a write is armed and unexpired — for surfacing the armed state in the UI. */
    fun isArmed(): Boolean = pendingPlan != null && !isExpired()

    fun armTextRecord(label: String): NfcTagWriteResult {
        val plan = NfcTagWritePlanner.planTextRecord(label)
        pendingPlan = plan
        armedAtElapsedMs = android.os.SystemClock.elapsedRealtime()
        return publish(NfcTagWriteResult(true, R.string.nfc_write_armed, listOf(plan.estimatedBytes)))
    }

    /** Cancels any pending write; safe to call when the editor dialog is dismissed. */
    fun disarm() {
        pendingPlan = null
    }

    private fun isExpired(): Boolean =
        android.os.SystemClock.elapsedRealtime() - armedAtElapsedMs > ARM_TIMEOUT_MS

    fun writeFromIntent(intent: Intent): NfcTagWriteResult? {
        val plan = pendingPlan ?: return null
        if (isExpired()) {
            pendingPlan = null
            return null
        }
        val tag = intent.nfcTag()
            ?: return publish(NfcTagWriteResult(false, R.string.nfc_write_no_tag))
        val result = runCatching {
            writeTextRecord(tag, plan)
        }.getOrElse { error ->
            // The tag's own exception text goes to the log; the dialog says what to do next.
            AppLogger.warn("NfcTagWriteSession", "NFC write failed", error)
            NfcTagWriteResult(false, nfcWriteFailureRes(error))
        }
        pendingPlan = null
        return publish(result)
    }

    private fun writeTextRecord(tag: Tag, plan: NfcTagWritePlan): NfcTagWriteResult {
        val message = NdefMessage(arrayOf(NdefRecord.createTextRecord("en", plan.text)))
        val payloadSize = message.toByteArray().size
        val ndef = Ndef.get(tag)
        if (ndef != null) {
            ndef.connect()
            try {
                if (!ndef.isWritable) {
                    return NfcTagWriteResult(false, R.string.nfc_write_read_only)
                }
                if (ndef.maxSize < payloadSize) {
                    return NfcTagWriteResult(false, R.string.nfc_write_too_small, listOf(payloadSize))
                }
                ndef.writeNdefMessage(message)
                return NfcTagWriteResult(true, R.string.nfc_write_written)
            } finally {
                runCatching { ndef.close() }
            }
        }

        val formatable = NdefFormatable.get(tag)
            ?: return NfcTagWriteResult(false, R.string.nfc_write_no_ndef)
        formatable.connect()
        try {
            formatable.format(message)
            return NfcTagWriteResult(true, R.string.nfc_write_formatted)
        } finally {
            runCatching { formatable.close() }
        }
    }

    private fun publish(result: NfcTagWriteResult): NfcTagWriteResult {
        writeResults.tryEmit(result)
        return result
    }

    @Suppress("DEPRECATION")
    private fun Intent.nfcTag(): Tag? =
        if (Build.VERSION.SDK_INT >= 33) {
            getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag::class.java)
        } else {
            getParcelableExtra(NfcAdapter.EXTRA_TAG)
        }
}
