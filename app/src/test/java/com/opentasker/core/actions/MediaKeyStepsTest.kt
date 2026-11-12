package com.opentasker.core.actions

import android.content.ContextWrapper
import com.opentasker.app.R
import com.opentasker.core.engine.Action
import com.opentasker.core.engine.ActionCategory
import com.opentasker.core.engine.ActionContext
import com.opentasker.core.engine.ActionResult
import com.opentasker.core.engine.ActionRetrySafety
import com.opentasker.core.engine.VariableStore
import com.opentasker.core.platform.AndroidAudioHardening
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Resume, Play/Pause, Rewind and Fast Forward send a media key to the app that's playing or played
 * last, the same way Pause and Next Track do. Resume is safe to retry because a second Play key changes nothing. The
 * other three aren't, since a second press flips playback back or seeks again.
 */
class MediaKeyStepsTest {
    private val noServicesContext = object : ContextWrapper(null) {
        override fun getSystemService(name: String): Any? = null
    }

    private val steps = mapOf<String, () -> Action>(
        "sound.resume" to ::ResumeSoundAction,
        "sound.toggle" to ::TogglePlaybackAction,
        "track.rewind" to ::RewindTrackAction,
        "track.fastforward" to ::FastForwardTrackAction,
    )

    @Before
    fun setUp() {
        registerActionMetadata()
    }

    @After
    fun clearHardeningOverride() {
        AndroidAudioHardening.sdkIntOverrideForTests = null
    }

    @Test
    fun newMediaKeysAreMediaStepsWithThePauseStepsCapability() {
        val pause = ActionCatalog.require("sound.pause")
        steps.keys.forEach { id ->
            val definition = ActionCatalog.require(id)
            assertEquals(id, ActionCategory.MEDIA, definition.category)
            assertEquals(id, R.string.catalog_category_media, definition.metadata.categoryRes)
            assertTrue(id, definition.metadata.fields.isEmpty())
            assertEquals(id, pause.capability().level, definition.capability().level)
            assertEquals(id, pause.capability().reasonRes, definition.capability().reasonRes)
        }
    }

    @Test
    fun onlyResumeIsSafeToRetry() {
        assertEquals(ActionRetrySafety.IDEMPOTENT, ActionCatalog.require("sound.resume").retrySafety)
        listOf("sound.toggle", "track.rewind", "track.fastforward").forEach { id ->
            assertEquals(id, ActionRetrySafety.NEVER, ActionCatalog.require(id).retrySafety)
        }
    }

    @Test
    fun newMediaKeysAreRefusedInTheBackgroundOnAndroid17() = runBlocking {
        AndroidAudioHardening.sdkIntOverrideForTests = 37

        steps.forEach { (id, step) ->
            val result = step().run(ctx(), emptyMap())
            assertTrue(id, result is ActionResult.Failure)
            assertTrue(id, (result as ActionResult.Failure).message.contains("blocked media-key dispatch before any audio side effect"))
        }
    }

    @Test
    fun newMediaKeysReachTheAudioServiceBeforeAndroid17() = runBlocking {
        AndroidAudioHardening.sdkIntOverrideForTests = 36

        steps.forEach { (id, step) ->
            val result = step().run(ctx(), emptyMap())
            assertTrue(id, result is ActionResult.Failure)
            assertTrue(id, (result as ActionResult.Failure).message.contains("audio service not available"))
        }
    }

    private fun ctx() = ActionContext(app = noServicesContext, variables = VariableStore())
}
