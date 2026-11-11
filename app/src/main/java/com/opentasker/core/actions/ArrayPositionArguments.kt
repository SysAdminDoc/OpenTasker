package com.opentasker.core.actions

import com.opentasker.core.engine.variables.VariableExpander

/**
 * Array Push and Array Pop take a blank position as 1. A position written as a variable that
 * turns out empty would quietly use the first element, and one that's unset stays as written,
 * so runs and Preflight stop the step on either, the way Tasker stops on a position that isn't
 * a number.
 */
internal object ArrayPositionArguments {
    private val actions = setOf("array.push", "array.pop")

    fun isPosition(actionType: String, argumentName: String): Boolean = actionType in actions && argumentName == "position"

    fun emptyFailure(actionType: String, argumentName: String, rawValue: String, expanded: String): String? =
        "position ${rawValue.trim()} came out empty, so the step can't tell which element to use"
            .takeIf {
                isPosition(actionType, argumentName) && rawValue.isNotBlank() &&
                    (expanded.isBlank() || VariableExpander.keptUnset(rawValue, expanded))
            }
}
