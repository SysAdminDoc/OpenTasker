package com.opentasker.core.transfer

import com.opentasker.core.actions.registerActionMetadata
import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.Task
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tasker's Delete File (406) removes one file, as the delete file step does, and its path is passed
 * through the way Read File and Write File already are. The two plain layouts are copied from real
 * exports, one with the later Use Global Namespace field. The shred and root steps use the same
 * layout with arg1 and arg2 changed.
 */
class TaskerDeleteFileImportTest {

    @Before
    fun setUp() {
        registerActionMetadata()
    }

    private fun parse(fields: String): TaskerXmlImportReport =
        TaskerXmlImporter.parse(
            rawXml = """
                <TaskerData>
                    <Task sr="task1">
                        <id>1</id><nme>Cleanup</nme>
                        <Action sr="act4" ve="7"><code>406</code><se>false</se>$fields</Action>
                    </Task>
                </TaskerData>
            """.trimIndent(),
            appVersion = "test",
            importedAtEpochMs = 0L,
        )

    @Test
    fun deleteFileImportsAsTheDeleteFileStepWithItsPath() {
        val report = parse("""<Str sr="arg0" ve="3">%filepathname</Str><Int sr="arg1" val="0"/><Int sr="arg2" val="0"/>""")

        val step = report.bundle.tasks.single().actions.single()
        assertEquals("file.delete", step.type)
        assertEquals(mapOf("path" to "%filepathname"), step.args)
        assertEquals(listOf("406" to "file.delete"), report.mappedActions.map { it.taskerCode to it.openTaskerActionId })
        assertTrue(report.lossyWarnings.toString(), report.lossyWarnings.isEmpty())
    }

    @Test
    fun theGlobalNamespaceFieldOnItsOwnLeavesNoWarning() {
        val report = parse(
            """<Str sr="arg0" ve="3">%png</Str><Int sr="arg1" val="0"/><Int sr="arg2" val="0"/><Int sr="arg3" val="1"/>""",
        )

        assertEquals("file.delete", report.bundle.tasks.single().actions.single().type)
        assertTrue(report.lossyWarnings.toString(), report.lossyWarnings.isEmpty())
    }

    @Test
    fun shredLevelAndUseRootAreNamedAsNotImported() {
        val report = parse("""<Str sr="arg0" ve="3">%png</Str><Int sr="arg1" val="3"/><Int sr="arg2" val="1"/>""")

        assertEquals("file.delete", report.bundle.tasks.single().actions.single().type)
        assertEquals(1, report.lossyWarnings.size)
        assertTrue(report.lossyWarnings.toString(), "Shred Level of 3" in report.lossyWarnings.toString())
        assertTrue(report.lossyWarnings.toString(), "Use Root" in report.lossyWarnings.toString())
    }

    @Test
    fun aShredLevelFromAVariableIsNamedByTheVariable() {
        val report = parse("""<Str sr="arg0" ve="3">%png</Str><Int sr="arg1"><var>%shred</var></Int><Int sr="arg2" val="0"/>""")

        assertEquals("file.delete", report.bundle.tasks.single().actions.single().type)
        assertEquals(1, report.lossyWarnings.size)
        assertTrue(report.lossyWarnings.toString(), "Shred Level of %shred" in report.lossyWarnings.toString())
        assertTrue(report.lossyWarnings.toString(), "Use Root" !in report.lossyWarnings.toString())
    }

    @Test
    fun useRootOnItsOwnIsNamed() {
        val report = parse("""<Str sr="arg0" ve="3">%png</Str><Int sr="arg1" val="0"/><Int sr="arg2" val="1"/>""")

        assertEquals(1, report.lossyWarnings.size)
        assertTrue(report.lossyWarnings.toString(), "Use Root" in report.lossyWarnings.toString())
        assertTrue(report.lossyWarnings.toString(), "Shred" !in report.lossyWarnings.toString())
    }

    @Test
    fun theDeleteFileStepExportsAsDeleteFileAndComesBack() {
        val report = TaskerXmlExporter.export(
            emptyList(),
            listOf(
                Task(
                    id = 1,
                    name = "Cleanup",
                    actions = listOf(ActionSpec(type = "file.delete", args = mapOf("path" to "notes/old.txt"))),
                ),
            ),
        )

        assertTrue(report.xml, "<code>406</code>" in report.xml)
        val back = TaskerXmlImporter.parse(report.xml, appVersion = "test", importedAtEpochMs = 0L)
        val step = back.bundle.tasks.single().actions.single()
        assertEquals("file.delete", step.type)
        assertEquals("notes/old.txt", step.args["path"])
        assertTrue(back.lossyWarnings.toString(), back.lossyWarnings.isEmpty())
    }
}
