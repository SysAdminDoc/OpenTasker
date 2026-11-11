package com.opentasker.core.actions

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.opentasker.core.engine.ActionContext
import com.opentasker.core.engine.ActionResult
import com.opentasker.core.engine.VariableStore
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
    fun stoppingABeepCancelsItInsteadOfReportingAFailure() = runBlocking {
        ActivityScenario.launch(ComponentActivity::class.java).use {
            val started = System.nanoTime()
            val result = withTimeoutOrNull(300) {
                BeepAction().run(ActionContext(app, VariableStore()), mapOf("frequency" to "440", "duration" to "5000", "amplitude" to "1"))
            }
            val elapsedMs = (System.nanoTime() - started) / 1_000_000

            assertNull(result)
            assertTrue("stopped after $elapsedMs ms", elapsedMs < 2_000)
        }
    }
}
