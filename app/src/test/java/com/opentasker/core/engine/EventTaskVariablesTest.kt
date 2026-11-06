package com.opentasker.core.engine

import android.content.ContextWrapper
import com.opentasker.core.actions.typedVariableOptions
import com.opentasker.core.contexts.ContextEvent
import com.opentasker.core.contexts.NotificationContextEvents
import com.opentasker.core.contexts.SmsContextEvents
import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.Task
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Issue #27: a profile could match an SMS but its task couldn't read the message. */
class EventTaskVariablesTest {
    private val received = mutableListOf<Map<String, String>>()

    @Before
    fun setUp() {
        received.clear()
        ActionRegistry.register(object : Action {
            override val id = "test.event.forward"
            override val category = ActionCategory.FLOW
            override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
                received += args
                return ActionResult.Success
            }
        })
    }

    private val sms = SmsContextEvents.buildSmsEvent(
        parts = listOf(
            SmsContextEvents.SmsPart("+15551234567", "Card ending 1234 "),
            SmsContextEvents.SmsPart(null, "charged 40 dollars"),
        ),
        subscriptionId = 2,
        sdkInt = 36,
    )

    @Test
    fun anSmsHandsItsTaskTheBoundedFieldsAndNothingElse() {
        assertEquals(
            mapOf(
                "sms_sender" to "+15551234567",
                "sms_body" to "Card ending 1234 charged 40 dollars",
                "sms_kind" to "sms",
                "sms_message_count" to "2",
                "sms_subscription_id" to "2",
            ),
            EventTaskVariables.from(sms),
        )
    }

    @Test
    fun aLongMessageStaysAtTheBoundItWasMatchedWith() {
        val long = SmsContextEvents.buildSmsEvent(listOf(SmsContextEvents.SmsPart("a", "x".repeat(5_000))), sdkInt = 36)

        assertEquals(240, EventTaskVariables.from(long).getValue("sms_body").length)
    }

    @Test
    fun anMmsExposesNoPayloadFields() {
        val mms = SmsContextEvents.buildMmsEvent("application/vnd.wap.mms-message", "tx-1", sdkInt = 36)

        val variables = EventTaskVariables.from(mms)

        assertEquals("mms", variables["sms_kind"])
        assertEquals("", variables["sms_body"])
        assertFalse(variables.keys.any { "transaction" in it || "content" in it })
    }

    @Test
    fun aNotificationHandsItsTaskThePackageTitleAndBody() {
        val event = NotificationContextEvents.buildEvent("com.example.bank", "Alert", "Payment made")

        assertEquals(
            mapOf(
                "notification_package" to "com.example.bank",
                "notification_title" to "Alert",
                "notification_body" to "Payment made",
            ),
            EventTaskVariables.from(event),
        )
    }

    @Test
    fun otherEventsStillHandOverWhatTheyDid() {
        val share = ContextEvent(type = "event", matched = true, metadata = mapOf("event" to "share", "text" to "hi"))
        assertEquals("hi", EventTaskVariables.from(share)["share_text"])
        assertEquals("true", EventTaskVariables.from(share)["share_event"])

        val broadcast = ContextEvent(
            type = "event",
            matched = true,
            metadata = mapOf("event" to "broadcast", "broadcast_action" to "a", "broadcast_extra_mode" to "on"),
        )
        assertEquals("on", EventTaskVariables.from(broadcast)["broadcast_extra_mode"])

        val other = ContextEvent(type = "event", matched = true, metadata = mapOf("event" to "usb", "body" to "x"))
        assertEquals(emptyMap<String, String>(), EventTaskVariables.from(other))
        assertEquals(emptyMap<String, String>(), EventTaskVariables.from(null))
    }

    /** The webhook case from the report: the request gets the message, the run log doesn't. */
    @Test
    fun anActionReceivesTheMessageWhileTheRunLogShowsAPlaceholder() {
        val eventVariables = EventTaskVariables.from(sms)
        val store = VariableStore().apply {
            eventVariables.forEach { (name, value) -> set(name, value, EventTaskVariables.isSensitive(name)) }
        }

        val report = runBlocking {
            TaskRunner(ActionContext(ContextWrapper(null), store, eventVariables = eventVariables)).run(
                Task(
                    name = "Forward",
                    actions = listOf(
                        ActionSpec(
                            type = "test.event.forward",
                            args = mapOf(
                                "scoped" to "{{ event.sms_body }}",
                                "bare" to "{{ sms_sender }}",
                                "legacy" to "%sms_body",
                                "plain" to "{{ event.sms_kind }}",
                            ),
                        ),
                    ),
                ),
            )
        }

        assertTrue(report.success)
        val args = received.single()
        assertEquals("Card ending 1234 charged 40 dollars", args["scoped"])
        assertEquals("+15551234567", args["bare"])
        assertEquals("Card ending 1234 charged 40 dollars", args["legacy"])
        val summary = report.traces.single().expandedArgSummary.orEmpty()
        assertFalse(summary, "Card ending" in summary)
        assertFalse(summary, "+15551234567" in summary)
        assertTrue(summary, "plain=sms" in summary)
    }

    @Test
    fun aHeldTriggerDoesNotStoreWhoTextedOrWhatTheySaid() {
        val task = Task(id = 7, name = "Forward", actions = emptyList())
        val encoded = HeldExecutionPayloadCodec.encode(
            task = task,
            envelope = ExecutionEnvelope.create(task, "Profile", executionId = "held-sms"),
            metadata = emptyList(),
            initialVariables = EventTaskVariables.from(sms) +
                EventTaskVariables.from(NotificationContextEvents.buildEvent("com.example", "From Alex", "Hello")),
        )

        assertFalse(encoded, "+15551234567" in encoded)
        assertFalse(encoded, "Card ending" in encoded)
        assertFalse(encoded, "From Alex" in encoded)
        assertFalse(encoded, "Hello" in encoded)
        assertEquals(HeldExecutionPayloadCodec.REDACTED_VALUE, HeldExecutionPayloadCodec.decode(encoded)!!.initialVariables["sms_sender"])
    }

    @Test
    fun theEditorOffersTheFieldsAsEventChips() {
        val references = typedVariableOptions(actions = emptyList()).map { it.reference }

        assertTrue("{{ event.sms_sender }}" in references)
        assertTrue("{{ event.sms_body }}" in references)
        assertTrue("{{ event.notification_title }}" in references)
    }
}
