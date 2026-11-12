package com.opentasker.core.engine

import com.opentasker.core.engine.variables.TaskerBuiltIns
import com.opentasker.core.platform.TASKER_VOLUME_STREAMS
import com.opentasker.core.platform.taskerLatLon
import com.opentasker.core.transfer.TaskerXmlImporter
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The next most-read built-ins in imported tasks: `%PACTIVE`, `%PENABLED` and `%TRUN` list profile
 * and task names the way Tasker 6.7.8 does (decompiled `MonitorService.L7`, `M7` and `aa`,
 * `ExecuteService.M4`), the `%VOL` ones read the streams Tasker's `dq` reads, and `%LOC`/`%LOCN`
 * show a fix as `m7.g1` does.
 */
class TaskerBuiltInListsTest {
    @Before
    fun setUp() {
        TaskerBuiltIns.resetForTests()
        AutomationLiveConditionState.clear()
        ActiveExecutionRegistry.reset()
    }

    @After
    fun tearDown() {
        TaskerBuiltIns.resetForTests()
        AutomationLiveConditionState.clear()
        ActiveExecutionRegistry.reset()
    }

    @Test
    fun enabledProfilesAreListedOnceEachInTheOrderTheyWereMade() {
        TaskerBuiltIns.watchProfiles(mapOf(7L to "Night", 2L to "Home", 5L to "Car", 9L to "Home", 11L to " "))

        assertEquals(",Home,Car,Night,", TaskerBuiltIns.value("PENABLED"))
    }

    @Test
    fun activeProfilesAreListedInTheOrderTheyWentOn() {
        TaskerBuiltIns.watchProfiles(mapOf(1L to "Home", 2L to "Car", 3L to "Night"))
        AutomationLiveConditionState.updateProfile(3, true)
        AutomationLiveConditionState.updateProfile(1, true)
        AutomationLiveConditionState.updateProfile(2, true)
        AutomationLiveConditionState.updateProfile(2, false)
        // On, but not one the engine watches any more.
        AutomationLiveConditionState.updateProfile(40, true)

        assertEquals(",Night,Home,", TaskerBuiltIns.value("PACTIVE"))
        assertTrue(VariableStore().evaluateCondition("%PACTIVE ~ *Home*"))
        assertFalse(VariableStore().evaluateCondition("%PACTIVE ~ *Car*"))
    }

    @Test
    fun runningTasksAreListedOnceEach() {
        ActiveExecutionRegistry.register(1, "Morning", "Manual", null, 0L)
        ActiveExecutionRegistry.register(2, "Backup", "Profile: Home", null, 0L)
        ActiveExecutionRegistry.register(1, "Morning", "Manual", null, 0L)

        assertEquals(",Morning,Backup,", TaskerBuiltIns.value("TRUN"))
    }

    @Test
    fun anEmptyListLeavesTheVariableUnsetAsTaskerDoes() {
        listOf("PACTIVE", "PENABLED", "TRUN").forEach { name -> assertNull(name, TaskerBuiltIns.value(name)) }
        assertTrue(VariableStore().evaluateCondition("%TRUN not_set"))
    }

    @Test
    fun eachVolumeReadsTheStreamTaskerReads() {
        // Tasker's dq: VOLA 4, VOLC 0, VOLD 8, VOLM 3, VOLN 5, VOLR 2, VOLS 1.
        assertEquals(
            mapOf("VOLA" to 4, "VOLC" to 0, "VOLD" to 8, "VOLM" to 3, "VOLN" to 5, "VOLR" to 2, "VOLS" to 1),
            TASKER_VOLUME_STREAMS,
        )
        assertTrue(TASKER_VOLUME_STREAMS.keys.all { it in TaskerBuiltIns.SUPPORTED })
    }

    @Test
    fun aFixReadsAsLatitudeCommaLongitude() {
        assertEquals("37.4219983,-122.084", taskerLatLon(37.4219983, -122.084))
        assertEquals("40.0,-74.0", taskerLatLon(40.0, -74.0))
    }

    @Test
    fun theImporterNoLongerNamesTheBuiltInsThatFillIn() {
        val action = """<Action sr="act0"><code>548</code><Str sr="arg0" ve="3">%VOLM %PACTIVE %PENABLED %TRUN %LOC %LOCN %LIGHT</Str></Action>"""
        val report = TaskerXmlImporter.parse(
            rawXml = "<TaskerData><Task sr=\"task1\"><id>1</id><nme>Readings</nme>$action</Task></TaskerData>",
            appVersion = "test",
            importedAtEpochMs = 0L,
        )

        val note = report.lossyWarnings.single { "built-in" in it }
        assertTrue(note, "%LIGHT" in note)
        listOf("%VOLM", "%PACTIVE", "%PENABLED", "%TRUN", "%LOC").forEach { assertFalse("$it in $note", it in note) }
    }
}
