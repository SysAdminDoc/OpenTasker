package com.opentasker.core.actions

import android.content.ContextWrapper
import com.opentasker.core.capabilities.AutomationLint
import com.opentasker.core.engine.ActionContext
import com.opentasker.core.engine.ActionResult
import com.opentasker.core.engine.VariableStore
import com.opentasker.core.model.ContextType
import com.opentasker.core.templates.ProfileTemplateCatalog
import com.opentasker.ui.screens.contextHasInvalidValues
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class WallpaperActionsTest {
    @Before
    fun setUp() {
        registerActionMetadata()
    }

    @Test
    fun theScreenChoiceMapsToWallpaperManagerFlags() {
        // FLAG_SYSTEM is 1 and FLAG_LOCK is 2 in WallpaperManager.
        listOf(null, "", " ", "both", "BOTH").forEach { assertEquals(it, 3, SetWallpaperAction.wallpaperFlags(it)) }
        assertEquals(1, SetWallpaperAction.wallpaperFlags("home"))
        assertEquals(2, SetWallpaperAction.wallpaperFlags(" Lock "))
        assertNull(SetWallpaperAction.wallpaperFlags("system"))
    }

    @Test
    fun onlyAnImageOnTheDeviceIsAccepted() {
        listOf(
            "/sdcard/Pictures/day.jpg",
            "file:///sdcard/Pictures/day.jpg",
            "content://com.android.providers.media.documents/document/image%3A12",
            "CONTENT://media/external/images/media/12",
            "android.resource://com.opentasker.app/drawable/wallpaper",
        ).forEach { assertTrue(it, SetWallpaperAction.isLocalImage(it)) }
        listOf("https://example.com/day.jpg", "ftp://example.com/day.jpg").forEach {
            assertFalse(it, SetWallpaperAction.isLocalImage(it))
        }
    }

    @Test
    fun badArgumentsFailBeforeTheWallpaperIsTouched() = runBlocking {
        val ctx = ActionContext(ContextWrapper(null), VariableStore())
        val action = SetWallpaperAction()

        assertEquals("missing path", (action.run(ctx, mapOf("path" to " ")) as ActionResult.Failure).message)
        assertTrue(
            (action.run(ctx, mapOf("path" to "/sdcard/a.jpg", "target" to "sky")) as ActionResult.Failure).message
                .startsWith("invalid target: sky"),
        )
        assertTrue(
            (action.run(ctx, mapOf("path" to "https://example.com/a.jpg")) as ActionResult.Failure).message
                .contains("not https://example.com/a.jpg"),
        )
    }

    @Test
    fun theEditorAsksForAnImageAndOffersTheThreeScreens() {
        val metadata = ActionMetadataRegistry.get("wallpaper.set")!!
        val path = metadata.fields.first { it.key == "path" }

        assertTrue(path.required)
        assertEquals(listOf("image/*"), path.fileRule?.pickerMimeTypes)
        assertEquals(
            listOf("both", "home", "lock"),
            metadata.fields.first { it.key == "target" }.options.map { it.value },
        )
        assertEquals(ActionFieldPolicy.Error.REQUIRED, ActionFieldPolicy.validateForm(metadata, mapOf("path" to ""))["path"]?.error)
        assertTrue(ActionFieldPolicy.validateForm(metadata, mapOf("path" to "content://media/1", "target" to "lock")).isEmpty())
    }

    @Test
    fun theDayAndNightTemplatesInstallProfilesThatValidate() {
        listOf("wallpaper-at-sunrise" to "sunrise", "wallpaper-at-sunset" to "sunset").forEach { (id, event) ->
            val applied = ProfileTemplateCatalog.get(id)!!.instantiate(mapOf("latitude" to "51.5", "longitude" to "-0.12"))
            val task = applied.task.copy(id = 1)
            val profile = applied.profile.copy(id = 1, enterTaskId = 1, enabled = true)
            val context = profile.contexts.single()

            assertEquals(ContextType.EVENT, context.type)
            assertEquals(event, context.config["event"])
            assertEquals("51.5", context.config["latitude"])
            assertEquals("-0.12", context.config["longitude"])
            assertFalse(id, contextHasInvalidValues(context.type, context.config))
            task.actions.forEach { action ->
                assertEquals("wallpaper.set", action.type)
                assertEquals(
                    "$id ${action.args}",
                    emptyMap<String, ActionFieldPolicy.Issue>(),
                    ActionFieldPolicy.validateForm(ActionMetadataRegistry.get(action.type)!!, action.args),
                )
            }
            assertEquals(id, emptyList<Any>(), AutomationLint.analyze(profile, listOf(task)).blockingFindings)
        }
    }
}
