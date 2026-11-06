package com.opentasker.core.contexts

import com.opentasker.core.contexts.IncomingCallGate.Step
import com.opentasker.core.engine.EventTaskVariables
import com.opentasker.core.model.ContextSpec
import com.opentasker.core.model.ContextType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Issue #19: a profile has to be able to tell who is calling while the phone rings. */
class CallContextEventsTest {
    private val number = "+15551234567"

    @Test
    fun theNumberFiresOnceWhicheverBroadcastComesFirst() {
        val numberFirst = IncomingCallGate()
        assertEquals(Step.Emit(number), numberFirst.onBroadcast(true, number))
        assertEquals(Step.Ignore, numberFirst.onBroadcast(true, null))
        assertFalse(numberFirst.onWaitElapsed())

        val numberSecond = IncomingCallGate()
        assertEquals(Step.Wait, numberSecond.onBroadcast(true, null))
        assertEquals(Step.Emit(number), numberSecond.onBroadcast(true, number))
        assertFalse("The wait must not fire a second event", numberSecond.onWaitElapsed())
    }

    @Test
    fun aRepeatedBroadcastDoesNotFireAgain() {
        val gate = IncomingCallGate()
        gate.onBroadcast(true, number)

        assertEquals(Step.Ignore, gate.onBroadcast(true, number))
    }

    @Test
    fun aCallWithNoNumberStillFiresAfterTheWait() {
        val gate = IncomingCallGate()

        assertEquals(Step.Wait, gate.onBroadcast(true, null))
        assertEquals(Step.Ignore, gate.onBroadcast(true, ""))
        assertTrue(gate.onWaitElapsed())
        assertFalse(gate.onWaitElapsed())
    }

    @Test
    fun aCallThatStopsRingingDuringTheWaitDoesNotFire() {
        val gate = IncomingCallGate()
        gate.onBroadcast(true, null)

        assertEquals(Step.Reset, gate.onBroadcast(false, null))
        assertFalse(gate.onWaitElapsed())
    }

    @Test
    fun theNextCallFiresAgainIncludingACallWaiting() {
        val gate = IncomingCallGate()
        gate.onBroadcast(true, number)
        gate.onBroadcast(false, null)

        assertEquals(Step.Emit("+15559876543"), gate.onBroadcast(true, "+15559876543"))
    }

    @Test
    fun aWithheldCallerHasNoNumberToSearch() {
        listOf(null, "", "   ", "Private", "Unknown", "-1", "-2").forEach { raw ->
            assertEquals("<$raw>", "", CallContextEvents.usableNumber(raw))
        }
        assertEquals(number, CallContextEvents.usableNumber(" $number "))
        assertEquals("911", CallContextEvents.usableNumber("911"))
    }

    @Test
    fun onlyACheckedNumberCanBeCalledNotAContact() {
        fun known(number: String, known: Boolean?) =
            CallContextEvents.buildEvent(number, known).metadata["knownContact"]

        assertEquals("true", known(number, true))
        assertEquals("false", known(number, false))
        assertEquals("unknown", known(number, null))
        assertEquals("unknown", known("", false))
    }

    @Test
    fun aProfileCanAskForCallersNotInContacts() {
        val spec = ContextSpec(
            type = ContextType.EVENT,
            config = mapOf("event" to "incoming_call", "knownContact" to "false"),
        )

        assertTrue(ContextMatchEvaluator.matches(spec, CallContextEvents.buildEvent(number, false)))
        assertFalse(ContextMatchEvaluator.matches(spec, CallContextEvents.buildEvent(number, true)))
        assertFalse(
            "Contacts access denied must not read as a stranger",
            ContextMatchEvaluator.matches(spec, CallContextEvents.buildEvent(number, null)),
        )
        assertFalse(
            "A withheld caller must not be searched",
            ContextMatchEvaluator.matches(spec, CallContextEvents.buildEvent("", null)),
        )
    }

    @Test
    fun aProfileCanFilterByNumber() {
        val spec = ContextSpec(
            type = ContextType.EVENT,
            config = mapOf("event" to "incoming_call", "number" to "555123"),
        )

        assertTrue(ContextMatchEvaluator.matches(spec, CallContextEvents.buildEvent(number, null)))
        assertFalse(ContextMatchEvaluator.matches(spec, CallContextEvents.buildEvent("+14045550000", null)))
    }

    @Test
    fun aTaskGetsTheNumberAsASensitiveVariable() {
        val variables = EventTaskVariables.from(CallContextEvents.buildEvent(number, false))

        assertEquals(mapOf("incoming_number" to number, "incoming_known_contact" to "false"), variables)
        assertTrue(EventTaskVariables.isSensitive("incoming_number"))
        assertFalse(EventTaskVariables.isSensitive("incoming_known_contact"))
    }

    @Test
    fun theReceiverFollowsTheProfiles() {
        val call = ContextSpec(type = ContextType.EVENT, config = mapOf("event" to "Incoming_Call"))
        val other = ContextSpec(type = ContextType.EVENT, config = mapOf("event" to "sms_received"))
        val state = ContextSpec(type = ContextType.STATE, config = mapOf("event" to "incoming_call"))

        assertTrue(declaresIncomingCall(listOf(other, call)))
        assertFalse(declaresIncomingCall(listOf(other, state)))
    }
}
