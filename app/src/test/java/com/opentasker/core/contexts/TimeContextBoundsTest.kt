package com.opentasker.core.contexts

import com.opentasker.core.model.ContextSpec
import com.opentasker.core.model.ContextType
import com.opentasker.core.model.Variable
import java.util.TimeZone
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TimeContextBoundsTest {
    private val utc = TimeZone.getTimeZone("UTC")
    private val sun = ContextSpec(ContextType.TIME, mapOf("start" to "%Sunrise", "end" to "%Sunset"))
    private val tick = ContextEvent("time", true, mapOf("time" to "12:00"))

    @Test
    fun aTimeReadsAsTaskerReadsIt() {
        assertEquals("09:07", TimeContextBounds.clockFromValue("9.7", utc))
        assertEquals("21:05", TimeContextBounds.clockFromValue("21:05", utc))
        assertEquals("06:42", TimeContextBounds.clockFromValue(" 06.42 ", utc))
        // 1700000000 is 2023-11-14 22:13:20 UTC, and only the hour and minute count.
        assertEquals("22:13", TimeContextBounds.clockFromValue("1700000000", utc))
        assertEquals("17:13", TimeContextBounds.clockFromValue("1700000000", TimeZone.getTimeZone("America/New_York")))
    }

    @Test
    fun anythingElseIsNoTime() {
        listOf("25.00", "9.70", "abc", "", "9:", "-5", "12345678901234").forEach { value ->
            assertNull(value, TimeContextBounds.clockFromValue(value, utc))
        }
    }

    @Test
    fun onlyAGlobalCanBoundAWindow() {
        assertEquals("Sunrise", TimeContextBounds.variableName("%Sunrise"))
        assertNull(TimeContextBounds.variableName("%sunrise"))
        assertNull(TimeContextBounds.variableName("09:00"))
        assertEquals(
            mapOf(TimeContextBounds.START_VALUE to "Sunrise", TimeContextBounds.END_VALUE to "Sunset"),
            TimeContextBounds.variableBounds(sun),
        )
        assertEquals(emptyMap<String, String>(), TimeContextBounds.variableBounds(ContextSpec(ContextType.TIME, mapOf("start" to "09:00", "end" to "17:00"))))
        assertEquals(emptyMap<String, String>(), TimeContextBounds.variableBounds(ContextSpec(ContextType.DAY, mapOf("start" to "%Sunrise"))))
    }

    @Test
    fun theEventCarriesEachGlobalsTime() {
        val resolved = TimeContextBounds.withVariableValues(sun, tick, mapOf("Sunrise" to "6.42", "Sunset" to "1700000000"), utc)

        assertEquals("06:42", resolved.metadata[TimeContextBounds.START_VALUE])
        assertEquals("22:13", resolved.metadata[TimeContextBounds.END_VALUE])
        assertEquals("12:00", resolved.metadata["time"])
    }

    @Test
    fun aGlobalThatStopsHoldingATimeTakesItsValueAway() {
        val earlier = TimeContextBounds.withVariableValues(sun, tick, mapOf("Sunrise" to "6.42", "Sunset" to "16.51"), utc)

        val later = TimeContextBounds.withVariableValues(sun, earlier, mapOf("Sunrise" to "soon", "Sunset" to "16.51"), utc)

        assertNull(later.metadata[TimeContextBounds.START_VALUE])
        assertEquals("16:51", later.metadata[TimeContextBounds.END_VALUE])
    }

    @Test
    fun theWindowMovesWhenABoundingGlobalChanges() = runBlocking {
        val globals = flow {
            emit(mapOf("Sunrise" to "6.42", "Sunset" to "16.51"))
            emit(mapOf("Sunrise" to "6.42", "Sunset" to "16.51", "Unrelated" to "x"))
            emit(mapOf("Sunrise" to "7.00", "Sunset" to "16.51"))
        }

        val events = TimeContextBounds.resolving(sun, flowOf(tick), globals).toList()

        assertEquals("07:00", events.last().metadata[TimeContextBounds.START_VALUE])
        assertTrue("an unrelated global must not re-evaluate the context", events.zipWithNext().none { (a, b) -> a == b })
    }

    @Test
    fun globalsGroupByProject() {
        val values = TimeContextBounds.valuesByProject(
            listOf(
                Variable(name = "Sunrise", value = "6.42", isGlobal = true, projectId = 1L),
                Variable(name = "Sunrise", value = "7.10", isGlobal = true, projectId = 2L),
                Variable(name = "local", value = "x", isGlobal = false, projectId = 1L),
            ),
        )

        assertEquals(mapOf(1L to mapOf("Sunrise" to "6.42"), 2L to mapOf("Sunrise" to "7.10")), values)
    }
}
