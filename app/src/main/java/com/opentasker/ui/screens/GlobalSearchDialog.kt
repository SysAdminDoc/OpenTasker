package com.opentasker.ui.screens

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import com.opentasker.app.R
import com.opentasker.core.model.Profile
import com.opentasker.core.model.Scene
import com.opentasker.core.model.Task
import com.opentasker.core.model.Variable
import com.opentasker.core.search.GlobalSearchResult
import com.opentasker.core.search.GlobalSearchResultKind
import com.opentasker.core.search.searchGlobalEntities

@Composable
fun GlobalSearchDialog(
    profiles: List<Profile>,
    tasks: List<Task>,
    variables: List<Variable>,
    scenes: List<Scene>,
    onDismiss: () -> Unit,
    onSelect: (GlobalSearchResult) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val results = remember(query, profiles, tasks, variables, scenes) {
        searchGlobalEntities(query, profiles, tasks, variables, scenes)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.global_search_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(stringResource(R.string.global_search_label)) },
                    placeholder = { Text(stringResource(R.string.global_search_hint)) },
                    leadingIcon = {
                        Icon(
                            Icons.Filled.Search,
                            contentDescription = stringResource(R.string.global_search_content_description),
                        )
                    },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { query = "" }) {
                                Icon(
                                    Icons.Filled.Clear,
                                    contentDescription = stringResource(R.string.global_search_clear),
                                )
                            }
                        }
                    },
                )
                // Polite live region: this line is the only feedback that a query matched
                // nothing, and without it a screen-reader user typing heard silence.
                val announce = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                when {
                    query.isBlank() -> Text(
                        stringResource(R.string.global_search_prompt),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = announce,
                    )

                    results.isEmpty() -> Text(
                        stringResource(R.string.global_search_no_results),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = announce,
                    )

                    else -> LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 420.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(
                            items = results,
                            key = { result ->
                                "${result.kind}:${result.entityId}:${result.actionIndex}:${result.variableName}"
                            },
                        ) { result ->
                            GlobalSearchResultCard(result = result, onClick = { onSelect(result) })
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
        },
    )
}

@Composable
private fun GlobalSearchResultCard(result: GlobalSearchResult, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        // surfaceVariant equals the dialog container in every static scheme, so without a border
        // these results had no visible boundary at all.
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Text(
                text = stringResource(result.kind.labelRes()),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = result.title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
            )
            Text(
                text = result.detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

private fun GlobalSearchResultKind.labelRes(): Int = when (this) {
    GlobalSearchResultKind.PROFILE -> R.string.global_search_result_profile
    GlobalSearchResultKind.TASK -> R.string.global_search_result_task
    GlobalSearchResultKind.ACTION -> R.string.global_search_result_action
    GlobalSearchResultKind.VARIABLE -> R.string.global_search_result_variable
    GlobalSearchResultKind.SCENE -> R.string.global_search_result_scene
}
