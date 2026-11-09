package com.opentasker.core.transfer

import com.opentasker.core.model.ContextType
import com.opentasker.core.model.Profile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Context shapes copied from exports Tasker wrote itself. */
class TaskerRealProfileContextsTest {
    private val time = """<Time sr="con0"><fh>22</fh><fm>0</fm><th>23</th><tm>30</tm></Time>"""

    private fun import(vararg contexts: String) = TaskerXmlImporter.parse(
        rawXml = """
            <TaskerData sr="" dvi="1" tv="6.3.13">
                <Profile sr="prof1" ve="2">
                    <cdate>1</cdate>
                    <clp>true</clp>
                    <edate>1</edate>
                    <flags>8</flags>
                    <id>1</id>
                    <limit>true</limit>
                    <mid0>2</mid0>
                    <nme>Evening</nme>
                    ${contexts.joinToString("")}
                </Profile>
                <Task sr="task2"><id>2</id><nme>Run</nme><Action sr="act0"><code>548</code><Str sr="arg0">Hi</Str></Action></Task>
            </TaskerData>
        """.trimIndent(),
        appVersion = "test",
        importedAtEpochMs = 1L,
    )

    private fun profile(vararg contexts: String): Profile = import(*contexts).bundle.profiles.single()

    @Test
    fun aDayContextKeepsItsWeekdays() {
        val day = """<Day sr="con1"><wday0>2</wday0><wday1>3</wday1><wday2>4</wday2><wday3>5</wday3><wday4>6</wday4></Day>"""

        val imported = profile(time, day)

        assertEquals(listOf(ContextType.TIME, ContextType.DAY), imported.contexts.map { it.type })
        assertEquals("MON,TUE,WED,THU,FRI", imported.contexts[1].config["days"])
        assertTrue(imported.enabled)
    }

    @Test
    fun sundayIsDayOne() {
        val weekend = """<Day sr="con1" ve="2"><wday0>1</wday0><wday1>7</wday1></Day>"""

        assertEquals("SAT,SUN", profile(time, weekend).contexts[1].config["days"])
    }

    @Test
    fun anAppContextKeepsEveryApp() {
        val app = """<App sr="con0" ve="2"><cls0>a.Main</cls0><flags>2</flags><label0>A</label0><pkg0>com.a</pkg0><cls1>b.Main</cls1><label1>B</label1><pkg1>com.b</pkg1></App>"""

        val imported = profile(app)

        assertEquals("com.a,com.b", imported.contexts.single().config["package"])
        assertTrue(imported.enabled)
    }

    @Test
    fun anInvertedContextStaysInverted() {
        val notWeekdays = """<Day sr="con1"><pin>true</pin><wday0>2</wday0></Day>"""

        assertTrue(profile(time, notWeekdays).contexts[1].invert)
        assertFalse(profile(time).contexts.single().invert)
    }

    @Test
    fun aContextThatCantBeReadTurnsTheProfileOffInsteadOfBroadeningIt() {
        val state = """<State sr="con1" ve="2"><code>5</code><Str sr="arg0" ve="3"/></State>"""

        val report = import(time, state)
        val imported = report.bundle.profiles.single()

        assertEquals(listOf(ContextType.TIME), imported.contexts.map { it.type })
        assertFalse("a Time-only copy would fire every day", imported.enabled)
        assertTrue(report.lossyWarnings.toString(), report.lossyWarnings.any { it.contains("code 5") })
        assertTrue(report.lossyWarnings.toString(), report.lossyWarnings.any { it.contains("turned off") })
    }

    @Test
    fun aDayOfTheMonthCantBeReadEither() {
        val dates = """<Day sr="con1"><mday0>15</mday0></Day>"""

        val report = import(time, dates)

        assertFalse(report.bundle.profiles.single().enabled)
        assertTrue(report.lossyWarnings.toString(), report.lossyWarnings.any { it.contains("dates of the month") })
    }

    @Test
    fun fieldsThatArentContextsDontCountAsLostOnes() {
        val report = import(time)

        assertTrue(report.bundle.profiles.single().enabled)
        assertFalse(report.lossyWarnings.toString(), report.lossyWarnings.any { it.contains("unsupported Tasker context") })
    }
}
