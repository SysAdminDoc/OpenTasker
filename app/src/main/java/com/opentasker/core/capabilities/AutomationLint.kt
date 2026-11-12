package com.opentasker.core.capabilities

import com.opentasker.core.contexts.DaySchedule
import com.opentasker.core.contexts.parseStatePredicate
import com.opentasker.core.location.FossGeofenceEvaluator
import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.AutomationInvariant
import com.opentasker.core.model.AutomationInvariantPolicy
import com.opentasker.core.model.ContextSpec
import com.opentasker.core.model.ContextType
import com.opentasker.core.model.Profile
import com.opentasker.core.model.ProfileLifetime
import com.opentasker.core.model.Task
import com.opentasker.core.model.enterTaskIdOrNull
import com.opentasker.core.model.isValidForContextCount
import java.util.Locale

enum class AutomationLintSeverity {
    WARNING,
    BLOCKING,
}

enum class AutomationLintCode {
    MISSING_REVERSAL,
    REPEATED_TRIGGERING,
    PRIORITY_CONFLICT,
    INTER_PROFILE_LOOP,
    SHADOWED_RULE,
    UNREACHABLE_RULE,
    ACTION_REVERT_PAIR,
    INVARIANT_VIOLATION,
}

data class AutomationLintFinding(
    val code: AutomationLintCode,
    val severity: AutomationLintSeverity,
    val profileIds: List<Long>,
    val profileNames: List<String>,
    val title: String,
    val detail: String,
    val suggestedFix: String,
    val invariantId: Long? = null,
)

data class AutomationLintReport(
    val findings: List<AutomationLintFinding> = emptyList(),
) {
    val blockingFindings: List<AutomationLintFinding>
        get() = findings.filter { it.severity == AutomationLintSeverity.BLOCKING }

    val warnings: List<AutomationLintFinding>
        get() = findings.filter { it.severity == AutomationLintSeverity.WARNING }

    fun forProfile(profileId: Long): List<AutomationLintFinding> = findings.filter { profileId in it.profileIds }

    fun blockingFor(profileId: Long): List<AutomationLintFinding> =
        blockingFindings.filter { profileId in it.profileIds }
}

/**
 * High-confidence structural lint for trigger/action combinations.
 *
 * The analyzer deliberately proves only contradictions and direct task edges. It does not guess
 * about arbitrary device state, dynamic task references, or side effects hidden behind plugins;
 * uncertain cases remain warnings or are omitted rather than becoming noisy blockers.
 */
object AutomationLint {
    fun analyze(
        profile: Profile,
        tasks: List<Task>,
        otherProfiles: List<Profile> = emptyList(),
        strings: AutomationLintStrings = AutomationLintStrings.English,
        invariants: List<AutomationInvariant> = emptyList(),
        nowMs: Long? = null,
    ): AutomationLintReport =
        analyze(
            profiles = otherProfiles.filterNot { it.id == profile.id } + profile,
            tasks = tasks,
            strings = strings,
            invariants = invariants,
            nowMs = nowMs,
        )

    fun analyze(
        profiles: List<Profile>,
        tasks: List<Task>,
        strings: AutomationLintStrings = AutomationLintStrings.English,
        invariants: List<AutomationInvariant> = emptyList(),
        nowMs: Long? = null,
    ): AutomationLintReport {
        if (profiles.isEmpty()) return AutomationLintReport()
        val findings = linkedSetOf<AutomationLintFinding>()
        val taskById = tasks.associateBy(Task::id)
        val writesByProfileId = profiles.associate { profile ->
            profile.id to settingWrites(profile, tasks)
        }

        profiles.forEach { profile ->
            val enterTask = taskById[profile.enterTaskId]
            val writes = writesByProfileId[profile.id].orEmpty()
            val unreversed = writes.filterNot(SettingWrite::automaticallyReversed)
            if (profile.exitTaskId == null && unreversed.isNotEmpty()) {
                val copy = strings.missingReversal(profile.name, unreversed.joinToString { it.key })
                findings += AutomationLintFinding(
                    code = AutomationLintCode.MISSING_REVERSAL,
                    severity = AutomationLintSeverity.WARNING,
                    profileIds = listOf(profile.id),
                    profileNames = listOf(profile.name),
                    title = copy.title,
                    detail = copy.detail,
                    suggestedFix = copy.suggestedFix,
                )
            }

            unreachableReason(profile, enterTask, profile.exitTaskId?.let(taskById::get), nowMs)?.let { reason ->
                val copy = strings.unreachableRule(profile.name, reason)
                findings += AutomationLintFinding(
                    code = AutomationLintCode.UNREACHABLE_RULE,
                    severity = AutomationLintSeverity.BLOCKING,
                    profileIds = listOf(profile.id),
                    profileNames = listOf(profile.name),
                    title = copy.title,
                    detail = copy.detail,
                    suggestedFix = copy.suggestedFix,
                )
            }

            val enterWrites = directSettingWrites(enterTask)
            val exitWrites = directSettingWrites(profile.exitTaskId?.let(taskById::get))
            val revertOverlap = (enterWrites intersect exitWrites).toList().sorted()
            if (revertOverlap.isNotEmpty()) {
                val copy = strings.actionRevertPair(profile.name, revertOverlap.joinToString())
                findings += AutomationLintFinding(
                    code = AutomationLintCode.ACTION_REVERT_PAIR,
                    severity = AutomationLintSeverity.WARNING,
                    profileIds = listOf(profile.id),
                    profileNames = listOf(profile.name),
                    title = copy.title,
                    detail = copy.detail,
                    suggestedFix = copy.suggestedFix,
                )
            }

            if (profile.contexts.any { it.type == ContextType.STATE } && !hasRetriggerGuard(profile, enterTask, taskById)) {
                val copy = if (profile.enterTaskIdOrNull == null) {
                    strings.repeatedExitTriggering(profile.name)
                } else {
                    strings.repeatedTriggering(profile.name)
                }
                findings += AutomationLintFinding(
                    code = AutomationLintCode.REPEATED_TRIGGERING,
                    severity = AutomationLintSeverity.WARNING,
                    profileIds = listOf(profile.id),
                    profileNames = listOf(profile.name),
                    title = copy.title,
                    detail = copy.detail,
                    suggestedFix = copy.suggestedFix,
                )
            }
        }

        val enabledProfiles = profiles.filter(Profile::enabled)
        for (leftIndex in enabledProfiles.indices) {
            for (rightIndex in leftIndex + 1 until enabledProfiles.size) {
                val left = enabledProfiles[leftIndex]
                val right = enabledProfiles[rightIndex]
                if (!contextsMayOverlap(left, right)) continue
                val leftWrites = writesByProfileId[left.id].orEmpty().mapTo(hashSetOf(), SettingWrite::key)
                val rightWrites = writesByProfileId[right.id].orEmpty().mapTo(hashSetOf(), SettingWrite::key)
                val overlap = (leftWrites intersect rightWrites).toList().sorted()
                if (overlap.isEmpty()) continue
                val leftPriority = left.priority
                val rightPriority = right.priority
                val equalPriority = leftPriority == rightPriority
                val copy = strings.priorityConflict(
                    leftName = left.name,
                    rightName = right.name,
                    overlap = overlap.joinToString(),
                    leftPriority = leftPriority,
                    rightPriority = rightPriority,
                    equalPriority = equalPriority,
                )
                findings += AutomationLintFinding(
                    code = AutomationLintCode.PRIORITY_CONFLICT,
                    severity = if (equalPriority) AutomationLintSeverity.BLOCKING else AutomationLintSeverity.WARNING,
                    profileIds = listOf(left.id, right.id).sorted(),
                    profileNames = listOf(left.name, right.name),
                    title = copy.title,
                    detail = copy.detail,
                    suggestedFix = copy.suggestedFix,
                )

                if (leftPriority != rightPriority && contextsEquivalent(left, right)) {
                    val shadowing = if (leftPriority > rightPriority) left else right
                    val shadowed = if (leftPriority > rightPriority) right else left
                    val shadowCopy = strings.shadowedRule(
                        shadowingProfileName = shadowing.name,
                        shadowedProfileName = shadowed.name,
                        overlap = overlap.joinToString(),
                        shadowingPriority = shadowing.priority,
                        shadowedPriority = shadowed.priority,
                    )
                    findings += AutomationLintFinding(
                        code = AutomationLintCode.SHADOWED_RULE,
                        severity = AutomationLintSeverity.WARNING,
                        profileIds = listOf(shadowing.id, shadowed.id),
                        profileNames = listOf(shadowing.name, shadowed.name),
                        title = shadowCopy.title,
                        detail = shadowCopy.detail,
                        suggestedFix = shadowCopy.suggestedFix,
                    )
                }
            }
        }

        AutomationInvariantPolicy.normalize(invariants)
            .filter(AutomationInvariant::enabled)
            .forEach { invariant ->
                val guard = ContextSpec(
                    type = ContextType.STATE,
                    config = mapOf(
                        "key" to invariant.guard.key,
                        "operator" to invariant.guard.operator.symbol,
                        "value" to invariant.guard.value,
                    ),
                )
                val violatingProfiles = enabledProfiles.filter { profile ->
                    writesByProfileId[profile.id].orEmpty().any { write ->
                        write.key.equals(invariant.forbiddenWriteKey, ignoreCase = true)
                    } && profileMaySatisfy(profile, guard)
                }
                if (violatingProfiles.isEmpty()) return@forEach
                val displayedNames = violatingProfiles
                    .take(MAX_INVARIANT_PROFILE_NAMES)
                    .map(Profile::name)
                    .toMutableList()
                if (violatingProfiles.size > MAX_INVARIANT_PROFILE_NAMES) {
                    displayedNames += "+${violatingProfiles.size - MAX_INVARIANT_PROFILE_NAMES} more"
                }
                val copy = strings.invariantViolation(
                    invariantName = invariant.name,
                    guard = "${invariant.guard.key} ${invariant.guard.operator.symbol} ${invariant.guard.value}",
                    profileNames = displayedNames.joinToString(),
                    forbiddenWriteKey = invariant.forbiddenWriteKey,
                )
                findings += AutomationLintFinding(
                    code = AutomationLintCode.INVARIANT_VIOLATION,
                    severity = AutomationLintSeverity.BLOCKING,
                    profileIds = violatingProfiles.map(Profile::id).distinct().sorted(),
                    profileNames = violatingProfiles.map(Profile::name),
                    title = copy.title,
                    detail = copy.detail,
                    suggestedFix = copy.suggestedFix,
                    invariantId = invariant.id,
                )
            }

        interProfileCycles(profiles, tasks).forEach { cycle ->
            val copy = strings.interProfileLoop(
                cycle.joinToString(" → ") { it.name } + " → ${cycle.first().name}",
            )
            findings += AutomationLintFinding(
                code = AutomationLintCode.INTER_PROFILE_LOOP,
                severity = AutomationLintSeverity.WARNING,
                profileIds = cycle.map(Profile::id),
                profileNames = cycle.map(Profile::name),
                title = copy.title,
                detail = copy.detail,
                suggestedFix = copy.suggestedFix,
            )
        }

        return AutomationLintReport(findings.toList())
    }

    private fun unreachableReason(profile: Profile, enterTask: Task?, exitTask: Task?, nowMs: Long?): String? {
        // Bound locally: contextExpression is a public property of another module now, so it does
        // not smart-cast.
        val contextExpression = profile.contextExpression
        return when {
            profile.enterTaskIdOrNull == null && profile.exitTaskId == null -> "it has no enter or exit task"
            // A profile with only an exit task runs it when its contexts stop matching.
            profile.enterTaskIdOrNull == null && exitTask == null -> "its exit task ${profile.exitTaskId} is missing"
            // An event fires and is over, so the engine never holds such a profile on.
            profile.enterTaskIdOrNull == null && profile.contexts.any { it.type == ContextType.EVENT } ->
                "it has no enter task, and an event never stops matching, so its exit task never runs"
            profile.enterTaskIdOrNull != null && enterTask == null -> "its enter task ${profile.enterTaskId} is missing"
            contextExpression != null && !contextExpression.isValidForContextCount(profile.contexts.size) ->
                "its context expression is invalid"
            profile.contexts.size > 1 && profile.contexts.indices.any { leftIndex ->
                profile.contexts.drop(leftIndex + 1).any { right ->
                    !contextPairMayOverlap(profile.contexts[leftIndex], right)
                }
            } -> "its context entries contain contradictory conditions"
            profile.lifetime == ProfileLifetime.ONCE && profile.lifetimeConsumed ->
                "its one-shot lifetime has already been consumed"
            profile.lifetime == ProfileLifetime.UNTIL_DATE && profile.expiresAtMs == null ->
                "its lifetime has no expiry"
            profile.lifetime == ProfileLifetime.UNTIL_DATE && nowMs != null &&
                profile.expiresAtMs?.let { expiresAt -> expiresAt <= nowMs } == true ->
                "its lifetime has expired"
            else -> null
        }
    }

    private fun contextsEquivalent(left: Profile, right: Profile): Boolean =
        left.contextExpression == right.contextExpression && canonicalContexts(left) == canonicalContexts(right)

    private fun canonicalContexts(profile: Profile): List<ContextSpec> = profile.contexts
        .sortedWith(compareBy<ContextSpec> { it.type.name }.thenBy { it.config.entries.sortedBy(Map.Entry<String, String>::key).toString() })

    private fun profileMaySatisfy(profile: Profile, guard: ContextSpec): Boolean {
        if (profile.contextExpression != null) return true
        return profile.contexts.none { context -> !contextPairMayOverlap(context, guard) }
    }

    private fun hasRetriggerGuard(profile: Profile, enterTask: Task?, taskById: Map<Long, Task>): Boolean {
        // With no enter task only the exit task runs, and a cooldown never holds that back.
        val guardedTask = if (profile.enterTaskIdOrNull == null) profile.exitTaskId?.let(taskById::get) else enterTask
        if (profile.enterTaskIdOrNull != null && profile.cooldownSec > 0) return true
        if (profile.gracePeriodSec > 0) return true
        if (profile.contexts.any { spec ->
                val dwell = listOf("dwellMillis", "dwellMs", "dwellSeconds", "dwellSec")
                    .firstNotNullOfOrNull { spec.config[it]?.toLongOrNull() }
                dwell != null && dwell > 0L
            }
        ) return true
        return guardedTask?.actions.orEmpty().any { action ->
            action.enabled && (
                action.condition?.trim()?.isNotEmpty() == true ||
                    action.args["idempotent"].equals("true", ignoreCase = true) ||
                    action.args["guard"].equals("true", ignoreCase = true)
                )
        }
    }

    private fun settingWrites(profile: Profile, tasks: List<Task>): List<SettingWrite> =
        AutomationSensitivityRegistry.reachableTasks(profile, tasks)
            .flatMap { task -> task.actions.mapNotNull { action -> action.toSettingWrite(task) } }
            .distinctBy { it.taskId to (it.key to it.automaticallyReversed) }

    private fun directSettingWrites(task: Task?): Set<String> = task
        ?.let { candidate ->
            candidate.actions
                .mapNotNull { action -> action.toSettingWrite(candidate) }
                .mapTo(hashSetOf(), SettingWrite::key)
        }
        ?: emptySet()

    private fun ActionSpec.toSettingWrite(task: Task): SettingWrite? {
        // A step turned off writes nothing, so it can't need a reversal or conflict with another.
        if (!enabled) return null
        val key = when (type) {
            "wifi.toggle" -> "wifi"
            "bluetooth.toggle" -> "bluetooth"
            "brightness.set" -> "brightness"
            "volume.set" -> "volume:${args["stream"].orEmpty().ifBlank { "music" }.lowercase(Locale.US)}"
            "airplane.toggle" -> "airplane"
            "mobile.toggle" -> "mobile"
            "hotspot.toggle" -> "hotspot"
            "screen.timeout" -> "screen_timeout"
            "dnd.set", "zen.rule.set", "zen.rule.clear" -> "dnd"
            "ringer.set" -> "ringer"
            "torch.set" -> "torch"
            "ime.set" -> "ime"
            "screen.off", "wake" -> "screen"
            "tile.set" -> "tile:${args["slot"].orEmpty().ifBlank { "default" }}"
            "state.temporary" -> {
                val target = args["target_action"]?.trim().orEmpty()
                target.takeIf { it in TEMPORARY_TARGET_ACTIONS }?.let {
                    "temporary:$it:${args["key"].orEmpty().ifBlank { "default" }}"
                }
            }
            else -> null
        } ?: return null
        return SettingWrite(
            taskId = task.id,
            key = key,
            automaticallyReversed = type == "state.temporary",
        )
    }

    private fun contextsMayOverlap(left: Profile, right: Profile): Boolean {
        if (left.contexts.isEmpty() || right.contexts.isEmpty()) return false
        // An explicit expression can contain OR/NOT branches, so an inexpensive pairwise proof
        // would risk blocking a satisfiable branch. Leave those cases as warnings only.
        if (left.contextExpression != null || right.contextExpression != null) return true
        // Legacy profiles are implicit ANDs. Any contradictory cross-pair proves that the two
        // profiles cannot be active together; otherwise they remain conservatively overlapping.
        return left.contexts.none { leftSpec ->
            right.contexts.any { rightSpec -> !contextPairMayOverlap(leftSpec, rightSpec) }
        }
    }

    private fun contextPairMayOverlap(left: ContextSpec, right: ContextSpec): Boolean {
        if (left.invert != right.invert) {
            // A positive and a negated copy of the same condition are contradictory. For
            // different conditions, the positive condition can still satisfy the negation.
            return left.copy(invert = false) != right.copy(invert = false)
        }
        if (left.invert && right.invert) return true
        if (left.type != right.type) return true
        return when (left.type) {
            ContextType.APPLICATION -> applicationOverlap(left, right)
            ContextType.TIME -> timeOverlap(left, right)
            ContextType.DAY -> DaySchedule.parse(first(left, "days", "day"))
                .intersect(DaySchedule.parse(first(right, "days", "day"))).isNotEmpty()
            ContextType.LOCATION -> locationOverlap(left, right)
            ContextType.STATE -> stateOverlap(left, right)
            ContextType.EVENT -> eventOverlap(left, right)
            ContextType.PLUGIN -> pluginOverlap(left, right)
        }
    }

    private fun applicationOverlap(left: ContextSpec, right: ContextSpec): Boolean {
        val leftPackages = csv(first(left, "package", "packages", "apps"))
        val rightPackages = csv(first(right, "package", "packages", "apps"))
        if (leftPackages.isNotEmpty() && rightPackages.isNotEmpty() && leftPackages.intersect(rightPackages).isEmpty()) return false
        val leftComponent = first(left, "component", "activity", "class")
        val rightComponent = first(right, "component", "activity", "class")
        return leftComponent.isBlank() || rightComponent.isBlank() || leftComponent == rightComponent ||
            leftComponent.contains('*') || rightComponent.contains('*')
    }

    private fun timeOverlap(left: ContextSpec, right: ContextSpec): Boolean {
        val leftStart = parseClock(first(left, "start", "from")) ?: return true
        val leftEnd = parseClock(first(left, "end", "to")) ?: return true
        val rightStart = parseClock(first(right, "start", "from")) ?: return true
        val rightEnd = parseClock(first(right, "end", "to")) ?: return true
        return (0 until MINUTES_PER_DAY).any { minute ->
            inWindow(minute, leftStart, leftEnd) && inWindow(minute, rightStart, rightEnd)
        }
    }

    private fun locationOverlap(left: ContextSpec, right: ContextSpec): Boolean {
        if (first(left, "outside", "inverted").equals("true", true) ||
            first(right, "outside", "inverted").equals("true", true)
        ) return true
        val leftLat = first(left, "latitude", "lat").toDoubleOrNull() ?: return true
        val leftLon = first(left, "longitude", "lon", "lng").toDoubleOrNull() ?: return true
        val rightLat = first(right, "latitude", "lat").toDoubleOrNull() ?: return true
        val rightLon = first(right, "longitude", "lon", "lng").toDoubleOrNull() ?: return true
        val leftRadius = first(left, "radiusMeters", "radius").toDoubleOrNull() ?: return true
        val rightRadius = first(right, "radiusMeters", "radius").toDoubleOrNull() ?: return true
        return FossGeofenceEvaluator.distanceMeters(leftLat, leftLon, rightLat, rightLon) <= leftRadius + rightRadius
    }

    private fun stateOverlap(left: ContextSpec, right: ContextSpec): Boolean {
        val leftPredicate = statePredicate(left) ?: return true
        val rightPredicate = statePredicate(right) ?: return true
        if (normalizeStateKey(leftPredicate.first) != normalizeStateKey(rightPredicate.first)) return true
        // A Wi-Fi name pattern can match many names, `+5` among them, so it's neither one name
        // nor one number.
        if (leftPredicate.second == "~" || rightPredicate.second == "~") return true
        val leftValue = leftPredicate.third.lowercase(Locale.US)
        val rightValue = rightPredicate.third.lowercase(Locale.US)
        if (leftPredicate.second == "=" && rightPredicate.second == "=" && leftValue != rightValue) return false
        if (leftPredicate.second == "=" && rightPredicate.second == "!=" && leftValue == rightValue) return false
        if (leftPredicate.second == "!=" && rightPredicate.second == "=" && leftValue == rightValue) return false
        val leftNumber = leftValue.toIntOrNull()
        val rightNumber = rightValue.toIntOrNull()
        if (leftNumber != null && rightNumber != null) {
            val leftRange = numericRange(leftPredicate.second, leftNumber)
            val rightRange = numericRange(rightPredicate.second, rightNumber)
            return leftRange.first <= rightRange.second && rightRange.first <= leftRange.second
        }
        return true
    }

    private fun eventOverlap(left: ContextSpec, right: ContextSpec): Boolean {
        val leftEvent = first(left, "event")
        val rightEvent = first(right, "event")
        return leftEvent.isBlank() || rightEvent.isBlank() || leftEvent.equals(rightEvent, true)
    }

    private fun pluginOverlap(left: ContextSpec, right: ContextSpec): Boolean {
        val leftPackage = first(left, "package")
        val rightPackage = first(right, "package")
        if (leftPackage.isNotBlank() && rightPackage.isNotBlank() && !leftPackage.equals(rightPackage, true)) return false
        val leftBundle = first(left, "bundleJson").ifBlank { "{}" }
        val rightBundle = first(right, "bundleJson").ifBlank { "{}" }
        return leftBundle == rightBundle || leftBundle == "{}" || rightBundle == "{}"
    }

    private fun interProfileCycles(profiles: List<Profile>, tasks: List<Task>): List<List<Profile>> {
        val profilesByEnterTask = profiles.groupBy(Profile::enterTaskId)
        val edges = profiles.associateWith { profile ->
            AutomationSensitivityRegistry.reachableTasks(profile, tasks)
                .flatMap { task -> directTaskReferences(task, tasks) }
                .flatMap { target -> profilesByEnterTask[target.id].orEmpty() }
                .filter { it.id != profile.id }
                .distinctBy(Profile::id)
        }
        val cycles = linkedMapOf<String, List<Profile>>()
        fun walk(start: Profile, current: Profile, path: List<Profile>) {
            edges[current].orEmpty().forEach { next ->
                if (next.id == start.id) {
                    val cycle = path
                    val rotations = cycle.indices.map { index -> cycle.drop(index) + cycle.take(index) }
                    val canonical = rotations.minBy { rotation -> rotation.joinToString("\u0000") { it.id.toString() } }
                    cycles[canonical.joinToString(",") { it.id.toString() }] = canonical
                } else if (next !in path && path.size < profiles.size) {
                    walk(start, next, path + next)
                }
            }
        }
        profiles.forEach { walk(it, it, listOf(it)) }
        return cycles.values.toList()
    }

    private fun directTaskReferences(task: Task, tasks: List<Task>): List<Task> {
        val byId = tasks.associateBy(Task::id)
        val byName = tasks.groupBy { it.name.trim().lowercase(Locale.US) }
        return task.actions.filter { it.enabled && it.type == "task.run" }.flatMap { action ->
            val reference = listOf("task", "name", "id")
                .firstNotNullOfOrNull { key -> action.args[key]?.trim()?.takeIf(String::isNotBlank) }
                ?: return@flatMap emptyList()
            if (reference.contains('%') || reference.contains("{{")) return@flatMap emptyList()
            reference.toLongOrNull()?.let { id -> listOfNotNull(byId[id]) }
                ?: byName[reference.lowercase(Locale.US)].orEmpty()
        }
    }

    private fun statePredicate(spec: ContextSpec): Triple<String, String, String>? {
        val predicate = spec.config["predicate"]?.trim().orEmpty()
        if (predicate.isNotBlank()) return parsePredicate(predicate)
        val key = spec.config["key"]?.trim().orEmpty()
        val value = spec.config["value"]?.trim().orEmpty()
        if (key.isBlank() || value.isBlank()) return null
        return Triple(key.lowercase(Locale.US), spec.config["operator"]?.trim().orEmpty().ifBlank { "=" }, value)
    }

    /** [value] read the way matching reads it, so `wifi ~ a=b` is a pattern, not `wifi ~ a` equal to `b`. */
    private fun parsePredicate(value: String): Triple<String, String, String>? {
        val (key, operator, rhs) = parseStatePredicate(value) ?: return null
        if (key.isBlank() || rhs.isBlank()) return null
        return Triple(key.trim().lowercase(Locale.US), operator, rhs)
    }

    private fun numericRange(operator: String, value: Int): Pair<Int, Int> = when (operator) {
        ">=" -> value to Int.MAX_VALUE
        ">" -> (value + 1) to Int.MAX_VALUE
        "<=" -> Int.MIN_VALUE to value
        "<" -> Int.MIN_VALUE to (value - 1)
        else -> value to value
    }

    private fun first(spec: ContextSpec, vararg keys: String): String =
        keys.firstNotNullOfOrNull { spec.config[it]?.trim()?.takeIf(String::isNotBlank) }.orEmpty()

    private fun normalizeStateKey(key: String): String = when (key.lowercase(Locale.US)) {
        "wifissid", "wifi_network", "wifi_connected" -> "wifi"
        "battery_level", "batterypercent" -> "battery"
        "headphones", "headphones_connected" -> "headset"
        else -> key.lowercase(Locale.US)
    }

    private fun csv(value: String): Set<String> = value.split(',', ';')
        .map { it.trim().lowercase(Locale.US) }
        .filter(String::isNotBlank)
        .toSet()

    private fun parseClock(value: String): Int? {
        val parts = value.split(":")
        if (parts.size !in 1..2) return null
        val hour = parts[0].toIntOrNull() ?: return null
        val minute = parts.getOrNull(1)?.toIntOrNull() ?: 0
        return (hour * 60 + minute).takeIf { hour in 0..23 && minute in 0..59 }
    }

    private fun inWindow(minute: Int, start: Int, end: Int): Boolean =
        if (start <= end) minute in start..end else minute >= start || minute <= end

    private data class SettingWrite(
        val taskId: Long,
        val key: String,
        val automaticallyReversed: Boolean,
    )

    private val TEMPORARY_TARGET_ACTIONS = setOf("brightness.set", "volume.set", "ringer.set", "dnd.set")
    private const val MAX_INVARIANT_PROFILE_NAMES = 8
    private const val MINUTES_PER_DAY = 24 * 60
}
