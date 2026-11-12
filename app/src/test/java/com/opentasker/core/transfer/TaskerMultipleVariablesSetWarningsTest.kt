package com.opentasker.core.transfer

import com.opentasker.core.actions.registerActionMetadata
import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.Task
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** What moving Tasker's Multiple Variables Set (389) across says, and when it stays quiet. */
class TaskerMultipleVariablesSetWarningsTest {

    @Before
    fun setUp() {
        registerActionMetadata()
    }

    private fun parse(names: String, values: String, doMaths: Boolean = false) =
        TaskerXmlImporter.parse(
            rawXml = "<TaskerData><Task sr=\"task1\"><id>1</id><nme>Imported</nme><Action sr=\"act0\" ve=\"7\">" +
                "<code>389</code><Bundle sr=\"arg0\"><Vals sr=\"val\"/></Bundle>" +
                str(1, names) + str(2, "") + str(3, values) + str(4, "") +
                "<Int sr=\"arg5\" val=\"${if (doMaths) 1 else 0}\"/><Int sr=\"arg6\" val=\"3\"/>" +
                "<Int sr=\"arg7\" val=\"0\"/><Int sr=\"arg8\" val=\"0\"/></Action></Task></TaskerData>",
            appVersion = "test",
            importedAtEpochMs = 0L,
        )

    private fun str(index: Int, value: String) =
        if (value.isEmpty()) "<Str sr=\"arg$index\" ve=\"3\"/>" else "<Str sr=\"arg$index\" ve=\"3\">$value</Str>"

    @Test
    fun aBuiltInTheTaskSetsIsNotReportedAsMissing() {
        val listed = parse("%a,%HTTPD", "1,2")
        assertTrue(listed.lossyWarnings.toString(), listed.lossyWarnings.isEmpty())
        val inline = parse("%a,1\n%HTTPD,done", "")
        assertTrue(inline.lossyWarnings.toString(), inline.lossyWarnings.isEmpty())
    }

    @Test
    fun aBuiltInTheTaskOnlyUnsetsIsStillReported() {
        val parsed = parse("%a,%HTTPD", "1")
        assertTrue(parsed.lossyWarnings.toString(), parsed.lossyWarnings.any { "HTTPD" in it })
    }

    @Test
    fun wordsWithADashAreTextNotMaths() {
        val parsed = parse("%a,%b", "hello-world,well-known", doMaths = true)
        assertEquals("hello-world,well-known", parsed.bundle.tasks.single().actions.single().args["values"])
        assertTrue(parsed.lossyWarnings.toString(), parsed.lossyWarnings.isEmpty())
    }

    @Test
    fun aSumWithAFunctionIsStillNamed() {
        val parsed = parse("%a", "sqrt(%x)", doMaths = true)
        assertTrue(parsed.lossyWarnings.toString(), parsed.lossyWarnings.any { "sqrt(%x)" in it })
    }

    @Test
    fun doMathsOnValuesWrittenAfterTheirNamesIsNamed() {
        val parsed = parse("%a,1+1\n%b,2", "", doMaths = true)
        assertEquals("%a,1+1\n%b,2", parsed.bundle.tasks.single().actions.single().args["names"])
        assertTrue(parsed.lossyWarnings.toString(), parsed.lossyWarnings.any { "written after their names" in it })
    }

    @Test
    fun exportNamesATemplateTaskerWontWorkOut() {
        fun warnings(args: Map<String, String>) = TaskerXmlExporter.export(
            emptyList(),
            listOf(Task(id = 1, name = "Exported", actions = listOf(ActionSpec(type = "var.set_multiple", args = args)))),
        ).warnings
        assertTrue(warnings(mapOf("names" to "%a", "values" to "%b")).isEmpty())
        assertTrue(warnings(mapOf("names" to "%a", "values" to "{{ b | upper }}")).any { "{{ }}" in it })
        assertTrue(warnings(mapOf("names" to "%a,{{ b }}\n%c,1")).any { "{{ }}" in it })
    }
}
