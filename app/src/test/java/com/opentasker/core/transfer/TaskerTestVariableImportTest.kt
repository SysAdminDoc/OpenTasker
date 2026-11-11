package com.opentasker.core.transfer

import android.content.ContextWrapper
import com.opentasker.core.engine.ActionContext
import com.opentasker.core.engine.TaskRunner
import com.opentasker.core.engine.VariableStore
import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.Task
import com.opentasker.core.registerCoreRuntime
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tasker's Test Variable (code 345). Its only type is Length (decompiled 6.7.8, `ro.i`), which
 * stores how many characters the Data has once variables are filled in. An unset variable stays
 * as written there, so `%x` counts as 2. The layout is copied from a real export.
 */
class TaskerTestVariableImportTest {
    @Before
    fun setUp() {
        registerCoreRuntime()
    }

    private fun testVariable(type: String = """<Int sr="arg0" val="0"/>""", data: String = "%brightn", storeIn: String = "%brlen"): Task =
        TaskerXmlImporter.parse(
            rawXml = """
                <TaskerData>
                    <Task sr="task1">
                        <id>1</id><nme>Brightness</nme>
                        <Action sr="act0" ve="7">
                            <code>345</code>
                            $type
                            <Str sr="arg1" ve="3">$data</Str>
                            <Str sr="arg2" ve="3">$storeIn</Str>
                        </Action>
                    </Task>
                </TaskerData>
            """.trimIndent(),
            appVersion = "test",
            importedAtEpochMs = 0L,
        ).bundle.tasks.single()

    private fun run(task: Task, vararg variables: Pair<String, String>): VariableStore = runBlocking {
        val store = VariableStore().apply { variables.forEach { (name, value) -> set(name, value) } }
        val report = TaskRunner(ActionContext(ContextWrapper(null), store)).run(task)
        assertTrue(report.results.toString(), report.success)
        store
    }

    @Test
    fun lengthImportsAsATextLengthStep() {
        val action = testVariable().actions.single()

        assertEquals("text.length", action.type)
        assertEquals(mapOf("source" to "%brightn", "var" to "%brlen"), action.args)
    }

    // The real export stores into %brlen, which is local to its task and gone once the run ends,
    // so these runs store into a global the test can read afterwards.
    @Test
    fun theStepCountsTheDataOnceItsVariablesAreFilledIn() {
        assertEquals("3", run(testVariable(data = "%BRIGHTN", storeIn = "%BRLEN"), "BRIGHTN" to "128").get("BRLEN"))
        assertEquals("2", run(testVariable(data = "%x", storeIn = "%BRLEN")).get("BRLEN"))
        assertEquals("5", run(testVariable(data = "%SPACEX(1)", storeIn = "%BRLEN"), "SPACEX1" to "hello").get("BRLEN"))
        assertEquals("0", run(testVariable(data = "%BRIGHTN", storeIn = "%BRLEN"), "BRIGHTN" to "").get("BRLEN"))
    }

    @Test
    fun spacesAroundTheDataCount() {
        val task = testVariable(data = " %BRIGHTN ", storeIn = "%BRLEN")

        assertEquals(" %BRIGHTN ", task.actions.single().args["source"])
        assertEquals("5", run(task, "BRIGHTN" to "128").get("BRLEN"))
    }

    @Test
    fun emptyOrSpacesOnlyDataStillImportsAndCounts() {
        assertEquals("0", run(testVariable(data = "", storeIn = "%BRLEN")).get("BRLEN"))
        assertEquals("2", run(testVariable(data = "  ", storeIn = "%BRLEN")).get("BRLEN"))
    }

    @Test
    fun theStepGoesBackOutAsTestVariableAndComesBackTheSame() {
        val task = testVariable()
        val report = TaskerXmlExporter.export(emptyList(), listOf(task))
        val xml = report.xml.replace(Regex(">\\s+<"), "><")

        assertTrue(xml, "<code>345</code>" in xml)
        assertTrue(xml, """<Int sr="arg0" val="0"/>""" in xml)
        assertTrue(xml, ">%brightn</Str>" in xml)
        assertTrue(xml, ">%brlen</Str>" in xml)
        assertTrue(report.warnings.toString(), report.warnings.none { "345" in it || "length" in it })

        val back = TaskerXmlImporter.parse(report.xml, appVersion = "test", importedAtEpochMs = 0L)
            .bundle.tasks.single().actions.single()
        assertEquals(task.actions.single().type, back.type)
        assertEquals(task.actions.single().args, back.args)
    }

    @Test
    fun aStepWrittenHereWithoutAnOutputNameGoesOutStoringIntoLength() {
        val report = TaskerXmlExporter.export(
            emptyList(),
            listOf(Task(id = 1, name = "Count", actions = listOf(ActionSpec(type = "text.length", args = mapOf("source" to "%name"))))),
        )

        assertTrue(report.xml, ">%length</Str>" in report.xml.replace(Regex(">\\s+<"), "><"))
    }

    @Test
    fun anythingButOnePlainStoreVariableOrTheLengthTypeStaysAPlaceholder() {
        listOf(
            testVariable(storeIn = "%arr(1)"),
            testVariable(storeIn = ""),
            testVariable(storeIn = "brlen"),
            testVariable(type = """<Int sr="arg0" val="1"/>"""),
            testVariable(type = """<Int sr="arg0"><var>%kind</var></Int>"""),
            testVariable(type = ""),
        ).forEach { task ->
            assertEquals(task.actions.toString(), "tasker.unsupported", task.actions.single().type)
            val reason = task.actions.single().args["summary"].orEmpty()
            assertTrue(reason, reason.startsWith("Tasker Test Variable") && "null" !in reason)
        }
    }
}
