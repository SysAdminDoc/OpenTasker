package com.opentasker.core.contexts

import android.content.Context
import android.content.Intent
import android.nfc.NfcAdapter
import android.os.Build
import androidx.annotation.RequiresApi

/**
 * Android 16's "Allow apps to read NFC tags" switch, kept per app by NfcService.
 *
 * A fresh install starts with it off: the first tag scan launches nothing and NfcService only
 * posts a notification asking the user to allow OpenTasker. Until they tap it, an NFC profile
 * never fires and nothing in OpenTasker said why. Setup, Context Inspector and Diagnostics read
 * the switch through [current].
 */
object NfcTagIntentGate {
    const val MIN_SDK = 36

    enum class State(val label: String) {
        /** Older Android, no NFC hardware, or an NFC stack without the per-app switch. */
        NOT_APPLICABLE("not applicable"),
        ALLOWED("allowed"),
        BLOCKED("blocked by Android's \"Allow apps to read NFC tags\" setting"),
    }

    /** The two adapter calls the switch needs, so a JVM test can stand in for NfcAdapter. */
    interface TagIntentPreference {
        fun supported(): Boolean
        fun allowed(): Boolean
    }

    /** [preference] is null without NFC hardware, and is never read below [MIN_SDK]. */
    fun state(sdkInt: Int, preference: TagIntentPreference?): State = when {
        sdkInt < MIN_SDK || preference == null || !preference.supported() -> State.NOT_APPLICABLE
        preference.allowed() -> State.ALLOWED
        else -> State.BLOCKED
    }

    fun current(context: Context): State = state(Build.VERSION.SDK_INT, platformPreference(context))

    /** The system page holding the switch. Only Android 16 and later has it. */
    @RequiresApi(MIN_SDK)
    fun settingsIntent(): Intent = Intent(NfcAdapter.ACTION_CHANGE_TAG_INTENT_PREFERENCE)

    private fun platformPreference(context: Context): TagIntentPreference? {
        if (Build.VERSION.SDK_INT < MIN_SDK) return null
        val adapter = NfcAdapter.getDefaultAdapter(context) ?: return null
        return PlatformPreference(adapter)
    }

    @RequiresApi(MIN_SDK)
    private class PlatformPreference(private val adapter: NfcAdapter) : TagIntentPreference {
        override fun supported(): Boolean = adapter.isTagIntentAppPreferenceSupported
        override fun allowed(): Boolean = adapter.isTagIntentAllowed
    }
}
