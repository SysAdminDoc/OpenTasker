package com.opentasker.core.transfer

import com.opentasker.core.actions.registerActionMetadata
import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.Task
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Tasker's Multiple Variables Set (389), laid out the way Tasker 6.7.8 exports it. */
class TaskerMultipleVariablesSetTransferTest {

    @Before
    fun setUp() {
        registerActionMetadata()
    }

    private fun parse(actionXml: String) =
        TaskerXmlImporter.parse(
            rawXml = "<TaskerData><Task sr=\"task1\"><id>1</id><nme>Imported</nme>" +
                "<Action sr=\"act0\" ve=\"7\">$actionXml</Action></Task></TaskerData>",
            appVersion = "test",
            importedAtEpochMs = 0L,
        )

    private fun action(xml: String): ActionSpec = parse(xml).bundle.tasks.single().actions.single()

    private fun tasker(
        names: String,
        namesSplitter: String,
        values: String,
        valuesSplitter: String,
        doMaths: String = "<Int sr=\"arg5\" val=\"0\"/>",
        keepExisting: String = "<Int sr=\"arg7\" val=\"0\"/>",
    ) = "<code>389</code><Bundle sr=\"arg0\"><Vals sr=\"val\"/></Bundle>" +
        str(1, names) + str(2, namesSplitter) + str(3, values) + str(4, valuesSplitter) +
        doMaths + "<Int sr=\"arg6\" val=\"3\"/>" + keepExisting + "<Int sr=\"arg8\" val=\"0\"/>"

    private fun str(index: Int, value: String) =
        if (value.isEmpty()) "<Str sr=\"arg$index\" ve=\"3\"/>" else "<Str sr=\"arg$index\" ve=\"3\">$value</Str>"

    private val placeholder = TaskerXmlImporter.TASKER_UNSUPPORTED_ACTION_ID

    @Test
    fun theSunriseStepComesAcrossWithItsNewLineSplitters() {
        val parsed = parse(tasker("%Sunrise\n%Sunset", "\n", "%ss_sunrise\n%ss_sunset", "\n"))
        val action = parsed.bundle.tasks.single().actions.single()
        assertEquals("var.set_multiple", action.type)
        assertEquals(
            mapOf(
                "names" to "%Sunrise\n%Sunset",
                "values" to "%ss_sunrise\n%ss_sunset",
                "names_splitter" to "\n",
                "values_splitter" to "\n",
            ),
            action.args,
        )
        assertTrue(parsed.lossyWarnings.toString(), parsed.lossyWarnings.isEmpty())
    }

    @Test
    fun aSpaceSplitterAndALeadingBlankValueSurvive() {
        val args = action(tasker("%a %b", " ", ",2", "")).args
        assertEquals(mapOf("names" to "%a %b", "values" to ",2", "names_splitter" to " "), args)
    }

    @Test
    fun keepExistingComesAcross() {
        val args = action(tasker("%a", "", "1", "", keepExisting = "<Int sr=\"arg7\" val=\"1\"/>")).args
        assertEquals("true", args["keep_existing"])
    }

    @Test
    fun doMathsTurnsVariablesAndSimpleSumsIntoTemplatesAndLeavesTextAlone() {
        val parsed = parse(tasker("%a\n%b\n%c\n%d", "", "5\n%x\n%y+1\nhello", "", doMaths = "<Int sr=\"arg5\" val=\"1\"/>"))
        assertEquals(
            "5\n{{ x | default:0 }}\n{{ y | default:0 | add:1 }}\nhello",
            parsed.bundle.tasks.single().actions.single().args["values"],
        )
        assertTrue(parsed.lossyWarnings.toString(), parsed.lossyWarnings.isEmpty())
    }

    @Test
    fun doMathsThisAppCantWorkOutIsSetAsWrittenAndNamed() {
        val parsed = parse(tasker("%a,%b", "", "(2+3)*4,%x", "", doMaths = "<Int sr=\"arg5\" val=\"1\"/>"))
        assertEquals("(2+3)*4,{{ x | default:0 }}", parsed.bundle.tasks.single().actions.single().args["values"])
        assertTrue(parsed.lossyWarnings.toString(), parsed.lossyWarnings.toString().contains("(2+3)*4"))
    }

    @Test
    fun aSwitchFromAVariableStaysAPlaceholder() {
        listOf(
            tasker("%a", "", "1", "", doMaths = "<Int sr=\"arg5\"><var>%maths</var></Int>"),
            tasker("%a", "", "1", "", keepExisting = "<Int sr=\"arg7\"><var>%keep</var></Int>"),
        ).forEach { assertEquals(it, placeholder, action(it).type) }
    }

    @Test
    fun exportWritesTaskersLayoutAndReadsBackTheSame() {
        val args = mapOf(
            "names" to "%Sunrise\n%Sunset",
            "values" to "%ss_sunrise\n%ss_sunset",
            "names_splitter" to "\n",
            "keep_existing" to "true",
        )
        val report = TaskerXmlExporter.export(
            emptyList(),
            listOf(Task(id = 1, name = "Exported", actions = listOf(ActionSpec(type = "var.set_multiple", args = args)))),
        )
        val xml = report.xml
        listOf(
            "<code>389</code>",
            "<Bundle sr=\"arg0\">",
            "<Str sr=\"arg1\" ve=\"3\">%Sunrise\n%Sunset</Str>",
            "<Str sr=\"arg2\" ve=\"3\">\n</Str>",
            "<Str sr=\"arg3\" ve=\"3\">%ss_sunrise\n%ss_sunset</Str>",
            "<Str sr=\"arg4\" ve=\"3\"></Str>",
            "<Int sr=\"arg5\" val=\"0\"/>",
            "<Int sr=\"arg6\" val=\"3\"/>",
            "<Int sr=\"arg7\" val=\"1\"/>",
            "<Int sr=\"arg8\" val=\"0\"/>",
        ).forEach { assertTrue("expected $it in:\n$xml", it in xml) }
        assertTrue(report.warnings.toString(), report.warnings.isEmpty())

        val back = TaskerXmlImporter.parse(rawXml = xml, appVersion = "test", importedAtEpochMs = 0L)
        assertEquals(args, back.bundle.tasks.single().actions.single().args)
    }
}
