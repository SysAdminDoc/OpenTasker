package com.opentasker.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.PlaylistPlay
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.MonitorHeart
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Sensors
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Widgets
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.opentasker.ui.theme.DesignSystem
import com.opentasker.ui.theme.selectedContainerColor
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.room.withTransaction
import com.opentasker.app.BuildConfig
import com.opentasker.app.R
import com.opentasker.core.actions.ActionField
import com.opentasker.core.diagnostics.DiagnosticExport
import com.opentasker.core.diagnostics.RunLogExportFormat
import com.opentasker.core.actions.ActionMetadata
import com.opentasker.core.actions.ActionMetadataRegistry
import com.opentasker.core.actions.FieldType
import com.opentasker.core.capabilities.ActionCapabilityRegistry
import com.opentasker.core.capabilities.CapabilityLevel
import com.opentasker.core.capabilities.SetupRequirement
import com.opentasker.core.capabilities.SetupRequirementResolver
import com.opentasker.core.contexts.CalendarSunEventPresets
import com.opentasker.core.contexts.DaySchedule
import com.opentasker.core.contexts.EventContextPreset
import com.opentasker.core.contexts.NfcTagWriteSession
import com.opentasker.core.contexts.contextConfigSummary
import com.opentasker.core.engine.executeAndLogTask
import com.opentasker.widget.TaskShortcutHelper
import com.opentasker.core.flow.AutomationFlowTarget
import com.opentasker.core.location.LocationDwellStateStore
import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.AutomationMode
import com.opentasker.core.model.ContextSpec
import com.opentasker.core.model.ContextType
import com.opentasker.core.model.Profile
import com.opentasker.core.model.RunLogEntry
import com.opentasker.core.model.Scene
import com.opentasker.core.model.Task
import com.opentasker.core.model.Variable
import com.opentasker.core.search.GlobalSearchResultKind
import com.opentasker.core.storage.AppDatabase
import com.opentasker.core.storage.DatabaseBackupManager
import com.opentasker.core.storage.EditHistoryDao
import com.opentasker.core.storage.EditHistoryEntity
import com.opentasker.core.storage.VariableEntity
import com.opentasker.core.storage.RunLogRetentionPolicy
import com.opentasker.core.storage.RunLogRetentionSettings
import com.opentasker.core.storage.StorageDecodeIssue
import com.opentasker.core.storage.minimumTimestamp
import com.opentasker.core.storage.normalized
import com.opentasker.core.storage.toEntity
import com.opentasker.core.transfer.BundleImportPlan
import com.opentasker.core.transfer.OpenTaskerBundle
import com.opentasker.core.transfer.OpenTaskerBundleCodec
import com.opentasker.core.transfer.OpenTaskerBundleRepository
import com.opentasker.core.transfer.TaskerImportPlanner
import com.opentasker.core.transfer.TaskerImportPreview
import com.opentasker.core.transfer.TaskerXmlImportReport
import com.opentasker.core.transfer.TaskerXmlImporter
import com.opentasker.core.templates.ProfileTemplate
import com.opentasker.core.templates.BlueprintCatalogStore
import com.opentasker.core.templates.TemplateAvailability
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
import java.io.ByteArrayOutputStream
import java.util.Locale

private const val NO_DIALOG_ENTITY_ID = 0L
private const val NO_DIALOG_INDEX = -1
private const val DELETE_TARGET_PROFILE = "profile"
private const val DELETE_TARGET_TASK = "task"
private const val DELETE_TARGET_SCENE = "scene"

internal data class ActionEditState(
    val task: Task,
    val metadata: ActionMetadata,
    val index: Int? = null,
    val existing: ActionSpec? = null,
)

internal data class ContextEditState(
    val profile: Profile,
    val type: ContextType,
    val index: Int? = null,
    val existing: ContextSpec? = null,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActiveAutomationUi(
    db: AppDatabase,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current.applicationContext
    val blueprintCatalogStore = remember(context) { BlueprintCatalogStore(context) }
    val availableBlueprints = remember(blueprintCatalogStore) { blueprintCatalogStore.available() }
    val useNavigationRail = usesNavigationRail(LocalConfiguration.current.screenWidthDp, LocalConfiguration.current.fontScale)
    val viewModel: ActiveAutomationViewModel = viewModel(factory = ActiveAutomationViewModelFactory(db, context))
    val profiles by viewModel.profiles.collectAsState(); val contentLoaded by viewModel.contentLoaded.collectAsState(); val historyAvailability by viewModel.historyAvailability.collectAsState()
    val runBusy by viewModel.runActionBusy.collectAsState()
    val tasks by viewModel.tasks.collectAsState(); val invariants by viewModel.invariants.collectAsState()
    val scenes by viewModel.scenes.collectAsState()
    val projects by viewModel.projects.collectAsState()
    val runLogs by viewModel.runLog.runLogs.collectAsState()
    val runLogPage by viewModel.runLog.runLogPage.collectAsState()
    val runLogFilters by viewModel.runLog.runLogFilters.collectAsState()
    val runLogTaskOptions by viewModel.runLog.runLogTaskOptions.collectAsState()
    val activeExecutions by viewModel.activeExecutions.collectAsState()
    val globalVariables by viewModel.globalVariables.collectAsState()
    val runLogRetentionPolicy by viewModel.runLog.runLogRetentionPolicy.collectAsState()
    val globalFallbackTaskId by viewModel.globalFallbackTaskId.collectAsState()
    val runLogRetentionPreview by viewModel.runLog.runLogRetentionPreview.collectAsState()
    val backupSetupState by viewModel.backup.backupSetupState.collectAsState()
    val restoreReview by viewModel.backup.restoreReview.collectAsState()
    val diagnosticsState by viewModel.diagnostics.diagnosticsState.collectAsState()
    val storageDecodeIssues by viewModel.storageDecodeIssues.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val unsupportedActionTypeMessage = stringResource(R.string.ui_error_action_type_unsupported)
    val scope = rememberCoroutineScope()
    val undoLabel = stringResource(R.string.action_undo)
    val showUndoableMessage = remember(scope, snackbarHostState, undoLabel) {
        undoableMessages(scope, snackbarHostState, undoLabel)
    }
    var screenOrdinal by rememberSaveable { mutableIntStateOf(0) }
    var selectedProjectId by rememberSaveable { mutableStateOf<Long?>(null) }
    var showGlobalSearchDialog by rememberSaveable { mutableStateOf(false) }
    var focusedVariableName by rememberSaveable { mutableStateOf<String?>(null) }
    var focusedVariableProjectId by rememberSaveable { mutableLongStateOf(com.opentasker.core.model.DEFAULT_PROJECT_ID) }
    var focusedSceneId by rememberSaveable { mutableLongStateOf(NO_DIALOG_ENTITY_ID) }
    LaunchedEffect(projects, selectedProjectId) {
        if (selectedProjectId != null && projects.none { it.id == selectedProjectId }) selectedProjectId = null
    }
    val projectTasks = remember(tasks, selectedProjectId) { tasks.filter { selectedProjectId == null || it.projectId == selectedProjectId } }
    val projectProfiles = remember(profiles, selectedProjectId) { profiles.filter { selectedProjectId == null || it.projectId == selectedProjectId } }
    val projectScenes = remember(scenes, selectedProjectId) { scenes.filter { selectedProjectId == null || it.projectId == selectedProjectId } }
    val projectVariables = remember(globalVariables, selectedProjectId) { globalVariables.filter { selectedProjectId == null || it.projectId == selectedProjectId } }
    val screen = OpenTaskerScreen.entries.getOrElse(screenOrdinal) { OpenTaskerScreen.Profiles }
    var taskDialogId by rememberSaveable { mutableLongStateOf(NO_DIALOG_ENTITY_ID) }
    var showCreateTaskDialog by rememberSaveable { mutableStateOf(false) }
    var profileDialogId by rememberSaveable { mutableLongStateOf(NO_DIALOG_ENTITY_ID) }
    var showCreateProfileDialog by rememberSaveable { mutableStateOf(false) }
    var showTemplateDialog by rememberSaveable { mutableStateOf(false) }
    var showBundleTextImportDialog by rememberSaveable { mutableStateOf(false) }
    var bundleTextImportDraft by rememberSaveable { mutableStateOf("") }
    var onboardingTemplateFlow by rememberSaveable { mutableStateOf(false) }
    // Saved by name: MainActivity declares no configChanges, so a rotation while Setup is open
    // would otherwise drop the focus and take the template's rows off screen with it.
    var setupFocusRequirements by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var selectedTemplateId by rememberSaveable { mutableStateOf<String?>(null) }
    val onboardingCompleted by OnboardingPreference.hasCompleted(context).collectAsState(initial = true)
    LaunchedEffect(onboardingCompleted) {
        if (shouldLaunchOnboarding(onboardingCompleted, selectedTemplateId)) {
            showTemplateDialog = true
            onboardingTemplateFlow = true
        }
    }
    fun finishOnboarding(exit: OnboardingExit) {
        val wasOnboarding = onboardingTemplateFlow
        onboardingTemplateFlow = false
        if (wasOnboarding && shouldCompleteOnboarding(exit)) {
            scope.launch { OnboardingPreference.markCompleted(context) }
        }
    }
    var actionPickerTaskId by rememberSaveable { mutableLongStateOf(NO_DIALOG_ENTITY_ID) }
    var actionEditTaskId by rememberSaveable { mutableLongStateOf(NO_DIALOG_ENTITY_ID) }
    var actionEditActionId by rememberSaveable { mutableStateOf<String?>(null) }
    var actionEditIndex by rememberSaveable { mutableIntStateOf(NO_DIALOG_INDEX) }
    var contextPickerProfileId by rememberSaveable { mutableLongStateOf(NO_DIALOG_ENTITY_ID) }
    var contextEditProfileId by rememberSaveable { mutableLongStateOf(NO_DIALOG_ENTITY_ID) }
    var contextEditTypeName by rememberSaveable { mutableStateOf<String?>(null) }
    var contextEditIndex by rememberSaveable { mutableIntStateOf(NO_DIALOG_INDEX) }
    var contextLogicProfileId by rememberSaveable { mutableLongStateOf(NO_DIALOG_ENTITY_ID) }
    var pendingDeleteKind by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingDeleteOwnerId by rememberSaveable { mutableLongStateOf(NO_DIALOG_ENTITY_ID) }
    var importedProfileReviewId by rememberSaveable { mutableLongStateOf(NO_DIALOG_ENTITY_ID) }
    val taskerImportReview by viewModel.taskerImportReview.collectAsState()
    val macroDroidPasswordPrompt by viewModel.macroDroidPasswordPrompt.collectAsState()
    val taskerImportBusy by viewModel.taskerImportBusy.collectAsState()
    val taskerImportProgress by viewModel.taskerImportProgress.collectAsState()
    val openTaskerBundleProgress by viewModel.openTaskerBundleProgress.collectAsState()
    val openTaskerBundleReview by viewModel.openTaskerBundleReview.collectAsState(); val openTaskerBundleBusy by viewModel.openTaskerBundleBusy.collectAsState(); val semanticDiffReview by viewModel.semanticDiffReview.collectAsState(); val highlightedFlowNodeKeys by viewModel.highlightedFlowNodeKeys.collectAsState(); val simulationProfile by viewModel.simulationProfile.collectAsState()
    val profileShareReview by viewModel.profileShareReview.collectAsState(); val preflightReview by viewModel.preflightReview.collectAsState()
    val preflightBusy by viewModel.preflightBusy.collectAsState()
    val taskerXmlLauncher = rememberOpenDocumentLauncher {
        viewModel.previewTaskerOrMacroDroid(it, BuildConfig.VERSION_NAME)
    }
    val openTaskerBundleExportLauncher = rememberCreateDocumentLauncher("application/json") {
        viewModel.exportOpenTaskerBundle(it, BuildConfig.VERSION_NAME)
    }
    val openTaskerBundleImportLauncher = rememberOpenDocumentLauncher { viewModel.previewOpenTaskerBundle(it) }
    val taskerXmlExportLauncher = rememberCreateDocumentLauncher("text/xml") { viewModel.exportTaskerXml(it) }
    val profileShareScreenshotLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        viewModel.addProfileShareScreenshots(uris)
    }
    val databaseBackupExportLauncher = rememberCreateDocumentLauncher("application/octet-stream") {
        viewModel.backup.exportDatabaseBackup(it)
    }
    val databaseBackupImportLauncher = rememberOpenDocumentLauncher { viewModel.backup.importDatabaseBackup(it) }
    var exportAllRunLogs by rememberSaveable { mutableStateOf(false) }
    val runLogJsonExportLauncher = rememberCreateDocumentLauncher("application/json") {
        viewModel.runLog.exportRunLogs(it, RunLogExportFormat.JSON, exportAllRunLogs)
        exportAllRunLogs = false
    }
    val runLogCsvExportLauncher = rememberCreateDocumentLauncher("text/csv") {
        viewModel.runLog.exportRunLogs(it, RunLogExportFormat.CSV, exportAllRunLogs)
        exportAllRunLogs = false
    }
    val taskDialog = taskDialogId.takeIf { it != NO_DIALOG_ENTITY_ID }
        ?.let { taskId -> tasks.firstOrNull { it.id == taskId } }
    val profileDialog = profileDialogId.takeIf { it != NO_DIALOG_ENTITY_ID }
        ?.let { profileId -> profiles.firstOrNull { it.id == profileId } }
    val selectedTemplate = selectedTemplateId
        ?.let { templateId -> availableBlueprints.firstOrNull { it.id == templateId } }
    val actionPickerTask = actionPickerTaskId.takeIf { it != NO_DIALOG_ENTITY_ID }
        ?.let { taskId -> tasks.firstOrNull { it.id == taskId } }
    val actionEdit = actionEditTaskId.takeIf { it != NO_DIALOG_ENTITY_ID }?.let { taskId ->
        val task = tasks.firstOrNull { it.id == taskId } ?: return@let null
        val actionId = actionEditActionId ?: return@let null
        val metadata = ActionMetadataRegistry.get(actionId) ?: return@let null
        val index = actionEditIndex.takeIf { it != NO_DIALOG_INDEX }
        val existing = index?.let { task.actions.getOrNull(it) }?.takeIf { it.type == actionId }
        if (index != null && existing == null) {
            null
        } else {
            ActionEditState(task = task, metadata = metadata, index = index, existing = existing)
        }
    }
    val contextPickerProfile = contextPickerProfileId.takeIf { it != NO_DIALOG_ENTITY_ID }
        ?.let { profileId -> profiles.firstOrNull { it.id == profileId } }
    val contextEdit = contextEditProfileId.takeIf { it != NO_DIALOG_ENTITY_ID }?.let { profileId ->
        val profile = profiles.firstOrNull { it.id == profileId } ?: return@let null
        val type = contextEditTypeName
            ?.let { typeName -> runCatching { ContextType.valueOf(typeName) }.getOrNull() }
            ?: return@let null
        val index = contextEditIndex.takeIf { it != NO_DIALOG_INDEX }
        val existing = index?.let { profile.contexts.getOrNull(it) }?.takeIf { it.type == type }
        if (index != null && existing == null) {
            null
        } else {
            ContextEditState(profile = profile, type = type, index = index, existing = existing)
        }
    }
    val contextLogicProfile = contextLogicProfileId.takeIf { it != NO_DIALOG_ENTITY_ID }
        ?.let { profileId -> profiles.firstOrNull { it.id == profileId } }
    val pendingDelete = when (pendingDeleteKind) {
        DELETE_TARGET_PROFILE -> profiles.firstOrNull { it.id == pendingDeleteOwnerId }
            ?.let { DeleteTarget.ProfileTarget(it) }
        DELETE_TARGET_TASK -> tasks.firstOrNull { it.id == pendingDeleteOwnerId }
            ?.let { DeleteTarget.TaskTarget(it) }
        DELETE_TARGET_SCENE -> scenes.firstOrNull { it.id == pendingDeleteOwnerId }
            ?.let { DeleteTarget.SceneTarget(it) }
        else -> null
    }
    val importedProfileReview = importedProfileReviewId.takeIf { it != NO_DIALOG_ENTITY_ID }
        ?.let { profileId -> profiles.firstOrNull { it.id == profileId } }

    // Task deletes are gated on a dependency scan: a task referenced by a profile, another task's
    // `task.run`/notification button, or a scene gesture needs an explicit reassign-or-clear
    // decision before it can go away, so the plain confirmation dialog is not enough.
    val pendingTaskDelete = (pendingDelete as? DeleteTarget.TaskTarget)?.task
    var taskDeletionPreview by remember { mutableStateOf<TaskDeletionPreview?>(null) }
    LaunchedEffect(pendingTaskDelete?.id, tasks, profiles, scenes) {
        taskDeletionPreview = pendingTaskDelete?.let { viewModel.taskDeletionPreview(it) }
    }

    fun clearPendingDelete() {
        pendingDeleteKind = null
        pendingDeleteOwnerId = NO_DIALOG_ENTITY_ID
    }
    fun openTaskDialog(task: Task) {
        taskDialogId = task.id
    }
    fun clearTaskDialog() {
        taskDialogId = NO_DIALOG_ENTITY_ID
    }
    fun openProfileDialog(profile: Profile) {
        profileDialogId = profile.id
    }
    fun clearProfileDialog() {
        profileDialogId = NO_DIALOG_ENTITY_ID
    }
    fun openSimulation(profile: Profile) { viewModel.openSimulation(profile) }
    fun clearSimulation() {
        viewModel.clearSimulation()
    }
    fun openActionPicker(task: Task) {
        actionPickerTaskId = task.id
    }
    fun clearActionPicker() {
        actionPickerTaskId = NO_DIALOG_ENTITY_ID
    }
    fun openActionEdit(task: Task, metadata: ActionMetadata, index: Int? = null) {
        actionEditTaskId = task.id
        actionEditActionId = metadata.id
        actionEditIndex = index ?: NO_DIALOG_INDEX
    }
    fun clearActionEdit() {
        actionEditTaskId = NO_DIALOG_ENTITY_ID
        actionEditActionId = null
        actionEditIndex = NO_DIALOG_INDEX
    }
    fun openContextPicker(profile: Profile) {
        contextPickerProfileId = profile.id
    }
    fun clearContextPicker() {
        contextPickerProfileId = NO_DIALOG_ENTITY_ID
    }
    fun openContextEdit(profile: Profile, type: ContextType, index: Int? = null) {
        contextEditProfileId = profile.id
        contextEditTypeName = type.name
        contextEditIndex = index ?: NO_DIALOG_INDEX
    }
    fun clearContextEdit() {
        contextEditProfileId = NO_DIALOG_ENTITY_ID
        contextEditTypeName = null
        contextEditIndex = NO_DIALOG_INDEX
    }
    fun clearContextLogic() {
        contextLogicProfileId = NO_DIALOG_ENTITY_ID
    }
    fun openDeleteProfile(profile: Profile) {
        pendingDeleteKind = DELETE_TARGET_PROFILE
        pendingDeleteOwnerId = profile.id
    }
    fun openDeleteTask(task: Task) {
        pendingDeleteKind = DELETE_TARGET_TASK
        pendingDeleteOwnerId = task.id
    }
    fun openDeleteScene(scene: Scene) {
        pendingDeleteKind = DELETE_TARGET_SCENE
        pendingDeleteOwnerId = scene.id
    }
    val openFlowTarget: (AutomationFlowTarget) -> Unit = { target ->
        var opened = true
        when (target) {
            is AutomationFlowTarget.Profile -> {
                profiles.firstOrNull { it.id == target.profileId }?.let { profile ->
                    screenOrdinal = OpenTaskerScreen.Profiles.ordinal
                    openProfileDialog(profile)
                } ?: run { opened = false }
            }

            is AutomationFlowTarget.Context -> {
                val profile = profiles.firstOrNull { it.id == target.profileId }
                val contextSpec = profile?.contexts?.getOrNull(target.index)
                if (profile != null && contextSpec != null) {
                    screenOrdinal = OpenTaskerScreen.Profiles.ordinal
                    openContextEdit(profile, contextSpec.type, target.index)
                } else {
                    opened = false
                }
            }

            is AutomationFlowTarget.Task -> {
                tasks.firstOrNull { it.id == target.taskId }?.let { task ->
                    screenOrdinal = OpenTaskerScreen.Tasks.ordinal
                    openTaskDialog(task)
                } ?: run { opened = false }
            }

            is AutomationFlowTarget.Action -> {
                val task = tasks.firstOrNull { it.id == target.taskId }
                val action = task?.actions?.getOrNull(target.index)
                val metadata = action?.let { ActionMetadataRegistry.get(it.type) }
                if (task != null && action != null && metadata != null) {
                    screenOrdinal = OpenTaskerScreen.Tasks.ordinal
                    openActionEdit(task, metadata, target.index)
                } else {
                    opened = false
                }
            }
        }
        if (!opened) {
            scope.launch { snackbarHostState.showSnackbar(context.getString(R.string.ui_flow_target_missing)) }
        }
    }

    LaunchedEffect(Unit) {
        viewModel.messages.collect { message ->
            val result = snackbarHostState.showSnackbar(
                message = message.resolve(context),
                actionLabel = message.action?.let { context.getString(R.string.action_undo) },
                withDismissAction = message.action != null,
                duration = when {
                    message.action != null -> SnackbarDuration.Indefinite
                    message.longDuration -> SnackbarDuration.Long
                    else -> SnackbarDuration.Short
                },
            )
            if (result == SnackbarResult.ActionPerformed) {
                when (val action = message.action) {
                    is UiMessageAction.Undo -> when (action.entityType) {
                        EditHistoryDao.TYPE_PROFILE -> viewModel.undoLastProfileEdit(action.entityId)
                        EditHistoryDao.TYPE_TASK -> viewModel.undoLastTaskEdit(action.entityId)
                        EditHistoryDao.TYPE_SCENE -> viewModel.undoLastSceneEdit(action.entityId)
                        EditHistoryDao.TYPE_VARIABLE -> viewModel.undoLastVariableDelete(action.entityId)
                        EditHistoryDao.TYPE_PROJECT -> viewModel.undoLastProjectDelete(action.entityId)
                        else -> Unit
                    }
                    null -> Unit
                }
            }
        }
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(screen) {
        if (screen == OpenTaskerScreen.Diagnostics) {
            // repeatOnLifecycle: LaunchedEffect is composition-scoped, so without it the
            // 5-second file/crash-log polling kept running while the app sat backgrounded
            // with Diagnostics selected.
            lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    viewModel.diagnostics.refreshDiagnostics()
                    delay(DIAGNOSTICS_REFRESH_INTERVAL_MS)
                }
            }
        }
    }

    var showMoreDestinations by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = shouldNavigateBackToProfiles(screen)) {
        screenOrdinal = OpenTaskerScreen.Profiles.ordinal
        showMoreDestinations = false
    }
    val headerDetail = when (screen) {
        // Counted over the same project-scoped lists the screens render, or the header disagreed
        // with the list under it whenever a project was selected.
        OpenTaskerScreen.Profiles -> stringResource(R.string.header_profiles_detail, projectProfiles.count { it.enabled }, projectProfiles.size)
        OpenTaskerScreen.Tasks -> stringResource(R.string.header_tasks_detail, projectTasks.sumOf { it.actions.size }, projectTasks.size)
        OpenTaskerScreen.Vars -> stringResource(R.string.header_variables_detail, globalVariables.size)
        OpenTaskerScreen.Flow -> stringResource(R.string.header_flow_detail, projectProfiles.size, projectTasks.size)
        OpenTaskerScreen.Scenes -> stringResource(R.string.header_scenes_detail, scenes.sumOf { it.elements.size }, scenes.size)
        OpenTaskerScreen.Inspector -> stringResource(R.string.header_inspector_detail)
        OpenTaskerScreen.Setup -> stringResource(R.string.header_setup_detail)
        OpenTaskerScreen.RunLog -> stringResource(R.string.header_run_log_detail, runLogPage.totalCount)
        OpenTaskerScreen.Diagnostics -> stringResource(R.string.header_diagnostics_detail)
        OpenTaskerScreen.Settings -> stringResource(R.string.header_settings_detail)
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .imePadding(),
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            Column {
                OpenTaskerHeader(
                    screen = screen,
                    detail = headerDetail,
                    onOpenSearch = { showGlobalSearchDialog = true },
                )
                if (screen in setOf(OpenTaskerScreen.Profiles, OpenTaskerScreen.Tasks, OpenTaskerScreen.Vars, OpenTaskerScreen.Scenes, OpenTaskerScreen.Flow)) {
                    ProjectScopeBar(
                        projects = projects,
                        selectedProjectId = selectedProjectId,
                        onSelectProject = { selectedProjectId = it },
                        onCreateProject = { name, onCreated -> viewModel.createProject(name, onCreated) },
                        onRenameProject = { project, name, onRenamed -> viewModel.renameProject(project, name, onRenamed) },
                        onReorderProject = viewModel::reorderProject,
                        onDeleteProject = viewModel::deleteProject,
                    )
                }
            }
        },
        floatingActionButton = {
            when (screen) {
                OpenTaskerScreen.Profiles -> {
                    val createLabel = stringResource(if (projectTasks.isEmpty()) R.string.task_new else R.string.profile_new)
                    ExtendedFloatingActionButton(
                        onClick = {
                            if (projectTasks.isEmpty()) {
                                showCreateTaskDialog = true
                            } else {
                                showCreateProfileDialog = true
                            }
                        },
                        shape = RoundedCornerShape(DesignSystem.Radii.lg),
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        icon = {
                            Icon(
                                Icons.Filled.Add,
                                contentDescription = createLabel,
                            )
                        },
                        text = { Text(createLabel) },
                    )
                }

                OpenTaskerScreen.Tasks -> ExtendedFloatingActionButton(
                    onClick = { showCreateTaskDialog = true },
                    shape = RoundedCornerShape(DesignSystem.Radii.lg),
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    // Material3 clears the extended FAB's text semantics, so the icon's description is the button's name.
                    icon = { Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.task_new)) },
                    text = { Text(stringResource(R.string.task_new)) },
                )

                OpenTaskerScreen.Vars,
                OpenTaskerScreen.Flow,
                OpenTaskerScreen.Scenes,
                OpenTaskerScreen.Inspector,
                OpenTaskerScreen.Setup,
                OpenTaskerScreen.RunLog,
                OpenTaskerScreen.Settings,
                -> Unit
                OpenTaskerScreen.Diagnostics -> Unit
            }
        },
        bottomBar = {
            if (!useNavigationRail) {
                Column {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.78f))
                    NavigationBar(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        tonalElevation = 0.dp,
                    ) {
                        primaryNavigationScreens.forEach { destination ->
                            OpenTaskerNavigationItem(
                                selected = screen == destination,
                                onClick = {
                                    screenOrdinal = destination.ordinal
                                    showMoreDestinations = false
                                },
                                icon = destination.icon(),
                                label = stringResource(destination.labelRes),
                                modifier = Modifier.weight(1f),
                            )
                        }
                        Box(Modifier.weight(1f)) {
                            OpenTaskerNavigationItem(
                                selected = screen in secondaryNavigationScreens || showMoreDestinations,
                                onClick = { showMoreDestinations = true },
                                icon = Icons.Outlined.MoreHoriz,
                                label = stringResource(R.string.nav_more),
                                modifier = Modifier.fillMaxWidth(),
                            )
                            DropdownMenu(
                                expanded = showMoreDestinations,
                                onDismissRequest = { showMoreDestinations = false },
                                modifier = Modifier.align(Alignment.TopEnd),
                            ) {
                                secondaryNavigationScreens.forEach { destination ->
                                    DropdownMenuItem(
                                        text = { Text(stringResource(destination.labelRes)) },
                                        leadingIcon = { Icon(destination.icon(), contentDescription = null) },
                                        onClick = {
                                            screenOrdinal = destination.ordinal
                                            showMoreDestinations = false
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
    ) { innerPadding ->
        val permissionScreen: @Composable (Boolean) -> Unit = { settingsOnly ->
            PermissionOnboardingScreen(
                contentPadding = innerPadding,
                onMessage = { message -> scope.launch { snackbarHostState.showSnackbar(message) } },
                onUndoableMessage = showUndoableMessage,
                backupState = backupSetupState,
                onCreateBackup = viewModel.backup::createDatabaseBackup,
                onExportBackup = { databaseBackupExportLauncher.launch(databaseBackupExportName()) },
                onImportBackup = { databaseBackupImportLauncher.launch(DATABASE_BACKUP_MIME_TYPES) },
                onCancelPendingRestore = viewModel.backup::cancelPendingRestore,
                onReviewRestoreRollback = viewModel.backup::reviewRestoreRollback,
                onSnapshotPolicyChanged = viewModel.backup::updateSnapshotPolicy,
                onSnapshotDestinationSelected = viewModel.backup::updateSnapshotDestination,
                profiles = profiles,
                tasks = tasks,
                globalFallbackTaskId = globalFallbackTaskId,
                onGlobalFallbackTaskChange = viewModel::updateGlobalFallbackTask,
                settingsOnly = settingsOnly,
                focusRequirements = if (settingsOnly) {
                    emptySet()
                } else {
                    setupFocusRequirements.mapTo(mutableSetOf(), SetupRequirement::valueOf)
                },
                // Deliberately does not clear the persisted onboarding flag. Doing so meant an
                // established user who opened this out of curiosity and tapped outside the dialog
                // got the first-run flow again on every cold start, with no way back.
                onRunOnboardingAgain = if (settingsOnly) {
                    {
                        showTemplateDialog = true
                        onboardingTemplateFlow = true
                    }
                } else {
                    null
                },
            )
        }
        Row(Modifier.fillMaxSize()) {
            if (useNavigationRail) {
                OpenTaskerNavigationRail(
                    selectedScreen = screen,
                    onSelect = { destination ->
                        screenOrdinal = destination.ordinal
                        showMoreDestinations = false
                    },
                    scaffoldPadding = innerPadding,
                )
            }
            Box(Modifier.weight(1f).fillMaxHeight()) {
        when (screen) {
            OpenTaskerScreen.Profiles -> ProfilesScreen(
                profiles = projectProfiles,
                tasks = projectTasks,
                allTasks = tasks,
                runLogs = runLogs,
                storageDecodeIssues = storageDecodeIssues,
                onCreateTaskFirst = {
                    screenOrdinal = OpenTaskerScreen.Tasks.ordinal
                    showCreateTaskDialog = true
                },
                onCreateProfile = { showCreateProfileDialog = true },
                onBrowseTemplates = {
                    showTemplateDialog = true
                    if (!onboardingCompleted) onboardingTemplateFlow = true
                },
                onPreviewProfileShare = { viewModel.previewLocalProfileShare(BuildConfig.VERSION_NAME) },
                onPreflightProfile = viewModel::previewProfilePreflight,
                onExportOpenTaskerBundle = { openTaskerBundleExportLauncher.launch(openTaskerBundleExportName()) },
                onImportOpenTaskerBundle = { openTaskerBundleImportLauncher.launch(OPEN_TASKER_BUNDLE_MIME_TYPES) },
                onImportOpenTaskerBundleText = {
                    bundleTextImportDraft = readClipboardText(context)
                    showBundleTextImportDialog = true
                },
                openTaskerBundleBusy = openTaskerBundleBusy,
                onImportTaskerXml = { taskerXmlLauncher.launch(TASKER_MACRODROID_MIME_TYPES) },
                onExportTaskerXml = { taskerXmlExportLauncher.launch("opentasker-tasker-export.xml") },
                taskerImportBusy = taskerImportBusy,
                onEditProfile = { openProfileDialog(it) },
                onUndoProfileEdit = { viewModel.undoLastProfileEdit(it.id) },
                onRedoProfileEdit = { viewModel.redoLastProfileEdit(it.id) },
                onDeleteProfile = { openDeleteProfile(it) }, onDuplicateProfile = viewModel::duplicateProfile,
                onToggleProfile = { profile, enabled ->
                    if (enabled && profile.requiresRiskAcknowledgement) {
                        importedProfileReviewId = profile.id
                    } else {
                        viewModel.updateProfile(
                            profile.copy(enabled = enabled),
                            if (enabled) R.string.ui_message_profile_enabled else R.string.ui_message_profile_disabled,
                        )
                    }
                },
                onAddContext = { openContextPicker(it) },
                onEditContextLogic = { contextLogicProfileId = it.id },
                onEditContext = { profile, index, context ->
                    openContextEdit(profile, context.type, index)
                },
                onDeleteContext = { profile, index ->
                    if (profile.contexts.getOrNull(index) != null) viewModel.removeProfileContext(profile, index)
                },
                contentPadding = innerPadding, contentLoaded = contentLoaded, historyAvailability = historyAvailability,
            )

            OpenTaskerScreen.Tasks -> TasksScreen(
                tasks = projectTasks,
                storageDecodeIssues = storageDecodeIssues,
                onCreateTask = { showCreateTaskDialog = true },
                onEditTask = { openTaskDialog(it) },
                onUndoTaskEdit = { viewModel.undoLastTaskEdit(it.id) },
                onRedoTaskEdit = { viewModel.redoLastTaskEdit(it.id) },
                onDeleteTask = { openDeleteTask(it) }, onDuplicateTask = viewModel::duplicateTask,
                onRunTask = { viewModel.runTaskNow(it) },
                onPreflightTask = viewModel::previewTaskPreflight,
                onPinTask = { viewModel.pinTaskShortcut(it) },
                onAddAction = { openActionPicker(it) },
                onEditAction = { task, index, action ->
                    val metadata = ActionMetadataRegistry.get(action.type)
                    if (metadata != null) {
                        openActionEdit(task, metadata, index)
                    } else {
                        // Unknown/unsupported action types (e.g. from an import on a build that
                        // lacks the action) have no editor; tell the user instead of dead-tapping.
                        scope.launch {
                            snackbarHostState.showSnackbar(unsupportedActionTypeMessage)
                        }
                    }
                },
                onDeleteAction = { task, index ->
                    if (task.actions.getOrNull(index) != null) viewModel.removeTaskAction(task, index)
                },
                onMoveAction = { task, fromIndex, toIndex ->
                    viewModel.moveTaskAction(task.id, fromIndex, toIndex)
                },
                onRunAction = { task, index -> viewModel.runActionNow(task, index) },
                runBusy = runBusy,
                contentPadding = innerPadding, contentLoaded = contentLoaded, historyAvailability = historyAvailability,
            )

            OpenTaskerScreen.Flow -> AutomationFlowScreen(
                profiles = projectProfiles,
                tasks = projectTasks, invariants = invariants,
                contentPadding = innerPadding, changedNodeKeys = highlightedFlowNodeKeys,
                contentLoaded = contentLoaded,
                onNodeTargetSelected = openFlowTarget, onUpdateInvariants = viewModel::updateAutomationInvariants,
                onAddContext = { profileId ->
                    val profile = profiles.firstOrNull { it.id == profileId }
                    if (profile != null) {
                        screenOrdinal = OpenTaskerScreen.Profiles.ordinal
                        openContextPicker(profile)
                    } else {
                        scope.launch { snackbarHostState.showSnackbar(context.getString(R.string.ui_flow_target_missing)) }
                    }
                },
                onAddAction = { taskId ->
                    val task = tasks.firstOrNull { it.id == taskId }
                    if (task != null) {
                        screenOrdinal = OpenTaskerScreen.Tasks.ordinal
                        openActionPicker(task)
                    } else {
                        scope.launch { snackbarHostState.showSnackbar(context.getString(R.string.ui_flow_target_missing)) }
                    }
                },
            )

            OpenTaskerScreen.Vars -> VariablesScreen(
                variables = projectVariables,
                contentPadding = innerPadding,
                projectId = selectedProjectId ?: com.opentasker.core.model.DEFAULT_PROJECT_ID,
                focusVariableName = focusedVariableName,
                focusVariableProjectId = focusedVariableProjectId,
                onUpdate = { previousName, name, value, isSecret, successMessage, projectId, onSaved ->
                    viewModel.updateVariable(previousName, name, value, isSecret, successMessage, projectId, onSaved)
                },
                onDelete = { name, successMessage, projectId ->
                    viewModel.deleteVariable(name, successMessage, projectId)
                },
                onMessage = { message -> scope.launch { snackbarHostState.showSnackbar(message) } },
                contentLoaded = contentLoaded,
            )

            OpenTaskerScreen.Scenes -> SceneLibraryScreen(
                scenes = projectScenes,
                tasks = projectTasks,
                focusSceneId = focusedSceneId.takeIf { it != NO_DIALOG_ENTITY_ID },
                onCreateScene = { name, width, height, onSaved ->
                    viewModel.createScene(name, width, height, selectedProjectId ?: com.opentasker.core.model.DEFAULT_PROJECT_ID, onSaved)
                },
                onUpdateScene = { scene, messageRes, onSaved -> viewModel.updateScene(scene, messageRes, onSaved = onSaved) },
                onRemoveElement = { scene, index -> viewModel.removeSceneElement(scene, index) },
                onUndoSceneEdit = { viewModel.undoLastSceneEdit(it.id) },
                onRedoSceneEdit = { viewModel.redoLastSceneEdit(it.id) },
                onDeleteScene = { openDeleteScene(it) }, onDuplicateScene = viewModel::duplicateScene,
                contentPadding = innerPadding, contentLoaded = contentLoaded, historyAvailability = historyAvailability,
            )

            OpenTaskerScreen.Setup -> permissionScreen(false)
            OpenTaskerScreen.Settings -> permissionScreen(true)

            OpenTaskerScreen.Inspector -> ContextInspectorScreen(
                db = db,
                contentPadding = innerPadding,
                onUndoableMessage = showUndoableMessage,
            )

            OpenTaskerScreen.RunLog -> RunLogScreenContent(
                logs = runLogPage.entries,
                tasks = tasks,
                totalCount = runLogPage.totalCount,
                hasMore = runLogPage.hasMore,
                loading = runLogPage.loading,
                failed = runLogPage.failed,
                filters = runLogFilters,
                taskOptions = runLogTaskOptions.map { it.taskId to it.taskName },
                onFiltersChange = viewModel.runLog::updateRunLogFilters,
                onLoadMore = viewModel.runLog::loadNextRunLogPage,
                onRefresh = viewModel.runLog::refreshRunLogPage,
                retentionPolicy = runLogRetentionPolicy,
                onRetentionPolicyChange = viewModel.runLog::requestRunLogRetention,
                onClearRunLog = viewModel.runLog::clearRunLog,
                onShareDiagnostic = viewModel.diagnostics::shareDiagnosticReport,
                onExportJson = {
                    exportAllRunLogs = false
                    runLogJsonExportLauncher.launch(runLogExportName(RunLogExportFormat.JSON))
                },
                onExportCsv = {
                    exportAllRunLogs = false
                    runLogCsvExportLauncher.launch(runLogExportName(RunLogExportFormat.CSV))
                },
                contentPadding = innerPadding,
                activeExecutions = activeExecutions,
                onCancelExecution = viewModel::cancelExecution,
                onReplayHeldRun = viewModel::replayHeldRun,
                onToggleRunLogStar = { entry -> viewModel.runLog.setRunLogStarred(entry) },
            )

            OpenTaskerScreen.Diagnostics -> DiagnosticsScreen(
                state = diagnosticsState,
                contentPadding = innerPadding,
                onRefresh = viewModel.diagnostics::refreshDiagnostics,
                onShare = viewModel.diagnostics::shareDiagnosticReport,
                onCopy = viewModel.diagnostics::copyDiagnosticReport,
            )
        }
            }
        }
    }

    runLogRetentionPreview?.let { preview ->
        RunLogRetentionPreviewDialog(
            preview = preview,
            onDismiss = viewModel.runLog::dismissRunLogRetentionPreview,
            onExportJson = {
                exportAllRunLogs = true
                runLogJsonExportLauncher.launch(runLogExportName(RunLogExportFormat.JSON))
            },
            onConfirm = viewModel.runLog::confirmRunLogRetention,
        )
    }

    val blockedTaskDelete = taskDeletionPreview
        ?.takeIf { preview -> preview.hasDependents && preview.task.id == pendingTaskDelete?.id }

    blockedTaskDelete?.let { preview ->
        TaskDeleteReferencesDialog(
            preview = preview,
            tasks = tasks,
            onDismiss = { clearPendingDelete() },
            onConfirm = { resolution ->
                viewModel.deleteTask(preview.task, resolution)
                clearPendingDelete()
            },
        )
    }

    pendingDelete?.takeIf { blockedTaskDelete == null }?.let { target ->
        DeleteConfirmationDialog(
            target = target,
            onDismiss = { clearPendingDelete() },
            onConfirm = {
                when (target) {
                    is DeleteTarget.ProfileTarget -> viewModel.deleteProfile(target.profile)
                    is DeleteTarget.TaskTarget -> viewModel.deleteTask(target.task)
                    is DeleteTarget.SceneTarget -> viewModel.deleteScene(target.scene)
                }
                clearPendingDelete()
            },
        )
    }

    restoreReview?.let { review ->
        RestoreReviewDialog(
            state = review,
            busy = backupSetupState.busy,
            onDismiss = viewModel.backup::dismissRestoreReview,
            onStage = viewModel.backup::confirmStageRestore,
        )
    }

    importedProfileReview?.let { profile ->
        ImportedProfileRiskDialog(
            profile = profile,
            tasks = tasks,
            otherProfiles = profiles,
            onDismiss = { importedProfileReviewId = NO_DIALOG_ENTITY_ID },
            onAcknowledgeAndEnable = {
                viewModel.acknowledgeAndEnableImportedProfile(profile.id)
                importedProfileReviewId = NO_DIALOG_ENTITY_ID
            },
        )
    }

    macroDroidPasswordPrompt?.let { prompt ->
        MacroDroidPasswordDialog(
            wrongPassword = prompt.wrongPassword,
            busy = taskerImportBusy,
            progress = taskerImportProgress,
            onSubmit = { viewModel.openMacroDroidEncryptedExport(it, BuildConfig.VERSION_NAME) },
            onDismiss = viewModel::clearMacroDroidPasswordPrompt,
            onCancel = viewModel::cancelTransfers,
        )
    }

    taskerImportReview?.let { state ->
        TaskerImportReviewDialog(
            state = state,
            busy = taskerImportBusy,
            onDismiss = viewModel::clearTaskerImportReview,
            progress = taskerImportProgress,
            onCancel = viewModel::cancelTransfers,
            onConfirm = { viewModel.confirmTaskerImport(state) },
        )
    }

    openTaskerBundleReview?.let { state ->
        OpenTaskerBundleReviewDialog(
            state = state,
            busy = openTaskerBundleBusy,
            onDismiss = viewModel::clearOpenTaskerBundleReview,
            onVariableConflictResolution = viewModel::resolveOpenTaskerVariableConflict,
            progress = openTaskerBundleProgress,
            onCancel = viewModel::cancelTransfers,
            onConfirm = viewModel::confirmOpenTaskerBundleImport,
        )
    }

    profileShareReview?.let { state ->
        ProfileShareReviewDialog(
            state = state,
            busy = openTaskerBundleBusy,
            onDismiss = viewModel::clearProfileShareReview,
            onDraftChanged = viewModel::updateProfileShareDraft,
            onAttachScreenshots = { profileShareScreenshotLauncher.launch(arrayOf("image/*")) },
            onRemoveScreenshot = viewModel::removeProfileShareScreenshot,
            onContinueImportReview = viewModel::continueProfileShareImportReview,
        )
    }

    if (showBundleTextImportDialog) {
        OpenTaskerBundleTextImportDialog(
            text = bundleTextImportDraft,
            busy = openTaskerBundleBusy,
            onTextChanged = { bundleTextImportDraft = it.take(MAX_PASTED_BUNDLE_CHARS) },
            onDismiss = { if (!openTaskerBundleBusy) showBundleTextImportDialog = false },
            onCancel = viewModel::cancelTransfers,
            onConfirm = {
                showBundleTextImportDialog = false
                viewModel.previewPastedImport(bundleTextImportDraft, BuildConfig.VERSION_NAME)
            },
        )
    }

    preflightReview?.let { state ->
        PreflightReviewDialog(
            state = state,
            busy = preflightBusy,
            onDismiss = viewModel::clearPreflightReview,
            onRerun = viewModel::rerunPreflight,
            onStop = viewModel::stopPreflight,
        )
    }

    if (showGlobalSearchDialog) {
        GlobalSearchDialog(
            profiles = profiles,
            tasks = tasks,
            variables = globalVariables,
            scenes = scenes,
            onDismiss = { showGlobalSearchDialog = false },
            onSelect = { result ->
                showGlobalSearchDialog = false
                selectedProjectId = result.projectId
                when (result.kind) {
                    GlobalSearchResultKind.PROFILE -> profiles.firstOrNull { it.id == result.entityId }?.let {
                        screenOrdinal = OpenTaskerScreen.Profiles.ordinal
                        openProfileDialog(it)
                    }

                    GlobalSearchResultKind.TASK -> tasks.firstOrNull { it.id == result.entityId }?.let {
                        screenOrdinal = OpenTaskerScreen.Tasks.ordinal
                        openTaskDialog(it)
                    }

                    GlobalSearchResultKind.ACTION -> {
                        val task = tasks.firstOrNull { it.id == result.entityId }
                        val action = result.actionIndex?.let { task?.actions?.getOrNull(it) }
                        val metadata = action?.let { ActionMetadataRegistry.get(it.type) }
                        if (task != null && metadata != null) {
                            screenOrdinal = OpenTaskerScreen.Tasks.ordinal
                            openActionEdit(task, metadata, result.actionIndex)
                        }
                    }

                    GlobalSearchResultKind.VARIABLE -> {
                        focusedVariableName = result.variableName
                        focusedVariableProjectId = result.projectId
                        screenOrdinal = OpenTaskerScreen.Vars.ordinal
                    }

                    GlobalSearchResultKind.SCENE -> {
                        focusedSceneId = result.entityId
                        screenOrdinal = OpenTaskerScreen.Scenes.ordinal
                    }
                }
            },
        )
    }

    if (showCreateTaskDialog) {
        TaskEditorDialog(
            existingTaskNames = tasks.map { it.name },
            task = null,
            onDismiss = { showCreateTaskDialog = false },
            onSave = { name, priority, collisionMode ->
                val projectId = selectedProjectId ?: com.opentasker.core.model.DEFAULT_PROJECT_ID
                viewModel.createTask(name, priority, collisionMode, projectId) { showCreateTaskDialog = false }
            },
        )
    }

    taskDialog?.let { task ->
        TaskEditorDialog(
            existingTaskNames = tasks.map { it.name },
            task = task,
            onDismiss = { clearTaskDialog() },
            onSave = { name, priority, collisionMode ->
                viewModel.updateTask(
                    task.copy(
                        name = name.trim(),
                        priority = priority.coerceIn(0, 10),
                        collisionMode = collisionMode,
                    ),
                    onSaved = { clearTaskDialog() },
                )
            },
        )
    }
    if (showCreateProfileDialog) {
        ProfileEditorDialog(
            profile = null,
            tasks = projectTasks,
            onDismiss = { showCreateProfileDialog = false },
            onSave = { name, enabled, enterTaskId, exitTaskId, cooldown, priority, gracePeriod, automationMode, group, lifetime, expiresAtMs, maxActiveExecutions, burstLimit, overflowPolicy, fallbackTaskId ->
                viewModel.createProfile(name, enabled, enterTaskId, exitTaskId, cooldown, automationMode, group, selectedProjectId ?: com.opentasker.core.model.DEFAULT_PROJECT_ID, priority, gracePeriod, lifetime, expiresAtMs, maxActiveExecutions, burstLimit, overflowPolicy, fallbackTaskId) { showCreateProfileDialog = false }
            },
        )
    }

    if (showTemplateDialog) {
        TemplatePickerDialog(
            templates = availableBlueprints,
            onDismiss = {
                showTemplateDialog = false
                finishOnboarding(OnboardingExit.Dismissed)
            },
            onSelect = { template ->
                showTemplateDialog = false
                selectedTemplateId = template.id
            },
            onSkip = if (onboardingTemplateFlow) {
                {
                    showTemplateDialog = false
                    finishOnboarding(OnboardingExit.Skipped)
                }
            } else {
                null
            },
        )
    }

    selectedTemplate?.let { template ->
        TemplateSlotDialog(
            template = template,
            onDismiss = {
                selectedTemplateId = null
                finishOnboarding(OnboardingExit.Dismissed)
            },
            onInstall = { values ->
                viewModel.installProfileTemplate(template, values)
                selectedTemplateId = null
                // A template whose actions need a grant would otherwise land on Profiles and fail
                // on its first run with nothing explaining why, so send them to Setup scoped to
                // exactly what this template is waiting on.
                val needed = SetupRequirementResolver.resolveForTemplate(
                    actionTypes = (template.actions + template.exitActions).map { it.type },
                    contexts = template.contexts.map { ContextSpec(it.type, it.config) },
                )
                setupFocusRequirements = needed.map(SetupRequirement::name)
                screenOrdinal = if (needed.isEmpty()) {
                    OpenTaskerScreen.Profiles.ordinal
                } else {
                    OpenTaskerScreen.Setup.ordinal
                }
                finishOnboarding(OnboardingExit.InstalledTemplate)
            },
        )
    }

    profileDialog?.let { profile ->
        ProfileEditorDialog(
            profile = profile,
            tasks = remember(profile, projectTasks, tasks) { profileEditorTasks(profile, projectTasks, tasks) },
            onDismiss = { clearProfileDialog() },
            onSave = { name, enabled, enterTaskId, exitTaskId, cooldown, priority, gracePeriod, automationMode, group, lifetime, expiresAtMs, maxActiveExecutions, burstLimit, overflowPolicy, fallbackTaskId ->
                viewModel.updateProfile(profile.copy(
                        name = name.trim(),
                        enabled = enabled,
                        enterTaskId = enterTaskId,
                        exitTaskId = exitTaskId,
                        cooldownSec = cooldown.coerceAtLeast(0),
                        automationMode = automationMode,
                        group = group,
                        priority = priority, gracePeriodSec = gracePeriod, lifetime = lifetime, expiresAtMs = expiresAtMs,
                        maxActiveExecutions = maxActiveExecutions, burstLimit = burstLimit, overflowPolicy = overflowPolicy,
                        fallbackTaskId = fallbackTaskId,
                        lifetimeConsumed = if (lifetime == profile.lifetime) profile.lifetimeConsumed else false,
                    ),
                    onSaved = { clearProfileDialog() },
                )
            },
            onSimulate = { editedProfile -> openSimulation(editedProfile) },
        )
    }

    semanticDiffReview?.let { SemanticDiffDialog(it.document, viewModel::clearSemanticDiffReview) }

    actionPickerTask?.let { task ->
        ActionPickerDialog(
            onDismiss = { clearActionPicker() },
            onSelect = { metadata ->
                clearActionPicker()
                openActionEdit(task, metadata)
            },
        )
    }

    actionEdit?.let { state ->
        ActionConfigDialog(
            state = state,
            tasks = tasks,
            enclosingActions = state.task.actions,
            globalVariables = projectVariables,
            onDismiss = { clearActionEdit() },
            onSave = { action ->
                val updatedActions = state.index?.let { index ->
                    state.task.actions.mapIndexed { i, existing -> if (i == index) action else existing }
                } ?: (state.task.actions + action)
                viewModel.updateTask(
                    state.task.copy(actions = updatedActions),
                    if (state.index == null) R.string.ui_message_action_added else R.string.ui_message_action_updated,
                    onSaved = { clearActionEdit() },
                )
            },
        )
    }
    contextPickerProfile?.let { profile ->
        ContextTypePickerDialog(
            onDismiss = { clearContextPicker() },
            onSelect = { type ->
                clearContextPicker()
                openContextEdit(profile, type)
            },
        )
    }

    contextEdit?.let { state ->
        ContextConfigDialog(
            state = state,
            onDismiss = { clearContextEdit() },
            onSimulate = { context ->
                val updatedContexts = state.index?.let { index ->
                    state.profile.contexts.mapIndexed { i, existing -> if (i == index) context else existing }
                } ?: (state.profile.contexts + context)
                val updatedExpression = if (state.index == null) {
                    state.profile.contextExpression?.appendLeaf(updatedContexts.lastIndex)
                } else {
                    state.profile.contextExpression
                }
                openSimulation(
                    state.profile.copy(
                        contexts = updatedContexts,
                        contextExpression = updatedExpression,
                    ),
                )
            },
            onSave = { context ->
                val updatedContexts = state.index?.let { index ->
                    state.profile.contexts.mapIndexed { i, existing -> if (i == index) context else existing }
                } ?: (state.profile.contexts + context)
                val updatedExpression = if (state.index == null) {
                    state.profile.contextExpression?.appendLeaf(updatedContexts.lastIndex)
                } else {
                    state.profile.contextExpression
                }
                viewModel.updateProfile(
                    state.profile.copy(contexts = updatedContexts, contextExpression = updatedExpression),
                    if (state.index == null) R.string.ui_message_context_added else R.string.ui_message_context_updated,
                    onSaved = { clearContextEdit() },
                )
            },
        )
    }

    contextLogicProfile?.let { profile ->
        ContextGroupingDialog(
            profile = profile,
            onDismiss = { clearContextLogic() },
            onSave = { expression ->
                viewModel.updateProfile(
                    profile.copy(contextExpression = expression),
                    R.string.context_logic_updated,
                    onSaved = { clearContextLogic() },
                )
            },
        )
    }

    // Declared last so the simulation sits above whichever editor launched it: the editor stays
    // open behind it, and dismissing the simulation returns the user to their unsaved edits.
    simulationProfile?.let { profile ->
        SyntheticTriggerSimulationDialog(
            profile = profile,
            onDismiss = { clearSimulation() },
        )
    }
}

private const val DIAGNOSTICS_REFRESH_INTERVAL_MS = 5_000L
