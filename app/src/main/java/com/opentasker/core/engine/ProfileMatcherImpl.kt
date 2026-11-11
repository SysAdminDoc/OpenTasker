package com.opentasker.core.engine

import android.content.Context
import com.opentasker.app.OpenTaskerApp_NoHilt
import com.opentasker.core.contexts.ContextMatchEvaluator
import com.opentasker.core.contexts.ContextEvent
import com.opentasker.core.contexts.ContextSourceRegistry
import com.opentasker.core.contexts.EventDemandContextSource
import com.opentasker.core.contexts.StateDemandContextSource
import com.opentasker.core.contexts.UNRESOLVED_STATE_KEY
import com.opentasker.core.contexts.stateContextKey
import com.opentasker.core.contexts.SubscriptionReadyContextSource
import com.opentasker.core.contexts.TimeContextBounds
import com.opentasker.core.location.LocationDwellStateStore
import com.opentasker.core.logging.AppLogger
import com.opentasker.core.model.ContextSpec
import com.opentasker.core.model.ContextType
import com.opentasker.core.model.Profile
import com.opentasker.core.model.ContextExpressionNode
import com.opentasker.core.model.isValidForContextCount
import com.opentasker.core.model.ProfileLifecyclePolicy
import com.opentasker.core.storage.VariableRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.scan

/**
 * Watches a Profile's contexts and emits level-state transitions or event pulses.
 * Level contexts activate/deactivate when the aggregate match changes; event
 * contexts activate on each matching pulse.
 * 
 * Includes performance monitoring to detect slow matchers.
 */
internal class ProfileMatcher(
    private val app: Context,
    private val profile: Profile,
    private val pulseContinuity: PulseEventContinuity = PulseEventContinuity(),
    private val clock: () -> Long = { System.currentTimeMillis() },
    /** A project's saved globals by name, read only for a Time context bounded by one. */
    private val globalValues: (projectId: Long) -> Flow<Map<String, String>> = ::savedGlobalValues,
) {
    private val tag = "ProfileMatcher[${profile.name}]"
    private val performanceThresholdMs = 1000L // Warn if evaluation takes > 1 second
    private val locationDwellStateStore = LocationDwellStateStore(app)
    private val monitorSubscriptionsReady = CompletableDeferred<Unit>()
    private val readyPulseContextIndexes = mutableSetOf<Int>()

    suspend fun awaitMonitorSubscriptions() {
        monitorSubscriptionsReady.await()
    }
    
    @OptIn(kotlinx.coroutines.FlowPreview::class)
    fun stateChanges(): Flow<ProfileStateChange> {
        if (profile.contexts.isEmpty() || ProfileLifecyclePolicy.isSuppressed(profile, clock())) {
            monitorSubscriptionsReady.complete(Unit)
            return emptyFlow()
        }

        val pulseContextCount = profile.contexts.count { it.type == ContextType.EVENT }
        val hasPulseContexts = pulseContextCount > 0
        if (!hasPulseContexts) monitorSubscriptionsReady.complete(Unit)
        val flows = profile.contexts.mapIndexed { index, spec ->
            val sourceType = ContextMatchEvaluator.sourceKey(spec.type)
            val source = sourceType?.let(ContextSourceRegistry::get)
            if (source != null) {
                val isPulseContext = spec.type == ContextType.EVENT
                val sourceEvents = if (isPulseContext && source is EventDemandContextSource) {
                    source.events(app, spec.config["event"]) {
                        markPulseContextSubscribed(index, pulseContextCount)
                    }
                } else if (spec.type == ContextType.STATE && source is StateDemandContextSource) {
                    // A matcher always narrows demand to one key. If the spec has no resolvable
                    // key, subscribe to nothing physical rather than everything - null here means
                    // "the Inspector wants all keys".
                    source.events(app, stateContextKey(spec) ?: UNRESOLVED_STATE_KEY)
                } else if (isPulseContext && source is SubscriptionReadyContextSource) {
                    source.events(app) { markPulseContextSubscribed(index, pulseContextCount) }
                } else {
                    if (isPulseContext) markPulseContextSubscribed(index, pulseContextCount)
                    source.events(app)
                }
                timeBoundedEvents(profile, spec, sourceEvents, globalValues).scan(
                    ContextMatchUpdate.initial(
                        pulseContext = isPulseContext,
                        pulseSequence = if (isPulseContext) pulseContinuity.currentSequence() else 0L,
                    ),
                ) { previous, event ->
                    if (spec.type == ContextType.PLUGIN &&
                        !ContextMatchEvaluator.pluginEventAddressesSpec(spec, event)
                    ) {
                        // The shared plugin source multiplexes every subscription's poll results;
                        // a result for a different plugin/bundle must not flap this level context.
                        return@scan previous
                    }
                    val preparedEvent = if (spec.type == ContextType.LOCATION) {
                        locationDwellStateStore.enrich(profile.id, index, spec, event)
                    } else {
                        event
                    }
                    val identityEvent = ContextMatchEvaluator.withStablePulseIdentity(spec, preparedEvent)
                    val pulseObservation = if (isPulseContext) {
                        pulseContinuity.observe(index, identityEvent)
                    } else {
                        null
                    }
                    if (pulseObservation?.duplicate == true) return@scan previous
                    val matched = ContextMatchEvaluator.matches(spec, identityEvent)
                    // A health reading that was refused or not read yet, or a Time bound whose global
                    // holds no time, is unknown, not false, so its inverted context stays off too.
                    val effectiveMatched = if (spec.invert) {
                        !matched && !ContextMatchEvaluator.valueUnknown(spec, identityEvent.metadata)
                    } else {
                        matched
                    }
                    ContextMatchUpdate(
                        matched = effectiveMatched,
                        pulseContext = isPulseContext,
                        pulseSequence = pulseSequenceAfterObservation(
                            matched = matched,
                            observedSequence = pulseObservation?.sequence,
                            previousSequence = previous.pulseSequence,
                        ),
                        event = if (isPulseContext && effectiveMatched) identityEvent else null,
                    )
                }.onEach { update ->
                    AutomationLiveConditionState.updateContext(profile.id, index, update.matched)
                }
            } else {
                AppLogger.warn(tag, "No context source registered for ${spec.type}; treating as non-matching")
                if (spec.type == ContextType.EVENT) markPulseContextSubscribed(index, pulseContextCount)
                flowOf(ContextMatchUpdate.initial(spec.type == ContextType.EVENT)).onEach { update ->
                    AutomationLiveConditionState.updateContext(profile.id, index, update.matched)
                }
            }
        }

        return if (flows.isEmpty()) {
            emptyFlow()
        } else {
            combine(flows) { allMatches ->
                evaluateSnapshot(allMatches)
            }.let { snapshots ->
                val stabilizedSnapshots = stabilizeProfileSnapshots(
                    snapshots = snapshots,
                    lifecycleTicks = lifecycleTicks(),
                    profile = profile,
                    clock = clock,
                    hasPulseContexts = hasPulseContexts,
                )
                profileStateChangesFromSnapshots(
                    snapshots = stabilizedSnapshots,
                    hasPulseContexts = hasPulseContexts,
                    initialPulseSequence = pulseContinuity.currentSequence(),
                ) { change ->
                    val startTime = System.currentTimeMillis()
                    when (change) {
                        is ProfileStateChange.Activated -> {
                            val reason = if (hasPulseContexts) "Profile activated by event pulse" else "Profile activated"
                            AppLogger.info(tag, reason)
                        }
                        ProfileStateChange.Deactivated -> AppLogger.info(tag, "Profile deactivated")
                    }
                    val duration = System.currentTimeMillis() - startTime
                    AppLogger.debug(tag, "State transition evaluated in ${duration}ms")
                }
            }
        }
    }

    private fun lifecycleTicks(): Flow<Unit> = flow {
        emit(Unit)
        val expiry = profile.expiresAtMs?.takeIf { profile.lifetime == com.opentasker.core.model.ProfileLifetime.UNTIL_DATE }
        val remaining = expiry?.minus(clock()) ?: 0L
        if (remaining > 0L) {
            delay(remaining)
            emit(Unit)
        }
    }

    /** Evaluates editor-provided events through the same profile boolean logic as live matching. */
    internal fun evaluateSyntheticEvents(events: Map<Int, ContextEvent>): ProfileMatchSnapshot {
        val updates = profile.contexts.mapIndexed { index, spec ->
            val event = events[index]
            val rawMatched = event?.let { ContextMatchEvaluator.matches(spec, it) } == true
            ContextMatchUpdate(
                matched = if (spec.invert) !rawMatched else rawMatched,
                pulseContext = spec.type == ContextType.EVENT,
                pulseSequence = if (spec.type == ContextType.EVENT && rawMatched) 1L else 0L,
                event = event?.takeIf { rawMatched },
            )
        }
        return ProfileMatchSnapshot(
            allMatched = evaluateContextExpression(
                updates.toTypedArray(),
                profile.contexts,
                profile.contextExpression,
            ),
            pulseSequence = updates.maxOfOrNull { it.pulseSequence } ?: 0L,
            event = updates.lastOrNull { it.event != null }?.event,
        )
    }

    private fun evaluateSnapshot(
        contextMatches: Array<ContextMatchUpdate>,
    ): ProfileMatchSnapshot {
        val startTime = System.currentTimeMillis()
        val allMatched = evaluateContextExpression(contextMatches, profile.contexts, profile.contextExpression)
        val pulseSequence = contextMatches
            .filter { it.pulseContext }
            .maxOfOrNull { it.pulseSequence }
            ?: 0L
        val duration = System.currentTimeMillis() - startTime

        if (duration > performanceThresholdMs) {
            AppLogger.warn(tag, "Slow profile evaluation: ${duration}ms (threshold: ${performanceThresholdMs}ms)")
        }

        return ProfileMatchSnapshot(
            allMatched = allMatched,
            pulseSequence = pulseSequence,
            event = contextMatches.lastOrNull { it.event != null }?.event,
        )
    }

    private fun markPulseContextSubscribed(index: Int, expectedCount: Int) {
        synchronized(readyPulseContextIndexes) {
            if (readyPulseContextIndexes.add(index) && readyPulseContextIndexes.size >= expectedCount) {
                monitorSubscriptionsReady.complete(Unit)
            }
        }
    }

}

@OptIn(kotlinx.coroutines.FlowPreview::class)
internal fun stabilizeProfileSnapshots(
    snapshots: Flow<ProfileMatchSnapshot>,
    lifecycleTicks: Flow<Unit>,
    profile: Profile,
    clock: () -> Long,
    hasPulseContexts: Boolean,
): Flow<ProfileMatchSnapshot> {
    val lifecycleSnapshots = combine(snapshots, lifecycleTicks) { snapshot, _ ->
        if (ProfileLifecyclePolicy.isSuppressed(profile, clock())) {
            ProfileMatchSnapshot(allMatched = false, pulseSequence = 0L)
        } else {
            snapshot
        }
    }
    return if (!hasPulseContexts && profile.gracePeriodSec > 0) {
        lifecycleSnapshots.debounce(profile.gracePeriodSec * 1_000L)
    } else {
        lifecycleSnapshots
    }
}

internal data class ContextMatchUpdate(
    val matched: Boolean,
    val pulseContext: Boolean,
    val pulseSequence: Long,
    val event: ContextEvent? = null,
) {
    companion object {
        fun initial(pulseContext: Boolean, pulseSequence: Long = 0L): ContextMatchUpdate =
            ContextMatchUpdate(matched = false, pulseContext = pulseContext, pulseSequence = pulseSequence)
    }
}

/**
 * The pulse sequence a context reports after observing an event.
 *
 * Only an event the context is actually watching advances it. Every EVENT context is subscribed to
 * every bridge, so advancing on arrival alone made a profile activate whenever its expression
 * happened to be true for another reason: `EVENT(nfc) OR STATE(wifi=Home)` re-fired on every
 * notification while on that network, and two OR'd EVENT leaves turned one physical event into two
 * activations because each leaf advanced the shared sequence in turn.
 *
 * [matched] is the raw spec match, before inversion: the pulse means "the awaited event arrived",
 * which an inverted leaf never observes.
 */
internal fun pulseSequenceAfterObservation(
    matched: Boolean,
    observedSequence: Long?,
    previousSequence: Long,
): Long = if (matched) observedSequence ?: 0L else previousSequence

/** [events] for [spec], carrying the times of the globals it's bounded by, read from [profile]'s own project. */
internal fun timeBoundedEvents(
    profile: Profile,
    spec: ContextSpec,
    events: Flow<ContextEvent>,
    globalValues: (projectId: Long) -> Flow<Map<String, String>>,
): Flow<ContextEvent> = if (TimeContextBounds.variableBounds(spec).isEmpty()) {
    events
} else {
    TimeContextBounds.resolving(spec, events, globalValues(profile.projectId))
}

/**
 * [source], read again after a pause whenever it fails. Nothing is emitted meanwhile, so a Time
 * context keeps the last globals it read instead of switching off until the profiles reload, and
 * stays off before the first read. The pause grows with the failures in a row and starts over once
 * a read gets through, and only the first failure of a run is logged.
 */
internal fun retryingGlobalValues(
    pauseMs: (failuresInARow: Long) -> Long = { failures -> minOf(60_000L, 1_000L shl failures.coerceAtMost(6L).toInt()) },
    source: () -> Flow<Map<String, String>>,
): Flow<Map<String, String>> = flow {
    var failures = 0L
    emitAll(
        flow { emitAll(source()) }
            .onEach { failures = 0L }
            .retryWhen { error, _ ->
                if (failures == 0L) AppLogger.error("ProfileMatcher", "Could not read the globals a Time context is bounded by", error)
                delay(pauseMs(failures))
                failures++
                true
            },
    )
}

private fun savedGlobalValues(projectId: Long): Flow<Map<String, String>> = retryingGlobalValues {
    VariableRepository(OpenTaskerApp_NoHilt.db.variableDao()).observeGlobals(projectId)
        .map { variables -> TimeContextBounds.valuesByProject(variables)[projectId].orEmpty() }
}

internal data class ProfileMatchSnapshot(
    val allMatched: Boolean,
    val pulseSequence: Long,
    val event: ContextEvent? = null,
)

private data class PulseAccumulator(
    val lastPulseSequence: Long,
    val change: ProfileStateChange?,
)

internal fun profileStateChangesFromSnapshots(
    snapshots: Flow<ProfileMatchSnapshot>,
    hasPulseContexts: Boolean,
    initialPulseSequence: Long = 0L,
    onChange: (ProfileStateChange) -> Unit = {},
): Flow<ProfileStateChange> =
    if (hasPulseContexts) {
        snapshots.scan(PulseAccumulator(lastPulseSequence = initialPulseSequence, change = null)) { previous, snapshot ->
            val pulseChanged = snapshot.pulseSequence != previous.lastPulseSequence
            val change = if (pulseChanged && snapshot.pulseSequence > 0 && snapshot.allMatched) {
                ProfileStateChange.Activated(snapshot.event)
            } else {
                null
            }
            PulseAccumulator(lastPulseSequence = snapshot.pulseSequence, change = change)
        }.mapNotNull { accumulator ->
            accumulator.change?.also(onChange)
        }
    } else {
        snapshots.map { it.allMatched }
            .distinctUntilChanged()
            .scan(Pair(false, false)) { (_, prev), now -> Pair(prev, now) }
            .mapNotNull { (prev, now) ->
                val change = when {
                    !prev && now -> ProfileStateChange.Activated(null)
                    prev && !now -> ProfileStateChange.Deactivated
                    else -> null
                }
                change?.also(onChange)
            }
    }

internal fun evaluateWithOrGroups(
    contextMatches: Array<ContextMatchUpdate>,
    specs: List<ContextSpec>,
): Boolean {
    if (contextMatches.isEmpty()) return false
    val andTerms = mutableListOf<Boolean>()
    val orGroups = mutableMapOf<String, Boolean>()

    for (i in contextMatches.indices) {
        val group = specs.getOrNull(i)?.orGroup
        if (group != null) {
            orGroups[group] = orGroups.getOrDefault(group, false) || contextMatches[i].matched
        } else {
            andTerms.add(contextMatches[i].matched)
        }
    }
    return andTerms.all { it } && orGroups.values.all { it }
}

internal fun evaluateContextExpression(
    contextMatches: Array<ContextMatchUpdate>,
    specs: List<ContextSpec>,
    expression: ContextExpressionNode?,
): Boolean {
    if (expression == null) return evaluateWithOrGroups(contextMatches, specs)
    if (!expression.isValidForContextCount(specs.size)) return false
    return expression.evaluate(contextMatches.map { it.matched })
}

sealed class ProfileStateChange {
    data class Activated(val event: ContextEvent?) : ProfileStateChange()
    data object Deactivated : ProfileStateChange()
}
