package com.opentasker.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.opentasker.app.R
import com.opentasker.core.logging.AppLogger
import com.opentasker.core.apps.InstalledApp
import com.opentasker.core.apps.InstalledAppRepository
import com.opentasker.core.apps.InstalledAppSearch
import com.opentasker.core.apps.PackageNamePolicy
import com.opentasker.ui.theme.DesignSystem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun InstalledAppFieldInput(
    label: String,
    hint: String?,
    value: String,
    required: Boolean,
    suggestedPackage: String? = null,
    onChange: (String) -> Unit,
) {
    var pickerVisible by rememberSaveable(label) { mutableStateOf(false) }
    val invalid = value.isNotBlank() && !PackageNamePolicy.isValid(value)
    Column(verticalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.xs)) {
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            label = { Text(label) },
            isError = invalid,
            supportingText = {
                Text(
                    when {
                        invalid -> stringResource(R.string.app_picker_invalid_package)
                        required && value.isBlank() -> stringResource(R.string.label_required)
                        else -> hint.orEmpty()
                    },
                )
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedButton(onClick = { pickerVisible = true }, modifier = Modifier.fillMaxWidth()) {
            Icon(
                Icons.Filled.Search,
                contentDescription = stringResource(R.string.app_picker_browse),
                modifier = Modifier.size(18.dp),
            )
            Text(stringResource(R.string.app_picker_browse))
        }
    }
    if (pickerVisible) {
        InstalledAppPickerDialog(
            suggestedPackage = suggestedPackage,
            onDismiss = { pickerVisible = false },
            onSelect = { app ->
                onChange(app.packageName)
                pickerVisible = false
            },
        )
    }
}

@Composable
internal fun InstalledAppPickerDialog(
    suggestedPackage: String? = null,
    appsOverride: List<InstalledApp>? = null,
    onDismiss: () -> Unit,
    onSelect: (InstalledApp) -> Unit,
) {
    val appContext = LocalContext.current.applicationContext
    var query by rememberSaveable { mutableStateOf("") }
    val loadState by produceState(
        initialValue = InstalledAppLoadState(
            apps = appsOverride.orEmpty(),
            loading = appsOverride == null,
        ),
        appContext,
        appsOverride,
    ) {
        if (appsOverride == null) {
            value = withContext(Dispatchers.IO) {
                runCatching { InstalledAppRepository(appContext).loadVisibleApps() }
                    .fold(
                        onSuccess = { InstalledAppLoadState(apps = it) },
                        onFailure = {
                            AppLogger.warn("OpenTasker.InstalledAppPicker", "Installed-app query failed", it)
                            // Previously composed "Operation failed" into this sentence, which
                            // read "Installed apps could not be loaded: Operation failed".
                            InstalledAppLoadState(
                                error = appContext.getString(R.string.app_picker_load_error),
                            )
                        },
                    )
            }
        }
    }
    val results = remember(loadState.apps, query) { InstalledAppSearch.filter(loadState.apps, query) }
    val suggested = suggestedPackage
        ?.trim()
        ?.takeIf(PackageNamePolicy::isValid)
        ?.let { packageName ->
            loadState.apps.firstOrNull { it.packageName == packageName }
                ?: InstalledApp(packageName = packageName, label = packageName)
        }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.app_picker_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.md),
            ) {
                Text(
                    stringResource(R.string.app_picker_visibility_notice),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                suggested?.let { app ->
                    OutlinedButton(onClick = { onSelect(app) }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.app_picker_use_observed, app.label, app.packageName))
                    }
                }
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text(stringResource(R.string.app_picker_search)) },
                    leadingIcon = {
                        Icon(
                            Icons.Filled.Search,
                            contentDescription = stringResource(R.string.app_picker_search),
                        )
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                when {
                    loadState.loading -> CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
                    loadState.error != null -> Text(
                        stringResource(R.string.app_picker_load_error, loadState.error.orEmpty()),
                        color = MaterialTheme.colorScheme.error,
                    )
                    results.isEmpty() -> Text(stringResource(R.string.app_picker_empty))
                    else -> LazyColumn(
                        modifier = Modifier.heightIn(max = 360.dp),
                        verticalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.sm),
                    ) {
                        items(results, key = InstalledApp::packageName) { app ->
                            InstalledAppRow(app = app, onClick = { onSelect(app) })
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun InstalledAppRow(app: InstalledApp, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.58f)),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.44f)),
        shape = RoundedCornerShape(14.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.md),
        ) {
            InstalledAppIcon(app)
            Column(Modifier.weight(1f)) {
                Text(app.label, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    app.packageName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun InstalledAppIcon(app: InstalledApp) {
    val appContext = LocalContext.current.applicationContext
    val image by produceState<ImageBitmap?>(initialValue = null, app.packageName, appContext) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                appContext.packageManager.getApplicationIcon(app.packageName)
                    .toBitmap(width = 48, height = 48)
                    .asImageBitmap()
            }.getOrNull()
        }
    }
    val iconModifier = Modifier
        .size(44.dp)
        .clip(RoundedCornerShape(10.dp))
    if (image != null) {
        Image(
            bitmap = checkNotNull(image),
            contentDescription = stringResource(R.string.app_picker_icon_description, app.label),
            modifier = iconModifier,
        )
    } else {
        Surface(modifier = iconModifier, color = MaterialTheme.colorScheme.primaryContainer) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    app.label.firstOrNull()?.uppercase().orEmpty(),
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
    }
}

private data class InstalledAppLoadState(
    val apps: List<InstalledApp> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
)
