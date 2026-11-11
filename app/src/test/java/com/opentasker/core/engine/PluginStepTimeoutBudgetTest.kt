package com.opentasker.core.engine

import com.opentasker.core.actions.ActionMetadataRegistry
import com.opentasker.core.plugins.locale.LocalePluginContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A plugin step waits for its plugin's answer as long as its timeout says, so the engine's budget
 * for the step has to outlast the longest wait the step accepts, or its own timeout message is lost.
 */
class PluginStepTimeoutBudgetTest {

    @Test
    fun theEngineOutlastsTheLongestPluginWait() {
        assertTrue(actionTimeoutMs("plugin.locale.fire") > LocalePluginContract.MAX_SETTING_WAIT_MS)
    }

    @Test
    fun theStepAcceptsNoWaitUpToTheLongestOne() {
        val rule = requireNotNull(ActionMetadataRegistry.get("plugin.locale.fire"))
            .fields.single { it.key == "timeoutMs" }
            .let { requireNotNull(it.numberRule) }

        assertEquals(0.0, rule.minimum)
        assertEquals(LocalePluginContract.MAX_SETTING_WAIT_MS.toDouble(), rule.maximum)
    }
}
