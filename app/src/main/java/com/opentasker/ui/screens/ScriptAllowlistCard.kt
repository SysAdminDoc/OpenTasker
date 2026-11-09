package com.opentasker.ui.screens

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.opentasker.ui.theme.DesignSystem
import com.opentasker.app.R
import com.opentasker.core.scripting.ApprovedScript
import com.opentasker.core.scripting.ScriptAllowlistSaveResult
import com.opentasker.core.scripting.ScriptAllowlistStore
import com.opentasker.core.scripting.TermuxScriptPolicy
import com.opentasker.ui.utils.expandCollapseToggle
import androidx.compose.runtime.produceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The wording that differs between the script allowlists. The rest of the card reads the same. */
private data class ScriptAllowlistText(
    @param:StringRes val title: Int,
    @param:StringRes val body: Int,
    @param:StringRes val pathHint: Int,
    @param:StringRes val pathError: Int,
    @param:StringRes val empty: Int,
)

@Composable
internal fun TermuxScriptAllowlistCard(onMessage: (String) -> Unit) {
    ScriptAllowlistCard(
        text = ScriptAllowlistText(
            title = R.string.setup_termux_allowlist_title,
            body = R.string.setup_termux_allowlist_body,
            pathHint = R.string.setup_termux_script_path_hint,
            pathError = R.string.setup_termux_script_path_error,
            empty = R.string.setup_termux_allowlist_empty,
        ),
        openStore = { ScriptAllowlistStore.termux(it) },
        onMessage = onMessage,
    )
}

@Composable
internal fun ShizukuScriptAllowlistCard(onMessage: (String) -> Unit) {
    ScriptAllowlistCard(
        text = ScriptAllowlistText(
            title = R.string.setup_shizuku_allowlist_title,
            body = R.string.setup_shizuku_allowlist_body,
            pathHint = R.string.setup_shizuku_script_path_hint,
            pathError = R.string.setup_shizuku_script_path_error,
            empty = R.string.setup_shizuku_allowlist_empty,
        ),
        openStore = { ScriptAllowlistStore.shizuku(it) },
        onMessage = onMessage,
    )
}

@Composable
private fun ScriptAllowlistCard(
    text: ScriptAllowlistText,
    openStore: (Context) -> ScriptAllowlistStore,
    onMessage: (String) -> Unit,
) {
    val context = LocalContext.current
    val store = remember(context) { openStore(context) }
    var executable by rememberSaveable { mutableStateOf("") }
    var sha256 by rememberSaveable { mutableStateOf("") }
    var expanded by rememberSaveable { mutableStateOf(false) }
    var revision by remember { mutableIntStateOf(0) }
    // SharedPreferences first-load is disk work; keep it off the composition thread.
    val loadedEntries by produceState<List<ApprovedScript>?>(initialValue = null, store, revision) {
        value = withContext(Dispatchers.IO) { store.entries() }
    }
    val entries = loadedEntries.orEmpty()
    val pathValid = store.policy.normalizePath(executable) != null
    val hashValid = TermuxScriptPolicy.normalizeHash(sha256) != null
    val savedMessage = stringResource(R.string.setup_termux_script_saved)
    val invalidPathMessage = stringResource(text.pathError)
    val invalidHashMessage = stringResource(R.string.setup_termux_script_hash_error)
    val allowlistFullMessage = stringResource(R.string.setup_termux_allowlist_full)
    val revokedMessage = stringResource(R.string.setup_termux_script_revoked)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.58f)),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.46f)),
        shape = RoundedCornerShape(DesignSystem.Radii.lg),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().expandCollapseToggle(expanded) { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(stringResource(text.title), style = MaterialTheme.typography.titleSmall)
                    Text(
                        // Blank until read, or every open of Setup starts by claiming there are none.
                        if (loadedEntries == null) "" else pluralStringResource(R.plurals.setup_script_allowlist_count, entries.size, entries.size),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                // The row already reads the title and its expanded state.
                Icon(
                    if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = null,
                )
            }
            if (expanded) {
                Text(
                    stringResource(text.body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            OutlinedTextField(
                value = executable,
                onValueChange = { executable = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.setup_termux_script_path_label)) },
                placeholder = { Text(stringResource(text.pathHint)) },
                singleLine = true,
                isError = executable.isNotBlank() && !pathValid,
                supportingText = if (executable.isNotBlank() && !pathValid) {
                    { Text(stringResource(text.pathError)) }
                } else {
                    null
                },
            )
            OutlinedTextField(
                value = sha256,
                onValueChange = { sha256 = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.setup_termux_script_hash_label)) },
                placeholder = { Text(stringResource(R.string.setup_termux_script_hash_hint)) },
                singleLine = true,
                isError = sha256.isNotBlank() && !hashValid,
                supportingText = if (sha256.isNotBlank() && !hashValid) {
                    { Text(stringResource(R.string.setup_termux_script_hash_error)) }
                } else {
                    null
                },
            )
            Button(
                onClick = {
                    val result = store.approve(executable, sha256)
                    val message = when (result) {
                        ScriptAllowlistSaveResult.SAVED -> {
                            executable = ""
                            sha256 = ""
                            revision++
                            savedMessage
                        }
                        ScriptAllowlistSaveResult.INVALID_PATH -> invalidPathMessage
                        ScriptAllowlistSaveResult.INVALID_HASH -> invalidHashMessage
                        ScriptAllowlistSaveResult.FULL -> allowlistFullMessage
                    }
                    onMessage(message)
                },
                enabled = pathValid && hashValid,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text(stringResource(R.string.setup_termux_script_approve))
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
            if (entries.isEmpty()) {
                Text(
                    stringResource(text.empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                entries.forEach { entry ->
                    val deleteDescription = stringResource(R.string.setup_termux_script_delete_a11y, entry.executable)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                entry.executable,
                                style = MaterialTheme.typography.labelLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                stringResource(R.string.setup_termux_script_hash_value, entry.sha256),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(
                            onClick = {
                                store.revoke(entry.executable)
                                revision++
                                onMessage(revokedMessage)
                            },
                            modifier = Modifier.semantics { contentDescription = deleteDescription },
                        ) {
                            Text(stringResource(R.string.action_delete))
                        }
                    }
                }
            }
            }
        }
    }
}
