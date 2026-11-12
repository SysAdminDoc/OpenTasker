package com.opentasker.core.transfer

import android.content.ContextWrapper
import android.media.AudioAttributes
import com.opentasker.core.actions.BeepAction
import com.opentasker.core.actions.registerActionMetadata
import com.opentasker.core.engine.ActionContext
import com.opentasker.core.engine.ActionResult
import com.opentasker.core.engine.VariableStore
import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.Task
import com.opentasker.core.platform.AndroidAudioHardening
import com.opentasker.core.platform.AudioOutputStream
import com.opentasker.core.platform.AudioUsageEligibility
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Play Ringtone mostly plays on Tasker's Notification stream (5), and Ringer (2) is the other one
 * people pick. Beep, Say, Play Ringtone and Music Play keep both through an import and an export
 * instead of falling back to Music with a note.
 */
class TaskerRingerNotificationStreamTest {

    @Before
    fun setUp() {
        registerActionMetadata()
    }

    private fun imported(xml: String) =
        TaskerXmlImporter.parse(
            rawXml = "<TaskerData><Task sr=\"task1\"><id>1</id><nme>Imported</nme><Action sr=\"act0\">$xml</Action></Task></TaskerData>",
            appVersion = "test",
            importedAtEpochMs = 0L,
        )

    private fun action(xml: String): ActionSpec = imported(xml).bundle.tasks.single().actions.single()

    private fun exported(type: String, vararg args: Pair<String, String>): String =
        TaskerXmlExporter.export(emptyList(), listOf(Task(id = 1, name = "Exported", actions = listOf(ActionSpec(type = type, args = mapOf(*args)))))).xml

    private fun roundTrip(type: String, vararg args: Pair<String, String>): ActionSpec =
        TaskerXmlImporter.parse(exported(type, *args), appVersion = "test", importedAtEpochMs = 0L).bundle.tasks.single().actions.single()

    @Test
    fun eachSoundStepImportsRingerAndNotificationWithoutANote() {
        val steps = mapOf(
            "ringtone" to { stream: Int -> """<code>192</code><Int sr="arg0" val="1"/><Str sr="arg1" ve="3">/a.ogg</Str><Int sr="arg2" val="$stream"/>""" },
            "music play" to { stream: Int -> """<code>445</code><Str sr="arg0" ve="3">a.mp3</Str><Int sr="arg3" val="$stream"/>""" },
            "say" to { stream: Int -> """<code>559</code><Str sr="arg0" ve="3">hi</Str><Str sr="arg1" ve="3"/><Int sr="arg2" val="$stream"/>""" },
            "beep" to { stream: Int -> """<code>171</code><Int sr="arg0" val="1852"/><Int sr="arg1" val="100"/><Int sr="arg3" val="$stream"/>""" },
        )
        steps.forEach { (name, xml) ->
            assertEquals(name, "ring", action(xml(2)).args["stream"])
            assertEquals(name, "notification", action(xml(5)).args["stream"])
            val notes = imported(xml(2)).lossyWarnings + imported(xml(5)).lossyWarnings
            assertTrue("$name: $notes", notes.none { "stream" in it })
        }
    }

    @Test
    fun aStreamStillMissingHereKeepsItsNote() {
        val system = """<code>192</code><Int sr="arg0" val="1"/><Str sr="arg1" ve="3">/a.ogg</Str><Int sr="arg2" val="1"/>"""

        assertEquals(null, action(system).args["stream"])
        assertTrue(imported(system).lossyWarnings.toString(), imported(system).lossyWarnings.any { "stream 1 (System)" in it })
    }

    @Test
    fun exportsWriteTaskersStreamNumber() {
        assertTrue(exported("sound.play", "path" to "a.mp3", "stream" to "notification").contains("""<Int sr="arg3" val="5"/>"""))
        assertTrue(exported("sound.play", "path" to "a.mp3", "stream" to "ring").contains("""<Int sr="arg3" val="2"/>"""))
        assertTrue(exported("tts.speak", "text" to "hi", "stream" to "notification").contains("""<Int sr="arg2" val="5"/>"""))
        assertTrue(exported("sound.beep", "frequency" to "440", "duration" to "100", "stream" to "ring").contains("""<Int sr="arg3" val="2"/>"""))
        assertTrue(exported("sound.beep", "frequency" to "440", "duration" to "100", "stream" to " Notification ").contains("""<Int sr="arg3" val="5"/>"""))
    }

    @Test
    fun ringerAndNotificationComeBackFromTheirOwnExport() {
        listOf("ring", "notification").forEach { stream ->
            assertEquals(stream, roundTrip("sound.play", "path" to "a.mp3", "stream" to stream).args["stream"])
            assertEquals(stream, roundTrip("tts.speak", "text" to "hi", "stream" to stream).args["stream"])
            assertEquals(stream, roundTrip("sound.beep", "frequency" to "440", "duration" to "100", "stream" to stream).args["stream"])
        }
    }

    @Test
    fun theStepsPlayOnTheMatchingAndroidUsage() {
        assertEquals(AudioOutputStream.RING, AudioOutputStream.parse(" Ring "))
        assertEquals(AudioOutputStream.NOTIFICATION, AudioOutputStream.parse("notification"))
        assertEquals(AudioAttributes.USAGE_NOTIFICATION_RINGTONE, AudioOutputStream.RING.attributeUsage)
        assertEquals(AudioAttributes.USAGE_NOTIFICATION, AudioOutputStream.NOTIFICATION.attributeUsage)
        assertEquals(AudioAttributes.USAGE_MEDIA, AudioOutputStream.MUSIC.attributeUsage)
        assertEquals(AudioAttributes.USAGE_ALARM, AudioOutputStream.ALARM.attributeUsage)
        // Android 17 only lets alarm audio through from the background, so these get the Alarm hint.
        assertEquals(AudioUsageEligibility.GENERAL, AudioOutputStream.RING.usage)
        assertEquals(AudioUsageEligibility.GENERAL, AudioOutputStream.NOTIFICATION.usage)
    }

    @Test
    fun aBackgroundBeepOnRingerOrNotificationPointsAtAlarmOnAndroid17() = runBlocking {
        AndroidAudioHardening.sdkIntOverrideForTests = 37
        try {
            listOf("ring", "notification").forEach { stream ->
                val result = BeepAction().run(
                    ActionContext(app = ContextWrapper(null), variables = VariableStore()),
                    mapOf("frequency" to "440", "duration" to "100", "stream" to stream),
                )
                assertTrue("$stream: $result", result is ActionResult.Failure)
                val message = (result as ActionResult.Failure).message
                assertTrue(message, "set this action's stream to Alarm" in message)
            }
        } finally {
            AndroidAudioHardening.sdkIntOverrideForTests = null
        }
    }
}
