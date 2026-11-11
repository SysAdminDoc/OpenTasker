package com.opentasker.core.transfer

import android.content.ContextWrapper
import com.opentasker.core.engine.ActionContext
import com.opentasker.core.engine.TaskRunner
import com.opentasker.core.engine.VariableStore
import com.opentasker.core.model.Task
import com.opentasker.core.registerCoreRuntime
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tasker's Variable Subtract (code 890), and the rounding it shares with Variable Add (888). Tasker
 * (decompiled 6.7.8, `ExecuteService.W1`) reads an unset or empty variable as 0, rounds a value with
 * a decimal point to a whole number, then subtracts. The layout is copied from a real export.
 */
class TaskerVariableSubtractImportTest {
    @Before
    fun setUp() {
        registerCoreRuntime()
    }

    private fun step(code: String = "890", name: String = "%lcount", value: String = """<Int sr="arg1" val="1"/>""", wrap: String = """<Int sr="arg2" val="0"/>"""): Task =
        TaskerXmlImporter.parse(
            rawXml = """
                <TaskerData>
                    <Task sr="task1">
                        <id>1</id><nme>Loop</nme>
                        <Action sr="act0" ve="7">
                            <code>$code</code>
                            <Str sr="arg0" ve="3">$name</Str>
                            $value
                            $wrap
                        </Action>
                    </Task>
                </TaskerData>
            """.trimIndent(),
            appVersion = "test",
            importedAtEpochMs = 0L,
        ).bundle.tasks.single()

    // The real export subtracts from %lcount, which is local to its task and gone once the run
    // ends, so these runs subtract from a global the test can read afterwards.
    private fun runOn(task: Task, start: String?): String? = runBlocking {
        val store = VariableStore().apply { start?.let { set("LCOUNT", it) } }
        val report = TaskRunner(ActionContext(ContextWrapper(null), store)).run(task)
        assertTrue(report.results.toString(), report.success)
        store.get("LCOUNT")
    }

    @Test
    fun subtractImportsAsASetVariableStep() {
        val action = step().actions.single()

        assertEquals("var.set", action.type)
        assertEquals("%lcount", action.args["name"])
    }

    @Test
    fun subtractCountsDownFromTheValueOrFromNothing() {
        val task = step(name = "%LCOUNT")

        assertEquals("4", runOn(task, "5"))
        assertEquals("-1", runOn(task, null))
        assertEquals("-1", runOn(task, ""))
        assertEquals("-3", runOn(task, "-2"))
    }

    @Test
    fun aLargerOrNegativeValueIsSubtractedAsWritten() {
        assertEquals("2", runOn(step(name = "%LCOUNT", value = """<Int sr="arg1" val="8"/>"""), "10"))
        assertEquals("13", runOn(step(name = "%LCOUNT", value = """<Int sr="arg1" val="-3"/>"""), "10"))
    }

    @Test
    fun aDecimalValueIsRoundedBeforeTheStepForSubtractAndAdd() {
        val subtract = step(name = "%LCOUNT")
        val add = step(code = "888", name = "%LCOUNT")

        assertEquals("2", runOn(subtract, "2.5"))
        assertEquals("1", runOn(subtract, "2.4"))
        assertEquals("4", runOn(add, "2.5"))
        assertEquals("3", runOn(add, "2.4"))
    }

    // Tasker rounds with Math.round, which takes -2.5 up to -2, not away from zero to -3.
    @Test
    fun aNegativeHalfIsRoundedUpTheWayMathRoundDoes() {
        assertEquals("-3", runOn(step(name = "%LCOUNT"), "-2.5"))
        assertEquals("-1", runOn(step(code = "888", name = "%LCOUNT"), "-2.5"))
    }

    @Test
    fun aValueThatIsNotANumberLeavesTheRunFinishing() {
        assertEquals("", runOn(step(name = "%LCOUNT"), "NaN"))
        assertEquals("", runOn(step(code = "888", name = "%LCOUNT"), "NaN"))
    }

    // The real export's own shape: subtract from a local, then copy it out with Variable Set.
    @Test
    fun aLocalVariableIsSubtractedWithinItsTask() {
        val task = TaskerXmlImporter.parse(
            rawXml = """
                <TaskerData>
                    <Task sr="task1">
                        <id>1</id><nme>Loop</nme>
                        <Action sr="act0" ve="7">
                            <code>547</code>
                            <Str sr="arg0" ve="3">%lcount</Str>
                            <Str sr="arg1" ve="3">7</Str>
                            <Int sr="arg2" val="0"/>
                            <Int sr="arg3" val="0"/>
                            <Int sr="arg4" val="0"/>
                        </Action>
                        <Action sr="act1" ve="7">
                            <code>890</code>
                            <Str sr="arg0" ve="3">%lcount</Str>
                            <Int sr="arg1" val="1"/>
                            <Int sr="arg2" val="0"/>
                        </Action>
                        <Action sr="act2" ve="7">
                            <code>547</code>
                            <Str sr="arg0" ve="3">%LCOUNT</Str>
                            <Str sr="arg1" ve="3">%lcount</Str>
                            <Int sr="arg2" val="0"/>
                            <Int sr="arg3" val="0"/>
                            <Int sr="arg4" val="0"/>
                        </Action>
                    </Task>
                </TaskerData>
            """.trimIndent(),
            appVersion = "test",
            importedAtEpochMs = 0L,
        ).bundle.tasks.single()

        assertEquals("6", runOn(task, null))
    }

    @Test
    fun aVariableValueAWrapOrAnythingButOnePlainVariableStaysAPlaceholder() {
        listOf(
            step(value = """<Int sr="arg1"><var>%step</var></Int>"""),
            step(wrap = """<Int sr="arg2" val="10"/>"""),
            step(wrap = """<Int sr="arg2"><var>%max</var></Int>"""),
            step(name = "%arr(1)"),
            step(name = "lcount"),
            step(name = ""),
        ).forEach { task ->
            assertEquals(task.actions.toString(), TaskerXmlImporter.TASKER_UNSUPPORTED_ACTION_ID, task.actions.single().type)
            val reason = task.actions.single().args["summary"].orEmpty()
            assertTrue(reason, reason.startsWith("Tasker Variable Subtract"))
        }
    }

    @Test
    fun theRefusalSaysWhatTheStepSubtracts() {
        val reason = step(name = "%brightn", value = """<Int sr="arg1"><var>%step</var></Int>""").actions.single().args["summary"]

        assertEquals("Tasker Variable Subtract subtracts %step, and this app only subtracts a fixed number", reason)
    }
}
