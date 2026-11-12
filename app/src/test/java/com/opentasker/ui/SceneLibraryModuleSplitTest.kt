package com.opentasker.ui

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SceneLibraryModuleSplitTest {
    private val screensSourceRoot: Path = listOf(
        Path.of("src/main/java/com/opentasker/ui/screens"),
        Path.of("app/src/main/java/com/opentasker/ui/screens"),
    ).first(Files::exists)

    @Test
    fun sceneLibraryShellOnlyCoordinatesScreenStateAndModules() {
        val shellSource = screensSourceRoot.resolve("SceneLibraryScreen.kt").readText()

        listOf(
            "detectDragGestures",
            "AlertDialog",
            "SceneCanvasProjector",
            "SceneValidator.validate",
            "Settings.canDrawOverlays",
        ).forEach { responsibilityMarker ->
            assertFalse(
                "SceneLibraryScreen.kt should delegate $responsibilityMarker",
                shellSource.contains(responsibilityMarker),
            )
        }
        assertTrue("Scene library shell should stay under 250 lines", Files.readAllLines(screensSourceRoot.resolve("SceneLibraryScreen.kt")).size < 250)
    }

    @Test
    fun sceneEditorResponsibilitiesStayInFocusedModules() {
        val expectedDeclarations = mapOf(
            "SceneLibraryCards.kt" to listOf(
                "internal fun SceneEmptyState",
                "internal fun SceneOverviewCard",
                "internal fun SceneCard",
                "private fun SceneElementRow",
            ),
            "SceneEditorCanvas.kt" to listOf(
                "internal fun ScenePreviewBox",
                "private fun SceneCanvasElement",
            ),
            "SceneEditorDialogs.kt" to listOf(
                "internal fun SceneElementEditorDialog",
                "internal fun SceneEditorDialog",
            ),
            "SceneOverlayControls.kt" to listOf(
                "internal fun SceneOverlayReadinessPill",
                "internal fun SceneOverlayButton",
            ),
        )

        expectedDeclarations.forEach { (fileName, declarations) ->
            val module = screensSourceRoot.resolve(fileName)
            assertTrue("Missing extracted scene-editor module: $fileName", Files.exists(module))
            val source = module.readText()
            declarations.forEach { declaration ->
                assertTrue("$fileName should own $declaration", source.contains(declaration))
            }
        }
    }
}
