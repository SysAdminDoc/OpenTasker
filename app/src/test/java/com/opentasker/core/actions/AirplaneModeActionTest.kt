package com.opentasker.core.actions

import com.opentasker.core.engine.ActionResult
import com.opentasker.core.power.ShizukuCommandPolicy
import org.junit.Assert.assertEquals
import org.junit.Test

class AirplaneModeActionTest {
    @Test
    fun onAndOffReachTheCommandThatTellsTheRadios() {
        assertEquals(
            listOf("cmd", "connectivity", "airplane-mode", "enable"),
            ShizukuCommandPolicy.command("airplane.toggle", 0),
        )
        assertEquals(
            listOf("cmd", "connectivity", "airplane-mode", "disable"),
            ShizukuCommandPolicy.command("airplane.toggle", 1),
        )
    }

    @Test
    fun theSettingReachingItsTargetCountsAsDone() {
        assertEquals(ActionResult.Success, airplaneModeOutcome(target = true, applied = true))
        assertEquals(ActionResult.Success, airplaneModeOutcome(target = false, applied = false))
    }

    @Test
    fun aBuildThatIgnoresTheCommandIsReportedAsAFailure() {
        assertEquals(
            ActionResult.Failure("Airplane mode is still off. This version of Android ignored the command."),
            airplaneModeOutcome(target = true, applied = false),
        )
        assertEquals(
            ActionResult.Failure("Airplane mode is still on. This version of Android ignored the command."),
            airplaneModeOutcome(target = false, applied = true),
        )
    }
}
