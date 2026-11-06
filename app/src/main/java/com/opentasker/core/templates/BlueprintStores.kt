package com.opentasker.core.templates

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/** A durable link between a blueprint definition and the profile/task it created. */
@Serializable
data class BlueprintInstallation(
    val blueprintId: String,
    val blueprintVersion: Int,
    val profileId: Long,
    val taskId: Long,
    val inputValues: Map<String, String> = emptyMap(),
    val exitTaskId: Long? = null,
)

private const val MAX_STORED_BLUEPRINTS = 128
private const val MAX_STORED_INSTALLATIONS = 256

private val blueprintStoreJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}

@Serializable
private data class BlueprintDefinitionsDocument(
    val blueprints: List<AutomationBlueprint> = emptyList(),
)

@Serializable
private data class BlueprintInstallationsDocument(
    val installations: List<BlueprintInstallation> = emptyList(),
)

/** Local definitions imported from bundles. Built-in definitions remain code-owned and immutable. */
class BlueprintCatalogStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun imported(): List<AutomationBlueprint> = runCatching {
        blueprintStoreJson.decodeFromString<BlueprintDefinitionsDocument>(
            preferences.getString(KEY_DEFINITIONS, null).orEmpty(),
        ).blueprints
    }.getOrDefault(emptyList())
        .take(MAX_STORED_BLUEPRINTS)

    fun available(): List<AutomationBlueprint> = (ProfileTemplateCatalog.all + imported())
        .associateBy { it.id }
        .values
        .sortedWith(compareBy<AutomationBlueprint> { it.category.lowercase() }.thenBy { it.title.lowercase() }.thenBy { it.id })

    fun resolve(id: String): AutomationBlueprint? = available().firstOrNull { it.id == id }

    fun merge(definitions: List<AutomationBlueprint>) {
        if (definitions.isEmpty()) return
        val merged = (imported() + definitions)
            .associateBy { it.id }
            .values
            .sortedWith(compareBy<AutomationBlueprint> { it.id })
            .takeLast(MAX_STORED_BLUEPRINTS)
        preferences.edit()
            .putString(KEY_DEFINITIONS, blueprintStoreJson.encodeToString(BlueprintDefinitionsDocument(merged)))
            .apply()
    }

    private companion object {
        const val PREFERENCES = "opentasker_blueprints"
        const val KEY_DEFINITIONS = "definitions"
    }
}

/** SharedPreferences-backed installation links used only for review/update planning. */
class BlueprintInstallationStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun load(): List<BlueprintInstallation> = runCatching {
        blueprintStoreJson.decodeFromString<BlueprintInstallationsDocument>(
            preferences.getString(KEY_INSTALLATIONS, null).orEmpty(),
        ).installations
    }.getOrDefault(emptyList())
        .filter { it.blueprintId.isNotBlank() && it.blueprintVersion > 0 && it.profileId > 0L && it.taskId > 0L }
        .take(MAX_STORED_INSTALLATIONS)

    fun forBlueprint(blueprintId: String): List<BlueprintInstallation> =
        load().filter { it.blueprintId == blueprintId }

    fun record(installation: BlueprintInstallation) {
        require(installation.blueprintId.isNotBlank()) { "Blueprint id cannot be blank" }
        require(installation.blueprintVersion > 0) { "Blueprint version must be positive" }
        require(installation.profileId > 0L && installation.taskId > 0L) { "Blueprint records need durable ids" }
        val updated = (load().filterNot { it.profileId == installation.profileId } + installation)
            .sortedWith(compareBy<BlueprintInstallation> { it.blueprintId }.thenBy { it.profileId })
            .takeLast(MAX_STORED_INSTALLATIONS)
        preferences.edit()
            .putString(KEY_INSTALLATIONS, blueprintStoreJson.encodeToString(BlueprintInstallationsDocument(updated)))
            .apply()
    }

    private companion object {
        const val PREFERENCES = "opentasker_blueprint_installations"
        const val KEY_INSTALLATIONS = "installations"
    }
}
