package com.opentasker.core.transfer

import com.opentasker.core.actions.registerActionMetadata
import com.opentasker.core.model.ContextType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tasker matches a Wi-Fi Connected name against the whole network name, spaces at either end
 * included, while a state context here trims its value. A name with those spaces stays out of
 * the import with a note instead of coming in as a different name.
 */
class TaskerWifiNameSpacesImportTest {
    @Before
    fun setUp() {
        registerActionMetadata()
    }

    @Test
    fun aNameWithSpacesAtEitherEndStaysOut() {
        listOf("Home ", " Home", " Home*").forEach { ssid ->
            val report = importXml(time + wifi(ssid, sr = "con1"))

            assertEquals(ssid, listOf(ContextType.TIME), report.bundle.profiles.single().contexts.map { it.type })
            assertFalse(ssid, report.bundle.profiles.single().enabled)
            assertTrue(report.lossyWarnings.toString(), report.lossyWarnings.any { it.contains("code 160") })
        }
    }

    @Test
    fun aNameWithSpacesInsideStillComesIn() {
        val report = importXml(wifi("Home Office"))

        assertEquals(mapOf("key" to "wifi", "value" to "Home Office"), report.bundle.profiles.single().contexts.single().config)
    }

    private fun wifi(ssid: String, sr: String = "con0") =
        """<State sr="$sr" ve="2"><code>160</code><Str sr="arg0" ve="3">$ssid</Str><Str sr="arg1" ve="3"/><Str sr="arg2" ve="3"/><Int sr="arg3" val="2"/></State>"""

    private val time = """<Time sr="con0"><fh>22</fh><fm>0</fm><th>23</th><tm>30</tm></Time>"""

    // Not trimIndent: it would leave the names' spaces alone, but keeping the document on one line
    // makes sure nothing else touches them either.
    private fun importXml(context: String) = TaskerXmlImporter.parse(
        rawXml = """<TaskerData sr="" dvi="1" tv="6.7.8">""" +
            """<Profile sr="prof1" ve="2"><cdate>1</cdate><edate>1</edate><flags>8</flags><id>1</id><mid0>2</mid0><nme>P</nme>$context</Profile>""" +
            """<Task sr="task2"><id>2</id><nme>Run</nme><Action sr="act0"><code>61</code><Int sr="arg0" val="200"/></Action></Task>""" +
            """</TaskerData>""",
        appVersion = "test",
        importedAtEpochMs = 1L,
    )
}
