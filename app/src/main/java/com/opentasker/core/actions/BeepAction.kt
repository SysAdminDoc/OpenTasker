package com.opentasker.core.actions

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.opentasker.core.engine.ActionContext
import com.opentasker.core.engine.ActionResult
import com.opentasker.core.platform.AndroidAudioHardening
import com.opentasker.core.platform.AudioOutputStream
import kotlin.math.PI
import kotlin.math.sin
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Tasker's Beep tone: a sine wave whose first and last twentieth fade in and out, so it starts and
 * stops without a click. The peak is the amplitude percent times 327, as in Tasker.
 */
internal object BeepTone {
    const val SAMPLE_RATE = 22_050
    const val MAX_FREQUENCY_HZ = 10_000.0
    const val MAX_DURATION_MS = 10_000
    private const val PEAK_PER_PERCENT = 327

    fun samples(frequencyHz: Double, durationMs: Int, amplitudePercent: Int): ShortArray {
        val count = (durationMs.toLong() * SAMPLE_RATE / 1_000).toInt()
        val peak = amplitudePercent * PEAK_PER_PERCENT.toDouble()
        val step = 2 * PI * frequencyHz / SAMPLE_RATE
        val fade = count / 20
        return ShortArray(count) { i ->
            val envelope = when {
                i < fade -> i.toDouble() / fade
                i >= count - fade -> (count - i).toDouble() / fade
                else -> 1.0
            }
            (sin(i * step) * peak * envelope).toInt().toShort()
        }
    }
}

/**
 * Plays a tone. Args: `frequency` in Hz, `duration` in ms, `amplitude` 0-100 (50 when blank),
 * `stream` music or alarm. The step ends once the tone has played, as Tasker's Beep does.
 */
class BeepAction : DeclaredAction(ActionCatalog.require("sound.beep")) {

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val frequency = args["frequency"]?.trim()?.toDoubleOrNull()?.takeIf { it in 0.0..BeepTone.MAX_FREQUENCY_HZ }
            ?: return ActionResult.Failure("frequency must be 0 to 10000 Hz, not '${args["frequency"].orEmpty()}'")
        val duration = args["duration"]?.trim()?.toIntOrNull()?.takeIf { it in 1..BeepTone.MAX_DURATION_MS }
            ?: return ActionResult.Failure("duration must be 1 to 10000 ms, not '${args["duration"].orEmpty()}'")
        val amplitude = args["amplitude"].orEmpty().trim().ifEmpty { "50" }.toIntOrNull()?.takeIf { it in 0..100 }
            ?: return ActionResult.Failure("amplitude must be 0 to 100, not '${args["amplitude"].orEmpty()}'")
        val stream = AudioOutputStream.parse(args["stream"])
            ?: return ActionResult.Failure("invalid stream: ${args["stream"]}")
        AndroidAudioHardening.failureIfIneligible(
            ctx,
            "beep playback",
            stream.usage,
            offersAlarmStream = stream == AudioOutputStream.MUSIC,
        )?.let { return it }

        val samples = BeepTone.samples(frequency, duration, amplitude)
        val attributes = if (stream == AudioOutputStream.ALARM) {
            alarmAudioAttributes(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        } else {
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        }
        val track = try {
            AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(BeepTone.SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(samples.size * 2)
                .build()
        } catch (ex: Exception) {
            return ActionResult.Failure("could not open an audio track: ${ex.message}", ex)
        }
        try {
            if (track.state != AudioTrack.STATE_INITIALIZED || track.write(samples, 0, samples.size) != samples.size) {
                return ActionResult.Failure("could not load the tone into an audio track")
            }
            track.play()
            ctx.logger("Beep: $frequency Hz for $duration ms")
            delay(duration.toLong())
            // A static track's head stops on its last frame. A busy mixer can start it a little late.
            withTimeoutOrNull(500) {
                while (track.playbackHeadPosition < samples.size) delay(10)
            }
            return ActionResult.Success
        } catch (ex: IllegalStateException) {
            return ActionResult.Failure("the audio track would not play: ${ex.message}", ex)
        } finally {
            track.release()
        }
    }
}
