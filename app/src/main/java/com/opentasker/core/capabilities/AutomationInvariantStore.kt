package com.opentasker.core.capabilities

import android.content.Context
import android.content.SharedPreferences
import com.opentasker.core.model.AutomationInvariant
import com.opentasker.core.model.AutomationInvariantPolicy
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Device-local persistence for the optional diagnostics policy; it is never read by the engine. */
class AutomationInvariantStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    fun load(): List<AutomationInvariant> = synchronized(this) {
        preferences.getString(KEY_INVARIANTS, null)
            ?.let { encoded -> runCatching { json.decodeFromString<List<AutomationInvariant>>(encoded) }.getOrNull() }
            ?.let(AutomationInvariantPolicy::normalize)
            .orEmpty()
    }

    fun save(invariants: List<AutomationInvariant>): List<AutomationInvariant> = synchronized(this) {
        val normalized = AutomationInvariantPolicy.normalize(invariants)
        preferences.edit().putString(KEY_INVARIANTS, json.encodeToString(normalized)).apply()
        normalized
    }

    fun merge(invariants: List<AutomationInvariant>): List<AutomationInvariant> = save(load() + invariants)

    /** The saved list now and after every write, so an import reaches screens that are already open. */
    fun observe(): Flow<List<AutomationInvariant>> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == KEY_INVARIANTS) trySend(load())
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        trySend(load())
        awaitClose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    private companion object {
        const val PREFERENCES_NAME = "automation_invariants"
        const val KEY_INVARIANTS = "invariants"
        val json = Json {
            encodeDefaults = true
            explicitNulls = false
            ignoreUnknownKeys = false
        }
    }
}
