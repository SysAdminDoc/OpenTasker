package com.opentasker.core.actions

/**
 * Arguments that hold one entry per line: Send Intent's extras and an HTTP request's headers and
 * query. Expanded as one string, a variable holding a line break added an entry the step never
 * listed, so runs and Preflight expand each written line on its own and stop the step when one
 * comes out as more than one line.
 */
internal object LineListArguments {
    const val HEADER_LINE_BREAK_FAILURE = "a variable in headers expanded to a line break, which would add a header the step doesn't list"
    const val QUERY_LINE_BREAK_FAILURE = "a variable in query expanded to a line break, which would add a query parameter the step doesn't list"

    private val httpFields = mapOf("headers" to HEADER_LINE_BREAK_FAILURE, "query" to QUERY_LINE_BREAK_FAILURE)
    private val fields = mapOf(
        "intent.launch" to mapOf("extras" to IntentExtrasLines.LINE_BREAK_FAILURE),
        // http.get and http.post hand their arguments to http.request unchanged.
        HttpRequestAction.ID to httpFields,
        "http.get" to httpFields,
        "http.post" to httpFields,
    )

    fun applies(actionType: String, argumentName: String): Boolean =
        fields[actionType]?.containsKey(argumentName) == true

    fun lineBreakFailure(actionType: String, argumentName: String, expandedLines: List<String>): String? =
        fields[actionType]?.get(argumentName)
            ?.takeIf { expandedLines.any { line -> line.any { it == '\n' || it == '\r' } } }
}
