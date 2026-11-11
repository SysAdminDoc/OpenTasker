package com.opentasker.core.actions

import android.app.Application
import android.media.AudioAttributes
import android.media.AudioManager
import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.opentasker.core.engine.ActionContext
import com.opentasker.core.engine.ActionResult
import com.opentasker.core.engine.VariableStore
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A static AudioTrack only reaches STATE_INITIALIZED once its samples are written, so a beep that
 * checked the state first failed on every phone. Amplitude 1 keeps these near silent.
 */
class BeepActionInstrumentedTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun aBeepPlaysForItsDurationAndSucceedsOnBothStreams() = runBlocking {
        ActivityScenario.launch(ComponentActivity::class.java).use {
            listOf("music", "alarm").forEach { stream ->
                val started = System.nanoTime()
                val result = BeepAction().run(
                    ActionContext(app, VariableStore()),
                    mapOf("frequency" to "440", "duration" to "300", "amplitude" to "1", "stream" to stream),
                )
                val elapsedMs = (System.nanoTime() - started) / 1_000_000

                assertEquals(stream, ActionResult.Success, result)
                assertTrue("$stream returned after $elapsedMs ms", elapsedMs >= 300)
            }
        }
    }

    @Test
    fun aBeepSoundsOnTheStreamItWasGiven() = runBlocking {
        val audio = app.getSystemService(AudioManager::class.java)
        ActivityScenario.launch(ComponentActivity::class.java).use {
            listOf("music" to AudioAttributes.USAGE_MEDIA, "alarm" to AudioAttributes.USAGE_ALARM).forEach { (stream, usage) ->
                val beep = async {
                    BeepAction().run(
                        ActionContext(app, VariableStore()),
                        mapOf("frequency" to "440", "duration" to "800", "amplitude" to "1", "stream" to stream),
                    )
                }
                delay(300)
                // This list holds idle players too, so it shows the track's stream, not that it plays.
                val onItsStream = audio.activePlaybackConfigurations.any {
                    it.audioAttributes.usage == usage && it.audioAttributes.contentType == AudioAttributes.CONTENT_TYPE_SONIFICATION
                }
                // Music is the one stream with a public "is it sounding" check.
                val musicSounding = audio.isMusicActive

                assertEquals(stream, ActionResult.Success, beep.await())
                assertTrue("no $stream track 300 ms into the beep", onItsStream)
                if (stream == "music") assertTrue("the music stream was silent 300 ms into the beep", musicSounding)
            }
        }
    }

    @Test
    fun stoppingABeepCancelsItInsteadOfReportingAFailure() = runBlocking {
        ActivityScenario.launch(ComponentActivity::class.java).use {
            // Once it times out, withTimeoutOrNull returns null even if the block then returns, so the result is caught inside it.
            var result: ActionResult? = null
            val started = System.nanoTime()
            withTimeoutOrNull(300) {
                result = BeepAction().run(ActionContext(app, VariableStore()), mapOf("frequency" to "440", "duration" to "5000", "amplitude" to "1"))
            }
            val elapsedMs = (System.nanoTime() - started) / 1_000_000

            assertNull(result)
            assertTrue("stopped after $elapsedMs ms", elapsedMs < 2_000)
        }
    }
}
