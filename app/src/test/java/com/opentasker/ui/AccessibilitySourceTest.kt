package com.opentasker.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText

class AccessibilitySourceTest {
    private val mainSourceRoot: Path = listOf(
        Path.of("src/main/java"),
        Path.of("app/src/main/java"),
    ).first(Files::exists)
    private val uiSourceRoot: Path = mainSourceRoot.resolve("com/opentasker/ui")

    @Test
    fun uiSourceDoesNotShipNullContentDescriptions() {
        val offenders = kotlinFiles()
            .filter { it.readText().contains("contentDescription = null") }
            .map { uiSourceRoot.relativize(it).toString() }

        assertTrue("Null content descriptions found in $offenders", offenders.isEmpty())
    }

    @Test
    fun toggleRowsExposeSwitchRoleAndStateDescriptions() {
        val source = uiSourceRoot.resolve("screens/ActiveAutomationUi.kt").readText()

        val toggleableCount = Regex("""\.toggleable\s*\(""").findAll(source).count()
        val switchRoleCount = Regex("""role\s*=\s*Role\.Switch""").findAll(source).count()
        val stateDescriptionCount = Regex("""stateDescription\s*=""").findAll(source).count()

        assertEquals("Every toggleable row must expose Role.Switch", toggleableCount, switchRoleCount)
        assertTrue(
            "Every toggleable row must expose a stateDescription",
            stateDescriptionCount >= toggleableCount,
        )
    }

    @Test
    fun flowAndSceneScreensKeepScreenReaderAlternatives() {
        val flowSource = uiSourceRoot.resolve("screens/AutomationFlowScreen.kt").readText()
        assertTrue(flowSource.contains("graph.accessibilitySummary()"))
        assertTrue(flowSource.contains("node.accessibilityLabel()"))
        assertTrue("Flow canvas must keep bounded zoom/pan interaction", flowSource.contains("rememberTransformableState"))
        assertTrue("Flow graph must expose missing-reference repair nodes", flowSource.contains("AutomationFlowNodeKind.MISSING"))
        assertTrue("Flow graph must keep picker-backed context add", flowSource.contains("onAddContext"))
        assertTrue("Flow graph must keep picker-backed action add", flowSource.contains("onAddAction"))

        val sceneSource = uiSourceRoot.resolve("screens/SceneLibraryCards.kt").readText()
        val requiredNudgeLabels = listOf(
            "R.string.scenes_move_left_content_description",
            "R.string.scenes_move_up_content_description",
            "R.string.scenes_move_down_content_description",
            "R.string.scenes_move_right_content_description",
        )
        val missingLabels = requiredNudgeLabels.filterNot(sceneSource::contains)

        assertFalse("Missing scene nudge accessibility labels: $missingLabels", missingLabels.isNotEmpty())
    }

    @Test
    fun sceneCanvasElementsExposeGeometryAndAdjustmentActions() {
        val source = uiSourceRoot.resolve("screens/SceneEditorCanvas.kt").readText()
        val requiredMarkers = listOf(
            ".semantics(mergeDescendants = true)",
            "contentDescription = elementDescription",
            "stateDescription = selectionState",
            "role = Role.Button",
            "customActions = elementAccessibilityActions",
            "scenes_canvas_element_description",
            "scenes_canvas_element_with_label",
            "sceneElementTypeLabel(element.type)",
            "scenes_resize_handle_content_description",
            "scenes_resize_narrower_content_description",
            "scenes_resize_wider_content_description",
            "scenes_resize_shorter_content_description",
            "scenes_resize_taller_content_description",
            "private const val MIN_ELEMENT_SIZE = 12",
        )
        val missingMarkers = requiredMarkers.filterNot(source::contains)

        assertTrue("Missing scene canvas accessibility contracts: $missingMarkers", missingMarkers.isEmpty())
    }

    @Test
    fun sceneOverlayKeepsTouchAndNonTouchMovementContracts() {
        val source = mainSourceRoot.resolve("com/opentasker/core/scenes/SceneOverlayService.kt").readText()
        val requiredMarkers = listOf(
            "HEADER_HEIGHT_DP = 48",
            "CLOSE_BUTTON_SIZE_DP = 48",
            "scene_overlay_drag_handle_content_description",
            "scene_overlay_close_content_description",
            "scene_overlay_move_left_action",
            "scene_overlay_move_up_action",
            "scene_overlay_move_down_action",
            "scene_overlay_move_right_action",
            "ViewCompat.addAccessibilityAction",
            "view.performClick()",
        )
        val missingMarkers = requiredMarkers.filterNot(source::contains)

        assertTrue("Missing overlay accessibility contracts: $missingMarkers", missingMarkers.isEmpty())
    }

    @Test
    fun automationRowsUseNamedControlsAndAuthoredSwitchState() {
        val source = uiSourceRoot.resolve("screens/ActiveAutomationLists.kt").readText()
        val requiredMarkers = listOf(
            "R.string.a11y_profile_status",
            "stateDescription = profileState",
            "R.string.a11y_edit_profile",
            "R.string.a11y_run_task",
            "R.string.a11y_edit_action",
            "R.string.a11y_delete_context",
            "clearAndSetSemantics",
        )
        val missingMarkers = requiredMarkers.filterNot(source::contains)

        assertTrue("Missing named automation control semantics: $missingMarkers", missingMarkers.isEmpty())
        assertFalse(
            "Nested automation controls must not use generic edit descriptions",
            source.contains("contentDescription = stringResource(R.string.action_edit)"),
        )
        assertFalse(
            "Profile switch must keep its authored accessible name and state",
            source.contains("Switch(checked = profile.enabled, onCheckedChange = onToggle)"),
        )
    }

    @Test
    fun criticalFlowsKeepAccessibilityContracts() {
        val requiredMarkersByFile = mapOf(
            "screens/PermissionOnboardingScreen.kt" to listOf(
                "role = Role.RadioButton",
                "selected = mode == currentMode",
                "stateDescription = selectionDescription",
                "R.string.a11y_option_selected",
                "R.string.status_granted",
                "R.string.status_optional",
                "R.string.status_required",
            ),
            "screens/EditorDialogs.kt" to listOf(
                "role = Role.Switch",
                "stateDescription = if (enabled) onLabel else offLabel",
                "enabled = canSave",
                "R.string.ui_info_content_description",
                "R.string.delete_undo_helper",
                "blueprint-input-",
            ),
            "screens/ImportedProfileRiskDialog.kt" to listOf(
                "Checkbox(checked = acknowledged",
                "enabled = review.canAcknowledge && acknowledged",
                "R.string.imported_profile_acknowledgement",
                "R.string.imported_profile_acknowledge_enable",
            ),
            "screens/ActionEditorDialogs.kt" to listOf(
                "role = Role.Switch",
                "stateDescription = stateDescriptionLabel",
                "enabled = validationIssues.isEmpty() && taskBindingIssues.isEmpty() && capability.canAdd",
                "R.string.label_required",
            ),
            "screens/ContextEditorDialogs.kt" to listOf(
                "role = Role.Switch",
                "stateDescription = if (invert) onLabel else offLabel",
                "enabled = !missingRequired",
                "R.string.context_invert_helper",
            ),
            "screens/SceneLibraryCards.kt" to listOf(
                "R.string.scenes_empty_content_description",
                "R.string.scenes_move_left_content_description",
            ),
            "screens/SceneEditorDialogs.kt" to listOf(
                "R.string.scenes_remove_element_content_description",
                "enabled = canSave",
            ),
            "screens/RunLogScreenContent.kt" to listOf(
                "R.string.empty_run_log_title",
                "R.string.empty_run_log_search_title",
                "R.string.run_log_share_diagnostic",
                "contentDescription = when (outcome)",
                "stateDescription = selectionDescription",
                "R.string.a11y_expression_details",
                "role = Role.Button",
                "clearAndSetSemantics",
            ),
        )

        val missingMarkers = requiredMarkersByFile.flatMap { (relativePath, markers) ->
            val source = uiSourceRoot.resolve(relativePath).readText()
            markers.filterNot(source::contains).map { "$relativePath: $it" }
        }

        assertTrue("Missing critical-flow accessibility markers: $missingMarkers", missingMarkers.isEmpty())
    }

    @Test
    fun appShellAndSetupDoNotShipHardcodedSemanticLabels() {
        val checkedFiles = listOf(
            "screens/ActiveAutomationUi.kt",
            "screens/PermissionOnboardingScreen.kt",
            // Setup's rows moved to SetupRows.kt, and a row is precisely the thing that grows a
            // literal contentDescription. Naming only the screen would have left the definitions
            // this gate exists to police outside it.
            "screens/SetupRows.kt",
        )
        val forbiddenPatterns = mapOf(
            "literal contentDescription" to Regex("""contentDescription\s*=\s*""" + "\""),
            "literal stateDescription" to Regex("""stateDescription\s*=\s*""" + "\""),
            "interpolated selected state" to Regex("""\${'$'}label\s+(not\s+)?selected"""),
            "literal create icon" to Regex("""Create (task|profile) icon"""),
        )

        val offenders = checkedFiles.flatMap { relativePath ->
            val source = uiSourceRoot.resolve(relativePath).readText()
            forbiddenPatterns.mapNotNull { (name, pattern) ->
                if (pattern.containsMatchIn(source)) "$relativePath: $name" else null
            }
        }

        assertTrue("Hardcoded accessibility semantic labels found: $offenders", offenders.isEmpty())
    }

    private fun kotlinFiles(): List<Path> =
        Files.walk(uiSourceRoot).use { stream ->
            stream
                .filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".kt") }
                .toList()
        }
}
