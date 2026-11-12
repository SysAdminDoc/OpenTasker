package com.opentasker.core.transfer

import com.opentasker.core.actions.registerActionMetadata
import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.Task
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Tasker's Get Sunrise/Sunset Times (473), laid out the way Tasker 6.7.8 exports it. */
class TaskerSunTimesTransferTest {

    @Before
    fun setUp() {
        registerActionMetadata()
    }

    private fun parse(actionXml: String) =
        TaskerXmlImporter.parse(
            rawXml = "<TaskerData><Task sr=\"task1\"><id>1</id><nme>Imported</nme>" +
                "<Action sr=\"act0\">$actionXml</Action></Task></TaskerData>",
            appVersion = "test",
            importedAtEpochMs = 0L,
        )

    private fun export(vararg args: Pair<String, String>) =
        TaskerXmlExporter.export(
            emptyList(),
            listOf(Task(id = 1, name = "Exported", actions = listOf(ActionSpec(type = "sun.times", args = mapOf(*args))))),
        )

    private fun tasker(lat: String, lon: String, time: String, elevations: String? = null) =
        "<code>473</code><Bundle sr=\"arg0\"><Vals sr=\"val\"/></Bundle>" +
            str(1, lat) + str(2, lon) + str(3, time) + (elevations?.let { str(4, it) } ?: "")

    private fun str(index: Int, value: String) =
        if (value.isEmpty()) "<Str sr=\"arg$index\" ve=\"3\"/>" else "<Str sr=\"arg$index\" ve=\"3\">$value</Str>"

    @Test
    fun typedCoordinatesComeAcrossAsTheyAre() {
        val parsed = parse(tasker("40.6972846", "-74.1443129", ""))
        val action = parsed.bundle.tasks.single().actions.single()
        assertEquals("sun.times", action.type)
        assertEquals(mapOf("latitude" to "40.6972846", "longitude" to "-74.1443129"), action.args)
        assertTrue(parsed.lossyWarnings.toString(), parsed.lossyWarnings.isEmpty())
    }

    @Test
    fun blankFieldsStayBlankSoTheyMeanThePhonesLocationAndNow() {
        val parsed = parse(tasker("", "", ""))
        assertEquals(emptyMap<String, String>(), parsed.bundle.tasks.single().actions.single().args)
        assertTrue(parsed.lossyWarnings.toString(), parsed.lossyWarnings.isEmpty())
    }

    @Test
    fun timeAndElevationsComeAcrossWithTheirLineBreaks() {
        val action = parse(tasker("%LAT", "%LON", "%TIMES", "-3\n10")).bundle.tasks.single().actions.single()
        assertEquals(
            mapOf("latitude" to "%LAT", "longitude" to "%LON", "time" to "%TIMES", "elevations" to "-3\n10"),
            action.args,
        )
    }

    @Test
    fun exportWritesTaskersLayoutAndReadsBackTheSame() {
        val args = arrayOf("latitude" to "40.6972846", "longitude" to "-74.1443129", "time" to "%TIMES", "elevations" to "-3,10")
        val report = export(*args)
        val xml = report.xml
        listOf(
            "<code>473</code>",
            "<Bundle sr=\"arg0\">",
            "<Vals sr=\"val\"/>",
            "<Str sr=\"arg1\" ve=\"3\">40.6972846</Str>",
            "<Str sr=\"arg2\" ve=\"3\">-74.1443129</Str>",
            "<Str sr=\"arg3\" ve=\"3\">%TIMES</Str>",
            "<Str sr=\"arg4\" ve=\"3\">-3,10</Str>",
        ).forEach { assertTrue("expected $it in:\n$xml", it in xml) }
        assertTrue(report.warnings.toString(), report.warnings.isEmpty())

        val back = TaskerXmlImporter.parse(xml, appVersion = "test", importedAtEpochMs = 0L)
            .bundle.tasks.single().actions.single()
        assertEquals("sun.times", back.type)
        assertEquals(mapOf(*args), back.args)
    }

    @Test
    fun aPrefixOtherThanSsIsWarnedAboutBecauseTaskerAlwaysUsesSs() {
        assertTrue(export("var" to "%ss").warnings.isEmpty())
        assertTrue(export("var" to "ss").warnings.isEmpty())
        val warnings = export("var" to "%Sun").warnings
        assertTrue(warnings.toString(), warnings.any { "'Sun'" in it && "%ss_" in it })
    }
}
