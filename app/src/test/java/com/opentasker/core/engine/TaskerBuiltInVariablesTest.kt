package com.opentasker.core.engine

import android.content.ContextWrapper
import com.opentasker.core.engine.variables.TaskerBuiltIns
import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.Task
import com.opentasker.core.registerCoreRuntime
import com.opentasker.core.transfer.TaskerXmlImporter
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/**
 * Tasker fills in its built-in variables (`%TIME`, `%DATE`, `%BATT`, ...) whenever a task reads
 * them, and imported tasks read them often. Tasker won't let a task set one, so a value stored
 * under a built-in's name here is a conflict Preflight should name.
 */
class TaskerBuiltInVariablesTest {
    private val recorded = mutableListOf<String>()

    // Friday, July 4, 2025, 9:05:07.123 AM in New York (EDT, four hours behind UTC).
    private val now = Instant.parse("2025-07-04T13:05:07.123Z")

    @Before
    fun setUp() {
        registerCoreRuntime()
        ActionRegistry.register(object : Action {
            override val id = "test.builtin.record"
            override val category = ActionCategory.FLOW
            override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
                recorded += args.getValue("v")
                return ActionResult.Success
            }
        })
        TaskerBuiltIns.clockForTests = Clock.fixed(now, ZoneId.of("America/New_York"))
        TaskerBuiltIns.localeForTests = Locale.US
        TaskerBuiltIns.installDevice { name -> mapOf("BATT" to "15", "SCREEN" to "on")[name] }
    }

    @After
    fun tearDown() {
        TaskerBuiltIns.resetForTests()
        registerCoreRuntime()
    }

    private fun record(text: String, condition: String? = null) =
        ActionSpec(type = "test.builtin.record", args = mapOf("v" to text), condition = condition)

    private fun run(vararg actions: ActionSpec, store: VariableStore = VariableStore()): List<String> = runBlocking {
        recorded.clear()
        val report = TaskRunner(ActionContext(ContextWrapper(null), store)).run(Task(name = "Built-ins", actions = actions.toList()))
        assertTrue(report.results.toString(), report.success)
        recorded.toList()
    }

    @Test
    fun theClockBuiltInsFillInTheWayTaskerWritesThem() {
        assertEquals(
            listOf("09.05|${now.epochSecond}|${now.toEpochMilli()}|7-4-25|Friday|4"),
            run(record("%TIME|%TIMES|%TIMEMS|%DATE|%DAYW|%DAYM")),
        )
    }

    @Test
    fun theDeviceBuiltInsComeFromTheDeviceReader() {
        // The reader here has no %SDK, so that one stays as written.
        assertEquals(listOf("battery 15, screen on, sdk %SDK"), run(record("battery %BATT, screen %SCREEN, sdk %SDK")))
    }

    @Test
    fun aNameThatOnlyStartsWithABuiltInIsntOne() {
        assertEquals(listOf("%TIMEOUT %BATTERY 09.05-ish"), run(record("%TIMEOUT %BATTERY %TIME-ish")))
    }

    @Test
    fun conditionsReadTheBuiltIns() {
        assertEquals(
            listOf("low", "has a time"),
            run(
                record("low", condition = "%BATT < 20"),
                record("high", condition = "%BATT > 20"),
                record("has a time", condition = "%TIME is_set"),
            ),
        )
    }

    @Test
    fun aVariableSplitOfTimeSplitsTheClock() {
        // Tasker's own example for Variable Split.
        val split = ActionSpec(type = "var.split", args = mapOf("name" to "%TIME", "splitter" to "."))
        assertEquals(listOf("09 h 05"), run(split, record("%TIME1 h %TIME2")))

        val preview = PreflightRunner.preflightTask(Task(name = "Split", actions = listOf(split))).tasks.single().steps
        assertTrue(preview.single().warnings.toString(), preview.single().warnings.none { "built-in" in it })
    }

    @Test
    fun thePreviewFillsInTheBuiltInsToo() {
        val steps = PreflightRunner.preflightTask(
            Task(name = "Built-ins", actions = listOf(ActionSpec(type = "log", args = mapOf("message" to "%TIME on %DAYW, battery %BATT")))),
        ).tasks.single().steps

        assertEquals("09.05 on Friday, battery 15", steps.single().expandedArguments["message"])
    }

    @Test
    fun aValueSetUnderABuiltInsNameWinsAndThePreviewSaysSo() {
        val actions = listOf(
            ActionSpec(type = "var.set", args = mapOf("name" to "%TIME", "value" to "lunch")),
            ActionSpec(type = "var.set", args = mapOf("name" to "%Time", "value" to "noon")),
            record("%TIME / %Time"),
        )
        assertEquals(listOf("lunch / noon"), run(*actions.toTypedArray()))

        val steps = PreflightRunner.preflightTask(Task(name = "Lunch", actions = actions)).tasks.single().steps
        assertTrue(steps[0].warnings.toString(), steps[0].warnings.any { "%TIME" in it && "built-in" in it })
        // Names are case-sensitive in Tasker too, so %Time is an ordinary global.
        assertTrue(steps[1].warnings.toString(), steps[1].warnings.none { "built-in" in it })
        assertEquals("lunch / noon", steps[2].expandedArguments["v"])
    }

    @Test
    fun theImporterNamesTheBuiltInsThisAppCantFillIn() {
        val flash = """<Action sr="act0"><code>548</code><Str sr="arg0" ve="3">Light %LIGHT, speed %LOCSPD, battery %BATT at %TIME</Str></Action>"""
        val signal = """<Action sr="act1"><code>548</code>""" +
            """<ConditionList sr="if"><Condition sr="c0" ve="3"><lhs>%CELLSIG</lhs><op>6</op><rhs>2</rhs></Condition></ConditionList>""" +
            """<Str sr="arg0" ve="3">weak</Str></Action>"""
        val dated = """<Action sr="act0"><code>548</code><Str sr="arg0" ve="3">Today is %DATE, %TIMEOUT left</Str></Action>"""
        val report = TaskerXmlImporter.parse(
            rawXml = "<TaskerData>" +
                "<Task sr=\"task1\"><id>1</id><nme>Readings</nme>$flash$signal</Task>" +
                "<Task sr=\"task2\"><id>2</id><nme>Dated</nme>$dated</Task>" +
                "</TaskerData>",
            appVersion = "test",
            importedAtEpochMs = 0L,
        )

        val builtInWarnings = report.lossyWarnings.filter { "built-in" in it }
        val readings = builtInWarnings.single()
        assertTrue(readings, "'Readings'" in readings)
        listOf("%LIGHT", "%LOCSPD", "%CELLSIG").forEach { assertTrue(readings, it in readings) }
        // %BATT and %TIME are filled in here, and %TIMEOUT isn't a built-in.
        assertFalse(readings, "%BATT" in readings)
        assertFalse(readings, "%TIME" in readings)
    }

    @Test
    fun thePreviewFillsInEveryClockBuiltIn() {
        val steps = PreflightRunner.preflightTask(
            Task(name = "Clock", actions = listOf(record("%TIME|%TIMES|%TIMEMS|%DATE|%DAYW|%DAYM"))),
        ).tasks.single().steps

        assertEquals("09.05|${now.epochSecond}|${now.toEpochMilli()}|7-4-25|Friday|4", steps.single().expandedArguments["v"])
    }

    @Test
    fun anImportedDoMathsSumReadsTheClock() {
        // Tasker's usual "an hour from now": Variable Set %Later to %TIMES + 3600 with Do Maths on,
        // which comes over as a template.
        val imported = TaskerXmlImporter.parse(
            rawXml = "<TaskerData><Task sr=\"task1\"><id>1</id><nme>Later</nme>" +
                """<Action sr="act0"><code>547</code><Str sr="arg0" ve="3">%Later</Str><Str sr="arg1" ve="3">%TIMES + 3600</Str>""" +
                """<Int sr="arg2" val="0"/><Int sr="arg3" val="1"/><Int sr="arg4" val="0"/></Action>""" +
                "</Task></TaskerData>",
            appVersion = "test",
            importedAtEpochMs = 0L,
        ).bundle.tasks.single()
        val actions = imported.actions + record("%Later") + record("{{ global.TIMES }}")
        val expected = listOf("${now.epochSecond + 3600}", "${now.epochSecond}")

        assertEquals(expected, run(*actions.toTypedArray()))
        val steps = PreflightRunner.preflightTask(Task(name = "Later", actions = actions)).tasks.single().steps
        assertEquals(expected, steps.drop(1).map { it.expandedArguments["v"] })
    }

    @Test
    fun aBuiltInSetToNothingFillsInAgain() {
        val actions = listOf(
            ActionSpec(type = "var.set", args = mapOf("name" to "%TIME", "value" to "lunch")),
            record("%TIME"),
            // A Variable Set to nothing.
            ActionSpec(type = "var.set", args = mapOf("name" to "%TIME", "value" to "")),
            record("%TIME"),
            ActionSpec(type = "var.set", args = mapOf("name" to "%Hour", "value" to "{{ TIME }}")),
            record("%Hour"),
        )

        assertEquals(listOf("lunch", "09.05", "09.05"), run(*actions.toTypedArray()))
    }

    @Test
    fun aStoredGlobalUnderABuiltInsNameStillWins() {
        val store = VariableStore().apply { seedGlobals(mapOf("TIME" to "teatime")) }

        assertEquals(listOf("teatime at teatime"), run(record("%TIME at {{ TIME }}"), store = store))
    }

    @Test
    fun thePreviewWarnsAboutWritesThatHideAFilledInBuiltInEvenOnASkippedStep() {
        val steps = PreflightRunner.preflightTask(
            Task(
                name = "Writes",
                actions = listOf(
                    // The battery reads 15, so this step is skipped here, but it runs on a full charge.
                    ActionSpec(type = "var.set", args = mapOf("name" to "%DATE", "value" to "today"), condition = "%BATT > 50"),
                    // Tasker's HTTP Get writes %HTTPD and %HTTPR itself, and this app doesn't fill them in.
                    ActionSpec(
                        type = "http.request",
                        args = mapOf("method" to "GET", "url" to "https://example.invalid/", "response_var" to "%HTTPD", "status_var" to "%HTTPR"),
                    ),
                ),
            ),
        ).tasks.single().steps

        assertEquals(PreflightStepStatus.SKIPPED, steps[0].status)
        assertTrue(steps[0].warnings.toString(), steps[0].warnings.any { "%DATE" in it && "built-in" in it })
        assertTrue(steps[1].warnings.toString(), steps[1].warnings.none { "built-in" in it })
    }

    @Test
    fun theImporterLeavesOutABuiltInTheTasksOwnStepsSet() {
        val get = """<Action sr="act0"><code>118</code><Str sr="arg0" ve="3">https://example.invalid/x</Str><Int sr="arg5" val="10"/></Action>"""
        val show = """<Action sr="act1"><code>548</code><Str sr="arg0" ve="3">%HTTPR: %HTTPD</Str></Action>"""
        val report = TaskerXmlImporter.parse(
            rawXml = "<TaskerData>" +
                "<Task sr=\"task1\"><id>1</id><nme>Fetch</nme>$get$show</Task>" +
                "<Task sr=\"task2\"><id>2</id><nme>Show</nme>${show.replace("act1", "act0")}</Task>" +
                "</TaskerData>",
            appVersion = "test",
            importedAtEpochMs = 0L,
        )

        val builtInWarnings = report.lossyWarnings.filter { "built-in" in it }
        // Only the task that reads them without fetching gets the note.
        val shown = builtInWarnings.single()
        assertTrue(shown, "'Show'" in shown && "%HTTPD" in shown && "%HTTPR" in shown)
    }
}
