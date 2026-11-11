package com.opentasker.core.contexts

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.core.content.ContextCompat
import com.opentasker.core.logging.AppLogger
import com.opentasker.core.platform.ForeignParcels
import java.util.Locale
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * A trigger that fires when another app, or `adb shell am broadcast`, sends a named intent.
 *
 * This is the oldest unmet ask in the FOSS automation space, and it is also the one with the
 * widest blast radius, because the action string comes from the user and the payload comes from
 * whoever sent it. Two rules keep that bounded:
 *
 * 1. The receiver is registered for exactly the actions enabled profiles declare, and for nothing
 *    else. `RECEIVER_EXPORTED` is unavoidable (a private receiver could never hear another app),
 *    so the action allowlist is the control.
 * 2. Extras are copied into bounded strings and nothing else. They're read with the boot class
 *    loader, so no Parcelable or Serializable of OpenTasker's own is built from one, and anything
 *    oversized is dropped with a note rather than truncated silently, so a task never sees half a
 *    value and believes it whole.
 */
object BroadcastContextEvents {
    const val EVENT_BROADCAST = "broadcast"

    /** Each registered action costs a filter entry and a wake-up, so the set is bounded. */
    const val MAX_ACTIONS = 16
    const val MAX_ACTION_CHARS = 128
    const val MAX_EXTRA_KEYS = 16
    const val MAX_EXTRA_KEY_CHARS = 64
    const val MAX_EXTRA_VALUE_CHARS = 512

    /**
     * `getSentFromPackage` arrived in API 34, and even there it reports a name only when the
     * sender opted in through `BroadcastOptions.setShareIdentityEnabled`. Almost nothing does,
     * `adb shell am broadcast` included, so an empty sender is the normal case rather than the
     * exception. The editor says so; the matcher refuses an unknown sender rather than firing
     * anyway, because a sender filter that matches everything is worse than no filter.
     */
    const val SENDER_IDENTITY_API = 34

    private val broadcasts = MutableSharedFlow<ContextEvent>(extraBufferCapacity = 32)
    val events: SharedFlow<ContextEvent> = broadcasts.asSharedFlow()

    private val lock = Any()
    private var registeredActions: Set<String> = emptySet()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action?.trim().orEmpty()
            // An exported receiver hears whatever the filter lets through, so re-check the action
            // against the allowlist rather than trusting the filter alone.
            if (action.isEmpty() || action !in currentActions()) return
            val sender = if (Build.VERSION.SDK_INT >= SENDER_IDENTITY_API) sentFromPackage.orEmpty() else ""
            broadcasts.tryEmit(buildEvent(action, sender, readExtras(intent)))
        }
    }

    private fun currentActions(): Set<String> = synchronized(lock) { registeredActions }

    /**
     * Registers, re-registers, or unregisters the receiver so it covers exactly [actions].
     *
     * Called on every profile reconcile, because the action set is per-profile configuration and
     * can change without any monitor starting or stopping.
     */
    fun sync(context: Context, actions: Set<String>) {
        val wanted = actions.mapNotNull(::normalizeAction).distinct().take(MAX_ACTIONS).toSet()
        synchronized(lock) {
            if (wanted == registeredActions) return
            if (registeredActions.isNotEmpty()) {
                runCatching { context.unregisterReceiver(receiver) }
                    .onFailure { AppLogger.warn(TAG, "Broadcast receiver was already unregistered", it) }
                registeredActions = emptySet()
            }
            if (wanted.isEmpty()) {
                AppLogger.info(TAG, "Broadcast trigger receiver unregistered; no enabled profile declares an action")
                return
            }
            val filter = IntentFilter().apply { wanted.forEach(::addAction) }
            val registered = runCatching {
                ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
            }.isSuccess
            if (registered) {
                registeredActions = wanted
                AppLogger.info(TAG, "Broadcast trigger listening for ${wanted.size} action(s)")
            } else {
                AppLogger.warn(TAG, "Broadcast trigger receiver could not be registered")
            }
        }
    }

    /** The action set currently registered, for the Context Inspector and for tests. */
    fun listeningActions(): Set<String> = currentActions()

    /**
     * A usable action string, or null. Actions are intent action names, so anything with
     * whitespace, control characters, or no content at all is a configuration mistake rather than
     * something to register and never hear from again.
     */
    fun normalizeAction(raw: String?): String? {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty() || value.length > MAX_ACTION_CHARS) return null
        if (value.any { it.isWhitespace() || it.isISOControl() }) return null
        return value
    }

    /**
     * True when the action belongs to the platform's own namespace.
     *
     * Apps cannot send these, so a profile on one is either waiting on a system broadcast that
     * already has a first-class trigger here, or waiting forever. The editor warns rather than
     * refusing, because a runtime receiver genuinely can hear some of them.
     */
    fun isPlatformAction(action: String): Boolean {
        val value = action.lowercase(Locale.US)
        return value.startsWith("android.intent.action.") ||
            value.startsWith("android.net.") ||
            value.startsWith("android.provider.") ||
            value.startsWith("android.bluetooth.") ||
            value.startsWith("android.media.") ||
            value.startsWith("android.os.")
    }

    /**
     * The bounded, string-only view of an intent's extras.
     *
     * [lossy] is true when something was dropped: an oversized value, an unsupported type, or more
     * keys than [MAX_EXTRA_KEYS]. A task that branches on an extra needs to know the difference
     * between "absent" and "too big to carry".
     */
    data class SanitizedExtras(val values: Map<String, String>, val lossy: Boolean)

    fun sanitizeExtras(raw: Map<String, Any?>): SanitizedExtras {
        val values = linkedMapOf<String, String>()
        var lossy = false
        raw.entries.sortedBy { it.key }.forEach { (key, value) ->
            if (values.size >= MAX_EXTRA_KEYS) {
                lossy = true
                return@forEach
            }
            val name = key.trim()
            if (name.isEmpty() || name.length > MAX_EXTRA_KEY_CHARS || name.any { it.isWhitespace() || it.isISOControl() }) {
                lossy = true
                return@forEach
            }
            val rendered = renderExtra(value)
            if (rendered == null) {
                lossy = true
                return@forEach
            }
            values[name] = rendered
        }
        return SanitizedExtras(values, lossy)
    }

    /**
     * Strings, numbers and booleans only.
     *
     * Deliberately not Parcelable or Serializable: both run the sender's class-loading code inside
     * this process, which is exactly what an exported receiver must not do with untrusted input.
     */
    private fun renderExtra(value: Any?): String? {
        val rendered = when (value) {
            is String -> value
            is Boolean, is Int, is Long, is Short, is Byte, is Float, is Double -> value.toString()
            is Char -> value.toString()
            else -> return null
        }
        if (rendered.length > MAX_EXTRA_VALUE_CHARS) return null
        if (rendered.any { it.isISOControl() && it !in "\r\n\t" }) return null
        return rendered
    }

    fun buildEvent(
        action: String,
        senderPackage: String,
        extras: SanitizedExtras,
        nowMs: Long = System.currentTimeMillis(),
    ): ContextEvent = ContextEvent(
        type = "event",
        matched = true,
        metadata = buildMap {
            put("event", EVENT_BROADCAST)
            put("broadcast_action", action)
            put("broadcast_sender", senderPackage)
            put("broadcast_extra_count", extras.values.size.toString())
            if (extras.lossy) put("broadcast_extras_lossy", "true")
            extras.values.forEach { (key, value) -> put("broadcast_extra_$key", value) }
            put("observedAtEpochMs", nowMs.toString())
        },
    )

    internal fun readExtras(intent: Intent): SanitizedExtras {
        ForeignParcels.guard(intent)
        val bundle = runCatching { intent.extras }.getOrNull() ?: return SanitizedExtras(emptyMap(), lossy = false)
        val raw = linkedMapOf<String, Any?>()
        // keySet() does not unparcel values; each get() below is guarded by renderExtra's type
        // allowlist, and a bundle that fails to unparcel at all is reported as lossy.
        val keys = runCatching { bundle.keySet() }.getOrNull()
            ?: return SanitizedExtras(emptyMap(), lossy = true)
        keys.forEach { key -> raw[key] = runCatching { @Suppress("DEPRECATION") bundle.get(key) }.getOrNull() }
        return sanitizeExtras(raw)
    }

    internal fun resetForTests() {
        synchronized(lock) { registeredActions = emptySet() }
    }

    private const val TAG = "BroadcastContextEvents"
}

/** The distinct broadcast actions the given profiles declare, for [BroadcastContextEvents.sync]. */
fun declaredBroadcastActions(specs: Iterable<com.opentasker.core.model.ContextSpec>): Set<String> =
    specs.filter { spec ->
        spec.type == com.opentasker.core.model.ContextType.EVENT &&
            spec.config["event"].orEmpty().trim().lowercase(Locale.US) == BroadcastContextEvents.EVENT_BROADCAST
    }
        .mapNotNull { spec -> BroadcastContextEvents.normalizeAction(spec.config["action"]) }
        .toSet()
