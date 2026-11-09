package com.opentasker.ui.screens

import android.content.Context
import android.net.Uri
import android.annotation.SuppressLint
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.opentasker.app.R
import com.opentasker.core.capabilities.AutomationFeedbackRiskAnalyzer
import com.opentasker.core.capabilities.AutomationLint
import com.opentasker.core.capabilities.AutomationLintReport
import com.opentasker.core.capabilities.AutomationLintSeverity
import com.opentasker.core.capabilities.AutomationLintStrings
import com.opentasker.core.capabilities.AutomationInvariantStore
import com.opentasker.core.capabilities.ImportedProfileEnablePolicy
import com.opentasker.core.contexts.NfcTagWriteSession
import com.opentasker.core.diagnostics.RunLogExportFormat
import com.opentasker.core.actions.ActionMetadataRegistry
import com.opentasker.core.actions.readActiveTransport
import com.opentasker.core.engine.ActiveExecution
import com.opentasker.core.engine.ActiveExecutionRegistry
import com.opentasker.core.engine.ExecutionEnvelope
import com.opentasker.core.engine.ExecutionAdmissionRegistry
import com.opentasker.core.engine.PreflightInputs
import com.opentasker.core.engine.PreflightReport
import com.opentasker.core.engine.PreflightRunner
import com.opentasker.core.engine.SingleActionRun
import com.opentasker.core.engine.executeAndLogTask
import com.opentasker.core.engine.replayHeldExecution
import com.opentasker.core.location.LocationDwellStateStore
import com.opentasker.core.model.AutomationMode
import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.AutomationInvariant
import com.opentasker.core.model.CollisionMode
import com.opentasker.core.model.Profile
import com.opentasker.core.model.ProfileLifetime
import com.opentasker.core.model.ProfileLifecyclePolicy
import com.opentasker.core.model.ProfileOverflowPolicy
import com.opentasker.core.model.Project
import com.opentasker.core.model.DEFAULT_PROJECT_ID
import com.opentasker.core.validation.InputValidation
import com.opentasker.core.model.RunLogEntry
import com.opentasker.core.model.Scene
import com.opentasker.core.model.Task
import com.opentasker.core.model.Variable
import com.opentasker.core.model.VariableNamePolicy
import com.opentasker.core.logging.AppLogger
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.SerializationException
import com.opentasker.core.plugins.locale.LocaleConditionGrantStore
import com.opentasker.core.plugins.locale.LocaleGrantStore
import com.opentasker.core.diff.SemanticDiffDocument
import com.opentasker.core.references.AutomationReferenceIndex
import com.opentasker.core.references.AutomationDuplicator
import com.opentasker.core.references.AutomationDuplicateStrings
import com.opentasker.core.references.AutomationReferenceRewriter
import com.opentasker.core.references.ReferenceResolution
import com.opentasker.core.references.TaskReference
import com.opentasker.core.references.describe
import com.opentasker.core.sharing.ProfileShareDraft
import com.opentasker.core.sharing.ProfileShareLibrary
import com.opentasker.core.sharing.ProfileShareManifest
import com.opentasker.core.storage.AppDatabase
import com.opentasker.core.storage.CorruptStoredRecordException
import com.opentasker.core.storage.DatabaseBackupManager
import com.opentasker.core.storage.StorageDecodeResult
import com.opentasker.core.storage.FallbackTaskSettings
import com.opentasker.core.storage.ProjectDeletionSnapshot
import com.opentasker.core.storage.EditHistoryDao
import com.opentasker.core.storage.EditHistoryEntity
import com.opentasker.core.storage.RunLogSnapshot
import com.opentasker.core.storage.StorageDecodeIssue
import com.opentasker.core.storage.StorageJson
import com.opentasker.core.storage.VariableRepository
import com.opentasker.core.storage.VariableEditHistoryIdentity
import com.opentasker.core.storage.ProjectEntity
import com.opentasker.core.storage.normalized
import com.opentasker.core.storage.toEntity
import com.opentasker.core.templates.ProfileTemplate
import com.opentasker.core.templates.BlueprintCatalogStore
import com.opentasker.core.templates.BlueprintInstallation
import com.opentasker.core.templates.BlueprintInstallationStore
import com.opentasker.core.transfer.BundleImportPlan
import com.opentasker.core.transfer.MacroDroidEncryptedExport
import com.opentasker.core.transfer.MacroDroidImportPlanner
import com.opentasker.core.transfer.MacroDroidImportReport
import com.opentasker.core.transfer.MacroDroidImporter
import com.opentasker.core.transfer.OpenTaskerBundle
import com.opentasker.core.transfer.OpenTaskerBundleCodec
import com.opentasker.core.transfer.OpenTaskerBundleRepository
import com.opentasker.core.transfer.OpenTaskerBundleTextImport
import com.opentasker.core.transfer.PastedImportKind
import com.opentasker.core.transfer.PastedImportSource
import com.opentasker.core.transfer.TaskerImportPlanner
import com.opentasker.core.transfer.TaskerOrMacroDroidDocument
import com.opentasker.core.transfer.TaskerXmlExporter
import com.opentasker.core.transfer.TaskerImportPreview
import com.opentasker.core.transfer.TaskerXmlImportReport
import com.opentasker.core.transfer.TaskerXmlImporter
import com.opentasker.core.transfer.VariableConflictResolution
import com.opentasker.widget.TaskShortcutHelper
import com.opentasker.widget.TaskWidgetProvider
import androidx.room.withTransaction
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal const val TASKER_MACRODROID_IMPORT_MAX_BYTES = 16 * 1024 * 1024
internal const val OPEN_TASKER_BUNDLE_IMPORT_MAX_BYTES = 8 * 1024 * 1024
internal val TASKER_MACRODROID_MIME_TYPES = arrayOf(
    "application/json",
    "text/json",
    "application/xml",
    "text/xml",
    "application/octet-stream",
    "text/*",
    "*/*",
)
internal val OPEN_TASKER_BUNDLE_MIME_TYPES = arrayOf("application/json", "text/json", "text/*", "*/*")
internal val DATABASE_BACKUP_MIME_TYPES = arrayOf(
    "application/octet-stream",
    "application/x-sqlite3",
    "application/vnd.sqlite3",
    "*/*",
)

internal fun databaseBackupExportName(): String =
    "opentasker_backup_${SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())}.db"

internal fun openTaskerBundleExportName(): String =
    "opentasker_bundle_${SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())}.json"

internal fun runLogExportName(format: RunLogExportFormat): String {
    val extension = if (format == RunLogExportFormat.JSON) "json" else "csv"
    return "opentasker_run_log_${SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())}.$extension"
}

internal data class TaskerImportReviewState(
    val bundle: OpenTaskerBundle,
    val preview: TaskerImportPreview,
    @StringRes val titleRes: Int,
    val mappedActionRows: List<String>,
    val unsupportedActionRows: List<String>,
)

/** A picked `.emdr`, waiting for the password it was exported with. */
internal class MacroDroidPasswordPrompt(val bytes: ByteArray, val wrongPassword: Boolean = false) {
    fun copy(wrongPassword: Boolean) = MacroDroidPasswordPrompt(bytes, wrongPassword)
}

private fun taskerImportReviewState(report: TaskerXmlImportReport): TaskerImportReviewState =
    TaskerImportReviewState(
        bundle = TaskerImportPlanner.confirmedBundle(report),
        preview = TaskerImportPlanner.preview(report),
        titleRes = R.string.dialog_review_tasker,
        mappedActionRows = report.mappedActions.map {
            "${it.taskName}: ${it.taskerCode} -> ${it.openTaskerActionId}"
        },
        unsupportedActionRows = report.unsupportedActions.map {
            "${it.taskName} step ${it.actionIndex + 1}: code ${it.taskerCode}"
        },
    )

private fun macroDroidImportReviewState(report: MacroDroidImportReport): TaskerImportReviewState =
    TaskerImportReviewState(
        bundle = MacroDroidImportPlanner.confirmedBundle(report),
        preview = MacroDroidImportPlanner.preview(report),
        titleRes = R.string.dialog_review_macrodroid,
        mappedActionRows = report.mappedActions.map {
            "${it.macroName} step ${it.actionIndex + 1}: ${it.classType} -> ${it.openTaskerActionIds.joinToString(" + ")}"
        },
        unsupportedActionRows = report.unsupportedActions.map {
            "${it.macroName} step ${it.actionIndex + 1}: ${it.classType} (${it.reason})"
        },
    )

/** Snackbar payloads stay as resource IDs until the Compose collector resolves them. */
sealed interface UiMessageAction {
    data class Undo(
        val entityType: String,
        val entityId: Long,
    ) : UiMessageAction
}

internal data class OpenTaskerBundleReviewState(
    val bundle: OpenTaskerBundle,
    val plan: BundleImportPlan,
    val variableResolutions: Map<String, VariableConflictResolution> = emptyMap(),
)

internal data class SemanticDiffReviewState(
    val document: SemanticDiffDocument,
)

internal data class ProfileShareReviewState(
    val draft: ProfileShareDraft,
    val manifest: ProfileShareManifest,
    val plan: BundleImportPlan,
    val draftError: String? = null,
)

internal sealed interface PreflightTarget {
    data class TaskTarget(val task: Task) : PreflightTarget
    data class ProfileTarget(val profile: Profile) : PreflightTarget
}

internal data class PreflightReviewState(
    val target: PreflightTarget,
    val inputs: PreflightInputs,
    val report: PreflightReport,
)

/**
 * What a task delete would break: every dependent object, plus whether any of them holds a
 * reference that cannot legally be cleared (a profile's enter task).
 */
data class TaskDeletionPreview(
    val task: Task,
    val references: List<TaskReference> = emptyList(),
    val requiresReassignment: Boolean = false,
) {
    val hasDependents: Boolean get() = references.isNotEmpty()
}

class ActiveAutomationViewModel(
    private val db: AppDatabase,
    private val appContext: Context,
) : ViewModel() {
    private val locationDwellStateStore = LocationDwellStateStore(appContext)
    private val variableRepository = VariableRepository(db.variableDao())
    private val blueprintCatalogStore = BlueprintCatalogStore(appContext)
    private val blueprintInstallationStore = BlueprintInstallationStore(appContext)
    private val invariantStore = AutomationInvariantStore(appContext)
    private val bundleRepository = OpenTaskerBundleRepository(
        db = db,
        variableRepository = variableRepository,
        blueprintCatalogStore = blueprintCatalogStore,
        blueprintInstallationStore = blueprintInstallationStore,
        invariantStore = invariantStore,
    )
    private val fallbackTaskSettings = FallbackTaskSettings(appContext)
    private val writeSettingsGuard = WriteSettingsGuard(db, appContext)
    private val editHistoryTransitions = EditHistoryTransitions(
        db,
        appContext,
        fallbackTaskSettings,
        locationDwellStateStore,
        writeSettingsGuard,
        variableRepository,
    )

    private fun message(@StringRes resId: Int, vararg args: Any): UiMessage = uiMessage(resId, *args)

    private fun pluralMessage(@PluralsRes resId: Int, quantity: Int, vararg args: Any): UiMessage =
        uiPluralMessage(resId, quantity, *args)

    private fun errorMessage(error: Throwable, fallbackRes: Int): UiMessage = loggedUiErrorMessage(error, fallbackRes)

    private suspend fun recordEdit(entityType: String, entityId: Long, previousJson: String, nextJson: String) =
        db.editHistoryDao().recordEdit(entityType, entityId, previousJson, nextJson)

    private suspend fun recordCreation(entityType: String, entityId: Long, nextJson: String) =
        db.editHistoryDao().recordCreation(entityType, entityId, nextJson)

    private suspend fun recordDeletion(entityType: String, entityId: Long, previousJson: String) =
        db.editHistoryDao().recordDeletion(entityType, entityId, previousJson)

    /** See [contentLoadedSignal]: screens gate first-run empty states on this. */
    val contentLoaded: StateFlow<Boolean> = contentLoadedSignal(db, viewModelScope)

    /** See [editHistoryAvailability]: Undo/Redo are enabled only where there is history. */
    val historyAvailability: StateFlow<EditHistoryAvailabilityState> = editHistoryAvailability(db, viewModelScope)

    private val profileDecodeResults = db.profileDao()
        .getAllAsFlow()
        .map { entities -> entities.map { it.toDomainDecodeResult() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val taskDecodeResults = db.taskDao()
        .getAllAsFlow()
        .map { entities -> entities.map { it.toDomainDecodeResult() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val profiles: StateFlow<ImmutableList<Profile>> = profileDecodeResults
        .map { results ->
            results.mapNotNull { result -> result.value.takeIf { result.issue == null } }
                .sortedBy { it.name.lowercase() }
                .toImmutableList()
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), persistentListOf())

    val invariants: StateFlow<List<AutomationInvariant>> = invariantStore.observe()
        .stateIn(viewModelScope, SharingStarted.Eagerly, invariantStore.load())

    fun updateAutomationInvariants(value: List<AutomationInvariant>) {
        invariantStore.save(value)
    }

    val tasks: StateFlow<ImmutableList<Task>> = taskDecodeResults
        .map { results ->
            results.mapNotNull { result -> result.value.takeIf { result.issue == null } }
                .sortedBy { it.name.lowercase() }
                .toImmutableList()
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), persistentListOf())

    private val sceneDecodeResults = db.sceneDao()
        .getAllAsFlow()
        .map { entities -> entities.map { it.toDomainDecodeResult() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val storageDecodeIssues: StateFlow<ImmutableList<StorageDecodeIssue>> = combine(
        profileDecodeResults,
        taskDecodeResults,
        sceneDecodeResults,
    ) { profileResults, taskResults, sceneResults ->
        (profileResults.mapNotNull { it.issue } + taskResults.mapNotNull { it.issue } + sceneResults.mapNotNull { it.issue })
            .sortedWith(compareBy<StorageDecodeIssue> { it.recordType.label }.thenBy { it.recordName.lowercase() })
            .toImmutableList()
    }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), persistentListOf())

    val scenes: StateFlow<ImmutableList<Scene>> = sceneDecodeResults
        .map { results ->
            results.mapNotNull { result -> result.value.takeIf { result.issue == null } }
                .sortedBy { it.name.lowercase() }
                .toImmutableList()
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), persistentListOf())

    val projects: StateFlow<ImmutableList<Project>> = db.projectDao()
        .getAllAsFlow()
        .map { entities ->
            entities.map(ProjectEntity::toDomain).sortedWith(compareBy<Project> { it.position }.thenBy { it.id }).toImmutableList()
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), persistentListOf())

    /** Runs in flight right now, so the Run Log can show and stop them. */
    val activeExecutions: StateFlow<ImmutableList<ActiveExecution>> = ActiveExecutionRegistry.active
        .map { it.toImmutableList() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), persistentListOf())

    fun cancelExecution(executionId: Long) {
        viewModelScope.launch {
            val cancelled = ActiveExecutionRegistry.cancel(executionId)
            events.send(message(if (cancelled) R.string.ui_message_cancelling_automation else R.string.ui_message_automation_finished))
        }
    }

    val globalVariables: StateFlow<ImmutableList<Variable>> = variableRepository
        .observeGlobals(null)
        .map { variables -> variables.toImmutableList() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), persistentListOf())

    private val events = Channel<UiMessage>(Channel.BUFFERED)
    val messages = events.receiveAsFlow()

    /** Run Log paging, filters, export and retention (A-353). */
    internal val runLog = RunLogController(db, appContext, viewModelScope, events)

    /** Backup, restore staging and configuration snapshots for the Setup card (A-353). */
    internal val backup = BackupController(appContext, viewModelScope, events, DatabaseBackupManager(appContext, db))

    /** The health read and the redacted report for the Diagnostics screen. */
    internal val diagnostics = DiagnosticsController(db, appContext, viewModelScope, events)

    private val _globalFallbackTaskId = MutableStateFlow(fallbackTaskSettings.loadTaskId())
    val globalFallbackTaskId: StateFlow<Long?> = _globalFallbackTaskId.asStateFlow()

    private val _taskerImportReview = MutableStateFlow<TaskerImportReviewState?>(null)
    internal val taskerImportReview: StateFlow<TaskerImportReviewState?> = _taskerImportReview.asStateFlow()
    private val _macroDroidPasswordPrompt = MutableStateFlow<MacroDroidPasswordPrompt?>(null)
    internal val macroDroidPasswordPrompt: StateFlow<MacroDroidPasswordPrompt?> = _macroDroidPasswordPrompt.asStateFlow()

    private val automationTransfer = ImportExportCoordinator(viewModelScope)
    val taskerImportBusy: StateFlow<Boolean> = automationTransfer.busy
    val taskerImportProgress: StateFlow<TransferProgress?> = automationTransfer.progress

    private val _openTaskerBundleReview = MutableStateFlow<OpenTaskerBundleReviewState?>(null)
    internal val openTaskerBundleReview: StateFlow<OpenTaskerBundleReviewState?> = _openTaskerBundleReview.asStateFlow()

    private val bundleTransfer = ImportExportCoordinator(viewModelScope)
    val openTaskerBundleBusy: StateFlow<Boolean> = bundleTransfer.busy
    val openTaskerBundleProgress: StateFlow<TransferProgress?> = bundleTransfer.progress

    /** Stops whichever transfer is running; nothing is written until a review is confirmed. */
    fun cancelTransfers() {
        automationTransfer.cancel()
        bundleTransfer.cancel()
        preflightTransfer.cancel()
    }

    private val _semanticDiffReview = MutableStateFlow<SemanticDiffReviewState?>(null)
    internal val semanticDiffReview: StateFlow<SemanticDiffReviewState?> = _semanticDiffReview.asStateFlow()

    /**
     * Nodes the last reviewed undo/redo touched, highlighted on the Flow tab.
     *
     * This deliberately outlives [semanticDiffReview]: the diff dialog's scrim covers Flow and
     * closing it is the only way to reach the tab, so keys tied to the dialog's lifetime made the
     * highlight - and the dialog's own "highlighted in Flow" note - unreachable. The next edit
     * replaces them.
     */
    private val _highlightedFlowNodeKeys = MutableStateFlow<Set<String>>(emptySet())
    internal val highlightedFlowNodeKeys: StateFlow<Set<String>> = _highlightedFlowNodeKeys.asStateFlow()

    /** The profile a synthetic-trigger simulation is running against; survives rotation. */
    private val _simulationProfile = MutableStateFlow<Profile?>(null)
    internal val simulationProfile: StateFlow<Profile?> = _simulationProfile.asStateFlow()

    fun openSimulation(profile: Profile) {
        _simulationProfile.value = profile
    }

    fun clearSimulation() {
        _simulationProfile.value = null
    }

    private val _profileShareReview = MutableStateFlow<ProfileShareReviewState?>(null)
    internal val profileShareReview: StateFlow<ProfileShareReviewState?> = _profileShareReview.asStateFlow()

    private val _preflightReview = MutableStateFlow<PreflightReviewState?>(null)
    internal val preflightReview: StateFlow<PreflightReviewState?> = _preflightReview.asStateFlow()

    /**
     * Guards the one-shot run actions. Their buttons stay enabled while the coroutine is in
     * flight, so a double tap ran the task - or replayed a held execution - twice, with real
     * side effects each time.
     */
    private val _runActionBusy = MutableStateFlow(false)
    val runActionBusy: StateFlow<Boolean> = _runActionBusy.asStateFlow()

    private val preflightTransfer = ImportExportCoordinator(viewModelScope)
    val preflightBusy: StateFlow<Boolean> = preflightTransfer.busy

    init {
        viewModelScope.launch {
            runCatching { reconcileGlobalFallbackTask() }
        }
    }

    fun createTask(
        name: String,
        priority: Int,
        collisionMode: CollisionMode,
        projectId: Long = DEFAULT_PROJECT_ID,
        onSaved: () -> Unit = {},
    ) = launchCreate(R.string.ui_message_task_created, onSaved = onSaved) {
        db.taskDao().insert(
            Task(
                name = name.trim(),
                priority = priority.coerceIn(0, 10),
                collisionMode = collisionMode,
                projectId = projectId,
            ).toEntity(),
        )
    }

    fun duplicateTask(task: Task) {
        viewModelScope.launch {
            runCatching {
                db.withTransaction {
                    val source = db.taskDao().getById(task.id)?.toDomainDecodeResult()?.also { result ->
                        result.issue?.let { issue -> throw CorruptRecordOverwriteException(issue) }
                    }?.value ?: error("Task no longer exists.")
                    val name = AutomationDuplicator.copyName(
                        source.name,
                        db.taskDao().getAll().map { it.name },
                        AutomationDuplicateStrings.from(appContext.resources),
                    )
                    val staged = AutomationDuplicator.taskPayload(source, name)
                    val newId = db.taskDao().insert(staged.toEntity())
                    val duplicate = AutomationReferenceRewriter.remapDuplicateSelfReferences(
                        original = source,
                        duplicate = staged.copy(id = newId),
                    )
                    db.taskDao().update(duplicate.toEntity())
                    recordCreation(EditHistoryDao.TYPE_TASK, newId, StorageJson.encodeToString(duplicate))
                }
            }.onSuccess { events.send(message(R.string.ui_message_task_duplicated)) }
                .onFailure { events.send(errorMessage(it, R.string.ui_error_generic)) }
        }
    }

    fun updateTask(
        task: Task,
        @StringRes successMessageRes: Int = R.string.ui_message_task_updated,
        successAction: UiMessageAction? = null,
        onSaved: () -> Unit = {},
    ) = launchWithMessage(
        successMessageRes,
        successAction,
        // A rename leaves every task widget showing the old label until something asks them to
        // re-read it; nothing else does.
        onSaved = { TaskWidgetProvider.requestRefresh(appContext); onSaved() },
    ) {
        // Wrapped like updateScene: the corrupt-record check, history snapshot, prune, and
        // update must be atomic so a concurrent writer can't interleave and lose a revision.
        db.withTransaction {
            val previous = db.taskDao().getById(task.id)
            if (previous != null) {
                previous.toDomainDecodeResult().issue?.let { issue ->
                    throw CorruptRecordOverwriteException(issue)
                }
                val previousTask = previous.toDomain()
                recordEdit(
                    entityType = EditHistoryDao.TYPE_TASK,
                    entityId = task.id,
                    previousJson = StorageJson.encodeToString(previousTask),
                    nextJson = StorageJson.encodeToString(task),
                )

                // A rename breaks every reference that still names this task ("task.run" targets,
                // legacy notification bindings). Pin those to the stable id in the same
                // transaction so they cannot dangle or be captured by a future task that takes the
                // old name.
                if (!previousTask.name.equals(task.name, ignoreCase = true)) {
                    val rewrite = AutomationReferenceRewriter.stabilizeNameReferences(
                        target = previousTask,
                        profiles = db.profileDao().getAll().map { it.toDomain() },
                        tasks = db.taskDao().getAll().map { it.toDomain() },
                        scenes = db.sceneDao().getAll().map { it.toDomain() },
                    )
                    rewrite.profiles.forEach { db.profileDao().upsert(it.toEntity()) }
                    rewrite.tasks.filterNot { it.id == task.id }.forEach { db.taskDao().update(it.toEntity()) }
                    rewrite.scenes.forEach { db.sceneDao().update(it.toEntity()) }
                }
            }
            db.taskDao().update(task.toEntity())
        }
    }

    fun moveTaskAction(taskId: Long, fromIndex: Int, toIndex: Int) = launchWithMessage(R.string.ui_message_action_moved) {
        db.withTransaction {
            val entity = db.taskDao().getById(taskId) ?: error("Task no longer exists.")
            val decoded = entity.toDomainDecodeResult()
            decoded.issue?.let { throw CorruptRecordOverwriteException(it) }
            val updated = decoded.value.copy(
                actions = reorderActions(decoded.value.actions, fromIndex, toIndex),
            )
            recordEdit(
                entityType = EditHistoryDao.TYPE_TASK,
                entityId = taskId,
                previousJson = StorageJson.encodeToString(decoded.value),
                nextJson = StorageJson.encodeToString(updated),
            )
            db.taskDao().update(updated.toEntity())
        }
    }

    fun removeTaskAction(task: Task, index: Int) {
        require(index in task.actions.indices) { "Action index is out of range." }
        updateTask(
            task.copy(actions = task.actions.filterIndexed { actionIndex, _ -> actionIndex != index }),
            R.string.ui_message_action_removed,
            UiMessageAction.Undo(EditHistoryDao.TYPE_TASK, task.id),
        )
    }

    /**
     * Every object that still points at [task], resolved through the shared reference index so
     * `task.run` arguments, notification buttons, and scene gestures are surfaced alongside the
     * profile columns that used to be the only thing checked.
     */
    suspend fun taskDeletionPreview(task: Task): TaskDeletionPreview = withContext(Dispatchers.IO) {
        val references = runCatching {
            AutomationReferenceIndex.referencesTo(
                task = task,
                profiles = db.profileDao().getAll().map { it.toDomain() },
                tasks = db.taskDao().getAll().map { it.toDomain() },
                scenes = db.sceneDao().getAll().map { it.toDomain() },
                globalFallbackTaskId = fallbackTaskSettings.loadTaskId(),
            )
        }.getOrElse { emptyList() }
        TaskDeletionPreview(
            task = task,
            references = references,
            requiresReassignment = references.any { it.isRequired },
        )
    }

    /**
     * Deletes [task] and applies [resolution] to every dependent reference in one transaction, so
     * the workspace can never be observed with a dangling or half-rewritten reference.
     */
    fun deleteTask(task: Task, resolution: ReferenceResolution = ReferenceResolution.Block) {
        viewModelScope.launch {
            runCatching {
                var blockedCount = 0
                var rewrittenGlobalFallbackTaskId: Long? = null
                var globalFallbackChanged = false
                db.withTransaction {
                    val currentTask = db.taskDao().getById(task.id)?.toDomainDecodeResult()?.also { result ->
                        result.issue?.let { issue -> throw CorruptRecordOverwriteException(issue) }
                    }?.value ?: error("Task no longer exists.")
                    val profiles = db.profileDao().getAll().map { it.toDomain() }
                    val tasks = db.taskDao().getAll().map { it.toDomain() }
                    val scenes = db.sceneDao().getAll().map { it.toDomain() }
                    val rewrite = AutomationReferenceRewriter.retarget(
                        target = currentTask,
                        resolution = resolution,
                        profiles = profiles,
                        tasks = tasks,
                        scenes = scenes,
                        globalFallbackTaskId = fallbackTaskSettings.loadTaskId(),
                    )
                    if (!rewrite.canCommit) {
                        blockedCount = rewrite.blocked.size
                        return@withTransaction
                    }
                    recordDeletion(
                        entityType = EditHistoryDao.TYPE_TASK,
                        entityId = currentTask.id,
                        previousJson = StorageJson.encodeToString(currentTask),
                    )
                    rewrite.profiles.forEach { db.profileDao().upsert(it.toEntity()) }
                    rewrite.tasks.forEach { db.taskDao().update(it.toEntity()) }
                    rewrite.scenes.forEach { db.sceneDao().update(it.toEntity()) }
                    db.taskDao().delete(currentTask.toEntity())
                    rewrittenGlobalFallbackTaskId = rewrite.globalFallbackTaskId
                    globalFallbackChanged = rewrite.globalFallbackChanged
                }
                if (blockedCount == 0 && globalFallbackChanged) {
                    fallbackTaskSettings.saveTaskId(rewrittenGlobalFallbackTaskId)
                    _globalFallbackTaskId.value = rewrittenGlobalFallbackTaskId
                }
                blockedCount
            }
                .onSuccess { blocked ->
                    if (blocked > 0) {
                        events.send(message(R.string.ui_task_still_used, blocked))
                    } else {
                        LocaleGrantStore(appContext).revokeAllForTask(task.id)
                        // Otherwise a widget bound to this task keeps looking runnable and only
                        // answers a tap with "Task not found".
                        TaskWidgetProvider.requestRefresh(appContext)
                        events.send(
                            UiMessage(
                                resId = R.string.ui_message_task_deleted,
                                action = UiMessageAction.Undo(EditHistoryDao.TYPE_TASK, task.id),
                            ),
                        )
                    }
                }
                .onFailure { events.send(errorMessage(it, R.string.ui_error_task_delete)) }
        }
    }

    fun createScene(
        name: String,
        widthDp: Int,
        heightDp: Int,
        projectId: Long = DEFAULT_PROJECT_ID,
        onSaved: () -> Unit = {},
    ) = launchCreate(R.string.ui_message_scene_created, onSaved = onSaved) {
        db.sceneDao().insert(
            Scene(
                name = name.trim(),
                widthDp = widthDp.coerceIn(SCENE_WIDTH_RANGE_DP),
                heightDp = heightDp.coerceIn(SCENE_HEIGHT_RANGE_DP),
                projectId = projectId,
            ).toEntity()
        )
    }

    fun duplicateScene(scene: Scene) {
        viewModelScope.launch {
            runCatching {
                db.withTransaction {
                    val source = db.sceneDao().getById(scene.id)?.toDomainDecodeResult()?.also { result ->
                        result.issue?.let { issue -> throw CorruptRecordOverwriteException(issue) }
                    }?.value ?: error("Scene no longer exists.")
                    val name = AutomationDuplicator.copyName(
                        source.name,
                        db.sceneDao().getAll().map { it.name },
                        AutomationDuplicateStrings.from(appContext.resources),
                    )
                    val duplicate = AutomationDuplicator.scenePayload(source, name)
                    val newId = db.sceneDao().insert(duplicate.toEntity())
                    val persisted = duplicate.copy(id = newId)
                    db.sceneDao().update(persisted.toEntity())
                    recordCreation(EditHistoryDao.TYPE_SCENE, newId, StorageJson.encodeToString(persisted))
                }
            }.onSuccess { events.send(message(R.string.ui_message_scene_duplicated)) }
                .onFailure { events.send(errorMessage(it, R.string.ui_error_generic)) }
        }
    }

    fun removeSceneElement(scene: Scene, index: Int) {
        require(index in scene.elements.indices) { "Scene element index is out of range." }
        updateScene(
            scene.copy(elements = scene.elements.filterIndexed { elementIndex, _ -> elementIndex != index }),
            R.string.ui_message_element_removed,
            UiMessageAction.Undo(EditHistoryDao.TYPE_SCENE, scene.id),
        )
    }

    fun updateScene(
        scene: Scene,
        @StringRes successMessageRes: Int = R.string.ui_message_scene_updated,
        successAction: UiMessageAction? = null,
        onSaved: () -> Unit = {},
    ) = launchWithMessage(successMessageRes, successAction, onSaved) {
        db.withTransaction {
            val previous = scene.id.takeIf { it > 0L }?.let { db.sceneDao().getById(it) }
            if (previous != null) {
                previous.toDomainDecodeResult().issue?.let { issue ->
                    throw CorruptRecordOverwriteException(issue)
                }
                recordEdit(
                    entityType = EditHistoryDao.TYPE_SCENE,
                    entityId = scene.id,
                    previousJson = StorageJson.encodeToString(previous.toDomain()),
                    nextJson = StorageJson.encodeToString(scene),
                )
            }
            db.sceneDao().update(scene.toEntity())
        }
    }

    fun deleteScene(scene: Scene) {
        viewModelScope.launch {
            runCatching {
                db.withTransaction {
                    val current = db.sceneDao().getById(scene.id)?.toDomainDecodeResult()?.also { result ->
                        result.issue?.let { issue -> throw CorruptRecordOverwriteException(issue) }
                    }?.value ?: error("Scene no longer exists.")
                    recordDeletion(
                        entityType = EditHistoryDao.TYPE_SCENE,
                        entityId = current.id,
                        previousJson = StorageJson.encodeToString(current),
                    )
                    db.sceneDao().delete(current.toEntity())
                }
            }.onSuccess {
                events.send(
                    UiMessage(
                        resId = R.string.ui_message_scene_deleted,
                        action = UiMessageAction.Undo(EditHistoryDao.TYPE_SCENE, scene.id),
                    ),
                )
            }.onFailure { events.send(errorMessage(it, R.string.ui_error_generic)) }
        }
    }

    fun createProfile(
        name: String,
        enabled: Boolean,
        enterTaskId: Long,
        exitTaskId: Long?,
        cooldownSec: Int,
        automationMode: AutomationMode,
        group: String? = null,
        projectId: Long = DEFAULT_PROJECT_ID,
        priority: Int = 0,
        gracePeriodSec: Int = 0,
        lifetime: ProfileLifetime = ProfileLifetime.NEVER,
        expiresAtMs: Long? = null,
        maxActiveExecutions: Int? = null,
        burstLimit: Int? = null,
        overflowPolicy: ProfileOverflowPolicy = ProfileOverflowPolicy.LOG,
        fallbackTaskId: Long? = null,
        onSaved: () -> Unit = {},
    ) =
        launchCreateForMessage(R.string.ui_message_profile_created, onSaved) {
            val profile = ProfileLifecyclePolicy.normalize(
                Profile(
                name = name.trim(),
                enabled = enabled,
                enterTaskId = enterTaskId,
                exitTaskId = exitTaskId,
                cooldownSec = cooldownSec.coerceAtLeast(0),
                automationMode = automationMode,
                group = group,
                projectId = projectId,
                priority = priority,
                gracePeriodSec = gracePeriodSec,
                lifetime = lifetime,
                expiresAtMs = expiresAtMs,
                maxActiveExecutions = maxActiveExecutions,
                burstLimit = burstLimit,
                overflowPolicy = overflowPolicy,
                fallbackTaskId = fallbackTaskId,
                ),
            )
            requireValidProfileFieldLimits(profile)
            val lint = requireAutomationLint(profile)
            val reviewed = reviewFeedbackRisk(profile)
            writeSettingsGuard.requireWriteSettingsIfEnabled(reviewed)
            db.profileDao().upsert(reviewed.toEntity())
            emitLintWarnings(profile, lint)
            UiMessage(profileSavedMessageRes(profile.enabled, reviewed.enabled, R.string.ui_message_profile_created))
        }

    fun duplicateProfile(profile: Profile) {
        viewModelScope.launch {
            runCatching {
                db.withTransaction {
                    val source = db.profileDao().getById(profile.id)?.toDomainDecodeResult()?.also { result ->
                        result.issue?.let { issue -> throw CorruptRecordOverwriteException(issue) }
                    }?.value ?: error("Profile no longer exists.")
                    val name = AutomationDuplicator.copyName(
                        source.name,
                        db.profileDao().getAll().map { it.name },
                        AutomationDuplicateStrings.from(appContext.resources),
                    )
                    val duplicate = AutomationDuplicator.profilePayload(source, name)
                    requireValidProfileFieldLimits(duplicate)
                    val newId = db.profileDao().insert(duplicate.toEntity())
                    val persisted = duplicate.copy(id = newId)
                    recordCreation(EditHistoryDao.TYPE_PROFILE, newId, StorageJson.encodeToString(persisted))
                }
            }.onSuccess { events.send(message(R.string.ui_message_profile_duplicated)) }
                .onFailure { events.send(errorMessage(it, R.string.ui_error_generic)) }
        }
    }

    fun updateProfile(
        profile: Profile,
        @StringRes successMessageRes: Int = R.string.ui_message_profile_updated,
        successAction: UiMessageAction? = null,
        onSaved: () -> Unit = {},
    ) = launchForMessage(onSaved) {
            val normalized = ProfileLifecyclePolicy.normalize(profile)
            val reviewedProfile = reviewFeedbackRisk(normalized)
            requireValidProfileFieldLimits(reviewedProfile)
            val lint = requireAutomationLint(reviewedProfile)
            // Atomic read-check-snapshot-update, matching updateScene, so racing writers
            // (dialog save vs. notification/external-intent path) can't lose a revision.
            db.withTransaction {
                val previousEntity = profile.id.takeIf { it > 0L }
                    ?.let { db.profileDao().getById(it) }
                previousEntity?.toDomainDecodeResult()?.issue?.let { issue ->
                    throw CorruptRecordOverwriteException(issue)
                }
                val previous = previousEntity?.toDomain()
                if (
                    previous?.requiresRiskAcknowledgement == true &&
                    (reviewedProfile.enabled || !reviewedProfile.requiresRiskAcknowledgement)
                ) {
                    throw IllegalStateException("Review imported automation powers before enabling this profile.")
                }
                if (reviewedProfile.enabled && previous?.enabled != true) {
                    writeSettingsGuard.requireWriteSettingsIfEnabled(reviewedProfile)
                }
                if (previousEntity != null) {
                    recordEdit(
                        entityType = EditHistoryDao.TYPE_PROFILE,
                        entityId = profile.id,
                        previousJson = StorageJson.encodeToString(previous),
                        nextJson = StorageJson.encodeToString(reviewedProfile),
                    )
                }
                if (previous != null && previous.contexts != profile.contexts) {
                    locationDwellStateStore.clearProfile(profile.id)
                    previous.contexts.indices.forEach { index ->
                        LocaleConditionGrantStore(appContext).revokeAllForBinding(
                            LocaleConditionGrantStore.contextKey(profile.id, index),
                        )
                    }
                }
                db.profileDao().upsert(reviewedProfile.toEntity())
            }
            emitLintWarnings(reviewedProfile, lint)
            UiMessage(
                profileSavedMessageRes(normalized.enabled, reviewedProfile.enabled, successMessageRes),
                action = successAction,
            )
        }

    fun removeProfileContext(profile: Profile, index: Int) {
        require(index in profile.contexts.indices) { "Context index is out of range." }
        updateProfile(
            profile.copy(
                contexts = profile.contexts.filterIndexed { contextIndex, _ -> contextIndex != index },
                contextExpression = profile.contextExpression?.removeLeaf(index),
            ),
            R.string.ui_message_context_removed,
            UiMessageAction.Undo(EditHistoryDao.TYPE_PROFILE, profile.id),
        )
    }

    fun createProject(name: String, onSaved: () -> Unit = {}) = launchCreate(R.string.ui_message_project_created, onSaved = onSaved) {
        val normalized = validateProjectName(name)
        require(db.projectDao().getAll().none { it.name.equals(normalized, ignoreCase = true) }) {
            "A project with that name already exists."
        }
        val nextPosition = (db.projectDao().getAll().maxOfOrNull { it.position } ?: -1) + 1
        db.projectDao().insert(ProjectEntity(name = normalized, position = nextPosition))
    }

    fun renameProject(project: Project, name: String, onSaved: () -> Unit = {}) = launchWithMessage(R.string.ui_message_project_renamed, onSaved = onSaved) {
        require(project.id != DEFAULT_PROJECT_ID) { "The Default project cannot be renamed." }
        val normalized = validateProjectName(name)
        require(db.projectDao().getAll().none { it.id != project.id && it.name.equals(normalized, ignoreCase = true) }) {
            "A project with that name already exists."
        }
        db.projectDao().update(ProjectEntity(project.id, normalized, project.position))
    }

    fun reorderProject(project: Project, direction: Int) {
        viewModelScope.launch {
            runCatching {
                // Positions come from the stored rows, not the Project the UI passed, which can be a
                // tap behind; two quick moves used to leave two projects on one position. Writing
                // every row's index also repairs any duplicates that bug already left.
                db.withTransaction {
                    val ordered = db.projectDao().getAll().sortedWith(compareBy<ProjectEntity> { it.position }.thenBy { it.id })
                    val index = ordered.indexOfFirst { it.id == project.id }
                    val targetIndex = projectReorderTarget(index, direction, ordered.size) ?: return@withTransaction false
                    projectPositionUpdates(ordered, index, targetIndex).forEach { db.projectDao().update(it) }
                    true
                }
            }
                // "Moved" only when something moved: the first row's Move up used to report success.
                .onSuccess { moved -> if (moved) events.send(UiMessage(R.string.ui_message_project_reordered)) }
                .onFailure { events.send(errorMessage(it, R.string.ui_error_generic)) }
        }
    }

    fun deleteProject(project: Project, targetProject: Project) = launchWithMessage(
        successMessageRes = R.string.ui_message_project_deleted,
        successAction = UiMessageAction.Undo(EditHistoryDao.TYPE_PROJECT, project.id),
    ) {
        require(project.id != DEFAULT_PROJECT_ID) { "The Default project cannot be deleted." }
        require(project.id != targetProject.id) { "Choose a different destination project." }
        // Mutation lock first, then the transaction: the reverse order deadlocks against the
        // engine's variable commit path.
        variableRepository.withMutationLock {
            db.withTransaction {
                val currentProject = db.projectDao().getById(project.id)
                    ?: throw IllegalStateException("Project no longer exists.")
                val currentTarget = db.projectDao().getById(targetProject.id)
                    ?: throw IllegalStateException("Destination project no longer exists.")
                val sourceVariables = getAllStoredInProject(currentProject.id)
                val targetNames = db.variableDao().getAllInProject(currentTarget.id).map { it.name }.toSet()
                val collisions = sourceVariables.map { it.name }.filter { it in targetNames }
                require(collisions.isEmpty()) {
                    "Reassignment would overwrite variables: ${collisions.joinToString()}. Rename or remove them first."
                }
                val snapshot = ProjectDeletionSnapshot(
                    project = currentProject.toDomain(),
                    targetProjectId = currentTarget.id,
                    taskIds = db.taskDao().getAll()
                        .filter { it.projectId == currentProject.id }
                        .map { it.id }
                        .sorted(),
                    profileIds = db.profileDao().getAll()
                        .filter { it.projectId == currentProject.id }
                        .map { it.id }
                        .sorted(),
                    sceneIds = db.sceneDao().getAll()
                        .filter { it.projectId == currentProject.id }
                        .map { it.id }
                        .sorted(),
                    variableNames = sourceVariables.map { it.name }.sorted(),
                )
                recordDeletion(
                    EditHistoryDao.TYPE_PROJECT,
                    currentProject.id,
                    StorageJson.encodeToString(snapshot),
                )
                db.taskDao().reassignProject(currentProject.id, currentTarget.id)
                db.profileDao().reassignProject(currentProject.id, currentTarget.id)
                db.sceneDao().reassignProject(currentProject.id, currentTarget.id)
                // Secrets must be re-encrypted, not row-copied: their envelope binds the project id.
                reassignProject(currentProject.id, currentTarget.id)
                check(db.projectDao().deleteIfNotDefault(currentProject.id) == 1) { "Project no longer exists." }
            }
        }
    }

    private fun validateProjectName(name: String): String {
        val normalized = name.trim()
        require(normalized.isNotEmpty()) { "Project name cannot be empty." }
        require(normalized.length <= 64) { "Project names must be 64 characters or fewer." }
        return normalized
    }

    private suspend fun reviewFeedbackRisk(profile: Profile): Profile {
        if (!profile.enabled || profile.requiresRiskAcknowledgement) return profile
        val tasks = db.taskDao().getAll().map { it.toDomain() }
        return if (AutomationFeedbackRiskAnalyzer.analyze(profile, tasks).isEmpty()) {
            profile
        } else {
            profile.copy(enabled = false, requiresRiskAcknowledgement = true)
        }
    }

    fun acknowledgeAndEnableImportedProfile(profileId: Long) =
        launchWithMessage(R.string.ui_message_profile_reviewed) {
            val current = db.profileDao().getById(profileId)?.toDomain()
                ?: throw IllegalStateException("Profile no longer exists.")
            check(current.requiresRiskAcknowledgement) { "Profile review is no longer required." }
            val tasks = db.taskDao().getAll().map { it.toDomain() }
            val peers = db.profileDao().getAll().map { entity ->
                entity.toDomainDecodeResult().also { result ->
                    result.issue?.let { issue -> throw CorruptRecordOverwriteException(issue) }
                }.value
            }
            val review = ImportedProfileEnablePolicy.review(
                profile = current,
                tasks = tasks,
                otherProfiles = peers,
                strings = AutomationLintStrings.from(appContext.resources),
            )
            check(review.canAcknowledge) {
                "Resolve unsupported actions, missing references, and blocking automation lint findings before enabling this imported profile."
            }
            writeSettingsGuard.requireWriteSettingsIfEnabled(current.copy(enabled = true))
            val enabledProfile = current.copy(
                enabled = true,
                requiresRiskAcknowledgement = false,
            )
            val lint = requireAutomationLint(enabledProfile)
            db.withTransaction {
                // Acknowledging risk and enabling an imported profile is a real edit to that
                // profile, and it was the one profile write that recorded no history - so the
                // step that arms an unreviewed automation was the one the user could not undo.
                recordEdit(
                    entityType = EditHistoryDao.TYPE_PROFILE,
                    entityId = current.id,
                    previousJson = StorageJson.encodeToString(current),
                    nextJson = StorageJson.encodeToString(enabledProfile),
                )
                db.profileDao().upsert(enabledProfile.toEntity())
            }
            emitLintWarnings(enabledProfile, lint)
        }

    fun deleteProfile(profile: Profile) {
        viewModelScope.launch {
            runCatching {
                db.withTransaction {
                    val current = db.profileDao().getById(profile.id)?.toDomainDecodeResult()?.also { result ->
                        result.issue?.let { issue -> throw CorruptRecordOverwriteException(issue) }
                    }?.value ?: error("Profile no longer exists.")
                    recordDeletion(
                        entityType = EditHistoryDao.TYPE_PROFILE,
                        entityId = current.id,
                        previousJson = StorageJson.encodeToString(current),
                    )
                    db.profileDao().delete(current.toEntity())
                }
                LocaleConditionGrantStore(appContext).apply {
                    revokeAllForBinding(LocaleConditionGrantStore.profileKey(profile.id))
                    profile.contexts.indices.forEach { index ->
                        revokeAllForBinding(LocaleConditionGrantStore.contextKey(profile.id, index))
                    }
                }
                locationDwellStateStore.clearProfile(profile.id)
            }.onSuccess {
                events.send(
                    UiMessage(
                        resId = R.string.ui_message_profile_deleted,
                        action = UiMessageAction.Undo(EditHistoryDao.TYPE_PROFILE, profile.id),
                    ),
                )
            }.onFailure { events.send(errorMessage(it, R.string.ui_error_generic)) }
        }
    }

    fun installProfileTemplate(template: ProfileTemplate, slotValues: Map<String, String>, onSaved: () -> Unit = {}) =
        launchCreate(R.string.ui_message_template_installed, onSaved = onSaved) {
            val applied = template.instantiate(slotValues)
            val resolvedValues = template.defaults() + slotValues.mapValues { it.value.trim() }
            var taskId = 0L
            var exitTaskId: Long? = null
            var profileId = 0L
            db.withTransaction {
                taskId = db.taskDao().insert(applied.task.toEntity())
                exitTaskId = applied.exitTask?.let { db.taskDao().insert(it.toEntity()) }
                profileId = db.profileDao().insert(
                    applied.profile.copy(enabled = false, enterTaskId = taskId, exitTaskId = exitTaskId).toEntity(),
                )
            }
            blueprintCatalogStore.merge(listOf(template))
            blueprintInstallationStore.record(
                BlueprintInstallation(
                    blueprintId = template.id,
                    blueprintVersion = template.version,
                    profileId = profileId,
                    taskId = taskId,
                    inputValues = resolvedValues,
                    exitTaskId = exitTaskId,
                ),
            )
        }

    fun previewLocalProfileShare(appVersion: String) {
        bundleTransfer.launch { reportStage ->
            runTransferCatching {
                withContext(Dispatchers.IO) {
                    reportStage(TransferStage.Plan)
                    val bundle = bundleRepository.exportBundle(
                        appVersion = appVersion,
                        name = "OpenTasker Community Share",
                        description = "A local OpenTasker profile share draft.",
                    )
                    buildProfileShareReview(bundle)
                }
            }
                .onSuccess { _profileShareReview.value = it }
                .onFailure { events.send(errorMessage(it, R.string.ui_error_share_preview)) }
        }
    }

    fun previewTaskerOrMacroDroid(uri: Uri, appVersion: String) {
        automationTransfer.launch(FILE_PREVIEW_STEPS) { report ->
            runTransferCatching {
                withContext(Dispatchers.IO) {
                    report(TransferStage.Preflight)
                    val bytes = readBoundedTaskerOrMacroDroidBytes(appContext, uri)
                    report(TransferStage.Decode)
                    val raw = when (val document = TaskerOrMacroDroidDocument.classify(bytes)) {
                        is TaskerOrMacroDroidDocument.Text -> document.text
                        TaskerOrMacroDroidDocument.Unrecognized ->
                            throw UiRejection(R.string.ui_error_import_not_recognized)
                        TaskerOrMacroDroidDocument.MacroDroidEncrypted -> {
                            _macroDroidPasswordPrompt.value = MacroDroidPasswordPrompt(bytes)
                            return@withContext null
                        }
                    }
                    if (raw.removePrefix("\uFEFF").trimStart().startsWith("<")) {
                        taskerImportReviewState(TaskerXmlImporter.parse(rawXml = raw, appVersion = appVersion))
                    } else {
                        macroDroidImportReviewState(MacroDroidImporter.parse(rawJson = raw, appVersion = appVersion))
                    }
                }
            }
                .onSuccess { review ->
                    if (review != null) {
                        _taskerImportReview.value = review
                        events.send(message(R.string.ui_message_automation_import_ready))
                    }
                }
                .onFailure { events.send(errorMessage(it, R.string.ui_error_automation_import_preview)) }
        }
    }

    /** A wrong password leaves the prompt up to try again. The password goes no further than here. */
    fun openMacroDroidEncryptedExport(password: String, appVersion: String) {
        val prompt = _macroDroidPasswordPrompt.value ?: return
        automationTransfer.launch(FILE_PREVIEW_STEPS) { report ->
            runTransferCatching {
                withContext(Dispatchers.IO) {
                    report(TransferStage.Decode)
                    MacroDroidEncryptedExport.decrypt(prompt.bytes, password.toCharArray())?.let { json ->
                        macroDroidImportReviewState(MacroDroidImporter.parse(rawJson = json, appVersion = appVersion))
                    }
                }
            }
                .onSuccess { review ->
                    if (review == null) {
                        _macroDroidPasswordPrompt.value = prompt.copy(wrongPassword = true)
                    } else {
                        _macroDroidPasswordPrompt.value = null
                        _taskerImportReview.value = review
                        events.send(message(R.string.ui_message_automation_import_ready))
                    }
                }
                .onFailure {
                    _macroDroidPasswordPrompt.value = null
                    events.send(errorMessage(it, R.string.ui_error_automation_import_preview))
                }
        }
    }

    fun clearMacroDroidPasswordPrompt() {
        _macroDroidPasswordPrompt.value = null
    }

    fun clearTaskerImportReview() {
        if (!taskerImportBusy.value) {
            _taskerImportReview.value = null
        }
    }

    internal fun confirmTaskerImport(state: TaskerImportReviewState) {
        automationTransfer.launch { report ->
            runTransferCatching {
                withContext(Dispatchers.IO) {
                    report(TransferStage.Write)
                    bundleRepository.importBundle(state.bundle)
                }
            }
                .onSuccess { importReport ->
                    _taskerImportReview.value = null
                    events.send(
                        message(
                            R.string.ui_message_tasker_imported,
                            importReport.insertedTasks,
                            importReport.insertedProfiles,
                        ),
                    )
                }
                .onFailure { events.send(errorMessage(it, R.string.ui_error_automation_import)) }
        }
    }

    fun exportOpenTaskerBundle(uri: Uri, appVersion: String) {
        bundleTransfer.launch { report ->
            runTransferCatching {
                withContext(Dispatchers.IO) {
                    report(TransferStage.Write)
                    val bundle = bundleRepository.exportBundle(
                        appVersion = appVersion,
                        name = "OpenTasker Workspace Export",
                        description = "Profiles, tasks, variables, and scenes exported from OpenTasker.",
                    )
                    val encoded = OpenTaskerBundleCodec.encode(bundle)
                    val stream = appContext.contentResolver.openOutputStream(uri)
                        ?: error("Unable to open export destination")
                    stream.bufferedWriter(Charsets.UTF_8).use { writer -> writer.write(encoded) }
                    bundle
                }
            }
                .onSuccess { bundle ->
                    events.send(
                        message(
                            R.string.ui_message_bundle_exported,
                            bundle.tasks.size,
                            bundle.profiles.size,
                            bundle.scenes.size,
                        ),
                    )
                }
                .onFailure { events.send(errorMessage(it, R.string.ui_error_bundle_export)) }
        }
    }

    /**
     * Writes the workspace as Tasker XML.
     *
     * The exporter shipped unreachable: nothing in the app called it, so the changelog claimed a
     * feature users could not run and its redaction path - the only export path that can match a
     * secret's literal plaintext - was never exercised outside tests.
     */
    fun exportTaskerXml(uri: Uri) {
        automationTransfer.launch { reportStage ->
            runTransferCatching {
                withContext(Dispatchers.IO) {
                    reportStage(TransferStage.Write)
                    val report = TaskerXmlExporter.export(
                        profiles = db.profileDao().getAll().map { it.toDomain() },
                        tasks = db.taskDao().getAll().map { it.toDomain() },
                        variables = variableRepository.decodedForExportRedaction(),
                    )
                    val stream = appContext.contentResolver.openOutputStream(uri)
                        ?: error("Unable to open export destination")
                    stream.bufferedWriter(Charsets.UTF_8).use { writer -> writer.write(report.xml) }
                    report
                }
            }
                .onSuccess { report ->
                    events.send(
                        message(
                            R.string.ui_message_tasker_xml_exported,
                            report.exportedProfileCount,
                            report.exportedTaskCount,
                            report.skippedActions.size,
                        ),
                    )
                    if (report.warnings.isNotEmpty()) {
                        events.send(
                            message(
                                R.string.ui_message_tasker_xml_export_warnings,
                                report.warnings.size,
                                report.warnings.take(MAX_EXPORT_WARNINGS_SHOWN).joinToString(" "),
                            ).copy(longDuration = true),
                        )
                    }
                }
                .onFailure { events.send(errorMessage(it, R.string.ui_error_tasker_xml_export)) }
        }
    }

    fun previewOpenTaskerBundle(uri: Uri) {
        previewOpenTaskerBundleSource {
            OpenTaskerBundleCodec.decode(readBoundedOpenTaskerBundle(appContext, uri))
        }
    }

    /**
     * One paste box, two formats. Tasker XML opens the same review the document picker does, so a
     * copied Tasker task and a Tasker file end up in the same place.
     */
    fun previewPastedImport(rawText: String, appVersion: String) {
        when (PastedImportSource.classify(rawText)) {
            PastedImportKind.TASKER_XML -> previewTaskerXmlText(rawText, appVersion)
            PastedImportKind.OPEN_TASKER_JSON -> previewOpenTaskerBundleText(rawText)
        }
    }

    fun previewOpenTaskerBundleText(rawText: String) {
        previewOpenTaskerBundleSource {
            OpenTaskerBundleTextImport.decode(rawText)
        }
    }

    private fun previewTaskerXmlText(rawText: String, appVersion: String) {
        automationTransfer.launch(FILE_PREVIEW_STEPS) { reportStage ->
            runTransferCatching {
                withContext(Dispatchers.IO) {
                    reportStage(TransferStage.Preflight)
                    val rawXml = PastedImportSource.requireTaskerXmlWithinBudget(rawText)
                    reportStage(TransferStage.Decode)
                    val report = TaskerXmlImporter.parse(rawXml = rawXml, appVersion = appVersion)
                    taskerImportReviewState(report)
                }
            }
                .onSuccess {
                    _taskerImportReview.value = it
                    events.send(message(R.string.ui_message_tasker_xml_ready))
                }
                .onFailure { events.send(errorMessage(it, R.string.ui_error_tasker_xml_preview)) }
        }
    }

    private fun previewOpenTaskerBundleSource(load: suspend () -> OpenTaskerBundle) {
        bundleTransfer.launch { reportStage ->
            runTransferCatching {
                withContext(Dispatchers.IO) {
                    reportStage(TransferStage.Decode)
                    buildProfileShareReview(load())
                }
            }
                .onSuccess {
                    _profileShareReview.value = it
                    events.send(message(R.string.ui_message_bundle_ready))
                }
                .onFailure { error ->
                    // A decode failure means the input is not an OpenTasker bundle. Surfacing the
                    // serializer's own text put "Unexpected JSON token at offset 0: Expected start
                    // of the object '{'" in front of the user, along with their raw input.
                    if (error is SerializationException) {
                        AppLogger.warn("OpenTasker", "Rejected an OpenTasker bundle that failed to decode", error)
                        events.send(message(R.string.ui_error_bundle_not_recognized))
                    } else {
                        events.send(errorMessage(error, R.string.ui_error_bundle_preview))
                    }
                }
        }
    }

    fun updateProfileShareDraft(draft: ProfileShareDraft) {
        val current = _profileShareReview.value ?: return
        runCatching { ProfileShareLibrary.buildManifest(draft) }
            .onSuccess { manifest ->
                _profileShareReview.value = current.copy(
                    draft = draft,
                    manifest = manifest,
                    draftError = null,
                )
            }
            .onFailure { error ->
                AppLogger.warn("OpenTasker.UI", "Profile share validation failed", error)
                _profileShareReview.value = current.copy(
                    draft = draft,
                    draftError = appContext.getString(R.string.profile_share_invalid_details_body),
                )
            }
    }

    fun addProfileShareScreenshots(uris: List<Uri>) {
        val current = _profileShareReview.value ?: return
        val screenshots = (current.draft.screenshots + uris.map(Uri::toString))
            .distinct()
            .take(PROFILE_SHARE_MAX_SCREENSHOTS)
        updateProfileShareDraft(current.draft.copy(screenshots = screenshots))
    }

    fun removeProfileShareScreenshot(uri: String) {
        val current = _profileShareReview.value ?: return
        updateProfileShareDraft(current.draft.copy(screenshots = current.draft.screenshots - uri))
    }

    fun clearProfileShareReview() {
        if (!openTaskerBundleBusy.value) {
            _profileShareReview.value = null
        }
    }

    fun continueProfileShareImportReview() {
        val share = _profileShareReview.value ?: return
        if (share.draftError != null || share.manifest.hasBlockingFindings || !share.plan.canImport) return
        _openTaskerBundleReview.value = OpenTaskerBundleReviewState(
            bundle = share.draft.bundle,
            plan = share.plan,
        )
        _profileShareReview.value = null
    }

    private suspend fun buildProfileShareReview(bundle: OpenTaskerBundle): ProfileShareReviewState {
        val draft = ProfileShareDraft(
            slug = defaultProfileShareSlug(bundle.metadata.name),
            title = bundle.metadata.name.ifBlank { "OpenTasker Share" },
            summary = bundle.metadata.description.ifBlank {
                "A local OpenTasker profile share draft."
            },
            bundle = bundle,
        )
        return ProfileShareReviewState(
            draft = draft,
            manifest = ProfileShareLibrary.buildManifest(draft),
            plan = bundleRepository.planImport(bundle),
        )
    }

    fun clearOpenTaskerBundleReview() {
        if (!openTaskerBundleBusy.value) {
            _openTaskerBundleReview.value = null
        }
    }

    fun resolveOpenTaskerVariableConflict(name: String, resolution: VariableConflictResolution) {
        if (openTaskerBundleBusy.value) return
        val review = _openTaskerBundleReview.value ?: return
        if (review.plan.variableConflicts.none { it.name == name }) return
        _openTaskerBundleReview.value = review.copy(
            variableResolutions = review.variableResolutions + (name to resolution),
        )
    }

    fun confirmOpenTaskerBundleImport() {
        val review = _openTaskerBundleReview.value ?: return
        if (review.plan.variableConflicts.any { it.name !in review.variableResolutions }) return
        bundleTransfer.launch { reportStage ->
            runTransferCatching {
                withContext(Dispatchers.IO) {
                    reportStage(TransferStage.Write)
                    bundleRepository.importBundle(review.bundle, review.variableResolutions)
                }
            }
                .onSuccess { importReport ->
                    _openTaskerBundleReview.value = null
                    events.send(
                        message(
                            R.string.ui_message_bundle_imported,
                            importReport.insertedTasks,
                            importReport.insertedProfiles,
                            importReport.insertedScenes,
                        ),
                    )
                }
                .onFailure { events.send(errorMessage(it, R.string.ui_error_bundle_import)) }
        }
    }

    fun updateGlobalFallbackTask(taskId: Long?) {
        val normalized = taskId?.takeIf { it > 0L }
        fallbackTaskSettings.saveTaskId(normalized)
        _globalFallbackTaskId.value = normalized
    }

    /**
     * Clears the global fallback task when it points at a task that no longer exists.
     *
     * The setting lives in SharedPreferences and cannot join the Room transaction that deletes a
     * task, so process death between the commit and the settings write leaves a dangling id.
     * Healing it on load keeps that window harmless instead of leaving a fallback that silently
     * never runs.
     */
    private suspend fun reconcileGlobalFallbackTask() {
        val storedId = fallbackTaskSettings.loadTaskId() ?: return
        if (db.taskDao().getById(storedId) != null) return
        fallbackTaskSettings.saveTaskId(null)
        _globalFallbackTaskId.value = null
    }

    fun runTaskNow(task: Task) {
        viewModelScope.launch {
            if (_runActionBusy.value) { events.send(message(R.string.ui_message_run_busy)); return@launch }
            _runActionBusy.value = true
            runCatching {
                writeSettingsGuard.requireWriteSettingsReady(task.actions)
                executeAndLogTask(
                    appContext = appContext,
                    db = db,
                    task = task,
                    source = "Manual run",
                    // Admit against the engine's live controller. The default is a separate
                    // in-memory one that admits even while the profile is saturated.
                    admissionController = ExecutionAdmissionRegistry.current(appContext),
                    execution = ExecutionEnvelope.create(task, "Manual run"),
                )
            }.onSuccess { result ->
                val status = when {
                    result.held -> appContext.getString(R.string.ui_run_status_held)
                    result.skippedReason != null -> appContext.getString(R.string.ui_run_status_skipped)
                    result.report.success -> appContext.getString(R.string.ui_run_status_succeeded)
                    else -> appContext.getString(R.string.ui_run_status_failed)
                }
                events.send(message(R.string.ui_message_run_status, task.name, status, result.report.durationMs))
                // A manual run can be held, which adds a row the run-log page should show.
                runLog.refreshRunLogPage()
            }.onFailure { events.send(errorMessage(it, R.string.ui_error_run_task)) }
            _runActionBusy.value = false
        }
    }

    /**
     * Runs one action of [task] on its own, so tuning an HTTP call or a variable write does not
     * mean re-running everything before it.
     *
     * It goes through the same execution path as a whole-task manual run, against the engine's
     * live admission controller, so limits, the collision policy and the run log all behave as
     * they do for a real run. Flow-control markers are refused: the UI does not offer them, and
     * [SingleActionRun.taskFor] refuses them again in case a stale index arrives.
     */
    fun runActionNow(task: Task, index: Int) {
        viewModelScope.launch {
            if (_runActionBusy.value) { events.send(message(R.string.ui_message_run_busy)); return@launch }
            val single = SingleActionRun.taskFor(task, index) ?: return@launch
            val action = single.actions.first()
            val label = action.label
                ?: ActionMetadataRegistry.get(action.type)?.let { appContext.getString(it.nameRes) }
                ?: action.type
            val source = SingleActionRun.sourceFor(label)
            _runActionBusy.value = true
            runCatching {
                writeSettingsGuard.requireWriteSettingsReady(single.actions)
                executeAndLogTask(
                    appContext = appContext,
                    db = db,
                    task = single,
                    source = source,
                    admissionController = ExecutionAdmissionRegistry.current(appContext),
                    execution = ExecutionEnvelope.create(single, source),
                )
            }.onSuccess { result ->
                val status = when {
                    result.held -> appContext.getString(R.string.ui_run_status_held)
                    result.skippedReason != null -> appContext.getString(R.string.ui_run_status_skipped)
                    result.report.success -> appContext.getString(R.string.ui_run_status_succeeded)
                    else -> appContext.getString(R.string.ui_run_status_failed)
                }
                // The label comes from the action's own name or its metadata, never from its
                // arguments, so a credential in an argument cannot reach this snackbar.
                events.send(message(R.string.ui_message_action_run_status, label, status, result.report.durationMs))
                runLog.refreshRunLogPage()
            }.onFailure { events.send(errorMessage(it, R.string.ui_error_run_action)) }
            _runActionBusy.value = false
        }
    }

    fun replayHeldRun(entry: RunLogEntry) {
        viewModelScope.launch {
            if (_runActionBusy.value) { events.send(message(R.string.ui_message_run_busy)); return@launch }
            _runActionBusy.value = true
            runCatching {
                replayHeldExecution(
                    appContext = appContext,
                    db = db,
                    heldEntry = entry,
                    admissionController = ExecutionAdmissionRegistry.current(appContext),
                )
            }.onSuccess { result ->
                val status = when {
                    result.held -> appContext.getString(R.string.ui_run_status_held)
                    result.report.success -> appContext.getString(R.string.ui_run_status_succeeded)
                    else -> appContext.getString(R.string.ui_run_status_failed)
                }
                events.send(message(R.string.ui_message_run_replayed, entry.taskName, status, result.report.durationMs))
                runLog.refreshRunLogPage()
            }.onFailure { events.send(errorMessage(it, R.string.ui_error_run_log_replay)) }
            _runActionBusy.value = false
        }
    }

    fun previewTaskPreflight(task: Task) {
        startPreflight(PreflightTarget.TaskTarget(task), PreflightInputs())
    }

    fun previewProfilePreflight(profile: Profile) {
        startPreflight(PreflightTarget.ProfileTarget(profile), PreflightInputs())
    }

    fun rerunPreflight(eventVariables: Map<String, String>) {
        val current = _preflightReview.value ?: return
        startPreflight(
            target = current.target,
            requested = current.inputs.copy(eventVariables = eventVariables),
        )
    }

    fun clearPreflightReview() {
        if (!preflightBusy.value) _preflightReview.value = null
    }

    /** Stops a preflight rerun; the last report stays on screen. */
    fun stopPreflight() = preflightTransfer.cancel()

    private fun startPreflight(target: PreflightTarget, requested: PreflightInputs) {
        preflightTransfer.launch { reportStage ->
            runTransferCatching {
                // Read the live connection here rather than in the runner: the preview is pure
                // and stays that way, and an HTTP Request limited to Wi-Fi should say so while
                // the user is looking at it.
                val inputs = requested.copy(activeTransport = readActiveTransport(appContext))
                withContext(Dispatchers.Default) {
                    reportStage(TransferStage.Plan)
                    val availableTasks = tasks.value.toList()
                    val report = when (target) {
                        is PreflightTarget.TaskTarget -> PreflightRunner.preflightTask(
                            task = target.task,
                            tasks = availableTasks,
                            inputs = inputs,
                        )
                        is PreflightTarget.ProfileTarget -> PreflightRunner.preflightProfile(
                            profile = target.profile,
                            tasks = availableTasks,
                            inputs = inputs,
                        )
                    }
                    PreflightReviewState(target, inputs, report)
                }
            }.onSuccess { _preflightReview.value = it }
                .onFailure { events.send(errorMessage(it, R.string.ui_error_preflight)) }
        }
    }

    fun pinTaskShortcut(task: Task) {
        viewModelScope.launch {
            if (!TaskShortcutHelper.canPinShortcut(appContext)) {
                events.send(message(R.string.ui_message_shortcut_unsupported))
                return@launch
            }
            val requested = TaskShortcutHelper.requestPinShortcut(appContext, task)
            if (requested) {
                events.send(message(R.string.ui_message_shortcut_pinning, task.name))
            } else {
                events.send(message(R.string.ui_message_shortcut_failed))
            }
        }
    }

    private fun transitionEditAsync(entityType: String, entityId: Long, redo: Boolean) {
        _highlightedFlowNodeKeys.value = emptySet()
        viewModelScope.launch {
            runCatching { editHistoryTransitions.transition(entityType, entityId, redo) }
                .onSuccess { diff ->
                    val changed = diff != null
                    if (changed && entityType == EditHistoryDao.TYPE_TASK) TaskWidgetProvider.requestRefresh(appContext)
                    if (diff != null && !diff.isEmpty) {
                        _semanticDiffReview.value = SemanticDiffReviewState(diff)
                        _highlightedFlowNodeKeys.value = diff.flowNodeKeys
                    }
                    val messageRes = when {
                        changed && redo -> R.string.ui_message_edit_redone
                        changed -> R.string.ui_message_edit_undone
                        redo -> R.string.ui_message_no_redo_history
                        else -> R.string.ui_message_no_edit_history
                    }
                    events.send(message(messageRes))
                }
                .onFailure { events.send(errorMessage(it, if (redo) R.string.ui_error_redo else R.string.ui_error_undo)) }
        }
    }

    fun clearSemanticDiffReview() {
        _semanticDiffReview.value = null
    }

    fun undoLastTaskEdit(taskId: Long) = transitionEditAsync(EditHistoryDao.TYPE_TASK, taskId, redo = false)
    fun redoLastTaskEdit(taskId: Long) = transitionEditAsync(EditHistoryDao.TYPE_TASK, taskId, redo = true)
    fun undoLastProfileEdit(profileId: Long) = transitionEditAsync(EditHistoryDao.TYPE_PROFILE, profileId, redo = false)
    fun redoLastProfileEdit(profileId: Long) = transitionEditAsync(EditHistoryDao.TYPE_PROFILE, profileId, redo = true)
    fun undoLastSceneEdit(sceneId: Long) = transitionEditAsync(EditHistoryDao.TYPE_SCENE, sceneId, redo = false)
    fun redoLastSceneEdit(sceneId: Long) = transitionEditAsync(EditHistoryDao.TYPE_SCENE, sceneId, redo = true)
    fun undoLastVariableDelete(historyId: Long) = transitionEditAsync(EditHistoryDao.TYPE_VARIABLE, historyId, redo = false)
    fun redoLastVariableDelete(historyId: Long) = transitionEditAsync(EditHistoryDao.TYPE_VARIABLE, historyId, redo = true)
    fun undoLastProjectDelete(projectId: Long) = transitionEditAsync(EditHistoryDao.TYPE_PROJECT, projectId, redo = false)
    fun redoLastProjectDelete(projectId: Long) = transitionEditAsync(EditHistoryDao.TYPE_PROJECT, projectId, redo = true)

    fun updateVariable(
        previousName: String?,
        name: String,
        value: String,
        isSecret: Boolean,
        successMessage: UiMessage,
        projectId: Long = DEFAULT_PROJECT_ID,
        onSaved: () -> Unit = {},
    ) {
        viewModelScope.launch {
            runCatching {
                val globalName = requireNotNull(VariableNamePolicy.promoteToGlobal(name)) {
                    appContext.getString(R.string.ui_error_invalid_variable_name)
                }
                val updated = Variable(
                    globalName,
                    value,
                    isGlobal = true,
                    isSecret = isSecret,
                    projectId = projectId,
                )
                val previous = previousName?.let {
                    variableRepository.get(it, projectId)
                        ?: throw IllegalStateException("Variable '%$it' no longer exists.")
                }
                if (previous == null || previous.name == globalName) {
                    variableRepository.upsert(updated)
                } else {
                    // Mutation lock first, then the transaction: the reverse order deadlocks
                    // against the engine's variable commit path.
                    variableRepository.withMutationLock {
                        db.withTransaction {
                            val (profiles, tasks, scenes) = loadDecodedAutomation()
                            val rewrite = AutomationReferenceRewriter.renameVariable(
                                target = previous,
                                replacementName = globalName,
                                profiles = profiles,
                                tasks = tasks,
                                scenes = scenes,
                            )
                            rewrite.profiles.forEach { rewritten ->
                                val current = profiles.first { it.id == rewritten.id }
                                recordEdit(
                                    entityType = EditHistoryDao.TYPE_PROFILE,
                                    entityId = rewritten.id,
                                    previousJson = StorageJson.encodeToString(current),
                                    nextJson = StorageJson.encodeToString(rewritten),
                                )
                                db.profileDao().upsert(rewritten.toEntity())
                            }
                            rewrite.tasks.forEach { rewritten ->
                                val current = tasks.first { it.id == rewritten.id }
                                recordEdit(
                                    entityType = EditHistoryDao.TYPE_TASK,
                                    entityId = rewritten.id,
                                    previousJson = StorageJson.encodeToString(current),
                                    nextJson = StorageJson.encodeToString(rewritten),
                                )
                                db.taskDao().update(rewritten.toEntity())
                            }
                            rewrite.scenes.forEach { rewritten ->
                                val current = scenes.first { it.id == rewritten.id }
                                recordEdit(
                                    entityType = EditHistoryDao.TYPE_SCENE,
                                    entityId = rewritten.id,
                                    previousJson = StorageJson.encodeToString(current),
                                    nextJson = StorageJson.encodeToString(rewritten),
                                )
                                db.sceneDao().update(rewritten.toEntity())
                            }
                            rename(previous.name, updated)
                        }
                    }
                }
                onSaved()
                events.send(successMessage)
            }.onFailure { error ->
                events.send(errorMessage(error, R.string.ui_error_variable_save))
            }
        }
    }

    fun deleteVariable(name: String, successMessage: UiMessage, projectId: Long = DEFAULT_PROJECT_ID) {
        var deletedVariableBinding: String? = null
        var deletedVariableHistoryId: Long? = null
        viewModelScope.launch {
            runCatching {
                // Mutation lock first, then the transaction: the reverse order deadlocks against
                // the engine's variable commit path.
                variableRepository.withMutationLock {
                    db.withTransaction {
                        val stored = getStored(name, projectId)
                            ?: throw IllegalStateException("Variable '%$name' no longer exists.")
                        val variable = get(name, projectId)
                            ?: throw IllegalStateException("Variable '%$name' no longer exists.")
                        val (profiles, tasks, scenes) = loadDecodedAutomation()
                        val guard = AutomationReferenceRewriter.guardVariableDeletion(
                            target = variable,
                            profiles = profiles,
                            tasks = tasks,
                            scenes = scenes,
                        )
                        if (!guard.canCommit) {
                            val sites = guard.blocked.map { it.describe() }.distinct().joinToString("; ")
                            throw UiRejection(
                                R.string.ui_error_variable_referenced,
                                listOf("%${variable.name}", sites),
                            )
                        }
                        val historyId = VariableEditHistoryIdentity.entityId(stored.projectId, stored.name)
                        recordDeletion(
                            EditHistoryDao.TYPE_VARIABLE,
                            historyId,
                            StorageJson.encodeToString(stored),
                        )
                        delete(variable.name, projectId)
                        deletedVariableHistoryId = historyId
                        deletedVariableBinding = LocaleConditionGrantStore.variableKey(projectId, variable.name)
                    }
                }
            }
                .onSuccess {
                    deletedVariableBinding?.let { LocaleConditionGrantStore(appContext).revokeAllForBinding(it) }
                    val action = deletedVariableHistoryId?.let {
                        UiMessageAction.Undo(EditHistoryDao.TYPE_VARIABLE, it)
                    }
                    events.send(successMessage.copy(action = action))
                }
                .onFailure { events.send(errorMessage(it, R.string.ui_error_variable_delete)) }
        }
    }

    /**
     * Rejects a profile whose per-field limits (name length, cooldown range) are out of bounds
     * before it is written. Structural completeness (enter task, contexts) is intentionally not
     * gated here so incremental editing can save partial profiles; the engine no-ops on those.
     */
    private fun requireValidProfileFieldLimits(profile: Profile) {
        val violation = InputValidation.validateProfile(profile)
            .firstOrNull {
                it.field == "name" ||
                    it.field == "cooldownSec" ||
                    it.field == "priority" ||
                    it.field == "gracePeriodSec" ||
                    it.field == "expiresAtMs" ||
                    it.field == "maxActiveExecutions" ||
                    it.field == "burstLimit"
            }
        if (violation != null) {
            throw IllegalArgumentException(violation.message)
        }
    }

    private suspend fun requireAutomationLint(profile: Profile): AutomationLintReport {
        val peers = db.profileDao().getAll().map { entity ->
            entity.toDomainDecodeResult().also { result ->
                result.issue?.let { issue -> throw CorruptRecordOverwriteException(issue) }
            }.value
        }.filterNot { it.id == profile.id }
        val tasks = db.taskDao().getAll().map { entity ->
            entity.toDomainDecodeResult().also { result ->
                result.issue?.let { issue -> throw CorruptRecordOverwriteException(issue) }
            }.value
        }
        val report = AutomationLint.analyze(
            peers + profile,
            tasks,
            strings = AutomationLintStrings.from(appContext.resources),
        )
        val blockers = report.blockingFor(profile.id)
        require(blockers.isEmpty()) {
            blockers.joinToString(" ") { finding ->
                "${finding.title}: ${finding.detail} ${finding.suggestedFix}"
            }
        }
        return report
    }

    private suspend fun emitLintWarnings(profile: Profile, report: AutomationLintReport) {
        val warningCount = report.forProfile(profile.id).count { it.severity == AutomationLintSeverity.WARNING }
        if (warningCount > 0) {
            events.send(pluralMessage(R.plurals.ui_profile_lint_warnings, warningCount, warningCount))
        }
    }

    /**
     * Every profile, task, and scene decoded, refusing to proceed if any stored record is corrupt.
     * Variable rename and delete both rewrite references across all three, so they must read the
     * same consistent view rather than silently skipping a record they could not decode.
     */
    private suspend fun loadDecodedAutomation(): Triple<List<Profile>, List<Task>, List<Scene>> {
        fun <T> decode(decoded: StorageDecodeResult<T>): T {
            decoded.issue?.let { throw CorruptRecordOverwriteException(it) }
            return decoded.value
        }
        return Triple(
            db.profileDao().getAll().map { decode(it.toDomainDecodeResult()) },
            db.taskDao().getAll().map { decode(it.toDomainDecodeResult()) },
            db.sceneDao().getAll().map { decode(it.toDomainDecodeResult()) },
        )
    }

    /**
     * [onSaved] runs only when [block] succeeded. Editors use it to close themselves: closing
     * unconditionally at the call site is what discarded a whole form whenever validation the
     * dialog cannot perform (automation lint, duplicate names, reference guards) rejected the save.
     */
    private fun launchWithMessage(
        @StringRes successMessageRes: Int,
        successAction: UiMessageAction? = null,
        onSaved: () -> Unit = {},
        block: suspend () -> Unit,
    ) = launchForMessage(onSaved) {
        block()
        UiMessage(successMessageRes, action = successAction)
    }

    private fun launchForMessage(onSaved: () -> Unit = {}, block: suspend () -> UiMessage) {
        viewModelScope.launch {
            runCatching { block() }
                .onSuccess { message ->
                    onSaved()
                    events.send(message)
                }
                .onFailure { events.send(errorMessage(it, R.string.ui_error_generic)) }
        }
    }

    // A create dialog stays open until the insert lands, so a second tap on Save used to insert a
    // second row. One create of each kind runs at a time; nothing suspends between the insert and
    // onSaved closing the dialog, so no tap can slip in after the slot clears.
    private val createsInFlight = ConcurrentHashMap.newKeySet<Int>()

    private fun launchCreate(
        @StringRes successMessageRes: Int,
        onSaved: () -> Unit = {},
        block: suspend () -> Unit,
    ) = launchCreateForMessage(successMessageRes, onSaved) {
        block()
        UiMessage(successMessageRes)
    }

    private fun launchCreateForMessage(slot: Int, onSaved: () -> Unit = {}, block: suspend () -> UiMessage) {
        if (!createsInFlight.add(slot)) return
        launchForMessage(onSaved) {
            try {
                block()
            } finally {
                createsInFlight.remove(slot)
            }
        }
    }
}

private const val MAX_EXPORT_WARNINGS_SHOWN = 3

/** The feedback-loop review can switch a profile off as it saves, and "updated" alone hid that. */
@StringRes
internal fun profileSavedMessageRes(requestedEnabled: Boolean, savedEnabled: Boolean, @StringRes savedRes: Int): Int =
    if (requestedEnabled && !savedEnabled) R.string.ui_message_profile_left_off_for_review else savedRes
internal const val PROFILE_SHARE_MAX_SCREENSHOTS = 6

/** Where a project at [index] lands when moved by [direction], or null when it can't move that way. */
internal fun projectReorderTarget(index: Int, direction: Int, count: Int): Int? {
    if (index !in 0 until count || direction == 0) return null
    return (index + direction.coerceIn(-1, 1)).takeIf { it in 0 until count }
}

/** The rows whose position changes when [ordered]`[index]` moves to [targetIndex], numbered 0 up. */
internal fun projectPositionUpdates(ordered: List<ProjectEntity>, index: Int, targetIndex: Int): List<ProjectEntity> =
    ordered.toMutableList()
        .apply { add(targetIndex, removeAt(index)) }
        .mapIndexedNotNull { position, entity -> entity.copy(position = position).takeIf { entity.position != position } }

internal fun defaultProfileShareSlug(name: String): String {
    val slug = name
        .lowercase(Locale.US)
        .replace(Regex("[^a-z0-9]+"), "-")
        .trim('-')
        .take(64)
    return slug.takeIf { it.length >= 3 } ?: "opentasker-share"
}

internal fun reorderActions(actions: List<ActionSpec>, fromIndex: Int, toIndex: Int): List<ActionSpec> {
    require(fromIndex in actions.indices) { "Source action index is out of range." }
    require(toIndex in actions.indices) { "Destination action index is out of range." }
    if (fromIndex == toIndex) return actions
    return actions.toMutableList().apply {
        add(toIndex, removeAt(fromIndex))
    }
}

class ActiveAutomationViewModelFactory(
    private val db: AppDatabase,
    private val appContext: Context,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ActiveAutomationViewModel::class.java)) {
            return ActiveAutomationViewModel(db, appContext) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}
