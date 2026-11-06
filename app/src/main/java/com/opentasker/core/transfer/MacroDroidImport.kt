package com.opentasker.core.transfer

import com.opentasker.core.contexts.DaySchedule
import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.ContextBooleanOperator
import com.opentasker.core.model.ContextExpressionNode
import com.opentasker.core.model.ContextSpec
import com.opentasker.core.model.ContextType
import com.opentasker.core.model.Profile
import com.opentasker.core.model.Task
import com.opentasker.core.model.Variable
import com.opentasker.core.model.VariableNamePolicy
import com.opentasker.core.validation.InputValidation
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import java.util.Locale

const val MACRODROID_UNSUPPORTED_ACTION_ID = "macrodroid.unsupported"

data class MacroDroidImportReport(
    val bundle: OpenTaskerBundle,
    val sourceMacroCount: Int,
    val sourceVariableCount: Int,
    val sourceActionCount: Int,
    val sourceTriggerCount: Int,
    val sourceConstraintCount: Int,
    val mappedActions: List<MacroDroidMappedAction>,
    val unsupportedActions: List<MacroDroidUnsupportedAction>,
    val mappedTriggers: List<MacroDroidMappedTrigger>,
    val unsupportedTriggers: List<MacroDroidUnsupportedTrigger>,
    val unsupportedConstraints: List<MacroDroidUnsupportedConstraint>,
    val warnings: List<String> = emptyList(),
    val lossyWarnings: List<String> = emptyList(),
)

data class MacroDroidMappedAction(
    val macroName: String,
    val classType: String,
    val actionIndex: Int,
    val openTaskerActionIds: List<String>,
)

data class MacroDroidUnsupportedAction(
    val macroName: String,
    val classType: String,
    val actionIndex: Int,
    val reason: String,
)

data class MacroDroidMappedTrigger(
    val macroName: String,
    val classType: String,
    val triggerIndex: Int,
    val openTaskerContextTypes: List<ContextType>,
)

data class MacroDroidUnsupportedTrigger(
    val macroName: String,
    val classType: String,
    val triggerIndex: Int,
    val reason: String,
)

data class MacroDroidUnsupportedConstraint(
    val owner: String,
    val classType: String,
    val reason: String,
)

/** Converts MacroDroid full-backup `.mdr` and single-macro `.macro` JSON exports. */
object MacroDroidImporter {
    private val json = Json {
        ignoreUnknownKeys = true
        allowTrailingComma = true
    }

    fun parse(
        rawJson: String,
        appVersion: String,
        importedAtEpochMs: Long = System.currentTimeMillis(),
    ): MacroDroidImportReport = parse(
        rawJson = rawJson,
        appVersion = appVersion,
        importedAtEpochMs = importedAtEpochMs,
        budget = ImportResourceBudget.Default,
        sourceBudget = ImportResourceBudget.MacroDroidSource,
    )

    internal fun parse(
        rawJson: String,
        appVersion: String,
        importedAtEpochMs: Long,
        budget: ImportResourceBudget,
        sourceBudget: ImportResourceBudget = budget,
    ): MacroDroidImportReport {
        val normalized = rawJson.removePrefix(BYTE_ORDER_MARK).trim()
        require(normalized.isNotEmpty()) { "MacroDroid import is empty." }
        // Checked before the scan: a file that was never JSON otherwise fails on token or depth
        // limits, which reads as "too big" when it's the wrong kind of file.
        require(normalized.startsWith("{")) { "MacroDroid import root must be a JSON object." }
        ImportResourceGuard.requireJsonPreflight(normalized, sourceBudget)

        val root = json.parseToJsonElement(normalized) as? JsonObject
            ?: throw IllegalArgumentException("MacroDroid import root must be a JSON object.")
        val macros = root.macroObjects()
        require(macros.isNotEmpty()) { "MacroDroid import contains no macros." }
        val globalVariables = root.objectArray("variables")
        val localVariableCount = macros.sumOf { it.objectArray("localVariables").size }
        val sourceActionCount = macros.sumOf { it.objectArray("m_actionList").size }
        val sourceTriggerCount = macros.sumOf { it.objectArray("m_triggerList").size }
        val sourceConstraintCount = macros.sumOf { macro ->
            macro.objectArray("m_constraintList").size +
                macro.objectArray("m_actionList").sumOf { it.objectArray("m_constraintList").size } +
                macro.objectArray("m_triggerList").sumOf { it.objectArray("m_constraintList").size }
        }
        ImportResourceGuard.requireSourceCounts(
            entities = macros.size.toLong() * 2L + globalVariables.size + localVariableCount,
            actions = sourceActionCount.toLong(),
            contexts = sourceTriggerCount.toLong() + sourceConstraintCount,
            budget = budget,
        )

        val warnings = mutableListOf<String>()
        val lossyWarnings = mutableListOf<String>()
        val mappedActions = mutableListOf<MacroDroidMappedAction>()
        val unsupportedActions = mutableListOf<MacroDroidUnsupportedAction>()
        val mappedTriggers = mutableListOf<MacroDroidMappedTrigger>()
        val unsupportedTriggers = mutableListOf<MacroDroidUnsupportedTrigger>()
        val unsupportedConstraints = mutableListOf<MacroDroidUnsupportedConstraint>()
        val usedTaskIds = mutableSetOf<Long>()
        val usedProfileIds = mutableSetOf<Long>()

        val tasks = mutableListOf<Task>()
        val profiles = mutableListOf<Profile>()
        macros.forEachIndexed { macroIndex, macro ->
            val sourceName = macro.string("m_name").ifBlank { "MacroDroid macro ${macroIndex + 1}" }
            val name = sourceName.take(InputValidation.MAX_NAME_LENGTH)
            if (name != sourceName) {
                lossyWarnings += "Macro '$sourceName' had a long name. It was shortened to '$name'."
            }
            val guid = macro.long("m_GUID")
            val taskId = uniquePositiveId(guid, macroIndex, usedTaskIds)
            val profileId = uniquePositiveId(guid, macroIndex, usedProfileIds)
            val actionObjects = macro.objectArray("m_actionList")
            val actions = actionObjects.flatMapIndexed { actionIndex, action ->
                parseAction(
                    macroName = name,
                    action = action,
                    actionIndex = actionIndex,
                    mappedActions = mappedActions,
                    unsupportedActions = unsupportedActions,
                    unsupportedConstraints = unsupportedConstraints,
                    lossyWarnings = lossyWarnings,
                )
            }.ifEmpty {
                val reason = "The source macro has no actions."
                unsupportedActions += MacroDroidUnsupportedAction(name, "EmptyActionList", 0, reason)
                lossyWarnings += "Macro '$name' has no actions. A disabled placeholder step was added."
                listOf(unsupportedAction("EmptyActionList", reason))
            }
            tasks += Task(id = taskId, name = name, actions = actions)

            val contexts = mutableListOf<ContextSpec>()
            val triggerNodes = mutableListOf<ContextExpressionNode>()
            val triggerObjects = macro.objectArray("m_triggerList")
            triggerObjects.forEachIndexed { triggerIndex, trigger ->
                val mapping = parseTrigger(
                    macroName = name,
                    trigger = trigger,
                    triggerIndex = triggerIndex,
                    mappedTriggers = mappedTriggers,
                    unsupportedTriggers = unsupportedTriggers,
                    unsupportedConstraints = unsupportedConstraints,
                    lossyWarnings = lossyWarnings,
                )
                triggerNodes += contexts.appendAsAndGroup(mapping.contexts)
            }
            if (triggerObjects.isEmpty()) {
                val reason = "The source macro has no triggers."
                unsupportedTriggers += MacroDroidUnsupportedTrigger(name, "EmptyTriggerList", 0, reason)
                lossyWarnings += "Macro '$name' has no triggers. A non-matching placeholder context was added."
                triggerNodes += contexts.appendAsAndGroup(listOf(unsupportedContext("EmptyTriggerList")))
            }

            val constraintNodes = macro.objectArray("m_constraintList").mapIndexed { constraintIndex, constraint ->
                val classType = constraint.classType("UnknownConstraint")
                val owner = "Macro '$name' constraint ${constraintIndex + 1}"
                unsupportedConstraints += MacroDroidUnsupportedConstraint(
                    owner = owner,
                    classType = classType,
                    reason = "MacroDroid constraints do not have a safe OpenTasker mapping yet.",
                )
                lossyWarnings += "$owner ($classType) was preserved as a non-matching placeholder context."
                contexts.appendAsAndGroup(listOf(unsupportedContext(classType)))
            }

            val triggerExpression = triggerNodes.asGroup(ContextBooleanOperator.OR)
            val constraintExpression = constraintNodes.asGroup(
                if (macro.boolean("m_isOrCondition")) ContextBooleanOperator.OR else ContextBooleanOperator.AND,
            )
            val expression = listOfNotNull(triggerExpression, constraintExpression)
                .asGroup(ContextBooleanOperator.AND)
                ?.takeIf { contexts.size > 1 }

            val description = macro.string("m_description")
            if (description.isNotBlank()) {
                lossyWarnings += "Macro '$name' has a description that is not represented on OpenTasker profiles."
            }
            val localVariables = macro.objectArray("localVariables")
            if (localVariables.isNotEmpty()) {
                lossyWarnings += "Macro '$name' has ${localVariables.size} local variable(s). Initial local values were not imported."
            }
            profiles += Profile(
                id = profileId,
                name = name,
                enabled = false,
                contexts = contexts,
                enterTaskId = taskId,
                group = macro.string("m_category").takeIf(String::isNotBlank)?.take(InputValidation.MAX_NAME_LENGTH),
                requiresRiskAcknowledgement = true,
                contextExpression = expression,
            )
        }

        val variables = parseGlobalVariables(globalVariables, lossyWarnings)
        val bundle = OpenTaskerBundleCodec.build(
            appVersion = appVersion,
            exportedAtEpochMs = importedAtEpochMs,
            profiles = profiles,
            tasks = tasks,
            variables = variables,
            name = "MacroDroid Import",
            description = "Converted from a MacroDroid export. Review every placeholder and warning before enabling imported profiles.",
        )
        ImportResourceGuard.requireBundle(bundle, budget)
        val mergedWarnings = (bundle.metadata.warnings + warnings + lossyWarnings).distinct()
        val reportBundle = bundle.copy(metadata = bundle.metadata.copy(warnings = mergedWarnings))

        return MacroDroidImportReport(
            bundle = reportBundle,
            sourceMacroCount = macros.size,
            sourceVariableCount = globalVariables.size + localVariableCount,
            sourceActionCount = sourceActionCount,
            sourceTriggerCount = sourceTriggerCount,
            sourceConstraintCount = sourceConstraintCount,
            mappedActions = mappedActions,
            unsupportedActions = unsupportedActions,
            mappedTriggers = mappedTriggers,
            unsupportedTriggers = unsupportedTriggers,
            unsupportedConstraints = unsupportedConstraints,
            warnings = warnings.distinct(),
            lossyWarnings = lossyWarnings.distinct(),
        )
    }

    private fun parseAction(
        macroName: String,
        action: JsonObject,
        actionIndex: Int,
        mappedActions: MutableList<MacroDroidMappedAction>,
        unsupportedActions: MutableList<MacroDroidUnsupportedAction>,
        unsupportedConstraints: MutableList<MacroDroidUnsupportedConstraint>,
        lossyWarnings: MutableList<String>,
    ): List<ActionSpec> {
        val classType = action.classType("UnknownAction")
        val constraints = action.objectArray("m_constraintList")
        constraints.forEachIndexed { index, constraint ->
            unsupportedConstraints += MacroDroidUnsupportedConstraint(
                owner = "Macro '$macroName' action ${actionIndex + 1} constraint ${index + 1}",
                classType = constraint.classType("UnknownConstraint"),
                reason = "Action-level MacroDroid constraints cannot be represented safely.",
            )
        }
        val refusalReason = when {
            action.boolean("m_isDisabled") -> "The source action is disabled."
            constraints.isNotEmpty() -> "The source action has unsupported action-level constraints."
            else -> null
        }
        val mapping = if (refusalReason == null) mapAction(action, actionIndex) else null
        if (mapping == null) {
            val reason = refusalReason ?: "No reviewed OpenTasker mapping exists for this class."
            unsupportedActions += MacroDroidUnsupportedAction(macroName, classType, actionIndex, reason)
            lossyWarnings += "Macro '$macroName' action ${actionIndex + 1} ($classType) is an unsupported placeholder. $reason"
            return listOf(unsupportedAction(classType, reason))
        }

        mappedActions += MacroDroidMappedAction(
            macroName = macroName,
            classType = classType,
            actionIndex = actionIndex,
            openTaskerActionIds = mapping.actions.map(ActionSpec::type),
        )
        mapping.lossyWarning?.let {
            lossyWarnings += "Macro '$macroName' action ${actionIndex + 1} ($classType): $it."
        }
        return mapping.actions
    }

    private fun mapAction(action: JsonObject, actionIndex: Int): ActionMapping? = when (action.classType()) {
        "PauseAction" -> {
            val millis = (action.long("m_delayInSeconds") ?: 0L) * 1_000L +
                (action.long("m_delayInMilliSeconds") ?: 0L)
            ActionMapping(
                actions = listOf(
                    ActionSpec(
                        type = "flow.wait",
                        label = "MacroDroid pause",
                        args = mapOf("millis" to millis.coerceAtLeast(1L).toString()),
                    ),
                ),
                lossyWarning = if (action.boolean("m_useAlarm")) {
                    "the exact-alarm wake behavior was not imported"
                } else {
                    null
                },
            )
        }
        "SetVariableAction" -> mapSetVariableAction(action)
        "ClipboardAction" -> {
            val text = action.string("m_clipboardText")
            if (text.isEmpty()) return null
            ActionMapping(
                actions = listOf(
                    ActionSpec(
                        type = "clipboard.set",
                        label = "MacroDroid fill clipboard",
                        args = mapOf("text" to text),
                    ),
                ),
                lossyWarning = if (MACRODROID_MAGIC_TEXT.containsMatchIn(text)) {
                    "its magic text was kept as written, and OpenTasker won't fill it in"
                } else {
                    null
                },
            )
        }
        "SetAirplaneModeAction" -> {
            val state = when (action.int("m_state")) {
                0 -> "on"
                1 -> "off"
                2 -> "toggle"
                else -> return null
            }
            val omittedOptions = action.boolean("m_keepWifiOn") ||
                action.boolean("m_keepBluetoothOn") ||
                (action.int("mechanismOption") ?: 0) != 0
            ActionMapping(
                actions = listOf(
                    ActionSpec(
                        type = "airplane.toggle",
                        label = "MacroDroid airplane mode",
                        args = mapOf("state" to state),
                    ),
                ),
                lossyWarning = if (omittedOptions) {
                    "the MacroDroid mechanism and keep-Wi-Fi/Bluetooth options were not imported"
                } else {
                    null
                },
            )
        }
        "LoopAction" -> {
            if ((action.int("m_option") ?: 0) != 0 || action["m_fixedOptionVariable"].isPresentValue()) return null
            val count = action.int("m_fixedOptionCount") ?: return null
            if (count !in 1..MAX_FIXED_LOOP_COUNT) return null
            val arrayName = "macroLoop${actionIndex + 1}"
            ActionMapping(
                actions = listOf(
                    ActionSpec(
                        type = "text.split",
                        label = "MacroDroid fixed loop values",
                        args = mapOf(
                            "source" to (1..count).joinToString(LOOP_DELIMITER),
                            "delimiter" to LOOP_DELIMITER,
                            "var" to arrayName,
                        ),
                    ),
                    ActionSpec(
                        type = "flow.foreach",
                        label = "MacroDroid fixed loop",
                        args = mapOf("list" to arrayName, "var" to "macroLoopIndex${actionIndex + 1}"),
                    ),
                ),
                lossyWarning = "the loop counter uses an OpenTasker local variable named macroLoopIndex${actionIndex + 1}",
            )
        }
        "EndLoopAction" -> ActionMapping(
            actions = listOf(ActionSpec(type = "flow.endfor", label = "MacroDroid end loop")),
        )
        "ControlMediaAction" -> {
            val target = when (action.string("m_option").lowercase(Locale.US)) {
                "next" -> "track.next"
                "previous" -> "track.previous"
                else -> return null
            }
            ActionMapping(
                actions = listOf(ActionSpec(type = target, label = "MacroDroid media control")),
                lossyWarning = action.string("m_packageName").takeIf(String::isNotBlank)?.let {
                    "the source app target '$it' was not imported"
                },
            )
        }
        else -> null
    }

    private fun mapSetVariableAction(action: JsonObject): ActionMapping? {
        if (
            action.boolean("m_userPrompt") ||
            action.boolean("m_intValueIncrement") ||
            action.boolean("m_intValueDecrement") ||
            action.boolean("m_booleanInvert") ||
            action.boolean("m_intRandom") ||
            action.boolean("m_intExpression") ||
            action.string("m_expression").isNotBlank() ||
            action["m_otherBooleanVariable"].isPresentValue()
        ) return null

        val variable = action.objectValue("m_variable") ?: return null
        val mappedName = mappedVariableName(variable) ?: return null
        val value = when (variable.int("m_type")) {
            0 -> action.boolean("m_newBooleanValue").toString()
            1 -> (action.long("m_newIntValue") ?: 0L).toString()
            2 -> action.string("m_newStringValue")
            3 -> (action.double("m_newDoubleValue") ?: 0.0).toString()
            else -> return null
        }
        return ActionMapping(
            actions = listOf(
                ActionSpec(
                    type = "var.set",
                    label = "MacroDroid set variable",
                    args = mapOf("name" to mappedName, "value" to value),
                ),
            ),
            lossyWarning = variable.string("m_name").takeIf { it != mappedName }?.let {
                "variable '$it' was normalized to '$mappedName'"
            },
        )
    }

    private fun parseTrigger(
        macroName: String,
        trigger: JsonObject,
        triggerIndex: Int,
        mappedTriggers: MutableList<MacroDroidMappedTrigger>,
        unsupportedTriggers: MutableList<MacroDroidUnsupportedTrigger>,
        unsupportedConstraints: MutableList<MacroDroidUnsupportedConstraint>,
        lossyWarnings: MutableList<String>,
    ): TriggerMapping {
        val classType = trigger.classType("UnknownTrigger")
        val constraints = trigger.objectArray("m_constraintList")
        constraints.forEachIndexed { index, constraint ->
            unsupportedConstraints += MacroDroidUnsupportedConstraint(
                owner = "Macro '$macroName' trigger ${triggerIndex + 1} constraint ${index + 1}",
                classType = constraint.classType("UnknownConstraint"),
                reason = "Trigger-level MacroDroid constraints cannot be represented safely.",
            )
        }
        val refusalReason = when {
            trigger.boolean("m_isDisabled") -> "The source trigger is disabled."
            constraints.isNotEmpty() -> "The source trigger has unsupported trigger-level constraints."
            else -> null
        }
        val mapping = if (refusalReason == null) mapTrigger(trigger) else null
        if (mapping == null) {
            val reason = refusalReason ?: "No reviewed OpenTasker mapping exists for this class."
            unsupportedTriggers += MacroDroidUnsupportedTrigger(macroName, classType, triggerIndex, reason)
            lossyWarnings += "Macro '$macroName' trigger ${triggerIndex + 1} ($classType) is a non-matching placeholder. $reason"
            return TriggerMapping(listOf(unsupportedContext(classType)))
        }

        mappedTriggers += MacroDroidMappedTrigger(
            macroName = macroName,
            classType = classType,
            triggerIndex = triggerIndex,
            openTaskerContextTypes = mapping.contexts.map(ContextSpec::type),
        )
        mapping.lossyWarning?.let {
            lossyWarnings += "Macro '$macroName' trigger ${triggerIndex + 1} ($classType): $it."
        }
        return mapping
    }

    private fun mapTrigger(trigger: JsonObject): TriggerMapping? = when (trigger.classType()) {
        "BootTrigger" -> TriggerMapping(
            contexts = listOf(ContextSpec(ContextType.EVENT, mapOf("event" to "boot_completed"))),
        )
        "BatteryLevelTrigger" -> {
            if ((trigger.int("m_option") ?: 0) != 0) return null
            val level = trigger.int("m_batteryLevel")?.takeIf { it in 0..100 } ?: return null
            TriggerMapping(
                contexts = listOf(
                    ContextSpec(
                        ContextType.STATE,
                        mapOf(
                            "key" to "battery_level",
                            "operator" to if (trigger.boolean("m_decreasesTo")) "<=" else ">=",
                            "value" to level.toString(),
                        ),
                    ),
                ),
                lossyWarning = "a threshold crossing was converted to a battery state predicate",
            )
        }
        "ScreenOnOffTrigger" -> TriggerMapping(
            contexts = listOf(
                ContextSpec(
                    ContextType.STATE,
                    mapOf("key" to "screen", "value" to if (trigger.boolean("m_screenOn")) "on" else "off"),
                ),
            ),
            lossyWarning = "a screen transition was converted to a screen state predicate",
        )
        "DeviceUnlockedTrigger" -> TriggerMapping(
            contexts = listOf(ContextSpec(ContextType.STATE, mapOf("key" to "unlocked", "value" to "true"))),
            lossyWarning = "an unlock transition was converted to an unlocked state predicate",
        )
        "TimerTrigger" -> mapTimerTrigger(trigger)
        else -> null
    }

    private fun mapTimerTrigger(trigger: JsonObject): TriggerMapping? {
        val hour = trigger.int("m_hour")?.takeIf { it in 0..23 } ?: return null
        val minute = trigger.int("m_minute")?.takeIf { it in 0..59 } ?: return null
        val startMinutes = hour * 60 + minute
        val endMinutes = (startMinutes + 1) % (24 * 60)
        val contexts = mutableListOf(
            ContextSpec(
                ContextType.TIME,
                mapOf("start" to clock(startMinutes), "end" to clock(endMinutes)),
            ),
        )
        val selectedDays = trigger.booleanArray("m_daysOfWeek")
            .mapIndexedNotNull { index, selected -> DaySchedule.orderedDays.getOrNull(index)?.takeIf { selected } }
        if (selectedDays.isNotEmpty() && selectedDays.size < DaySchedule.orderedDays.size) {
            contexts += ContextSpec(ContextType.DAY, mapOf("days" to selectedDays.joinToString(",")))
        }
        val warningParts = buildList {
            if ((trigger.int("m_second") ?: 0) != 0) add("seconds were rounded down to the minute")
            if (selectedDays.isEmpty()) add("no selected weekdays were present, so the timer was imported as daily")
        }
        return TriggerMapping(
            contexts = contexts,
            lossyWarning = warningParts.joinToString("; ").takeIf(String::isNotBlank),
        )
    }

    private fun parseGlobalVariables(
        source: List<JsonObject>,
        lossyWarnings: MutableList<String>,
    ): List<Variable> {
        val seenNames = mutableSetOf<String>()
        return source.mapIndexedNotNull { index, variable ->
            val rawName = variable.string("m_name")
            val name = VariableNamePolicy.promoteToGlobal(rawName)
            if (name == null) {
                lossyWarnings += "MacroDroid global variable ${index + 1} ('$rawName') has an invalid name and was not imported."
                return@mapIndexedNotNull null
            }
            if (!seenNames.add(name)) {
                lossyWarnings += "MacroDroid global variable '$rawName' maps to duplicate name '$name' and was not imported twice."
                return@mapIndexedNotNull null
            }
            val value = when (variable.int("m_type")) {
                0 -> variable.boolean("m_booleanValue").toString()
                1 -> (variable.long("m_intValue") ?: 0L).toString()
                2 -> variable.string("m_stringValue")
                3 -> (variable.double("m_decimalValue") ?: 0.0).toString()
                else -> {
                    lossyWarnings += "MacroDroid global variable '$rawName' uses an unsupported dictionary or array type."
                    return@mapIndexedNotNull null
                }
            }
            if (rawName != name) {
                lossyWarnings += "MacroDroid global variable '$rawName' was normalized to OpenTasker global '$name'."
            }
            Variable(name = name, value = value, isGlobal = true)
        }
    }

    private fun mappedVariableName(variable: JsonObject): String? {
        val rawName = variable.string("m_name")
        val normalized = VariableNamePolicy.normalize(rawName) ?: return null
        return if (variable.boolean("isLocal")) {
            normalized.lowercase(Locale.US)
        } else {
            VariableNamePolicy.promoteToGlobal(normalized)
        }
    }

    private fun unsupportedAction(classType: String, reason: String): ActionSpec = ActionSpec(
        type = MACRODROID_UNSUPPORTED_ACTION_ID,
        label = "Unsupported MacroDroid action $classType",
        args = mapOf(
            "classType" to classType,
            "summary" to reason,
        ),
    )

    private fun unsupportedContext(classType: String): ContextSpec = ContextSpec(
        type = ContextType.EVENT,
        config = mapOf(
            "event" to UNSUPPORTED_EVENT_NAME,
            "sourceClass" to classType,
        ),
    )

    private fun MutableList<ContextSpec>.appendAsAndGroup(values: List<ContextSpec>): ContextExpressionNode {
        val start = size
        addAll(values)
        val leaves = values.indices.map { ContextExpressionNode.leaf(start + it) }
        return leaves.asGroup(ContextBooleanOperator.AND) ?: error("Context mapping must contain a context")
    }

    private fun List<ContextExpressionNode>.asGroup(operator: ContextBooleanOperator): ContextExpressionNode? = when (size) {
        0 -> null
        1 -> first()
        else -> ContextExpressionNode.group(operator, this)
    }

    private fun uniquePositiveId(source: Long?, index: Int, used: MutableSet<Long>): Long {
        var candidate = source?.and(Long.MAX_VALUE)?.takeIf { it > 0L } ?: (index + 1L)
        while (!used.add(candidate)) {
            candidate = if (candidate == Long.MAX_VALUE) 1L else candidate + 1L
        }
        return candidate
    }

    private fun JsonObject.macroObjects(): List<JsonObject> {
        val list = this["macroList"]
        if (list != null && list !is JsonNull) return objectArray("macroList")
        val macro = objectValue("macro")
        return listOfNotNull(macro)
    }

    private fun JsonObject.objectArray(key: String): List<JsonObject> {
        val value = this[key] ?: return emptyList()
        if (value is JsonNull) return emptyList()
        val array = value as? JsonArray
            ?: throw IllegalArgumentException("MacroDroid field '$key' must be an array.")
        return array.mapIndexed { index, element ->
            element as? JsonObject
                ?: throw IllegalArgumentException("MacroDroid field '$key' item ${index + 1} must be an object.")
        }
    }

    private fun JsonObject.objectValue(key: String): JsonObject? = this[key] as? JsonObject

    private fun JsonObject.classType(fallback: String = ""): String = string("m_classType").ifBlank { fallback }

    private fun JsonObject.string(key: String): String =
        (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()

    private fun JsonObject.boolean(key: String): Boolean {
        val value = this[key] as? JsonPrimitive ?: return false
        return value.booleanOrNull ?: value.contentOrNull?.toBooleanStrictOrNull() ?: false
    }

    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

    private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull

    private fun JsonObject.double(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull

    private fun JsonObject.booleanArray(key: String): List<Boolean> =
        (this[key] as? JsonArray).orEmpty().map { (it as? JsonPrimitive)?.booleanOrNull ?: false }

    private fun Any?.isPresentValue(): Boolean = this != null && this !is JsonNull

    private fun clock(totalMinutes: Int): String = String.format(
        Locale.US,
        "%02d:%02d",
        totalMinutes / 60,
        totalMinutes % 60,
    )

    private data class ActionMapping(
        val actions: List<ActionSpec>,
        val lossyWarning: String? = null,
    )

    private data class TriggerMapping(
        val contexts: List<ContextSpec>,
        val lossyWarning: String? = null,
    )

    private const val BYTE_ORDER_MARK = "\uFEFF"
    private const val LOOP_DELIMITER = "|"
    private const val MAX_FIXED_LOOP_COUNT = 1_000
    private const val UNSUPPORTED_EVENT_NAME = "macrodroid_unsupported"
    private val MACRODROID_MAGIC_TEXT = Regex("""\[[^\]\s]+]|\{l?v=[^}]+}""")
}
