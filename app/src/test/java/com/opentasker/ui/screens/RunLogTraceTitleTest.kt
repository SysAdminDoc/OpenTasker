package com.opentasker.ui.screens

import com.opentasker.app.R
import com.opentasker.core.actions.registerActionMetadata
import com.opentasker.core.engine.ActionTraceStatus
import com.opentasker.core.engine.RunLogActionDiagnostic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class RunLogTraceTitleTest {
    @Before
    fun registerMetadata() {
        // The app registers action names at startup; without it every lookup is null and the
        // keep-the-label cases would pass for the wrong reason.
        registerActionMetadata()
    }

    private fun trace(label: String, actionType: String) = RunLogActionDiagnostic(
        index = 0,
        status = ActionTraceStatus.SUCCESS,
        label = label,
        actionType = actionType,
        durationMs = 9,
        message = "Completed",
    )

    @Test
    fun unlabeledStepIsTitledWithTheActionName() {
        // TaskRunner records `spec.label ?: spec.type`, so an unlabeled step arrives with its type id as the label.
        assertEquals(R.string.catalog_action_notify_show_name, runLogTraceTitleRes(trace("notify.show", "notify.show")))
    }

    @Test
    fun stepWithItsOwnLabelKeepsIt() {
        assertNull(runLogTraceTitleRes(trace("Tell me the weather", "notify.show")))
    }

    @Test
    fun unknownTypeKeepsTheRecordedId() {
        // A type this build doesn't register (an action from a newer build, say) is more useful as its id than as "Unknown action".
        assertNull(runLogTraceTitleRes(trace("future.action", "future.action")))
    }
}
