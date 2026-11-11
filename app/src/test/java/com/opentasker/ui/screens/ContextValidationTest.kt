package com.opentasker.ui.screens

import com.opentasker.core.actions.ActionFieldPolicy
import com.opentasker.core.model.ContextType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextValidationTest {
    @Test
    fun anAppContextCanNameSeveralApps() {
        assertFalse(contextHasInvalidValues(ContextType.APPLICATION, mapOf("package" to "com.example.a, com.example.b")))
        assertTrue(contextHasInvalidValues(ContextType.APPLICATION, mapOf("package" to ",")))
        assertTrue(contextHasInvalidValues(ContextType.APPLICATION, mapOf("package" to "com.example.a,not a package")))
        assertTrue(contextHasInvalidValues(ContextType.EVENT, mapOf("package" to "com.example.a,com.example.b")))
    }

    @Test
    fun validTimeWindowIsAccepted() {
        assertFalse(
            contextHasInvalidValues(ContextType.TIME, mapOf("start" to "08:30", "end" to "17:00")),
        )
    }

    @Test
    fun garbledTimeIsRejected() {
        assertTrue(contextHasInvalidValues(ContextType.TIME, mapOf("start" to "banana", "end" to "17:00")))
        assertTrue(contextHasInvalidValues(ContextType.TIME, mapOf("start" to "08:30", "end" to "25:99")))
        assertTrue(contextHasInvalidValues(ContextType.TIME, mapOf("start" to "8", "end" to "17:00")))
    }

    @Test
    fun aGlobalCanBoundATimeWindowButALocalCant() {
        assertFalse(contextHasInvalidValues(ContextType.TIME, mapOf("start" to "%Sunrise", "end" to "%Sunset")))
        assertFalse(contextHasInvalidValues(ContextType.TIME, mapOf("start" to "%Wake", "end" to "17:00")))
        assertEquals(
            setOf("start"),
            contextInvalidFields(ContextType.TIME, mapOf("start" to "%sunrise", "end" to "%Sunset")).keys,
        )
    }

    @Test
    fun aLocalTimeBoundSaysAGlobalIsNeeded() {
        assertEquals(
            mapOf("start" to ActionFieldPolicy.Issue(ActionFieldPolicy.Error.TIME_VARIABLE_NOT_GLOBAL)),
            contextInvalidFields(ContextType.TIME, mapOf("start" to "%sunrise", "end" to "%Sunset")),
        )
        assertEquals(
            mapOf("end" to ActionFieldPolicy.Issue(ActionFieldPolicy.Error.INVALID_TIME)),
            contextInvalidFields(ContextType.TIME, mapOf("start" to "%Sunrise", "end" to "25:00")),
        )
    }

    @Test
    fun blankTimeIsNotFlaggedHere() {
        // Required-but-blank is handled by the missingRequired gate, not this one.
        assertFalse(contextHasInvalidValues(ContextType.TIME, emptyMap()))
    }

    @Test
    fun outOfRangeCoordinatesAreRejected() {
        assertTrue(
            contextHasInvalidValues(
                ContextType.LOCATION,
                mapOf("latitude" to "999", "longitude" to "0", "radiusMeters" to "100"),
            ),
        )
        assertTrue(
            contextHasInvalidValues(
                ContextType.LOCATION,
                mapOf("latitude" to "1.2.3", "longitude" to "0", "radiusMeters" to "100"),
            ),
        )
        assertTrue(
            contextHasInvalidValues(
                ContextType.LOCATION,
                mapOf("latitude" to "40", "longitude" to "-200", "radiusMeters" to "100"),
            ),
        )
    }

    @Test
    fun validCoordinatesAreAccepted() {
        assertFalse(
            contextHasInvalidValues(
                ContextType.LOCATION,
                mapOf("latitude" to "40.7128", "longitude" to "-74.0060", "radiusMeters" to "150"),
            ),
        )
    }

    @Test
    fun eventLatLongRangeIsValidated() {
        assertTrue(contextHasInvalidValues(ContextType.EVENT, mapOf("latitude" to "91")))
        assertFalse(contextHasInvalidValues(ContextType.EVENT, mapOf("latitude" to "40", "longitude" to "-74")))
    }

    @Test
    fun packageBearingContextsRejectMalformedManualEntries() {
        assertFalse(contextHasInvalidValues(ContextType.APPLICATION, mapOf("package" to "com.example.app")))
        assertTrue(contextHasInvalidValues(ContextType.APPLICATION, mapOf("package" to "not a package")))
        assertTrue(contextHasInvalidValues(ContextType.PLUGIN, mapOf("package" to "plugin")))
        assertFalse(contextHasInvalidValues(ContextType.EVENT, mapOf("package" to "")))
    }

    @Test
    fun eachInvalidFieldIsNamedWithTheReasonTheEditorShowsUnderIt() {
        // A-328: a single Boolean greyed out Save while every field looked fine.
        assertEquals(
            mapOf("start" to ActionFieldPolicy.Issue(ActionFieldPolicy.Error.INVALID_TIME)),
            contextInvalidFields(ContextType.TIME, mapOf("start" to "25:00", "end" to "09:00")),
        )
        assertEquals(
            mapOf(
                "latitude" to ActionFieldPolicy.Issue(ActionFieldPolicy.Error.ABOVE_MAXIMUM, 90.0),
                "longitude" to ActionFieldPolicy.Issue(ActionFieldPolicy.Error.INVALID_NUMBER),
                "radiusMeters" to ActionFieldPolicy.Issue(ActionFieldPolicy.Error.BELOW_MINIMUM, 0.0),
            ),
            contextInvalidFields(
                ContextType.LOCATION,
                mapOf("latitude" to "91", "longitude" to "east", "radiusMeters" to "-5"),
            ),
        )
        assertEquals(
            setOf("package", "component"),
            contextInvalidFields(ContextType.APPLICATION, mapOf("package" to "not a package", "component" to "bad name!")).keys,
        )
        assertTrue(contextInvalidFields(ContextType.TIME, mapOf("start" to "08:30", "end" to "17:00")).isEmpty())
    }
}
