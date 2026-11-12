package com.opentasker.ui.screens

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.opentasker.app.R
import com.opentasker.core.model.Scene
import com.opentasker.core.model.SceneElement
import com.opentasker.core.model.SceneElementType
import com.opentasker.core.model.Task
import com.opentasker.core.scenes.SceneElementConfigResolver
import com.opentasker.core.scenes.SceneElementConfigValidator
import com.opentasker.core.scenes.SceneElementDrafts
import com.opentasker.core.scenes.SceneImageLoader
import com.opentasker.ui.theme.DesignSystem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal fun sceneElementEditorState(
    scenes: List<Scene>,
    sceneId: Long?,
    index: Int?,
    allowNew: Boolean,
): SceneElementEditorState? {
    val scene = scenes.firstOrNull { it.id == sceneId } ?: return null
    return if (index == null) {
        if (allowNew) SceneElementEditorState(scene = scene) else null
    } else {
        SceneElementEditorState(
            scene = scene,
            index = index,
            element = scene.elements.getOrNull(index) ?: return null,
        )
    }
}

internal data class SceneElementEditorState(
    val scene: Scene,
    val index: Int? = null,
    val element: SceneElement? = null,
)

@Composable
internal fun SceneElementEditorDialog(
    state: SceneElementEditorState,
    tasks: List<Task>,
    onDismiss: () -> Unit,
    onSave: (SceneElement) -> Unit,
) {
    val context = LocalContext.current
    val initial = remember(state) {
        state.element ?: SceneElementDrafts.defaultElement(state.scene, SceneElementType.BUTTON)
    }
    val initialSlider = remember(initial) { SceneElementConfigResolver.slider(initial) }
    var type by rememberSaveable(state.scene.id, state.index) {
        mutableStateOf(initial.type.takeIf { it in SceneElementDrafts.editableTypes } ?: SceneElementType.BUTTON)
    }
    var x by rememberSaveable(state.scene.id, state.index) { mutableStateOf(initial.xDp.toString()) }
    var y by rememberSaveable(state.scene.id, state.index) { mutableStateOf(initial.yDp.toString()) }
    var width by rememberSaveable(state.scene.id, state.index) { mutableStateOf(initial.widthDp.toString()) }
    var height by rememberSaveable(state.scene.id, state.index) { mutableStateOf(initial.heightDp.toString()) }
    var label by rememberSaveable(state.scene.id, state.index) {
        mutableStateOf(initial.config["label"] ?: initial.config["text"] ?: "")
    }
    var sliderMin by rememberSaveable(state.scene.id, state.index) { mutableStateOf(initialSlider.min.toString()) }
    var sliderMax by rememberSaveable(state.scene.id, state.index) { mutableStateOf(initialSlider.max.toString()) }
    var sliderValue by rememberSaveable(state.scene.id, state.index) { mutableStateOf(initialSlider.value.toString()) }
    var imageSource by rememberSaveable(state.scene.id, state.index) { mutableStateOf(initial.config["source"] ?: "") }
    var imageDescription by rememberSaveable(state.scene.id, state.index) {
        mutableStateOf(initial.config["content_description"] ?: "")
    }
    var imageDecorative by rememberSaveable(state.scene.id, state.index) {
        mutableStateOf(initial.config["decorative"].equals("true", ignoreCase = true))
    }
    var imageSourceValid by rememberSaveable(state.scene.id, state.index) { mutableStateOf(false) }
    var imageSourceChecking by remember(state.scene.id, state.index) { mutableStateOf(true) }
    var tapTaskId by rememberSaveable(state.scene.id, state.index) { mutableStateOf(initial.tapTaskId) }
    var longPressTaskId by rememberSaveable(state.scene.id, state.index) { mutableStateOf(initial.longPressTaskId) }

    val parsedX = x.toIntOrNull()
    val parsedY = y.toIntOrNull()
    val parsedWidth = width.toIntOrNull()
    val parsedHeight = height.toIntOrNull()
    val parsedSliderMin = sliderMin.toIntOrNull()
    val parsedSliderMax = sliderMax.toIntOrNull()
    val parsedSliderValue = sliderValue.toIntOrNull()
    val configForValidation = when (type) {
        SceneElementType.SLIDER -> mapOf(
            "min" to sliderMin,
            "max" to sliderMax,
            "value" to sliderValue,
        )
        SceneElementType.IMAGE -> mapOf(
            "source" to imageSource,
            "content_description" to imageDescription,
            "decorative" to imageDecorative.toString(),
        )
        else -> emptyMap()
    }
    val configValid = SceneElementConfigValidator.validate(type, configForValidation).isEmpty()
    val canSave = parsedX != null &&
        parsedY != null &&
        parsedWidth != null &&
        parsedHeight != null &&
        parsedX >= 0 &&
        parsedY >= 0 &&
        parsedWidth > 0 &&
        parsedHeight > 0 &&
        configValid && (type != SceneElementType.IMAGE || imageSourceValid)
    val defaultTextLabel = stringResource(R.string.scene_element_type_text)
    val defaultButtonLabel = stringResource(R.string.scene_element_type_button)
    val defaultSliderLabel = stringResource(R.string.scene_element_type_slider)
    LaunchedEffect(type, imageSource, parsedWidth, parsedHeight) {
        imageSourceChecking = true
        imageSourceValid = false
        if (
            type == SceneElementType.IMAGE &&
            parsedWidth != null && parsedHeight != null &&
            SceneImageLoader.isSupportedSource(imageSource)
        ) {
            imageSourceValid = withContext(Dispatchers.IO) {
                SceneImageLoader.load(
                    context = context,
                    source = imageSource,
                    targetWidthPx = parsedWidth.coerceAtLeast(1),
                    targetHeightPx = parsedHeight.coerceAtLeast(1),
                ) != null
            }
        }
        imageSourceChecking = false
    }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            imageSource = uri.toString().take(SceneImageLoader.MAX_SOURCE_CHARS)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(dismissOnClickOutside = false),
        title = {
            Text(
                stringResource(
                    if (state.index == null) R.string.action_add_element else R.string.scenes_edit_element_content_description,
                ),
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 560.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.md),
            ) {
                SceneElementTypeSelector(
                    selected = type,
                    onSelect = { selected ->
                        type = selected
                        val defaults = SceneElementDrafts.defaultElement(state.scene, selected)
                        width = defaults.widthDp.toString()
                        height = defaults.heightDp.toString()
                        label = defaults.config["label"] ?: defaults.config["text"] ?: ""
                        sliderMin = defaults.config["min"] ?: "0"
                        sliderMax = defaults.config["max"] ?: "100"
                        sliderValue = defaults.config["value"] ?: "50"
                        imageSource = defaults.config["source"] ?: ""
                        imageDescription = defaults.config["content_description"].orEmpty()
                        imageDecorative = defaults.config["decorative"].equals("true", ignoreCase = true)
                    },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.sm), modifier = Modifier.fillMaxWidth()) {
                    NumberField(stringResource(R.string.label_x_dp), x, { x = it.filter(Char::isDigit).take(4) }, parsedX == null, Modifier.weight(1f))
                    NumberField(stringResource(R.string.label_y_dp), y, { y = it.filter(Char::isDigit).take(4) }, parsedY == null, Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.sm), modifier = Modifier.fillMaxWidth()) {
                    NumberField(
                        label = stringResource(R.string.label_width_dp),
                        value = width,
                        onValueChange = { width = it.filter(Char::isDigit).take(4) },
                        isError = parsedWidth == null || parsedWidth <= 0,
                        modifier = Modifier.weight(1f),
                    )
                    NumberField(
                        label = stringResource(R.string.label_height_dp),
                        value = height,
                        onValueChange = { height = it.filter(Char::isDigit).take(4) },
                        isError = parsedHeight == null || parsedHeight <= 0,
                        modifier = Modifier.weight(1f),
                    )
                }
                when (type) {
                    SceneElementType.TEXT -> OutlinedTextField(
                        value = label,
                        onValueChange = { label = it.take(80) },
                        label = { Text(stringResource(R.string.label_text)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    SceneElementType.BUTTON -> OutlinedTextField(
                        value = label,
                        onValueChange = { label = it.take(48) },
                        label = { Text(stringResource(R.string.label_button_label)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    SceneElementType.SLIDER -> {
                        OutlinedTextField(
                            value = label,
                            onValueChange = { label = it.take(48) },
                            label = { Text(stringResource(R.string.label_slider_label)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.sm), modifier = Modifier.fillMaxWidth()) {
                            NumberField(stringResource(R.string.label_min), sliderMin, { sliderMin = it.filter(Char::isDigit).take(5) }, parsedSliderMin == null, Modifier.weight(1f))
                            NumberField(
                                stringResource(R.string.label_max),
                                sliderMax,
                                { sliderMax = it.filter(Char::isDigit).take(5) },
                                parsedSliderMax == null || (parsedSliderMin != null && parsedSliderMax < parsedSliderMin),
                                Modifier.weight(1f),
                            )
                            NumberField(stringResource(R.string.label_value), sliderValue, { sliderValue = it.filter(Char::isDigit).take(5) }, parsedSliderValue == null, Modifier.weight(1f))
                        }
                    }

                    SceneElementType.IMAGE -> Column(
                        verticalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.sm),
                    ) {
                        OutlinedTextField(
                            value = imageSource,
                            onValueChange = { imageSource = it.take(SceneImageLoader.MAX_SOURCE_CHARS) },
                            label = { Text(stringResource(R.string.label_image_label_or_uri)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = imageDescription,
                            onValueChange = { imageDescription = it.take(160) },
                            label = { Text(stringResource(R.string.label_image_description)) },
                            singleLine = true,
                            enabled = !imageDecorative,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = imageDecorative, onCheckedChange = { imageDecorative = it })
                            Text(stringResource(R.string.label_image_decorative))
                        }
                        if (imageSource.isNotBlank() && !imageSourceValid && !imageSourceChecking) {
                            Text(
                                stringResource(R.string.scene_image_repair_required),
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        OutlinedButton(
                            onClick = { imagePicker.launch(arrayOf("image/*")) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.action_choose_image))
                        }
                    }

                    else -> Unit
                }
                SceneTaskBindingSelector(
                    label = stringResource(R.string.label_tap_task),
                    tasks = tasks,
                    selectedTaskId = tapTaskId,
                    onSelect = { tapTaskId = it },
                )
                SceneTaskBindingSelector(
                    label = stringResource(R.string.label_long_press_task),
                    tasks = tasks,
                    selectedTaskId = longPressTaskId,
                    onSelect = { longPressTaskId = it },
                )
            }
        },
        confirmButton = {
            Button(
                enabled = canSave,
                onClick = {
                    onSave(
                        SceneElement(
                            id = initial.id,
                            type = type,
                            xDp = parsedX ?: 0,
                            yDp = parsedY ?: 0,
                            widthDp = parsedWidth ?: 1,
                            heightDp = parsedHeight ?: 1,
                            config = elementConfig(
                                type = type,
                                label = label,
                                sliderMin = sliderMin,
                                sliderMax = sliderMax,
                                sliderValue = sliderValue,
                                imageSource = imageSource,
                                imageDescription = imageDescription,
                                imageDecorative = imageDecorative,
                                defaultTextLabel = defaultTextLabel,
                                defaultButtonLabel = defaultButtonLabel,
                                defaultSliderLabel = defaultSliderLabel,
                            ),
                            tapTaskId = tapTaskId,
                            longPressTaskId = longPressTaskId,
                        ),
                    )
                },
            ) {
                Text(if (state.index == null) stringResource(R.string.action_add_element) else stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun SceneElementTypeSelector(
    selected: SceneElementType,
    onSelect: (SceneElementType) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Text(sceneElementTypeLabel(selected), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            SceneElementDrafts.editableTypes.forEach { type ->
                DropdownMenuItem(
                    text = { Text(sceneElementTypeLabel(type)) },
                    onClick = {
                        onSelect(type)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun SceneTaskBindingSelector(
    label: String,
    tasks: List<Task>,
    selectedTaskId: Long?,
    onSelect: (Long?) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val taskNames = remember(tasks) { tasks.associate { it.id to it.name } }
    val selectedTaskLabel = selectedTaskId?.let { taskId ->
        taskNames[taskId] ?: stringResource(R.string.scenes_missing_task_id, taskId)
    } ?: stringResource(R.string.label_none)
    Box {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Text(
                stringResource(R.string.scenes_task_binding_value, label, selectedTaskLabel),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.label_none)) },
                onClick = {
                    onSelect(null)
                    expanded = false
                },
            )
            tasks.sortedBy { it.name.lowercase() }.forEach { task ->
                DropdownMenuItem(
                    text = { Text(task.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    onClick = {
                        onSelect(task.id)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun NumberField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    isError: Boolean,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        isError = isError,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
        modifier = modifier,
    )
}

@Composable
internal fun SceneEditorDialog(
    onDismiss: () -> Unit,
    onSave: (String, Int, Int) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("") }
    var width by rememberSaveable { mutableStateOf("320") }
    var height by rememberSaveable { mutableStateOf("240") }
    val parsedWidth = width.toIntOrNull()
    val parsedHeight = height.toIntOrNull()
    val canSave = sceneCreateCanSave(name, parsedWidth, parsedHeight)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.scenes_create)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.md),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.scenes_scene_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = width,
                    onValueChange = { width = it.filter(Char::isDigit).take(4) },
                    label = { Text(stringResource(R.string.label_width_dp)) },
                    supportingText = { Text(stringResource(R.string.scenes_size_range_helper, SCENE_WIDTH_RANGE_DP.first, SCENE_WIDTH_RANGE_DP.last)) },
                    isError = parsedWidth == null || parsedWidth !in SCENE_WIDTH_RANGE_DP,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = height,
                    onValueChange = { height = it.filter(Char::isDigit).take(4) },
                    label = { Text(stringResource(R.string.label_height_dp)) },
                    supportingText = { Text(stringResource(R.string.scenes_size_range_helper, SCENE_HEIGHT_RANGE_DP.first, SCENE_HEIGHT_RANGE_DP.last)) },
                    isError = parsedHeight == null || parsedHeight !in SCENE_HEIGHT_RANGE_DP,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                enabled = canSave,
                onClick = { onSave(name.trim(), parsedWidth ?: 320, parsedHeight ?: 240) },
            ) {
                Text(stringResource(R.string.action_create))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

private fun elementConfig(
    type: SceneElementType,
    label: String,
    sliderMin: String,
    sliderMax: String,
    sliderValue: String,
    imageSource: String,
    imageDescription: String,
    imageDecorative: Boolean,
    defaultTextLabel: String,
    defaultButtonLabel: String,
    defaultSliderLabel: String,
): Map<String, String> = when (type) {
    SceneElementType.TEXT -> mapOf("text" to label.ifBlank { defaultTextLabel })
    SceneElementType.BUTTON -> mapOf("label" to label.ifBlank { defaultButtonLabel })
    SceneElementType.SLIDER -> {
        val min = sliderMin.toIntOrNull() ?: 0
        val max = (sliderMax.toIntOrNull() ?: 100).coerceAtLeast(min)
        val value = (sliderValue.toIntOrNull() ?: min).coerceIn(min, max)
        mapOf(
            "label" to label.ifBlank { defaultSliderLabel },
            "min" to min.toString(),
            "max" to max.toString(),
            "value" to value.toString(),
        )
    }
    SceneElementType.IMAGE -> mapOf(
        "source" to imageSource.trim(),
        "content_description" to imageDescription.trim(),
        "decorative" to imageDecorative.toString(),
    )
    else -> emptyMap()
}

internal val SCENE_WIDTH_RANGE_DP = 120..1440
internal val SCENE_HEIGHT_RANGE_DP = 80..2560

internal fun sceneCreateCanSave(name: String, widthDp: Int?, heightDp: Int?): Boolean =
    name.isNotBlank() && widthDp != null && widthDp in SCENE_WIDTH_RANGE_DP &&
        heightDp != null && heightDp in SCENE_HEIGHT_RANGE_DP
