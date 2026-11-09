package com.opentasker.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.isSystemInDarkTheme
import com.opentasker.app.R
import com.opentasker.app.OpenTaskerApp_NoHilt
import com.opentasker.core.model.Task
import com.opentasker.core.storage.StorageDecodeIssue
import com.opentasker.ui.screens.StorageDecodeWarningCard
import com.opentasker.ui.theme.DesignSystem
import com.opentasker.ui.theme.OpenTaskerTheme
import com.opentasker.ui.screens.FullScreenContentLoading
import com.opentasker.ui.theme.ThemeMode
import com.opentasker.ui.theme.ThemePreference
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.emitAll

class TaskWidgetConfigActivity : ComponentActivity() {

    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Without this the window keeps Theme.OpenTasker's opaque black bars while the
        // shared theme sets light status-bar icons, so a Light-theme user sees dark icons
        // on black - invisible - on Android 8 through 14. MainActivity already does this.
        enableEdgeToEdge()
        setResult(RESULT_CANCELED)

        widgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        // Cold flow: the database is awaited when the flow is first collected, not while
        // onCreate runs, so a startup still applying a staged restore cannot stall this window.
        val tasksFlow = flow { emitAll(OpenTaskerApp_NoHilt.awaitDb().taskDao().getAllAsFlow()) }
            .map { entities ->
                val results = entities.map { it.toDomainDecodeResult() }
                WidgetTaskState(
                    tasks = results.mapNotNull { result -> result.value.takeIf { result.issue == null } },
                    storageDecodeIssues = results.mapNotNull { it.issue },
                )
            }

        setContent {
            val themeMode by ThemePreference.observe(this).collectAsState(initial = ThemeMode.Amoled)
            OpenTaskerTheme(themeMode) {
                val loaded by tasksFlow.collectAsState(initial = null)
                val taskState = loaded
                if (taskState == null) {
                    FullScreenContentLoading()
                    return@OpenTaskerTheme
                }
                ConfigScreen(
                    tasks = taskState.tasks,
                    storageDecodeIssues = taskState.storageDecodeIssues,
                    onTaskSelected = ::onTaskPicked,
                )
            }
        }
    }

    private fun onTaskPicked(task: Task) {
        val prefs = getSharedPreferences(TaskWidgetProvider.PREFS_NAME, MODE_PRIVATE)
        prefs.edit()
            .putLong(TaskWidgetProvider.keyTaskId(widgetId), task.id)
            .putString(TaskWidgetProvider.keyTaskName(widgetId), task.name)
            .apply()

        TaskWidgetProvider.updateWidget(
            this,
            AppWidgetManager.getInstance(this),
            widgetId,
        )

        setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        finish()
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun ConfigScreen(
    tasks: List<Task>,
    storageDecodeIssues: List<StorageDecodeIssue>,
    onTaskSelected: (Task) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.widget_choose_task_title), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            stringResource(R.string.widget_choose_task_subtitle),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { padding ->
        if (tasks.isEmpty() && storageDecodeIssues.isEmpty()) {
            Surface(
                modifier = Modifier.fillMaxSize().padding(padding),
                color = MaterialTheme.colorScheme.background,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
                        shape = RoundedCornerShape(DesignSystem.Radii.xxl),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)),
                    ) {
                        Box(Modifier.padding(14.dp), contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Filled.Info,
                                contentDescription = stringResource(R.string.widget_setup_required),
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(30.dp),
                            )
                        }
                    }
                    Spacer(Modifier.size(20.dp))
                    Text(
                        stringResource(R.string.widget_empty_title),
                        style = MaterialTheme.typography.headlineSmall,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        stringResource(R.string.widget_empty_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(DesignSystem.Screen.horizontalPadding),
                verticalArrangement = Arrangement.spacedBy(DesignSystem.Screen.cardGap),
            ) {
                if (storageDecodeIssues.isNotEmpty()) {
                    item {
                        StorageDecodeWarningCard(storageDecodeIssues)
                    }
                }
                item {
                    WidgetConfigHeader(taskCount = tasks.size)
                }
                items(tasks, key = { it.id }) { task ->
                    Card(
                        onClick = { onTaskSelected(task) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 72.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.58f),
                        ),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.46f)),
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Filled.CheckCircle,
                                contentDescription = stringResource(R.string.widget_selectable_task),
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(24.dp),
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    task.name,
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    pluralStringResource(R.plurals.widget_action_count, task.actions.size, task.actions.size),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            Text(
                                stringResource(R.string.widget_assign),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
        }
    }
}

private data class WidgetTaskState(
    val tasks: List<Task> = emptyList(),
    val storageDecodeIssues: List<StorageDecodeIssue> = emptyList(),
)

@Composable
private fun WidgetConfigHeader(taskCount: Int) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.66f),
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.52f)),
        shape = RoundedCornerShape(DesignSystem.Radii.xxl),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.widget_action_title), style = MaterialTheme.typography.headlineSmall)
            Text(
                pluralStringResource(R.plurals.widget_saved_task_count, taskCount, taskCount),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
