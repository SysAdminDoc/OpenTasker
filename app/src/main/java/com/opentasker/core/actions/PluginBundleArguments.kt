package com.opentasker.core.actions

import com.opentasker.core.plugins.locale.LocalePluginBundleCodec
import com.opentasker.core.plugins.locale.LocalePluginContract
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A Locale plugin's bundle is JSON. Expanded as one string, a variable holding a quote broke the JSON
 * or added a key the step never had, so runs and Preflight expand each string value on its own and
 * write the JSON back, with the keys as written. A value is only text, so a leading `(a) ? b : c` in it
 * isn't run as a conditional. A value written without quotes, like `{"n": %n}`, fills in as a number,
 * true, false or null when that's what the variable holds, and as text otherwise, never as more JSON.
 * A bundle that names Tasker's VARIABLE_REPLACE_KEYS gets only those keys filled in, as Tasker does, so
 * a `%` the plugin meant literally in another key stays as it is. Without that list every key but the
 * host's own, like the `%name` lines of RELEVANT_VARIABLES, is filled in. A bundle that isn't a JSON
 * object as written, is past the bundle size limit or nests deeper than a typed array returns null and
 * nothing in it is filled in: text that only becomes JSON once a variable is in, like a whole bundle
 * held in `%payload`, would let the variable write the bundle. The plugin action then refuses it.
 */
internal object PluginBundleArguments {
    private val actions = setOf("plugin.locale.fire", "plugin.locale.query")

    const val UNREADABLE_WARNING =
        "The plugin settings aren't a JSON object OpenTasker can read, so no variables in them are filled in."

    fun applies(actionType: String, argumentName: String): Boolean =
        argumentName == "bundleJson" && actionType in actions

    fun expand(raw: String, expandText: (String) -> String): String? {
        if (raw.toByteArray(Charsets.UTF_8).size > LocalePluginContract.MAX_BUNDLE_JSON_BYTES) return null
        if (LocalePluginBundleCodec.jsonNestingDepth(raw) > LocalePluginContract.MAX_BUNDLE_DEPTH) return null
        val bundle = try {
            Json.parseToJsonElement(raw.trim().ifBlank { "{}" }) as? JsonObject
        } catch (_: IllegalArgumentException) {
            null
        } ?: return null
        val replaceKeys = (bundle[LocalePluginContract.EXTRA_VARIABLE_REPLACE_KEYS] as? JsonPrimitive)
            ?.takeIf { it.isString }
            ?.content
            ?.split(' ')
            ?.filter(String::isNotEmpty)
            ?.toSet()
        return JsonObject(
            bundle.mapValues { (key, value) ->
                val fills = if (replaceKeys != null) key in replaceKeys else HOST_KEY_PREFIXES.none(key::startsWith)
                expandStrings(value, if (fills) expandText else { text: String -> text })
            },
        ).toString()
    }

    private val HOST_KEY_PREFIXES = listOf("net.dinglisch.android.tasker.", "com.twofortyfouram.locale.intent.extra.")
    private val JSON_KEYWORDS = setOf("true", "false", "null")
    private val JSON_NUMBER = Regex("""-?(0|[1-9]\d*)(\.\d+)?([eE][+-]?\d+)?""")

    /** Strings at any depth: a plain value, a typed slot like `{"int":"%count"}`, or the items of a string array. */
    private fun expandStrings(value: JsonElement, expandText: (String) -> String): JsonElement = when (value) {
        is JsonPrimitive -> when {
            value.isString -> JsonPrimitive(expandText(value.content))
            isJsonLiteral(value.content) -> value
            // The parser takes an unquoted token like `%count` as a literal, which JSON doesn't allow.
            else -> expandText(value.content).let { text -> if (isJsonLiteral(text.trim())) literal(text.trim()) else JsonPrimitive(text) }
        }
        is JsonObject -> JsonObject(value.mapValues { (_, inner) -> expandStrings(inner, expandText) })
        is JsonArray -> JsonArray(value.map { expandStrings(it, expandText) })
    }

    private fun isJsonLiteral(text: String): Boolean = text in JSON_KEYWORDS || JSON_NUMBER.matches(text)

    private fun literal(text: String): JsonPrimitive = when (text) {
        "true" -> JsonPrimitive(true)
        "false" -> JsonPrimitive(false)
        "null" -> JsonNull
        else -> Json.parseToJsonElement(text) as JsonPrimitive
    }
}
