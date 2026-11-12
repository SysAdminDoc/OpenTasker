package com.opentasker.core.transfer

import com.opentasker.core.actions.registerActionMetadata
import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.ContextSpec
import com.opentasker.core.model.ContextType
import com.opentasker.core.model.Profile
import com.opentasker.core.model.Task
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A Wi-Fi network name and a notification's title and text are Tasker patterns in Tasker, so they
 * come in as patterns and go back out as the same text.
 */
class TaskerFilterPatternTransferTest {
    @Before
    fun setUp() {
        registerActionMetadata()
    }

    @Test
    fun aWifiNameThatIsAPatternComesInAsOne() {
        listOf("home/work", "Home*", "!Office", "home", "Off", "~Rguest\\d").forEach { ssid ->
            assertEquals(ssid, mapOf("key" to "wifi", "operator" to "~", "value" to ssid), imported(wifi(ssid)).config)
        }
    }

    @Test
    fun aPlainWifiNameStillComesInAsAName() {
        assertEquals(mapOf("key" to "wifi", "value" to "Home"), imported(wifi("Home")).config)
        assertEquals(mapOf("key" to "wifi", "value" to "Café 5G"), imported(wifi("Café 5G")).config)
    }

    @Test
    fun aNotificationTitleAndTextComeInAsPatterns() {
        val context = imported(notification(title = "Door*", text = "*open*"))

        assertEquals(mapOf("event" to "notification", "title" to "Door*", "body" to "*open*", "patterns" to "tasker"), context.config)
    }

    @Test
    fun aNotificationSubtextStillTurnsTheProfileOff() {
        // A profile left with no contexts isn't kept at all, so a Time context holds this one.
        val report = importXml(time + notification(subtext = "Door", sr = "con1"))

        assertEquals(listOf(ContextType.TIME), report.bundle.profiles.single().contexts.map { it.type })
        assertFalse(report.bundle.profiles.single().enabled)
        assertTrue(report.lossyWarnings.toString(), report.lossyWarnings.any { it.contains("code 461") })
    }

    @Test
    fun patternsGoBackOutAsTheSameText() {
        val homeOrWork = ContextSpec(ContextType.STATE, mapOf("key" to "wifi", "operator" to "~", "value" to "home/work"))
        val door = ContextSpec(ContextType.EVENT, mapOf("event" to "notification", "title" to "Door*", "body" to "*open*", "patterns" to "tasker"))

        val xml = export(homeOrWork, door)

        assertTrue(xml, xml.contains("""<Str sr="arg0" ve="3">home/work</Str>"""))
        assertTrue(xml, xml.contains("""<Str sr="arg1" ve="3">Door*</Str>"""))
        assertTrue(xml, xml.contains("""<Str sr="arg2" ve="3">*open*</Str>"""))
        val back = TaskerXmlImporter.parse(xml, appVersion = "test", importedAtEpochMs = 0L).bundle.profiles.map { it.contexts.single().config }
        assertEquals(listOf(homeOrWork.config, door.config), back)
    }

    @Test
    fun aPatternNamingAVariableStaysOut() {
        val report = TaskerXmlExporter.export(
            listOf(Profile(id = 1, name = "P", enterTaskId = 1, contexts = listOf(ContextSpec(ContextType.STATE, mapOf("key" to "wifi", "operator" to "~", "value" to "%HOME"))))),
            listOf(task),
        )

        assertFalse(report.xml, report.xml.contains("<State"))
    }

    private val task = Task(id = 1, name = "Run", actions = listOf(ActionSpec(type = "vibrate")))

    private fun export(vararg contexts: ContextSpec): String {
        val report = TaskerXmlExporter.export(
            contexts.mapIndexed { index, context -> Profile(id = index + 1L, name = "P$index", enterTaskId = 1, contexts = listOf(context)) },
            listOf(task),
        )
        assertTrue(report.warnings.toString(), report.warnings.isEmpty())
        return report.xml
    }

    private fun wifi(ssid: String) =
        """<State sr="con0" ve="2"><code>160</code><Str sr="arg0" ve="3">${escapeXml(ssid)}</Str><Str sr="arg1" ve="3"/><Str sr="arg2" ve="3"/><Int sr="arg3" val="2"/></State>"""

    private val time = """<Time sr="con0"><fh>22</fh><fm>0</fm><th>23</th><tm>30</tm></Time>"""

    private fun notification(title: String = "", text: String = "", subtext: String = "", sr: String = "con0") =
        """<Event sr="$sr" ve="2"><code>461</code><App sr="arg0"/>""" +
            listOf(title, text, subtext, "", "", "").mapIndexed { index, value -> """<Str sr="arg${index + 1}" ve="3">${escapeXml(value)}</Str>""" }.joinToString("") +
            """<Int sr="arg7" val="0"/></Event>"""

    private fun escapeXml(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun importXml(context: String) = TaskerXmlImporter.parse(
        rawXml = """
            <TaskerData sr="" dvi="1" tv="6.7.8">
                <Profile sr="prof1" ve="2"><cdate>1</cdate><edate>1</edate><flags>8</flags><id>1</id><mid0>2</mid0><nme>P</nme>$context</Profile>
                <Task sr="task2"><id>2</id><nme>Run</nme><Action sr="act0"><code>61</code><Int sr="arg0" val="200"/></Action></Task>
            </TaskerData>
        """.trimIndent(),
        appVersion = "test",
        importedAtEpochMs = 1L,
    )

    private fun imported(context: String): ContextSpec {
        val report = importXml(context)
        assertTrue(report.lossyWarnings.toString(), report.lossyWarnings.isEmpty())
        return report.bundle.profiles.single().contexts.single()
    }
}
