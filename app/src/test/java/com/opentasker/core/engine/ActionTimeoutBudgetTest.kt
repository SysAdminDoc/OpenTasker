package com.opentasker.core.engine

import com.opentasker.core.actions.CONTACT_PICKER_TIMEOUT_MS
import com.opentasker.core.actions.HomeAssistantWebhookConfig
import com.opentasker.core.actions.HomeAssistantWebhookProtocol
import com.opentasker.core.scripting.TermuxScriptPolicy
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The engine stops any step that outlives its budget and logs "timed out after Ns". A step that
 * carries its own, longer limit has to fit inside that budget, or the engine cuts off work the
 * user configured and the step's own message never reaches the run log.
 */
class ActionTimeoutBudgetTest {

    @Test
    fun aSubTaskCutOffFromAboveReportsTheLimitThatFired() = runBlocking {
        val result = runStepWithinLimit(1_000) {
            runStepWithinLimit(60_000) {
                delay(60_000)
                ActionResult.Success
            }
        }

        assertEquals("timed out after 1s", (result as ActionResult.Failure).message)
    }

    @Test
    fun aLimitAnActionSetForItselfFailsThatStepAlone() = runBlocking {
        val result = runStepWithinLimit(60_000) {
            withTimeout(10) { delay(60_000) }
            ActionResult.Success
        }

        assertTrue((result as ActionResult.Failure).message.startsWith("timed out:"))
    }

    @Test
    fun aScriptMayRunForItsWholeConfiguredTimeout() {
        val longestScript = TermuxScriptPolicy.HASH_TIMEOUT_MS + TermuxScriptPolicy.MAX_TIMEOUT_MS

        assertTrue(actionTimeoutMs("script.termux.run") > longestScript)
        assertTrue(actionTimeoutMs("script.shizuku.run") > longestScript)
    }

    @Test
    fun theContactPickerStaysOpenForItsFullWait() {
        assertTrue(actionTimeoutMs("contacts.lookup") > CONTACT_PICKER_TIMEOUT_MS)
    }

    @Test
    fun aWebhookMayUseEveryRetryItWasGiven() {
        val slowest = HomeAssistantWebhookConfig(
            url = "https://ha.example/api/webhook/x",
            payload = "{}",
            timeoutSeconds = 30,
            maxRetries = HomeAssistantWebhookProtocol.MAX_RETRIES,
            backoffMilliseconds = HomeAssistantWebhookProtocol.MAX_BACKOFF_MILLISECONDS,
            allowHttp = false,
        )
        val attempts = (slowest.maxRetries + 1) * slowest.timeoutSeconds * 1_000L
        val waits = (0 until slowest.maxRetries).sumOf {
            HomeAssistantWebhookProtocol.retryDelayMilliseconds(slowest, it)
        }

        assertTrue(actionTimeoutMs("integration.home_assistant.webhook") > attempts + waits)
    }

    @Test
    fun ordinaryStepsKeepTheShortBudget() {
        assertTrue(actionTimeoutMs("notify.show") <= 60_000L)
    }
}
