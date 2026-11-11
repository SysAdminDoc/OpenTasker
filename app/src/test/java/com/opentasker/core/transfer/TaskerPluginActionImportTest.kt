package com.opentasker.core.transfer

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tasker plugin steps, laid out as real exports write them: the plugin's settings in a typed
 * bundle at arg0, its package at arg1 and its settings screen at arg2.
 */
class TaskerPluginActionImportTest {
    private fun import(action: String) = TaskerXmlImporter.parse(
        rawXml = """
            <TaskerData>
                <Task sr="task1">
                    <id>1</id>
                    <nme>Plugin task</nme>
                    $action
                </Task>
            </TaskerData>
        """.trimIndent(),
        appVersion = "test",
        importedAtEpochMs = 123L,
    )

    private fun json(text: String): JsonElement = Json.parseToJsonElement(text)

    /** One plugin step holding [vals], as the single imported action. */
    private fun plugin(vals: String) = import(
        """
        <Action sr="act0" ve="7">
            <code>703953103</code>
            <Bundle sr="arg0">
                <Vals sr="val">
                    $vals
                </Vals>
            </Bundle>
            <Str sr="arg1" ve="3">com.example.plugin</Str>
            <Str sr="arg2" ve="3">com.example.plugin.EditActivity</Str>
        </Action>
        """,
    ).bundle.tasks.single().actions.single()

    @Test
    fun aKustomStepFiresThePluginWithItsSettingsAndTypes() {
        val report = import(
            """
            <Action sr="act0" ve="7">
                <code>1186637727</code>
                <Bundle sr="arg0">
                    <Vals sr="val">
                        <com.twofortyfouram.locale.intent.extra.BLURB>Set: DISTEXT</com.twofortyfouram.locale.intent.extra.BLURB>
                        <com.twofortyfouram.locale.intent.extra.BLURB-type>java.lang.String</com.twofortyfouram.locale.intent.extra.BLURB-type>
                        <net.dinglisch.android.tasker.extras.VARIABLE_REPLACE_KEYS>org.kustom.tasker.VAR_NAME org.kustom.tasker.VAR_VALUE</net.dinglisch.android.tasker.extras.VARIABLE_REPLACE_KEYS>
                        <net.dinglisch.android.tasker.extras.VARIABLE_REPLACE_KEYS-type>java.lang.String</net.dinglisch.android.tasker.extras.VARIABLE_REPLACE_KEYS-type>
                        <net.dinglisch.android.tasker.subbundled>true</net.dinglisch.android.tasker.subbundled>
                        <net.dinglisch.android.tasker.subbundled-type>java.lang.Boolean</net.dinglisch.android.tasker.subbundled-type>
                        <org.kustom.tasker.VAR_NAME>DISTEXT</org.kustom.tasker.VAR_NAME>
                        <org.kustom.tasker.VAR_NAME-type>java.lang.String</org.kustom.tasker.VAR_NAME-type>
                        <org.kustom.tasker.VAR_VALUE>%DisconText</org.kustom.tasker.VAR_VALUE>
                        <org.kustom.tasker.VAR_VALUE-type>java.lang.String</org.kustom.tasker.VAR_VALUE-type>
                        <org.kustom.tasker.extra.INT_VERSION_CODE>375407209</org.kustom.tasker.extra.INT_VERSION_CODE>
                        <org.kustom.tasker.extra.INT_VERSION_CODE-type>java.lang.Integer</org.kustom.tasker.extra.INT_VERSION_CODE-type>
                    </Vals>
                </Bundle>
                <Str sr="arg1" ve="3">org.kustom.widget</Str>
                <Str sr="arg2" ve="3">org.kustom.lib.editor.tasker.EditVarActivity</Str>
                <Int sr="arg3" val="0"/>
                <Int sr="arg4" val="1"/>
            </Action>
            """,
        )

        val action = report.bundle.tasks.single().actions.single()
        assertEquals("plugin.locale.fire", action.type)
        assertEquals("org.kustom.widget", action.args["package"])
        assertEquals("Set: DISTEXT", action.args["blurb"])
        assertEquals(
            json(
                """
                {
                    "net.dinglisch.android.tasker.extras.VARIABLE_REPLACE_KEYS": "org.kustom.tasker.VAR_NAME org.kustom.tasker.VAR_VALUE",
                    "net.dinglisch.android.tasker.subbundled": {"boolean": true},
                    "org.kustom.tasker.VAR_NAME": "DISTEXT",
                    "org.kustom.tasker.VAR_VALUE": "%DisconText",
                    "org.kustom.tasker.extra.INT_VERSION_CODE": {"int": 375407209}
                }
                """,
            ),
            json(action.args.getValue("bundleJson")),
        )
        assertEquals("0", action.args["timeoutMs"])
        assertEquals(listOf("plugin.locale.fire"), report.mappedActions.map { it.openTaskerActionId })
        assertTrue(report.unsupportedActions.isEmpty())
        assertTrue(report.lossyWarnings.toString(), report.lossyWarnings.none { "org.kustom.widget" in it })
    }

    @Test
    fun anAutoInputStepKeepsItsNullsAndItsWait() {
        val report = import(
            """
            <Action sr="act0" ve="7">
                <code>1732635924</code>
                <Bundle sr="arg0">
                    <Vals sr="val">
                        <ActionId>Show Now Playing</ActionId>
                        <ActionId-type>java.lang.String</ActionId-type>
                        <IsTaskerAction>true</IsTaskerAction>
                        <IsTaskerAction-type>java.lang.Boolean</IsTaskerAction-type>
                        <NearbyText>&lt;null&gt;</NearbyText>
                        <NearbyText-type>java.lang.String</NearbyText-type>
                        <net.dinglisch.android.tasker.RELEVANT_VARIABLES>&lt;StringArray sr=""&gt;&lt;_array_net.dinglisch.android.tasker.RELEVANT_VARIABLES0&gt;%aiscreen
            Screen On or Off
            'on' if screen was on and 'off' otherwise&lt;/_array_net.dinglisch.android.tasker.RELEVANT_VARIABLES0&gt;&lt;_array_net.dinglisch.android.tasker.RELEVANT_VARIABLES1&gt;%err
            Error Code
            Only available if you select &amp;lt;b&amp;gt;Continue Task After Error&amp;lt;/b&amp;gt;&lt;/_array_net.dinglisch.android.tasker.RELEVANT_VARIABLES1&gt;&lt;/StringArray&gt;</net.dinglisch.android.tasker.RELEVANT_VARIABLES>
                        <net.dinglisch.android.tasker.RELEVANT_VARIABLES-type>[Ljava.lang.String;</net.dinglisch.android.tasker.RELEVANT_VARIABLES-type>
                    </Vals>
                </Bundle>
                <Str sr="arg1" ve="3">com.joaomgcd.autoinput</Str>
                <Str sr="arg2" ve="3">com.joaomgcd.autoinput.activity.ActivityConfigPerformAction</Str>
                <Int sr="arg3" val="4"/>
            </Action>
            """,
        )

        val action = report.bundle.tasks.single().actions.single()
        assertEquals("plugin.locale.fire", action.type)
        val bundle = json(action.args.getValue("bundleJson"))
        // The importer's trimIndent takes the fixture's indent off the item lines too.
        val variables = Json.parseToJsonElement(
            """
            {"stringArray": [
                "%aiscreen\nScreen On or Off\n'on' if screen was on and 'off' otherwise",
                "%err\nError Code\nOnly available if you select <b>Continue Task After Error</b>"
            ]}
            """,
        )
        assertEquals(
            json(
                """{"ActionId": "Show Now Playing", "IsTaskerAction": {"boolean": true}, "NearbyText": {"null": true}, "net.dinglisch.android.tasker.RELEVANT_VARIABLES": $variables}""",
            ),
            bundle,
        )
        assertFalse(action.args.containsKey("blurb"))
        // The step waits the 4 seconds Tasker would and gets %aiscreen and %err back, so nothing is lost.
        assertEquals("4000", action.args["timeoutMs"])
        assertTrue(report.lossyWarnings.toString(), report.lossyWarnings.none { "com.joaomgcd.autoinput" in it })
    }

    @Test
    fun stringArrayItemsKeepTheirNumberedOrderPastTen() {
        val items = (0..10).sortedBy(Int::toString).joinToString("") { "&lt;_array_k$it&gt;item$it&lt;/_array_k$it&gt;" }
        val report = import(
            """
            <Action sr="act0" ve="7">
                <code>297358436</code>
                <Bundle sr="arg0">
                    <Vals sr="val">
                        <k>&lt;StringArray sr=""&gt;$items&lt;/StringArray&gt;</k>
                        <k-type>[Ljava.lang.String;</k-type>
                    </Vals>
                </Bundle>
                <Str sr="arg1" ve="3">com.example.plugin</Str>
                <Str sr="arg2" ve="3">com.example.plugin.EditActivity</Str>
            </Action>
            """,
        )

        val action = report.bundle.tasks.single().actions.single()
        val expected = (0..10).joinToString(",") { "\"item$it\"" }
        assertEquals(json("""{"k": {"stringArray": [$expected]}}"""), json(action.args.getValue("bundleJson")))
    }

    @Test
    fun aKeyboardStepWithNoPluginPackageStaysAPlaceholder() {
        val report = import(
            """
            <Action sr="act0" ve="7">
                <code>328</code>
                <Bundle sr="arg0">
                    <pref>ly</pref>
                    <Vals sr="val">
                        <net.dinglisch.android.tasker.RELEVANT_VARIABLES>&lt;StringArray sr=""&gt;&lt;_array_net.dinglisch.android.tasker.RELEVANT_VARIABLES0&gt;%kb_text
            Text
            The text in the field, if any&lt;/_array_net.dinglisch.android.tasker.RELEVANT_VARIABLES0&gt;&lt;/StringArray&gt;</net.dinglisch.android.tasker.RELEVANT_VARIABLES>
                        <net.dinglisch.android.tasker.RELEVANT_VARIABLES-type>[Ljava.lang.String;</net.dinglisch.android.tasker.RELEVANT_VARIABLES-type>
                    </Vals>
                </Bundle>
                <Str sr="arg1" ve="3">wait(0)</Str>
                <Int sr="arg2">
                    <var>0</var>
                </Int>
                <Int sr="arg3" val="1"/>
            </Action>
            """,
        )

        val action = report.bundle.tasks.single().actions.single()
        assertEquals("tasker.unsupported", action.type)
        assertEquals(listOf("328"), report.unsupportedActions.map { it.taskerCode })
    }

    @Test
    fun aValueOfATypeAPluginBundleCantHoldStaysAPlaceholderNamingTheKey() {
        val report = import(
            """
            <Action sr="act0" ve="7">
                <code>703953103</code>
                <Bundle sr="arg0">
                    <Vals sr="val">
                        <title>Hello</title>
                        <title-type>java.lang.String</title-type>
                        <extras>Bundle[{}]</extras>
                        <extras-type>android.os.Bundle</extras-type>
                    </Vals>
                </Bundle>
                <Str sr="arg1" ve="3">com.example.plugin</Str>
                <Str sr="arg2" ve="3">com.example.plugin.EditActivity</Str>
            </Action>
            """,
        )

        val action = report.bundle.tasks.single().actions.single()
        assertEquals("tasker.unsupported", action.type)
        assertTrue(action.args["summary"], "extras" in action.args.getValue("summary"))
        assertTrue(action.args["summary"], "android.os.Bundle" in action.args.getValue("summary"))
    }

    @Test
    fun aStringArrayCarryingADoctypeStaysAPlaceholder() {
        val report = import(
            """
            <Action sr="act0" ve="7">
                <code>703953103</code>
                <Bundle sr="arg0">
                    <Vals sr="val">
                        <k>&lt;!DOCTYPE StringArray [&lt;!ENTITY x "y"&gt;]&gt;&lt;StringArray sr=""&gt;&lt;_array_k0&gt;&amp;x;&lt;/_array_k0&gt;&lt;/StringArray&gt;</k>
                        <k-type>[Ljava.lang.String;</k-type>
                    </Vals>
                </Bundle>
                <Str sr="arg1" ve="3">com.example.plugin</Str>
                <Str sr="arg2" ve="3">com.example.plugin.EditActivity</Str>
            </Action>
            """,
        )

        val action = report.bundle.tasks.single().actions.single()
        assertEquals("tasker.unsupported", action.type)
        assertTrue(action.args["summary"], "k isn't a valid" in action.args.getValue("summary"))
    }

    @Test
    fun longFloatAndDoubleValuesKeepTheirTypes() {
        val action = plugin(
            """
            <n>9000000000</n><n-type>java.lang.Long</n-type>
            <f>1.5</f><f-type>java.lang.Float</f-type>
            <d>2.25</d><d-type>java.lang.Double</d-type>
            """,
        )

        assertEquals(
            json("""{"d": {"double": 2.25}, "f": {"float": 1.5}, "n": {"long": 9000000000}}"""),
            json(action.args.getValue("bundleJson")),
        )
    }

    @Test
    fun aKeyThatEndsInTypeKeepsItsValue() {
        val action = plugin(
            """
            <font>Sans</font><font-type>java.lang.String</font-type>
            <font-type>Roboto</font-type><font-type-type>java.lang.String</font-type-type>
            <size>12</size><size-type>java.lang.Integer</size-type>
            """,
        )

        assertEquals(json("""{"font": "Sans", "font-type": "Roboto", "size": {"int": 12}}"""), json(action.args.getValue("bundleJson")))
    }

    @Test
    fun valuesThatCantBeSentStayPlaceholdersNamingTheKey() {
        val cases = mapOf(
            "<ratio>NaN</ratio><ratio-type>java.lang.Double</ratio-type>" to "ratio",
            "<scale>Infinity</scale><scale-type>java.lang.Float</scale-type>" to "scale",
            "<on>yes</on><on-type>java.lang.Boolean</on-type>" to "on isn't a valid",
            "<big>${"x".repeat(17_000)}</big><big-type>java.lang.String</big-type>" to "for big is too large",
            "<${"k".repeat(300)}>v</${"k".repeat(300)}><${"k".repeat(300)}-type>java.lang.String</${"k".repeat(300)}-type>" to "key ${"k".repeat(40)}... is too large",
            (0..128).joinToString("") { "<k$it>v</k$it><k$it-type>java.lang.String</k$it-type>" } to "too many entries",
            (0..2).joinToString("") { "<k$it>${"x".repeat(6_000)}</k$it><k$it-type>java.lang.String</k$it-type>" } to "exceeds 16384 bytes",
        )

        cases.forEach { (vals, named) ->
            val action = plugin(vals)
            assertEquals(vals.take(40), "tasker.unsupported", action.type)
            assertTrue(action.args["summary"], named in action.args.getValue("summary"))
        }
    }

    @Test
    fun stringArrayItemsUnderAKeyEndingInDigitsKeepTheirOrder() {
        val key = "since1700000000"
        val items = (0..10).sortedBy(Int::toString).joinToString("") { "&lt;_array_$key$it&gt;item$it&lt;/_array_$key$it&gt;" }
        val action = plugin("""<$key>&lt;StringArray sr=""&gt;$items&lt;/StringArray&gt;</$key><$key-type>[Ljava.lang.String;</$key-type>""")

        val expected = (0..10).joinToString(",") { "\"item$it\"" }
        assertEquals(json("""{"$key": {"stringArray": [$expected]}}"""), json(action.args.getValue("bundleJson")))
    }

    @Test
    fun aStringArrayNestedPastItsItemsStaysAPlaceholder() {
        val deep = "&lt;a&gt;".repeat(2_000) + "deep" + "&lt;/a&gt;".repeat(2_000)
        val action = plugin("""<k>&lt;StringArray sr=""&gt;&lt;_array_k0&gt;$deep&lt;/_array_k0&gt;&lt;/StringArray&gt;</k><k-type>[Ljava.lang.String;</k-type>""")

        assertEquals("tasker.unsupported", action.type)
        assertTrue(action.args["summary"], "k isn't a valid" in action.args.getValue("summary"))
    }
}
