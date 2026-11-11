package com.opentasker.ui.screens

import android.app.Activity
import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.opentasker.app.R
import com.opentasker.core.apps.PackageNamePolicy
import com.opentasker.core.plugins.locale.LocalePluginConfigurationResultParser
import com.opentasker.core.plugins.locale.LocalePluginHost

/**
 * Opens a Locale plugin's own settings screen and writes what it saves into the bundle and blurb
 * fields. Without it the only way to set up a real plugin was typing its bundle keys by hand.
 */
@Composable
internal fun LocalePluginConfigureButton(
    packageName: String,
    condition: Boolean,
    bundleJson: String,
    blurb: String,
    onConfigured: (bundleJson: String, blurb: String) -> Unit,
) {
    val context = LocalContext.current
    var problem by remember { mutableStateOf<String?>(null) }
    val failedTemplate = stringResource(R.string.locale_plugin_configure_failed)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        // Backing out of the plugin's screen keeps what was there, and isn't an error.
        if (result.resultCode == Activity.RESULT_CANCELED) return@rememberLauncherForActivityResult
        problem = try {
            val configured = LocalePluginConfigurationResultParser.parse(result.resultCode, result.data)
            onConfigured(configured.bundleJson, configured.blurb)
            null
        } catch (e: IllegalArgumentException) {
            failedTemplate.format(e.message.orEmpty())
        } catch (e: IllegalStateException) {
            failedTemplate.format(e.message.orEmpty())
        }
    }
    val target = packageName.trim()
    val missing = stringResource(R.string.locale_plugin_configure_missing)
    Column(Modifier.fillMaxWidth()) {
        OutlinedButton(
            enabled = PackageNamePolicy.isValid(target),
            onClick = {
                val edit = LocalePluginHost(context.applicationContext)
                    .configurationIntent(target, condition, bundleJson, blurb)
                val intent = edit.intent
                problem = if (!edit.success || intent == null) {
                    missing
                } else {
                    try {
                        launcher.launch(intent)
                        null
                    } catch (_: ActivityNotFoundException) {
                        missing
                    } catch (e: SecurityException) {
                        failedTemplate.format(e.message.orEmpty())
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.locale_plugin_configure))
        }
        problem?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}
