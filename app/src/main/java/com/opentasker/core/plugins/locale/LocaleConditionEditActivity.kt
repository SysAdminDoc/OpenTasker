package com.opentasker.core.plugins.locale

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.opentasker.app.OpenTaskerApp_NoHilt
import com.opentasker.app.R
import com.opentasker.core.logging.AppLogger
import com.opentasker.ui.theme.DesignSystem
import com.opentasker.ui.theme.OpenTaskerTheme
import com.opentasker.ui.screens.FullScreenContentLoading
import com.opentasker.ui.screens.contextTitleRes
import com.opentasker.ui.theme.ThemePreference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
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
        // Awaited on first collection rather than in onCreate, where the blocking getter could hold
        // the main thread for up to 30 seconds after a staged restore; see MainActivity.
        val profilesFlow = flow { emitAll(OpenTaskerApp_NoHilt.awaitDb().profileDao().getAllAsFlow()) }.map { entities ->
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
                            label = getString(contextTitleRes(context.type)),
                        )
                    },
                )
            }.sortedBy { it.name.lowercase() }
        }.catch { closeWithoutDatabase(it) }
        val variablesFlow = flow {
            emit(
                OpenTaskerApp_NoHilt.awaitDb().variableDao().getAll()
                    .filterNot { it.isSecret }
                    .map { LocaleConditionVariableItem(it.name, it.projectId) }
                    .sortedWith(compareBy<LocaleConditionVariableItem> { it.name.lowercase() }.thenBy { it.projectId }),
            )
        }.flowOn(Dispatchers.IO).catch { closeWithoutDatabase(it) }

        setContent {
            val themeMode by ThemePreference.observe(this).collectAsState(initial = null)
            val resolvedTheme = themeMode ?: return@setContent
            val profiles by profilesFlow.collectAsState(initial = null)
            val variables by variablesFlow.collectAsState(initial = null)
            OpenTaskerTheme(resolvedTheme) {
                val loadedProfiles = profiles
                val loadedVariables = variables
                if (loadedProfiles == null || loadedVariables == null) {
                    FullScreenContentLoading()
                    return@OpenTaskerTheme
                }
                LocaleConditionEditor(
                    profiles = loadedProfiles,
                    variables = loadedVariables,
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

    private fun closeWithoutDatabase(error: Throwable) {
        AppLogger.warn("LocaleConditionEdit", "Closed the condition picker without a database", error)
        setResult(RESULT_CANCELED)
        finish()
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
    var selectedVariableName by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedVariableProjectId by rememberSaveable { mutableLongStateOf(0L) }
    var selectedOperator by rememberSaveable { mutableStateOf(LocaleConditionOperator.EQUALS) }
    var expectedValue by rememberSaveable { mutableStateOf("") }
    var saveFailed by rememberSaveable { mutableStateOf(false) }
    var revealEditor by remember { mutableStateOf(false) }
    val selectedVariable = variables.firstOrNull {
        it.name == selectedVariableName && it.projectId == selectedVariableProjectId
    }
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
                    val blurb = stringResource(R.string.locale_condition_blurb_profile, profile.name)
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
                                blurb,
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
                    val blurb = stringResource(R.string.locale_condition_blurb_context, context.profileName, context.index + 1)
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
                                blurb,
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
                    Column(verticalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.sm)) {
                        ConditionChoiceCard(
                            title = variable.name,
                            detail = stringResource(R.string.locale_condition_variable_choose),
                            onClick = {
                                selectedVariableName = variable.name
                                selectedVariableProjectId = variable.projectId
                                expectedValue = ""
                                saveFailed = false
                                revealEditor = true
                            },
                        )
                        // Right under the card: at the end of a long list the form appeared off
                        // screen and tapping a variable looked like it had done nothing.
                        if (variable == selectedVariable) {
                            val blurb = stringResource(
                                R.string.locale_condition_blurb_variable,
                                variable.name,
                                operatorLabel(selectedOperator),
                            )
                            VariableComparisonEditor(
                                variable = variable,
                                operator = selectedOperator,
                                expectedValue = expectedValue,
                                saveFailed = saveFailed,
                                reveal = revealEditor,
                                onRevealed = { revealEditor = false },
                                onOperatorChanged = {
                                    selectedOperator = it
                                    saveFailed = false
                                },
                                onExpectedValueChanged = {
                                    expectedValue = it
                                    saveFailed = false
                                },
                                onClear = {
                                    selectedVariableName = null
                                    selectedOperator = LocaleConditionOperator.EQUALS
                                    expectedValue = ""
                                    saveFailed = false
                                },
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
                                        onConfigured(values, blurb)
                                    }.onFailure { saveFailed = true }
                                },
                            )
                        }
                    }
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
    saveFailed: Boolean,
    reveal: Boolean,
    onRevealed: () -> Unit,
    onOperatorChanged: (LocaleConditionOperator) -> Unit,
    onExpectedValueChanged: (String) -> Unit,
    onClear: () -> Unit,
    onSave: () -> Unit,
) {
    val valueTooLong = expectedValue.toByteArray(Charsets.UTF_8).size > LocaleConditionTarget.MAX_EXPECTED_VALUE_BYTES
    val errorRes = when {
        valueTooLong -> R.string.locale_condition_value_too_long
        saveFailed -> R.string.locale_condition_save_failed
        else -> null
    }
    val revealRequester = remember { BringIntoViewRequester() }
    LaunchedEffect(reveal) {
        if (reveal) {
            revealRequester.bringIntoView()
            onRevealed()
        }
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .bringIntoViewRequester(revealRequester)
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
            supportingText = if (errorRes != null) {
                { Text(stringResource(errorRes)) }
            } else {
                null
            },
            isError = errorRes != null,
            singleLine = true,
        )
        Button(onClick = onSave, enabled = expectedValue.isNotEmpty() && !valueTooLong, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.locale_condition_save))
        }
        TextButton(onClick = onClear) {
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
