package com.opentasker.core.actions

import android.app.WallpaperManager
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.opentasker.core.engine.ActionContext
import com.opentasker.core.engine.ActionResult
import com.opentasker.core.engine.VariableStore
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Set Wallpaper replaces the real wallpaper, which can't be put back afterwards, so this runs on
 * an emulator only (A-393).
 */
@RunWith(AndroidJUnit4::class)
class SetWallpaperInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val manager = WallpaperManager.getInstance(context)
    private val image = File(context.cacheDir, "wallpaper-test.png")
    private val ctx = ActionContext(context, VariableStore())

    // A '#' starts a URI fragment, so a path pasted into "file://" lost everything after it.
    private val oddlyNamed = File(context.cacheDir, "wall#paper 100%.png")

    @Before
    fun setUp() {
        assumeTrue("changes the wallpaper, so emulator only", Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish")
        listOf(image, oddlyNamed).forEach { file ->
            InstrumentationRegistry.getInstrumentation().context.assets.open("wallpaper-test.png").use { input ->
                file.outputStream().use { input.copyTo(it) }
            }
        }
    }

    @After
    fun tearDown() {
        image.delete()
        oddlyNamed.delete()
    }

    @Test
    fun aBundledImageBecomesTheHomeScreenWallpaper() = runBlocking {
        val before = manager.getWallpaperId(WallpaperManager.FLAG_SYSTEM)

        val result = SetWallpaperAction().run(ctx, mapOf("path" to image.absolutePath, "target" to "home"))

        assertEquals(ActionResult.Success, result)
        assertNotEquals(before, manager.getWallpaperId(WallpaperManager.FLAG_SYSTEM))
    }

    @Test
    fun theLockScreenCanBeSetOnItsOwn() = runBlocking {
        val before = manager.getWallpaperId(WallpaperManager.FLAG_LOCK)

        val result = SetWallpaperAction().run(ctx, mapOf("path" to "file://${image.absolutePath}", "target" to "lock"))

        assertEquals(ActionResult.Success, result)
        val after = manager.getWallpaperId(WallpaperManager.FLAG_LOCK)
        assertTrue("lock wallpaper id $after", after >= 0)
        assertNotEquals(before, after)
    }

    @Test
    fun aFileNameWithAHashOrAnUppercaseSchemeStillOpens() = runBlocking {
        val home = manager.getWallpaperId(WallpaperManager.FLAG_SYSTEM)
        assertEquals(ActionResult.Success, SetWallpaperAction().run(ctx, mapOf("path" to oddlyNamed.absolutePath, "target" to "home")))
        assertNotEquals(home, manager.getWallpaperId(WallpaperManager.FLAG_SYSTEM))

        val lock = manager.getWallpaperId(WallpaperManager.FLAG_LOCK)
        assertEquals(ActionResult.Success, SetWallpaperAction().run(ctx, mapOf("path" to "FILE://${image.absolutePath}", "target" to "lock")))
        assertNotEquals(lock, manager.getWallpaperId(WallpaperManager.FLAG_LOCK))
    }

    @Test
    fun aMissingImageFailsAndLeavesTheWallpaperAlone() = runBlocking {
        val before = manager.getWallpaperId(WallpaperManager.FLAG_SYSTEM)

        val result = SetWallpaperAction().run(ctx, mapOf("path" to File(context.cacheDir, "gone.png").absolutePath))

        assertTrue("$result", (result as ActionResult.Failure).message.startsWith("could not set the wallpaper"))
        assertEquals(before, manager.getWallpaperId(WallpaperManager.FLAG_SYSTEM))
    }
}
