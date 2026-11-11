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

    // Tasker writes `<limit>` only for a profile whose switch is off, so the shared layout leaves it to [switch].
    private fun import(vararg contexts: String, switch: String = "") = TaskerXmlImporter.parse(
        rawXml = """
            <TaskerData sr="" dvi="1" tv="6.3.13">
                <Profile sr="prof1" ve="2">
                    <cdate>1</cdate>
                    <clp>true</clp>
                    <edate>1</edate>
                    <flags>8</flags>
                    <id>1</id>
                    $switch
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
    fun statesTaskerWroteComeInAsStates() {
        val power = """<State sr="con1" ve="2"><code>10</code><Int sr="arg0" val="0"/></State>"""
        val headset = """<State sr="con1" ve="2"><code>30</code><Int sr="arg0" val="0"/></State>"""
        val displayOff = """<State sr="con1" ve="2"><code>123</code><Int sr="arg0" val="0"/></State>"""
        val battery = """<State sr="con2" ve="2"><code>140</code><Int sr="arg0" val="25"/><Int sr="arg1" val="100"/></State>"""
        val wifi = """<State sr="con0" ve="2"><code>160</code><Str sr="arg0" ve="3">Home</Str><Str sr="arg1" ve="3"/><Str sr="arg2" ve="3"/><Int sr="arg3" val="2"/></State>"""

        assertEquals(mapOf("key" to "charging", "value" to "true"), profile(time, power).contexts[1].config)
        assertEquals(mapOf("key" to "headphones", "value" to "true"), profile(time, headset).contexts[1].config)
        assertEquals(mapOf("key" to "screen", "value" to "off"), profile(time, displayOff).contexts[1].config)
        assertEquals(mapOf("key" to "battery_level", "operator" to ">=", "value" to "25"), profile(time, battery).contexts[1].config)
        assertEquals(mapOf("key" to "wifi", "value" to "Home"), profile(time, wifi).contexts[1].config)
        assertTrue(profile(time, battery).enabled)
    }

    @Test
    fun eventsTaskerWroteComeInAsEvents() {
        val boot = """<Event sr="con0" ve="2"><code>411</code><pri>0</pri></Event>"""
        val facebook = """<Event sr="con0" ve="2"><code>461</code><pri>0</pri><App sr="arg0"><appClass>com.facebook.katana.LoginActivity</appClass><appPkg>com.facebook.katana</appPkg><label>Facebook</label></App><Str sr="arg1" ve="3"/><Str sr="arg2" ve="3"/><Str sr="arg3" ve="3"/><Str sr="arg4" ve="3"/><Str sr="arg5" ve="3"/><Str sr="arg6" ve="3"/><Int sr="arg7" val="0"/></Event>"""

        assertEquals(mapOf("event" to "boot_completed"), profile(boot).contexts.single().config)
        assertEquals(mapOf("event" to "notification", "package" to "com.facebook.katana"), profile(facebook).contexts.single().config)
    }

    @Test
    fun whatTasker678WritesComesInToo() {
        // 6.7.8 adds arg4 (check after unlock) to Wifi, and an App gains cls0 with its pin before pkg0.
        val wifi = """<State sr="con0" ve="2"><code>160</code><Str sr="arg0" ve="3">Home</Str><Str sr="arg1" ve="3"/><Str sr="arg2" ve="3"/><Int sr="arg3" val="2"/><Int sr="arg4" val="1"/></State>"""
        val noWifi = """<State sr="con0" ve="2"><code>160</code><pin>true</pin><Str sr="arg0" ve="3"/><Str sr="arg1" ve="3"/><Str sr="arg2" ve="3"/><Int sr="arg3" val="2"/><Int sr="arg4" val="1"/></State>"""
        val notChrome = """<App sr="con0" ve="2"><cls0>com.google.android.apps.chrome.Main</cls0><flags>2</flags><label0>com.android.chrome</label0><pin>true</pin><pkg0>com.android.chrome</pkg0></App>"""
        val chromeNotification = """<Event sr="con0" ve="2"><code>461</code><App sr="arg0"><appPkg>com.android.chrome</appPkg><label>com.android.chrome</label></App><Str sr="arg1" ve="3"/><Str sr="arg2" ve="3"/><Str sr="arg3" ve="3"/><Str sr="arg4" ve="3"/><Str sr="arg5" ve="3"/><Str sr="arg6" ve="3"/><Int sr="arg7" val="0"/></Event>"""

        assertEquals(mapOf("key" to "wifi", "value" to "Home"), profile(wifi).contexts.single().config)
        assertEquals(mapOf("key" to "wifi", "value" to "connected"), profile(noWifi).contexts.single().config)
        assertTrue(profile(noWifi).contexts.single().invert)
        assertEquals(mapOf("package" to "com.android.chrome"), profile(notChrome).contexts.single().config)
        assertTrue(profile(notChrome).contexts.single().invert)
        assertEquals(mapOf("event" to "notification", "package" to "com.android.chrome"), profile(chromeNotification).contexts.single().config)
        listOf(wifi, noWifi, notChrome, chromeNotification).forEach { assertTrue(it, profile(it).enabled) }
    }

    @Test
    fun aSettingThereIsNoRoomForStillTurnsTheProfileOff() {
        val acOnly = """<State sr="con1" ve="2"><code>10</code><Int sr="arg0" val="1"/></State>"""
        val wifiList = """<State sr="con1" ve="2"><cname>Home Wifi Connected</cname><code>160</code><Str sr="arg0" ve="3">%mc_wifi_home_list</Str><Str sr="arg1" ve="3"/><Str sr="arg2" ve="3"/><Int sr="arg3" val="2"/></State>"""
        val midRange = """<State sr="con1" ve="2"><code>140</code><Int sr="arg0" val="20"/><Int sr="arg1" val="80"/></State>"""
        val titled = """<Event sr="con1" ve="2"><code>461</code><App sr="arg0"/><Str sr="arg1" ve="3">Door</Str><Str sr="arg2" ve="3"/><Str sr="arg3" ve="3"/><Str sr="arg4" ve="3"/><Str sr="arg5" ve="3"/><Str sr="arg6" ve="3"/><Int sr="arg7" val="0"/></Event>"""
        // Here "wifi=Off" means Wi-Fi is off, not a network called Off.
        val networkCalledOff = """<State sr="con1" ve="2"><code>160</code><Str sr="arg0" ve="3">Off</Str><Str sr="arg1" ve="3"/><Str sr="arg2" ve="3"/><Int sr="arg3" val="2"/></State>"""

        listOf(
            acOnly to "code 10",
            wifiList to "code 160",
            midRange to "code 140",
            titled to "code 461",
            networkCalledOff to "code 160",
        ).forEach { (context, code) ->
            val report = import(time, context)
            assertEquals(listOf(ContextType.TIME), report.bundle.profiles.single().contexts.map { it.type })
            assertFalse(context, report.bundle.profiles.single().enabled)
            assertTrue(report.lossyWarnings.toString(), report.lossyWarnings.any { it.contains(code) })
        }
    }

    @Test
    fun aDayOfTheMonthCantBeReadEither() {
        val dates = """<Day sr="con1"><mday0>15</mday0></Day>"""

        val report = import(time, dates)

        assertFalse(report.bundle.profiles.single().enabled)
        assertTrue(report.lossyWarnings.toString(), report.lossyWarnings.any { it.contains("dates of the month") })
    }

    @Test
    fun aProfileSwitchedOffInTaskerComesInSwitchedOff() {
        // What Tasker 6.7.8 wrote for the same profile with its switch off, then on.
        assertFalse(import(time, switch = "<limit>true</limit>").bundle.profiles.single().enabled)
        assertTrue(profile(time).enabled)
    }

    @Test
    fun aProfileWithRunsLeftIsStillOn() {
        val report = import(time, switch = "<limit>true</limit><repeats>2</repeats>")

        assertTrue(report.bundle.profiles.single().enabled)
        assertTrue(report.lossyWarnings.toString(), report.lossyWarnings.any { it.contains("2 more runs") })
        assertFalse(report.lossyWarnings.toString(), report.lossyWarnings.any { it.contains("unsupported Tasker context") })
    }

    @Test
    fun aTimeWindowBetweenTwoGlobalsKeepsThem() {
        // From Tasker 6.6.0-beta's "Sunrise/Sunset Examples" project: a midnight profile stores
        // %Sunrise and %Sunset, and this Time context reads them.
        val sun = """<Time sr="con0"><fromvar>%Sunrise</fromvar><tovar>%Sunset</tovar></Time>"""

        val report = import(sun)
        val imported = report.bundle.profiles.single()

        assertEquals(mapOf("start" to "%Sunrise", "end" to "%Sunset"), imported.contexts.single().config)
        assertTrue(imported.enabled)
        assertFalse(report.lossyWarnings.toString(), report.lossyWarnings.any { it.contains("Time context") })
    }

    @Test
    fun aTimeWindowBoundedByALocalCantBeRead() {
        val local = """<Time sr="con0"><fromvar>%sunrise</fromvar><th>23</th><tm>0</tm></Time>"""
        val day = """<Day sr="con1"><wday0>2</wday0></Day>"""

        val report = import(local, day)

        assertEquals(listOf(ContextType.DAY), report.bundle.profiles.single().contexts.map { it.type })
        assertFalse(report.bundle.profiles.single().enabled)
        assertTrue(report.lossyWarnings.toString(), report.lossyWarnings.any { it.contains("%sunrise") })
    }

    @Test
    fun fieldsThatArentContextsDontCountAsLostOnes() {
        val report = import(time)

        assertTrue(report.bundle.profiles.single().enabled)
        assertFalse(report.lossyWarnings.toString(), report.lossyWarnings.any { it.contains("unsupported Tasker context") })
    }
}
