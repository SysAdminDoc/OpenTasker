package com.opentasker.ui.screens

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.opentasker.app.R
import com.opentasker.ui.theme.DesignSystem
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.opentasker.core.contexts.ContextEventObservation
import com.opentasker.core.contexts.ContextInspectionSnapshot
import com.opentasker.core.contexts.ContextObservationStatus
import com.opentasker.core.contexts.ContextSourceRegistry
import com.opentasker.core.contexts.ContextSourceSnapshot
import com.opentasker.core.contexts.ContextSourceStatus
import com.opentasker.core.contexts.NfcTagIntentGate
import com.opentasker.core.contexts.ProfileInspection
import com.opentasker.core.contexts.STAND_IN_MARKER_PREFIX
import com.opentasker.core.contexts.TimeContextBounds
import com.opentasker.core.contexts.inspectProfiles
import com.opentasker.core.contexts.observationStatus
import com.opentasker.core.capabilities.AutomationLint
import com.opentasker.core.capabilities.AutomationLintFinding
import com.opentasker.core.capabilities.AutomationLintReport
import com.opentasker.core.capabilities.AutomationLintSeverity
import com.opentasker.core.capabilities.AutomationLintStrings
import com.opentasker.core.capabilities.AutomationInvariantStore
import com.opentasker.core.capabilities.PlatformSupport
import com.opentasker.core.contexts.BroadcastContextEvents
import com.opentasker.core.engine.CausalLoopDiagnostics
import com.opentasker.core.location.LocationDwellStateStore
import com.opentasker.core.location.LocationPolicyDisclosures
import com.opentasker.core.logging.AppLogger
import com.opentasker.core.model.ContextType
import com.opentasker.core.model.AutomationInvariant
import com.opentasker.core.model.Profile
import com.opentasker.core.model.ProfileLifecycleStrings
import com.opentasker.core.model.Task
import com.opentasker.core.permissions.OemBatteryGuidance
import com.opentasker.core.permissions.UsageAccess
import com.opentasker.core.scheduling.ExactAlarmSupport
import com.opentasker.core.storage.AppDatabase
import com.opentasker.core.storage.StorageDecodeIssue
import com.opentasker.core.storage.StorageDecodeResult
import com.opentasker.core.storage.VariableRepository
import com.opentasker.ui.utils.expandCollapseToggle
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Date
import java.util.Locale

class ContextInspectorViewModel(
    db: AppDatabase,
    private val appContext: Context,
    private val clock: () -> Long = { System.currentTimeMillis() },
) : ViewModel() {
    private val latestEvents = MutableStateFlow<Map<String, ContextEventObservation>>(emptyMap())
    private val sourceErrors = MutableStateFlow<Map<String, String>>(emptyMap())
    private val refreshTick = MutableStateFlow(clock())
    private val sourceCollectorJobs = mutableMapOf<String, Job>()
    private var refreshJob: Job? = null
    private val locationDwellStateStore = LocationDwellStateStore(appContext, clock)
    private val invariantStore = AutomationInvariantStore(appContext)

    /** The profile a synthetic-trigger simulation is running against; survives rotation. */
    private val _simulationProfile = MutableStateFlow<Profile?>(null)
    val simulationProfile: StateFlow<Profile?> = _simulationProfile.asStateFlow()

    fun openSimulation(profile: Profile) {
        _simulationProfile.value = profile
    }

    fun clearSimulation() {
        _simulationProfile.value = null
    }

    // Null until Room's first emission. An empty seed made a cold start look exactly like having
    // no profiles, so the Inspector flashed its first-run empty state on every open (A-360).
    private val profileDecodeResults = db.profileDao()
        .getAllAsFlow()
        .map<_, List<StorageDecodeResult<Profile>>?> { entities -> entities.map { it.toDomainDecodeResult() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val loadedProfiles: StateFlow<List<Profile>?> = profileDecodeResults
        .map { results ->
            results?.mapNotNull { result -> result.value.takeIf { result.issue == null } }
                ?.sortedBy { it.name.lowercase(Locale.US) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val profiles: StateFlow<List<Profile>> = loadedProfiles
        .map { it.orEmpty() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val taskDecodeResults = db.taskDao()
        .getAllAsFlow()
        .map { entities -> entities.map { it.toDomainDecodeResult() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val tasks: StateFlow<List<Task>> = taskDecodeResults
        .map { results ->
            results.mapNotNull { result -> result.value.takeIf { result.issue == null } }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val invariants: StateFlow<List<AutomationInvariant>> = invariantStore.observe()
        .stateIn(viewModelScope, SharingStarted.Eagerly, invariantStore.load())

    fun updateInvariants(value: List<AutomationInvariant>) {
        invariantStore.save(value)
    }

    /** Undo for a delete, applied to the saved list as it is when Undo is tapped. */
    fun restoreInvariant(deleted: AutomationInvariant, index: Int) {
        val restored = withRestoredInvariant(invariantStore.load(), deleted, index) ?: return
        invariantStore.save(restored)
    }

    val lintReport: StateFlow<AutomationLintReport> = combine(profiles, tasks, invariants) { profiles, tasks, invariants ->
        AutomationLint.analyze(
            profiles,
            tasks,
            strings = AutomationLintStrings.from(appContext.resources),
            invariants = invariants,
            nowMs = clock(),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AutomationLintReport())

    val lintFindings: StateFlow<Map<Long, List<AutomationLintFinding>>> = lintReport.map { report ->
        report.findings
            .flatMap { finding -> finding.profileIds.map { profileId -> profileId to finding } }
            .groupBy({ it.first }, { it.second })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    val storageDecodeIssues: StateFlow<List<StorageDecodeIssue>> = profileDecodeResults
        .map { results -> results.orEmpty().mapNotNull { it.issue } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * The saved globals that bound a Time context (%Sunrise), by project, so the Inspector reads what
     * the engine reads and redraws only when one of them changes.
     */
    private val globalsByProject: StateFlow<Map<Long, Map<String, String>>> = combine(
        loadedProfiles.map { profiles -> TimeContextBounds.boundGlobals(profiles.orEmpty()) }.distinctUntilChanged(),
        VariableRepository(db.variableDao()).observeGlobals(null),
    ) { names, variables -> TimeContextBounds.valuesByProject(variables, names) }
        .distinctUntilChanged()
        .catch { error ->
            AppLogger.warn("ContextInspector", "Could not read globals for Time context bounds", error)
            emit(emptyMap())
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** Null until the profiles have loaded, so the screen can tell "still reading" from "none". */
    val snapshot: StateFlow<ContextInspectionSnapshot?> = combine(
        loadedProfiles,
        latestEvents,
        sourceErrors,
        refreshTick,
        combine(CausalLoopDiagnostics.latest, globalsByProject, ::Pair),
    ) { profiles, observations, errors, now, (causalLoop, globals) ->
        if (profiles == null) return@combine null
        val sources = buildContextSourceSnapshots(appContext, profiles, observations, errors)
        ContextInspectionSnapshot(
            generatedAtMs = now,
            sources = sources,
            profiles = inspectProfiles(
                profiles = profiles,
                sourceSnapshots = sources,
                observationTransformer = { profile, index, spec, observation ->
                    when (spec.type) {
                        // observe() is read-only: the Inspector must never persist or clear the
                        // engine's dwell timers from its own independent location stream.
                        ContextType.LOCATION ->
                            observation.copy(event = locationDwellStateStore.observe(profile.id, index, spec, observation.event))
                        ContextType.TIME -> observation.copy(
                            event = TimeContextBounds.withVariableValues(spec, observation.event, globals[profile.projectId].orEmpty()),
                        )
                        else -> observation
                    }
                },
                nowMs = now,
                lifecycleStrings = ProfileLifecycleStrings.from(appContext.resources),
            ),
            causalLoop = causalLoop,
        )
    }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun refresh() {
        refreshTick.value = clock()
    }

    /** Start only while the inspector is visible; the shared event bus then has real demand. */
    fun startObserving() {
        if (sourceCollectorJobs.values.any(Job::isActive)) return
        requiredContextSourceKeys().forEach { key ->
            val source = ContextSourceRegistry.get(key) ?: return@forEach
            sourceCollectorJobs[key] = viewModelScope.launch {
                source.events(appContext)
                    .catch { error ->
                        AppLogger.warn("OpenTasker.ContextInspector", "Context source failed for $key", error)
                        sourceErrors.update { current ->
                            current + (key to appContext.getString(R.string.inspector_source_error))
                        }
                    }
                    .collect { event ->
                        sourceErrors.update { current -> current - key }
                        latestEvents.update { current ->
                            current + (key to ContextEventObservation(event, clock()))
                        }
                        refresh()
                    }
            }
        }
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            while (isActive) {
                delay(5_000)
                refresh()
            }
        }
    }

    fun stopObserving() {
        sourceCollectorJobs.values.forEach(Job::cancel)
        sourceCollectorJobs.clear()
        refreshJob?.cancel()
        refreshJob = null
        latestEvents.value = emptyMap()
        sourceErrors.value = emptyMap()
        refresh()
    }

    override fun onCleared() {
        stopObserving()
        super.onCleared()
    }

}

class ContextInspectorViewModelFactory(
    private val db: AppDatabase,
    private val appContext: Context,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ContextInspectorViewModel::class.java)) {
            return ContextInspectorViewModel(db, appContext) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}

@Composable
fun ContextInspectorScreen(
    db: AppDatabase,
    contentPadding: PaddingValues,
    onUndoableMessage: UndoableMessage,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current.applicationContext
    val factory = remember(db, context) { ContextInspectorViewModelFactory(db, context) }
    val viewModel: ContextInspectorViewModel = viewModel(factory = factory)
    val loadedSnapshot by viewModel.snapshot.collectAsState()
    val storageDecodeIssues by viewModel.storageDecodeIssues.collectAsState()
    val lintFindings by viewModel.lintFindings.collectAsState()
    val invariants by viewModel.invariants.collectAsState()
    val lintReport by viewModel.lintReport.collectAsState()
    val simulationProfile by viewModel.simulationProfile.collectAsState()
    var selectedProfileId by rememberSaveable { mutableStateOf<Long?>(null) }

    DisposableEffect(viewModel) {
        viewModel.startObserving()
        onDispose { viewModel.stopObserving() }
    }

    val snapshot = loadedSnapshot ?: run {
        ContentLoadingState(contentPadding)
        return
    }
    val selectedProfile = snapshot.profiles.firstOrNull { it.profileId == selectedProfileId }
        ?: snapshot.profiles.firstOrNull()

    LaunchedEffect(snapshot.profiles, selectedProfileId) {
        if (selectedProfileId == null || snapshot.profiles.none { it.profileId == selectedProfileId }) {
            selectedProfileId = snapshot.profiles.firstOrNull()?.profileId
        }
    }

    if (snapshot.sources.isEmpty() && snapshot.profiles.isEmpty() && storageDecodeIssues.isEmpty() && invariants.isEmpty()) {
        InspectorEmptyState(contentPadding)
        return
    }

    val oem = remember { OemBatteryGuidance.forDevice(Build.MANUFACTURER, Build.BRAND) }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(contentPadding),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.md),
    ) {
        item {
            ContextInspectorSummaryCard(snapshot = snapshot, onRefresh = viewModel::refresh)
        }
        snapshot.causalLoop?.let { causalLoop ->
            item {
                InspectorNotice(
                    title = stringResource(R.string.inspector_causal_loop_title),
                    body = stringResource(
                        R.string.inspector_causal_loop_body,
                        causalLoop.profileChain.joinToString(" -> "),
                    ),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        if (storageDecodeIssues.isNotEmpty()) {
            item {
                StorageDecodeWarningCard(storageDecodeIssues)
            }
        }
        if (oem.needsExtraSteps) {
            item {
                OemRiskNotice(oem)
            }
        }
        item {
            Text(
                stringResource(R.string.inspector_match_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (snapshot.profiles.isEmpty()) {
            item {
                InspectorNotice(
                    title = stringResource(R.string.empty_profiles_inspector),
                    body = stringResource(R.string.inspector_no_profiles_body),
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        } else {
            item {
                InspectorProfileSelector(
                    profiles = snapshot.profiles,
                    selectedProfileId = selectedProfile?.profileId,
                    onSelected = { selectedProfileId = it },
                )
            }
            selectedProfile?.let { profile ->
                item(key = profile.profileId) {
                    ProfileInspectorCard(
                        profile = profile,
                        nowMs = snapshot.generatedAtMs,
                        lintFindings = lintFindings[profile.profileId].orEmpty(),
                        onSimulate = { candidate -> viewModel.openSimulation(candidate) },
                    )
                }
            }
        }
        item {
            Text(
                stringResource(R.string.inspector_sources_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        items(snapshot.sources, key = { it.key }) { source ->
            ContextSourceCard(source = source, nowMs = snapshot.generatedAtMs)
        }
        item {
            AutomationInvariantPanel(
                invariants = invariants,
                report = lintReport,
                onUpdate = viewModel::updateInvariants,
                onRestore = viewModel::restoreInvariant,
                onUndoableMessage = onUndoableMessage,
            )
        }
    }

    simulationProfile?.let { profile ->
        SyntheticTriggerSimulationDialog(
            profile = profile,
            onDismiss = { viewModel.clearSimulation() },
        )
    }
}

@Composable
private fun InspectorProfileSelector(
    profiles: List<ProfileInspection>,
    selectedProfileId: Long?,
    onSelected: (Long) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val selected = profiles.firstOrNull { it.profileId == selectedProfileId } ?: profiles.first()

    Box {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(DesignSystem.Radii.md),
        ) {
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
                Text(
                    stringResource(R.string.inspector_profile_selector),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(selected.profileName, style = MaterialTheme.typography.labelLarge)
            }
            Icon(Icons.Filled.KeyboardArrowDown, contentDescription = stringResource(R.string.inspector_profile_selector_content_description))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            profiles.forEach { profile ->
                DropdownMenuItem(
                    text = { Text(profile.profileName) },
                    onClick = {
                        onSelected(profile.profileId)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun ContextInspectorSummaryCard(
    snapshot: ContextInspectionSnapshot,
    onRefresh: () -> Unit,
) {
    val attentionSources = snapshot.sources.count {
        it.status == ContextSourceStatus.NeedsSetup ||
            it.status == ContextSourceStatus.Missing ||
            it.status == ContextSourceStatus.Error
    }
    val healthColor = if (attentionSources == 0) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        shape = RoundedCornerShape(DesignSystem.Radii.md),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                Icons.Filled.CheckCircle,
                contentDescription = stringResource(R.string.nav_inspector),
                tint = healthColor,
                modifier = Modifier.size(20.dp),
            )
            Text(
                if (attentionSources == 0) {
                    stringResource(R.string.inspector_ready)
                } else {
                    stringResource(R.string.inspector_attention, attentionSources)
                },
                style = MaterialTheme.typography.titleSmall,
                color = healthColor,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onRefresh) {
                Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.inspector_refresh))
            }
        }
    }
}

@Composable
private fun ContextSourceCard(source: ContextSourceSnapshot, nowMs: Long) {
    val color = sourceStatusColor(source.status)
    val observationStatus = source.observationStatus(nowMs)
    val observation = source.lastObservation
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when (source.status) {
                ContextSourceStatus.NeedsSetup,
                ContextSourceStatus.Missing,
                ContextSourceStatus.Error -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.20f)
                else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.30f)
            },
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shape = RoundedCornerShape(DesignSystem.Radii.md),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.md)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.md)) {
                Icon(sourceStatusIcon(source.status), contentDescription = stringResource(source.status.resourceId), tint = color, modifier = Modifier.size(22.dp))
                Column(Modifier.weight(1f)) {
                    Text(source.label, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val lastUpdateLabel = stringResource(R.string.inspector_last_update)
                    val noValueLabel = stringResource(R.string.inspector_no_value)
                    Text(
                        observation?.let {
                            stringResource(
                                R.string.inspector_last_update_value,
                                lastUpdateLabel,
                                formatRelativeTime(LocalContext.current, it.observedAtMs, nowMs),
                            )
                        } ?: noValueLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                InspectorStatusPill(stringResource(source.status.resourceId), color)
                InspectorStatusPill(
                    stringResource(observationStatus.resourceId),
                    observationStatusColor(observationStatus),
                )
            }
            source.setupDetail?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            source.error?.let {
                InspectorNotice(stringResource(R.string.inspector_source_error), it, MaterialTheme.colorScheme.error)
            }
            observation?.let {
                ContextMetadataBlock(event = it, nowMs = nowMs)
            }
        }
    }
}

@Composable
private fun ProfileInspectorCard(
    profile: ProfileInspection,
    nowMs: Long,
    lintFindings: List<AutomationLintFinding>,
    onSimulate: (Profile) -> Unit,
) {
    var lintExpanded by remember(profile.profileId) { mutableStateOf(false) }
    var detailsExpanded by remember(profile.profileId) { mutableStateOf(false) }
    val color = when {
        !profile.enabled -> MaterialTheme.colorScheme.onSurfaceVariant
        profile.matching -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.error
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (profile.matching) {
                MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.22f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.58f)
            },
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shape = RoundedCornerShape(DesignSystem.Radii.md),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.md)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.md)) {
                Icon(
                    if (profile.matching) Icons.Filled.CheckCircle else Icons.Filled.Info,
                    contentDescription = if (profile.matching) stringResource(R.string.status_matching) else stringResource(R.string.inspector_not_matching),
                    tint = color,
                    modifier = Modifier.size(22.dp),
                )
                Column(Modifier.weight(1f)) {
                    Text(profile.profileName, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(profile.summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                InspectorStatusPill(
                    label = when {
                        !profile.enabled -> stringResource(R.string.status_disabled)
                        profile.matching -> stringResource(R.string.status_matching)
                        else -> stringResource(R.string.status_blocked)
                    },
                    color = color,
                )
            }
            // Outside the details: "Blocked" with no reason is the whole symptom of a trigger this
            // release can't deliver, so the reason has to be visible without digging.
            val platformBlocked = remember(profile.profile) {
                profile.profile?.let { PlatformSupport.blockedTriggers(listOf(it), Build.VERSION.SDK_INT) }.orEmpty()
            }
            platformBlocked.forEach { blocked ->
                InspectorNotice(
                    title = stringResource(R.string.inspector_platform_blocked_title),
                    body = stringResource(
                        R.string.inspector_platform_blocked_body,
                        stringResource(platformFamilyLabelRes(blocked.trigger)),
                        PlatformSupport.androidRelease(blocked.trigger.minimumApi),
                        Build.VERSION.RELEASE,
                    ),
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (detailsExpanded) {
                profile.profile?.let { candidate ->
                    Text(
                        stringResource(
                            R.string.inspector_profile_policy,
                            candidate.priority,
                            candidate.gracePeriodSec,
                            profileLifetimeTitle(candidate.lifetime),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        stringResource(
                            R.string.inspector_profile_concurrency,
                            candidate.maxActiveExecutions?.toString() ?: stringResource(R.string.profile_concurrency_default),
                            candidate.burstLimit?.toString() ?: stringResource(R.string.profile_concurrency_default),
                            profileOverflowPolicyTitle(candidate.overflowPolicy),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (candidate.lifetime == com.opentasker.core.model.ProfileLifetime.UNTIL_DATE && candidate.expiresAtMs != null) {
                        Text(
                            stringResource(R.string.inspector_profile_expiry, formatProfileExpiryDate(candidate.expiresAtMs)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                profile.suppressionReason?.let { reason ->
                    InspectorNotice(
                        title = stringResource(R.string.inspector_profile_suppressed_title),
                        body = reason,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
                if (profile.logicExplanation.isNotBlank()) {
                    Text(
                        profile.logicExplanation,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
                if (profile.contexts.isEmpty()) {
                    InspectorNotice(
                        title = stringResource(R.string.inspector_no_contexts),
                        body = stringResource(R.string.inspector_no_contexts_body),
                        color = MaterialTheme.colorScheme.error,
                    )
                } else {
                    profile.contexts.forEach { check ->
                        ContextCheckRow(check = check, nowMs = nowMs)
                    }
                }
            }
            // Not the "Blocks enabling" prefix: this list describes profiles that already exist
            // and are already running, so nothing here is being refused. That prefix belongs to
            // the imported-profile review, which really does withhold the enable button.
            val conflictPrefix = stringResource(R.string.automation_lint_conflict_prefix)
            val warningPrefix = stringResource(R.string.automation_lint_warning_prefix)
            if (lintFindings.isNotEmpty()) {
                val lintColor = if (lintFindings.any { it.severity == AutomationLintSeverity.BLOCKING }) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.secondary
                }
                Surface(
                    color = lintColor.copy(alpha = 0.12f),
                    border = BorderStroke(1.dp, lintColor.copy(alpha = 0.42f)),
                    shape = RoundedCornerShape(DesignSystem.Radii.md),
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .expandCollapseToggle(lintExpanded) { lintExpanded = !lintExpanded },
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Icon(
                                Icons.Filled.Info,
                                contentDescription = null,
                                tint = lintColor,
                                modifier = Modifier.size(20.dp),
                            )
                            Column(Modifier.weight(1f)) {
                                Text(stringResource(R.string.inspector_lint_title), style = MaterialTheme.typography.labelLarge)
                                Text(
                                    stringResource(R.string.inspector_lint_count, lintFindings.size),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Icon(
                                if (lintExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                                contentDescription = stringResource(if (lintExpanded) R.string.action_collapse else R.string.action_expand),
                                tint = lintColor,
                            )
                        }
                        if (lintExpanded) {
                            Text(
                                lintFindings.joinToString("\n") { finding ->
                                    val prefix = if (finding.severity == AutomationLintSeverity.BLOCKING) {
                                        conflictPrefix
                                    } else {
                                        warningPrefix
                                    }
                                    "$prefix ${finding.title}: ${finding.detail} ${finding.suggestedFix}"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            profile.profile?.let { candidate ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.sm),
                ) {
                    TextButton(onClick = { detailsExpanded = !detailsExpanded }, modifier = Modifier.weight(1f)) {
                        Text(stringResource(if (detailsExpanded) R.string.diagnostics_hide_details else R.string.diagnostics_show_details))
                    }
                    OutlinedButton(onClick = { onSimulate(candidate) }, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.inspector_simulate_trigger), maxLines = 1)
                    }
                }
            }
        }
    }
}

@Composable
private fun ContextCheckRow(
    check: com.opentasker.core.contexts.ContextCheck,
    nowMs: Long,
) {
    val context = LocalContext.current
    val color = if (check.effectiveMatched) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.64f),
        shape = RoundedCornerShape(DesignSystem.Radii.lg),
        border = BorderStroke(1.dp, color.copy(alpha = 0.20f)),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.sm)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.sm)) {
                InspectorStatusPill(
                    stringResource(R.string.inspector_context_number, check.index + 1),
                    MaterialTheme.colorScheme.secondary,
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(
                            R.string.inspector_context_source,
                            stringResource(contextTitleRes(check.spec.type)),
                            check.sourceLabel,
                        ),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        check.configSummary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                InspectorStatusPill(
                    if (check.effectiveMatched) stringResource(R.string.inspector_match) else stringResource(R.string.inspector_no_match),
                    color,
                )
            }
            Text(check.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            locationDwellDetail(context, check, nowMs)?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            check.lastObservation?.let {
                Text(
                    stringResource(R.string.inspector_observed, formatRelativeTime(context, it.observedAtMs, nowMs)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ContextMetadataBlock(event: ContextEventObservation, nowMs: Long) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.62f),
        shape = RoundedCornerShape(DesignSystem.Radii.lg),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.42f)),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.inspector_latest_value), style = MaterialTheme.typography.labelLarge)
            Text(
                stringResource(R.string.inspector_matched, event.event.matched),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            event.event.metadata["component"]?.let { component ->
                Text(
                    if (component.isBlank()) {
                        stringResource(R.string.inspector_component_unavailable)
                    } else {
                        stringResource(R.string.inspector_observed_component, component)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            event.event.metadata.entries
                .filterNot {
                    it.key == "component" || it.key == "component_status" ||
                        it.key.startsWith("_setup_") || it.key.startsWith(STAND_IN_MARKER_PREFIX)
                }
                .sortedBy { it.key }
                .forEach { (key, value) ->
                    Text(
                        stringResource(R.string.inspector_metadata, key, value),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
            }
            Text(
                formatAbsoluteTime(LocalContext.current, event.observedAtMs, nowMs),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun InspectorMetric(value: String, label: String, modifier: Modifier = Modifier) {
    SummaryMetric(value = value, label = label, modifier = modifier)
}

@Composable
private fun InspectorStatusPill(label: String, color: Color) {
    StatusPill(label = label, color = color)
}

@Composable
private fun OemRiskNotice(oem: OemBatteryGuidance.Guidance) {
    val color = when (oem.riskLevel) {
        OemBatteryGuidance.RiskLevel.SEVERE, OemBatteryGuidance.RiskLevel.HIGH -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.primary
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = color.copy(alpha = 0.12f),
        shape = RoundedCornerShape(DesignSystem.Radii.lg),
        border = BorderStroke(1.dp, color.copy(alpha = 0.26f)),
    ) {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Filled.Error, contentDescription = stringResource(R.string.inspector_warning), tint = color, modifier = Modifier.size(20.dp))
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    stringResource(
                        R.string.inspector_oem_risk,
                        oem.oemName,
                        stringResource(oemRiskLevelLabelRes(oem.riskLevel)),
                    ),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    stringResource(R.string.inspector_oem_summary, oem.summary),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun InspectorNotice(title: String, body: String, color: Color) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = color.copy(alpha = 0.12f),
        shape = RoundedCornerShape(DesignSystem.Radii.lg),
        border = BorderStroke(1.dp, color.copy(alpha = 0.26f)),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun InspectorEmptyState(contentPadding: PaddingValues) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.64f),
            shape = RoundedCornerShape(com.opentasker.ui.theme.DesignSystem.Radii.xxl),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.52f)),
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.md),
            ) {
                Icon(
                    Icons.Filled.Info,
                    contentDescription = stringResource(R.string.inspector_unavailable_content_description),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(32.dp),
                )
                Text(stringResource(R.string.inspector_unavailable_title), style = MaterialTheme.typography.titleLarge)
                Text(
                    stringResource(R.string.inspector_unavailable_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                InspectorStatusPill(
                    label = stringResource(R.string.inspector_waiting_for_sources),
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun sourceStatusColor(status: ContextSourceStatus): Color = when (status) {
    ContextSourceStatus.Active -> MaterialTheme.colorScheme.tertiary
    ContextSourceStatus.Waiting -> MaterialTheme.colorScheme.secondary
    ContextSourceStatus.NeedsSetup,
    ContextSourceStatus.Missing,
    ContextSourceStatus.Error -> MaterialTheme.colorScheme.error
}

@Composable
private fun observationStatusColor(status: ContextObservationStatus): Color = when (status) {
    ContextObservationStatus.Ready -> MaterialTheme.colorScheme.tertiary
    ContextObservationStatus.Loading -> MaterialTheme.colorScheme.secondary
    ContextObservationStatus.Stale -> MaterialTheme.colorScheme.onSurfaceVariant
    ContextObservationStatus.Error -> MaterialTheme.colorScheme.error
}

private fun sourceStatusIcon(status: ContextSourceStatus) = when (status) {
    ContextSourceStatus.Active -> Icons.Filled.CheckCircle
    ContextSourceStatus.Waiting -> Icons.Filled.Info
    ContextSourceStatus.NeedsSetup,
    ContextSourceStatus.Missing,
    ContextSourceStatus.Error -> Icons.Filled.Error
}

private fun buildContextSourceSnapshots(
    context: Context,
    profiles: List<Profile>,
    observations: Map<String, ContextEventObservation>,
    errors: Map<String, String>,
): List<ContextSourceSnapshot> {
    val registeredKeys = ContextSourceRegistry.all().map { it.type }.toSet()
    val keys = (requiredContextSourceKeys() + registeredKeys).sorted()
    return keys.map { key ->
        val setup = contextSourceSetup(context, key)
        ContextSourceSnapshot(
            key = key,
            label = context.getString(sourceLabelResource(key)),
            registered = key in registeredKeys,
            setupReady = setup.ready,
            setupDetail = setup.detail,
            error = errors[key],
            lastObservation = observations[key],
            eventSetupGaps = if (key == "event") eventSetupGaps(context, profiles) else emptyMap(),
        )
    }
}

/**
 * Android 16+ drops tag scans for an app the user hasn't allowed, so NFC events never arrive. Only
 * asked while an enabled profile waits for one, which is also when Setup shows the row it points to.
 */
private fun eventSetupGaps(context: Context, profiles: List<Profile>): Map<String, String> {
    val waitsForNfc = profiles.any { profile ->
        profile.enabled && profile.contexts.any { it.type == ContextType.EVENT && it.config["event"].equals("nfc", ignoreCase = true) }
    }
    if (!waitsForNfc) return emptyMap()
    val nfcTags = SetupProbes(::logFailedCheck).read("NFC tag reading", NfcTagIntentGate.State.NOT_APPLICABLE) {
        NfcTagIntentGate.current(context)
    }
    return if (nfcTags == NfcTagIntentGate.State.BLOCKED) {
        mapOf("nfc" to context.getString(R.string.inspector_setup_nfc_blocked))
    } else {
        emptyMap()
    }
}

private fun sourceLabelResource(key: String): Int = when (key) {
    "app" -> R.string.context_source_application
    "time" -> R.string.context_source_time
    "state" -> R.string.context_source_state
    "event" -> R.string.context_source_event
    "location" -> R.string.context_source_location
    "plugin" -> R.string.context_source_plugin
    else -> R.string.context_source_unknown
}

private data class ContextSourceSetup(val ready: Boolean, val detail: String)

private fun contextSourceSetup(context: Context, key: String): ContextSourceSetup = when (key) {
    "app" -> {
        val granted = UsageAccess.hasUsageStatsAccess(context)
        ContextSourceSetup(
            ready = granted,
            detail = if (granted) {
                context.getString(R.string.inspector_setup_usage_granted)
            } else {
                context.getString(R.string.inspector_setup_usage_missing)
            },
        )
    }
    "time" -> {
        val exactReady = ExactAlarmSupport.canScheduleExactAlarms(context)
        ContextSourceSetup(
            ready = true,
            detail = if (exactReady) {
                context.getString(R.string.inspector_setup_clock_exact)
            } else {
                context.getString(R.string.inspector_setup_clock_inexact)
            },
        )
    }
    "state" -> {
        val wifiReady = Build.VERSION.SDK_INT < 33 || hasPermission(context, Manifest.permission.NEARBY_WIFI_DEVICES)
        val locationReady = hasPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
        ContextSourceSetup(
            ready = true,
            detail = if (wifiReady && locationReady) {
                context.getString(R.string.inspector_setup_state_ready)
            } else {
                context.getString(R.string.inspector_setup_state_partial)
            },
        )
    }
    "event" -> {
        val notificationReady = hasNotificationListenerAccess(context)
        val calendarReady = hasPermission(context, Manifest.permission.READ_CALENDAR)
        val calendarDetail = if (calendarReady) {
            context.getString(R.string.inspector_setup_calendar_ready)
        } else {
            context.getString(R.string.inspector_setup_calendar_missing)
        }
        // The broadcast receiver's state is not a permission, it is a live registration, and the
        // question people actually have is whether anything is listening yet.
        val listening = BroadcastContextEvents.listeningActions()
        val broadcastDetail = if (listening.isEmpty()) {
            context.getString(R.string.inspector_broadcast_idle)
        } else {
            context.getString(
                R.string.inspector_broadcast_listening,
                listening.size,
                listening.sorted().joinToString(", "),
            )
        }
        ContextSourceSetup(
            ready = true,
            detail = listOfNotNull(
                if (notificationReady) {
                    context.getString(R.string.inspector_setup_event_ready, calendarDetail)
                } else {
                    context.getString(R.string.inspector_setup_event_notification_missing, calendarDetail)
                },
                broadcastDetail,
            ).joinToString(" "),
        )
    }
    "location" -> {
        val foreground = hasAnyLocationPermission(context)
        val precise = hasPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
        val background = Build.VERSION.SDK_INT < 29 || hasPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        val providerEnabled = hasEnabledLocationProvider(context)
        ContextSourceSetup(
            ready = LocationPolicyDisclosures.sourceReady(precise = precise, providerEnabled = providerEnabled),
            detail = LocationPolicyDisclosures.sourceSetupDetail(
                foreground = foreground,
                precise = precise,
                background = background,
                providerEnabled = providerEnabled,
                apiLevel = Build.VERSION.SDK_INT,
            ),
        )
    }
    else -> ContextSourceSetup(ready = true, detail = context.getString(R.string.inspector_setup_source_generic))
}

private fun requiredContextSourceKeys(): Set<String> =
    ContextType.entries.mapNotNull { com.opentasker.core.contexts.ContextMatchEvaluator.sourceKey(it) }.toSet()

private fun hasPermission(context: Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

private fun hasAnyLocationPermission(context: Context): Boolean =
    hasPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ||
        hasPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)

private fun hasEnabledLocationProvider(context: Context): Boolean {
    val locationManager = context.getSystemService(LocationManager::class.java) ?: return false
    return runCatching { locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrDefault(false) ||
        runCatching { locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) }.getOrDefault(false)
}

private fun hasNotificationListenerAccess(context: Context): Boolean {
    val enabledListeners = android.provider.Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
    return enabledListeners?.contains(context.packageName, ignoreCase = true) == true
}

private val ContextSourceStatus.resourceId: Int
    get() = when (this) {
        ContextSourceStatus.Active -> R.string.inspector_status_active
        ContextSourceStatus.Waiting -> R.string.inspector_status_waiting
        ContextSourceStatus.NeedsSetup -> R.string.inspector_status_needs_setup
        ContextSourceStatus.Missing -> R.string.inspector_status_missing
        ContextSourceStatus.Error -> R.string.inspector_status_error
    }

private val ContextObservationStatus.resourceId: Int
    get() = when (this) {
        ContextObservationStatus.Loading -> R.string.inspector_observation_loading
        ContextObservationStatus.Ready -> R.string.inspector_observation_ready
        ContextObservationStatus.Stale -> R.string.inspector_observation_stale
        ContextObservationStatus.Error -> R.string.inspector_observation_error
    }

private fun formatRelativeTime(context: Context, observedAtMs: Long, nowMs: Long): String {
    val seconds = ((nowMs - observedAtMs) / 1000L).coerceAtLeast(0)
    return when {
        seconds < 5 -> context.getString(R.string.inspector_time_just_now)
        seconds < 60 -> context.getString(R.string.inspector_time_seconds, seconds)
        seconds < 3_600 -> context.getString(R.string.inspector_time_minutes, seconds / 60)
        else -> context.getString(R.string.inspector_time_hours, seconds / 3_600)
    }
}

private fun formatAbsoluteTime(context: Context, observedAtMs: Long, nowMs: Long): String {
    val formatted = displayDateTimeFormat(context).format(Date(observedAtMs))
    return context.getString(R.string.inspector_time_absolute, formatted, formatRelativeTime(context, observedAtMs, nowMs))
}

private fun locationDwellDetail(context: Context, check: com.opentasker.core.contexts.ContextCheck, nowMs: Long): String? {
    if (check.spec.type != ContextType.LOCATION) return null
    val observation = check.lastObservation ?: return null
    val metadata = observation.event.metadata
    val state = metadata["dwellState"] ?: return null
    val dwellMillis = parseDwellMillis(check.spec.config)
    val target = dwellMillis.takeIf { it > 0L }?.let { context.getString(R.string.inspector_dwell_target, formatDuration(context, it)) }.orEmpty()
    val observedAt = metadata["observedAtEpochMs"]?.toLongOrNull() ?: observation.observedAtMs
    val insideSince = metadata["insideSinceEpochMs"]?.toLongOrNull()
    val insideFor = insideSince?.let { formatDuration(context, (observedAt - it).coerceAtLeast(0L)) }

    return when (state) {
        "inside" -> insideFor?.let { context.getString(R.string.inspector_dwell_inside, it, target) }
            ?: context.getString(R.string.inspector_dwell_inside_waiting)
        "accuracy_blocked" -> insideFor?.let {
            context.getString(R.string.inspector_dwell_accuracy_blocked, it, target)
        } ?: context.getString(R.string.inspector_dwell_accuracy_blocked_no_timer)
        "outside" -> context.getString(R.string.inspector_dwell_outside)
        "unknown" -> context.getString(R.string.inspector_dwell_unknown)
        else -> null
    }
}

private fun parseDwellMillis(config: Map<String, String>): Long {
    val millis = firstConfig(config, "dwellMillis", "dwellMs").toLongOrNull()
    if (millis != null) return millis.coerceAtLeast(0L)
    val seconds = firstConfig(config, "dwellSeconds", "dwellSec").toLongOrNull()
    return seconds?.coerceAtLeast(0L)?.times(1_000L) ?: 0L
}

private fun firstConfig(config: Map<String, String>, vararg keys: String): String =
    keys.firstNotNullOfOrNull { config[it]?.trim()?.takeIf(String::isNotBlank) }.orEmpty()

private fun formatDuration(context: Context, ms: Long): String {
    val seconds = (ms / 1000L).coerceAtLeast(0L)
    return when {
        seconds < 60 -> context.getString(R.string.inspector_duration_seconds, seconds)
        seconds < 3_600 -> context.getString(R.string.inspector_duration_minutes_seconds, seconds / 60, seconds % 60)
        else -> context.getString(R.string.inspector_duration_hours_minutes, seconds / 3_600, (seconds % 3_600) / 60)
    }
}
