package com.opentasker.core.plugins.locale

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import com.opentasker.core.contexts.ContextEvent
import com.opentasker.core.platform.ForeignParcels
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

object LocalePluginRequestQueryEvents {
    private const val EVENT_NAME = "locale_request_query"
    private const val MAX_ACTIVITY_CLASS_CHARS = 240
    private val classNamePattern = Regex("^[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)+$")

    fun events(app: Context): Flow<ContextEvent> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val event = intent?.let(::buildEventFromIntent) ?: return
                trySend(event)
            }
        }
        val filter = IntentFilter(LocalePluginContract.ACTION_REQUEST_QUERY)

        ContextCompat.registerReceiver(app, receiver, filter, ContextCompat.RECEIVER_EXPORTED)

        awaitClose {
            runCatching { app.unregisterReceiver(receiver) }
        }
    }

    fun buildEvent(
        activityClassName: String,
        bundleValues: Map<String, String> = emptyMap(),
    ): ContextEvent? {
        val normalizedClass = normalizeActivityClassName(activityClassName) ?: return null
        return ContextEvent(
            type = "event",
            matched = true,
            metadata = mapOf(
                "event" to EVENT_NAME,
                "activityClass" to normalizedClass,
                "bundleJson" to LocalePluginBundleCodec.encodeStringBundle(bundleValues),
            ),
        )
    }

    fun buildEventFromIntent(intent: Intent): ContextEvent? {
        if (intent.action != LocalePluginContract.ACTION_REQUEST_QUERY) return null
        ForeignParcels.guard(intent)
        return runCatching {
            val activityClassName = requestedActivityClass(intent::getStringExtra)
            val bundle = intent.getBundleExtra(LocalePluginContract.EXTRA_BUNDLE)
            val bundleValues = bundle?.let(LocalePluginBundleCodec::fromBundle).orEmpty()
            buildEvent(activityClassName, bundleValues)
        }.getOrNull()
    }

    /** The class a `REQUEST_QUERY` names, read from the standard extra first, then the spelled-out one. */
    fun requestedActivityClass(extra: (String) -> String?): String =
        listOf(
            LocalePluginContract.EXTRA_STRING_ACTIVITY_CLASS_NAME,
            LocalePluginContract.EXTRA_ACTIVITY_CLASS_NAME_SPELLED_OUT,
        ).firstNotNullOfOrNull { key -> extra(key)?.takeIf { it.isNotBlank() } }.orEmpty()

    fun normalizeActivityClassName(value: String): String? {
        val normalized = value.trim().take(MAX_ACTIVITY_CLASS_CHARS)
        if (normalized.isBlank()) return null
        return normalized.takeIf { classNamePattern.matches(it) }
    }
}
