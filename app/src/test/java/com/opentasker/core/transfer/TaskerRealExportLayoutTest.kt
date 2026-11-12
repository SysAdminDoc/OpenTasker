package com.opentasker.core.transfer

import android.content.ContextWrapper
import com.opentasker.core.actions.SetVariableAction
import com.opentasker.core.actions.TextReplaceAction
import com.opentasker.core.actions.TextSubstringAction
import com.opentasker.core.engine.ActionContext
import com.opentasker.core.engine.ActionRegistry
import com.opentasker.core.engine.FlowControl
import com.opentasker.core.engine.TaskRunner
import com.opentasker.core.engine.VariableStore
import com.opentasker.core.expressions.TemplateExpressionEngine
import com.opentasker.core.expressions.TemplateScope
import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.VariableNamePolicy
import com.opentasker.core.registerCoreRuntime
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every fixture here copies the layout of an action from a real Tasker export found on GitHub, with
 * field meanings from Tasker's own action table as vendored by Map-Tasker. Codes 116, 306, 312 and
 * 333 are the exception: no public export holds them, so those follow the table alone.
 */
class TaskerRealExportLayoutTest {

    private fun parse(vararg actions: String) =
        TaskerXmlImporter.parse(
            rawXml = "<TaskerData><Task sr=\"task1\"><id>1</id><nme>Imported</nme>" +
                actions.withIndex().joinToString("") { (index, xml) -> "<Action sr=\"act$index\">$xml</Action>" } +
                "</Task></TaskerData>",
            appVersion = "test",
            importedAtEpochMs = 0L,
        )

    private fun action(xml: String): ActionSpec = parse(xml).bundle.tasks.single().actions.single()

    private fun warnings(xml: String): List<String> = parse(xml).lossyWarnings

    private fun assertWarns(xml: String, text: String) {
        val all = warnings(xml)
        assertTrue("expected a warning naming '$text', got $all", all.any { text in it })
    }

    private fun expand(template: String, vararg task: Pair<String, String>): String =
        TemplateExpressionEngine().expand(template, TemplateScope(task = mapOf(*task))).value

    // For (39)

    @Test
    fun forReadsTheVariableFirstAndTheItemsSecond() {
        val action = action("""<code>39</code><Str sr="arg0" ve="3">%pair</Str><Str sr="arg1" ve="3">%input()</Str>""")

        assertEquals("flow.foreach", action.type)
        assertEquals("%pair", action.args["var"])
        assertEquals("input", action.args["list"])
    }

    @Test
    fun forOverARangeOrAWrittenOutListSaysItNeedsAnArray() {
        val range = """<code>39</code><Str sr="arg0" ve="3">%i</Str><Str sr="arg1" ve="3">1:%HTTPD(#)</Str>"""
        assertEquals("1:%HTTPD(#)", action(range).args["list"])
        assertWarns(range, "1:%HTTPD(#)")

        assertWarns("""<code>39</code><Str sr="arg0" ve="3">%i</Str><Str sr="arg1" ve="3">a,b,c</Str>""", "a,b,c")
    }

    // Display Timeout (812)

    @Test
    fun aDisplayTimeoutWithNoTimeSetDoesNotBecomeAOneSecondTimeout() {
        val zeros = """<code>812</code><Int sr="arg0" val="0"/><Int sr="arg1" val="0"/><Int sr="arg2" val="0"/>"""

        assertEquals(TaskerXmlImporter.TASKER_UNSUPPORTED_ACTION_ID, action(zeros).type)
        assertWarns(zeros, "no time set")
    }

    @Test
    fun displayTimeoutKeepsEachFieldInItsOwnUnit() {
        val seconds = """<code>812</code><Int sr="arg0" val="15"/><Int sr="arg1" val="0"/><Int sr="arg2" val="0"/>"""
        val minutes = """<code>812</code><Int sr="arg0" val="0"/><Int sr="arg1" val="5"/><Int sr="arg2" val="0"/>"""

        assertEquals("15000", action(seconds).args["millis"])
        assertEquals("300000", action(minutes).args["millis"])
        assertEquals("600000", action("""<code>812</code><Int sr="arg0" val="0"/><Int sr="arg1"><var>10</var></Int><Int sr="arg2" val="0"/>""").args["millis"])
    }

    @Test
    fun aDisplayTimeoutAboveTheLimitIsCappedAndSaysSo() {
        val hours = """<code>812</code><Int sr="arg0" val="0"/><Int sr="arg1" val="0"/><Int sr="arg2" val="23"/>"""

        assertEquals("1800000", action(hours).args["millis"])
        assertWarns(hours, "30 minutes")
    }

    @Test
    fun aDisplayTimeoutHeldInAVariableIsWorkedOutInMilliseconds() {
        val millis = action("""<code>812</code><Int sr="arg0"><var>%before</var></Int><Int sr="arg1" val="0"/><Int sr="arg2" val="0"/>""")
            .args.getValue("millis")

        assertEquals("15000", expand(millis, "before" to "15"))
    }

    // Launch App (20), Kill App (18), Lock (15), System Lock (16)

    @Test
    fun launchAppReadsThePackageFromTheAppElement() {
        val action = action(
            """<code>20</code><App sr="arg0"><appClass>c</appClass><appPkg>com.foo</appPkg><label>Foo</label></App><Str sr="arg1" ve="3"/><Int sr="arg2" val="0"/>""",
        )

        assertEquals("app.launch", action.type)
        assertEquals("com.foo", action.args["package"])
    }

    @Test
    fun launchAppNeverTakesItsDataFieldForAPackage() {
        val xml = """<code>20</code><App sr="arg0"><appPkg>com.foo</appPkg></App><Str sr="arg1" ve="3">content://x</Str>"""

        assertEquals("com.foo", action(xml).args["package"])
        assertWarns(xml, "Data")
    }

    @Test
    fun launchAppStillReadsOlderOpenTaskerExports() {
        assertEquals("com.foo", action("""<code>20</code><Str sr="arg0">com.foo</Str>""").args["package"])
    }

    @Test
    fun killAppReadsThePackageAndNamesTheRootOption() {
        val xml = """<code>18</code><App sr="arg0"><appPkg>com.kugou.android</appPkg></App><Int sr="arg1" val="1"/>"""

        assertEquals("app.kill", action(xml).type)
        assertEquals("com.kugou.android", action(xml).args["package"])
        assertWarns(xml, "Use Root")
    }

    @Test
    fun systemLockImportsAsLockAndCodeLockSaysWhatItLost() {
        assertEquals("lock", action("<code>16</code>").type)
        assertTrue(warnings("<code>16</code>").isEmpty())

        val code = """<code>15</code><Str sr="arg0" ve="3">Title</Str><Str sr="arg1" ve="3">1234</Str>"""
        assertEquals("lock", action(code).type)
        assertWarns(code, "code")
    }

    // Boolean Set fields: Torch (511), Bluetooth (294), Airplane Mode (333), WiFi (425), WiFi Tether (113)

    @Test
    fun wifiTetherKeepWifiIsDroppedWithAWarning() {
        val keepWifi = """<code>113</code><Int sr="arg0" val="1"/><Int sr="arg1" val="1"/>"""
        val plain = """<code>113</code><Int sr="arg0" val="0"/><Int sr="arg1" val="0"/>"""

        assertEquals("hotspot.toggle", action(keepWifi).type)
        assertEquals("on", action(keepWifi).args["state"])
        assertTrue(warnings(keepWifi).any { it.contains("Keep Wi-Fi") })

        assertEquals("off", action(plain).args["state"])
        assertFalse(warnings(plain).any { it.contains("Keep Wi-Fi") })
    }

    @Test
    fun setFieldsReadZeroAsOffOneAsOnAndTwoAsToggle() {
        val types = mapOf("511" to "torch.set", "294" to "bluetooth.toggle", "333" to "airplane.toggle", "425" to "wifi.toggle", "113" to "hotspot.toggle")
        types.forEach { (code, type) ->
            listOf("0" to "off", "1" to "on", "2" to "toggle").forEach { (value, state) ->
                val action = action("""<code>$code</code><Int sr="arg0" val="$value"/>""")
                assertEquals(type, action.type)
                assertEquals("code $code value $value", state, action.args["state"])
            }
        }
    }

    @Test
    fun torchWithItsLevelFieldStillReadsTheSetField() {
        assertEquals("toggle", action("""<code>511</code><Int sr="arg0" val="2"/><Str sr="arg1" ve="3"/>""").args["state"])
        assertEquals("on", action("""<code>511</code><Str sr="arg0">on</Str>""").args["state"])
    }

    @Test
    fun aSetFieldItCannotReadIsAPlaceholderRatherThanAGuess() {
        val variable = """<code>425</code><Int sr="arg0"><var>%wifi</var></Int>"""
        assertEquals(TaskerXmlImporter.TASKER_UNSUPPORTED_ACTION_ID, action(variable).type)
        assertWarns(variable, "%wifi")

        val unknown = """<code>294</code><Int sr="arg0" val="7"/>"""
        assertEquals(TaskerXmlImporter.TASKER_UNSUPPORTED_ACTION_ID, action(unknown).type)
        assertWarns(unknown, "7")
    }

    // Number fields holding a variable: <Int sr="argN"><var>%x</var></Int>

    @Test
    fun aVariableInANumberFieldIsKept() {
        assertEquals("%Vol0", action("""<code>307</code><Int sr="arg0"><var>%Vol0</var></Int><Int sr="arg1" val="0"/><Int sr="arg2" val="0"/>""").args["level"])
        assertEquals("%bright", action("""<code>810</code><Int sr="arg0"><var>%bright</var></Int><Int sr="arg1" val="1"/>""").args["brightness"])
        assertEquals("%ms", action("""<code>61</code><Int sr="arg0"><var>%ms</var></Int>""").args["millis"])
        assertEquals("%wait_ms", action("""<code>30</code><Int sr="arg0"><var>%wait_ms</var></Int><Int sr="arg1" val="0"/><Int sr="arg2" val="0"/><Int sr="arg3" val="0"/><Int sr="arg4" val="0"/>""").args["millis"])
    }

    @Test
    fun aWaitHeldInSecondsIsWorkedOutInMilliseconds() {
        val millis = action("""<code>30</code><Int sr="arg0" val="0"/><Int sr="arg1"><var>%secs</var></Int><Int sr="arg2" val="0"/>""")
            .args.getValue("millis")

        assertEquals("5000", expand(millis, "secs" to "5"))
    }

    @Test
    fun aWaitMixingAVariableWithOtherUnitsSaysWhatItDropped() {
        val xml = """<code>30</code><Int sr="arg0" val="500"/><Int sr="arg1"><var>%secs</var></Int>"""

        assertWarns(xml, "%secs")
    }

    @Test
    fun aSimpleSumInANumberFieldBecomesATemplate() {
        val brightness = action("""<code>810</code><Int sr="arg0"><var>%bri+1</var></Int><Int sr="arg1" val="1"/>""")
            .args.getValue("brightness")

        assertEquals("41", expand(brightness, "bri" to "40"))
    }

    @Test
    fun aHarderSumStaysVisibleAndIsNamedSoItCannotMuteAnyoneQuietly() {
        val xml = """<code>307</code><Int sr="arg0"><var>%a*%b+1</var></Int>"""

        assertEquals("%a*%b+1", action(xml).args["level"])
        assertWarns(xml, "%a*%b+1")
    }

    // Variable Set (547)

    @Test
    fun doMathsOnOneVariableAndOneNumberBecomesATemplate() {
        val value = action("""<code>547</code><Str sr="arg0" ve="3">%count</Str><Str sr="arg1" ve="3">%count + 1</Str><Int sr="arg2" val="0"/><Int sr="arg3" val="1"/><Int sr="arg4" val="0"/>""")
            .args.getValue("value")

        assertEquals("6", expand(value, "count" to "5"))
    }

    @Test
    fun doMathsThatCannotBeWorkedOutIsNamed() {
        val xml = """<code>547</code><Str sr="arg0" ve="3">%x</Str><Str sr="arg1" ve="3">sqrt(%y)*2</Str><Int sr="arg3" val="1"/>"""

        assertEquals("sqrt(%y)*2", action(xml).args["value"])
        assertWarns(xml, "sqrt(%y)*2")
    }

    @Test
    fun plainVariableSetStaysQuiet() {
        val xml = """<code>547</code><Str sr="arg0" ve="3">%x</Str><Str sr="arg1" ve="3">5</Str><Int sr="arg2" val="0"/><Int sr="arg3" val="0"/><Int sr="arg4" val="0"/>"""

        assertEquals("5", action(xml).args["value"])
        assertTrue(warnings(xml).isEmpty())
    }

    @Test
    fun appendAddsToWhatTheVariableAlreadyHolds() {
        val xml = """<code>547</code><Str sr="arg0" ve="3">%apps</Str><Str sr="arg1" ve="3">more</Str><Int sr="arg3" val="0"/><Int sr="arg4" val="1"/>"""

        val value = action(xml).args.getValue("value")

        assertEquals("twomore", expand(value, "apps" to "two"))
        assertEquals("more", expand(value))
        assertTrue(warnings(xml).isEmpty())
    }

    @Test
    fun recurseVariablesIsNamed() {
        assertWarns("""<code>547</code><Str sr="arg0" ve="3">%x</Str><Str sr="arg1" ve="3">%y</Str><Int sr="arg2" val="1"/>""", "Recurse")
    }

    // HTTP Request (339)

    @Test
    fun httpRequestReadsEveryMethodThisAppCanSend() {
        listOf("GET", "POST", "HEAD", "PUT", "PATCH", "DELETE", "OPTIONS").forEachIndexed { index, method ->
            val action = action("""<code>339</code><Int sr="arg1" val="$index"/><Str sr="arg2" ve="3">https://example.invalid/</Str>""")
            assertEquals(method, action.args["method"])
        }
    }

    @Test
    fun httpRequestNamesTraceInsteadOfSendingADifferentMethod() {
        val xml = """<code>339</code><Int sr="arg1" val="7"/><Str sr="arg2" ve="3">https://example.invalid/</Str>"""

        assertNull(action(xml).args["method"])
        assertWarns(xml, "Tasker index 7")
    }

    @Test
    fun httpRequestQueryLinesConvertToThisAppsForm() {
        val action = action(
            "<code>339</code><Int sr=\"arg1\" val=\"0\"/><Str sr=\"arg2\" ve=\"3\">https://example.invalid/</Str>" +
                "<Str sr=\"arg4\" ve=\"3\">lat:%gl_latitude\nunits:metric</Str>",
        )

        assertEquals("lat=%gl_latitude\nunits=metric", action.args["query"])
    }

    @Test
    fun httpRequestFillsTheVariablesTaskerTasksRead() {
        val action = action("""<code>339</code><Int sr="arg1" val="0"/><Str sr="arg2" ve="3">https://example.invalid/</Str>""")

        assertEquals("%http_data", action.args["response_var"])
        assertEquals("%http_response_code", action.args["status_var"])
    }

    @Test
    fun httpRequestToAPlainHttpAddressCanReachTheLocalNetworkAndSaysWhy() {
        val http = """<code>339</code><Int sr="arg1" val="0"/><Str sr="arg2" ve="3">http://192.168.1.2/x</Str>"""
        assertEquals("true", action(http).args["allow_http"])
        assertWarns(http, "https")

        assertNull(action("""<code>339</code><Int sr="arg1" val="0"/><Str sr="arg2" ve="3">https://example.invalid/</Str>""").args["allow_http"])
    }

    @Test
    fun httpRequestNamesTrustAnyCertificateAndNeverCarriesIt() {
        val xml = """<code>339</code><Int sr="arg1" val="0"/><Str sr="arg2" ve="3">https://example.invalid/</Str><Int sr="arg9" val="1"/>"""

        assertWarns(xml, "Trust Any Certificate")
        assertFalse(action(xml).args.keys.any { "trust" in it || "tls" in it })
    }

    @Test
    fun httpRequestDropsABodyThisAppWouldRefuseToSendOnAGet() {
        val xml = """<code>339</code><Int sr="arg1" val="0"/><Str sr="arg2" ve="3">https://example.invalid/</Str><Str sr="arg5" ve="3">x</Str>"""

        assertNull(action(xml).args["body"])
        assertWarns(xml, "body")
    }

    @Test
    fun httpRequestFollowsRedirectsOnlyInsideTheSameSite() {
        val xml = """<code>339</code><Int sr="arg1" val="0"/><Str sr="arg2" ve="3">https://example.invalid/</Str><Int sr="arg10" val="1"/>"""

        assertEquals("same_origin", action(xml).args["redirects"])
        assertWarns(xml, "redirect")
    }

    // HTTP Get (118), HTTP Post (116)

    @Test
    fun httpGetBuildsItsRequestFromARealExport() {
        val action = action(
            """<code>118</code><Str sr="arg0" ve="3">http://www.weather.gov.sg/x</Str><Str sr="arg1" ve="3"/><Str sr="arg2" ve="3"/><Str sr="arg3" ve="3"/><Str sr="arg4" ve="3"/><Int sr="arg5" val="10"/><Str sr="arg6" ve="3"/><Str sr="arg7" ve="3"/><Int sr="arg8" val="0"/>""",
        )

        assertEquals("http.request", action.type)
        assertEquals("GET", action.args["method"])
        assertEquals("http://www.weather.gov.sg/x", action.args["url"])
        assertEquals("10", action.args["timeout_sec"])
        assertEquals("%HTTPD", action.args["response_var"])
        assertEquals("%HTTPR", action.args["status_var"])
        assertEquals("true", action.args["allow_http"])
    }

    @Test
    fun httpGetJoinsServerAndPathAndCarriesItsOtherFields() {
        val action = action(
            """<code>118</code><Str sr="arg0" ve="3">example.invalid:8080</Str><Str sr="arg1" ve="3">api/x</Str><Str sr="arg2" ve="3">a=1</Str><Str sr="arg3" ve="3">c=1</Str><Str sr="arg4" ve="3">agent</Str>""",
        )

        assertEquals("http://example.invalid:8080/api/x", action.args["url"])
        assertEquals("a=1", action.args["query"])
        assertEquals("Cookie: c=1\nUser-Agent: agent", action.args["headers"])
    }

    @Test
    fun httpGetLeavesAServerHeldInAVariableAlone() {
        assertEquals("%url", action("""<code>118</code><Str sr="arg0" ve="3">%url</Str>""").args["url"])
    }

    @Test
    fun httpGetNamesTheFieldsItDoesNotImport() {
        val xml = """<code>118</code><Str sr="arg0" ve="3">https://example.invalid</Str><Str sr="arg7" ve="3">out.txt</Str><Int sr="arg8" val="1"/>"""

        assertWarns(xml, "Output File")
        assertWarns(xml, "Trust Any Certificate")
    }

    @Test
    fun httpPostCarriesBodyAndContentType() {
        val action = action(
            """<code>116</code><Str sr="arg0" ve="3">https://example.invalid</Str><Str sr="arg1" ve="3">/post</Str><Str sr="arg2" ve="3">a=b</Str><Str sr="arg3" ve="3"/><Str sr="arg4" ve="3"/><Int sr="arg5" val="20"/><Str sr="arg6" ve="3">text/plain</Str>""",
        )

        assertEquals("http.request", action.type)
        assertEquals("POST", action.args["method"])
        assertEquals("https://example.invalid/post", action.args["url"])
        assertEquals("a=b", action.args["body"])
        assertEquals("text/plain", action.args["content_type"])
        assertEquals("20", action.args["timeout_sec"])
    }

    // Custom Setting (235)

    @Test
    fun customSettingWritesTheRightTableNameAndValue() {
        val tables = listOf("global", "secure", "system")
        tables.forEachIndexed { index, table ->
            val action = action(
                """<code>235</code><Int sr="arg0" val="$index"/><Str sr="arg1" ve="3">doze_always_on</Str><Str sr="arg2" ve="3">1</Str><Int sr="arg3" val="0"/><Str sr="arg4" ve="3"/>""",
            )
            assertEquals("settings.write", action.type)
            assertEquals(table, action.args["table"])
            assertEquals("doze_always_on", action.args["key"])
            assertEquals("1", action.args["value"])
        }
    }

    @Test
    fun displayBrightnessIsStillABrightnessChange() {
        // A third-party table once put Custom Setting at 810. Tasker's own table and real exports
        // both have 810 as Display Brightness and 235 as Custom Setting.
        assertEquals("brightness.set", action("""<code>810</code><Int sr="arg0" val="100"/>""").type)
    }

    @Test
    fun customSettingThatReadsASettingIsNeverImportedAsAWrite() {
        // Importing this one as a write would wipe the user's quick settings tiles.
        val xml = """<code>235</code><Int sr="arg0" val="1"/><Str sr="arg1" ve="3">sysui_qs_tiles</Str><Str sr="arg2" ve="3"/><Int sr="arg3" val="0"/><Str sr="arg4" ve="3">%tiles</Str>"""

        assertEquals(TaskerXmlImporter.TASKER_UNSUPPORTED_ACTION_ID, action(xml).type)
        assertWarns(xml, "%tiles")
    }

    @Test
    fun customSettingWithNoValueIsNeverImportedAsAWrite() {
        val xml = """<code>235</code><Int sr="arg0" val="1"/><Str sr="arg1" ve="3">some_setting</Str><Str sr="arg2" ve="3"/>"""

        assertEquals(TaskerXmlImporter.TASKER_UNSUPPORTED_ACTION_ID, action(xml).type)
        assertWarns(xml, "some_setting")
    }

    @Test
    fun customSettingNamesItsRootOption() {
        assertWarns(
            """<code>235</code><Int sr="arg0" val="1"/><Str sr="arg1" ve="3">a</Str><Str sr="arg2" ve="3">1</Str><Int sr="arg3" val="1"/>""",
            "Use Root",
        )
    }

    // Send SMS (41), Read File (417), Take Screenshot (176)

    @Test
    fun sendSmsCarriesNumberAndMessage() {
        val xml = """<code>41</code><Str sr="arg0" ve="3">%number</Str><Str sr="arg1" ve="3">%message</Str><Int sr="arg2" val="0"/><Str sr="arg3" ve="3"/><Int sr="arg4" val="0"/>"""

        assertEquals("sms.send", action(xml).type)
        assertEquals("%number", action(xml).args["number"])
        assertEquals("%message", action(xml).args["message"])
        assertTrue(warnings(xml).isEmpty())
    }

    @Test
    fun sendSmsNamesTheSimItCannotChoose() {
        assertWarns("""<code>41</code><Str sr="arg0" ve="3">1</Str><Str sr="arg1" ve="3">x</Str><Str sr="arg3" ve="3">2</Str>""", "SIM")
    }

    @Test
    fun readFileCarriesPathAndVariable() {
        val action = action("""<code>417</code><Str sr="arg0" ve="3">%filepathname</Str><Str sr="arg1" ve="3">%input</Str>""")

        assertEquals("file.read", action.type)
        assertEquals("%filepathname", action.args["path"])
        assertEquals("%input", action.args["var"])
    }

    @Test
    fun takeScreenshotCarriesItsFile() {
        val xml = """<code>176</code><Str sr="arg0" ve="3">%png</Str><Int sr="arg1" val="1"/>"""

        assertEquals("%png", action(xml).args["path"])
        assertWarns(xml, "Insert In Gallery")
    }

    // Media Control (443), Music Play (445), Play Ringtone (192), Music Skip (451)

    @Test
    fun mediaControlMapsTheCommandsThisAppHas() {
        mapOf("0" to "track.next", "1" to "sound.pause", "2" to "track.previous", "4" to "sound.stop").forEach { (cmd, type) ->
            assertEquals(type, action("""<code>443</code><Int sr="arg0" val="$cmd"/><Int sr="arg1" val="1"/>""").type)
        }
    }

    @Test
    fun mediaControlNamesACommandItCannotMap() {
        mapOf("""<Int sr="arg0"><var>%cmd</var></Int>""" to "%cmd", """<Int sr="arg0" val="8"/>""" to "8").forEach { (arg, name) ->
            val xml = """<code>443</code>$arg"""
            assertEquals(TaskerXmlImporter.TASKER_UNSUPPORTED_ACTION_ID, action(xml).type)
            assertWarns(xml, name)
        }
    }

    @Test
    fun mediaControlNamesTheAppItCannotAim() {
        assertWarns(
            """<code>443</code><Int sr="arg0" val="1"/><Int sr="arg1" val="1"/><App sr="arg2"><appPkg>com.soundcloud.android</appPkg></App><Int sr="arg3" val="0"/>""",
            "com.soundcloud.android",
        )
    }

    @Test
    fun musicPlayCarriesItsFileAndNamesTheLoop() {
        val xml = """<code>445</code><Str sr="arg0" ve="3">%REPFILE</Str><Int sr="arg1" val="0"/><Int sr="arg2" val="1"/><Int sr="arg3" val="0"/>"""

        assertEquals("sound.play", action(xml).type)
        assertEquals("%REPFILE", action(xml).args["path"])
        assertWarns(xml, "Loop")
    }

    @Test
    fun playRingtoneSaysASystemSoundNameIsNotAFile() {
        val xml = """<code>192</code><Int sr="arg0" val="1"/><Str sr="arg1" ve="3">Hojus</Str><Int sr="arg2" val="5"/>"""

        assertEquals("sound.play", action(xml).type)
        assertEquals("Hojus", action(xml).args["path"])
        assertWarns(xml, "Hojus")
    }

    @Test
    fun taskerAlarmStreamComesAcrossAndOtherStreamsStayAsTheyWere() {
        assertEquals("alarm", action("""<code>445</code><Str sr="arg0" ve="3">a.mp3</Str><Int sr="arg3" val="4"/>""").args["stream"])
        assertEquals(null, action("""<code>445</code><Str sr="arg0" ve="3">a.mp3</Str><Int sr="arg3" val="3"/>""").args["stream"])
        assertEquals("alarm", action("""<code>559</code><Str sr="arg0" ve="3">hi</Str><Str sr="arg1" ve="3"/><Int sr="arg2" val="4"/>""").args["stream"])
        assertEquals(null, action("""<code>559</code><Str sr="arg0" ve="3">hi</Str><Str sr="arg1" ve="3"/><Int sr="arg2" val="3"/>""").args["stream"])
        assertEquals("alarm", action("""<code>192</code><Int sr="arg0" val="1"/><Str sr="arg1" ve="3">/a.ogg</Str><Int sr="arg2" val="4"/>""").args["stream"])
    }

    @Test
    fun aSoundOnAStreamThisAppLacksSaysItPlaysOnMusic() {
        assertWarns("""<code>192</code><Int sr="arg0" val="1"/><Str sr="arg1" ve="3">/a.ogg</Str><Int sr="arg2" val="1"/>""", "stream 1 (System)")
        assertWarns("""<code>445</code><Str sr="arg0" ve="3">a.mp3</Str><Int sr="arg3" val="0"/>""", "stream 0 (Call)")
        assertWarns("""<code>559</code><Str sr="arg0" ve="3">hi</Str><Str sr="arg1" ve="3"/><Int sr="arg2"><var>%where</var></Int>""", "%where")
        assertEquals(null, action("""<code>559</code><Str sr="arg0" ve="3">hi</Str><Str sr="arg1" ve="3"/><Int sr="arg2"><var>%where</var></Int>""").args["stream"])
        assertTrue(warnings("""<code>192</code><Int sr="arg0" val="1"/><Str sr="arg1" ve="3">/a.ogg</Str><Int sr="arg2" val="3"/>""").none { "stream" in it })
        assertTrue(warnings("""<code>559</code><Str sr="arg0" ve="3">hi</Str><Str sr="arg1" ve="3"/><Int sr="arg2" val="4"/>""").none { "stream" in it })
        val odd = warnings("""<code>171</code><Int sr="arg0" val="1852"/><Int sr="arg1" val="100"/><Int sr="arg3" val="3.0"/>""")
        assertTrue(odd.toString(), odd.any { "\"3.0\" isn't a stream number" in it } && odd.none { "picks its stream" in it })
    }

    @Test
    fun aStreamNoteComesWithTheActionsOtherNotes() {
        val ringtone = warnings("""<code>192</code><Int sr="arg0" val="1"/><Str sr="arg1" ve="3">Pixie Dust</Str><Int sr="arg2" val="1"/>""")
        assertTrue(ringtone.toString(), ringtone.any { "names a system sound" in it } && ringtone.any { "stream 1 (System)" in it })
        val music = warnings("""<code>445</code><Str sr="arg0" ve="3">a.mp3</Str><Int sr="arg2" val="1"/><Int sr="arg3"><var>%s</var></Int>""")
        assertTrue(music.toString(), music.any { "Loop" in it } && music.any { "picks its stream from %s" in it })
        assertWarns("""<code>559</code><Str sr="arg0" ve="3">hi</Str><Str sr="arg1" ve="3"/><Int sr="arg2" val="1"/>""", "speaks on Music")
        assertWarns("""<code>171</code><Int sr="arg0" val="1852"/><Int sr="arg1" val="100"/><Int sr="arg3"><var>1+%s</var></Int>""", "picks its stream from 1+%s")
    }

    // Beep (171)

    @Test
    fun beepComesInAtThePitchTaskerPlays() {
        val xml = """<code>171</code><Int sr="arg0" val="1852"/><Int sr="arg1" val="100"/><Int sr="arg2" val="9"/><Int sr="arg3" val="3"/><Str sr="arg4" ve="3"/>"""

        assertEquals("sound.beep", action(xml).type)
        assertEquals(mapOf("frequency" to "463", "duration" to "100", "amplitude" to "9"), action(xml).args)
        assertTrue(warnings(xml).isEmpty())
        assertEquals("196.75", action("""<code>171</code><Int sr="arg0" val="787"/><Int sr="arg1" val="100"/>""").args["frequency"])
        assertEquals("0.25", action("""<code>171</code><Int sr="arg0" val="1"/><Int sr="arg1" val="15"/><Int sr="arg2" val="0"/>""").args["frequency"])
    }

    @Test
    fun aBeepPitchFromAVariableComesInAsAQuarterOfIt() {
        assertEquals("{{ freq | div:4 }}", action("""<code>171</code><Int sr="arg0"><var>%freq</var></Int><Int sr="arg1" val="100"/>""").args["frequency"])
        assertEquals("{{ freq | add:100 | div:4 }}", action("""<code>171</code><Int sr="arg0"><var>%freq + 100</var></Int><Int sr="arg1" val="100"/>""").args["frequency"])
        assertEquals("%len", action("""<code>171</code><Int sr="arg0" val="1852"/><Int sr="arg1"><var>%len</var></Int>""").args["duration"])
    }

    @Test
    fun beepKeepsTheAlarmStreamAndNamesWhatItDrops() {
        assertEquals("alarm", action("""<code>171</code><Int sr="arg0" val="1852"/><Int sr="arg1" val="100"/><Int sr="arg3" val="4"/>""").args["stream"])
        assertWarns("""<code>171</code><Int sr="arg0" val="1852"/><Int sr="arg1" val="100"/><Int sr="arg3" val="1"/>""", "stream 1")
        assertWarns("""<code>171</code><Int sr="arg0" val="1852"/><Int sr="arg1" val="100"/><Str sr="arg4" ve="3">%later</Str>""", "Do At Time")
    }

    @Test
    fun aBeepStreamHeldInAVariableIsNamedBecauseItBeepsOnMusic() {
        val xml = """<code>171</code><Int sr="arg0" val="1852"/><Int sr="arg1" val="100"/><Int sr="arg3"><var>%stream</var></Int>"""

        assertEquals(null, action(xml).args["stream"])
        assertWarns(xml, "%stream")
    }

    @Test
    fun tasksOwnMusicPlayerJumpsSayTheySteerWhateverIsPlaying() {
        assertEquals("track.next", action("<code>451</code>").type)
        assertWarns("<code>451</code>", "Music Skip")
    }

    // Perform Task (130), Stop (137), Browse URL (104)

    @Test
    fun performTaskPassesItsParameters() {
        val action = action(
            """<code>130</code><Str sr="arg0" ve="3">Weather</Str><Int sr="arg1" val="100"/><Str sr="arg2" ve="3">manual</Str><Str sr="arg3" ve="3">%city</Str><Str sr="arg4" ve="3"/>""",
        )

        assertEquals("task.run", action.type)
        assertEquals("Weather", action.args["task"])
        assertEquals("manual", action.args["par1"])
        assertEquals("%city", action.args["par2"])
    }

    @Test
    fun performTaskReadsTheOlderLayoutToo() {
        val action = action(
            """<code>130</code><Str sr="arg0" ve="3">Weather</Str><Int sr="arg1" val="100"/><Int sr="arg2" val="0"/><Str sr="arg3" ve="3">manual</Str><Str sr="arg4" ve="3">%city</Str><Str sr="arg5" ve="3"/>""",
        )

        assertEquals("manual", action.args["par1"])
        assertEquals("%city", action.args["par2"])
    }

    @Test
    fun performTaskNamesTheReturnVariable() {
        val xml = """<code>130</code><Str sr="arg0" ve="3">Restore</Str><Int sr="arg1" val="100"/><Str sr="arg2" ve="3"/><Str sr="arg3" ve="3"/><Str sr="arg4" ve="3">%restored</Str>"""

        assertNull(action(xml).args["par1"])
        assertWarns(xml, "%restored")
    }

    @Test
    fun stoppingAnotherTaskImportsAsStopTaskAndAPlainStopStaysStop() {
        val named = action("""<code>137</code><Int sr="arg0" val="0"/><Str sr="arg1" ve="3">Background</Str>""")

        assertEquals("task.stop", named.type)
        assertEquals(mapOf("task" to "Background"), named.args)
        assertEquals(emptyList<String>(), warnings("""<code>137</code><Int sr="arg0" val="0"/><Str sr="arg1" ve="3">Background</Str>"""))
        assertEquals("flow.stop", action("""<code>137</code><Int sr="arg0" val="0"/><Str sr="arg1" ve="3"/>""").type)
    }

    @Test
    fun browseUrlNamesTheAppItCannotAim() {
        assertWarns(
            """<code>104</code><Str sr="arg0" ve="3">https://example.invalid</Str><App sr="arg1"><appPkg>org.mozilla.firefox</appPkg></App>""",
            "org.mozilla.firefox",
        )
    }

    // Notify (523), Notify Cancel (779)

    @Test
    fun notifyCancelFindsTheNotificationByTitle() {
        val report = parse(
            """<Str sr="arg0" ve="3">%arcomm</Str><Str sr="arg1" ve="3">body</Str><code>523</code>""",
            """<code>779</code><Str sr="arg0" ve="3">%arcomm</Str><Int sr="arg1" val="0"/>""",
        )

        val (show, cancel) = report.bundle.tasks.single().actions
        assertEquals("notify.show", show.type)
        assertEquals("notify.cancel", cancel.type)
        assertEquals("%arcomm", show.args["tag"])
        assertEquals(show.args["tag"], cancel.args["tag"])
    }

    // Variable Add (888), Variable Clear (549), Variable Section (597), Variable Search Replace (598)

    private val placeholder = TaskerXmlImporter.TASKER_UNSUPPORTED_ACTION_ID

    @Test
    fun variableAddCountsUpFromNothing() {
        val add = action("""<code>888</code><Str sr="arg0" ve="3">%LiveCycles</Str><Int sr="arg1" val="1"/><Int sr="arg2" val="0"/>""")

        assertEquals("var.set", add.type)
        assertEquals("%LiveCycles", add.args["name"])
        assertEquals("1", expand(add.args.getValue("value")))
        assertEquals("5", expand(add.args.getValue("value"), "LiveCycles" to "4"))
    }

    @Test
    fun variableAddThatWrapsOrAddsAVariableStaysAPlaceholder() {
        listOf(
            """<code>888</code><Str sr="arg0" ve="3">%n</Str><Int sr="arg1" val="1"/><Int sr="arg2" val="10"/>""",
            """<code>888</code><Str sr="arg0" ve="3">%n</Str><Int sr="arg1"><var>%step</var></Int><Int sr="arg2" val="0"/>""",
        ).forEach { assertEquals(it, placeholder, action(it).type) }
    }

    @Test
    fun variableAddThatWrapsAtAVariableStaysAPlaceholder() {
        val add = action("""<code>888</code><Str sr="arg0" ve="3">%n</Str><Int sr="arg1" val="1"/><Int sr="arg2"><var>%max</var></Int>""")

        assertEquals(placeholder, add.type)
    }

    @Test
    fun variableClearEmptiesOneVariable() {
        val clear = action("""<code>549</code><Str sr="arg0" ve="3">%Recording</Str><Int sr="arg1" val="0"/>""")

        assertEquals("var.set", clear.type)
        assertEquals(mapOf("name" to "%Recording", "value" to ""), clear.args)
    }

    @Test
    fun variableClearOfSeveralOrAllVariablesStaysAPlaceholder() {
        val several = """<code>549</code><Str sr="arg0" ve="3">%command_failed/%errmsg/%stdout/%stderr/%result</Str>""" +
            """<Int sr="arg1" val="1"/><Int sr="arg2" val="1"/><Int sr="arg3" val="0"/>"""
        val all = """<code>549</code><Str sr="arg0" ve="3">%a</Str><Int sr="arg1" val="0"/><Int sr="arg2" val="0"/><Int sr="arg3" val="1"/>"""
        // Local Variables Only can't reach a global one, so Tasker leaves it alone.
        val globalLeftAlone = """<code>549</code><Str sr="arg0" ve="3">%Kept</Str><Int sr="arg1" val="0"/><Int sr="arg2" val="1"/><Int sr="arg3" val="0"/>"""

        listOf(several, all, globalLeftAlone).forEach { assertEquals(it, placeholder, action(it).type) }
        assertEquals("var.set", action(globalLeftAlone.replace("%Kept", "%kept")).type)
    }

    @Test
    fun variableSectionCountsFromOne() {
        val section = """<code>597</code><Str sr="arg0" ve="3">%APPCLOSE</Str><Int sr="arg1" val="6"/>""" +
            """<Int sr="arg2" val="100"/><Int sr="arg3" val="1"/><Str sr="arg4" ve="3"/>"""
        val action = action(section)

        assertEquals("text.substring", action.type)
        assertEquals(
            mapOf("source" to "{{ APPCLOSE }}", "start" to "5", "end" to "105", "var" to "%APPCLOSE"),
            action.args,
        )
        assertFalse(warnings(section).toString(), warnings(section).any { "Adapt To Fit" in it })
        assertWarns(section.replace("""<Int sr="arg3" val="1"/>""", """<Int sr="arg3" val="0"/>"""), "Adapt To Fit")
        assertEquals("%Tail", action(section.replace("""<Str sr="arg4" ve="3"/>""", """<Str sr="arg4" ve="3">%Tail</Str>""")).args["var"])
    }

    @Test
    fun variableSectionFromAVariableStaysAPlaceholder() {
        assertEquals(
            placeholder,
            action("""<code>597</code><Str sr="arg0" ve="3">%s</Str><Int sr="arg1"><var>%from</var></Int><Int sr="arg2" val="3"/><Int sr="arg3" val="1"/>""").type,
        )
    }

    @Test
    fun searchReplaceWritesTheResultBackToItsVariable() {
        val replace = action(
            """<code>598</code><Str sr="arg0" ve="3">%par1</Str><Str sr="arg1" ve="3">&lt;</Str><Int sr="arg2" val="0"/>""" +
                """<Int sr="arg3" val="0"/><Int sr="arg4" val="0"/><Str sr="arg5" ve="3"/><Int sr="arg6" val="1"/><Str sr="arg7" ve="3">~lt~</Str>""",
        )

        assertEquals("text.replace", replace.type)
        assertEquals(mapOf("source" to "{{ par1 }}", "pattern" to "<", "replacement" to "~lt~", "var" to "%par1"), replace.args)
    }

    @Test
    fun searchReplaceKeepsItsSpacesFlagsAndEscapes() {
        val quotes = action(
            """<code>598</code><Str sr="arg0" ve="3">%argument_1</Str><Str sr="arg1" ve="3">'</Str><Int sr="arg2" val="0"/>""" +
                """<Int sr="arg3" val="1"/><Int sr="arg4" val="0"/><Str sr="arg5" ve="3"/><Int sr="arg6" val="1"/><Str sr="arg7" ve="3">'\\''</Str>""",
        )
        assertEquals("(?m)'", quotes.args["pattern"])
        assertEquals("""'\\''""", quotes.args["replacement"])

        val spaced = action(
            """<code>598</code><Str sr="arg0" ve="3">%text</Str><Str sr="arg1" ve="3">display:Odin Controller 0</Str><Int sr="arg2" val="1"/>""" +
                """<Int sr="arg3" val="0"/><Int sr="arg4" val="0"/><Str sr="arg5" ve="3"/><Int sr="arg6" val="1"/><Str sr="arg7" ve="3"> Odin </Str>""",
        )
        assertEquals("(?i)display:Odin Controller 0", spaced.args["pattern"])
        assertEquals(" Odin ", spaced.args["replacement"])
    }

    @Test
    fun searchReplaceNamesTheEscapedPercentItCannotKeep() {
        val termux = """<code>598</code><Str sr="arg0" ve="3">%command_failed</Str>""" +
            """<Str sr="arg1" ve="3">^err = `\%err`[\n]+errmsg =[\n]```[\n]\%errmsg[\n]```[\n]+</Str><Int sr="arg2" val="0"/>""" +
            """<Int sr="arg3" val="0"/><Int sr="arg4" val="0"/><Str sr="arg5" ve="3"/><Int sr="arg6" val="1"/><Str sr="arg7" ve="3"/>"""

        assertEquals("text.replace", action(termux).type)
        assertEquals("", action(termux).args["replacement"])
        assertWarns(termux, "\\%")
    }

    @Test
    fun searchReplaceThisAppCannotRunStaysAPlaceholder() {
        val base = """<code>598</code><Str sr="arg0" ve="3">%t</Str><Str sr="arg1" ve="3">a</Str><Int sr="arg2" val="0"/>""" +
            """<Int sr="arg3" val="0"/><Int sr="arg4" val="0"/><Str sr="arg5" ve="3"/><Int sr="arg6" val="1"/><Str sr="arg7" ve="3">b</Str>"""
        assertEquals("text.replace", action(base).type)

        listOf(
            base.replace("""<Int sr="arg6" val="1"/>""", """<Int sr="arg6" val="0"/>"""),
            base.replace("""<Int sr="arg4" val="0"/>""", """<Int sr="arg4" val="1"/>"""),
            // RE2 has no lookahead, so this pattern would never compile when the step ran.
            base.replace(""">a</Str>""", """>a(?=b)</Str>"""),
        ).forEach { assertEquals(it, placeholder, action(it).type) }
        assertWarns(base.replace("""<Str sr="arg5" ve="3"/>""", """<Str sr="arg5" ve="3">%found</Str>"""), "%found")
    }

    @Test
    fun searchReplaceNamesTheEndAnchorThatSkipsAFinalLineBreak() {
        val base = """<code>598</code><Str sr="arg0" ve="3">%stdout</Str><Str sr="arg1" ve="3">PATTERN</Str><Int sr="arg2" val="0"/>""" +
            """<Int sr="arg3" val="0"/><Int sr="arg4" val="0"/><Str sr="arg5" ve="3"/><Int sr="arg6" val="1"/><Str sr="arg7" ve="3"/>"""
        val trailing = base.replace("PATTERN", """\d+$""")

        assertEquals("text.replace", action(trailing).type)
        assertWarns(trailing, "final line break")
        listOf(
            base.replace("PATTERN", """\$5"""),
            base.replace("PATTERN", "[$]5"),
            base.replace("PATTERN", """\d+$""").replace("""<Int sr="arg3" val="0"/>""", """<Int sr="arg3" val="1"/>"""),
        ).forEach { xml -> assertFalse(xml, warnings(xml).any { "final line break" in it }) }
    }

    @Test
    fun searchReplaceReadsClassesAndQuotesTheWayTheRegexEnginesDo() {
        val base = """<code>598</code><Str sr="arg0" ve="3">%stdout</Str><Str sr="arg1" ve="3">PATTERN</Str><Int sr="arg2" val="0"/>""" +
            """<Int sr="arg3" val="0"/><Int sr="arg4" val="0"/><Str sr="arg5" ve="3"/><Int sr="arg6" val="1"/><Str sr="arg7" ve="3"/>"""
        fun warnsOfTheAnchor(pattern: String) = warnings(base.replace("PATTERN", pattern)).any { "final line break" in it }

        // A ] straight after [ or [^ is part of the class, so the $ after it is too.
        assertFalse(warnsOfTheAnchor("[]$]"))
        assertFalse(warnsOfTheAnchor("[^]$]"))
        assertTrue(warnsOfTheAnchor("[]]x$"))
        // \Q...\E quotes everything up to \E, a [ included.
        assertFalse(warnsOfTheAnchor("""\Q$\E"""))
        assertTrue(warnsOfTheAnchor("""\Q[\E\d+$"""))
        // An inline (?m) already matches before every line break.
        assertFalse(warnsOfTheAnchor("""(?m)\d+$"""))
        assertFalse(warnsOfTheAnchor("""(?im)\d+$"""))
        assertTrue(warnsOfTheAnchor("""(?i)\d+$"""))
    }

    @Test
    fun importedVariableStepsRunTheWayTaskersDo() {
        ActionRegistry.register(SetVariableAction())
        ActionRegistry.register(TextReplaceAction())
        ActionRegistry.register(TextSubstringAction())
        val add = """<code>888</code><Str sr="arg0" ve="3">%Count</Str><Int sr="arg1" val="1"/><Int sr="arg2" val="0"/>"""
        val task = parse(
            add,
            add,
            """<code>598</code><Str sr="arg0" ve="3">%Html</Str><Str sr="arg1" ve="3">&lt;</Str><Int sr="arg2" val="0"/>""" +
                """<Int sr="arg3" val="0"/><Int sr="arg4" val="0"/><Str sr="arg5" ve="3"/><Int sr="arg6" val="1"/><Str sr="arg7" ve="3">~lt~</Str>""",
            """<code>597</code><Str sr="arg0" ve="3">%Stamp</Str><Int sr="arg1" val="3"/><Int sr="arg2" val="2"/><Int sr="arg3" val="1"/>""" +
                """<Str sr="arg4" ve="3">%Part</Str>""",
            """<code>549</code><Str sr="arg0" ve="3">%Gone</Str><Int sr="arg1" val="0"/>""",
        ).bundle.tasks.single()
        val variables = VariableStore().apply {
            set("Html", "a<b<c")
            set("Stamp", "20261110")
            set("Gone", "x")
        }

        val report = runBlocking { TaskRunner(ActionContext(ContextWrapper(null), variables)).run(task) }

        assertTrue(report.toString(), report.success)
        assertEquals("2", variables.get("Count"))
        assertEquals("a~lt~b~lt~c", variables.get("Html"))
        assertEquals("26", variables.get("Part"))
        assertTrue(variables.get("Gone").isNullOrEmpty())
    }

    // Variable Split (590), Variable Join (592), Array Push (355), Array Pop (356), Array Clear (357)
    // and Array Process (369)

    @Test
    fun variableSplitKeepsItsSplitterAsWritten() {
        val dot = action("""<code>590</code><Str sr="arg0" ve="3">%TIME</Str><Str sr="arg1" ve="3">.</Str><Int sr="arg2" val="0"/>""")
        val spaced = action(
            """<code>590</code><Str sr="arg0" ve="3">%line</Str><Str sr="arg1" ve="3"> </Str><Int sr="arg2" val="1"/><Int sr="arg3" val="0"/>""",
        )
        val byVariable = action("""<code>590</code><Str sr="arg0" ve="3">%pair</Str><Str sr="arg1" ve="3">%equals_sign</Str><Int sr="arg2" val="0"/>""")

        assertEquals("var.split", dot.type)
        assertEquals(mapOf("name" to "%TIME", "splitter" to ".", "delete_base" to "false", "regex" to "false"), dot.args)
        assertEquals(mapOf("name" to "%line", "splitter" to " ", "delete_base" to "true", "regex" to "false"), spaced.args)
        assertEquals("%equals_sign", byVariable.args["splitter"])
    }

    @Test
    fun variableSplitWithARegexThisAppCannotRunStaysAPlaceholder() {
        val base = """<code>590</code><Str sr="arg0" ve="3">%s</Str><Str sr="arg1" ve="3">PATTERN</Str><Int sr="arg2" val="0"/><Int sr="arg3" val="1"/>"""

        assertEquals("true", action(base.replace("PATTERN", "[,;]")).args["regex"])
        assertEquals("var.split", action(base.replace("PATTERN", "%sep(?=x)")).type)
        assertEquals(placeholder, action(base.replace("PATTERN", ",(?=x)")).type)
        assertEquals("var.split", action(base.replace("PATTERN", ",(?=x)").replace("""<Int sr="arg3" val="1"/>""", """<Int sr="arg3" val="0"/>""")).type)
    }

    @Test
    fun variableJoinKeepsItsJoinerAndPartsFlag() {
        val join = action("""<code>592</code><Str sr="arg0" ve="3">%aicoordinates</Str><Str sr="arg1" ve="3">|||</Str><Int sr="arg2" val="0"/>""")
        val bare = action("""<code>592</code><Str sr="arg0" ve="3">%words</Str><Str sr="arg1" ve="3"/><Int sr="arg2" val="1"/>""")

        assertEquals("var.join", join.type)
        assertEquals(mapOf("name" to "%aicoordinates", "joiner" to "|||", "delete_parts" to "false"), join.args)
        assertEquals(mapOf("name" to "%words", "joiner" to "", "delete_parts" to "true"), bare.args)
    }

    @Test
    fun arrayPushPopAndClearNameTheirArray() {
        val push = action(
            """<code>355</code><Str sr="arg0" ve="3">%Postpones_log</Str><Int sr="arg1" val="99"/>""" +
                """<Str sr="arg2" ve="3">%Postpones</Str><Int sr="arg3" val="0"/>""",
        )
        val pushFirst = action(
            """<code>355</code><Str sr="arg0" ve="3">%twitalldate</Str><Int sr="arg1"><var>1</var></Int>""" +
                """<Str sr="arg2" ve="3">%twitfromdateu(1)</Str><Int sr="arg3" val="0"/>""",
        )
        val pop = action("""<code>356</code><Str sr="arg0" ve="3">%SS_TimedTimes</Str><Int sr="arg1"><var>%pos</var></Int><Str sr="arg2" ve="3"/>""")
        val popInto = action("""<code>356</code><Str sr="arg0" ve="3">%task_names</Str><Int sr="arg1" val="1"/><Str sr="arg2" ve="3">%first</Str>""")
        val clear = action("""<code>357</code><Str sr="arg0" ve="3">%SS_TimedTimes</Str>""")

        assertEquals("array.push", push.type)
        assertEquals(mapOf("array" to "%Postpones_log", "position" to "99", "value" to "%Postpones", "fill_spaces" to "false"), push.args)
        assertEquals(mapOf("array" to "%twitalldate", "position" to "1", "value" to "%twitfromdateu(1)", "fill_spaces" to "false"), pushFirst.args)
        assertEquals("array.pop", pop.type)
        assertEquals(mapOf("array" to "%SS_TimedTimes", "position" to "%pos"), pop.args)
        assertEquals(mapOf("array" to "%task_names", "position" to "1", "to_var" to "%first"), popInto.args)
        assertEquals("array.clear", clear.type)
        assertEquals(mapOf("array" to "%SS_TimedTimes"), clear.args)
    }

    @Test
    fun anArrayNamedThroughAnotherVariableStaysAPlaceholder() {
        listOf(
            """<code>357</code><Str sr="arg0" ve="3">%%array</Str>""",
            """<code>590</code><Str sr="arg0" ve="3">%%name</Str><Str sr="arg1" ve="3">,</Str><Int sr="arg2" val="0"/>""",
            """<code>355</code><Str sr="arg0" ve="3">%list</Str><Int sr="arg1"><var>%i+1</var></Int><Str sr="arg2" ve="3">x</Str><Int sr="arg3" val="0"/>""",
            """<code>356</code><Str sr="arg0" ve="3">%list</Str><Int sr="arg1" val="1"/><Str sr="arg2" ve="3">%out%n</Str>""",
        ).forEach { assertEquals(it, placeholder, action(it).type) }
    }

    @Test
    fun importedArrayStepsRunTheWayTaskersDo() {
        registerCoreRuntime()
        val task = parse(
            """<code>590</code><Str sr="arg0" ve="3">%names</Str><Str sr="arg1" ve="3">,</Str><Int sr="arg2" val="0"/>""",
            """<code>355</code><Str sr="arg0" ve="3">%names</Str><Int sr="arg1" val="1"/><Str sr="arg2" ve="3">zero</Str><Int sr="arg3" val="0"/>""",
            """<code>356</code><Str sr="arg0" ve="3">%names</Str><Int sr="arg1" val="99"/><Str sr="arg2" ve="3">%Last</Str>""",
            """<code>592</code><Str sr="arg0" ve="3">%names</Str><Str sr="arg1" ve="3">+</Str><Int sr="arg2" val="0"/>""",
            """<code>547</code><Str sr="arg0" ve="3">%Joined</Str><Str sr="arg1" ve="3">%names</Str><Int sr="arg2" val="0"/>""" +
                """<Int sr="arg3" val="0"/><Int sr="arg4" val="0"/>""",
        ).bundle.tasks.single()
        val variables = VariableStore().apply { set("names", "a,b,c") }

        val report = runBlocking { TaskRunner(ActionContext(ContextWrapper(null), variables)).run(task) }

        assertTrue(report.toString(), report.success)
        assertEquals("c", variables.get("Last"))
        assertEquals("zero+a+b", variables.get("Joined"))
    }

    @Test
    fun arrayProcessMapsTaskersOperationNumbers() {
        val sortNames = action("""<code>369</code><Str sr="arg0" ve="3">%names</Str><Int sr="arg1" val="5"/>""")
        val shuffle = action("""<code>369</code><label>随机</label><Str sr="arg0" ve="3">%Wallpaper</Str><Int sr="arg1" val="4"/>""")
        val caseless = action("""<code>369</code><Str sr="arg0" ve="3">%output</Str><Int sr="arg1" val="7"/>""")

        assertEquals("array.process", sortNames.type)
        assertEquals(mapOf("array" to "%names", "type" to "sort_alpha"), sortNames.args)
        assertEquals("shuffle", shuffle.args["type"])
        assertEquals("sort_alpha_caseless", caseless.args["type"])
        val expected = listOf(
            "remove_duplicates", "reverse", "rotate_left", "rotate_right", "shuffle", "sort_alpha", "sort_alpha_reverse",
            "sort_alpha_caseless", "sort_alpha_caseless_reverse", "sort_shortest_first", "sort_longest_first",
            "sort_numeric_integer", "sort_numeric_float", "squash",
        )
        expected.forEachIndexed { ordinal, type ->
            assertEquals(type, action("""<code>369</code><Str sr="arg0" ve="3">%list</Str><Int sr="arg1" val="$ordinal"/>""").args["type"])
        }
    }

    @Test
    fun arrayProcessWithAnUnknownOperationOrIndirectArrayStaysAPlaceholder() {
        listOf(
            """<code>369</code><Str sr="arg0" ve="3">%%array</Str><Int sr="arg1" val="11"/>""",
            """<code>369</code><Str sr="arg0" ve="3">%list</Str><Int sr="arg1" val="14"/>""",
            """<code>369</code><Str sr="arg0" ve="3">%list</Str><Int sr="arg1"><var>%op</var></Int>""",
            """<code>369</code><Str sr="arg0" ve="3">%list</Str>""",
        ).forEach { assertEquals(it, placeholder, action(it).type) }
    }

    @Test
    fun importedArrayProcessSortsTheWayTaskersDo() {
        registerCoreRuntime()
        val task = parse(
            """<code>590</code><Str sr="arg0" ve="3">%extras</Str><Str sr="arg1" ve="3">,</Str><Int sr="arg2" val="0"/>""",
            """<code>369</code><Str sr="arg0" ve="3">%extras</Str><Int sr="arg1" val="11"/>""",
            """<code>592</code><Str sr="arg0" ve="3">%extras</Str><Str sr="arg1" ve="3">,</Str><Int sr="arg2" val="0"/>""",
            """<code>547</code><Str sr="arg0" ve="3">%Sorted</Str><Str sr="arg1" ve="3">%extras</Str><Int sr="arg2" val="0"/>""" +
                """<Int sr="arg3" val="0"/><Int sr="arg4" val="0"/>""",
        ).bundle.tasks.single()
        val variables = VariableStore().apply { set("extras", "b5,a3c,none,1x,-2") }

        val report = runBlocking { TaskRunner(ActionContext(ContextWrapper(null), variables)).run(task) }

        assertTrue(report.toString(), report.success)
        assertEquals("-2,1x,a3c,b5,none", variables.get("Sorted"))
    }

    // Variable Convert (596)

    @Test
    fun variableConvertMapsTaskersFunctionNumbersAndStoreIn() {
        val toDateTime = action("""<code>596</code><Str sr="arg0" ve="3">%endtime</Str><Int sr="arg1" val="4"/><Str sr="arg2" ve="3">%enddatetime</Str>""")
        val lowerInPlace = action("""<code>596</code><Str sr="arg0" ve="3">%name</Str><Int sr="arg1" val="28"/><Str sr="arg2" ve="3"/><Int sr="arg3" val="0"/>""")
        val html = action("""<code>596</code><Str sr="arg0" ve="3">%newsout</Str><Int sr="arg1" val="7"/><Str sr="arg2" ve="3"/>""")
        val toSeconds = action("""<code>596</code><Str sr="arg0" ve="3">%spxdate</Str><Int sr="arg1" val="3"/><Str sr="arg2" ve="3">%spxtime</Str>""")
        val urlSafe = action("""<code>596</code><Str sr="arg0" ve="3">%token</Str><Int sr="arg1" val="25"/><Str sr="arg2" ve="3"/><Int sr="arg3" val="1"/>""")

        assertEquals("var.convert", toDateTime.type)
        assertEquals(mapOf("name" to "%endtime", "function" to "seconds_to_date_time", "var" to "%enddatetime"), toDateTime.args)
        assertEquals(mapOf("name" to "%name", "function" to "lower_case"), lowerInPlace.args)
        assertEquals("html_to_text", html.args["function"])
        assertEquals(mapOf("name" to "%spxdate", "function" to "date_time_to_seconds", "var" to "%spxtime"), toSeconds.args)
        assertEquals(mapOf("name" to "%token", "function" to "base64_decode", "url_safe" to "true"), urlSafe.args)
        val expected = listOf(
            "bytes_to_kilobytes", "bytes_to_megabytes", "bytes_to_gigabytes", "date_time_to_seconds", "seconds_to_date_time",
            "seconds_to_medium_date_time", "seconds_to_long_date_time", "html_to_text", "celsius_to_fahrenheit",
            "fahrenheit_to_celsius", "centimetres_to_inches", "inches_to_centimetres", "metres_to_feet", "feet_to_metres",
            "kilograms_to_pounds", "pounds_to_kilograms", "kilometres_to_miles", "miles_to_kilometres", "url_decode",
            "url_encode", "binary_to_decimal", "decimal_to_binary", "hex_to_decimal", "decimal_to_hex", "base64_encode",
            "base64_decode", "md5_digest", "sha1_digest", "lower_case", "upper_case", "upper_case_first", "json_encode",
        )
        expected.forEachIndexed { ordinal, function ->
            assertEquals(function, action("""<code>596</code><Str sr="arg0" ve="3">%v</Str><Int sr="arg1" val="$ordinal"/>""").args["function"])
        }
    }

    @Test
    fun variableConvertOfAnArrayItemOrAnUnknownFunctionStaysAPlaceholder() {
        listOf(
            """<code>596</code><Str sr="arg0" ve="3">%SS_TimedTimes(%num)</Str><Int sr="arg1" val="4"/><Str sr="arg2" ve="3">%time</Str>""",
            """<code>596</code><Str sr="arg0" ve="3">%v</Str><Int sr="arg1" val="32"/>""",
            """<code>596</code><Str sr="arg0" ve="3">%v</Str><Int sr="arg1"><var>%how</var></Int>""",
            """<code>596</code><Str sr="arg0" ve="3">%v</Str><Int sr="arg1" val="28"/><Str sr="arg2" ve="3">%out(1)</Str>""",
        ).forEach { assertEquals(it, placeholder, action(it).type) }
        assertWarns("""<code>596</code><Str sr="arg0" ve="3">%SS_TimedTimes(%num)</Str><Int sr="arg1" val="4"/>""", "isn't one plain variable")
    }

    @Test
    fun aVariableNameLongerThanThisAppTakesStaysAPlaceholder() {
        val longest = "%" + "a".repeat(VariableNamePolicy.MAX_LENGTH)
        val tooLong = "%" + "a".repeat(VariableNamePolicy.MAX_LENGTH + 1)

        assertEquals("var.convert", action("""<code>596</code><Str sr="arg0" ve="3">$longest</Str><Int sr="arg1" val="28"/>""").type)
        assertEquals(placeholder, action("""<code>596</code><Str sr="arg0" ve="3">$tooLong</Str><Int sr="arg1" val="28"/>""").type)
        assertEquals(placeholder, action("""<code>596</code><Str sr="arg0" ve="3">%v</Str><Int sr="arg1" val="28"/><Str sr="arg2" ve="3">$tooLong</Str>""").type)
    }

    @Test
    fun importedVariableConvertRunsTheWayTaskersDoes() {
        registerCoreRuntime()
        val task = parse(
            """<code>547</code><Str sr="arg0" ve="3">%Bytes</Str><Str sr="arg1" ve="3">1572864</Str><Int sr="arg2" val="0"/>""" +
                """<Int sr="arg3" val="0"/><Int sr="arg4" val="0"/>""",
            """<code>596</code><Str sr="arg0" ve="3">%Bytes</Str><Int sr="arg1" val="1"/><Str sr="arg2" ve="3">%Megabytes</Str><Int sr="arg3" val="0"/>""",
            """<code>596</code><Str sr="arg0" ve="3">%Name</Str><Int sr="arg1" val="28"/><Str sr="arg2" ve="3"/><Int sr="arg3" val="0"/>""",
        ).bundle.tasks.single()
        val variables = VariableStore().apply { set("Name", "Front DOOR") }

        val report = runBlocking { TaskRunner(ActionContext(ContextWrapper(null), variables)).run(task) }

        assertTrue(report.toString(), report.success)
        assertEquals("1.5", variables.get("Megabytes"))
        assertEquals("1572864", variables.get("Bytes"))
        assertEquals("front door", variables.get("Name"))
    }

    // Interrupt Mode (312). No public export holds one, so these follow Tasker's action table.

    @Test
    fun interruptModeMapsTheFourModesThisAppHas() {
        fun mode(index: String) =
            """<code>312</code><Int sr="arg0" val="$index"/><Int sr="arg1" val="0"/><Int sr="arg2" val="0"/><Int sr="arg3" val="0"/>"""

        mapOf("0" to "total_silence", "1" to "priority", "2" to "off", "3" to "alarms").forEach { (index, expected) ->
            val action = action(mode(index))
            assertEquals("dnd.set", action.type)
            assertEquals(expected, action.args["mode"])
        }
        assertWarns(mode("1"), "priority settings")
        assertWarns(mode("2") + """<Str sr="arg6" ve="3">%mode</Str>""", "Output Variables")
    }

    @Test
    fun interruptModeThisAppHasNoMatchForStaysAPlaceholder() {
        listOf(
            """<code>312</code><Int sr="arg0" val="4"/>""",
            """<code>312</code><Int sr="arg0" val="5"/>""",
            """<code>312</code><Int sr="arg0"><var>%m</var></Int>""",
            // A missing mode would read as 0, the most silent one.
            """<code>312</code><Int sr="arg1" val="0"/>""",
        ).forEach { assertEquals(it, placeholder, action(it).type) }
        assertWarns("""<code>312</code><Int sr="arg0" val="4"/>""", "Custom")
    }

    // Return (126)

    @Test
    fun returnEndsTheCalledTaskAndNamesTheValueItCannotPassBack() {
        val stop = """<code>126</code><Str sr="arg0" ve="3">%par1</Str><Int sr="arg1" val="1"/><Int sr="arg2" val="0"/><Int sr="arg3" val="0"/><Str sr="arg4" ve="3"/>"""

        assertEquals("flow.stop", action(stop).type)
        assertWarns(stop, "%par1")
        assertEquals(placeholder, action(stop.replace("""<Int sr="arg1" val="1"/>""", """<Int sr="arg1" val="0"/>""")).type)
    }

    // Send Intent (877)

    @Test
    fun sendIntentCarriesItsFieldsToIntentLaunch() {
        val onpc = """<code>877</code><Str sr="arg0" ve="3">android.intent.action.MAIN</Str><Int sr="arg1" val="8"/>""" +
            """<Str sr="arg2" ve="3">text/xml</Str><Str sr="arg3" ve="3">%iscpCmd</Str><Str sr="arg4" ve="3"/><Str sr="arg5" ve="3"/>""" +
            """<Str sr="arg6" ve="3"/><Str sr="arg7" ve="3">com.mkulesh.onpc</Str><Str sr="arg8" ve="3">com.mkulesh.onpc.MainActivity</Str><Int sr="arg9" val="1"/>"""
        val action = action(onpc)

        assertEquals("intent.launch", action.type)
        assertEquals(
            mapOf(
                "package" to "com.mkulesh.onpc",
                "mode" to "activity",
                "component" to "com.mkulesh.onpc.MainActivity",
                "action" to "android.intent.action.MAIN",
                "category" to "android.intent.category.LAUNCHER",
                "uri" to "%iscpCmd",
                "mime_type" to "text/xml",
            ),
            action.args,
        )
        assertWarns(onpc, "grant_read_uri")
    }

    @Test
    fun sendIntentExtrasKeepTheTypesTaskerWouldGiveThem() {
        val ping = """<code>877</code><Str sr="arg0" ve="3">com.example.app.PING</Str><Int sr="arg1" val="0"/>""" +
            """<Str sr="arg4" ve="3">count:5</Str><Str sr="arg5" ve="3">loud:true</Str><Str sr="arg6" ve="3">label:hi there</Str>""" +
            """<Str sr="arg7" ve="3">com.example.app</Str><Str sr="arg8" ve="3">com.example.app.Receiver</Str><Int sr="arg9" val="0"/>"""
        val action = action(ping)

        assertEquals("intent.launch", action.type)
        assertEquals("broadcast", action.args["mode"])
        assertEquals("count=int:5\nloud=bool:true\nlabel=string:hi there", action.args["extras"])
        assertFalse(warnings(ping).toString(), warnings(ping).any { "sent as text" in it })

        val guessed = ping.replace("loud:true", "big:9999999999").replace("label:hi there", "who:%who")
        assertEquals("count=int:5\nbig=string:9999999999\nwho=string:%who", action(guessed).args["extras"])
        assertWarns(guessed, "sent as text: big, who")
    }

    @Test
    fun sendIntentThisAppCannotSendStaysAPlaceholderAndSaysWhy() {
        val anki = """<code>877</code><Str sr="arg0" ve="3">com.ichi2.anki.DO_SYNC</Str><Int sr="arg1" val="1"/><Str sr="arg2" ve="3"/>""" +
            """<Str sr="arg3" ve="3"/><Str sr="arg7" ve="3"/><Str sr="arg8" ve="3"/><Int sr="arg9" val="2"/>"""
        val camsung = """<code>877</code><Str sr="arg0" ve="3">android.com.ericswpark.camsung.ACTION_SET_CAMERA</Str><Int sr="arg1" val="0"/>""" +
            """<Str sr="arg2" ve="3"/><Str sr="arg3" ve="3">app://mute</Str><Str sr="arg7" ve="3">android.com.ericswpark.camsung</Str>""" +
            """<Str sr="arg8" ve="3">android.com.ericswpark.camsung.Receiver</Str><Int sr="arg9" val="0"/>"""
        val autojs = """<code>877</code><Str sr="arg0" ve="3"/><Int sr="arg1" val="1"/><Str sr="arg2" ve="3">text/javascript</Str>""" +
            """<Str sr="arg3" ve="3">file:///storage/emulated/0/a.js</Str><Str sr="arg7" ve="3">org.autojs.autojs6</Str>""" +
            """<Str sr="arg8" ve="3">org.autojs.autojs.external.open.RunIntentActivity</Str><Int sr="arg9" val="1"/>"""

        listOf(anki, camsung, autojs).forEach { assertEquals(it, placeholder, action(it).type) }
        assertWarns(anki, "names no app")
        assertWarns(camsung, "not allowlisted")
    }

    // In-Call Volume (306)

    @Test
    fun inCallVolumeSetsTheVoiceStream() {
        val voice = action("""<code>306</code><Int sr="arg0" val="4"/><Int sr="arg1" val="0"/><Int sr="arg2" val="0"/>""")

        assertEquals("volume.set", voice.type)
        assertEquals("voice", voice.args["stream"])
        assertEquals("4", voice.args["level"])
    }

    // The item's acceptance: one of each code the item named, nothing left as a placeholder.

    @Test
    fun oneActionOfEachCodeImportsWithNoPlaceholder() {
        val report = parse(
            """<code>41</code><Str sr="arg0" ve="3">1</Str><Str sr="arg1" ve="3">m</Str>""",
            """<code>105</code><Str sr="arg0" ve="3">t</Str><Int sr="arg1" val="0"/>""",
            """<code>116</code><Str sr="arg0" ve="3">https://example.invalid</Str><Str sr="arg2" ve="3">d</Str>""",
            """<code>118</code><Str sr="arg0" ve="3">https://example.invalid</Str>""",
            """<code>235</code><Int sr="arg0" val="2"/><Str sr="arg1" ve="3">a</Str><Str sr="arg2" ve="3">1</Str>""",
            """<code>294</code><Int sr="arg0" val="1"/>""",
            """<code>333</code><Int sr="arg0" val="1"/>""",
            """<code>339</code><Int sr="arg1" val="0"/><Str sr="arg2" ve="3">https://example.invalid</Str>""",
            """<code>410</code><Str sr="arg0" ve="3">p</Str><Str sr="arg1" ve="3">t</Str>""",
            """<code>417</code><Str sr="arg0" ve="3">p</Str><Str sr="arg1" ve="3">%v</Str>""",
            """<code>425</code><Int sr="arg0" val="1"/>""",
            """<code>443</code><Int sr="arg0" val="0"/>""",
        )

        assertTrue(report.unsupportedActions.toString(), report.unsupportedActions.isEmpty())
        assertEquals(
            listOf(
                "sms.send", "clipboard.set", "http.request", "http.request", "settings.write", "bluetooth.toggle",
                "airplane.toggle", "http.request", "file.write", "file.read", "wifi.toggle", "track.next",
            ),
            report.bundle.tasks.single().actions.map { it.type },
        )
    }

    @Test
    fun takePhotoAndTheCodeRunnersStayPlaceholdersOnPurpose() {
        // No camera action exists, Run Shell mustn't change trust model and there's no JavaScript engine.
        listOf("101", "123", "129").forEach { code ->
            assertEquals(TaskerXmlImporter.TASKER_UNSUPPORTED_ACTION_ID, action("<code>$code</code>").type)
        }
    }

    // Goto (135), Anchor (300)

    @Test
    fun gotoByNumberOrLabelBecomesAGoTo() {
        val number = action("""<code>135</code><Int sr="arg0" val="0"/><Int sr="arg1" val="24"/><Str sr="arg2" ve="3"/>""")
        val variable = action("""<code>135</code><Int sr="arg0" val="0"/><Int sr="arg1"><var>%back</var></Int><Str sr="arg2" ve="3"/>""")
        val label = action("""<code>135</code><Int sr="arg0" val="1"/><Int sr="arg1" val="1"/><Str sr="arg2" ve="3">Wait Loop</Str>""")

        assertEquals("flow.goto", number.type)
        assertEquals(mapOf("step" to "24"), number.args)
        assertEquals("flow.goto", variable.type)
        assertEquals(mapOf("step" to "%back"), variable.args)
        assertEquals("flow.goto", label.type)
        assertEquals(mapOf("label" to "Wait Loop"), label.args)
    }

    @Test
    fun anAnchorAndAStepLabelComeAcrossSoALabelledGotoStillLands() {
        val task = parse(
            """<code>300</code><label>Wait Loop</label>""",
            """<code>547</code><label> Count </label><Str sr="arg0" ve="3">%n</Str><Str sr="arg1" ve="3">1</Str><Int sr="arg2" val="0"/><Int sr="arg3" val="0"/><Int sr="arg4" val="0"/>""",
            """<code>135</code><Int sr="arg0" val="1"/><Int sr="arg1" val="1"/><Str sr="arg2" ve="3">Wait Loop</Str>""",
        ).bundle.tasks.single()

        assertEquals(listOf("flow.anchor", "var.set", "flow.goto"), task.actions.map { it.type })
        assertEquals(listOf("Wait Loop", "Count", "Tasker goto"), task.actions.map { it.label })
        assertEquals(0, FlowControl.gotoTarget(task.actions, task.actions[2].args["label"], null).index)
    }
}
