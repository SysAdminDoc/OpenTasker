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

    // A null description is right for an icon beside its own label (describing both made TalkBack
    // read the label twice). It's wrong only where the icon is the whole control.
    @Test
    fun iconOnlyButtonsKeepAnAccessibleName() {
        val offenders = kotlinFiles().flatMap { file ->
            unnamedIconOnlyButtons(file.readText()).map { "${uiSourceRoot.relativize(file)}: $it" }
        }

        assertTrue("Icon-only buttons with no accessible name: $offenders", offenders.isEmpty())
    }

    @Test
    fun iconOnlyButtonCheckCatchesAnUnnamedButton() {
        val planted = """
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = null)
            }
            IconButton(onClick = onEdit, modifier = Modifier.semantics { contentDescription = editLabel }) {
                Icon(Icons.Filled.Edit, contentDescription = null)
            }
            OutlinedButton(onClick = onAdd) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Text(addLabel)
            }
        """.trimIndent()

        assertEquals(listOf("IconButton(onClick = onDelete)"), unnamedIconOnlyButtons(planted))
    }

    // The icon/text slot form of ExtendedFloatingActionButton clears its text's semantics, so the
    // icon's description is the only name the button has.
    @Test
    fun extendedFabsKeepTheirIconDescription() {
        val offenders = kotlinFiles().flatMap { file ->
            unnamedExtendedFabs(file.readText()).map { "${uiSourceRoot.relativize(file)}: $it" }
        }

        assertTrue("Extended FABs with no accessible name: $offenders", offenders.isEmpty())
    }

    @Test
    fun extendedFabCheckCatchesANullIcon() {
        val planted = """
            ExtendedFloatingActionButton(
                onClick = onNew,
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text(newLabel) },
            )
            ExtendedFloatingActionButton(
                onClick = onCreate,
                icon = { Icon(Icons.Filled.Add, contentDescription = createLabel) },
                text = { Text(createLabel) },
            )
            ExtendedFloatingActionButton(onClick = onAdd) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Text(addLabel)
            }
        """.trimIndent()

        assertEquals(listOf("line 1"), unnamedExtendedFabs(planted))
    }

    private fun unnamedExtendedFabs(source: String): List<String> =
        EXTENDED_FAB.findAll(source).mapNotNull { match ->
            val paramsEnd = closingIndex(source, match.range.last, '(', ')') ?: return@mapNotNull null
            val params = source.substring(match.range.first, paramsEnd + 1)
            val slotForm = "icon =" in params && "text =" in params
            val descriptions = CONTENT_DESCRIPTION.findAll(params).map { it.groupValues[1] }.toList()
            val line = source.substring(0, match.range.first).count { it == '\n' } + 1
            "line $line".takeIf { slotForm && descriptions.all { it == "null" } }
        }.toList()

    private fun unnamedIconOnlyButtons(source: String): List<String> =
        ICON_ONLY_BUTTON.findAll(source).mapNotNull { match ->
            val paramsEnd = closingIndex(source, match.range.last, '(', ')') ?: return@mapNotNull null
            val bodyStart = source.indexOf('{', paramsEnd).takeIf { start ->
                start >= 0 && source.substring(paramsEnd + 1, start).isBlank()
            } ?: return@mapNotNull null
            val bodyEnd = closingIndex(source, bodyStart, '{', '}') ?: return@mapNotNull null
            val params = source.substring(match.range.first, paramsEnd + 1)
            val body = source.substring(bodyStart, bodyEnd + 1)
            params.takeIf { "contentDescription = null" in body && "contentDescription" !in it }
        }.toList()

    private fun closingIndex(source: String, openIndex: Int, open: Char, close: Char): Int? {
        var depth = 0
        for (index in openIndex until source.length) {
            when (source[index]) {
                open -> depth++
                close -> if (--depth == 0) return index
            }
        }
        return null
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

    private companion object {
        val ICON_ONLY_BUTTON = Regex(
            """\b(IconButton|FilledIconButton|FilledTonalIconButton|OutlinedIconButton|IconToggleButton|""" +
                """FilledIconToggleButton|OutlinedIconToggleButton|FloatingActionButton|""" +
                """SmallFloatingActionButton|LargeFloatingActionButton)\s*\(""",
        )
        val EXTENDED_FAB = Regex("""\bExtendedFloatingActionButton\s*\(""")
        val CONTENT_DESCRIPTION = Regex("""contentDescription\s*=\s*(\w+)""")
    }
}
