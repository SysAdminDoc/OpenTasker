package com.opentasker.core.contexts

import com.opentasker.core.engine.CausalLoopDiagnostic
import com.opentasker.core.model.ContextSpec
import com.opentasker.core.model.ContextBooleanOperator
import com.opentasker.core.model.ContextExpressionNode
import com.opentasker.core.model.isValidForContextCount
import com.opentasker.core.model.ContextType
import com.opentasker.core.model.Profile
import com.opentasker.core.model.ProfileLifecyclePolicy
import com.opentasker.core.model.ProfileLifecycleStrings
import java.util.Locale

typealias ContextObservationTransformer = (
    profile: Profile,
    contextIndex: Int,
    spec: ContextSpec,
    observation: ContextEventObservation,
) -> ContextEventObservation

enum class ContextSourceStatus(val label: String) {
    Active("Active"),
    Waiting("Waiting"),
    NeedsSetup("Needs setup"),
    Missing("Missing"),
    Error("Error"),
}

/** Health of the latest observation, independent of whether the source itself needs setup. */
enum class ContextObservationStatus(val label: String) {
    Loading("Loading"),
    Ready("Ready"),
    Stale("Stale"),
    Error("Error"),
}

data class ContextEventObservation(
    val event: ContextEvent,
    val observedAtMs: Long,
)

data class ContextSourceSnapshot(
    val key: String,
    val label: String,
    val registered: Boolean,
    val setupReady: Boolean = true,
    val setupDetail: String? = null,
    val error: String? = null,
    val lastObservation: ContextEventObservation? = null,
    /**
     * Setup gaps that stop one kind of event while the source itself runs, keyed by the lowercase
     * `event` value they block, like "nfc" while Android withholds tags. Only those contexts show it.
     */
    val eventSetupGaps: Map<String, String> = emptyMap(),
) {
    val status: ContextSourceStatus
        get() = when {
            !registered -> ContextSourceStatus.Missing
            error != null -> ContextSourceStatus.Error
            !setupReady -> ContextSourceStatus.NeedsSetup
            lastObservation == null -> ContextSourceStatus.Waiting
            else -> ContextSourceStatus.Active
        }
}

data class ContextInspectionSnapshot(
    val generatedAtMs: Long,
    val sources: List<ContextSourceSnapshot>,
    val profiles: List<ProfileInspection>,
    val causalLoop: CausalLoopDiagnostic? = null,
)

data class ProfileInspection(
    val profileId: Long,
    val profileName: String,
    val enabled: Boolean,
    val matching: Boolean,
    val summary: String,
    val contexts: List<ContextCheck>,
    val logicExplanation: String = "",
    val profile: Profile? = null,
    val priority: Int = 0,
    val suppressionReason: String? = null,
)

data class ContextCheck(
    val index: Int,
    val spec: ContextSpec,
    val sourceKey: String?,
    val sourceLabel: String,
    val sourceStatus: ContextSourceStatus,
    val rawMatched: Boolean,
    val effectiveMatched: Boolean,
    val lastObservation: ContextEventObservation?,
    val reason: String,
    val configSummary: String,
    /** The value can't be told yet, so a NOT over this context doesn't match either. */
    val valueUnknown: Boolean = false,
)

fun inspectProfiles(
    profiles: List<Profile>,
    sourceSnapshots: Collection<ContextSourceSnapshot>,
    nowMs: Long = System.currentTimeMillis(),
    lifecycleStrings: ProfileLifecycleStrings = ProfileLifecycleStrings.English,
    observationTransformer: ContextObservationTransformer = { _, _, _, observation -> observation },
): List<ProfileInspection> {
    val sourcesByKey = sourceSnapshots.associateBy { it.key }
    val inspections = profiles
        .map { profile -> inspectProfile(profile, sourcesByKey, nowMs, lifecycleStrings, observationTransformer) }
        .sortedWith(compareBy<ProfileInspection> { !it.enabled }.thenBy { it.profileName.lowercase(Locale.US) })
    val profileById = profiles.associateBy(Profile::id)
    val matchingProfiles = inspections.mapNotNull { inspection ->
        inspection.profile
            ?.takeIf { inspection.matching }
    }
    return inspections.map { inspection ->
        val profile = profileById[inspection.profileId] ?: return@map inspection
        val suppression = ProfileLifecyclePolicy.suppressionByPriority(profile, matchingProfiles, lifecycleStrings)
        if (inspection.matching && suppression != null) {
            inspection.copy(matching = false, summary = suppression, suppressionReason = suppression)
        } else {
            inspection
        }
    }
}

fun inspectProfile(
    profile: Profile,
    sourcesByKey: Map<String, ContextSourceSnapshot>,
    nowMs: Long = System.currentTimeMillis(),
    lifecycleStrings: ProfileLifecycleStrings = ProfileLifecycleStrings.English,
    observationTransformer: ContextObservationTransformer = { _, _, _, observation -> observation },
): ProfileInspection {
    val checks = profile.contexts.mapIndexed { index, spec ->
        inspectContextForProfile(profile, index, spec, sourcesByKey, observationTransformer)
    }
    val contextsMatch = checks.isNotEmpty() && evaluateChecks(checks, profile.contextExpression)
    val lifecycleSuppression = ProfileLifecyclePolicy.suppressionReason(profile, nowMs, lifecycleStrings)
    val matching = profile.enabled && contextsMatch && lifecycleSuppression == null
    val summary = when {
        !profile.enabled -> "Profile is disabled."
        lifecycleSuppression != null -> lifecycleSuppression
        checks.isEmpty() -> "No contexts are configured."
        contextsMatch -> "All contexts currently match."
        else -> checks.firstOrNull { !it.effectiveMatched }?.reason ?: "At least one context does not match."
    }

    return ProfileInspection(
        profileId = profile.id,
        profileName = profile.name,
        enabled = profile.enabled,
        matching = matching,
        summary = summary,
        contexts = checks,
        logicExplanation = profile.contextExpression?.let { explainContextExpression(it, checks) }.orEmpty(),
        profile = profile,
        priority = profile.priority,
        suppressionReason = lifecycleSuppression,
    )
}

fun inspectContext(
    index: Int,
    spec: ContextSpec,
    sourcesByKey: Map<String, ContextSourceSnapshot>,
): ContextCheck {
    return inspectContextForProfile(
        profile = Profile(id = 0, name = "Inspector", enterTaskId = 0),
        index = index,
        spec = spec,
        sourcesByKey = sourcesByKey,
        observationTransformer = { _, _, _, observation -> observation },
    )
}

/**
 * Mirrors the engine's evaluateWithOrGroups semantics: contexts sharing an orGroup
 * need only one member to match; ungrouped contexts are AND terms. The Inspector
 * must agree with the engine or its "does not match" explanations lie for profiles
 * that are genuinely active through an OR group.
 */
internal fun evaluateChecksWithOrGroups(checks: List<ContextCheck>): Boolean {
    val andTerms = mutableListOf<Boolean>()
    val orGroups = mutableMapOf<String, Boolean>()
    for (check in checks) {
        val group = check.spec.orGroup
        if (group != null) {
            orGroups[group] = orGroups.getOrDefault(group, false) || check.effectiveMatched
        } else {
            andTerms.add(check.effectiveMatched)
        }
    }
    return andTerms.all { it } && orGroups.values.all { it }
}

internal fun evaluateChecks(
    checks: List<ContextCheck>,
    expression: ContextExpressionNode?,
): Boolean = if (expression == null) {
    evaluateChecksWithOrGroups(checks)
} else if (!expression.isValidForContextCount(checks.size)) {
    false
} else {
    expression.evaluate(checks.map { if (it.valueUnknown) null else it.effectiveMatched }) == true
}

internal fun explainContextExpression(
    expression: ContextExpressionNode,
    checks: List<ContextCheck>,
): String {
    fun explain(node: ContextExpressionNode): String {
        // Bound locally: `operator` is a public property of another module now, so checking it for
        // null inside the `when` subject no longer smart-casts it.
        val nodeOperator = node.operator
        return when {
            node.isLeaf() -> {
                val check = node.contextIndex?.let(checks::getOrNull)
                val state = when {
                    check?.effectiveMatched == true -> "match"
                    check?.valueUnknown == true -> "unknown"
                    else -> "no match"
                }
                "Context ${(node.contextIndex ?: -1) + 1}=$state"
            }
            nodeOperator != null && node.children.isNotEmpty() -> {
                val operator = when (nodeOperator) {
                    ContextBooleanOperator.AND -> "ALL"
                    ContextBooleanOperator.OR -> "ANY"
                }
                val body = node.children.joinToString(", ", transform = ::explain)
                if (node.invert) "NOT($operator($body))" else "$operator($body)"
            }
            else -> "Invalid context group"
        }
    }
    return explain(expression)
}

private fun inspectContextForProfile(
    profile: Profile,
    index: Int,
    spec: ContextSpec,
    sourcesByKey: Map<String, ContextSourceSnapshot>,
    observationTransformer: ContextObservationTransformer,
): ContextCheck {
    val sourceKey = ContextMatchEvaluator.sourceKey(spec.type)
    val snapshot = sourceKey?.let(sourcesByKey::get)
    val observation = snapshot?.lastObservation?.let {
        observationTransformer(profile, index, spec, it)
    }
    val rawMatched = observation?.let { ContextMatchEvaluator.matches(spec, it.event) } ?: false
    val sourceStatus = snapshot?.status ?: ContextSourceStatus.Missing
    val sourceCanMatch = sourceStatus == ContextSourceStatus.Active
    // Same rule as the engine: a state with no reading yet, or one whose value is unknown, is
    // unknown, so neither its inverted context nor a NOT group over it turns on.
    val valueUnknown = !rawMatched && if (observation == null) {
        spec.type != ContextType.EVENT
    } else {
        ContextMatchEvaluator.valueUnknown(spec, observation.event.metadata)
    }
    val effectiveMatched = sourceCanMatch && if (spec.invert) !rawMatched && observation != null && !valueUnknown else rawMatched
    val reason = contextReason(spec, sourceKey, snapshot, observation, rawMatched, effectiveMatched)

    return ContextCheck(
        index = index,
        spec = spec,
        sourceKey = sourceKey,
        sourceLabel = snapshot?.label ?: sourceKey?.toContextSourceLabel() ?: "Unknown source",
        sourceStatus = sourceStatus,
        rawMatched = rawMatched,
        effectiveMatched = effectiveMatched,
        lastObservation = observation,
        reason = reason,
        configSummary = contextConfigSummary(spec),
        valueUnknown = valueUnknown,
    )
}

fun contextConfigSummary(spec: ContextSpec): String {
    val summary = when (spec.type) {
        ContextType.DAY -> DaySchedule.displayLabel(spec.config["days"] ?: spec.config["day"].orEmpty())
        ContextType.PLUGIN -> {
            val pkg = spec.config["package"].orEmpty().ifBlank { "none" }
            val blurb = spec.config["blurb"]?.takeIf { it.isNotBlank() }
            if (blurb != null) "$pkg ($blurb)" else pkg
        }
        ContextType.STATE -> stateConditionSummary(spec) ?: configEntriesSummary(spec.config)
        ContextType.EVENT -> sunEventSummary(spec) ?: configEntriesSummary(spec.config)
        else -> configEntriesSummary(spec.config)
    }
    return if (spec.invert) "$summary; inverted" else summary
}

/** Names the moment a sun context waits for, "Civil dusk" rather than "elevation=civil, event=sunset". */
private fun sunEventSummary(spec: ContextSpec): String? {
    val event = spec.config["event"]?.trim()?.lowercase()
    if (event != "sunrise" && event != "sunset") return null
    val raw = spec.config["elevation"]?.trim().orEmpty()
    // An unreadable elevation never matches, so it shows as written.
    val degrees = SunEventCalculator.elevationDegrees(raw) ?: return null
    val rising = event == "sunrise"
    val moment = when {
        raw.isEmpty() -> if (rising) "Sunrise" else "Sunset"
        raw.lowercase() in SunEventCalculator.NAMED_ELEVATIONS -> {
            val name = raw.lowercase().replaceFirstChar { it.uppercase() }
            "$name ${if (rising) "dawn" else "dusk"} (sun ${degreesText(-degrees)} degrees below the horizon)"
        }
        else -> {
            val motion = if (rising) "rising" else "setting"
            when {
                degrees > 0 -> "Sun $motion past ${degreesText(degrees)} degrees above the horizon"
                degrees < 0 -> "Sun $motion past ${degreesText(-degrees)} degrees below the horizon"
                else -> "Sun $motion past the horizon"
            }
        }
    }
    val extras = spec.config.filterKeys { it !in SUN_EVENT_KEYS }
    return if (extras.isEmpty()) moment else "$moment, ${configEntriesSummary(extras)}"
}

private val SUN_EVENT_KEYS = setOf("event", "elevation")

private fun degreesText(value: Double): String =
    if (value == Math.rint(value)) value.toLong().toString() else value.toString()

private fun configEntriesSummary(config: Map<String, String>): String =
    config.entries
        .sortedBy { it.key }
        .joinToString { "${it.key}=${it.value}" }
        .ifBlank { "No configuration" }

/** Reads as the check it makes, "Bluetooth device nearby = Car" rather than "key=bt_near, operator==, value=Car". */
private fun stateConditionSummary(spec: ContextSpec): String? {
    // The engine reads a non-blank predicate alone, so one it can't parse shows as written, not as key fields it ignores.
    val predicate = spec.config["predicate"]
    if (!predicate.isNullOrBlank() && parseStatePredicate(predicate) == null) return null
    val (key, operator, value) = stateCondition(spec)
    val normalized = normalizeStateKey(key).ifEmpty { return null }
    val label = if (normalized == BT_NEAR_STATE_KEY) "Bluetooth device nearby" else normalized.replace('_', ' ')
    val condition = "$label ${operator.trim().ifEmpty { "=" }} ${value.trim()}".trim()
    val extras = configEntriesSummary(spec.config.filterKeys { it !in STATE_CONDITION_KEYS })
    return if (spec.config.keys.all { it in STATE_CONDITION_KEYS }) condition else "$condition, $extras"
}

private val STATE_CONDITION_KEYS = setOf("predicate", "key", "operator", "value")

fun String.toContextSourceLabel(): String = when (this) {
    "app" -> "Application"
    "time" -> "Time and day"
    "state" -> "Device state"
    "event" -> "System event"
    "location" -> "Location"
    "plugin" -> "Plugin condition"
    else -> replaceFirstChar { it.titlecase(Locale.US) }
}

private fun contextReason(
    spec: ContextSpec,
    sourceKey: String?,
    snapshot: ContextSourceSnapshot?,
    observation: ContextEventObservation?,
    rawMatched: Boolean,
    effectiveMatched: Boolean,
): String {
    if (sourceKey == null) return "This context type is not mapped to a runtime source."
    if (snapshot == null || !snapshot.registered) return "No registered runtime source for ${sourceKey.toContextSourceLabel()}."
    snapshot.error?.let { return "The ${snapshot.label} source stopped with an error: $it" }
    if (!snapshot.setupReady) {
        return snapshot.setupDetail ?: "${snapshot.label} needs setup before it can report values."
    }
    if (spec.type == ContextType.EVENT) {
        spec.config["event"]?.lowercase(Locale.ROOT)?.let(snapshot.eventSetupGaps::get)?.let { return it }
    }
    if (observation == null) return "Waiting for the first ${snapshot.label} event."
    if (observation.event.type != sourceKey) return "Latest event came from ${observation.event.type}, not $sourceKey."
    if (spec.type == ContextType.STATE) {
        stateSetupMarkerKey(spec)
            ?.let { observation.event.metadata[it] }
            ?.takeIf(String::isNotBlank)
            ?.let { return it }
    }
    timeBoundsGap(spec, observation.event.metadata)?.let { return it }
    if (spec.type == ContextType.EVENT) {
        return when {
            spec.invert && effectiveMatched -> "Latest event does not satisfy the configuration, so the inverted event context can trigger on this pulse."
            spec.invert && rawMatched -> "Latest event satisfies the configuration, so the inverted event context blocks this pulse."
            effectiveMatched -> "Latest event satisfies the configuration; event contexts are one-shot pulses and can trigger again on each matching event."
            else -> "Latest event does not satisfy the configuration; event contexts wait for the next matching pulse."
        }
    }

    val verdict = when {
        spec.invert && effectiveMatched -> "Latest value does not satisfy the configuration, so the inverted context matches."
        spec.invert && rawMatched -> "Latest value satisfies the configuration, so the inverted context blocks the profile."
        effectiveMatched -> "Latest value satisfies the configuration."
        else -> "Latest value does not satisfy the configuration."
    }
    return verdict + timeBoundsRead(spec, observation.event.metadata)
}

/** Why a Time context bounded by a variable can't match yet, naming the variable, or null when every bound holds a time. */
private fun timeBoundsGap(spec: ContextSpec, metadata: Map<String, String>): String? {
    val unresolved = TimeContextBounds.unresolvedBounds(spec, metadata).ifEmpty { return null }
    unresolved.firstOrNull { TimeContextBounds.variableName(it) == null }?.let { unreadable ->
        if (!TimeContextBounds.isLocalVariable(unreadable)) {
            return "$unreadable isn't a variable name, so this context stays off. A name starts with a letter and has only letters, digits, _ and -, like %Sunrise."
        }
        return "$unreadable is a local variable, which a context can't read, so this context stays off. A global has a capital letter in its name."
    }
    val holds = if (unresolved.size == 1) "doesn't hold" else "don't hold"
    return "${unresolved.joinToString(" and ")} $holds a time yet, so this context stays off, inverted or not."
}

/** " Read %Sunrise as 06:42 and %Sunset as 16:51." for a Time context bounded by variables, else empty. */
private fun timeBoundsRead(spec: ContextSpec, metadata: Map<String, String>): String {
    val read = TimeContextBounds.boundTimes(spec, metadata).ifEmpty { return "" }
    return read.joinToString(" and ", prefix = " Read ", postfix = ".") { (name, time) -> "$name as $time" }
}

fun ContextSourceSnapshot.observationStatus(
    nowMs: Long,
    staleAfterMs: Long = CONTEXT_OBSERVATION_STALE_AFTER_MS,
): ContextObservationStatus = when {
    error != null -> ContextObservationStatus.Error
    lastObservation == null -> ContextObservationStatus.Loading
    observationAgeMs(nowMs)?.let { it > staleAfterMs } == true -> ContextObservationStatus.Stale
    else -> ContextObservationStatus.Ready
}

fun ContextSourceSnapshot.observationAgeMs(nowMs: Long): Long? =
    lastObservation?.let { (nowMs - it.observedAtMs).coerceAtLeast(0L) }

internal const val CONTEXT_OBSERVATION_STALE_AFTER_MS = 5 * 60_000L
