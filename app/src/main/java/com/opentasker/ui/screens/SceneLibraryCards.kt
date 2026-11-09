package com.opentasker.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.luminance
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.opentasker.app.R
import com.opentasker.core.model.Scene
import com.opentasker.core.model.SceneElement
import com.opentasker.core.model.SceneElementType
import com.opentasker.core.model.Task
import com.opentasker.core.scenes.SceneEditorMutations
import com.opentasker.core.scenes.SceneElementConfigResolver
import com.opentasker.core.scenes.SceneIssue
import com.opentasker.core.scenes.SceneIssueSeverity
import com.opentasker.core.scenes.SceneValidator
import com.opentasker.ui.theme.DesignSystem
import com.opentasker.ui.utils.expandCollapseToggle

@Composable
internal fun SceneEmptyState(
    contentPadding: PaddingValues,
    onCreateScene: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.64f),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(DesignSystem.Radii.xxl),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.52f)),
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.md),
            ) {
                Icon(
                    Icons.Filled.Info,
                    contentDescription = stringResource(R.string.scenes_empty_content_description),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(32.dp),
                )
                Text(stringResource(R.string.empty_scenes_title), style = MaterialTheme.typography.titleLarge)
                Text(
                    stringResource(R.string.empty_scenes_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                Button(
                    onClick = onCreateScene,
                    modifier = Modifier.fillMaxWidth(),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(DesignSystem.Radii.lg),
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.scenes_create))
                }
            }
        }
    }
}

@Composable
internal fun SceneOverviewCard(
    scenes: List<Scene>,
    tasks: List<Task>,
) {
    val overlayReady = sceneOverlayReady()
    val issues = remember(scenes, tasks) {
        scenes.flatMap { scene -> SceneValidator.validate(scene, tasks) }
    }
    val errorCount = issues.count { it.severity == SceneIssueSeverity.ERROR }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(DesignSystem.Radii.md),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                if (errorCount > 0) Icons.Filled.Warning else Icons.Filled.Info,
                contentDescription = if (errorCount > 0) {
                    pluralStringResource(R.plurals.scenes_overview_errors, errorCount, errorCount)
                } else {
                    stringResource(R.string.nav_scenes)
                },
                tint = if (errorCount > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Text(
                stringResource(R.string.scenes_overview_summary, scenes.sumOf { it.elements.size }, scenes.size),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            SceneOverlayReadinessPill(overlayReady = overlayReady)
        }
    }
}

@Composable
internal fun SceneCard(
    scene: Scene,
    tasks: List<Task>,
    onAddElement: () -> Unit,
    onEditElement: (Int, SceneElement) -> Unit,
    onDeleteElement: (Int, SceneElement) -> Unit,
    onUpdateScene: (Scene, Int) -> Unit,
    canUndo: Boolean,
    canRedo: Boolean,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onDelete: () -> Unit,
    onDuplicate: () -> Unit,
    onShowOverlay: () -> Unit = {},
) {
    val taskNames = remember(tasks) { tasks.associate { it.id to it.name } }
    val issues = remember(scene, tasks) { SceneValidator.validate(scene, tasks) }
    // Key the selection on the element identity list, not just scene.id: when an element is added
    // or deleted the indices shift, so surviving indices would point at the wrong elements and a
    // group move would move the wrong ones. Resetting on any structural change keeps it honest.
    val elementIds = scene.elements.map { it.id }
    var selectedIndices by remember(scene.id, elementIds) { mutableStateOf(emptySet<Int>()) }
    val overlayReady = sceneOverlayReady()
    var expanded by remember(scene.id) { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.22f)),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(DesignSystem.Radii.md),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.md)) {
            Row(
                modifier = Modifier.expandCollapseToggle(expanded) { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.md),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(scene.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        stringResource(R.string.scenes_card_summary, scene.widthDp, scene.heightDp, scene.elements.size),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                SceneOverlayButton(
                    visible = overlayReady && scene.elements.isNotEmpty(),
                    onShowOverlay = onShowOverlay,
                )
                IconButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.scenes_delete), tint = MaterialTheme.colorScheme.error)
                }
                DuplicateMenu(
                    contentDescription = stringResource(R.string.a11y_duplicate_scene, scene.name),
                    onDuplicate = onDuplicate,
                )
                Icon(
                    if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription = stringResource(if (expanded) R.string.action_collapse else R.string.action_expand),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (expanded) ScenePreviewBox(
                scene = scene,
                onMoveElement = { index, xDp, yDp ->
                    scene.elements.getOrNull(index)?.let { element ->
                        onUpdateScene(
                            SceneEditorMutations.replaceElement(scene, index, element.copy(xDp = xDp, yDp = yDp)),
                            R.string.ui_message_element_moved,
                        )
                    }
                },
                onResizeElement = { index, widthDp, heightDp ->
                    scene.elements.getOrNull(index)?.let { element ->
                        onUpdateScene(
                            SceneEditorMutations.replaceElement(
                                scene,
                                index,
                                element.copy(widthDp = widthDp, heightDp = heightDp),
                            ),
                            R.string.ui_message_element_resized,
                        )
                    }
                },
                selectedIndices = selectedIndices,
                onToggleSelect = { index ->
                    selectedIndices = if (index in selectedIndices) selectedIndices - index else selectedIndices + index
                },
                onMoveSelected = { dx, dy ->
                    val updatedScene = SceneEditorMutations.moveSelected(scene, selectedIndices, dx, dy)
                    if (updatedScene != scene) {
                        onUpdateScene(updatedScene, R.string.ui_message_elements_moved)
                    }
                },
            )

            if (expanded) OutlinedButton(onClick = onAddElement, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.action_add_element))
            }

            val nothingToUndo = stringResource(R.string.history_nothing_to_undo)
            val nothingToRedo = stringResource(R.string.history_nothing_to_redo)
            if (expanded) Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.sm),
            ) {
                OutlinedButton(
                    onClick = onUndo,
                    enabled = canUndo,
                    modifier = Modifier
                        .weight(1f)
                        .semantics { if (!canUndo) stateDescription = nothingToUndo },
                ) {
                    Text(stringResource(R.string.action_undo))
                }
                OutlinedButton(
                    onClick = onRedo,
                    enabled = canRedo,
                    modifier = Modifier
                        .weight(1f)
                        .semantics { if (!canRedo) stateDescription = nothingToRedo },
                ) {
                    Text(stringResource(R.string.action_redo))
                }
            }

            if (expanded && scene.elements.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.sm)) {
                    scene.elements.forEachIndexed { index, element ->
                        SceneElementRow(
                            scene = scene,
                            element = element,
                            taskNames = taskNames,
                            onNudge = { deltaX, deltaY ->
                                onUpdateScene(
                                    SceneEditorMutations.replaceElement(
                                        scene,
                                        index,
                                        element.nudgedWithin(scene, deltaX, deltaY),
                                    ),
                                    R.string.ui_message_element_moved,
                                )
                            },
                            onEdit = { onEditElement(index, element) },
                            onDelete = { onDeleteElement(index, element) },
                        )
                    }
                }
            }

            if (expanded && issues.isNotEmpty()) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    issues.take(4).forEach { issue ->
                        SceneIssueText(issue)
                    }
                }
            }
        }
    }
}

private fun SceneElement.nudgedWithin(scene: Scene, deltaX: Int, deltaY: Int): SceneElement {
    val maxX = (scene.widthDp - widthDp).coerceAtLeast(0)
    val maxY = (scene.heightDp - heightDp).coerceAtLeast(0)
    return copy(
        xDp = (xDp + deltaX).coerceIn(0, maxX),
        yDp = (yDp + deltaY).coerceIn(0, maxY),
    )
}

@Composable
private fun SceneElementRow(
    scene: Scene,
    element: SceneElement,
    taskNames: Map<Long, String>,
    onNudge: (Int, Int) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.36f),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(DesignSystem.Radii.lg),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.38f)),
    ) {
        Column(
            Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.sm),
        ) {
            Row(
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.sm),
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(sceneElementTypeLabel(element.type), style = MaterialTheme.typography.labelLarge)
                    sceneElementSummary(element)?.let { summary ->
                        Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
                    }
                    Text(
                        stringResource(
                            R.string.scenes_element_bounds,
                            element.xDp,
                            element.yDp,
                            element.widthDp,
                            element.heightDp,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    listOfNotNull(
                        element.tapTaskId?.let {
                            stringResource(
                                R.string.scenes_binding_tap,
                                taskNames[it] ?: stringResource(R.string.scenes_missing_task_id, it),
                            )
                        },
                        element.longPressTaskId?.let {
                            stringResource(
                                R.string.scenes_binding_long_press,
                                taskNames[it] ?: stringResource(R.string.scenes_missing_task_id, it),
                            )
                        },
                    ).forEach { binding ->
                        Text(binding, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Row {
                    IconButton(onClick = onEdit) {
                        Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.scenes_edit_element_content_description))
                    }
                    IconButton(onClick = onDelete) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = stringResource(R.string.scenes_delete_element_content_description),
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
            SceneElementNudgeControls(
                scene = scene,
                element = element,
                onNudge = onNudge,
            )
        }
    }
}

@Composable
private fun SceneElementNudgeControls(
    scene: Scene,
    element: SceneElement,
    onNudge: (Int, Int) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        IconButton(
            enabled = element.xDp > 0,
            onClick = { onNudge(-1, 0) },
        ) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = stringResource(R.string.scenes_move_left_content_description))
        }
        IconButton(
            enabled = element.yDp > 0,
            onClick = { onNudge(0, -1) },
        ) {
            Icon(Icons.Filled.KeyboardArrowUp, contentDescription = stringResource(R.string.scenes_move_up_content_description))
        }
        IconButton(
            enabled = element.yDp < (scene.heightDp - element.heightDp).coerceAtLeast(0),
            onClick = { onNudge(0, 1) },
        ) {
            Icon(Icons.Filled.KeyboardArrowDown, contentDescription = stringResource(R.string.scenes_move_down_content_description))
        }
        IconButton(
            enabled = element.xDp < (scene.widthDp - element.widthDp).coerceAtLeast(0),
            onClick = { onNudge(1, 0) },
        ) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = stringResource(R.string.scenes_move_right_content_description))
        }
    }
}

@Composable
private fun SceneIssueText(issue: SceneIssue) {
    // Derive from the APPLIED theme's surface luminance, not the system setting: the app
    // theme is user-selectable and can diverge from the system theme, which previously left
    // warnings near-invisible (peach on white) in Light-app-on-dark-system.
    val darkSurface = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val color = when (issue.severity) {
        SceneIssueSeverity.ERROR -> MaterialTheme.colorScheme.error
        SceneIssueSeverity.WARNING -> if (darkSurface) {
            DesignSystem.SemanticColor.warningDark
        } else {
            DesignSystem.SemanticColor.warningLight
        }
    }
    Text(issue.message, style = MaterialTheme.typography.bodySmall, color = color)
}

@Composable
private fun SceneMetric(value: String, label: String, modifier: Modifier = Modifier) {
    SummaryMetric(value = value, label = label, modifier = modifier)
}

@Composable
internal fun sceneElementTypeLabel(type: SceneElementType): String = stringResource(sceneElementTypeLabelRes(type))

@Composable
internal fun sceneElementSummary(element: SceneElement): String? = when (element.type) {
    SceneElementType.TEXT -> element.config["text"]?.takeIf { it.isNotBlank() }
    SceneElementType.BUTTON -> element.config["label"]?.takeIf { it.isNotBlank() }
    SceneElementType.SLIDER -> {
        val slider = SceneElementConfigResolver.slider(element)
        val label = slider.label.ifBlank { stringResource(R.string.scene_element_type_slider) }
        stringResource(R.string.scenes_slider_summary, label, slider.value, slider.min, slider.max)
    }
    SceneElementType.IMAGE -> element.config["source"]?.takeIf { it.isNotBlank() }
    else -> null
}
