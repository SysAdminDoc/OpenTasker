package com.opentasker.core.plugins.locale

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.opentasker.app.OpenTaskerApp_NoHilt
import com.opentasker.app.R
import com.opentasker.ui.theme.DesignSystem
import com.opentasker.ui.theme.OpenTaskerTheme
import com.opentasker.ui.screens.ContentLoadingState
import com.opentasker.ui.theme.ThemeMode
import com.opentasker.ui.theme.ThemePreference
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.emitAll

class LocaleSettingEditActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Without this the window keeps Theme.OpenTasker's opaque black bars while the
        // shared theme sets light status-bar icons, so a Light-theme user sees dark icons
        // on black - invisible - on Android 8 through 14. MainActivity already does this.
        enableEdgeToEdge()

        val grantStore = LocaleGrantStore(this)
        // Awaited on first collection rather than in onCreate; see MainActivity.
        val tasksFlow = flow { emitAll(OpenTaskerApp_NoHilt.awaitDb().taskDao().getAllAsFlow()) }.map { tasks ->
            tasks.map { TaskPickerItem(it.id, it.name) }
        }

        setContent {
            // Honor the persisted theme like MainActivity and the widget config activity,
            // instead of defaulting to the system theme.
            val themeMode by ThemePreference.observe(this).collectAsState(initial = ThemeMode.Amoled)
            OpenTaskerTheme(themeMode) {
                val tasks by tasksFlow.collectAsState(initial = null)
                Scaffold(
                    topBar = {
                        TopAppBar(title = { Text(stringResource(R.string.locale_select_task_title)) })
                    },
                ) { padding ->
                    val loadedTasks = tasks
                    if (loadedTasks == null) {
                        ContentLoadingState(padding)
                        return@Scaffold
                    }
                    TaskPickerList(
                        tasks = loadedTasks,
                        contentPadding = padding,
                        onSelect = { item ->
                            val grant = grantStore.issue(item.id)
                            val resultBundle = LocalePluginTarget.buildResultBundle(item.id, item.name, grant)
                            val resultIntent = Intent().apply {
                                putExtra(LocalePluginContract.EXTRA_BUNDLE, resultBundle)
                                putExtra(
                                    LocalePluginContract.EXTRA_STRING_BLURB,
                                    LocalePluginTarget.buildBlurb(item.name),
                                )
                            }
                            setResult(RESULT_OK, resultIntent)
                            finish()
                        },
                    )
                }
            }
        }
    }
}

private data class TaskPickerItem(val id: Long, val name: String)

@Composable
private fun TaskPickerList(
    tasks: List<TaskPickerItem>,
    contentPadding: PaddingValues,
    onSelect: (TaskPickerItem) -> Unit,
) {
    if (tasks.isEmpty()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .padding(DesignSystem.Screen.horizontalPadding),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                "No tasks available. Create a task in OpenTasker first.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding),
        contentPadding = PaddingValues(DesignSystem.Screen.horizontalPadding),
        verticalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.sm),
    ) {
        items(tasks, key = { it.id }) { task ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelect(task) },
                shape = RoundedCornerShape(DesignSystem.Radii.lg),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Column(Modifier.padding(DesignSystem.Screen.horizontalPadding)) {
                    Text(task.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Task #${task.id}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
