package com.opentasker.core.contexts

import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.opentasker.app.OpenTaskerApp_NoHilt
import com.opentasker.app.R
import com.opentasker.core.model.Task
import com.opentasker.ui.theme.OpenTaskerTheme
import com.opentasker.ui.screens.FullScreenContentLoading
import com.opentasker.ui.theme.ThemePreference
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.emitAll

class QuickSettingsTileConfigActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Without this the window keeps Theme.OpenTasker's opaque black bars while the
        // shared theme sets light status-bar icons, so a Light-theme user sees dark icons
        // on black - invisible - on Android 8 through 14. MainActivity already does this.
        enableEdgeToEdge()
        setResult(RESULT_CANCELED)
        val slot = resolveSlot(intent)
        if (slot == null) {
            finish()
            return
        }

        val store = QuickSettingsTileStore(this)
        val initial = store.load(slot)
        // Awaited on first collection rather than in onCreate; see MainActivity.
        val tasksFlow = flow { emitAll(OpenTaskerApp_NoHilt.awaitDb().taskDao().getAllAsFlow()) }.map { entities ->
            entities.mapNotNull { entity ->
                val decoded = entity.toDomainDecodeResult()
                decoded.value.takeIf { decoded.issue == null }
            }
        }
        setContent {
            val themeMode by ThemePreference.observe(this).collectAsState(initial = null)
            val resolvedTheme = themeMode ?: return@setContent
            val tasks by tasksFlow.collectAsState(initial = null)
            OpenTaskerTheme(resolvedTheme) {
                val loadedTasks = tasks
                if (loadedTasks == null) {
                    FullScreenContentLoading()
                    return@OpenTaskerTheme
                }
                TileConfigScreen(
                    slot = slot,
                    initial = initial,
                    tasks = loadedTasks,
                    onSave = { config ->
                        store.save(config)
                        store.requestRefresh(this, slot)
                        setResult(RESULT_OK)
                        finish()
                    },
                )
            }
        }
    }

    private fun resolveSlot(source: Intent?): Int? {
        val explicit = source?.getIntExtra(EXTRA_SLOT, -1)?.takeIf { it > 0 }
        if (explicit != null) return QuickSettingsTileSlots.normalize(explicit)
        val component = if (Build.VERSION.SDK_INT >= 33) {
            source?.getParcelableExtra(Intent.EXTRA_COMPONENT_NAME, ComponentName::class.java)
        } else {
            @Suppress("DEPRECATION")
            source?.getParcelableExtra<ComponentName>(Intent.EXTRA_COMPONENT_NAME)
        }
        return QuickSettingsTileSlots.slotForComponent(component?.className)
    }

    companion object {
        const val EXTRA_SLOT = "com.opentasker.quick_settings.EXTRA_SLOT"
    }
}

private data class TileIconChoice(val key: String, val labelRes: Int)

private val tileIconChoices = listOf(
    TileIconChoice(QuickSettingsTileIcons.DEFAULT, R.string.action_option_tile_icon_play),
    TileIconChoice(QuickSettingsTileIcons.STAR, R.string.action_option_tile_icon_star),
    TileIconChoice(QuickSettingsTileIcons.SETTINGS, R.string.action_option_tile_icon_settings),
    TileIconChoice(QuickSettingsTileIcons.BOLT, R.string.action_option_tile_icon_bolt),
)

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun TileConfigScreen(
    slot: Int,
    initial: QuickSettingsTileConfig,
    tasks: List<Task>,
    onSave: (QuickSettingsTileConfig) -> Unit,
) {
    var taskId by rememberSaveable(slot) { mutableStateOf(initial.taskId) }
    var taskName by rememberSaveable(slot) { mutableStateOf(initial.taskName) }
    var label by rememberSaveable(slot) { mutableStateOf(initial.label) }
    var subtitle by rememberSaveable(slot) { mutableStateOf(initial.subtitle) }
    var iconKey by rememberSaveable(slot) { mutableStateOf(initial.iconKey) }
    val selectedTask = tasks.firstOrNull { it.id == taskId }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.qs_tile_config_title), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            stringResource(R.string.qs_tile_config_subtitle, slot),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { Text(stringResource(R.string.qs_tile_choose_task), style = MaterialTheme.typography.titleMedium) }
            if (tasks.isEmpty()) {
                item { Text(stringResource(R.string.qs_tile_no_tasks), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            } else {
                items(tasks, key = Task::id) { task ->
                    val selected = task.id == taskId
                    Card(
                        modifier = Modifier.fillMaxWidth().clickable {
                            taskId = task.id
                            taskName = task.name
                            if (label.isBlank() || label == initial.taskName) label = task.name
                        },
                        colors = CardDefaults.cardColors(
                            containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer
                            else MaterialTheme.colorScheme.surfaceVariant,
                        ),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                if (selected) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                                contentDescription = null,
                            )
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(task.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    stringResource(R.string.qs_tile_task_id, task.id),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
            item {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text(stringResource(R.string.qs_tile_label)) },
                    placeholder = { Text(stringResource(R.string.qs_tile_label_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                OutlinedTextField(
                    value = subtitle,
                    onValueChange = { subtitle = it },
                    label = { Text(stringResource(R.string.qs_tile_subtitle)) },
                    placeholder = { Text(stringResource(R.string.qs_tile_subtitle_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                Column {
                    Text(stringResource(R.string.qs_tile_icon), style = MaterialTheme.typography.titleSmall)
                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                        tileIconChoices.forEach { choice ->
                            OutlinedButton(
                                onClick = { iconKey = choice.key },
                                modifier = Modifier.padding(end = 8.dp),
                            ) {
                                Text(
                                    text = stringResource(choice.labelRes),
                                    color = if (choice.key == iconKey) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                    }
                }
            }
            item {
                Button(
                    onClick = {
                        selectedTask?.let { task ->
                            onSave(
                                QuickSettingsTileConfig(
                                    slot = slot,
                                    taskId = task.id,
                                    taskName = task.name,
                                    label = label,
                                    subtitle = subtitle,
                                    iconKey = iconKey,
                                    active = initial.active,
                                ),
                            )
                        }
                    },
                    enabled = selectedTask != null,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.qs_tile_save))
                }
            }
        }
    }
}
