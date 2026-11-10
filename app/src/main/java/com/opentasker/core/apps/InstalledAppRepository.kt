package com.opentasker.core.apps

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import com.opentasker.core.plugins.locale.LocalePluginContract

data class InstalledApp(
    val packageName: String,
    val label: String,
)

/** Android package names accepted by package-bearing contexts, actions, and plugin requests. */
object PackageNamePolicy {
    private val packagePattern = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")

    fun isValid(packageName: String): Boolean = packagePattern.matches(packageName.trim())

    /** An App context can watch several apps, comma-separated, which is how a Tasker import stores them. */
    fun isValidList(packageNames: String): Boolean {
        val entries = packageNames.split(',').map(String::trim).filter(String::isNotEmpty)
        return entries.isNotEmpty() && entries.all(::isValid)
    }
}

/** Pure search/sort contract shared by the picker and its host-side tests. */
object InstalledAppSearch {
    fun filter(apps: List<InstalledApp>, query: String): List<InstalledApp> {
        val needle = query.trim()
        return apps.asSequence()
            .filter { app ->
                needle.isBlank() ||
                    app.label.contains(needle, ignoreCase = true) ||
                    app.packageName.contains(needle, ignoreCase = true)
            }
            .distinctBy(InstalledApp::packageName)
            .sortedWith(compareBy<InstalledApp> { it.label.lowercase() }.thenBy { it.packageName })
            .toList()
    }
}

/**
 * Lists only applications Android makes visible to OpenTasker. The manifest declares launcher and
 * Locale-plugin intent queries, never QUERY_ALL_PACKAGES, so this naturally respects scoped
 * package visibility while still covering every package-bearing editor.
 */
class InstalledAppRepository(context: Context) {
    private val packageManager = context.applicationContext.packageManager

    fun loadVisibleApps(): List<InstalledApp> {
        val applications = linkedMapOf<String, ApplicationInfo>()
        installedApplications().forEach { info ->
            if (info.enabled) applications[info.packageName] = info
        }
        visibleIntentQueries().forEach { intent ->
            queryIntentActivities(intent).mapNotNull { info -> info.activityInfo?.applicationInfo }.forEach { applicationInfo ->
                if (applicationInfo.enabled) applications[applicationInfo.packageName] = applicationInfo
            }
        }
        return InstalledAppSearch.filter(
            applications.values.map { info ->
                InstalledApp(
                    packageName = info.packageName,
                    label = info.loadLabel(packageManager).toString().ifBlank { info.packageName },
                )
            },
            query = "",
        )
    }

    private fun visibleIntentQueries(): List<Intent> = buildList {
        add(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER))
        LocalePluginContract.visiblePluginIntentActions.forEach { action -> add(Intent(action)) }
    }

    @Suppress("DEPRECATION")
    private fun installedApplications(): List<ApplicationInfo> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
        } else {
            packageManager.getInstalledApplications(0)
        }

    @Suppress("DEPRECATION")
    private fun queryIntentActivities(intent: Intent) =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            packageManager.queryIntentActivities(intent, 0)
        }
}
