package com.opentasker.core.transfer

import com.opentasker.core.actions.registerActionMetadata
import com.opentasker.core.contexts.DaySchedule
import com.opentasker.core.contexts.stateMatches
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

/** Contexts go out in the layout Tasker writes itself, so Tasker can read them back. */
class TaskerContextExportTest {
    @Before
    fun setUp() {
        registerActionMetadata()
    }

    private val task = Task(id = 1, name = "Run", actions = listOf(ActionSpec(type = "vibrate")))

    private fun export(vararg contexts: ContextSpec) = TaskerXmlExporter.export(
        listOf(Profile(id = 1, name = "P", enterTaskId = 1, contexts = contexts.toList())),
        listOf(task),
    )

    private fun roundTrip(context: ContextSpec): ContextSpec {
        val report = export(context)
        assertTrue(report.warnings.toString(), report.warnings.isEmpty())
        val imported = TaskerXmlImporter.parse(report.xml, appVersion = "test", importedAtEpochMs = 0L)
        assertTrue(imported.lossyWarnings.toString(), imported.lossyWarnings.isEmpty())
        return imported.bundle.profiles.single().contexts.single()
    }

    private fun ContextSpec.holdsIn(state: Map<String, String>): Boolean {
        val operator = config["operator"]?.takeIf(String::isNotBlank) ?: "="
        return stateMatches("${config["key"]}$operator${config["value"]}", state) != invert
    }

    // A matcher reads the merged state every source has published, so each sample is a whole state
    // with one thing changed. An inverted context and its opposite only differ while a key is missing.
    private val seeded = mapOf(
        "battery_level" to "50",
        "charging" to "false",
        "headphones" to "false",
        "screen" to "on",
        "wifi_connected" to "true",
        "wifi" to "Home",
    )

    private val sampleStates: List<Map<String, String>> =
        (0..100).map { seeded + ("battery_level" to it.toString()) } +
            listOf("true", "false").flatMap { flag -> listOf(seeded + ("charging" to flag), seeded + ("headphones" to flag)) } +
            listOf(seeded + ("screen" to "off")) +
            listOf(
                seeded + ("wifi" to "Work"),
                seeded + mapOf("wifi_connected" to "false", "wifi" to "disconnected"),
            )

    private fun assertSameStates(original: ContextSpec) {
        val back = roundTrip(original)
        sampleStates.forEach { state ->
            assertEquals("$original vs $back in $state", original.holdsIn(state), back.holdsIn(state))
        }
    }

    @Test
    fun aDayIsWrittenAsTaskerWeekdayNumbers() {
        val xml = export(ContextSpec(ContextType.DAY, mapOf("days" to "MON,SUN"))).xml

        assertTrue(xml, xml.contains("""<Day sr="con0" ve="2">"""))
        assertTrue(xml, xml.contains("<wday0>1</wday0>"))
        assertTrue(xml, xml.contains("<wday1>2</wday1>"))
        assertFalse(xml, xml.contains("<days>"))
    }

    @Test
    fun anInvertedDayGoesOutAsTheOtherDays() {
        val notMonday = ContextSpec(ContextType.DAY, mapOf("days" to "MON"), invert = true)
        val xml = export(notMonday).xml
        val back = roundTrip(notMonday)

        assertFalse(xml, xml.contains("<pin>"))
        assertFalse(xml, xml.contains("<wday0>2</wday0>"))
        assertTrue(xml, xml.contains("<wday5>7</wday5>"))
        assertFalse(back.invert)
        DaySchedule.orderedDays.forEach { day ->
            assertEquals(day, DaySchedule.matches("MON", day) != notMonday.invert, DaySchedule.matches(back.config["days"].orEmpty(), day))
        }
    }

    @Test
    fun anInvertedDayLeavingNoDaysStaysOut() {
        val report = export(ContextSpec(ContextType.DAY, mapOf("days" to "MON,TUE,WED,THU,FRI,SAT,SUN"), invert = true))

        assertFalse(report.xml, report.xml.contains("<Day"))
        assertTrue(report.warnings.toString(), report.warnings.any { it.contains("DAY (inverted)") })
    }

    @Test
    fun anInvertedTimeStaysOut() {
        val report = export(ContextSpec(ContextType.TIME, mapOf("start" to "22:00", "end" to "23:30"), invert = true))

        assertFalse(report.xml, report.xml.contains("<Time"))
        assertTrue(report.warnings.toString(), report.warnings.any { it.contains("TIME (inverted)") })
    }

    @Test
    fun aContextLeftOutLeavesNoGapInTheNumbering() {
        val xml = export(
            ContextSpec(ContextType.TIME, mapOf("start" to "22:00", "end" to "23:30"), invert = true),
            ContextSpec(ContextType.DAY, mapOf("days" to "MON")),
        ).xml

        assertTrue(xml, xml.contains("""<Day sr="con0" ve="2">"""))
        assertFalse(xml, xml.contains("con1"))
    }

    @Test
    fun anEitherOrGroupIsNamed() {
        val either = export(
            ContextSpec(ContextType.DAY, mapOf("days" to "MON"), orGroup = "g"),
            ContextSpec(ContextType.DAY, mapOf("days" to "SAT"), orGroup = "g"),
        )
        val alone = export(ContextSpec(ContextType.DAY, mapOf("days" to "MON"), orGroup = "g"))

        assertTrue(either.warnings.toString(), either.warnings.any { it.contains("any one context") })
        assertTrue(alone.warnings.toString(), alone.warnings.isEmpty())
    }

    @Test
    fun aBlankSettingFiltersNothingSoNothingIsLost() {
        val report = export(ContextSpec(ContextType.APPLICATION, mapOf("package" to "com.a", "component" to " ")))

        assertTrue(report.xml, report.xml.contains("<pkg0>com.a</pkg0>"))
        assertTrue(report.warnings.toString(), report.warnings.isEmpty())
    }

    @Test
    fun anAppContextListsEachPackage() {
        val xml = export(ContextSpec(ContextType.APPLICATION, mapOf("package" to "com.a, com.b"))).xml

        assertTrue(xml, xml.contains("""<App sr="con0" ve="2">"""))
        assertTrue(xml, xml.contains("<flags>2</flags>"))
        assertTrue(xml, xml.contains("<pkg0>com.a</pkg0>"))
        assertTrue(xml, xml.contains("<pkg1>com.b</pkg1>"))
        assertFalse(xml, xml.contains("<Application"))
    }

    @Test
    fun aBatteryComparisonBecomesBatteryLevelFromTo() {
        val report = export(ContextSpec(ContextType.STATE, mapOf("key" to "battery", "operator" to "<", "value" to "20")))

        assertTrue(report.warnings.toString(), report.warnings.isEmpty())
        assertTrue(report.xml, report.xml.contains("<code>140</code>"))
        assertTrue(report.xml, report.xml.contains("""<Int sr="arg0" val="0"/>"""))
        assertTrue(report.xml, report.xml.contains("""<Int sr="arg1" val="19"/>"""))
    }

    @Test
    fun notChargingIsAnInvertedPowerState() {
        val xml = export(ContextSpec(ContextType.STATE, mapOf("key" to "charging", "value" to "false"))).xml

        assertTrue(xml, xml.contains("<code>10</code>"))
        assertTrue(xml, xml.contains("<pin>true</pin>"))
        assertFalse(
            export(ContextSpec(ContextType.STATE, mapOf("key" to "charging", "value" to "false"), invert = true)).xml.contains("<pin>"),
        )
    }

    @Test
    fun aWifiNameGoesInTheSsidField() {
        val xml = export(ContextSpec(ContextType.STATE, mapOf("predicate" to "wifi=Home"))).xml

        assertTrue(xml, xml.contains("<code>160</code>"))
        assertTrue(xml, xml.contains("""<Str sr="arg0" ve="3">Home</Str>"""))
        assertTrue(xml, xml.contains("""<Int sr="arg3" val="2"/>"""))
    }

    @Test
    fun aWifiNameTaskerWouldReadAsAPatternStaysOut() {
        val report = export(ContextSpec(ContextType.STATE, mapOf("key" to "wifi", "value" to "Home/Work")))

        assertFalse(report.xml, report.xml.contains("<State"))
        assertTrue(report.warnings.toString(), report.warnings.any { it.contains("no Tasker equivalent") })
    }

    @Test
    fun aNotificationFromOneAppNamesTheOwner() {
        val xml = export(ContextSpec(ContextType.EVENT, mapOf("event" to "notification", "package" to "com.a"))).xml

        assertTrue(xml, xml.contains("<code>461</code>"))
        assertTrue(xml, xml.contains("<appPkg>com.a</appPkg>"))
        assertTrue(xml, xml.contains("""<Int sr="arg7" val="0"/>"""))
    }

    @Test
    fun anEventWithAFilterTaskerCantHoldStaysOut() {
        val report = export(ContextSpec(ContextType.EVENT, mapOf("event" to "boot_completed", "package" to "com.a")))

        assertFalse(report.xml, report.xml.contains("<Event"))
        assertTrue(report.warnings.toString(), report.warnings.any { it.contains("package") })
    }

    @Test
    fun anAppContextWatchingOneScreenStaysOut() {
        val report = export(ContextSpec(ContextType.APPLICATION, mapOf("package" to "com.a", "component" to "com.a/.Settings")))

        assertFalse(report.xml, report.xml.contains("<App sr=\"con"))
        assertTrue(report.warnings.toString(), report.warnings.any { it.contains("component") })
    }

    @Test
    fun aSemicolonSeparatesPackagesTheWayTheMatcherReadsThem() {
        val app = export(ContextSpec(ContextType.APPLICATION, mapOf("package" to "com.a;com.b"))).xml
        val twoOwners = export(ContextSpec(ContextType.EVENT, mapOf("event" to "notification", "package" to "com.a;com.b")))

        assertTrue(app, app.contains("<pkg1>com.b</pkg1>"))
        assertFalse(twoOwners.xml, twoOwners.xml.contains("<Event"))
    }

    @Test
    fun statesTaskerCantMatchTheSameWayStayOut() {
        listOf(
            mapOf("key" to "wifi", "value" to ""),
            mapOf("predicate" to "wifi="),
            mapOf("predicate" to "charging", "key" to "charging", "value" to "true"),
            mapOf("key" to "battery", "value" to "050"),
        ).forEach { config ->
            val report = export(ContextSpec(ContextType.STATE, config))
            assertFalse("$config\n${report.xml}", report.xml.contains("<State"))
        }
    }

    @Test
    fun statesComeBackMatchingTheSameThings() {
        listOf(
            mapOf("key" to "battery", "value" to "50"),
            mapOf("key" to "battery", "operator" to "<", "value" to "20"),
            mapOf("key" to "battery_level", "operator" to ">=", "value" to "80"),
            mapOf("key" to "battery", "operator" to ">", "value" to "0"),
            mapOf("key" to "battery", "operator" to "<=", "value" to "100"),
            mapOf("key" to "charging", "value" to "true"),
            mapOf("key" to "charging", "value" to "unplugged"),
            mapOf("key" to "headset", "value" to "connected"),
            mapOf("key" to "screen", "value" to "off"),
            mapOf("key" to "screen", "value" to "on"),
            mapOf("key" to "wifi", "value" to "Home"),
            mapOf("key" to "wifi", "value" to "connected"),
            mapOf("key" to "ssid", "value" to "disconnected"),
        ).forEach { config ->
            assertSameStates(ContextSpec(ContextType.STATE, config))
            assertSameStates(ContextSpec(ContextType.STATE, config, invert = true))
        }
    }

    @Test
    fun daysAppsAndEventsComeBackUnchanged() {
        val day = roundTrip(ContextSpec(ContextType.DAY, mapOf("days" to "MON,WED,SUN")))
        val app = roundTrip(ContextSpec(ContextType.APPLICATION, mapOf("package" to "com.a,com.b")))
        val boot = roundTrip(ContextSpec(ContextType.EVENT, mapOf("event" to "boot_completed")))
        val anyNotification = roundTrip(ContextSpec(ContextType.EVENT, mapOf("event" to "notification")))
        val oneApp = roundTrip(ContextSpec(ContextType.EVENT, mapOf("event" to "notification", "package" to "com.a"), invert = true))

        assertEquals(mapOf("days" to "MON,WED,SUN"), day.config)
        assertEquals(mapOf("package" to "com.a,com.b"), app.config)
        assertEquals(mapOf("event" to "boot_completed"), boot.config)
        assertEquals(mapOf("event" to "notification"), anyNotification.config)
        assertEquals(mapOf("event" to "notification", "package" to "com.a"), oneApp.config)
        assertTrue(oneApp.invert)
    }
}
