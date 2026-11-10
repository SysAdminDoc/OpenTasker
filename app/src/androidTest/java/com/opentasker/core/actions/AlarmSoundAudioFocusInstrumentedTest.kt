package com.opentasker.core.actions

import android.app.Application
import android.media.AudioFocusRequest
import android.media.AudioManager
import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.opentasker.core.engine.ActionContext
import com.opentasker.core.engine.ActionResult
import com.opentasker.core.engine.VariableStore
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Android 17 only lets alarm audio play in the background once the audio server has refreshed its
 * record of the exact-alarm grant, and a MediaPlayer never does that on the app's behalf, so a
 * background sound was muted while the action reported success. The focus request is what refreshes
 * it, so this holds focus and watches the action take it away and give it back.
 */
class AlarmSoundAudioFocusInstrumentedTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun theAlarmStreamTakesAudioFocusWhilePlayingAndHandsItBack() = runBlocking {
        val audio = app.getSystemService(AudioManager::class.java)
        val changes = LinkedBlockingQueue<Int>()
        val holder = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setWillPauseWhenDucked(true)
            .setOnAudioFocusChangeListener { changes.add(it) }
            .build()
        val sound = File(app.cacheDir, "alarm-focus-test.wav").apply { writeBytes(shortSilentWav()) }
        ActivityScenario.launch(ComponentActivity::class.java).use {
            assertEquals(AudioManager.AUDIOFOCUS_REQUEST_GRANTED, audio.requestAudioFocus(holder))
            try {
                val result = PlaySoundAction().run(
                    ActionContext(app, VariableStore()),
                    mapOf("path" to sound.absolutePath, "stream" to "alarm"),
                )
                assertEquals(ActionResult.Success, result)

                val lost = changes.poll(5, TimeUnit.SECONDS)
                assertNotNull("the alarm stream never asked for audio focus", lost)
                assertTrue("expected a focus loss, got $lost", requireNotNull(lost) < 0)
                val regained = changes.poll(5, TimeUnit.SECONDS)
                assertEquals("focus was never handed back", AudioManager.AUDIOFOCUS_GAIN, regained)
            } finally {
                audio.abandonAudioFocusRequest(holder)
                sound.delete()
            }
        }
    }

    @Test
    fun aSoundWithAHashInItsFileNameStillPlays() = runBlocking {
        // A path pasted after "file://" ended at the '#', which starts a URI fragment.
        val sound = File(app.cacheDir, "chime#1 100%.wav").apply { writeBytes(shortSilentWav()) }
        ActivityScenario.launch(ComponentActivity::class.java).use {
            try {
                val result = PlaySoundAction().run(ActionContext(app, VariableStore()), mapOf("path" to sound.absolutePath))
                assertEquals(ActionResult.Success, result)
            } finally {
                sound.delete()
            }
        }
    }

    private fun shortSilentWav(): ByteArray {
        val sampleRate = 8_000
        val dataBytes = sampleRate / 2 * 2
        return ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray())
            putInt(36 + dataBytes)
            put("WAVEfmt ".toByteArray())
            putInt(16)
            putShort(1)
            putShort(1)
            putInt(sampleRate)
            putInt(sampleRate * 2)
            putShort(2)
            putShort(16)
            put("data".toByteArray())
            putInt(dataBytes)
        }.array()
    }
}
