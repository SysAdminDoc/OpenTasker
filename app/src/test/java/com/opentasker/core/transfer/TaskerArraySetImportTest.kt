package com.opentasker.core.transfer

import android.content.ContextWrapper
import com.opentasker.core.actions.registerActionMetadata
import com.opentasker.core.engine.ActionContext
import com.opentasker.core.engine.ActionRegistry
import com.opentasker.core.engine.ActionResult
import com.opentasker.core.engine.VariableStore
import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.Task
import com.opentasker.core.registerCoreRuntime
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tasker's Array Set (354) splits its Values by Splitter, or by white space when there's none, into
 * %arr1, %arr2, ... and clears the array first. It imports as Variable Split given those Values.
 * The steps are copied from real exports.
 */
class TaskerArraySetImportTest {

    @Before
    fun setUp() {
        registerCoreRuntime()
        registerActionMetadata()
    }

    private fun parse(action: String): TaskerXmlImportReport =
        TaskerXmlImporter.parse(
            rawXml = """
                <TaskerData>
                    <Task sr="task1">
                        <id>1</id><nme>Arrays</nme>
                        $action
                    </Task>
                </TaskerData>
            """.trimIndent(),
            appVersion = "test",
            importedAtEpochMs = 0L,
        )

    private fun step(action: String): ActionSpec = parse(action).bundle.tasks.single().actions.single()

    private fun execute(spec: ActionSpec, variables: VariableStore, sensitive: Set<String> = emptySet()): ActionResult =
        runBlocking {
            ActionRegistry.get(spec.type)!!.run(
                ActionContext(ContextWrapper(null), variables, sensitiveArgumentNames = sensitive),
                spec.args,
            )
        }

    private val commaSet = """
        <Action sr="act3" ve="7">
            <code>354</code>
            <Str sr="arg0" ve="3">%arr</Str>
            <Str sr="arg1" ve="3">1,2,3</Str>
            <Str sr="arg2" ve="3">,</Str>
        </Action>
    """

    private val whitespaceSet = """
        <Action sr="act0" ve="7">
            <code>354</code>
            <Str sr="arg0" ve="3">%busstopcodes</Str>
            <Str sr="arg1" ve="3">50231 50239 50038 50221</Str>
            <Str sr="arg2" ve="3"/>
        </Action>
    """

    @Test
    fun arraySetImportsAsVariableSplitOfItsValues() {
        val report = parse(commaSet)
        val spec = report.bundle.tasks.single().actions.single()

        assertEquals("var.split", spec.type)
        assertEquals("1,2,3", spec.args["values"])
        assertEquals(",", spec.args["splitter"])
        assertTrue(report.unsupportedActions.toString(), report.unsupportedActions.isEmpty())
        assertTrue(report.lossyWarnings.toString(), report.lossyWarnings.isEmpty())
        assertEquals(listOf("354" to "var.split"), report.mappedActions.map { it.taskerCode to it.openTaskerActionId })
    }

    @Test
    fun theImportedStepClearsTheOldArrayAndLeavesTheBaseAlone() {
        val variables = VariableStore().apply {
            set("arr1", "old")
            set("arr2", "old")
            set("arr7", "old")
        }

        assertEquals(ActionResult.Success, execute(step(commaSet), variables))

        assertEquals(mapOf(1 to "1", 2 to "2", 3 to "3"), variables.numberedItems("arr").toMap())
        assertNull(variables.get("arr"))
    }

    @Test
    fun noSplitterSplitsOnWhiteSpace() {
        val variables = VariableStore()

        assertEquals(ActionResult.Success, execute(step(whitespaceSet), variables))

        assertEquals(
            mapOf(1 to "50231", 2 to "50239", 3 to "50038", 4 to "50221"),
            variables.numberedItems("busstopcodes").toMap(),
        )
    }

    @Test
    fun valuesFromASecretMakeSecretElements() {
        val variables = VariableStore()

        execute(step(commaSet), variables, sensitive = setOf("values"))

        assertTrue(variables.isSensitive("arr2"))
        val plain = VariableStore().also { execute(step(commaSet), it) }
        assertFalse(plain.isSensitive("arr2"))
    }

    @Test
    fun emptyValuesStayAPlaceholderThatSaysWhy() {
        val spec = step(
            """
            <Action sr="act0" ve="7">
                <code>354</code>
                <Str sr="arg0" ve="3">%arr</Str>
                <Str sr="arg1" ve="3"/>
                <Str sr="arg2" ve="3">,</Str>
            </Action>
            """,
        )

        assertEquals(TaskerXmlImporter.TASKER_UNSUPPORTED_ACTION_ID, spec.type)
        assertTrue(spec.args.getValue("summary"), "has no Values" in spec.args.getValue("summary"))
    }

    @Test
    fun theStepExportsAsArraySetAndComesBackTheSame() {
        val imported = step(whitespaceSet)
        val report = TaskerXmlExporter.export(emptyList(), listOf(Task(id = 1, name = "Arrays", actions = listOf(imported))))

        assertTrue(report.xml, "<code>354</code>" in report.xml)
        assertTrue(report.warnings.toString(), report.warnings.isEmpty())
        val back = TaskerXmlImporter.parse(report.xml, appVersion = "test", importedAtEpochMs = 0L)
        assertEquals(imported.args, back.bundle.tasks.single().actions.single().args)
    }

    @Test
    fun aVariableSplitWithoutValuesStillExportsAsVariableSplit() {
        val split = ActionSpec(type = "var.split", args = mapOf("name" to "%text", "splitter" to ","))
        val report = TaskerXmlExporter.export(emptyList(), listOf(Task(id = 1, name = "Arrays", actions = listOf(split))))

        assertTrue(report.xml, "<code>590</code>" in report.xml)
        assertFalse(report.xml, "<code>354</code>" in report.xml)
    }

    @Test
    fun exportingValuesWithARegexSaysTheRegexIsDropped() {
        val split = ActionSpec(type = "var.split", args = mapOf("name" to "%arr", "values" to "a1b", "splitter" to "\\d", "regex" to "true"))
        val report = TaskerXmlExporter.export(emptyList(), listOf(Task(id = 1, name = "Arrays", actions = listOf(split))))

        assertTrue(report.xml, "<code>354</code>" in report.xml)
        assertTrue(report.warnings.toString(), report.warnings.any { "Array Set" in it && "regex" in it })
    }
}
