package com.opentasker.core.plugins.locale

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import com.opentasker.core.apps.PackageNamePolicy
import com.opentasker.core.apps.TargetSignerCheck
import com.opentasker.core.apps.TargetSignerGuard
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import java.util.LinkedHashMap
import kotlin.coroutines.resume

object LocalePluginContract {
    const val ACTION_EDIT_SETTING = "com.twofortyfouram.locale.intent.action.EDIT_SETTING"
    const val ACTION_FIRE_SETTING = "com.twofortyfouram.locale.intent.action.FIRE_SETTING"
    const val ACTION_EDIT_CONDITION = "com.twofortyfouram.locale.intent.action.EDIT_CONDITION"
    const val ACTION_QUERY_CONDITION = "com.twofortyfouram.locale.intent.action.QUERY_CONDITION"
    const val ACTION_REQUEST_QUERY = "com.twofortyfouram.locale.intent.action.REQUEST_QUERY"
    val visiblePluginIntentActions = listOf(ACTION_EDIT_SETTING, ACTION_EDIT_CONDITION)
    const val EXTRA_BUNDLE = "com.twofortyfouram.locale.intent.extra.BUNDLE"
    const val EXTRA_STRING_BLURB = "com.twofortyfouram.locale.intent.extra.BLURB"
    const val EXTRA_STRING_ACTIVITY_CLASS_NAME = "com.twofortyfouram.locale.intent.extra.ACTIVITY"
    const val EXTRA_STRING_BREADCRUMB = "com.twofortyfouram.locale.intent.extra.BREADCRUMB"
    const val EXTRA_BLURB = EXTRA_STRING_BLURB
    const val EXTRA_ACTIVITY_CLASS_NAME = EXTRA_STRING_ACTIVITY_CLASS_NAME
    const val RESULT_CONDITION_SATISFIED = 16
    const val RESULT_CONDITION_UNSATISFIED = 17
    const val RESULT_CONDITION_UNKNOWN = 18
    const val MAX_BUNDLE_JSON_BYTES = 16 * 1024
    const val MAX_BUNDLE_ENTRIES = 128
    const val MAX_BUNDLE_KEY_BYTES = 256
}

data class LocalePluginRequest(
    val packageName: String,
    val bundleJson: String = "{}",
    val blurb: String = "",
    val timeoutMs: Long = 5_000,
)

data class LocalePluginResult(
    val success: Boolean,
    val message: String,
)

data class LocalePluginEditIntentResult(
    val success: Boolean,
    val intent: Intent? = null,
    val message: String,
    /** The package has no edit activity at all, as opposed to one OpenTasker refused to open. */
    val notFound: Boolean = false,
)

data class LocalePluginConfigurationResult(
    val bundleJson: String,
    val blurb: String,
)

enum class LocalePluginConditionState(val resultCode: Int, val serializedName: String) {
    Satisfied(LocalePluginContract.RESULT_CONDITION_SATISFIED, "satisfied"),
    Unsatisfied(LocalePluginContract.RESULT_CONDITION_UNSATISFIED, "unsatisfied"),
    Unknown(LocalePluginContract.RESULT_CONDITION_UNKNOWN, "unknown"),
}

data class LocalePluginConditionResult(
    val state: LocalePluginConditionState,
    val message: String,
) {
    val satisfied: Boolean
        get() = state == LocalePluginConditionState.Satisfied
}

data class LocalePluginDescriptor(
    val packageName: String,
    val label: String,
    val supportsSettings: Boolean,
    val supportsConditions: Boolean,
    val requestedPermissions: List<String>,
    val settingReceiverPermissions: List<String> = emptyList(),
    val conditionReceiverPermissions: List<String> = emptyList(),
)

/**
 * Whether a Locale `FIRE_SETTING` / `QUERY_CONDITION` broadcast can actually be delivered.
 *
 * A receiver `android:permission` is a sender requirement. After confirming the sender holds
 * that permission, the host must call `sendBroadcast(intent)` with no receiver-permission
 * argument: the two-arg overload requires *receivers* to hold the permission, which would
 * drop a deliverable Locale fire and still look like Success.
 */
internal object LocaleSettingDispatch {
    fun allowed(requiredPermission: String?, senderHoldsPermission: Boolean): Boolean =
        requiredPermission.isNullOrBlank() || senderHoldsPermission

    fun missingPermissionMessage(permission: String): String =
        "Locale plugin setting requires $permission, which OpenTasker does not hold."
}

/** Package/component queries used by the host and discovery surfaces. */
interface LocalePluginComponentResolver {
    fun editActivities(packageName: String, action: String): List<ComponentName>
    fun broadcastReceivers(packageName: String, action: String): List<ComponentName>
    fun activityPackages(action: String): Set<String>
    fun broadcastReceiverPermissions(action: String): Map<String, List<String>>
}

/** Broadcast boundary used by the host; production uses Android and tests can supply a fixture. */
interface LocalePluginTransport {
    suspend fun sendSetting(intent: Intent)
    suspend fun queryCondition(intent: Intent): Int
}

private class AndroidLocalePluginComponentResolver(
    private val packageManager: PackageManager,
) : LocalePluginComponentResolver {
    override fun editActivities(packageName: String, action: String): List<ComponentName> =
        queryEditActivities(packageManager, packageName, action)

    override fun broadcastReceivers(packageName: String, action: String): List<ComponentName> =
        queryBroadcastReceivers(packageManager, packageName, action).map { it.component }

    override fun activityPackages(action: String): Set<String> = queryPackages(packageManager, action)

    override fun broadcastReceiverPermissions(action: String): Map<String, List<String>> =
        queryBroadcastReceiversByPackage(packageManager, action)
}

private class AndroidLocalePluginTransport(
    private val appContext: Context,
    private val dispatcher: CoroutineDispatcher,
) : LocalePluginTransport {
    override suspend fun sendSetting(intent: Intent) {
        withContext(dispatcher) {
            requireDeliverable(intent)
            appContext.sendBroadcast(intent)
        }
    }

    override suspend fun queryCondition(intent: Intent): Int = withContext(dispatcher) {
        requireDeliverable(intent)
        suspendCancellableCoroutine { continuation ->
            val resultReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent?) {
                    if (continuation.isActive) continuation.resume(resultCode)
                }
            }
            appContext.sendOrderedBroadcast(
                intent,
                null,
                resultReceiver,
                null,
                Activity.RESULT_CANCELED,
                null,
                null,
            )
        }
    }

    private fun requireDeliverable(intent: Intent) {
        val component = intent.component
            ?: throw IllegalStateException("Locale plugin broadcast has no target component.")
        val permission = runCatching {
            appContext.packageManager.getReceiverInfo(component, 0).permission
        }.getOrElse { error ->
            throw SecurityException(
                "Locale plugin receiver ${component.flattenToShortString()} could not be inspected: ${error.message}",
            )
        }?.takeIf { it.isNotBlank() }
        val holds = permission == null ||
            appContext.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
        if (!LocaleSettingDispatch.allowed(permission, holds)) {
            throw SecurityException(LocaleSettingDispatch.missingPermissionMessage(permission.orEmpty()))
        }
    }
}

/**
 * A bundle value with the type a plugin reads it back as. Plugins call `getInt`, `getBoolean` and
 * so on, which return the default when the stored value has another type, so a version code sent
 * as the string "1002" reads as 0 and the plugin rejects the whole bundle.
 */
sealed interface LocaleBundleValue {
    data class Text(val value: String) : LocaleBundleValue
    data class Flag(val value: Boolean) : LocaleBundleValue
    data class IntNumber(val value: Int) : LocaleBundleValue
    data class LongNumber(val value: Long) : LocaleBundleValue
    data class FloatNumber(val value: Float) : LocaleBundleValue
    data class DoubleNumber(val value: Double) : LocaleBundleValue

    /** A `String[]`, from `putStringArray`. */
    data class TextArray(val values: List<String>) : LocaleBundleValue

    /** An `ArrayList<String>`, from `putStringArrayList`, which `getStringArray` won't read. */
    data class TextList(val values: List<String>) : LocaleBundleValue

    /** A key present with no value, as `putString(key, null)` leaves it; `containsKey` still sees it. */
    data object Null : LocaleBundleValue
}

object LocalePluginBundleCodec {
    private const val TAG_STRING = "string"
    private const val TAG_BOOLEAN = "boolean"
    private const val TAG_INT = "int"
    private const val TAG_LONG = "long"
    private const val TAG_FLOAT = "float"
    private const val TAG_DOUBLE = "double"
    private const val TAG_STRING_ARRAY = "stringArray"
    private const val TAG_STRING_LIST = "stringList"
    private const val TAG_NULL = "null"
    private val VARIABLE_TYPED_TAGS = setOf(TAG_BOOLEAN, TAG_INT, TAG_LONG, TAG_FLOAT, TAG_DOUBLE)
    private val VARIABLE_IN_TEXT = Regex("""%[A-Za-z]|\{\{""")

    fun validatePackageName(packageName: String) {
        require(PackageNamePolicy.isValid(packageName)) { "Invalid plugin package name." }
    }

    /**
     * Reads bundle JSON as typed values. A plain JSON value is sent as a string, as it always was,
     * so older bundles keep working. A typed value is an object with one tag, like `{"int":1002}`,
     * `{"boolean":true}` or `{"stringArray":["a","b"]}`.
     */
    fun decodeBundle(bundleJson: String): Map<String, LocaleBundleValue> {
        val trimmed = bundleJson.trim().ifBlank { "{}" }
        require(trimmed.toByteArray(Charsets.UTF_8).size <= LocalePluginContract.MAX_BUNDLE_JSON_BYTES) {
            "Plugin bundle JSON exceeds ${LocalePluginContract.MAX_BUNDLE_JSON_BYTES} bytes."
        }
        val element = Json.parseToJsonElement(trimmed)
        val obj = element as? JsonObject ?: error("Plugin bundle must be a JSON object.")
        require(obj.size <= LocalePluginContract.MAX_BUNDLE_ENTRIES) {
            "Plugin bundle contains too many entries."
        }
        return obj.entries.associate { (key, value) ->
            requireValidKey(key)
            val decoded = when (value) {
                is JsonPrimitive -> LocaleBundleValue.Text(plainText(value))
                is JsonObject -> typedValue(key, value)
                else -> error("Plugin bundle value for $key must be a string, number, boolean or typed value.")
            }
            requireValueSize(decoded)
            key to decoded
        }
    }

    /**
     * The saved bundle for the plugin's own settings screen. A typed slot holding a variable until
     * the action runs, like `{"int":"%count"}`, goes as its text, and an entry that can't be read at
     * all is left out, so the rest of the plugin's form still opens filled in. Empty when the JSON
     * itself doesn't parse or is over the limits.
     */
    fun decodeBundleForEditing(bundleJson: String): Map<String, LocaleBundleValue> {
        val strict = try {
            decodeBundle(bundleJson)
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: IllegalStateException) {
            null
        }
        if (strict != null) return strict
        if (bundleJson.toByteArray(Charsets.UTF_8).size > LocalePluginContract.MAX_BUNDLE_JSON_BYTES) return emptyMap()
        val obj = try {
            Json.parseToJsonElement(bundleJson.trim().ifBlank { "{}" }) as? JsonObject
        } catch (_: IllegalArgumentException) {
            null
        } ?: return emptyMap()
        if (obj.size > LocalePluginContract.MAX_BUNDLE_ENTRIES) return emptyMap()
        return obj.entries.mapNotNull { (key, value) ->
            try {
                decodeBundle(JsonObject(mapOf(key to value)).toString()).entries.single().toPair()
            } catch (_: IllegalArgumentException) {
                variableInTypedSlot(key, value)?.let { key to it }
            } catch (_: IllegalStateException) {
                variableInTypedSlot(key, value)?.let { key to it }
            }
        }.toMap()
    }

    /** `{"int":"%count"}` as its text, under a key [decodeBundle] would take. Any other failure stays out. */
    private fun variableInTypedSlot(key: String, value: JsonElement): LocaleBundleValue.Text? {
        val (tag, raw) = (value as? JsonObject)?.entries?.singleOrNull() ?: return null
        if (tag !in VARIABLE_TYPED_TAGS) return null
        val text = (raw as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        if (!VARIABLE_IN_TEXT.containsMatchIn(text)) return null
        return try {
            requireValidKey(key)
            LocaleBundleValue.Text(text).also(::requireValueSize)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    /** The bundle as the strings it used to send, for callers that only compare or display it. */
    fun decodeStringBundle(bundleJson: String): Map<String, String> =
        decodeBundle(bundleJson).mapValues { (_, value) -> displayText(value) }

    private fun plainText(primitive: JsonPrimitive): String =
        primitive.contentOrNull
            ?: primitive.booleanOrNull?.toString()
            ?: primitive.longOrNull?.toString()
            ?: primitive.intOrNull?.toString()
            ?: primitive.doubleOrNull?.toString()
            ?: error("Plugin bundle values must not be null.")

    private fun typedValue(key: String, obj: JsonObject): LocaleBundleValue {
        val (tag, raw) = obj.entries.singleOrNull()
            ?: error("Typed plugin bundle value for $key must have exactly one type, like {\"int\":3}.")
        fun primitive(): JsonPrimitive = raw as? JsonPrimitive
            ?: error("Plugin bundle value for $key must be a single $tag.")
        // Quoted numbers and booleans are accepted too, since a variable inside quotes ends up that way.
        fun content(): String = primitive().contentOrNull?.trim()
            ?: error("Plugin bundle value for $key must not be null; use {\"null\":true}.")
        fun strings(): List<String> = (raw as? JsonArray ?: error("Plugin bundle value for $key must be a JSON array."))
            .map { item -> (item as? JsonPrimitive)?.contentOrNull ?: error("Plugin bundle $tag for $key must hold strings.") }
        fun invalid(): Nothing = error("Plugin bundle value for $key isn't a valid $tag.")
        return when (tag) {
            TAG_STRING -> LocaleBundleValue.Text(primitive().contentOrNull ?: invalid())
            TAG_BOOLEAN -> LocaleBundleValue.Flag(content().toBooleanStrictOrNull() ?: invalid())
            TAG_INT -> LocaleBundleValue.IntNumber(content().toIntOrNull() ?: invalid())
            TAG_LONG -> LocaleBundleValue.LongNumber(content().toLongOrNull() ?: invalid())
            TAG_FLOAT -> LocaleBundleValue.FloatNumber(content().toFloatOrNull()?.takeIf { it.isFinite() } ?: invalid())
            TAG_DOUBLE -> LocaleBundleValue.DoubleNumber(content().toDoubleOrNull()?.takeIf { it.isFinite() } ?: invalid())
            TAG_STRING_ARRAY -> LocaleBundleValue.TextArray(strings())
            TAG_STRING_LIST -> LocaleBundleValue.TextList(strings())
            TAG_NULL -> LocaleBundleValue.Null
            else -> error("Plugin bundle value for $key has an unknown type \"$tag\".")
        }
    }

    private fun displayText(value: LocaleBundleValue): String = when (value) {
        is LocaleBundleValue.Text -> value.value
        is LocaleBundleValue.Flag -> value.value.toString()
        is LocaleBundleValue.IntNumber -> value.value.toString()
        is LocaleBundleValue.LongNumber -> value.value.toString()
        is LocaleBundleValue.FloatNumber -> value.value.toString()
        is LocaleBundleValue.DoubleNumber -> value.value.toString()
        is LocaleBundleValue.TextArray -> value.values.joinToString(",")
        is LocaleBundleValue.TextList -> value.values.joinToString(",")
        LocaleBundleValue.Null -> ""
    }

    private fun requireValidKey(key: String) {
        require(key.isNotBlank()) { "Plugin bundle keys must not be blank." }
        require(key.toByteArray(Charsets.UTF_8).size <= LocalePluginContract.MAX_BUNDLE_KEY_BYTES) {
            "Plugin bundle key is too large."
        }
    }

    private fun requireValueSize(value: LocaleBundleValue) {
        require(encodeValue(value).toString().toByteArray(Charsets.UTF_8).size <= LocalePluginContract.MAX_BUNDLE_JSON_BYTES) {
            "Plugin bundle value is too large."
        }
    }

    /** Each value in its canonical JSON form, so a string "3" and an int 3 stay different keys. */
    fun canonicalValues(values: Map<String, LocaleBundleValue>): Map<String, String> =
        values.toSortedMap().mapValues { (_, value) -> encodeValue(value).toString() }

    /** Writes [values] back as bundle JSON: strings plain, everything else tagged with its type. */
    fun encodeBundle(values: Map<String, LocaleBundleValue>): String =
        JsonObject(values.toSortedMap().mapValues { (_, value) -> encodeValue(value) }).toString()

    private fun encodeValue(value: LocaleBundleValue): JsonElement = when (value) {
        is LocaleBundleValue.Text -> JsonPrimitive(value.value)
        is LocaleBundleValue.Flag -> tagged(TAG_BOOLEAN, JsonPrimitive(value.value))
        is LocaleBundleValue.IntNumber -> tagged(TAG_INT, JsonPrimitive(value.value))
        is LocaleBundleValue.LongNumber -> tagged(TAG_LONG, JsonPrimitive(value.value))
        is LocaleBundleValue.FloatNumber -> tagged(TAG_FLOAT, JsonPrimitive(value.value))
        is LocaleBundleValue.DoubleNumber -> tagged(TAG_DOUBLE, JsonPrimitive(value.value))
        is LocaleBundleValue.TextArray -> tagged(TAG_STRING_ARRAY, JsonArray(value.values.map { JsonPrimitive(it) }))
        is LocaleBundleValue.TextList -> tagged(TAG_STRING_LIST, JsonArray(value.values.map { JsonPrimitive(it) }))
        LocaleBundleValue.Null -> tagged(TAG_NULL, JsonPrimitive(true))
    }

    private fun tagged(tag: String, value: JsonElement) = JsonObject(mapOf(tag to value))

    fun toTypedBundle(values: Map<String, LocaleBundleValue>): Bundle = Bundle().apply {
        values.toSortedMap().forEach { (key, value) ->
            when (value) {
                is LocaleBundleValue.Text -> putString(key, value.value)
                is LocaleBundleValue.Flag -> putBoolean(key, value.value)
                is LocaleBundleValue.IntNumber -> putInt(key, value.value)
                is LocaleBundleValue.LongNumber -> putLong(key, value.value)
                is LocaleBundleValue.FloatNumber -> putFloat(key, value.value)
                is LocaleBundleValue.DoubleNumber -> putDouble(key, value.value)
                is LocaleBundleValue.TextArray -> putStringArray(key, value.values.toTypedArray())
                is LocaleBundleValue.TextList -> putStringArrayList(key, ArrayList(value.values))
                LocaleBundleValue.Null -> putString(key, null)
            }
        }
    }

    /** A plugin's saved configuration with its types kept, so firing it sends what the plugin wrote. */
    @Suppress("DEPRECATION")
    fun fromBundleTyped(bundle: Bundle): Map<String, LocaleBundleValue> =
        sanitizeTypedValues(bundle.keySet().associateWith { key -> bundle.get(key) })

    /**
     * Keeps the value types a bundle JSON can carry. Nested bundles, parcelables and anything else
     * are refused, naming the key, rather than dropped, since a plugin may need the missing key.
     */
    fun sanitizeTypedValues(values: Map<String, Any?>): Map<String, LocaleBundleValue> {
        require(values.size <= LocalePluginContract.MAX_BUNDLE_ENTRIES) {
            "Plugin bundle contains too many entries."
        }
        val typed = values.entries.associate { (key, value) ->
            requireValidKey(key)
            val converted = when (value) {
                null -> LocaleBundleValue.Null
                is String -> LocaleBundleValue.Text(value)
                is Boolean -> LocaleBundleValue.Flag(value)
                is Int -> LocaleBundleValue.IntNumber(value)
                is Long -> LocaleBundleValue.LongNumber(value)
                is Float -> LocaleBundleValue.FloatNumber(value)
                is Double -> LocaleBundleValue.DoubleNumber(value)
                is Array<*> -> LocaleBundleValue.TextArray(stringItems(key, value.asList()))
                is ArrayList<*> -> LocaleBundleValue.TextList(stringItems(key, value))
                else -> error("Plugin bundle value for $key is a ${value.javaClass.simpleName}, which OpenTasker can't store.")
            }
            requireValueSize(converted)
            key to converted
        }
        require(encodeBundle(typed).toByteArray(Charsets.UTF_8).size <= LocalePluginContract.MAX_BUNDLE_JSON_BYTES) {
            "Plugin bundle JSON exceeds ${LocalePluginContract.MAX_BUNDLE_JSON_BYTES} bytes."
        }
        return typed
    }

    private fun stringItems(key: String, items: List<*>): List<String> =
        items.map { item -> item as? String ?: error("Plugin bundle list for $key must hold only strings.") }

    fun toBundle(values: Map<String, String>): Bundle = Bundle().apply {
        sanitizeBundleValues(values).entries.sortedBy { it.key }.forEach { (key, value) ->
            putString(key, value)
        }
    }

    fun encodeStringBundle(values: Map<String, String>): String {
        val obj = JsonObject(
            values.toSortedMap().mapValues { (_, value) -> JsonPrimitive(value) }
        )
        return obj.toString()
    }

    @Suppress("DEPRECATION")
    fun fromBundle(bundle: Bundle): Map<String, String> =
        sanitizeBundleValues(
            bundle.keySet().associateWith { key -> bundle.get(key) }
        )

    fun sanitizeBundleValues(values: Map<String, Any?>): Map<String, String> {
        require(values.size <= LocalePluginContract.MAX_BUNDLE_ENTRIES) {
            "Plugin bundle contains too many entries."
        }
        val sanitized = values.entries.associate { (key, value) ->
            require(key.isNotBlank()) { "Plugin bundle keys must not be blank." }
            require(key.toByteArray(Charsets.UTF_8).size <= LocalePluginContract.MAX_BUNDLE_KEY_BYTES) {
                "Plugin bundle key is too large."
            }
            val stringValue = when (value) {
                is String -> value
                is Boolean -> value.toString()
                is Byte -> value.toString()
                is Short -> value.toString()
                is Int -> value.toString()
                is Long -> value.toString()
                is Float -> value.toString()
                is Double -> value.toString()
                null -> error("Plugin bundle values must not be null.")
                else -> error("Plugin bundle values must be primitive strings, numbers, or booleans.")
            }
            require(stringValue.toByteArray(Charsets.UTF_8).size <= LocalePluginContract.MAX_BUNDLE_JSON_BYTES) {
                "Plugin bundle value is too large."
            }
            key to stringValue
        }
        require(encodeStringBundle(sanitized).toByteArray(Charsets.UTF_8).size <= LocalePluginContract.MAX_BUNDLE_JSON_BYTES) {
            "Plugin bundle JSON exceeds ${LocalePluginContract.MAX_BUNDLE_JSON_BYTES} bytes."
        }
        return sanitized
    }
}

object LocalePluginConditionResultParser {
    fun parse(resultCode: Int, packageName: String): LocalePluginConditionResult {
        val state = when (resultCode) {
            LocalePluginContract.RESULT_CONDITION_SATISFIED -> LocalePluginConditionState.Satisfied
            LocalePluginContract.RESULT_CONDITION_UNSATISFIED -> LocalePluginConditionState.Unsatisfied
            LocalePluginContract.RESULT_CONDITION_UNKNOWN -> LocalePluginConditionState.Unknown
            else -> LocalePluginConditionState.Unknown
        }
        return LocalePluginConditionResult(
            state = state,
            message = when (state) {
                LocalePluginConditionState.Satisfied -> "Locale plugin condition satisfied for $packageName."
                LocalePluginConditionState.Unsatisfied -> "Locale plugin condition not satisfied for $packageName."
                LocalePluginConditionState.Unknown -> {
                    if (resultCode == LocalePluginContract.RESULT_CONDITION_UNKNOWN) {
                        "Locale plugin condition state is unknown for $packageName."
                    } else {
                        "Locale plugin condition returned unrecognized result code $resultCode for $packageName."
                    }
                }
            },
        )
    }
}

object LocalePluginConfigurationResultParser {
    fun parse(resultCode: Int, data: Intent?): LocalePluginConfigurationResult {
        require(resultCode == Activity.RESULT_OK) { "Locale plugin configuration was canceled." }
        val resultIntent = requireNotNull(data) { "Locale plugin configuration returned no result intent." }
        val values = readingPluginData {
            val bundle = resultIntent.getBundleExtra(LocalePluginContract.EXTRA_BUNDLE)
                ?: error("Locale plugin configuration returned no bundle.")
            LocalePluginBundleCodec.fromBundleTyped(bundle)
        }
        return LocalePluginConfigurationResult(
            bundleJson = LocalePluginBundleCodec.encodeBundle(values),
            blurb = readingPluginData { resultIntent.getStringExtra(LocalePluginContract.EXTRA_BLURB) }.orEmpty().take(120),
        )
    }

    /**
     * Unparceling what a plugin sent can fail in ways of its own, like BadParcelableException for a
     * class OpenTasker doesn't have. Those become the same refusal as any other unreadable result.
     */
    private inline fun <T> readingPluginData(read: () -> T): T = try {
        read()
    } catch (e: IllegalArgumentException) {
        throw e
    } catch (e: IllegalStateException) {
        throw e
    } catch (e: RuntimeException) {
        throw IllegalStateException("Locale plugin configuration couldn't be read (${e.javaClass.simpleName}).", e)
    }
}

data class LocalePluginConditionCacheKey(
    val packageName: String,
    val bundleValues: Map<String, String>,
)

class LocalePluginConditionStateCache(
    private val maxEntries: Int = 128,
) {
    private val knownStates = object : LinkedHashMap<LocalePluginConditionCacheKey, LocalePluginConditionState>(
        maxEntries,
        0.75f,
        true,
    ) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<LocalePluginConditionCacheKey, LocalePluginConditionState>?,
        ): Boolean = size > maxEntries
    }

    @Synchronized
    fun resolve(
        key: LocalePluginConditionCacheKey,
        rawResult: LocalePluginConditionResult,
    ): LocalePluginConditionResult {
        return when (rawResult.state) {
            LocalePluginConditionState.Satisfied,
            LocalePluginConditionState.Unsatisfied -> {
                knownStates[key] = rawResult.state
                rawResult
            }
            LocalePluginConditionState.Unknown -> {
                val lastKnown = knownStates[key]
                if (lastKnown == null) {
                    rawResult.copy(
                        state = LocalePluginConditionState.Unsatisfied,
                        message = "${rawResult.message} No last known result is available; treating unknown as unsatisfied.",
                    )
                } else {
                    rawResult.copy(
                        state = lastKnown,
                        message = "${rawResult.message} Using last known ${lastKnown.serializedName} result.",
                    )
                }
            }
        }
    }

    companion object {
        val Shared = LocalePluginConditionStateCache()
    }
}

class LocalePluginHost private constructor(
    private val conditionStateCache: LocalePluginConditionStateCache,
    private val componentResolver: LocalePluginComponentResolver,
    private val transport: LocalePluginTransport,
    private val signerGuard: TargetSignerGuard?,
) {
    constructor(
        appContext: Context,
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
        conditionStateCache: LocalePluginConditionStateCache = LocalePluginConditionStateCache.Shared,
    ) : this(
        conditionStateCache,
        AndroidLocalePluginComponentResolver(appContext.packageManager),
        AndroidLocalePluginTransport(appContext, dispatcher),
        TargetSignerGuard.forContext(appContext),
    )

    /**
     * For fixtures that bring their own resolver and transport. [signerGuard] defaults to null,
     * which skips the signer check, because a fixture package has nothing installed to pin.
     * Production always goes through the Context constructor, which always checks.
     */
    constructor(
        componentResolver: LocalePluginComponentResolver,
        transport: LocalePluginTransport,
        conditionStateCache: LocalePluginConditionStateCache = LocalePluginConditionStateCache(),
        signerGuard: TargetSignerGuard? = null,
    ) : this(
        conditionStateCache,
        componentResolver,
        transport,
        signerGuard,
    )

    fun buildEditSettingIntent(packageName: String): LocalePluginEditIntentResult =
        buildEditIntent(packageName, LocalePluginContract.ACTION_EDIT_SETTING, "setting")

    fun buildEditConditionIntent(packageName: String): LocalePluginEditIntentResult =
        buildEditIntent(packageName, LocalePluginContract.ACTION_EDIT_CONDITION, "condition")

    /**
     * The plugin's own settings screen, opened on what's saved now so it can show it. The saved
     * bundle can hold secrets, so a plugin whose signer changed gets nothing. Checking the signer
     * can write its first pin, so call this off the main thread.
     */
    fun configurationIntent(
        packageName: String,
        condition: Boolean,
        bundleJson: String,
        blurb: String,
    ): LocalePluginEditIntentResult {
        val edit = if (condition) buildEditConditionIntent(packageName) else buildEditSettingIntent(packageName)
        val intent = edit.intent ?: return edit
        signerRefusal(packageName)?.let { refusal -> return LocalePluginEditIntentResult(false, message = refusal) }
        val saved = LocalePluginBundleCodec.decodeBundleForEditing(bundleJson)
        if (saved.isNotEmpty()) intent.putExtra(LocalePluginContract.EXTRA_BUNDLE, LocalePluginBundleCodec.toTypedBundle(saved))
        if (blurb.isNotBlank()) intent.putExtra(LocalePluginContract.EXTRA_BLURB, blurb.take(120))
        intent.putExtra(LocalePluginContract.EXTRA_STRING_BREADCRUMB, "OpenTasker")
        return edit
    }

    suspend fun fireSetting(request: LocalePluginRequest): LocalePluginResult {
        LocalePluginBundleCodec.validatePackageName(request.packageName)
        require(request.timeoutMs in 1_000..30_000) { "Plugin timeout must be between 1000 and 30000 ms." }

        val bundleValues = LocalePluginBundleCodec.decodeBundle(request.bundleJson)
        signerRefusal(request.packageName)?.let { refusal -> return LocalePluginResult(false, refusal) }
        val pluginBundle = LocalePluginBundleCodec.toTypedBundle(bundleValues)
        val target = resolveBroadcastTarget(request.packageName, LocalePluginContract.ACTION_FIRE_SETTING)
        val component = target.component ?: return LocalePluginResult(false, target.message)
        val intent = pluginBroadcastIntent(
            action = LocalePluginContract.ACTION_FIRE_SETTING,
            request = request,
            pluginBundle = pluginBundle,
            target = component,
        )

        return withTimeout(request.timeoutMs) {
            try {
                transport.sendSetting(intent)
                LocalePluginResult(true, "Locale plugin setting dispatched to ${component.flattenToShortString()}.")
            } catch (error: SecurityException) {
                LocalePluginResult(false, error.message ?: "Locale plugin setting was not deliverable.")
            }
        }
    }

    suspend fun queryCondition(request: LocalePluginRequest): LocalePluginConditionResult {
        LocalePluginBundleCodec.validatePackageName(request.packageName)
        require(request.timeoutMs in 1_000..30_000) { "Plugin timeout must be between 1000 and 30000 ms." }

        val bundleValues = LocalePluginBundleCodec.decodeBundle(request.bundleJson)
        // Straight back as Unknown, not through the cache: a last known answer would hide the refusal.
        signerRefusal(request.packageName)?.let { refusal ->
            return LocalePluginConditionResult(state = LocalePluginConditionState.Unknown, message = refusal)
        }
        val pluginBundle = LocalePluginBundleCodec.toTypedBundle(bundleValues)
        val cacheKey = LocalePluginConditionCacheKey(
            packageName = request.packageName,
            bundleValues = LocalePluginBundleCodec.canonicalValues(bundleValues),
        )
        val target = resolveBroadcastTarget(request.packageName, LocalePluginContract.ACTION_QUERY_CONDITION)
        val component = target.component ?: return LocalePluginConditionResult(
                state = LocalePluginConditionState.Unknown,
                message = target.message,
            )
        val intent = pluginBroadcastIntent(
            action = LocalePluginContract.ACTION_QUERY_CONDITION,
            request = request,
            pluginBundle = pluginBundle,
            target = component,
        )

        return withTimeout(request.timeoutMs) {
            val resultCode = runCatching { transport.queryCondition(intent) }.getOrElse { throwable ->
                return@withTimeout LocalePluginConditionResult(
                    state = LocalePluginConditionState.Unknown,
                    message = "Locale plugin condition query failed for ${request.packageName}: ${throwable.message}",
                )
            }
            conditionStateCache.resolve(
                cacheKey,
                LocalePluginConditionResultParser.parse(resultCode, request.packageName),
            )
        }
    }

    /**
     * The pinned-signer check, run before any bundle, intent or broadcast exists so a refused
     * plugin is sent nothing. A package that isn't installed passes through to the usual
     * "no receiver" result; one with no pin yet is pinned here (trust on first use).
     */
    private fun signerRefusal(packageName: String): String? =
        (signerGuard?.check(packageName) as? TargetSignerCheck.Mismatch)?.message

    private fun pluginBroadcastIntent(
        action: String,
        request: LocalePluginRequest,
        pluginBundle: Bundle,
        target: ComponentName,
    ): Intent = Intent(action)
        .setComponent(target)
        .putExtra(LocalePluginContract.EXTRA_BUNDLE, pluginBundle)
        .putExtra(LocalePluginContract.EXTRA_BLURB, request.blurb.take(120))

    private fun resolveBroadcastTarget(packageName: String, action: String): LocaleBroadcastTargetResolution {
        val receivers = componentResolver.broadcastReceivers(packageName, action).map { LocaleBroadcastReceiverTarget(it) }
        return when (receivers.size) {
            0 -> LocaleBroadcastTargetResolution(
                component = null,
                message = "No Locale execution receiver found for $packageName and $action.",
            )
            1 -> LocaleBroadcastTargetResolution(
                component = receivers.single().component,
                message = "Resolved Locale execution receiver.",
            )
            else -> LocaleBroadcastTargetResolution(
                component = null,
                message = "Multiple Locale execution receivers found for $packageName and $action; refusing ambiguous plugin dispatch.",
            )
        }
    }

    private fun buildEditIntent(
        packageName: String,
        action: String,
        label: String,
    ): LocalePluginEditIntentResult {
        LocalePluginBundleCodec.validatePackageName(packageName)
        val activities = componentResolver.editActivities(packageName, action)
        return when (activities.size) {
            0 -> LocalePluginEditIntentResult(
                success = false,
                message = "No Locale $label configuration activity found for $packageName.",
                notFound = true,
            )
            1 -> {
                val component = activities.single()
                LocalePluginEditIntentResult(
                    success = true,
                    intent = Intent(action).setComponent(component),
                    message = "Resolved Locale $label configuration activity ${component.flattenToShortString()}.",
                )
            }
            else -> LocalePluginEditIntentResult(
                success = false,
                message = "Multiple Locale $label configuration activities found for $packageName; refusing ambiguous plugin configuration.",
            )
        }
    }
}

interface LocalePluginPackageMetadata {
    fun label(packageName: String): String
    fun requestedPermissions(packageName: String): List<String>
}

private class AndroidLocalePluginPackageMetadata(
    private val packageManager: PackageManager,
) : LocalePluginPackageMetadata {
    override fun label(packageName: String): String =
        runCatching { packageManager.getApplicationInfo(packageName, 0) }
            .map { packageManager.getApplicationLabel(it).toString() }
            .getOrDefault(packageName)

    override fun requestedPermissions(packageName: String): List<String> = runCatching {
        packageManager.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions
            ?.toList()
            .orEmpty()
    }.getOrDefault(emptyList())
}

class LocalePluginDiscovery(
    private val componentResolver: LocalePluginComponentResolver,
    private val packageMetadata: LocalePluginPackageMetadata,
) {
    constructor(appContext: Context) : this(
        componentResolver = AndroidLocalePluginComponentResolver(appContext.packageManager),
        packageMetadata = AndroidLocalePluginPackageMetadata(appContext.packageManager),
    )

    fun discover(): List<LocalePluginDescriptor> {
        val settings = componentResolver.activityPackages(LocalePluginContract.ACTION_EDIT_SETTING)
        val conditions = componentResolver.activityPackages(LocalePluginContract.ACTION_EDIT_CONDITION)
        val settingReceivers = componentResolver.broadcastReceiverPermissions(LocalePluginContract.ACTION_FIRE_SETTING)
        val conditionReceivers = componentResolver.broadcastReceiverPermissions(LocalePluginContract.ACTION_QUERY_CONDITION)
        return (settings + conditions)
            .distinct()
            .sorted()
            .map { packageName ->
                LocalePluginDescriptor(
                    packageName = packageName,
                    label = packageMetadata.label(packageName),
                    supportsSettings = packageName in settings,
                    supportsConditions = packageName in conditions,
                    requestedPermissions = packageMetadata.requestedPermissions(packageName).sorted(),
                    settingReceiverPermissions = settingReceivers[packageName].orEmpty(),
                    conditionReceiverPermissions = conditionReceivers[packageName].orEmpty(),
                )
            }
    }

}

private data class LocaleBroadcastReceiverTarget(
    val component: ComponentName,
)

private data class LocaleBroadcastTargetResolution(
    val component: ComponentName?,
    val message: String,
)

private fun queryBroadcastReceivers(
    pm: PackageManager,
    packageName: String,
    action: String,
): List<LocaleBroadcastReceiverTarget> =
    pm.queryBroadcastReceivers(Intent(action).setPackage(packageName), 0)
        .mapNotNull { resolveInfo ->
            val receiverInfo = resolveInfo.activityInfo ?: return@mapNotNull null
            if (receiverInfo.packageName != packageName) return@mapNotNull null
            LocaleBroadcastReceiverTarget(
                component = ComponentName(packageName, receiverInfo.name.toClassName(packageName)),
            )
        }
        .sortedBy { it.component.className }

private fun queryEditActivities(
    pm: PackageManager,
    packageName: String,
    action: String,
): List<ComponentName> {
    // No MATCH_DEFAULT_ONLY: the Locale spec's edit activities often don't declare CATEGORY_DEFAULT,
    // and they're opened by explicit component, which doesn't need it.
    val all = editActivityComponents(pm, packageName, action, flags = 0)
    if (all.size < 2) return all
    // Of several, the one that declares CATEGORY_DEFAULT is the one this app always opened.
    return preferSingle(all, editActivityComponents(pm, packageName, action, PackageManager.MATCH_DEFAULT_ONLY))
}

/** [all] narrowed to the one entry of [preferred] it holds, or [all] when that isn't exactly one. */
internal fun <T> preferSingle(all: List<T>, preferred: List<T>): List<T> =
    preferred.filter { it in all }.singleOrNull()?.let(::listOf) ?: all

private fun editActivityComponents(
    pm: PackageManager,
    packageName: String,
    action: String,
    flags: Int,
): List<ComponentName> =
    pm.queryIntentActivities(Intent(action).setPackage(packageName), flags)
        .mapNotNull { resolveInfo ->
            val activityInfo = resolveInfo.activityInfo ?: return@mapNotNull null
            if (activityInfo.packageName != packageName) return@mapNotNull null
            ComponentName(packageName, activityInfo.name.toClassName(packageName))
        }
        .sortedBy { it.className }

private fun queryPackages(pm: PackageManager, action: String): Set<String> =
    pm.queryIntentActivities(Intent(action), 0)
        .mapNotNull { it.activityInfo?.packageName }
        .toSet()

private fun queryBroadcastReceiversByPackage(pm: PackageManager, action: String): Map<String, List<String>> =
    pm.queryBroadcastReceivers(Intent(action), 0)
        .mapNotNull { resolveInfo ->
            val receiverInfo = resolveInfo.activityInfo ?: return@mapNotNull null
            receiverInfo.packageName to receiverInfo.permission.orEmpty()
        }
        .groupBy({ it.first }, { it.second })
        .mapValues { (_, permissions) -> permissions.filter { it.isNotBlank() }.distinct().sorted() }

private fun String.toClassName(packageName: String): String = when {
    startsWith(".") -> packageName + this
    "." !in this -> "$packageName.$this"
    else -> this
}
