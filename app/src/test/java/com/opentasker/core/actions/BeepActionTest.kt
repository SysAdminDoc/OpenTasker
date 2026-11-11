package com.opentasker.core.actions

import android.content.ContextWrapper
import com.opentasker.core.engine.ActionContext
import com.opentasker.core.engine.ActionResult
import com.opentasker.core.engine.VariableStore
import com.opentasker.core.platform.AndroidAudioHardening
import kotlin.math.abs
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BeepActionTest {

    @After
    fun clearAndroid17HardeningOverride() {
        AndroidAudioHardening.sdkIntOverrideForTests = null
    }

    @Test
    fun theToneLastsItsDurationAtTheSampleRate() {
        assertEquals(2_205, BeepTone.samples(440.0, 100, 50).size)
        assertEquals(22, BeepTone.samples(440.0, 1, 50).size)
    }

    @Test
    fun theToneHasTheAskedPitch() {
        val samples = BeepTone.samples(441.0, 1_000, 100)
        val middle = samples.copyOfRange(samples.size / 4, samples.size * 3 / 4)
        val crossings = middle.toList().zipWithNext().count { (a, b) -> (a < 0) != (b < 0) }

        // Half a second of 441 Hz crosses zero 441 times.
        assertTrue("$crossings crossings", crossings in 439..443)
    }

    @Test
    fun amplitudeIsTaskersPercentTimes327() {
        val full = BeepTone.samples(1_000.0, 200, 100)
        val peak = full.maxOf { abs(it.toInt()) }
        assertTrue("$peak", peak in 32_000..32_700)
        assertTrue(BeepTone.samples(1_000.0, 200, 0).all { it == 0.toShort() })
        assertTrue(BeepTone.samples(1_000.0, 200, 9).maxOf { abs(it.toInt()) } <= 9 * 327)
    }

    @Test
    fun theFirstAndLastTwentiethFadeSoTheBeepDoesNotClick() {
        val samples = BeepTone.samples(1_000.0, 1_000, 100)
        val fade = samples.size / 20

        assertEquals(0, samples.first().toInt())
        assertTrue(abs(samples.last().toInt()) <= 32_700 / fade + 1)
        assertTrue(samples.take(fade / 4).maxOf { abs(it.toInt()) } <= 32_700 / 4 + 1)
        assertTrue(samples.takeLast(fade / 4).maxOf { abs(it.toInt()) } <= 32_700 / 4 + 1)
    }

    @Test
    fun outOfRangeNumbersFailBeforeAnyAudio() = runBlocking {
        listOf(
            mapOf("frequency" to "20000", "duration" to "100") to "frequency",
            mapOf("frequency" to "%pitch", "duration" to "100") to "frequency",
            mapOf("frequency" to "440", "duration" to "0") to "duration",
            mapOf("frequency" to "440", "duration" to "60000") to "duration",
            mapOf("frequency" to "440", "duration" to "100", "amplitude" to "101") to "amplitude",
            mapOf("frequency" to "440", "duration" to "100", "stream" to "ring") to "stream",
        ).forEach { (args, field) ->
            val result = BeepAction().run(ctx(), args)
            assertTrue("$args: $result", result is ActionResult.Failure && field in result.message)
        }
    }

    @Test
    fun aBeepFromTheBackgroundOnAndroid17IsRefusedBeforeItPlays() = runBlocking {
        AndroidAudioHardening.sdkIntOverrideForTests = 37

        val result = BeepAction().run(ctx(), mapOf("frequency" to "440", "duration" to "100"))

        assertTrue(result.toString(), result is ActionResult.Failure)
        val message = (result as ActionResult.Failure).message
        assertTrue(message, "blocked beep playback before any audio side effect" in message)
        assertTrue(message, "set this action's stream to Alarm" in message)
    }

    private fun ctx() = ActionContext(app = ContextWrapper(null), variables = VariableStore())
}
