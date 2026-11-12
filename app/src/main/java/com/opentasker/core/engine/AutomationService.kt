package com.opentasker.core.engine

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.os.Trace
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.room.withTransaction
import com.opentasker.app.MainActivity
import com.opentasker.app.OpenTaskerApp_NoHilt
import com.opentasker.app.R
import com.opentasker.automation.app.AppUsageMonitor
import com.opentasker.automation.network.ConnectivityMonitor
import com.opentasker.automation.network.WiFiNetworkMonitor
import com.opentasker.automation.sensor.ShakeDetector
import com.opentasker.automation.scheduler.TimeEventScheduler
import com.opentasker.core.actions.NotificationActionReceiver
import com.opentasker.core.external.AutomationTargetContract
import com.opentasker.core.external.ExternalExecutionState
import com.opentasker.core.external.ExternalExecutions
import com.opentasker.core.actions.NotificationTaskBindings
import com.opentasker.core.actions.NotificationTaskCandidate
import com.opentasker.core.actions.NotificationTaskReference
import com.opentasker.core.actions.NotificationTaskResolution
import com.opentasker.core.logging.AppLogger
import com.opentasker.core.storage.recoveryMessage
import com.opentasker.core.contexts.BluetoothContextEvents
import com.opentasker.core.contexts.BootContextEvents
import com.opentasker.core.contexts.BluetoothNearTargets
import com.opentasker.core.contexts.CallContextEvents
import com.opentasker.core.health.HealthConnectPollWorker
import com.opentasker.core.health.HealthPolling
import com.opentasker.core.contexts.bluetoothNearTargets
import com.opentasker.core.contexts.systemAppReceiverFlags
import com.opentasker.core.contexts.CameraMicContextEvents
import com.opentasker.core.contexts.ContextEvent
import com.opentasker.core.contexts.BroadcastContextEvents
import com.opentasker.core.contexts.declaredBroadcastActions
import com.opentasker.core.contexts.declaresIncomingCall
import com.opentasker.core.contexts.PackageContextEvents
import com.opentasker.core.contexts.PluginConditionSubscriptions
import com.opentasker.core.contexts.pluginConditionSubscriptions
import com.opentasker.core.contexts.ScreenRecordingContextEvents
import com.opentasker.core.contexts.TimeContextEvents
import com.opentasker.core.contexts.UsbDeviceContextEvents
import com.opentasker.core.diagnostics.EngineHealthReader
import com.opentasker.core.engine.variables.TaskerBuiltIns
import com.opentasker.core.model.AutomationMode
import com.opentasker.core.model.Profile
import com.opentasker.core.model.ProfileLifecyclePolicy
import com.opentasker.core.model.ProfileLifetime
import com.opentasker.core.model.ProfileLifecycleStrings
import com.opentasker.core.model.ProfileOverflowPolicy
import com.opentasker.core.model.Task
import com.opentasker.core.model.enterTaskIdOrNull
import com.opentasker.core.platform.AudioForegroundServiceEligibility
import com.opentasker.core.platform.PromotedOngoingNotificationSupport
import com.opentasker.core.platform.ServiceAudioEligibility
import com.opentasker.core.storage.RunLogRetentionSettings
import com.opentasker.core.storage.applyRetention
import com.opentasker.core.storage.minimumTimestamp
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import com.opentasker.core.storage.ProfileEntity
import com.opentasker.core.storage.toEntity
import java.util.ArrayDeque
import java.util.Collections

/**
 * Foreground service that hosts the trigger engine.
 *
 * Subscribes to context sources, evaluates active profiles, and dispatches tasks.
 * A foreground service is required on modern Android (Doze/App Standby) for the
 * automation engine to evaluate triggers reliably.
 */
class AutomationService : Service() {
    private val job = Job()
    // Engine orchestration (context matching, dispatch) runs off the main thread. Room suspend DAOs
    // dispatch to Room's own executor, and task execution hops to Dispatchers.IO inside
    // executeAndLogTask, so no automation work blocks the UI thread.
    /**
     * Without a handler an exception from any engine coroutine reaches the default handler and
     * kills the process. The realistic trigger is the database not being ready inside its 30 s
     * deadline on a cold boot that is applying a staged restore, which the watchdog and boot alarm
     * would then retry into a crash loop. The engine is restarted by those same mechanisms, so
     * logging and letting this launch fail is the recoverable behaviour.
     */
    private val engineExceptionHandler = CoroutineExceptionHandler { _, error ->
        AppLogger.error(TAG, "Engine coroutine failed", error)
    }
    private val scope = CoroutineScope(Dispatchers.Default + job + engineExceptionHandler)
    private val db by lazy { OpenTaskerApp_NoHilt.db }
    private val timeEventScheduler by lazy { TimeEventScheduler(this) }
    private val wifiNetworkMonitor by lazy { WiFiNetworkMonitor(this) }
    private val connectivityMonitor by lazy { ConnectivityMonitor(this) }
    private val appUsageMonitor by lazy { AppUsageMonitor(this) }
    private val shakeDetector by lazy { ShakeDetector(this) }
    private val runLogRetentionSettings by lazy { RunLogRetentionSettings(this) }
    private val engineHeartbeatStore by lazy { EngineHeartbeatStore(this) }
    private val contextMonitorLifecycle by lazy {
        ContextMonitorLifecycle(
            mapOf(
                ContextMonitor.WIFI to ContextMonitorHandle(
                    start = { wifiNetworkMonitor.start() },
                    stop = { wifiNetworkMonitor.stop() },
                ),
                ContextMonitor.CONNECTIVITY to ContextMonitorHandle(
                    start = { connectivityMonitor.start() },
                    stop = { connectivityMonitor.stop() },
                ),
                ContextMonitor.APP_USAGE to ContextMonitorHandle(
                    start = { appUsageMonitor.start(scope) },
                    stop = { appUsageMonitor.stop() },
                ),
                ContextMonitor.SHAKE to ContextMonitorHandle(
                    start = { shakeDetector.start() },
                    stop = { shakeDetector.stop() },
                ),
                ContextMonitor.CAMERA_MIC to ContextMonitorHandle(
                    start = { CameraMicContextEvents.start(this) },
                    stop = { CameraMicContextEvents.stop(this) },
                ),
                ContextMonitor.PACKAGE_EVENTS to ContextMonitorHandle(
                    start = {
                        ContextCompat.registerReceiver(
                            this,
                            PackageContextEvents.receiver,
                            PackageContextEvents.intentFilter(),
                            ContextCompat.RECEIVER_NOT_EXPORTED,
                        )
                        true
                    },
                    stop = { unregisterReceiver(PackageContextEvents.receiver) },
                ),
                ContextMonitor.BLUETOOTH_EVENTS to ContextMonitorHandle(
                    start = {
                        // Anything that disconnected while the receiver was unregistered was never
                        // seen, so start from nothing rather than from a stale set.
                        BluetoothContextEvents.resetConnections()
                        ContextCompat.registerReceiver(
                            this,
                            BluetoothContextEvents.receiver,
                            BluetoothContextEvents.intentFilter(),
                            systemAppReceiverFlags(),
                        )
                        true
                    },
                    stop = { unregisterReceiver(BluetoothContextEvents.receiver) },
                ),
                ContextMonitor.USB_EVENTS to ContextMonitorHandle(
                    start = {
                        ContextCompat.registerReceiver(
                            this,
                            UsbDeviceContextEvents.receiver,
                            UsbDeviceContextEvents.intentFilter(),
                            ContextCompat.RECEIVER_NOT_EXPORTED,
                        )
                        true
                    },
                    stop = { unregisterReceiver(UsbDeviceContextEvents.receiver) },
                ),
                ContextMonitor.COMPANION_EVENTS to ContextMonitorHandle(
                    start = { true },
                    stop = {},
                ),
                ContextMonitor.SCREEN_RECORDING to ContextMonitorHandle(
                    start = { ScreenRecordingContextEvents.start(this) },
                    stop = { ScreenRecordingContextEvents.stop(this) },
                ),
            ),
        )
    }
    
    private val cooldownStore by lazy { CooldownStore(this) }
    private val executionAdmission by lazy { ExecutionAdmissionController.persisted(this) }
    private val matchers = Collections.synchronizedMap(mutableMapOf<Long, ProfileMatcher>())
    private val pulseContinuities = Collections.synchronizedMap(mutableMapOf<Long, PulseEventContinuity>())
    private val matchedProfiles = Collections.synchronizedMap(mutableMapOf<Long, Profile>())
    private val dispatchedProfiles = Collections.synchronizedSet(mutableSetOf<Long>())
    /**
     * Of [dispatchedProfiles], the ones that went on with no enter task, guarded by its lock. A one-shot
     * profile among them is used up when it goes off, even if an edit gave it an enter task meanwhile.
     */
    private val dispatchedWithoutEnterTask = mutableSetOf<Long>()
    private val cooldowns = CooldownReservations(persist = { profileId, deadline -> cooldownStore.set(profileId, deadline) })
    private val matcherJobs = Collections.synchronizedMap(mutableMapOf<Long, Job>()) // Track jobs for cleanup
    /** The profiles as last loaded. A matcher a reload kept acts on the edited profile through this. */
    private val registeredProfiles = Collections.synchronizedMap(mutableMapOf<Long, Profile>())
    /**
     * Held while a profile going on or off is handled, and by a reload while it decides which
     * matchers to keep, so a matcher still running can't change a profile's state under it.
     */
    private val profileStateLock = Mutex()
    private val profileTaskSlots = ProfileTaskSlots()
    private val queuedProfileTasks = Collections.synchronizedMap(mutableMapOf<Long, ArrayDeque<QueuedProfileTask>>())
    private val activeTaskNames = Collections.synchronizedMap(mutableMapOf<Long, String>())
    private val nextActiveTaskToken = AtomicLong()
    private val profileReloadMutex = Mutex()
    @Volatile private var lastRunLogPruneAt = 0L
    // Mirrored into ServiceAudioEligibility so work that runs outside the service, the
    // temporary-state restore in particular, acts with the eligibility the service really holds.
    @Volatile private var audioForegroundServiceEligibility = AudioForegroundServiceEligibility.BACKGROUND_STARTED
        set(value) {
            field = value
            ServiceAudioEligibility.current = value
        }
    @Volatile private var engineLoaded = false
    private val engineStartTraceOpen = AtomicBoolean(false)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // Read by the engine-start macrobenchmark; ends when the first profile load finishes.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && engineStartTraceOpen.compareAndSet(false, true)) {
            Trace.beginAsyncSection(ENGINE_START_TRACE_SECTION, ENGINE_START_TRACE_COOKIE)
        }
        // The property's initial value bypasses its setter, so publish it once here.
        ServiceAudioEligibility.current = audioForegroundServiceEligibility
        // A recreated service starts a new causal attribution lifetime; do not connect a fresh
        // Android process callback to a profile execution from a prior service instance.
        ExecutionCausality.reset()
        // Read the previous heartbeat before recordAlive() replaces it, so Diagnostics can explain
        // an OEM kill or crash using ApplicationExitInfo on Android 11+.
        EngineHealthReader.captureStartupProcessExitCorrelation(this)
        startForegroundCompat()
        timeEventScheduler.scheduleNextMinute()
        engineHeartbeatStore.recordAlive()
        scope.launch {
            while (isActive) {
                engineHeartbeatStore.recordAlive()
                delay(ENGINE_HEARTBEAT_INTERVAL_MS)
            }
        }
        cooldowns.seed(cooldownStore.loadAll())
        ExecutionAdmissionRegistry.attach(executionAdmission)
        // Executions that were accepted or in flight when the process died can never finish; a
        // caller polling them would otherwise wait forever on a non-terminal state.
        ExternalExecutions.failInterrupted(this)
        scope.launch { pruneRunLogs(force = true) }
        observeProfileRegistry()
    }

    /**
     * Keeps the running engine in sync with the profiles table. Creating, editing, enabling,
     * disabling, or deleting a profile changes the reconcile signature and triggers a matcher and
     * plugin-subscription rebuild without a service restart. The initial snapshot is dropped because
     * [onStartCommand] performs the first load, so the initial snapshot is dropped and only
     * subsequent engine-relevant changes reconcile. Identical signatures are coalesced by
     * [distinctUntilChanged], and [reloadProfiles] is idempotent, so rapid edits are safe.
     *
     * In-flight task runs are intentionally left running — reconciling re-subscribes matchers but
     * does not cancel a task that is already executing.
     */
    private fun observeProfileRegistry() {
        scope.launch {
            db.profileDao().getAllAsFlow()
                .map { profileRegistrySignature(it) }
                .distinctUntilChanged()
                .drop(1)
                .collect {
                    AppLogger.info(TAG, "Profile registry changed; reconciling matchers")
                    reloadProfiles()
                }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.getBooleanExtra(EXTRA_STARTED_FROM_VISIBLE_UI, false) == true) {
            audioForegroundServiceEligibility = AudioForegroundServiceEligibility.WHILE_IN_USE
        }
        if (intent?.action == ACTION_RUN_NOTIFICATION_TASK) {
            // Run notification-button tasks inside the foreground service's own scope instead of a
            // BroadcastReceiver's ~10 s goAsync window, so long tasks (e.g. flow.wait up to 30 min)
            // complete and log reliably without the system killing the receiver mid-run.
            val taskId = intent.getLongExtra(NotificationActionReceiver.EXTRA_TASK_ID, -1L).takeIf { it > 0 }
            val legacyName = intent.getStringExtra(NotificationActionReceiver.EXTRA_TASK_NAME)
            val buttonLabel = intent.getStringExtra(NotificationActionReceiver.EXTRA_BUTTON_LABEL)
                ?: legacyName ?: "Task ${taskId ?: "unknown"}"
            val reference = taskId?.let { NotificationTaskReference.Id(it) }
                ?: legacyName?.let { NotificationTaskReference.LegacyName(it) }
            if (reference != null) {
                scope.launch {
                    ensureEngineLoaded()
                    runNotificationTask(reference, buttonLabel)
                }
            }
            return START_STICKY
        }
        if (intent?.action == ACTION_RUN_EXTERNAL_TASK) {
            // Same reason as notification-button runs: the external RUN_TASK broadcast cannot hold
            // its window open for a task that may wait minutes, so the receiver validates and
            // hands the run here, where the foreground service owns it to completion.
            val executionId = intent.getStringExtra(AutomationTargetContract.EXTRA_EXECUTION_ID)
            val taskId = intent.getLongExtra(AutomationTargetContract.EXTRA_TASK_ID, -1L).takeIf { it > 0 }
            val variables = externalVariables(intent)
            val runSource = AutomationTargetContract.runSourceLabel(
                intent.getStringExtra(AutomationTargetContract.EXTRA_RUN_SOURCE),
            )
            val producer = intent.getStringExtra(AutomationTargetContract.EXTRA_EXECUTION_PRODUCER)
                ?: ExecutionProducer.fromSource(runSource).wireValue
            val parentExecutionId = intent.getStringExtra(AutomationTargetContract.EXTRA_PARENT_EXECUTION_ID)
            if (executionId != null && taskId != null) {
                scope.launch {
                    ensureEngineLoaded()
                    runExternalTask(
                        executionId = executionId,
                        taskId = taskId,
                        variables = variables,
                        runSource = runSource,
                        producer = producer,
                        parentExecutionId = parentExecutionId,
                    )
                }
            }
            return START_STICKY
        }
        val bootCompletedTrigger = intent?.action == ACTION_BOOT_COMPLETED_TRIGGER
        val timeTickTrigger = intent?.action == ACTION_TIME_TICK_TRIGGER
        timeEventScheduler.scheduleNextMinute()
        engineHeartbeatStore.recordAlive()
        scope.launch {
            if (!timeTickTrigger || !engineLoaded) reloadProfiles()
            val replayedDirectBootTick = DirectBootTriggerStore.consumePendingTimeTick(this@AutomationService)
            if (bootCompletedTrigger) {
                BootContextEvents.publishBootCompleted()
            }
            if (timeTickTrigger || replayedDirectBootTick) {
                TimeContextEvents.publish()
            }
        }
        return START_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        AppLogger.warn(TAG, "Foreground service timeout (startId=$startId, fgsType=$fgsType); stopping cleanly")
        engineHeartbeatStore.recordStopped()
        timeEventScheduler.scheduleRecovery()
        scope.launch {
            val entry = com.opentasker.core.storage.RunLogEntity(
                taskId = 0,
                taskName = "AutomationService",
                timestamp = System.currentTimeMillis(),
                durationMs = 0,
                success = false,
                message = "Service stopped by system foreground-service timeout",
                source = "system",
                sourceLabel = "FGS timeout",
            )
            runCatching { db.runLogDao().insert(entry) }
        }
        stopSelf(startId)
    }

    override fun onDestroy() {
        ServiceAudioEligibility.current = AudioForegroundServiceEligibility.NONE
        val matcherJobSnapshot = matcherJobs.values.toList()
        val taskJobSnapshot = profileTaskSlots.snapshot()
        matcherJobSnapshot.forEach { it.cancel() }
        taskJobSnapshot.forEach { it.cancel() }
        matcherJobs.clear()
        matchers.clear()
        registeredProfiles.clear()
        matchedProfiles.clear()
        TaskerBuiltIns.watchProfiles(emptyMap())
        AutomationLiveConditionState.clear()
        synchronized(dispatchedProfiles) {
            dispatchedProfiles.clear()
            dispatchedWithoutEnterTask.clear()
        }
        cooldowns.clear()
        ExecutionAdmissionRegistry.detach(executionAdmission)
        profileTaskSlots.clear()
        queuedProfileTasks.clear()
        activeTaskNames.clear()
        pulseContinuities.clear()
        engineHeartbeatStore.recordStopped()
        PluginConditionSubscriptions.clear()
        job.cancel()
        logContextMonitorTransition(contextMonitorLifecycle.stopAll())
        BroadcastContextEvents.sync(this, emptySet())
        CallContextEvents.sync(this, false)
        HealthConnectPollWorker.sync(this, null)
        super.onDestroy()
    }

    /**
     * Loads the matchers if this start did not already.
     *
     * A notification button and an external RUN_TASK both return before the general path that
     * reloads profiles, which is right for the run itself but leaves a freshly started process
     * matching nothing. After an OEM kill or a swipe away, tapping a notification button brought
     * the service up, recorded a healthy heartbeat and showed "running" while no trigger worked,
     * until the next minute tick happened to reload it: up to a minute with exact alarms, and a
     * Doze window without them. The watchdog saw a current heartbeat and had nothing to report.
     */
    private suspend fun ensureEngineLoaded() {
        if (engineLoaded) return
        try {
            reloadProfiles()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            // The run still happens. This start exists to run one task, that task resolves itself
            // from the database, and it used to run without touching the matchers at all. Letting
            // a reload failure reach the scope's handler would silently drop a run that previously
            // succeeded, which is a worse trade than starting with stale matchers.
            AppLogger.error(TAG, "Could not load profiles before the requested run", error)
        }
    }

    /**
     * Stops the matchers [planMatcherReload] lets go, starts [profiles]' new ones and runs the held
     * back profiles it released. Call with [profileStateLock] held.
     */
    private suspend fun startMatchers(profiles: List<Profile>, activeIds: Set<Long>) {
        val running = synchronized(registeredProfiles) {
            registeredProfiles.filterKeys { matcherJobs[it]?.isActive == true }
        }
        // Same lock order as onProfileDeactivated.
        val reload = synchronized(matchedProfiles) {
            synchronized(dispatchedProfiles) {
                planMatcherReload(running, profiles, matchedProfiles, dispatchedProfiles)
                    .also { dispatchedWithoutEnterTask.retainAll(dispatchedProfiles) }
            }
        }
        val oldJobs = synchronized(matcherJobs) {
            matcherJobs.filterKeys { it !in reload.kept }.values.toList().also { matcherJobs.keys.retainAll(reload.kept) }
        }
        matchers.keys.retainAll(reload.kept)
        oldJobs.forEach { it.cancel() }
        oldJobs.joinAll()
        synchronized(registeredProfiles) {
            registeredProfiles.clear()
            profiles.associateByTo(registeredProfiles) { it.id }
        }
        TaskerBuiltIns.watchProfiles(profiles.associate { it.id to it.name })
        AutomationLiveConditionState.retainProfiles(activeIds)
        synchronized(pulseContinuities) {
            pulseContinuities.keys.removeAll { it !in activeIds }
        }
        // Prune against profiles that still exist, not the enabled ones: activeIds comes from
        // getAllEnabled(), so disabling a profile mid-cooldown deleted its persisted deadline while
        // the in-memory reservation survived. Whether the cooldown still applied then depended on
        // whether the service happened to restart before the profile was switched back on.
        cooldownStore.pruneDeleted(db.profileDao().getAllIds().toSet())
        synchronized(queuedProfileTasks) {
            // Slots are keyed +id for enter tasks and -id for exit tasks; prune both.
            queuedProfileTasks.keys.removeAll { kotlin.math.abs(it) !in activeIds }
        }
        registerPluginSubscriptions(profiles)
        for (domain in profiles) {
            if (domain.id in reload.kept) continue
            val pulseContinuity = pulseContinuities.getOrPut(domain.id) { PulseEventContinuity() }
            // A profile that was on stays on: a reload doesn't run its enter task again, and runs
            // its exit task when the edit's fresh readings no longer match.
            val matcher = ProfileMatcher(this, domain, pulseContinuity, initiallyActive = domain.id in reload.stillOn)

            // A later reload can keep this matcher for an edited profile.
            fun current() = registeredProfiles[domain.id] ?: domain
            val matcherJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    matcher.stateChanges().collect { change ->
                        try {
                            profileStateLock.withLock {
                                val profile = current()
                                when (change) {
                                    is ProfileStateChange.Activated -> onProfileActivated(profile, change.event)
                                    is ProfileStateChange.Deactivated -> onProfileDeactivated(profile)
                                }
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            AppLogger.error(TAG, "Failed handling state change for ${current().name}", e)
                            recordMatcherError("Failed handling state change for ${current().name}", e)
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLogger.error(TAG, "Profile matcher stopped for ${current().name}", e)
                    recordMatcherError("Profile matcher stopped for ${current().name}", e)
                }
            }

            matcherJobs[domain.id] = matcherJob
            matchers[domain.id] = matcher
        }
        // Weighed only once every matcher is in place, as onProfileDeactivated weighs the ones it frees.
        reload.released.forEach { onProfileActivated(it, null) }
    }

    private suspend fun reloadProfiles() = profileReloadMutex.withLock {
        val profileEntities = db.profileDao().getAllEnabled()
        val nowMs = System.currentTimeMillis()
        val profiles = profileEntities.mapNotNull { entity ->
            val decoded = entity.toDomainDecodeResult()
            decoded.issue?.let { issue ->
                AppLogger.error(
                    TAG,
                    "Profile ${entity.id} is corrupt and was not registered: ${issue.message}",
                )
                return@mapNotNull null
            }
            val profile = decoded.value
            val suppression = ProfileLifecyclePolicy.suppressionReason(
                profile,
                nowMs,
                ProfileLifecycleStrings.from(resources),
            )
            if (suppression != null) {
                AppLogger.info(TAG, "Profile ${profile.id} is not registered: $suppression")
                db.profileDao().upsert(profile.copy(enabled = false).toEntity())
                null
            } else {
                profile
            }
        }
        val activeIds = profiles.map { it.id }.toSet()
        profileStateLock.withLock { startMatchers(profiles, activeIds) }
        val subscriptionsReady = withTimeoutOrNull(MONITOR_SUBSCRIPTION_TIMEOUT_MS) {
            matchers.values.toList().forEach { it.awaitMonitorSubscriptions() }
            true
        } == true
        if (!subscriptionsReady) {
            AppLogger.warn(TAG, "Context monitor subscription barrier timed out; starting required sources in degraded mode")
        }
        // Before reconcile, so a monitor this starts has something to scan for on its first pass.
        BluetoothNearTargets.sync(bluetoothNearTargets(profiles.flatMap { it.contexts }))
        logContextMonitorTransition(contextMonitorLifecycle.reconcile(profiles))
        // Not a ContextMonitor: the broadcast receiver's filter is per-profile configuration, so
        // it has to follow the declared action set, which changes without any monitor starting or
        // stopping. sync() is a no-op when the set is unchanged and unregisters on an empty set.
        BroadcastContextEvents.sync(this, declaredBroadcastActions(profiles.flatMap { it.contexts }))
        CallContextEvents.sync(this, declaresIncomingCall(profiles.flatMap { it.contexts }))
        // Health Connect has no broadcasts to listen for, so its readings come from scheduled reads.
        HealthConnectPollWorker.sync(this, HealthPolling.plan(profiles.flatMap { it.contexts }))
        engineLoaded = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && engineStartTraceOpen.compareAndSet(true, false)) {
            Trace.endAsyncSection(ENGINE_START_TRACE_SECTION, ENGINE_START_TRACE_COOKIE)
        }
    }

    private fun logContextMonitorTransition(transition: ContextMonitorTransition) {
        val counts = contextMonitorLifecycle.currentReferenceCounts()
        transition.started.forEach { monitor ->
            AppLogger.info(TAG, "Context monitor $monitor started for ${counts.getOrDefault(monitor, 0)} enabled profile(s)")
        }
        transition.stopped.forEach { monitor ->
            AppLogger.info(TAG, "Context monitor $monitor stopped after its final enabled profile was removed")
        }
        transition.failedToStart.forEach { monitor ->
            AppLogger.warn(TAG, "Context monitor $monitor could not start; it will retry on the next profile reconcile")
        }
        transition.failedToStop.forEach { monitor ->
            AppLogger.warn(TAG, "Context monitor $monitor could not stop cleanly")
        }
    }

    private fun registerPluginSubscriptions(profiles: List<Profile>) {
        PluginConditionSubscriptions.replaceAll(pluginConditionSubscriptions(profiles))
    }

    private suspend fun onProfileActivated(profile: com.opentasker.core.model.Profile, event: ContextEvent?) {
        val pulseProfile = profile.contexts.any { it.type == com.opentasker.core.model.ContextType.EVENT }
        val suppression = ProfileLifecyclePolicy.suppressionReason(
            profile,
            System.currentTimeMillis(),
            ProfileLifecycleStrings.from(resources),
        )
        if (suppression != null) {
            AutomationLiveConditionState.updateProfile(profile.id, false)
            AppLogger.info(TAG, "Profile ${profile.id} activation suppressed: $suppression")
            if (profile.enabled) db.profileDao().upsert(profile.copy(enabled = false).toEntity())
            return
        }
        AutomationLiveConditionState.updateProfile(profile.id, active = !pulseProfile)
        val enterTaskId = profile.enterTaskIdOrNull
        // A profile with only an exit task still goes on, with nothing to run yet: it takes part in
        // priority arbitration like any other and runs that exit task when its contexts stop
        // matching. An event profile never goes off, so without an enter task it has nothing to do.
        if (enterTaskId == null && (pulseProfile || profile.exitTaskId == null)) return

        val enterTask = if (enterTaskId == null) {
            null
        } else {
            val task = db.taskDao().getById(enterTaskId)
            if (task == null) {
                AppLogger.warn(TAG, "Enter task $enterTaskId not found for profile ${profile.name}")
                return
            }
            val decoded = task.toDomainDecodeResult()
            val decodeIssue = decoded.issue
            if (decodeIssue != null) {
                AppLogger.error(TAG, "Enter task $enterTaskId is corrupt for profile ${profile.name}: ${decodeIssue.message}")
                logProfileSkippedRun(profile, decoded.value, "Enter task data is corrupt (${decodeIssue.fieldName}); recover it before it can run.")
                return
            }
            decoded.value
        }

        // Read the candidate set once under the lock: the matcher runs a coroutine per profile, so
        // iterating the synchronized map outside it can throw ConcurrentModificationException.
        val suppressedBy = synchronized(matchedProfiles) {
            if (!pulseProfile) matchedProfiles[profile.id] = profile
            ProfileLifecyclePolicy.suppressionByPriority(
                profile,
                matchedProfiles.values + profile,
                ProfileLifecycleStrings.from(resources),
            )
        }
        if (suppressedBy != null) {
            if (enterTask != null) {
                logProfileSkippedRun(profile, enterTask, suppressedBy)
            } else {
                AppLogger.info(TAG, "Profile ${profile.id} held back: $suppressedBy")
            }
            return
        }
        // A one-shot profile with only an exit task is used up when that exit task runs.
        if (enterTask != null && profile.lifetime == ProfileLifetime.ONCE && !consumeOneShotProfile(profile)) {
            if (!pulseProfile) synchronized(matchedProfiles) { matchedProfiles.remove(profile.id) }
            return
        }
        if (!pulseProfile) {
            synchronized(dispatchedProfiles) {
                dispatchedProfiles += profile.id
                if (enterTask == null) dispatchedWithoutEnterTask += profile.id else dispatchedWithoutEnterTask -= profile.id
            }
        }
        if (enterTask != null) {
            dispatchTask(profile, enterTask, isExit = false, initialVariables = EventTaskVariables.from(event))
        }
    }

    private suspend fun onProfileDeactivated(profile: com.opentasker.core.model.Profile) {
        AutomationLiveConditionState.updateProfile(profile.id, false)
        val wasDispatched: Boolean
        val wentOnWithoutEnterTask: Boolean
        // Profiles this one was outranking; they become eligible now that it has deactivated.
        val released: List<Profile>
        synchronized(matchedProfiles) {
            val before = matchedProfiles.values.toList()
            matchedProfiles.remove(profile.id)
            synchronized(dispatchedProfiles) {
                wentOnWithoutEnterTask = dispatchedWithoutEnterTask.remove(profile.id)
                wasDispatched = dispatchedProfiles.remove(profile.id)
            }
            val remaining = matchedProfiles.values.toList()
            released = remaining.filter { candidate ->
                ProfileLifecyclePolicy.suppressor(candidate, before) != null &&
                    ProfileLifecyclePolicy.suppressor(candidate, remaining) == null
            }
        }
        if (!wasDispatched) {
            released.forEach { onProfileActivated(it, null) }
            return
        }
        val exitTaskId = profile.exitTaskId
        if (exitTaskId == null || exitTaskId <= 0) {
            released.forEach { onProfileActivated(it, null) }
            return
        }
        val task = db.taskDao().getById(exitTaskId)
        if (task == null) {
            AppLogger.warn(TAG, "Exit task $exitTaskId not found for profile ${profile.name}")
            released.forEach { onProfileActivated(it, null) }
            return
        }
        val decoded = task.toDomainDecodeResult()
        val decodeIssue = decoded.issue
        if (decodeIssue != null) {
            AppLogger.error(TAG, "Exit task $exitTaskId is corrupt for profile ${profile.name}: ${decodeIssue.message}")
            logProfileSkippedRun(profile, decoded.value, "Exit task data is corrupt (${decodeIssue.fieldName}); recover it before it can run.")
            released.forEach { onProfileActivated(it, null) }
            return
        }
        if (wentOnWithoutEnterTask && profile.lifetime == ProfileLifetime.ONCE && !consumeOneShotProfile(profile)) {
            released.forEach { onProfileActivated(it, null) }
            return
        }
        dispatchTask(profile, decoded.value, isExit = true)
        released.forEach { onProfileActivated(it, null) }
    }

    private suspend fun consumeOneShotProfile(profile: Profile): Boolean = db.withTransaction {
        val entity = db.profileDao().getById(profile.id) ?: return@withTransaction false
        val decoded = entity.toDomainDecodeResult()
        val decodeIssue = decoded.issue
        if (decodeIssue != null) {
            AppLogger.error(TAG, "One-shot profile ${profile.id} is corrupt: ${decodeIssue.message}")
            return@withTransaction false
        }
        val current = decoded.value
        if (!current.enabled || current.lifetime != ProfileLifetime.ONCE || current.lifetimeConsumed) {
            return@withTransaction false
        }
        db.profileDao().upsert(current.copy(enabled = false, lifetimeConsumed = true).toEntity())
        true
    }

    /**
     * Enter and exit tasks are distinct execution units: they use separate job slots (so a
     * still-running enter task never suppresses or gets cancelled by the exit task) and only
     * enter dispatch consumes the profile cooldown — cooldown gates re-triggering, not the
     * cleanup work users rely on when a profile deactivates.
     */
    private fun dispatchTask(
        profile: Profile,
        task: Task,
        isExit: Boolean,
        initialVariables: Map<String, String> = emptyMap(),
    ) {
        val causal = ExecutionCausality.nextForProfile(profile.id, profile.name, isExit = isExit)
        if (!causal.allowed) {
            val reason = requireNotNull(causal.blockedReason)
            AppLogger.warn(TAG, reason)
            ExecutionCausality.recordBlocked(causal)
            logProfileCausalLoop(profile, task, causal, reason)
            return
        }
        val slot = taskSlotKey(profile.id, isExit)

        // Every mode decides and stores under the slot lock. The policy reads whether the slot is
        // busy and the launch writes the job into it; if those halves interleave, SINGLE starts
        // twice and RESTART leaves its first job running but untracked. QUEUED additionally nests
        // the queue lock so a task can never be appended to a queue whose consumer has already
        // decided to exit. Lock order is always slots -> queue; the queue consumer takes only the
        // queue lock, so there is no cycle.
        //
        // Logging and run-log writes deliberately happen after the lock is released.
        var cooldownSkipRemainingMs: Long? = null
        val plan = profileTaskSlots.exclusively { slots ->
            val decision = if (profile.automationMode == AutomationMode.QUEUED) {
                synchronized(queuedProfileTasks) {
                    val queuedDecision = TaskDispatchPolicy.plan(
                        mode = profile.automationMode,
                        isExit = isExit,
                        slotActive = slots.isActive(slot),
                        queuedCount = queuedProfileTasks[slot]?.size ?: 0,
                        queueCap = MAX_QUEUED_TASKS,
                    )
                    if (queuedDecision.step == DispatchStep.ENQUEUE) {
                        queuedProfileTasks.getOrPut(slot) { ArrayDeque() }
                            .add(QueuedProfileTask(task, initialVariables, causal))
                    }
                    queuedDecision
                }
            } else {
                TaskDispatchPolicy.plan(
                    mode = profile.automationMode,
                    isExit = isExit,
                    slotActive = slots.isActive(slot),
                )
            }

            if (!decision.startsRun) {
                return@exclusively decision
            }

            if (decision.reservesCooldown) {
                val reservation = reserveCooldown(profile.id, profile.cooldownSec)
                if (!reservation.accepted) {
                    cooldownSkipRemainingMs = reservation.remainingMs
                    return@exclusively decision
                }
            }

            when (decision.step) {
                DispatchStep.START ->
                    slots.store(slot, launchTrackedTask(profile, slot, task, initialVariables, causal))
                DispatchStep.RESTART -> {
                    slots.cancel(slot)
                    slots.store(slot, launchTrackedTask(profile, slot, task, initialVariables, causal))
                }
                DispatchStep.START_QUEUE -> slots.store(
                    slot,
                    launchQueuedTasks(profile, slot, QueuedProfileTask(task, initialVariables, causal)),
                )
                DispatchStep.LAUNCH_PARALLEL -> scope.launch { runTask(task, profile, initialVariables, causal) }
                else -> Unit
            }
            decision
        }

        cooldownSkipRemainingMs?.let { remainingMs ->
            logCooldownSkip(profile, task, remainingMs)
            return
        }

        when (plan.step) {
            DispatchStep.SKIP_ALREADY_RUNNING -> {
                AppLogger.info(TAG, "Profile ${profile.id} already running; SINGLE mode skipped retrigger")
                logProfileSkippedRun(profile, task, "Profile is already running in SINGLE mode.")
            }
            DispatchStep.SKIP_QUEUE_FULL -> {
                AppLogger.warn(TAG, "Profile ${profile.id} queue full ($MAX_QUEUED_TASKS), dropping retrigger")
                logProfileSkippedRun(profile, task, "Task queue is full ($MAX_QUEUED_TASKS pending).")
            }
            DispatchStep.ENQUEUE -> AppLogger.info(TAG, "Profile ${profile.id} queued retrigger")
            else -> Unit
        }
    }

    /** Profile ids are positive Room autogenerated keys, so -id is a collision-free exit slot. */
    private fun taskSlotKey(profileId: Long, isExit: Boolean): Long = if (isExit) -profileId else profileId

    private fun launchTrackedTask(
        profile: Profile,
        slot: Long,
        task: Task,
        initialVariables: Map<String, String>,
        causal: CausalExecutionDecision,
    ): Job =
        scope.launch(start = CoroutineStart.DEFAULT) {
            val thisJob = currentCoroutineContext()[Job]
            try {
                runTask(task, profile, initialVariables, causal)
            } finally {
                profileTaskSlots.releaseIfCurrent(slot, thisJob)
            }
        }

    private fun launchQueuedTasks(profile: Profile, slot: Long, firstTask: QueuedProfileTask): Job =
        scope.launch(start = CoroutineStart.DEFAULT) {
            val thisJob = currentCoroutineContext()[Job]
            var nextTask: QueuedProfileTask? = firstTask
            try {
                while (isActive && nextTask != null) {
                    val queuedTask = requireNotNull(nextTask)
                    runTask(queuedTask.task, profile, queuedTask.initialVariables, queuedTask.causal)
                    // Slots outside, queue inside — the same order the producer uses. Taking them
                    // the other way round here would be a lock-order inversion against dispatch.
                    nextTask = profileTaskSlots.exclusively { slots ->
                        synchronized(queuedProfileTasks) {
                            val polled = queuedProfileTasks[slot]?.poll()
                            if (polled == null) {
                                // Deregister inside the queue lock so the producer either sees an
                                // active consumer (and enqueues into a queue that will be drained)
                                // or no consumer at all (and starts a fresh one) — never a consumer
                                // that has already decided to exit.
                                queuedProfileTasks.remove(slot)
                                slots.releaseIfCurrent(slot, thisJob)
                            } else if (queuedProfileTasks[slot]?.isEmpty() == true) {
                                queuedProfileTasks.remove(slot)
                            }
                            polled
                        }
                    }
                }
            } finally {
                profileTaskSlots.exclusively { slots ->
                    synchronized(queuedProfileTasks) {
                        slots.releaseIfCurrent(slot, thisJob)
                    }
                }
            }
        }

    private suspend fun runTask(
        task: Task,
        profile: Profile,
        initialVariables: Map<String, String> = emptyMap(),
        causal: CausalExecutionDecision,
    ) {
        val source = "Profile: ${profile.name}"
        val result = withTaskPresence(task) {
            executeAndLogTask(
                appContext = this,
                db = db,
                task = task,
                source = source,
                metadata = profileRunMetadata(profile),
                initialVariables = initialVariables,
                audioForegroundService = audioForegroundServiceEligibility,
                admissionController = executionAdmission,
                profileId = profile.id,
                profileLimits = profile.toExecutionAdmissionProfileLimits(),
                overflowPolicy = profile.overflowPolicy,
                profileName = profile.name,
                profileFallbackTaskId = profile.fallbackTaskId,
                execution = ExecutionEnvelope.create(
                    task = task,
                    source = source,
                    profileId = profile.id,
                    parentExecutionId = causal.parentExecutionId,
                    causalDepth = causal.depth,
                    causalProfileChain = causal.profileChain,
                ),
            )
        }
        if (result.logInserted) {
            pruneRunLogs(force = false)
        }
    }

    private data class QueuedProfileTask(
        val task: Task,
        val initialVariables: Map<String, String>,
        val causal: CausalExecutionDecision,
    )

    /** Variable extras a validated external request forwarded, already name-checked and capped. */
    private fun externalVariables(intent: Intent): Map<String, String> {
        val extras = intent.extras ?: return emptyMap()
        return extras.keySet()
            .asSequence()
            .filter { it.startsWith(AutomationTargetContract.VARIABLE_EXTRA_PREFIX) }
            .mapNotNull { key ->
                val name = key.removePrefix(AutomationTargetContract.VARIABLE_EXTRA_PREFIX)
                if (!AutomationTargetContract.isValidVariableName(name)) return@mapNotNull null
                extras.getString(key)?.let { name to it }
            }
            .toMap()
    }

    private suspend fun runExternalTask(
        executionId: String,
        taskId: Long,
        variables: Map<String, String>,
        runSource: String,
        producer: String,
        parentExecutionId: String?,
    ) {
        val storedExecution = ExternalExecutions.get(this, executionId)
        val envelopeTask = Task(
            id = taskId,
            name = storedExecution?.taskName ?: "Task $taskId",
        )
        val execution = ExecutionEnvelope(
            executionId = executionId,
            producer = ExecutionProducer.fromWireValue(producer),
            taskId = taskId,
            taskName = envelopeTask.name,
            source = runSource,
            parentExecutionId = parentExecutionId ?: storedExecution?.parentExecutionId,
            createdAtMs = storedExecution?.acceptedAtMs ?: System.currentTimeMillis(),
        )
        fun fail(reason: String) {
            AppLogger.warn(TAG, "External execution $executionId failed: $reason")
            ExternalExecutions.update(this, executionId, ExternalExecutionState.FAILED, error = reason)
        }

        try {
            val entity = db.taskDao().getById(taskId)
            if (entity == null) {
                val reason = "Task $taskId no longer exists."
                logSkippedRun(
                    db = db,
                    task = envelopeTask,
                    source = execution.source,
                    reason = reason,
                    execution = execution,
                    terminalReason = ExecutionTerminalReason(
                        ExecutionTerminalReasonCode.TASK_NOT_FOUND,
                        reason,
                    ),
                )
                return fail(reason)
            }
            val decoded = entity.toDomainDecodeResult()
            decoded.issue?.let { issue ->
                val reason = issue.recoveryMessage()
                logSkippedRun(
                    db = db,
                    task = decoded.value,
                    source = execution.source,
                    reason = reason,
                    execution = execution,
                    terminalReason = ExecutionTerminalReason(
                        ExecutionTerminalReasonCode.TASK_CORRUPT,
                        reason,
                    ),
                )
                return fail(reason)
            }
            ExternalExecutions.update(this, executionId, ExternalExecutionState.RUNNING)
            val result = withTaskPresence(decoded.value) {
                executeAndLogTask(
                    appContext = this,
                    db = db,
                    task = decoded.value,
                    source = execution.source,
                    metadata = listOf("Variables: ${variables.size} provided"),
                    initialVariables = variables,
                    audioForegroundService = audioForegroundServiceEligibility,
                    logTag = TAG,
                    admissionController = executionAdmission,
                    execution = execution.copy(taskName = decoded.value.name),
                )
            }
            ExternalExecutions.update(
                context = this,
                executionId = executionId,
                state = when {
                    result.held -> ExternalExecutionState.HELD
                    result.report.success -> ExternalExecutionState.SUCCEEDED
                    else -> ExternalExecutionState.FAILED
                },
                durationMs = result.report.durationMs,
                error = when {
                    result.report.success -> null
                    result.skippedReason != null -> result.skippedReason
                    else -> "Task reported a failure; see the run log."
                },
            )
        } catch (e: CancellationException) {
            ExternalExecutions.update(this, executionId, ExternalExecutionState.FAILED, error = "The run was cancelled.")
            throw e
        } catch (e: Exception) {
            fail(e.message ?: "The run threw an unexpected error.")
        }
    }

    private suspend fun runNotificationTask(reference: NotificationTaskReference, buttonLabel: String) {
        try {
            val entities = when (reference) {
                is NotificationTaskReference.Id -> listOfNotNull(db.taskDao().getById(reference.taskId))
                is NotificationTaskReference.LegacyName -> db.taskDao().getAll()
                is NotificationTaskReference.Invalid -> emptyList()
            }
            val resolution = NotificationTaskBindings.resolve(
                reference = reference,
                candidates = entities.map { NotificationTaskCandidate(it.id, it.name) },
            )
            if (resolution !is NotificationTaskResolution.Bound) {
                AppLogger.warn(
                    TAG,
                    "Notification button '$buttonLabel' did not run: ${NotificationTaskBindings.failureMessage(resolution)}",
                )
                return
            }
            val entity = entities.single { it.id == resolution.task.id }
            val decoded = entity.toDomainDecodeResult()
            val issue = decoded.issue
            if (issue != null) {
                val reason = issue.recoveryMessage()
                AppLogger.error(TAG, "Notification button '$buttonLabel' blocked: $reason")
                logSkippedRun(
                    db = db,
                    task = decoded.value,
                    source = NotificationActionReceiver.SOURCE,
                    reason = reason,
                    metadata = listOf("button=$buttonLabel"),
                    execution = ExecutionEnvelope.create(
                        task = decoded.value,
                        source = NotificationActionReceiver.SOURCE,
                    ),
                    terminalReason = ExecutionTerminalReason(
                        ExecutionTerminalReasonCode.TASK_CORRUPT,
                        reason,
                    ),
                )
                return
            }
            val task = decoded.value
            val result = withTaskPresence(task) {
                executeAndLogTask(
                    appContext = this,
                    db = db,
                    task = task,
                    source = NotificationActionReceiver.SOURCE,
                    metadata = listOf("button=$buttonLabel"),
                    audioForegroundService = audioForegroundServiceEligibility,
                    admissionController = executionAdmission,
                    execution = ExecutionEnvelope.create(
                        task = task,
                        source = NotificationActionReceiver.SOURCE,
                    ),
                )
            }
            if (result.logInserted) pruneRunLogs(force = false)
            val status = when {
                result.held -> "held"
                result.skippedReason != null -> "skipped"
                result.report.success -> "succeeded"
                else -> "failed"
            }
            AppLogger.info(TAG, "Notification button '$buttonLabel' -> ${task.name} $status (${result.report.durationMs}ms)")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.error(TAG, "Notification button '$buttonLabel' failed", e)
        }
    }

    private fun logCooldownSkip(profile: Profile, task: Task, remainingMs: Long) {
        logProfileSkippedRun(profile, task, "Cooldown active for ${formatRemainingCooldown(remainingMs)}.")
    }

    private fun logProfileCausalLoop(
        profile: Profile,
        task: Task,
        causal: CausalExecutionDecision,
        reason: String,
    ) {
        engineHeartbeatStore.recordMatcherError(reason)
        scope.launch {
            val source = "Profile: ${profile.name}"
            val inserted = logSkippedRun(
                db = db,
                task = task,
                source = source,
                reason = reason,
                metadata = profileRunMetadata(profile),
                execution = ExecutionEnvelope.create(
                    task = task,
                    source = source,
                    profileId = profile.id,
                    parentExecutionId = causal.parentExecutionId,
                    causalDepth = causal.depth,
                    causalProfileChain = causal.profileChain,
                ),
                terminalReason = ExecutionTerminalReason(
                    ExecutionTerminalReasonCode.CAUSAL_LOOP,
                    reason,
                ),
            )
            if (inserted) pruneRunLogs(force = false)
        }
    }

    private fun logProfileSkippedRun(profile: Profile, task: Task, reason: String) {
        scope.launch {
            val inserted = logSkippedRun(
                db = db,
                task = task,
                source = "Profile: ${profile.name}",
                reason = reason,
                metadata = profileRunMetadata(profile),
                execution = ExecutionEnvelope.create(
                    task = task,
                    source = "Profile: ${profile.name}",
                    profileId = profile.id,
                ),
                terminalReason = ExecutionTerminalReason(
                    ExecutionTerminalReasonCode.COLLISION_SKIPPED,
                    reason,
                ),
            )
            if (inserted) pruneRunLogs(force = false)
        }
    }

    private suspend fun pruneRunLogs(force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force && now - lastRunLogPruneAt < RUN_LOG_PRUNE_INTERVAL_MS) return

        val policy = runLogRetentionSettings.load()
        runCatching {
            db.runLogDao().applyRetention(policy, now)
        }
            .onSuccess { deleted ->
                lastRunLogPruneAt = now
                if (deleted > 0) {
                    AppLogger.info(TAG, "Pruned $deleted old run log entries")
                }
            }
            .onFailure { error ->
                AppLogger.error(TAG, "Failed to prune run logs", error)
            }
    }

    private fun reserveCooldown(profileId: Long, cooldownSec: Int): CooldownReservation {
        val reservation = cooldowns.reserve(profileId, cooldownSec)
        if (!reservation.accepted) {
            AppLogger.info(TAG, "Profile $profileId on cooldown, skipping")
        }
        return reservation
    }

    private fun profileRunMetadata(profile: Profile): List<String> = buildList {
        add("Mode: ${profile.automationMode.name.lowercase()}")
        if (profile.cooldownSec > 0) add("Cooldown: ${profile.cooldownSec}s")
        if (profile.priority != 0) add("Profile priority: ${profile.priority}")
        if (profile.gracePeriodSec > 0) add("Grace period: ${profile.gracePeriodSec}s")
        profile.maxActiveExecutions?.let { add("Profile active limit: $it") }
        profile.burstLimit?.let { add("Profile burst limit: $it") }
        if (profile.overflowPolicy == com.opentasker.core.model.ProfileOverflowPolicy.SILENT) {
            add("Overflow logging: silent")
        }
        when (profile.lifetime) {
            ProfileLifetime.NEVER -> Unit
            ProfileLifetime.UNTIL_DATE -> add("Lifetime: until ${profile.expiresAtMs}")
            ProfileLifetime.ONCE -> add("Lifetime: once")
        }
    }

    private fun formatRemainingCooldown(remainingMs: Long): String {
        val seconds = TimeUnit.MILLISECONDS.toSeconds(remainingMs).coerceAtLeast(1)
        return if (seconds == 1L) "1 second" else "$seconds seconds"
    }

    private suspend fun <T> withTaskPresence(task: Task, block: suspend () -> T): T {
        val token = nextActiveTaskToken.incrementAndGet()
        synchronized(activeTaskNames) {
            activeTaskNames[token] = task.name.trim().takeIf(String::isNotBlank) ?: "Task ${task.id}"
        }
        updateForegroundNotification()
        return try {
            block()
        } finally {
            activeTaskNames.remove(token)
            updateForegroundNotification()
        }
    }

    private fun updateForegroundNotification() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        runCatching {
            NotificationManagerCompat.from(this).notify(NOTIF_ID, buildForegroundNotification())
        }.onFailure { error ->
            AppLogger.warn(TAG, "Unable to refresh foreground task notification: ${error.message}")
        }
    }

    private fun buildForegroundNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            PromotedOngoingNotificationSupport.ENGINE_CHANNEL_ID,
            getString(R.string.service_notification_channel_name),
            PromotedOngoingNotificationSupport.ENGINE_CHANNEL_IMPORTANCE,
        )
        nm.createNotificationChannel(channel)
        val channelImportance = nm.getNotificationChannel(channel.id)?.importance ?: channel.importance
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val activeTasks = synchronized(activeTaskNames) { activeTaskNames.values.toList() }
        val activeTask = activeTasks.firstOrNull()
        val title = if (activeTask == null) {
            getString(R.string.service_notification_idle_title)
        } else {
            getString(R.string.service_notification_active_title, activeTask)
        }
        val text = when {
            activeTasks.size <= 1 -> getString(R.string.service_notification_open_status)
            else -> getString(R.string.service_notification_multiple_tasks, activeTasks.size - 1)
        }
        val builder = NotificationCompat.Builder(this, channel.id)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ContextCompat.getColor(this, R.color.notification_accent))
            .setContentIntent(pendingIntent)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
        if (activeTask != null) {
            builder.setShortCriticalText(activeTask.take(24))
        }
        return if (activeTask == null) {
            builder.build()
        } else {
            PromotedOngoingNotificationSupport.build(
                builder = builder,
                manager = nm,
                channelImportance = channelImportance,
                title = title,
                ongoing = true,
            )
        }
    }

    private fun startForegroundCompat() {
        val n = buildForegroundNotification()
        val activeTypes = foregroundServiceTypes()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, activeTypes)
        } else {
            startForeground(NOTIF_ID, n)
        }
        engineHeartbeatStore.recordAlive(foregroundServiceTypes = activeTypes)
    }

    private fun foregroundServiceTypes(): Int {
        var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        if (hasBackgroundLocationForegroundServicePrerequisites()) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        }
        return types
    }

    private fun hasBackgroundLocationForegroundServicePrerequisites(): Boolean {
        if (!hasAnyLocationPermission()) return false
        if (Build.VERSION.SDK_INT >= 29 && !hasPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) return false
        val locationManager = getSystemService(LocationManager::class.java) ?: return false
        return if (Build.VERSION.SDK_INT >= 28) {
            locationManager.isLocationEnabled
        } else {
            runCatching { locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrDefault(false) ||
                runCatching { locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) }.getOrDefault(false)
        }
    }

    private fun hasAnyLocationPermission(): Boolean =
        hasPermission(Manifest.permission.ACCESS_FINE_LOCATION) ||
            hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun recordMatcherError(message: String, error: Throwable) {
        val detail = error.message?.takeIf(String::isNotBlank)?.let { ": $it" }.orEmpty()
        engineHeartbeatStore.recordMatcherError(message + detail)
    }

    companion object {
        private const val TAG = "AutomationService"
        const val ACTION_BOOT_COMPLETED_TRIGGER = "com.opentasker.action.BOOT_COMPLETED_TRIGGER"
        const val ACTION_TIME_TICK_TRIGGER = "com.opentasker.action.TIME_TICK_TRIGGER"
        const val ACTION_RUN_NOTIFICATION_TASK = "com.opentasker.action.RUN_NOTIFICATION_TASK"
        const val ACTION_RUN_EXTERNAL_TASK = "com.opentasker.action.RUN_EXTERNAL_TASK"
        const val EXTRA_STARTED_FROM_VISIBLE_UI = "com.opentasker.extra.STARTED_FROM_VISIBLE_UI"
        private const val NOTIF_ID = 1001
        private const val MAX_QUEUED_TASKS = 50
        private const val MONITOR_SUBSCRIPTION_TIMEOUT_MS = 2_000L
        private const val ENGINE_HEARTBEAT_INTERVAL_MS = 60_000L
        private const val ENGINE_START_TRACE_SECTION = "OpenTasker.engineStart"
        private const val ENGINE_START_TRACE_COOKIE = 0x0E57
        private val RUN_LOG_PRUNE_INTERVAL_MS = TimeUnit.HOURS.toMillis(1)
    }
}

/**
 * Reconcile signature for the running profile registry. Two profile-table snapshots produce the
 * same signature when nothing the engine depends on has changed, so purely cosmetic edits (name,
 * group) do not thrash matcher rebuilds while enable/disable, context, task-wiring, mode, and
 * cooldown changes do. Disabled profiles are excluded because the engine only runs enabled ones.
 */
internal fun profileRegistrySignature(profiles: List<ProfileEntity>): List<String> =
    profiles.asSequence()
        .filter { it.enabled && !it.requiresRiskAcknowledgement }
        .sortedBy { it.id }
        .map { p ->
            listOf(
                p.id,
                p.enterTaskId,
                p.exitTaskId,
                p.cooldownSec,
                p.automationMode,
                p.contextsJson,
                p.priority,
                p.gracePeriodSec,
                p.lifetime,
                p.expiresAtMs,
                p.lifetimeConsumed,
                p.maxActiveExecutions,
                p.burstLimit,
                p.overflowPolicy,
                p.fallbackTaskId,
            ).joinToString("|")
        }
        .toList()

/**
 * Which matchers a reload keeps running, which of the ones it builds again start out on, and the
 * profiles held back by priority that nothing outranks anymore.
 */
internal data class MatcherReload(val kept: Set<Long>, val stillOn: Set<Long>, val released: List<Profile> = emptyList())

/**
 * Plans a reload of [profiles], the enabled set, against [running], the profiles whose matchers are
 * still going, and prunes the engine's record of [matched] and [dispatched] profiles to match.
 * A profile whose matching rules haven't changed keeps its matcher, so the reload doesn't read its
 * contexts again: a source's first event can be a stand-in, like the App source's empty foreground
 * or a sensor's default. Of the rest, a profile whose enter task was handed off stays on, and one
 * that matched without running, held back by a higher priority profile, starts off and matches
 * afresh. A kept profile that's held back stays matched, and is released once the edited
 * priorities, or a profile no longer enabled, leave nothing outranking it.
 */
internal fun planMatcherReload(
    running: Map<Long, Profile>,
    profiles: List<Profile>,
    matched: MutableMap<Long, Profile>,
    dispatched: MutableSet<Long>,
): MatcherReload {
    val kept = profiles.filter { profile ->
        val before = running[profile.id]
        before != null && matchingRules(before) == matchingRules(profile)
    }.mapTo(mutableSetOf()) { it.id }
    dispatched.retainAll(profiles.mapTo(mutableSetOf()) { it.id })
    matched.keys.retainAll { it in dispatched || it in kept }
    profiles.forEach { profile -> if (profile.id in matched) matched[profile.id] = profile }
    val candidates = matched.values.toList()
    val released = candidates.filter { it.id !in dispatched && ProfileLifecyclePolicy.suppressor(it, candidates) == null }
    return MatcherReload(kept, matched.keys - kept, released)
}

/** [profile] without what only matters once it has gone on or off: its name, tasks, priority and run limits. */
internal fun matchingRules(profile: Profile): Profile = profile.copy(
    name = "",
    enterTaskId = 0,
    exitTaskId = null,
    cooldownSec = 0,
    automationMode = AutomationMode.SINGLE,
    group = null,
    priority = 0,
    maxActiveExecutions = null,
    burstLimit = null,
    overflowPolicy = ProfileOverflowPolicy.LOG,
    fallbackTaskId = null,
)
