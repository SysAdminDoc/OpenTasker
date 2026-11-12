package com.opentasker.core.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * More Tasker actions with no counterpart here. They stay placeholders, and each import note names
 * the Tasker action and says what's missing instead of the generic line. Every step is copied from
 * a real export, with three changes the importer doesn't read. Parse/Format DateTime and Get Battery
 * Info have their lists of output variables cut to the first two. Each output variable is written on
 * one line, without the line breaks Tasker puts between its name, label and description. Simple
 * Match/Regex's bundle leaves out its pref element.
 */
class TaskerMoreNoCounterpartReasonTest {

    private fun importNote(action: String): String {
        val report = TaskerXmlImporter.parse(
            rawXml = """
                <TaskerData>
                    <Task sr="task1">
                        <id>1</id><nme>Kept</nme>
                        $action
                    </Task>
                </TaskerData>
            """.trimIndent(),
            appVersion = "test",
            importedAtEpochMs = 0L,
        )
        val step = report.bundle.tasks.single().actions.single()
        assertEquals(TaskerXmlImporter.TASKER_UNSUPPORTED_ACTION_ID, step.type)
        assertTrue(report.lossyWarnings.toString(), report.lossyWarnings.isEmpty())
        return step.args.getValue("summary")
    }

    private fun relevantVariables(vararg names: String): String {
        val items = names.mapIndexed { index, name ->
            "&lt;_array_net.dinglisch.android.tasker.RELEVANT_VARIABLES$index&gt;$name&lt;/_array_net.dinglisch.android.tasker.RELEVANT_VARIABLES$index&gt;"
        }.joinToString("")
        val array = if (names.isEmpty()) "&lt;StringArray sr=\"\"/&gt;" else "&lt;StringArray sr=\"\"&gt;$items&lt;/StringArray&gt;"
        return """<Bundle sr="arg0"><Vals sr="val"><net.dinglisch.android.tasker.RELEVANT_VARIABLES>$array</net.dinglisch.android.tasker.RELEVANT_VARIABLES>""" +
            """<net.dinglisch.android.tasker.RELEVANT_VARIABLES-type>[Ljava.lang.String;</net.dinglisch.android.tasker.RELEVANT_VARIABLES-type></Vals></Bundle>"""
    }

    @Test
    fun matchAndDateStepsNameTheVariablesTaskerFills() {
        val match = importNote(
            """<Action sr="act12" ve="7"><code>396</code>""" +
                relevantVariables("%mt_match匹配也可以作为数组访问", "%mt_match_found匹配如果匹配则为真，否则为假", "%mt_groups()组") +
                """<Int sr="arg1" val="1"/><Str sr="arg2" ve="3">%TextAfterCursor</Str><Str sr="arg3" ve="3">.*?[?？。\.!\n]</Str><Str sr="arg4" ve="3"/></Action>""",
        )
        val date = importNote(
            """<Action sr="act3" ve="7"><code>394</code>""" +
                relevantVariables("%formatted00. Formatted", "%dt_millis1. MilliSecondsMilliseconds Since Epoch") +
                """<Int sr="arg1" val="2"/><Int sr="arg10" val="0"/><Str sr="arg11" ve="3"/><Str sr="arg12" ve="3"/>""" +
                """<Str sr="arg2" ve="3">%bat_full_in</Str><Str sr="arg3" ve="3"/><Str sr="arg4" ve="3"/><Str sr="arg5" ve="3">kk:mm</Str>""" +
                """<Str sr="arg6" ve="3"/><Str sr="arg7" ve="3"/><Int sr="arg8" val="0"/><Int sr="arg9" val="0"/></Action>""",
        )

        assertTrue(match, "Tasker's Simple Match/Regex" in match && "%mt_match_found" in match && "an array you name" in match)
        assertTrue(date, "Tasker's Parse/Format DateTime" in date && "%formatted" in date && "each fill one variable" in date)
    }

    @Test
    fun stepsThatReadDetailsSayThereIsNoStepThatDoes() {
        listOf(
            "Set Variable Structure Type" to
                """<Action sr="act0" ve="7"><code>392</code>${relevantVariables()}<Str sr="arg1" ve="3">%http_request_body</Str><Str sr="arg2" ve="3">JSON</Str><Int sr="arg3" val="0"/></Action>""",
            "Get Battery Info" to
                """<Action sr="act0" ve="7"><code>424</code>${relevantVariables("%bi_level01. LevelThe current battery level, from 0 to scale", "%bi_scale02. ScaleThe maximum battery level")}</Action>""",
            "Test Net" to """<Action sr="act1" ve="7"><code>341</code><Int sr="arg0" val="0"/><Str sr="arg1" ve="3"/><Str sr="arg2" ve="3">%connectiontype</Str></Action>""",
            "Test Display" to """<Action sr="act1" ve="7"><code>348</code><Int sr="arg0" val="5"/><Str sr="arg1" ve="3"/><Str sr="arg2" ve="3">%locked</Str></Action>""",
        ).associate { (name, action) -> name to importNote(action) }
            .forEach { (name, note) ->
                assertTrue(note, "Tasker's $name" in note)
                assertTrue(note, if (name == "Set Variable Structure Type") "no such setting" in note else "no step that does" in note)
                assertEquals(note, name == "Get Battery Info", "%BATT" in note)
            }
    }

    @Test
    fun screenWallpaperAndVolumeStepsPointAtTheClosestStep() {
        val turnOff = importNote("""<Action sr="act0" ve="7"><code>248</code><Int sr="arg0" val="1"/><Int sr="arg1" val="1"/></Action>""")
        val wallpaper = importNote(
            """<Action sr="act14" ve="7"><code>109</code><Int sr="arg0" val="0"/><Str sr="arg1" ve="3">%Wallpaper1</Str><Int sr="arg2" val="0"/><Int sr="arg3" val="0"/></Action>""",
        )
        val btVolume = importNote("""<Action sr="act1" ve="7"><code>311</code><Int sr="arg0" val="7"/><Int sr="arg1" val="0"/><Int sr="arg2" val="0"/></Action>""")

        assertTrue(turnOff, "Tasker's Turn Off" in turnOff && "screen off step" in turnOff && "lock step" in turnOff)
        assertTrue(wallpaper, "Tasker's Set Wallpaper" in wallpaper && "Scale and Crop" in wallpaper && "wallpaper step" in wallpaper)
        assertTrue(btVolume, "Tasker's BT Voice Volume" in btVolume && "no Bluetooth call stream" in btVolume)
    }

    @Test
    fun widgetTileAndPanelStepsSayWhatIsMissing() {
        val widgetIcon = importNote(
            """<Action sr="act3" ve="7"><code>152</code><Str sr="arg0" ve="3">Tog Shakelock</Str><Img sr="arg1" ve="2"><nme>hd_device_access_screen_rotation</nme></Img></Action>""",
        )
        val tileSetup = importNote(
            """<Action sr="act3" ve="7"><code>162</code><Int sr="arg0" val="1"/><Str sr="arg1" ve="3">Simple Auto Brightness</Str><Int sr="arg2" val="0"/>""" +
                """<ConditionList sr="if"><Condition sr="c0" ve="3"><lhs>%app_name</lhs><op>3</op><rhs>*dinglisch*</rhs></Condition></ConditionList></Action>""",
        )
        val tileRemove = importNote("""<Action sr="act5" ve="7"><code>158</code><Str sr="arg0" lb="" virt="0">KeepAlive</Str><Int sr="arg1" val="0"/></Action>""")
        val panel = importNote("""<Action sr="act14" ve="7"><code>219</code><se>false</se></Action>""")
        val appSettings = importNote("""<Action sr="act3" ve="7"><code>216</code><Str sr="arg0" ve="3">%pkg</Str></Action>""")
        val zoom = importNote("""<Action sr="act2" ve="7"><code>740</code><Str sr="arg0" ve="3">tasker任务.w / 手动记录番茄钟</Str><Str sr="arg1" ve="3">番茄钟</Str></Action>""")

        assertTrue(widgetIcon, "Tasker's Set Widget Icon" in widgetIcon && "changes a widget" in widgetIcon)
        assertTrue(tileSetup, "Tasker's Set up Quick Setting Tile" in tileSetup && "from the tile in Quick Settings" in tileSetup)
        assertTrue(tileRemove, "Tasker's Quick Setting Remove" in tileRemove && "no step that does" in tileRemove)
        assertTrue(panel, "Tasker's Quick Settings pulls down" in panel)
        assertTrue(appSettings, "Tasker's App Settings" in appSettings && "app list" in appSettings && "info screen" in appSettings)
        assertTrue(zoom, "Tasker's Zoom Text" in zoom && "Zoom app" in zoom)
    }

    @Test
    fun imageShortcutAndContactStepsSayWhatIsMissing() {
        val save = importNote("""<Action sr="act6" ve="7"><code>187</code><Str sr="arg0" ve="3">%webp</Str><Int sr="arg1" val="85"/><Int sr="arg2" val="1"/></Action>""")
        val load = importNote("""<Action sr="act3" ve="7"><code>188</code><Img sr="arg0" ve="2"><var>%png</var></Img><Int sr="arg1"/><Int sr="arg2" val="0"/></Action>""")
        val shortcut = importNote(
            """<Action sr="act1" ve="7"><code>370</code>${relevantVariables()}""" +
                """<Str sr="arg1" ve="3">#Intent;action=com.google.android.apps.tasks.NewTask;component=com.google.android.apps.tasks/.common.TrampolineActivity;B.from-launcher-shortcut=true;end</Str></Action>""",
        )
        val contact = importNote(
            """<Action sr="act2" ve="7"><code>381</code><se>false</se>${relevantVariables()}""" +
                """<Str sr="arg1" ve="3">%par1</Str><Str sr="arg2" ve="3">vnd.android.cursor.item/vnd.com.whatsapp.profile</Str><Str sr="arg3" ve="3">%par2</Str><Int sr="arg4" val="1"/></Action>""",
        )

        assertTrue(save, "Tasker's Save Image" in save && "no steps that edit images" in save)
        assertTrue(load, "Tasker's Load Image" in load && "no steps that edit images" in load)
        assertTrue(shortcut, "Tasker's Shortcut" in shortcut && "The intent step" in shortcut)
        assertTrue(contact, "Tasker's Contact Via App" in contact && "no step that does" in contact)
    }
}
