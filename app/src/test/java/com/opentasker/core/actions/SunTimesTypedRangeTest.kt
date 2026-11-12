package com.opentasker.core.actions

import android.content.ContextWrapper
import com.opentasker.core.engine.ActionContext
import com.opentasker.core.engine.ActionResult
import com.opentasker.core.engine.VariableStore
import com.opentasker.core.registerCoreRuntime
import java.util.TimeZone
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/** A typed coordinate out of range fails with the range error, before any location is asked for. */
class SunTimesTypedRangeTest {
    private var permissionAsked = 0
    private var fixAsked = 0

    @Before
    fun setUp() {
        registerCoreRuntime()
    }

    private fun run(vararg args: Pair<String, String>, permitted: Boolean): ActionResult {
        val action = SunTimesAction(
            permitted = { permissionAsked++; permitted },
            locate = { fixAsked++; 40.0 to -74.0 },
            nowMs = { 1_750_841_218_000L },
            zone = { TimeZone.getTimeZone("America/New_York") },
        )
        return runBlocking { action.run(ActionContext(ContextWrapper(null), VariableStore()), mapOf(*args)) }
    }

    @Test
    fun aTypedLatitudeOutOfRangeFailsWithoutWaitingForTheLongitudeFix() {
        val result = run("latitude" to "91", permitted = true)
        assertEquals("latitude must be between -90 and 90", (result as ActionResult.Failure).message)
        assertEquals(0, permissionAsked)
        assertEquals(0, fixAsked)
    }

    @Test
    fun withoutLocationAccessTheRangeErrorStillWins() {
        val result = run("longitude" to "-181", permitted = false)
        assertEquals("longitude must be between -180 and 180", (result as ActionResult.Failure).message)
        assertEquals(0, permissionAsked)
    }

    @Test
    fun aTypedCoordinateInRangeStillFillsTheOtherFromTheFix() {
        val result = run("latitude" to "40.5", permitted = true)
        assertEquals(ActionResult.Success, result)
        assertEquals(1, fixAsked)
    }
}
