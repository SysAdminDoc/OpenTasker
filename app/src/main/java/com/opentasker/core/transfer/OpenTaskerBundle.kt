package com.opentasker.core.transfer

import androidx.room.withTransaction
import com.opentasker.core.capabilities.ActionCapabilityRegistry
import com.opentasker.core.capabilities.AutomationInvariantStore
import com.opentasker.core.capabilities.AutomationPower
import com.opentasker.core.capabilities.AutomationSensitivityRegistry
import com.opentasker.core.capabilities.CapabilityLevel
import com.opentasker.core.diff.AutomationSemanticDiff
import com.opentasker.core.diff.SemanticDiffDocument
import com.opentasker.core.diagnostics.ExportRedactionPolicy
import com.opentasker.core.references.AutomationReferenceRewriter
import com.opentasker.core.references.AutomationReferenceIndex
import com.opentasker.core.model.Profile
import com.opentasker.core.model.Project
import com.opentasker.core.model.Scene
import com.opentasker.core.model.SceneElement
import com.opentasker.core.model.Task
import com.opentasker.core.model.Variable
import com.opentasker.core.model.AutomationInvariant
import com.opentasker.core.model.AutomationInvariantPolicy
import com.opentasker.core.model.DEFAULT_PROJECT_ID
import com.opentasker.core.model.VariableNamePolicy
import com.opentasker.core.model.isValidForContextCount
import com.opentasker.core.storage.AppDatabase
import com.opentasker.core.storage.ProjectEntity
import com.opentasker.core.storage.VariableRepository
import com.opentasker.core.storage.isEffectivelySecret
import com.opentasker.core.storage.toEntity
import com.opentasker.core.scenes.SceneElementConfigValidator
import com.opentasker.core.templates.AutomationBlueprint
import com.opentasker.core.templates.BlueprintCatalogStore
import com.opentasker.core.templates.BlueprintInstallationStore
import com.opentasker.core.templates.BlueprintUpdatePlanner
import com.opentasker.core.templates.BlueprintUpdateReview
import com.opentasker.core.templates.validationError
import com.opentasker.core.validation.InputValidation
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

const val OPEN_TASKER_BUNDLE_SCHEMA_VERSION = 2

/**
 * Oldest bundle schema still importable. Published in `tools/release-truth.json` and documented in
 * `docs/OPEN_JSON_BUNDLE.md`; the release gate fails when the three disagree, so dropping support
 * for a version cannot happen silently.
 */
const val MIN_SUPPORTED_OPEN_TASKER_BUNDLE_SCHEMA_VERSION = 1
private val SUPPORTED_OPEN_TASKER_BUNDLE_SCHEMAS =
    MIN_SUPPORTED_OPEN_TASKER_BUNDLE_SCHEMA_VERSION..OPEN_TASKER_BUNDLE_SCHEMA_VERSION
private fun projectVariableKey(projectId: Long, name: String): String = "$projectId:$name"
private val BLUEPRINT_ID_PATTERN = Regex("^[a-z0-9][a-z0-9._-]{0,63}$")
private val BLUEPRINT_INPUT_KEY_PATTERN = Regex("^[a-zA-Z][a-zA-Z0-9_-]{0,63}$")
internal const val BLUEPRINT_SECTION_KEY_PREFIX = "blueprint-section-"

@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
@Serializable
data class OpenTaskerBundle(
    val schemaVersion: Int = OPEN_TASKER_BUNDLE_SCHEMA_VERSION,
    val appVersion: String,
    val exportedAtEpochMs: Long,
    val metadata: BundleMetadata = BundleMetadata(),
    val projects: List<Project> = listOf(Project(DEFAULT_PROJECT_ID, "Default", 0)),
    val tasks: List<Task> = emptyList(),
    val profiles: List<Profile> = emptyList(),
    val variables: List<Variable> = emptyList(),
    val scenes: List<Scene> = emptyList(),
    /** Optional additive content; empty legacy exports stay byte-compatible with schema 2. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val blueprints: List<AutomationBlueprint> = emptyList(),
    /** Optional additive diagnostics policy; it is not consumed by the automation engine. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val invariants: List<AutomationInvariant> = emptyList(),
)

@Serializable
data class BundleMetadata(
    val name: String = "OpenTasker Export",
    val description: String = "",
    val capabilityRequirements: List<CapabilityRequirement> = emptyList(),
    val powerRequests: List<RecipePowerRequest> = emptyList(),
    val warnings: List<String> = emptyList(),
)

@Serializable
data class CapabilityRequirement(
    val actionId: String,
    val level: CapabilityLevel,
    val reason: String,
)

@Serializable
data class RecipePowerRequest(
    val taskId: Long,
    val taskName: String,
    val profileNames: List<String> = emptyList(),
    val powers: List<AutomationPower> = emptyList(),
    val actionIds: List<String> = emptyList(),
    val dataToExternalChains: List<DataToExternalChainRequest> = emptyList(),
    val unknownActionIds: List<String> = emptyList(),
)

@Serializable
data class DataToExternalChainRequest(
    val sourceActionId: String,
    val sinkActionId: String,
)

data class BundleImportPlan(
    val canImport: Boolean,
    val warnings: List<String> = emptyList(),
    val lossyWarnings: List<String> = emptyList(),
    val capabilityRequirements: List<CapabilityRequirement> = emptyList(),
    val powerRequests: List<RecipePowerRequest> = emptyList(),
    val variableConflicts: List<VariableImportConflict> = emptyList(),
    val semanticDiff: SemanticDiffDocument = SemanticDiffDocument(),
    val blueprintUpdates: List<BlueprintUpdateReview> = emptyList(),
)

enum class VariableConflictAction {
    PRESERVE_EXISTING,
    RENAME_IMPORTED,
    REPLACE_EXISTING,
}

data class VariableImportConflict(
    val name: String,
    val existingIsSecret: Boolean,
    val suggestedRename: String,
)

data class VariableConflictResolution(
    val action: VariableConflictAction,
    val renamedTo: String? = null,
)

data class BundleImportReport(
    val insertedTasks: Int,
    val insertedProfiles: Int,
    val insertedVariables: Int,
    val insertedScenes: Int,
    val warnings: List<String> = emptyList(),
    val lossyWarnings: List<String> = emptyList(),
    val importedBlueprints: Int = 0,
    val importedInvariants: Int = 0,
)

object OpenTaskerBundleCodec {
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = false
        // Be forgiving of hand-edited/shared bundles on decode (export output is unaffected):
        // allow // comments, trailing commas, and case-insensitive enum values. Unknown keys are
        // still rejected so structurally wrong bundles fail.
        allowComments = true
        allowTrailingComma = true
        decodeEnumsCaseInsensitive = true
    }
    private val schemaProbeJson = Json(json) {
        ignoreUnknownKeys = true
    }

    fun build(
        appVersion: String,
        exportedAtEpochMs: Long,
        profiles: List<Profile>,
        tasks: List<Task>,
        variables: List<Variable> = emptyList(),
        scenes: List<Scene> = emptyList(),
        projects: List<Project> = listOf(Project(DEFAULT_PROJECT_ID, "Default", 0)),
        omittedSecretVariableCount: Int = 0,
        name: String = "OpenTasker Export",
        description: String = "",
        blueprints: List<AutomationBlueprint> = emptyList(),
        invariants: List<AutomationInvariant> = emptyList(),
    ): OpenTaskerBundle {
        val sortedTasks = tasks.sortedWith(compareBy<Task> { it.name.lowercase() }.thenBy { it.id })
        // A consumed one-shot is runtime state, not portable configuration. Export its declared
        // lifetime while making the imported copy eligible to run once in the destination.
        val sortedProfiles = profiles
            .map { it.copy(lifetimeConsumed = false) }
            .sortedWith(compareBy<Profile> { it.name.lowercase() }.thenBy { it.id })
        val omittedSecretCount = omittedSecretVariableCount + variables.count { it.isSecret }
        val sortedVariables = variables
            .filterNot { it.isSecret }
            .sortedWith(compareBy<Variable> { it.projectId }.thenBy { it.name.lowercase() }.thenBy { it.name })
        val sortedScenes = scenes.sortedWith(compareBy<Scene> { it.name.lowercase() }.thenBy { it.id })
        val sortedBlueprints = blueprints
            .sortedWith(compareBy<AutomationBlueprint> { it.category.lowercase() }.thenBy { it.title.lowercase() }.thenBy { it.id })
        val sortedInvariants = AutomationInvariantPolicy.normalize(invariants)
            .sortedWith(compareBy<AutomationInvariant> { it.name.lowercase() }.thenBy { it.id })
        val sortedProjects = (projects.ifEmpty { listOf(Project(DEFAULT_PROJECT_ID, "Default", 0)) })
            .sortedWith(compareBy<Project> { it.position }.thenBy { it.name.lowercase() }.thenBy { it.id })
        val capabilityRequirements = capabilityRequirements(sortedTasks)
        val powerRequests = powerRequests(sortedTasks, sortedProfiles)
        val base = OpenTaskerBundle(
            appVersion = appVersion,
            exportedAtEpochMs = exportedAtEpochMs,
            metadata = BundleMetadata(
                name = name,
                description = description,
                warnings = if (omittedSecretCount > 0) {
                    listOf("$omittedSecretCount secret variable(s) were omitted and must be re-entered after import.")
                } else {
                    emptyList()
                },
                capabilityRequirements = capabilityRequirements,
                powerRequests = powerRequests,
            ),
            projects = sortedProjects,
            tasks = sortedTasks,
            profiles = sortedProfiles,
            variables = sortedVariables,
            scenes = sortedScenes,
            blueprints = sortedBlueprints,
            invariants = sortedInvariants,
        )
        val plan = validate(base)
        return base.copy(
            metadata = base.metadata.copy(
                capabilityRequirements = plan.capabilityRequirements,
                powerRequests = plan.powerRequests,
                warnings = base.metadata.warnings + plan.warnings + plan.lossyWarnings,
            )
        )
    }

    fun encode(bundle: OpenTaskerBundle): String {
        val sanitized = sanitizeForExport(bundle)
        require(sanitized.variables.none { it.isSecret }) {
            "Secret variable values cannot be written to an ordinary OpenTasker bundle."
        }
        return json.encodeToString(sanitized)
    }

    /** Applies the same field-aware policy used by diagnostic and Tasker XML serialization. */
    fun sanitizeForExport(
        bundle: OpenTaskerBundle,
        secretVariableNames: Set<String> = emptySet(),
        secretVariableValues: Set<String> = emptySet(),
    ): OpenTaskerBundle {
        // Passing the plaintext values, not just the names, is what lets the policy catch an
        // argument holding a literal copy of a secret. Without them the JSON export could only
        // redact arguments that referenced a secret by name or matched a generic token pattern,
        // while the Tasker XML exporter - the same policy - already redacted the literal.
        val context = ExportRedactionPolicy.Context(
            secretNames = secretVariableNames,
            secretValues = secretVariableValues,
        )
        var redactedFieldCount = 0
        val tasks = bundle.tasks.map { task ->
            task.copy(
                actions = task.actions.map { action ->
                    val sanitized = ExportRedactionPolicy.sanitizeActionArguments(action.type, action.args, context)
                    redactedFieldCount += sanitized.redactedFields.size
                    // A run-only-if guard is user text like `%Pin == 4321`, so it can hold a
                    // literal copy of a secret exactly the way an argument can. Only args were
                    // sanitized here, while the Tasker XML exporter - the same policy - already
                    // refused to write such a guard. A redacted guard can no longer match, so the
                    // action is skipped after import rather than running unguarded.
                    val guard = redactExportedText(action.condition, context)
                    val label = redactExportedText(action.label, context)
                    if (guard.wasRedacted) redactedFieldCount++
                    if (label.wasRedacted) redactedFieldCount++
                    action.copy(args = sanitized.args, condition = guard.value, label = label.value)
                },
            )
        }
        if (redactedFieldCount == 0) return bundle.copy(tasks = tasks)
        return bundle.copy(
            tasks = tasks,
            metadata = bundle.metadata.copy(
                warnings = bundle.metadata.warnings + ExportRedactionPolicy.SENSITIVE_ACTION_WARNING,
            ),
        )
    }

    private class ExportedText(val value: String?, val wasRedacted: Boolean)

    /**
     * Redacts a free-text action field that holds no argument semantics.
     *
     * Matching on a literal secret *value* only. A guard that names a secret (`%ApiKey == is_set`)
     * leaks nothing, because the bundle already carries that variable's name with its value
     * deliberately omitted, and redacting it would break a working guard. Running these fields
     * through the full `redactText` was worse than doing nothing: its URL, Authorization and
     * `key=value` patterns fire with no secrets configured at all, so an ordinary label like
     * "Set config key=abc123" was mangled and the export warned about a secret nobody had.
     */
    private fun redactExportedText(
        value: String?,
        context: ExportRedactionPolicy.Context,
    ): ExportedText {
        if (value.isNullOrBlank()) return ExportedText(value, wasRedacted = false)
        // ignoreCase for the same reason redactText matches that way: a secret retyped with
        // different capitalisation is the same credential, and a case-sensitive contains() let one
        // through into the bundle, the paste text and a shared profile.
        val carriesSecret = context.secretValues.any { it.isNotEmpty() && value.contains(it, ignoreCase = true) }
        if (!carriesSecret) return ExportedText(value, wasRedacted = false)
        // The whole field goes, not just the secret inside it. Substituting in place looked
        // friendlier and was unsafe: `%Pin != 4321` would have become `%Pin != [REDACTED]`, which
        // is true for every value %Pin can realistically hold, so an action that was guarded on
        // export would run unguarded after import. A bare placeholder parses as no comparison at
        // all, falls through to toBoolean(), and is false whatever operator was there.
        return ExportedText(ExportRedactionPolicy.REDACTED, wasRedacted = true)
    }

    @Throws(SerializationException::class, IllegalArgumentException::class)
    fun decode(rawJson: String): OpenTaskerBundle = decode(rawJson, ImportResourceBudget.Default)

    internal fun decode(rawJson: String, budget: ImportResourceBudget): OpenTaskerBundle {
        ImportResourceGuard.requireJsonPreflight(rawJson, budget)
        // Probe only the envelope before choosing a version DTO. This keeps future schemas away
        // from current domain serializers without allocating an untrusted JsonElement tree.
        val schemaVersion = schemaProbeJson.decodeFromString<BundleSchemaEnvelope>(rawJson).schemaVersion
        require(schemaVersion in SUPPORTED_OPEN_TASKER_BUNDLE_SCHEMAS) {
            "Unsupported schema version $schemaVersion; supported versions are " +
                "${SUPPORTED_OPEN_TASKER_BUNDLE_SCHEMAS.first}..${SUPPORTED_OPEN_TASKER_BUNDLE_SCHEMAS.last}."
        }
        val bundle = when (schemaVersion) {
            1 -> migrateV1(json.decodeFromString<OpenTaskerBundleV1>(rawJson))
            OPEN_TASKER_BUNDLE_SCHEMA_VERSION -> json.decodeFromString<OpenTaskerBundle>(rawJson)
            else -> error("Schema preflight and migration chain are out of sync")
        }
        return bundle.also {
            ImportResourceGuard.requireBundle(bundle, budget)
        }
    }

    private fun migrateV1(source: OpenTaskerBundleV1): OpenTaskerBundle {
        val migrationWarning =
            "Migrated bundle schema 1 to 2; action capability and power manifests were recomputed."
        val legacy = OpenTaskerBundle(
            schemaVersion = 1,
            appVersion = source.appVersion,
            exportedAtEpochMs = source.exportedAtEpochMs,
            metadata = BundleMetadata(
                name = source.metadata.name,
                description = source.metadata.description,
                warnings = source.metadata.warnings + migrationWarning,
            ),
            projects = listOf(Project(DEFAULT_PROJECT_ID, "Default", 0)),
            tasks = source.tasks,
            profiles = source.profiles,
            variables = source.variables,
            scenes = source.scenes,
        )
        val plan = validate(legacy)
        return legacy.copy(
            schemaVersion = OPEN_TASKER_BUNDLE_SCHEMA_VERSION,
            metadata = legacy.metadata.copy(
                capabilityRequirements = plan.capabilityRequirements,
                powerRequests = plan.powerRequests,
                warnings = (legacy.metadata.warnings + plan.warnings + plan.lossyWarnings).distinct(),
            ),
        )
    }

    fun validate(bundle: OpenTaskerBundle): BundleImportPlan = validate(bundle, ImportResourceBudget.Default)

    internal fun validate(bundle: OpenTaskerBundle, budget: ImportResourceBudget): BundleImportPlan {
        val warnings = mutableListOf<String>()
        val lossyWarnings = mutableListOf<String>()

        ImportResourceGuard.bundleViolation(bundle, budget)?.let { violation ->
            return BundleImportPlan(
                canImport = false,
                warnings = listOf(violation.message.orEmpty()),
            )
        }

        if (bundle.schemaVersion !in SUPPORTED_OPEN_TASKER_BUNDLE_SCHEMAS) {
            warnings += "Unsupported schema version ${bundle.schemaVersion}; supported versions are " +
                "${SUPPORTED_OPEN_TASKER_BUNDLE_SCHEMAS.first}..${SUPPORTED_OPEN_TASKER_BUNDLE_SCHEMAS.last}."
        }

        duplicateLongs(bundle.tasks.map { it.id }).takeIf { it.isNotEmpty() }?.let { duplicates ->
            warnings += "Bundle has duplicate task ids: ${duplicates.joinToString()}."
        }
        duplicateLongs(bundle.projects.map { it.id }).takeIf { it.isNotEmpty() }?.let { duplicates ->
            warnings += "Bundle has duplicate project ids: ${duplicates.joinToString()}."
        }
        duplicateStrings(bundle.projects.map { it.name.lowercase() }).takeIf { it.isNotEmpty() }?.let { duplicates ->
            warnings += "Bundle has duplicate project names: ${duplicates.joinToString()}."
        }
        val projectIds = bundle.projects.map { it.id }.toSet()
        if (DEFAULT_PROJECT_ID !in projectIds) {
            warnings += "Bundle is missing the Default project."
        }
        bundle.tasks.filterNot { it.projectId in projectIds }.forEach { task ->
            warnings += "Task '${task.name}' references missing project ${task.projectId}."
        }
        bundle.profiles.filterNot { it.projectId in projectIds }.forEach { profile ->
            warnings += "Profile '${profile.name}' references missing project ${profile.projectId}."
        }
        bundle.scenes.filterNot { it.projectId in projectIds }.forEach { scene ->
            warnings += "Scene '${scene.name}' references missing project ${scene.projectId}."
        }
        bundle.variables.filterNot { it.projectId in projectIds }.forEach { variable ->
            warnings += "Variable '${variable.name}' references missing project ${variable.projectId}."
        }
        duplicateStrings(bundle.variables.map { projectVariableKey(it.projectId, it.name) }).takeIf { it.isNotEmpty() }?.let { duplicates ->
            warnings += "Bundle has duplicate variable names: ${duplicates.joinToString { it.substringAfter(':') }}."
        }
        duplicateStrings(bundle.variables.mapNotNull { variable ->
            VariableNamePolicy.normalizeForScope(variable.name, variable.isGlobal)?.let { projectVariableKey(variable.projectId, it) }
        }).takeIf { it.isNotEmpty() }?.let { duplicates ->
            warnings += "Bundle has duplicate normalized variable names: ${duplicates.joinToString { it.substringAfter(':') }}."
        }
        bundle.variables.forEach { variable ->
            if (VariableNamePolicy.normalizeForScope(variable.name, variable.isGlobal) == null) {
                warnings += "Invalid variable name '${variable.name}'."
            }
        }
        if (bundle.variables.any(Variable::isSecret)) {
            warnings += "Bundle contains secret variable values; ordinary JSON bundles must omit secrets."
        }

        duplicateStrings(bundle.blueprints.map { it.id }).takeIf { it.isNotEmpty() }?.let { duplicates ->
            warnings += "Bundle has duplicate blueprint ids: ${duplicates.joinToString()}."
        }
        duplicateLongs(bundle.invariants.map { it.id }).takeIf { it.isNotEmpty() }?.let { duplicates ->
            warnings += "Bundle has duplicate automation invariant ids: ${duplicates.joinToString()}."
        }
        bundle.invariants.forEach { invariant ->
            AutomationInvariantPolicy.validate(invariant)?.let {
                warnings += "Invalid automation invariant '${invariant.name}'."
            }
        }
        bundle.blueprints.forEach { blueprint ->
            if (!blueprint.id.matches(BLUEPRINT_ID_PATTERN) || blueprint.title.isBlank()) {
                warnings += "Invalid blueprint '${blueprint.id}' (id/title)."
            }
            if (blueprint.version <= 0) {
                warnings += "Invalid blueprint '${blueprint.id}' (version must be positive)."
            }
            if (blueprint.inputs.isEmpty() || blueprint.actions.isEmpty() || blueprint.contexts.isEmpty()) {
                warnings += "Invalid blueprint '${blueprint.id}' (inputs, contexts, and actions are required)."
            }
            duplicateStrings(blueprint.inputs.map { it.key }).takeIf { it.isNotEmpty() }?.let { duplicates ->
                warnings += "Invalid blueprint '${blueprint.id}' (duplicate input keys: ${duplicates.joinToString()})."
            }
            blueprint.inputs.forEach { input ->
                if (!input.key.matches(BLUEPRINT_INPUT_KEY_PATTERN) || input.label.isBlank() || input.section.isBlank()) {
                    warnings += "Invalid blueprint '${blueprint.id}' (input '${input.key}' has invalid metadata)."
                }
                if (input.key.startsWith(BLUEPRINT_SECTION_KEY_PREFIX)) {
                    warnings += "Invalid blueprint '${blueprint.id}' (input '${input.key}' collides with the section-header key namespace)."
                }
                if (input.minimum != null && input.maximum != null && input.minimum > input.maximum) {
                    warnings += "Invalid blueprint '${blueprint.id}' (input '${input.key}' has reversed bounds)."
                }
                input.validationError(input.defaultValue)?.let { issue ->
                    warnings += "Invalid blueprint '${blueprint.id}' (input '${input.key}': $issue)."
                }
            }
        }

        val taskIds = bundle.tasks.map { it.id }.toSet()
        val tasksById = bundle.tasks.associateBy { it.id }
        val profilesById = bundle.profiles.associateBy { it.id }
        val scenesById = bundle.scenes.associateBy { it.id }
        bundle.profiles.forEach { profile ->
            if (profile.enterTaskId !in taskIds) {
                lossyWarnings += "Profile '${profile.name}' references missing enter task ${profile.enterTaskId} and will be skipped."
            }
            val exitTaskId = profile.exitTaskId
            if (exitTaskId != null && exitTaskId !in taskIds) {
                lossyWarnings += "Profile '${profile.name}' references missing exit task $exitTaskId; the exit task will be dropped."
            }
            val fallbackTaskId = profile.fallbackTaskId
            if (fallbackTaskId != null && fallbackTaskId !in taskIds) {
                lossyWarnings += "Profile '${profile.name}' references missing fallback task $fallbackTaskId; the fallback task will be dropped."
            }
        }

        AutomationReferenceIndex.build(bundle.profiles, bundle.tasks, bundle.scenes).forEach { reference ->
            val ownerProjectId = when (reference.site.ownerKind) {
                com.opentasker.core.references.TaskReferenceSite.OwnerKind.PROFILE -> profilesById[reference.site.ownerId]?.projectId
                com.opentasker.core.references.TaskReferenceSite.OwnerKind.TASK -> tasksById[reference.site.ownerId]?.projectId
                com.opentasker.core.references.TaskReferenceSite.OwnerKind.SCENE -> scenesById[reference.site.ownerId]?.projectId
                com.opentasker.core.references.TaskReferenceSite.OwnerKind.SETTINGS -> null
            }
            val targets = bundle.tasks.filter { reference.ref.matches(it) }
            if (ownerProjectId != null && targets.any { it.projectId != ownerProjectId }) {
                warnings += "Cross-project reference from '${reference.site.ownerName}' to " +
                    "${targets.joinToString { "'${it.name}'" }} must be reviewed."
            }
        }

        bundle.scenes.forEach { scene ->
            scene.elements.forEach { element ->
                SceneElementConfigValidator.validate(element).forEach { issue ->
                    warnings += "Invalid scene '${scene.name}' element ${element.id}: $issue."
                }
            }
            scene.elements.forEach { element ->
                if (element.tapTaskId != null && element.tapTaskId !in taskIds) {
                    lossyWarnings += "Scene '${scene.name}' element ${element.id} references missing tap task ${element.tapTaskId}; the link will be dropped."
                }
                if (element.longPressTaskId != null && element.longPressTaskId !in taskIds) {
                    lossyWarnings += "Scene '${scene.name}' element ${element.id} references missing long-press task ${element.longPressTaskId}; the link will be dropped."
                }
            }
        }

        val taskPowerRequests = powerRequests(bundle.tasks, bundle.profiles)
        val computedCapabilityRequirements = capabilityRequirements(bundle.tasks)
        val unknownActions = taskPowerRequests.flatMap { it.unknownActionIds }.distinct().sorted()
        if (unknownActions.isNotEmpty()) {
            warnings += "Bundle contains unknown unclassified actions: ${unknownActions.joinToString()}."
        }
        taskPowerRequests
            .filter { it.dataToExternalChains.isNotEmpty() }
            .forEach { request ->
                val profiles = request.profileNames.takeIf(List<String>::isNotEmpty)
                    ?.joinToString(prefix = " (profiles: ", postfix = ")")
                    .orEmpty()
                warnings += "Potential data-to-external chain in task '${request.taskName}'$profiles: " +
                    request.dataToExternalChains.joinToString { "${it.sourceActionId} -> ${it.sinkActionId}" }
            }
        bundle.profiles.forEach { profile ->
            val profileRisk = AutomationSensitivityRegistry.summarize(profile, bundle.tasks)
            profileRisk.dataToExternalChains.forEach { chain ->
                warnings += "Potential data-to-external chain in profile '${profile.name}': " +
                    "${chain.sourceActionId} -> ${chain.sinkActionId}."
            }
        }

        if (bundle.schemaVersion >= 2 && bundle.metadata.powerRequests != taskPowerRequests) {
            warnings += "Bundle power manifest did not match its actions; review uses the computed powers."
        }
        if (
            bundle.schemaVersion >= 2 &&
            bundle.metadata.capabilityRequirements != computedCapabilityRequirements
        ) {
            warnings += "Bundle capability manifest did not match its actions; review uses the computed requirements."
        }

        val unsupportedActions = bundle.tasks
            .flatMap { task -> task.actions.map { task.name to it.type } }
            .filter { (_, actionId) ->
                AutomationSensitivityRegistry.isKnown(actionId) &&
                    ActionCapabilityRegistry.get(actionId).level == CapabilityLevel.Unsupported
            }
        if (unsupportedActions.isNotEmpty()) {
            warnings += "Bundle contains unsupported actions: ${unsupportedActions.joinToString { "${it.first}:${it.second}" }}."
        }

        // Enforce per-field integrity limits at the import boundary so malformed exports (name
        // length, task priority range, blank action type, empty action lists) fail closed instead
        // of writing invalid rows. Profile structural wiring (enter task / contexts) is validated
        // contextually above and during import remapping, so only field limits are gated here.
        bundle.tasks.forEach { task ->
            InputValidation.validateTask(task).forEach { error ->
                warnings += "Invalid task '${task.name}' (${error.field}): ${error.message}."
            }
            task.actions.forEach { action ->
                InputValidation.validateAction(action).forEach { error ->
                    warnings += "Invalid action '${action.type}' in task '${task.name}' (${error.field}): ${error.message}."
                }
            }
        }
        bundle.profiles.forEach { profile ->
            InputValidation.validateProfile(profile)
                .filter {
                    it.field == "name" ||
                        it.field == "cooldownSec" ||
                        it.field == "priority" ||
                        it.field == "gracePeriodSec" ||
                        it.field == "expiresAtMs" ||
                        it.field == "maxActiveExecutions" ||
                        it.field == "burstLimit"
                        || it.field == "fallbackTaskId"
                }
                .forEach { error ->
                    warnings += "Invalid profile '${profile.name}' (${error.field}): ${error.message}."
                }
            val contextExpression = profile.contextExpression
            if (contextExpression != null &&
                !contextExpression.isValidForContextCount(profile.contexts.size)
            ) {
                warnings += "Invalid profile '${profile.name}' (contextExpression): leaf references or group structure are invalid."
            }
        }

        return BundleImportPlan(
            canImport = warnings.none { warning -> warning.isBlockingImportWarning() },
            warnings = warnings,
            lossyWarnings = lossyWarnings,
            capabilityRequirements = computedCapabilityRequirements,
            powerRequests = taskPowerRequests,
        )
    }

    private fun String.isBlockingImportWarning(): Boolean =
        startsWith("Unsupported schema version") ||
            startsWith("Bundle has duplicate task ids") ||
            startsWith("Bundle has duplicate project ids") ||
            startsWith("Bundle has duplicate project names") ||
            startsWith("Bundle is missing the Default project") ||
            startsWith("Task '") && contains("references missing project") ||
            startsWith("Profile '") && contains("references missing project") ||
            startsWith("Scene '") && contains("references missing project") ||
            startsWith("Variable '") && contains("references missing project") ||
            startsWith("Bundle has duplicate variable names") ||
            startsWith("Bundle has duplicate normalized variable names") ||
            startsWith("Bundle has duplicate blueprint ids") ||
            startsWith("Invalid blueprint ") ||
            startsWith("Bundle contains secret variable values") ||
            startsWith("Bundle contains unknown unclassified actions") ||
            startsWith("Import budget exceeded") ||
            startsWith("Invalid task ") ||
            startsWith("Invalid action ") ||
            startsWith("Invalid profile ") ||
            startsWith("Invalid variable name ") ||
            startsWith("Invalid scene ")

    private fun duplicateLongs(values: List<Long>): List<Long> =
        values.groupingBy { it }
            .eachCount()
            .filterValues { count -> count > 1 }
            .keys
            .sorted()

    private fun duplicateStrings(values: List<String>): List<String> =
        values.groupingBy { it }
            .eachCount()
            .filterValues { count -> count > 1 }
            .keys
            .sorted()

    private fun capabilityRequirements(tasks: List<Task>): List<CapabilityRequirement> =
        tasks
            .flatMap { it.actions }
            .map { it.type }
            .distinct()
            .sorted()
            .map { actionId -> actionId to ActionCapabilityRegistry.get(actionId) }
            .filter { (_, capability) -> capability.level != CapabilityLevel.Supported }
            .map { (actionId, capability) ->
                CapabilityRequirement(
                    actionId = actionId,
                    level = capability.level,
                    reason = capability.reason,
                )
            }

    private fun powerRequests(tasks: List<Task>, profiles: List<Profile>): List<RecipePowerRequest> {
        val profileNamesByTaskId = mutableMapOf<Long, MutableSet<String>>()
        profiles.forEach { profile ->
            AutomationSensitivityRegistry.reachableTasks(profile, tasks).forEach { task ->
                profileNamesByTaskId.getOrPut(task.id, ::linkedSetOf).add(profile.name)
            }
        }
        return tasks.mapNotNull { task ->
            val summary = AutomationSensitivityRegistry.summarize(task)
            if (summary.powers.isEmpty() && summary.unknownActionIds.isEmpty()) return@mapNotNull null
            RecipePowerRequest(
                taskId = task.id,
                taskName = task.name,
                profileNames = profileNamesByTaskId[task.id].orEmpty().sorted(),
                powers = summary.powers.sortedBy(AutomationPower::ordinal),
                actionIds = summary.sensitiveActionIds.sorted(),
                dataToExternalChains = summary.dataToExternalChains.map { chain ->
                    DataToExternalChainRequest(chain.sourceActionId, chain.sinkActionId)
                },
                unknownActionIds = summary.unknownActionIds.sorted(),
            )
        }.sortedWith(compareBy<RecipePowerRequest> { it.taskName.lowercase() }.thenBy { it.taskId })
    }
}

class OpenTaskerBundleRepository(
    private val db: AppDatabase,
    private val variableRepository: VariableRepository = VariableRepository(db.variableDao()),
    private val blueprintCatalogStore: BlueprintCatalogStore? = null,
    private val blueprintInstallationStore: BlueprintInstallationStore? = null,
    private val invariantStore: AutomationInvariantStore? = null,
) {
    suspend fun planImport(bundle: OpenTaskerBundle): BundleImportPlan {
        val base = OpenTaskerBundleCodec.validate(bundle)
        if (!base.canImport) return base

        val projectIdMap = resolveProjectIds(bundle.projects, createMissing = false)
        val occupiedNames = db.variableDao().getAll().associateBy { variableKey(it.projectId, it.name) }.toMutableMap()
        val reservedNames = occupiedNames.keys.toMutableSet()
        bundle.variables.mapNotNullTo(reservedNames) { variable ->
            val normalized = VariableNamePolicy.normalizeForScope(variable.name, variable.isGlobal) ?: return@mapNotNullTo null
            variableKey(projectIdMap[variable.projectId] ?: DEFAULT_PROJECT_ID, normalized)
        }
        val conflicts = bundle.variables
            .sortedWith(compareBy<Variable> { it.name.lowercase() }.thenBy { it.name })
            .mapNotNull { variable ->
                val storageName = VariableNamePolicy.normalizeForScope(variable.name, variable.isGlobal)
                    ?: return@mapNotNull null
                val targetProjectId = projectIdMap[variable.projectId] ?: DEFAULT_PROJECT_ID
                val existing = occupiedNames[variableKey(targetProjectId, storageName)] ?: return@mapNotNull null
                VariableImportConflict(
                    name = storageName,
                    existingIsSecret = existing.isEffectivelySecret(),
                    suggestedRename = nextImportedVariableName(storageName, variable.isGlobal, reservedNames),
                ).also { reservedNames += it.suggestedRename }
            }
        val existingTasks = db.taskDao().getAll().mapNotNull { record ->
            record.toDomainDecodeResult().takeUnless { it.issue != null }?.value
        }
        val existingProfiles = db.profileDao().getAll().mapNotNull { record ->
            record.toDomainDecodeResult().takeUnless { it.issue != null }?.value
        }
        val existingScenes = db.sceneDao().getAll().mapNotNull { record ->
            record.toDomainDecodeResult().takeUnless { it.issue != null }?.value
        }
        val existingVariables = db.variableDao().getAll()
            .filterNot { it.isEffectivelySecret() }
            .map { it.toDomain() }
        val blueprintUpdates = bundle.blueprints.flatMap { blueprint ->
            blueprintInstallationStore?.forBlueprint(blueprint.id).orEmpty().mapNotNull { installation ->
                BlueprintUpdatePlanner.plan(
                    blueprint = blueprint,
                    installation = installation,
                    currentProfile = existingProfiles.firstOrNull { it.id == installation.profileId },
                    currentTask = existingTasks.firstOrNull { it.id == installation.taskId },
                )
            }
        }
        val blueprintWarnings = blueprintUpdates.mapNotNull { review ->
            review.error?.let { "Blueprint '${review.blueprintTitle}' update cannot be reviewed: $it" }
        }
        return base.copy(
            warnings = base.warnings + blueprintWarnings,
            variableConflicts = conflicts,
            blueprintUpdates = blueprintUpdates,
            semanticDiff = AutomationSemanticDiff.compareBundle(
                bundle = bundle,
                existingTasks = existingTasks,
                existingProfiles = existingProfiles,
                existingVariables = existingVariables,
                existingScenes = existingScenes,
                projectIdMap = projectIdMap,
            ),
        )
    }

    suspend fun exportBundle(
        appVersion: String,
        exportedAtEpochMs: Long = System.currentTimeMillis(),
        name: String = "OpenTasker Export",
        description: String = "",
    ): OpenTaskerBundle {
        val tasks = db.taskDao().getAll().map { it.toDomain() }
        val profiles = db.profileDao().getAll().map { it.toDomain() }
        val variableExport = variableRepository.ordinaryExport()
        val scenes = db.sceneDao().getAll().map { it.toDomain() }
        val projects = db.projectDao().getAll().map { it.toDomain() }
        val blueprints = blueprintInstallationStore?.load()
            ?.mapNotNull { installation -> blueprintCatalogStore?.resolve(installation.blueprintId) }
            .orEmpty()
        val invariants = invariantStore?.load().orEmpty()

        return OpenTaskerBundleCodec.sanitizeForExport(
            OpenTaskerBundleCodec.build(
                appVersion = appVersion,
                exportedAtEpochMs = exportedAtEpochMs,
                profiles = profiles,
                tasks = tasks,
                variables = variableExport.variables,
                scenes = scenes,
                projects = projects,
                omittedSecretVariableCount = variableExport.omittedSecretCount,
                name = name,
                description = description,
                blueprints = blueprints,
                invariants = invariants,
            ),
            secretVariableNames = variableExport.omittedSecretNames,
            secretVariableValues = variableRepository.decodedForExportRedaction()
                .filter { it.isSecret && it.value.isNotEmpty() }
                .mapTo(linkedSetOf()) { it.value },
        )
    }

    suspend fun importBundle(
        bundle: OpenTaskerBundle,
        variableResolutions: Map<String, VariableConflictResolution> = emptyMap(),
    ): BundleImportReport {
        val plan = planImport(bundle)
        require(plan.canImport) { plan.warnings.joinToString() }

        var insertedTasks = 0
        var insertedProfiles = 0
        var insertedVariables = 0
        var insertedScenes = 0
        val importWarnings = (bundle.metadata.warnings + plan.warnings).distinct().toMutableList()
        val lossyWarnings = plan.lossyWarnings.toMutableList()

        // Mutation lock first, then the transaction: the reverse order deadlocks against the
        // engine's variable commit path.
        variableRepository.withMutationLock {
            db.withTransaction {
                val projectIdMap = resolveProjectIds(bundle.projects, createMissing = true)
                val taskIdMap = mutableMapOf<Long, Long>()
                val insertedTaskRecords = mutableListOf<Task>()
                bundle.tasks.sortedWith(compareBy<Task> { it.name.lowercase() }.thenBy { it.id }).forEach { task ->
                    val newId = db.taskDao().insert(task.copy(id = 0, projectId = projectIdMap[task.projectId] ?: DEFAULT_PROJECT_ID).toEntity())
                    taskIdMap[task.id] = newId
                    insertedTaskRecords += task.copy(id = newId)
                    insertedTasks++
                }

                // Task-to-task references (`task.run` targets and notification-button bindings) live
                // inside action arguments, so they can only be remapped once every task has its new id.
                // Skipping this pass is how imported sub-task calls used to point at whatever task
                // happened to own the exporter's id in this database.
                AutomationReferenceRewriter
                    .remapIds(idMap = taskIdMap, tasks = insertedTaskRecords)
                    .tasks
                    .forEach { db.taskDao().update(it.toEntity()) }

                bundle.variables.sortedWith(compareBy<Variable> { it.name.lowercase() }.thenBy { it.name }).forEach { variable ->
                    val storageName = VariableNamePolicy.normalizeForScope(variable.name, variable.isGlobal)
                        ?: throw IllegalArgumentException("Invalid variable name '${variable.name}'")
                    val targetProjectId = projectIdMap[variable.projectId] ?: DEFAULT_PROJECT_ID
                    val existing = if (targetProjectId == DEFAULT_PROJECT_ID) {
                        db.variableDao().get(storageName)
                    } else {
                        db.variableDao().getInProject(storageName, targetProjectId)
                    }
                    if (existing == null) {
                        importVariable(variable.copy(name = storageName, projectId = targetProjectId))
                        insertedVariables++
                        return@forEach
                    }

                    val resolution = variableResolutions[storageName]
                        ?: VariableConflictResolution(VariableConflictAction.PRESERVE_EXISTING)
                    when (resolution.action) {
                        VariableConflictAction.PRESERVE_EXISTING -> {
                            importWarnings += "Preserved existing variable '$storageName'."
                        }
                        VariableConflictAction.RENAME_IMPORTED -> {
                            val rename = resolution.renamedTo
                                ?: plan.variableConflicts.first { it.name == storageName }.suggestedRename
                            val normalizedRename = VariableNamePolicy.normalizeForScope(rename, variable.isGlobal)
                                ?: throw IllegalArgumentException("Invalid renamed variable '$rename'")
                            require(normalizedRename != storageName) {
                                "Renamed variable '$storageName' must use a different name."
                            }
                            val renamedExists = if (targetProjectId == DEFAULT_PROJECT_ID) {
                                db.variableDao().get(normalizedRename) != null
                            } else {
                                db.variableDao().getInProject(normalizedRename, targetProjectId) != null
                            }
                            require(!renamedExists) {
                                "Renamed variable '$normalizedRename' already exists."
                            }
                            importVariable(variable.copy(name = normalizedRename, projectId = targetProjectId))
                            insertedVariables++
                            importWarnings += "Renamed imported variable '$storageName' to '$normalizedRename'."
                        }
                        VariableConflictAction.REPLACE_EXISTING -> {
                            val keepSecret = existing.isEffectivelySecret()
                            importVariable(
                                variable.copy(name = storageName, isSecret = keepSecret || variable.isSecret, projectId = targetProjectId),
                            )
                            val suffix = if (keepSecret) " and kept it secret" else ""
                            importWarnings += "Replaced existing variable '$storageName'$suffix."
                        }
                    }
                }

                bundle.profiles.sortedWith(compareBy<Profile> { it.name.lowercase() }.thenBy { it.id }).forEach { profile ->
                    val enterTaskId = taskIdMap[profile.enterTaskId]
                    if (enterTaskId == null) {
                        lossyWarnings += "Skipped profile '${profile.name}' because enter task ${profile.enterTaskId} was not imported."
                        return@forEach
                    }
                    val remappedProfile = profile.copy(
                        id = 0,
                        enabled = false,
                        requiresRiskAcknowledgement = true,
                        lifetimeConsumed = false,
                        enterTaskId = enterTaskId,
                        exitTaskId = profile.exitTaskId?.let { taskIdMap[it] },
                        fallbackTaskId = profile.fallbackTaskId?.let { taskIdMap[it] },
                        projectId = projectIdMap[profile.projectId] ?: DEFAULT_PROJECT_ID,
                    )
                    db.profileDao().upsert(remappedProfile.toEntity())
                    insertedProfiles++
                }

                bundle.scenes.sortedWith(compareBy<Scene> { it.name.lowercase() }.thenBy { it.id }).forEach { scene ->
                    val remappedElements = scene.elements.map { element ->
                        remapSceneElement(element, taskIdMap)
                    }
                    db.sceneDao().insert(scene.copy(id = 0, elements = remappedElements, projectId = projectIdMap[scene.projectId] ?: DEFAULT_PROJECT_ID).toEntity())
                    insertedScenes++
                }
            }
        }

        if (bundle.blueprints.isNotEmpty()) {
            blueprintCatalogStore?.merge(bundle.blueprints)
            importWarnings += "${bundle.blueprints.size} blueprint definition(s) were reviewed and saved; existing instantiated profiles were not overwritten."
        }

        val importableInvariants = AutomationInvariantPolicy.normalize(bundle.invariants)
        if (importableInvariants.isNotEmpty() && invariantStore != null) {
            invariantStore.merge(importableInvariants)
            importWarnings += "${importableInvariants.size} automation invariant(s) were imported and added to local diagnostics policy."
        }

        return BundleImportReport(
            insertedTasks = insertedTasks,
            insertedProfiles = insertedProfiles,
            insertedVariables = insertedVariables,
            insertedScenes = insertedScenes,
            warnings = importWarnings,
            lossyWarnings = lossyWarnings.distinct(),
            importedBlueprints = bundle.blueprints.size,
            importedInvariants = if (invariantStore == null) 0 else importableInvariants.size,
        )
    }

    private fun remapSceneElement(element: SceneElement, taskIdMap: Map<Long, Long>): SceneElement =
        element.copy(
            tapTaskId = element.tapTaskId?.let { taskIdMap[it] },
            longPressTaskId = element.longPressTaskId?.let { taskIdMap[it] },
        )

    private suspend fun resolveProjectIds(projects: List<Project>, createMissing: Boolean): Map<Long, Long> {
        val existing = db.projectDao().getAll()
        val byName = existing.associateBy { it.name.trim().lowercase() }.toMutableMap()
        val ids = mutableMapOf<Long, Long>()
        projects.ifEmpty { listOf(Project(DEFAULT_PROJECT_ID, "Default", 0)) }
            .sortedWith(compareBy<Project> { it.position }.thenBy { it.name.lowercase() }.thenBy { it.id })
            .forEach { project ->
                val normalizedName = project.name.trim().lowercase()
                val target = byName[normalizedName] ?: if (createMissing) {
                    val id = db.projectDao().insert(
                        ProjectEntity(id = 0, name = project.name.trim(), position = project.position),
                    )
                    ProjectEntity(id = id, name = project.name.trim(), position = project.position)
                        .also { byName[normalizedName] = it }
                } else {
                    null
                }
                if (target != null) ids[project.id] = target.id
            }
        ids.putIfAbsent(DEFAULT_PROJECT_ID, existing.firstOrNull { it.id == DEFAULT_PROJECT_ID }?.id ?: DEFAULT_PROJECT_ID)
        return ids
    }

    private fun variableKey(projectId: Long, name: String): String = "$projectId:$name"

    private fun nextImportedVariableName(
        original: String,
        isGlobal: Boolean,
        reservedNames: Set<String>,
    ): String {
        val baseLength = (VariableNamePolicy.MAX_LENGTH - "_imported9999".length).coerceAtLeast(1)
        val base = original.take(baseLength)
        var suffix = "_imported"
        var candidate = VariableNamePolicy.normalizeForScope(base + suffix, isGlobal)
        var index = 2
        while (candidate == null || candidate in reservedNames) {
            suffix = "_imported$index"
            candidate = VariableNamePolicy.normalizeForScope(
                original.take((VariableNamePolicy.MAX_LENGTH - suffix.length).coerceAtLeast(1)) + suffix,
                isGlobal,
            )
            index++
        }
        return candidate
    }
}

@Serializable
private data class OpenTaskerBundleV1(
    val schemaVersion: Int = 1,
    val appVersion: String,
    val exportedAtEpochMs: Long,
    val metadata: BundleMetadataV1 = BundleMetadataV1(),
    val tasks: List<Task> = emptyList(),
    val profiles: List<Profile> = emptyList(),
    val variables: List<Variable> = emptyList(),
    val scenes: List<Scene> = emptyList(),
)

@Serializable
private data class BundleMetadataV1(
    val name: String = "OpenTasker Export",
    val description: String = "",
    val capabilityRequirements: List<CapabilityRequirement> = emptyList(),
    val warnings: List<String> = emptyList(),
)

@Serializable
private data class BundleSchemaEnvelope(
    val schemaVersion: Int = 1,
)
