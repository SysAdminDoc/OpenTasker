package com.opentasker.core.transfer

import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.Task
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tasker's Matches Regex and Doesn't Match Regex conditions (op codes 4 and 5) in and out. */
class TaskerRegexConditionTransferTest {

    private fun importCondition(op: String, rhs: String) = TaskerXmlImporter.parse(
        rawXml = """
            <TaskerData>
                <Task sr="task1">
                    <id>1</id><nme>Regex</nme>
                    <Action sr="act0">
                        <code>37</code>
                        <ConditionList sr="if">
                            <Condition sr="c0"><lhs>%pa_url</lhs><op>$op</op><rhs>$rhs</rhs></Condition>
                        </ConditionList>
                    </Action>
                </Task>
            </TaskerData>
        """.trimIndent(),
        appVersion = "test",
        importedAtEpochMs = 123L,
    )

    @Test
    fun regexConditionsSurviveAnExportImportRoundTrip() {
        listOf("%pa_url ~R ^https?://" to "4", "%pa_url !~R [0-9]{3}" to "5").forEach { (condition, op) ->
            val exported = TaskerXmlExporter.export(
                emptyList(),
                listOf(Task(id = 1, name = "Guarded stop", actions = listOf(ActionSpec(type = "flow.stop", condition = condition)))),
            )
            assertTrue(exported.xml, exported.xml.contains("<op>$op</op>"))

            val reimported = TaskerXmlImporter.parse(rawXml = exported.xml, appVersion = "test", importedAtEpochMs = 123L)
            assertEquals(condition, reimported.bundle.tasks.single().actions.single().condition)
        }
    }

    @Test
    fun aMatchesPatternStartingWithTildeRStillExportsAsMatches() {
        val exported = TaskerXmlExporter.export(
            emptyList(),
            listOf(Task(id = 1, name = "Guarded stop", actions = listOf(ActionSpec(type = "flow.stop", condition = "%pa_url ~ ~R^https")))),
        )
        assertTrue(exported.xml, exported.xml.contains("<op>2</op>"))
        assertTrue(exported.xml, exported.xml.contains("<rhs>~R^https</rhs>"))
    }

    @Test
    fun aRegexThisAppCantRunGetsAnImportNote() {
        val report = importCondition("5", "^(?!https)")
        assertEquals("%pa_url !~R ^(?!https)", report.bundle.tasks.single().actions.single().condition)
        assertTrue(report.lossyWarnings.toString(), report.lossyWarnings.any { it.contains("regex") && it.contains("^(?!https)") })
    }

    @Test
    fun aRegexBuiltFromAVariableGetsNoNote() {
        // The pattern isn't known until the task runs, so the import can't judge it.
        val report = importCondition("4", "%pattern")
        assertEquals("%pa_url ~R %pattern", report.bundle.tasks.single().actions.single().condition)
        assertTrue(report.lossyWarnings.toString(), report.lossyWarnings.none { it.contains("regex") })
    }

    @Test
    fun aBlankRegexGetsNoNoteAndAnOverlongOneSaysWhy() {
        val blank = importCondition("4", "")
        assertTrue(blank.lossyWarnings.toString(), blank.lossyWarnings.none { it.contains("regex") })

        val long = importCondition("4", "a".repeat(300))
        assertTrue(long.lossyWarnings.toString(), long.lossyWarnings.any { it.contains("regex") && it.contains("longer than 256") })
        assertTrue(long.lossyWarnings.toString(), long.lossyWarnings.none { it.contains("features this app doesn't have") })
    }
}
