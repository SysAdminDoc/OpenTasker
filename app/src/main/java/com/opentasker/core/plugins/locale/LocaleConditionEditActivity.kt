package com.opentasker.core.plugins.locale

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.opentasker.app.OpenTaskerApp_NoHilt
import com.opentasker.app.R
import com.opentasker.core.model.ContextType
import com.opentasker.ui.theme.DesignSystem
import com.opentasker.ui.theme.OpenTaskerTheme
import com.opentasker.ui.theme.ThemeMode
import com.opentasker.ui.theme.ThemePreference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

class LocaleConditionEditActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Without this the window keeps Theme.OpenTasker's opaque black bars while the
        // shared theme sets light status-bar icons, so a Light-theme user sees dark icons
        // on black - invisible - on Android 8 through 14. MainActivity already does this.
        enableEdgeToEdge()
        val db = runCatching { OpenTaskerApp_NoHilt.db }.getOrNull()
        if (db == null) {
            setResult(RESULT_CANCELED)
            finish()
            return
        }

        val profilesFlow = db.profileDao().getAllAsFlow().map { entities ->
            entities.mapNotNull { entity ->
                val decoded = entity.toDomainDecodeResult()
                decoded.issue?.let { return@mapNotNull null }
                LocaleConditionProfileItem(
                    id = decoded.value.id,
                    name = decoded.value.name,
                    contexts = decoded.value.contexts.mapIndexed { index, context ->
                        LocaleConditionContextItem(
                            profileId = decoded.value.id,
                            profileName = decoded.value.name,
                            index = index,
                            label = contextLabel(context.type),
                        )
                    },
                )
            }.sortedBy { it.name.lowercase() }
        }
        val variablesFlow = flow {
            emit(
                db.variableDao().getAll()
                    .filterNot { it.isSecret }
                    .map { LocaleConditionVariableItem(it.name, it.projectId) }
                    .sortedWith(compareBy<LocaleConditionVariableItem> { it.name.lowercase() }.thenBy { it.projectId }),
            )
        }.flowOn(Dispatchers.IO)

        setContent {
            val themeMode by ThemePreference.observe(this).collectAsState(initial = ThemeMode.Amoled)
            val profiles by profilesFlow.collectAsState(initial = emptyList())
            val variables by variablesFlow.collectAsState(initial = emptyList())
            OpenTaskerTheme(themeMode) {
                LocaleConditionEditor(
                    profiles = profiles,
                    variables = variables,
                    onIssueGrant = { variableKey -> LocaleConditionGrantStore(this).issue(variableKey) },
                    onConfigured = { values, blurb ->
                        val resultIntent = Intent().apply {
                            putExtra(LocalePluginContract.EXTRA_BUNDLE, LocalePluginBundleCodec.toBundle(values))
                            putExtra(LocalePluginContract.EXTRA_STRING_BLURB, blurb.take(120))
                        }
                        setResult(RESULT_OK, resultIntent)
                        finish()
                    },
                )
            }
        }
    }
}

private data class LocaleConditionProfileItem(
    val id: Long,
    val name: String,
    val contexts: List<LocaleConditionContextItem>,
)

private data class LocaleConditionContextItem(
    val profileId: Long,
    val profileName: String,
    val index: Int,
    val label: String,
)

private data class LocaleConditionVariableItem(
    val name: String,
    val projectId: Long,
)

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun LocaleConditionEditor(
    profiles: List<LocaleConditionProfileItem>,
    variables: List<LocaleConditionVariableItem>,
    onIssueGrant: (String) -> String,
    onConfigured: (Map<String, String>, String) -> Unit,
) {
    var selectedVariable by remember { mutableStateOf<LocaleConditionVariableItem?>(null) }
    var selectedOperator by remember { mutableStateOf(LocaleConditionOperator.EQUALS) }
    var expectedValue by remember { mutableStateOf("") }
    val contexts = profiles.flatMap { it.contexts }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.locale_condition_edit_title)) }) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(DesignSystem.Screen.horizontalPadding),
            verticalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.sm),
        ) {
            item { ConditionSectionTitle(stringResource(R.string.locale_condition_profiles)) }
            if (profiles.isEmpty()) {
                item { EmptyConditionText(stringResource(R.string.locale_condition_no_profiles)) }
            } else {
                items(profiles, key = { "profile:${it.id}" }) { profile ->
                    ConditionChoiceCard(
                        title = profile.name,
                        detail = stringResource(R.string.locale_condition_profile_active),
                        onClick = {
                            onConfigured(
                                LocaleConditionTarget.profileActive(
                                    profile.id,
                                    profile.name,
                                    onIssueGrant(LocaleConditionGrantStore.profileKey(profile.id)),
                                ),
                                "Profile active: ${profile.name}",
                            )
                        },
                    )
                }
            }

            item { ConditionSectionTitle(stringResource(R.string.locale_condition_contexts)) }
            if (contexts.isEmpty()) {
                item { EmptyConditionText(stringResource(R.string.locale_condition_no_contexts)) }
            } else {
                items(contexts, key = { "context:${it.profileId}:${it.index}" }) { context ->
                    ConditionChoiceCard(
                        title = "${context.profileName} · ${context.label}",
                        detail = stringResource(R.string.locale_condition_context_satisfied),
                        onClick = {
                            onConfigured(
                                LocaleConditionTarget.contextSatisfied(
                                    context.profileId,
                                    context.profileName,
                                    context.index,
                                    context.label,
                                    onIssueGrant(
                                        LocaleConditionGrantStore.contextKey(context.profileId, context.index),
                                    ),
                                ),
                                "Context satisfied: ${context.profileName} #${context.index + 1}",
                            )
                        },
                    )
                }
            }

            item { ConditionSectionTitle(stringResource(R.string.locale_condition_variables)) }
            item {
                Text(
                    stringResource(R.string.locale_condition_variable_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (variables.isEmpty()) {
                item { EmptyConditionText(stringResource(R.string.locale_condition_no_variables)) }
            } else {
                items(variables, key = { "variable:${it.projectId}:${it.name}" }) { variable ->
                    ConditionChoiceCard(
                        title = variable.name,
                        detail = stringResource(R.string.locale_condition_variable_choose),
                        onClick = {
                            selectedVariable = variable
                            expectedValue = ""
                        },
                    )
                }
            }
            selectedVariable?.let { variable ->
                item(key = "variable-editor:${variable.projectId}:${variable.name}") {
                    VariableComparisonEditor(
                        variable = variable,
                        operator = selectedOperator,
                        expectedValue = expectedValue,
                        onOperatorChanged = { selectedOperator = it },
                        onExpectedValueChanged = { expectedValue = it },
                        onSave = {
                            runCatching {
                                LocaleConditionTarget.variableCompare(
                                    variable.name,
                                    variable.projectId,
                                    selectedOperator,
                                    expectedValue,
                                    // Choosing the variable here is the only place the user
                                    // authorizes the exported receiver to report on it.
                                    onIssueGrant(
                                        LocaleConditionGrantStore.comparisonKey(
                                            variable.projectId,
                                            variable.name,
                                            selectedOperator,
                                            expectedValue,
                                        ),
                                    ),
                                )
                            }.onSuccess { values ->
                                onConfigured(
                                    values,
                                    "${variable.name} ${selectedOperator.wireName} configured value",
                                )
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ConditionSectionTitle(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = DesignSystem.Spacing.sm),
    )
}

@Composable
private fun EmptyConditionText(text: String) {
    Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun ConditionChoiceCard(title: String, detail: String, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(DesignSystem.Radii.lg),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(DesignSystem.Screen.horizontalPadding)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun VariableComparisonEditor(
    variable: LocaleConditionVariableItem,
    operator: LocaleConditionOperator,
    expectedValue: String,
    onOperatorChanged: (LocaleConditionOperator) -> Unit,
    onExpectedValueChanged: (String) -> Unit,
    onSave: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = DesignSystem.Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.sm),
    ) {
        Text(stringResource(R.string.locale_condition_compare_title, variable.name), style = MaterialTheme.typography.titleSmall)
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.xs),
        ) {
            LocaleConditionOperator.entries.forEach { candidate ->
                FilterChip(
                    selected = candidate == operator,
                    onClick = { onOperatorChanged(candidate) },
                    label = { Text(operatorLabel(candidate)) },
                )
            }
        }
        OutlinedTextField(
            value = expectedValue,
            onValueChange = onExpectedValueChanged,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.locale_condition_expected_label)) },
            singleLine = true,
        )
        Button(onClick = onSave, enabled = expectedValue.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.locale_condition_save))
        }
        TextButton(onClick = { onOperatorChanged(LocaleConditionOperator.EQUALS); onExpectedValueChanged("") }) {
            Text(stringResource(R.string.locale_condition_clear_selection))
        }
    }
}

@Composable
private fun operatorLabel(operator: LocaleConditionOperator): String = stringResource(
    when (operator) {
        LocaleConditionOperator.EQUALS -> R.string.locale_condition_operator_equals
        LocaleConditionOperator.NOT_EQUALS -> R.string.locale_condition_operator_not_equals
        LocaleConditionOperator.CONTAINS -> R.string.locale_condition_operator_contains
        LocaleConditionOperator.STARTS_WITH -> R.string.locale_condition_operator_starts_with
        LocaleConditionOperator.ENDS_WITH -> R.string.locale_condition_operator_ends_with
    },
)

private fun contextLabel(type: ContextType): String = when (type) {
    ContextType.APPLICATION -> "Application"
    ContextType.TIME -> "Time"
    ContextType.DAY -> "Day"
    ContextType.LOCATION -> "Location"
    ContextType.STATE -> "Device state"
    ContextType.EVENT -> "Event"
    ContextType.PLUGIN -> "Plugin condition"
}
