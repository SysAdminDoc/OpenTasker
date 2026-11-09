package com.opentasker.core.contexts

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import com.opentasker.app.R
import com.opentasker.core.logging.AppLogger
import com.opentasker.core.model.ContextSpec
import com.opentasker.core.model.ContextType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/**
 * Turns the ringing phone-state broadcasts of one call into one decision.
 *
 * With READ_PHONE_STATE and READ_CALL_LOG granted, Android delivers the ringing broadcast twice,
 * once with the number and once without, in no fixed order, and a dual-SIM phone can repeat it. A
 * task started by the first one would often have no number to read, so a broadcast without a number
 * waits briefly for one that has it. That wait is also what lets the event fire with an empty
 * number when Android never sends one: Call log denied, or a withheld caller.
 */
internal class IncomingCallGate {
    sealed interface Step {
        /** Fire now with this number. */
        data class Emit(val number: String) : Step

        /** Start the wait, then call [onWaitElapsed]. */
        data object Wait : Step

        /** The call stopped ringing, so a running wait no longer applies. */
        data object Reset : Step

        data object Ignore : Step
    }

    private enum class Phase { IDLE, WAITING, EMITTED }

    private var phase = Phase.IDLE

    fun onBroadcast(ringing: Boolean, number: String?): Step {
        if (!ringing) {
            phase = Phase.IDLE
            return Step.Reset
        }
        if (phase == Phase.EMITTED) return Step.Ignore
        if (!number.isNullOrBlank()) {
            phase = Phase.EMITTED
            return Step.Emit(number)
        }
        if (phase == Phase.WAITING) return Step.Ignore
        phase = Phase.WAITING
        return Step.Wait
    }

    /** Listening stopped, so the broadcast that would end this call will never arrive. */
    fun reset() {
        phase = Phase.IDLE
    }

    /** True when the wait ended with no number and the event should fire without one. */
    fun onWaitElapsed(): Boolean {
        if (phase != Phase.WAITING) return false
        phase = Phase.EMITTED
        return true
    }
}

/** Bridges a ringing call into an `event=incoming_call` pulse carrying the caller's number. */
object CallContextEvents {
    const val EVENT_INCOMING_CALL = "incoming_call"
    const val STATE_RINGING = "ringing"
    const val KNOWN_CONTACT_YES = "true"
    const val KNOWN_CONTACT_NO = "false"

    /** Never read as "not a contact": no number to look up, or Contacts access isn't granted. */
    const val KNOWN_CONTACT_UNKNOWN = "unknown"

    private const val NUMBER_WAIT_MS = 1_500L
    private const val MAX_NUMBER_CHARS = 64
    private const val MIN_NUMBER_DIGITS = 3
    private const val TAG = "CallContextEvents"

    private val events_ = MutableSharedFlow<ContextEvent>(extraBufferCapacity = 32)
    val events: SharedFlow<ContextEvent> = events_.asSharedFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val gate = IncomingCallGate()
    private var wait: Job? = null
    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
            val ringing = intent.getStringExtra(TelephonyManager.EXTRA_STATE) == TelephonyManager.EXTRA_STATE_RINGING

            // Deprecated in favor of a call-screening role, which would make OpenTasker compete
            // with the phone's own spam protection for one number.
            @Suppress("DEPRECATION")
            val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
            onBroadcast(context.applicationContext, ringing, number)
        }
    }

    private fun onBroadcast(app: Context, ringing: Boolean, number: String?) = synchronized(lock) {
        when (val step = gate.onBroadcast(ringing, number)) {
            is IncomingCallGate.Step.Emit -> {
                wait?.cancel()
                scope.launch { publish(app, step.number) }
            }
            IncomingCallGate.Step.Wait -> {
                wait = scope.launch {
                    delay(NUMBER_WAIT_MS)
                    if (synchronized(lock) { gate.onWaitElapsed() }) publish(app, null)
                }
            }
            IncomingCallGate.Step.Reset -> wait?.cancel()
            IncomingCallGate.Step.Ignore -> Unit
        }
    }

    private fun publish(app: Context, number: String?) {
        val usable = usableNumber(number)
        events_.tryEmit(buildEvent(usable, if (usable.isEmpty()) null else isKnownContact(app, usable)))
    }

    /**
     * Follows whether any enabled profile declares the event, so a call doesn't wake the app for a
     * trigger nobody uses. The receiver isn't exported, so only the system can deliver to it.
     */
    fun sync(context: Context, wanted: Boolean) = synchronized(lock) {
        if (wanted == registered) return@synchronized
        if (wanted) {
            runCatching {
                ContextCompat.registerReceiver(
                    context,
                    receiver,
                    IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED),
                    ContextCompat.RECEIVER_NOT_EXPORTED,
                )
            }.onSuccess { registered = true }
                .onFailure { AppLogger.warn(TAG, "Incoming call receiver could not be registered", it) }
        } else {
            registered = false
            wait?.cancel()
            gate.reset()
            runCatching { context.unregisterReceiver(receiver) }
                .onFailure { AppLogger.warn(TAG, "Incoming call receiver was already unregistered", it) }
        }
    }

    /**
     * The number as Android gave it, or empty when it can't identify anyone. A withheld caller is
     * normally an empty string, but some carriers send a word such as "Private" or a marker like -1.
     */
    fun usableNumber(raw: String?): String {
        val trimmed = raw?.trim()?.take(MAX_NUMBER_CHARS).orEmpty()
        return if (trimmed.count(Char::isDigit) >= MIN_NUMBER_DIGITS) trimmed else ""
    }

    /** [knownContact] is null when nobody could check. An empty number is never a known contact. */
    fun buildEvent(number: String, knownContact: Boolean?): ContextEvent = ContextEvent(
        type = "event",
        matched = true,
        metadata = mapOf(
            "event" to EVENT_INCOMING_CALL,
            "state" to STATE_RINGING,
            "number" to number,
            "knownContact" to when {
                number.isEmpty() || knownContact == null -> KNOWN_CONTACT_UNKNOWN
                knownContact -> KNOWN_CONTACT_YES
                else -> KNOWN_CONTACT_NO
            },
        ),
    )

    private fun isKnownContact(context: Context, number: String): Boolean? {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) return null
        val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.ENTERPRISE_CONTENT_FILTER_URI, Uri.encode(number))
        return runCatching {
            context.contentResolver
                .query(uri, arrayOf(ContactsContract.PhoneLookup._ID), null, null, null)
                ?.use { it.moveToFirst() }
        }.onFailure { AppLogger.warn(TAG, "Contact lookup failed", it) }.getOrNull()
    }
}

/** True when these contexts include an incoming-call trigger, for [CallContextEvents.sync]. */
fun declaresIncomingCall(specs: Iterable<ContextSpec>): Boolean = specs.any { spec ->
    spec.type == ContextType.EVENT &&
        spec.config["event"].orEmpty().trim().equals(CallContextEvents.EVENT_INCOMING_CALL, ignoreCase = true)
}

object CallEventPresets {
    fun allPresets(): List<EventContextPreset> = listOf(
        EventContextPreset(
            id = "incoming-call",
            labelRes = R.string.context_preset_incoming_call,
            config = mapOf("event" to CallContextEvents.EVENT_INCOMING_CALL),
        ),
        EventContextPreset(
            id = "incoming-call-not-in-contacts",
            labelRes = R.string.context_preset_incoming_call_not_in_contacts,
            config = mapOf(
                "event" to CallContextEvents.EVENT_INCOMING_CALL,
                "knownContact" to CallContextEvents.KNOWN_CONTACT_NO,
            ),
        ),
    )
}
