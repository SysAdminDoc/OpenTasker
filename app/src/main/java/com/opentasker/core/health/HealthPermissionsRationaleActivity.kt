package com.opentasker.core.health

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.opentasker.app.R
import com.opentasker.ui.theme.DesignSystem
import com.opentasker.ui.theme.OpenTaskerTheme
import com.opentasker.ui.theme.ThemePreference

/**
 * The privacy page Health Connect opens from its permission dialog and its app list. Health
 * Connect won't show the dialog for an app that doesn't have one.
 */
class HealthPermissionsRationaleActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // See LocaleSettingEditActivity: without this a Light-theme user gets dark icons on black bars.
        enableEdgeToEdge()
        setContent {
            val themeMode by ThemePreference.observe(this).collectAsState(initial = null)
            val resolvedTheme = themeMode ?: return@setContent
            OpenTaskerTheme(resolvedTheme) {
                Scaffold(
                    topBar = { TopAppBar(title = { Text(stringResource(R.string.health_rationale_title)) }) },
                ) { padding ->
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding)
                            .verticalScroll(rememberScrollState())
                            .padding(DesignSystem.Screen.horizontalPadding),
                        verticalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.md),
                    ) {
                        Text(
                            stringResource(R.string.health_rationale_body),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
            }
        }
    }
}
