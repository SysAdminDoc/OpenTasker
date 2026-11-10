package com.opentasker.core.contexts

import com.opentasker.core.contexts.NfcTagIntentGate.State
import org.junit.Assert.assertEquals
import org.junit.Test

class NfcTagIntentGateTest {
    private class FakePreference(
        private val supported: Boolean = true,
        private val allowed: Boolean,
    ) : NfcTagIntentGate.TagIntentPreference {
        var reads = 0

        override fun supported(): Boolean {
            reads++
            return supported
        }

        override fun allowed(): Boolean {
            reads++
            return allowed
        }
    }

    @Test
    fun androidSixteenWithTheSwitchOffIsBlocked() {
        assertEquals(State.BLOCKED, NfcTagIntentGate.state(36, FakePreference(allowed = false)))
        assertEquals(State.BLOCKED, NfcTagIntentGate.state(37, FakePreference(allowed = false)))
    }

    @Test
    fun allowingItClearsTheGap() {
        assertEquals(State.ALLOWED, NfcTagIntentGate.state(37, FakePreference(allowed = true)))
    }

    @Test
    fun beforeAndroidSixteenTheAdapterIsNeverAsked() {
        val preference = FakePreference(allowed = false)

        assertEquals(State.NOT_APPLICABLE, NfcTagIntentGate.state(35, preference))
        assertEquals(0, preference.reads)
    }

    @Test
    fun noNfcHardwareOrNoPerAppSwitchIsNotApplicable() {
        assertEquals(State.NOT_APPLICABLE, NfcTagIntentGate.state(37, null))
        assertEquals(State.NOT_APPLICABLE, NfcTagIntentGate.state(37, FakePreference(supported = false, allowed = false)))
    }
}
