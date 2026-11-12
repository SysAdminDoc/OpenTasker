package com.opentasker.core.engine

import android.content.ContextWrapper
import com.opentasker.core.actions.SunTimesAction
import com.opentasker.core.actions.registerActionMetadata
import com.opentasker.core.registerCoreRuntime
import com.opentasker.core.transfer.TaskerXmlImporter
import java.util.TimeZone
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tasker's own "Sunrise/Sunset Examples" task: Get Sunrise/Sunset Times on the phone's location,
 * then Multiple Variables Set copies the times into the globals its Time profile reads. The XML is
 * the shared project's task as Tasker wrote it; the phone stands in New York before dawn on
 * 25 June 2025, where TaskerSunTimesTest has Tasker's own numbers.
 */
class TaskerSunriseVariablesRunTest {

    @Before
    fun setUp() {
        registerActionMetadata()
        registerCoreRuntime()
        ActionRegistry.register(
            SunTimesAction(
                permitted = { true },
                locate = { 40.6972846 to -74.1443129 },
                nowMs = { 1750841218000L },
                zone = { TimeZone.getTimeZone("America/New_York") },
            ),
        )
    }

    @After
    fun tearDown() {
        registerCoreRuntime()
    }

    private val taskXml = """
        <TaskerData sr="" dvi="1" tv="6.6.0-beta">
            <Task sr="task103">
                <cdate>1750773383358</cdate>
                <edate>1750775053561</edate>
                <id>103</id>
                <nme>Set Sunrise And Sunset Globals</nme>
                <pri>100</pri>
                <Action sr="act0" ve="7">
                    <code>473</code>
                    <Bundle sr="arg0">
                        <Vals sr="val"/>
                    </Bundle>
                    <Str sr="arg1" ve="3"/>
                    <Str sr="arg2" ve="3"/>
                    <Str sr="arg3" ve="3"/>
                </Action>
                <Action sr="act1" ve="7">
                    <code>389</code>
                    <Bundle sr="arg0">
                        <Vals sr="val"/>
                    </Bundle>
                    <Str sr="arg1" ve="3">%Sunrise
        %Sunset</Str>
                    <Str sr="arg2" ve="3">
        </Str>
                    <Str sr="arg3" ve="3">%ss_sunrise
        %ss_sunset</Str>
                    <Str sr="arg4" ve="3">
        </Str>
                    <Int sr="arg5" val="0"/>
                    <Int sr="arg6" val="3"/>
                    <Int sr="arg7" val="0"/>
                    <Int sr="arg8" val="0"/>
                </Action>
            </Task>
        </TaskerData>
    """.trimIndent()

    private fun runImported(store: VariableStore): Boolean {
        val parsed = TaskerXmlImporter.parse(rawXml = taskXml, appVersion = "test", importedAtEpochMs = 0L)
        val task = parsed.bundle.tasks.single()
        assertEquals(listOf("sun.times", "var.set_multiple"), task.actions.map { it.type })
        assertTrue(parsed.lossyWarnings.toString(), parsed.lossyWarnings.isEmpty())
        return runBlocking { TaskRunner(ActionContext(ContextWrapper(null), store)).run(task).success }
    }

    @Test
    fun theGlobalsTheTimeProfileReadsGetTheSunTimes() {
        val store = VariableStore()

        assertTrue(runImported(store))

        assertEquals("1750843560", store.get("Sunrise"))
        assertEquals("1750897860", store.get("Sunset"))
        // The %ss_ outputs are the task's own locals, so only the copies outlive the run.
        assertNull(store.get("ss_sunrise"))
    }

    @Test
    fun yesterdaysSunriseIsReplacedAndNeverReadAsAName() {
        val store = VariableStore()
        // A global, so a write through the old value would outlive the run and show up here.
        store.set("Sunrise", "%Leak")

        assertTrue(runImported(store))

        assertEquals("1750843560", store.get("Sunrise"))
        assertNull(store.get("Leak"))
    }
}
