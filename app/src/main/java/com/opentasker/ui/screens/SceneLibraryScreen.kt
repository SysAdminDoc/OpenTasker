package com.opentasker.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.opentasker.app.R
import com.opentasker.core.model.Scene
import com.opentasker.core.model.Task
import com.opentasker.core.scenes.SceneOverlayService
import com.opentasker.ui.theme.DesignSystem

@Composable
fun SceneLibraryScreen(
    scenes: List<Scene>,
    tasks: List<Task>,
    focusSceneId: Long? = null,
    onCreateScene: (name: String, widthDp: Int, heightDp: Int, onSaved: () -> Unit) -> Unit,
    onUpdateScene: (Scene, Int, onSaved: () -> Unit) -> Unit,
    onRemoveElement: (Scene, Int) -> Unit,
    onUndoSceneEdit: (Scene) -> Unit = {},
    onRedoSceneEdit: (Scene) -> Unit = {},
    onDeleteScene: (Scene) -> Unit,
    onDuplicateScene: (Scene) -> Unit = {},
    contentPadding: PaddingValues,
    contentLoaded: Boolean = true,
    historyAvailability: EditHistoryAvailabilityState = EditHistoryAvailabilityState(),
) {
    var showCreateDialog by rememberSaveable { mutableStateOf(false) }
    var elementEditorSceneId by rememberSaveable { mutableStateOf<Long?>(null) }
    var elementEditorIndex by rememberSaveable { mutableStateOf<Int?>(null) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    val sortedScenes = remember(scenes) { scenes.sortedBy { it.name.lowercase() } }
    val filteredScenes = remember(sortedScenes, searchQuery) {
        if (searchQuery.isBlank()) sortedScenes
        else sortedScenes.filter { it.name.contains(searchQuery, ignoreCase = true) }
    }
    val listState = rememberLazyListState()
    val elementEditor = remember(scenes, elementEditorSceneId, elementEditorIndex) {
        sceneElementEditorState(scenes, elementEditorSceneId, elementEditorIndex, allowNew = true)
    }

    LaunchedEffect(focusSceneId, sortedScenes) {
        val focusedIndex = focusSceneId?.let { id -> sortedScenes.indexOfFirst { it.id == id } } ?: -1
        if (focusedIndex >= 0) listState.animateScrollToItem(focusedIndex + 2)
    }

    LaunchedEffect(elementEditorSceneId, elementEditor) {
        if (elementEditorSceneId != null && elementEditor == null) {
            elementEditorSceneId = null
            elementEditorIndex = null
        }
    }
    if (showCreateDialog) {
        SceneEditorDialog(
            onDismiss = { showCreateDialog = false },
            // Editors close once the save lands, not before: a rejected save keeps what was typed.
            onSave = { name, widthDp, heightDp ->
                onCreateScene(name, widthDp, heightDp) { showCreateDialog = false }
            },
        )
    }

    elementEditor?.let { state ->
        SceneElementEditorDialog(
            state = state,
            tasks = tasks,
            onDismiss = {
                elementEditorSceneId = null
                elementEditorIndex = null
            },
            onSave = { element ->
                val updatedScene = if (state.index == null) {
                    state.scene.copy(elements = state.scene.elements + element)
                } else {
                    state.scene.copy(
                        elements = state.scene.elements.mapIndexed { index, existing ->
                            if (index == state.index) element else existing
                        },
                    )
                }
                onUpdateScene(
                    updatedScene,
                    if (state.index == null) R.string.ui_message_element_added else R.string.ui_message_element_updated,
                ) {
                    elementEditorSceneId = null
                    elementEditorIndex = null
                }
            },
        )
    }

    if (!contentLoaded) {
        ContentLoadingState(contentPadding)
        return
    }
    if (sortedScenes.isEmpty()) {
        SceneEmptyState(
            contentPadding = contentPadding,
            onCreateScene = { showCreateDialog = true },
        )
        return
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding),
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = DesignSystem.Screen.horizontalPadding,
                top = DesignSystem.Screen.verticalPadding,
                end = DesignSystem.Screen.horizontalPadding,
                bottom = 96.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(DesignSystem.Screen.cardGap),
        ) {
            item {
                SceneOverviewCard(
                    scenes = sortedScenes,
                    tasks = tasks,
                )
            }
            item {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(stringResource(R.string.scenes_search_hint)) },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    trailingIcon = if (searchQuery.isNotEmpty()) {
                        {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Filled.Clear, contentDescription = stringResource(R.string.variables_search_clear))
                            }
                        }
                    } else null,
                    singleLine = true,
                )
            }
            if (filteredScenes.isEmpty()) {
                item {
                    InlineNotice(
                        title = stringResource(R.string.scenes_no_matches_title),
                        body = stringResource(R.string.scenes_no_matches_body),
                        color = androidx.compose.material3.MaterialTheme.colorScheme.primary,
                    )
                }
            }
            items(filteredScenes, key = { it.id }) { scene ->
                val sceneContext = LocalContext.current
                SceneCard(
                    scene = scene,
                    tasks = tasks,
                    onAddElement = {
                        elementEditorSceneId = scene.id
                        elementEditorIndex = null
                    },
                    onEditElement = { index, _ ->
                        elementEditorSceneId = scene.id
                        elementEditorIndex = index
                    },
                    onDeleteElement = { index, _ ->
                        onRemoveElement(scene, index)
                    },
                    onUpdateScene = { scene, messageRes -> onUpdateScene(scene, messageRes) {} },
                    canUndo = historyAvailability.canUndoScene(scene.id),
                    canRedo = historyAvailability.canRedoScene(scene.id),
                    onUndo = { onUndoSceneEdit(scene) },
                    onRedo = { onRedoSceneEdit(scene) },
                    onDelete = { onDeleteScene(scene) },
                    onDuplicate = { onDuplicateScene(scene) },
                    onShowOverlay = { SceneOverlayService.show(sceneContext, scene) },
                )
            }
        }
        ExtendedFloatingActionButton(
            onClick = { showCreateDialog = true },
            // Material3 clears the extended FAB's text semantics, so the icon's description is the button's name.
            icon = { Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.scenes_create)) },
            text = { Text(stringResource(R.string.scenes_create)) },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = DesignSystem.Screen.horizontalPadding, bottom = 16.dp),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(DesignSystem.Radii.md),
        )
    }
}
