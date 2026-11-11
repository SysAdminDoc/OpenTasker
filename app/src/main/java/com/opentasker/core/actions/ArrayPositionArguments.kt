package com.opentasker.core.actions

/**
 * Array Push and Array Pop take a blank position as 1. A position written as a variable that
 * turns out unset expands to blank too, and would quietly use the first element, so runs and
 * Preflight stop the step instead, the way Tasker stops on a position that isn't a number.
 */
internal object ArrayPositionArguments {
    private val actions = setOf("array.push", "array.pop")

    fun emptyFailure(actionType: String, argumentName: String, rawValue: String, expanded: String): String? =
        "position ${rawValue.trim()} came out empty, so the step can't tell which element to use"
            .takeIf { actionType in actions && argumentName == "position" && rawValue.isNotBlank() && expanded.isBlank() }
}
