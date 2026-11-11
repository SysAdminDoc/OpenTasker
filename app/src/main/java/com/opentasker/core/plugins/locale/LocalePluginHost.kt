package com.opentasker.core.plugins.locale

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.core.content.ContextCompat
import com.opentasker.core.apps.PackageNamePolicy
import com.opentasker.core.apps.TargetSignerCheck
import com.opentasker.core.apps.TargetSignerGuard
import com.opentasker.core.data.JsonNesting
import com.opentasker.core.model.VariableNamePolicy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
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
import java.security.SecureRandom
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

    /** Tasker's extension: the bundle keys, space-separated, whose values the host fills variables into. */
    const val EXTRA_VARIABLE_REPLACE_KEYS = "net.dinglisch.android.tasker.extras.VARIABLE_REPLACE_KEYS"

    /** Tasker's extension: the variables a plugin sets once it finishes, each as `%name\nLabel\nDescription`. */
    const val EXTRA_RELEVANT_VARIABLES = "net.dinglisch.android.tasker.RELEVANT_VARIABLES"

    /**
     * Tasker's synchronous setting protocol. The host says what it supports in HOST_CAPABILITIES on
     * the edit and fire intents. A plugin that waits for something answers the ordered fire broadcast
     * with RESULT_CODE_PENDING and later sends the COMPLETION_INTENT back with its RESULT_CODE and a
     * VARIABLES bundle; one that finishes at once sets both on the ordered broadcast's result.
     */
    const val EXTRA_HOST_CAPABILITIES = "net.dinglisch.android.tasker.extras.HOST_CAPABILITIES"
    const val EXTRA_REQUESTED_TIMEOUT = "net.dinglisch.android.tasker.extras.REQUESTED_TIMEOUT"
    const val EXTRA_COMPLETION_INTENT = "net.dinglisch.android.tasker.extras.COMPLETION_INTENT"
    const val EXTRA_RESULT_CODE = "net.dinglisch.android.tasker.extras.RESULT_CODE"
    const val EXTRA_VARIABLES = "net.dinglisch.android.tasker.extras.VARIABLES"
    const val EXTRA_HINTS = "net.dinglisch.android.tasker.extras.HINTS"
    const val HINT_TIMEOUT_MS = ".hints.TIMEOUT"

    /** Variables returned from a setting, `%var` replacement in VARIABLE_REPLACE_KEYS, and the wait. */
    const val HOST_CAPABILITIES = 2 or 8 or 32
    const val RESULT_CODE_OK = Activity.RESULT_OK
    const val RESULT_CODE_OK_MINOR_FAILURES = 1
    const val RESULT_CODE_FAILED = 2
    const val RESULT_CODE_PENDING = 3
    const val RESULT_CODE_UNKNOWN = 4

    /** Tasker's "wait forever"; the longest wait here is [MAX_SETTING_WAIT_MS], as long as a Wait step. */
    const val REQUESTED_TIMEOUT_NEVER = 3_600_000
    const val MAX_SETTING_WAIT_MS = 1_800_000L
    const val MAX_RETURNED_VARIABLES = 64
    const val MAX_RETURNED_TEXT_CHARS = 256 * 1024

    /** How long a plugin step waits when its plugin asked for [requested] ms on its settings screen. */
    fun waitForRequestedTimeout(requested: Int): Long? =
        requested.takeIf { it >= 0 }?.toLong()?.coerceAtMost(MAX_SETTING_WAIT_MS)

    const val RESULT_CONDITION_SATISFIED = 16
    const val RESULT_CONDITION_UNSATISFIED = 17
    const val RESULT_CONDITION_UNKNOWN = 18
    const val MAX_BUNDLE_JSON_BYTES = 16 * 1024
    const val MAX_BUNDLE_ENTRIES = 128
    const val MAX_BUNDLE_KEY_BYTES = 256

    /** The bundle, a typed slot in it, and the array a string array holds. */
    const val MAX_BUNDLE_DEPTH = 3
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
    /** What the plugin answered, or null when it was sent its settings without a wait. */
    val resultCode: Int? = null,
    val variables: LocalePluginReturnedVariables = LocalePluginReturnedVariables(),
)

/** What a plugin's answer to a fire broadcast or its completion intent carried. */
data class LocalePluginSettingReply(
    val resultCode: Int,
    val variables: LocalePluginReturnedVariables = LocalePluginReturnedVariables(),
)

/**
 * The variables a plugin sent back, kept to what Tasker accepts: local names (`%name`, all lower
 * case), a String, a String[] or an ArrayList<String> each, and no more than the caps below in
 * all. Anything else is left out and named in [refused] for the run log.
 */
data class LocalePluginReturnedVariables(
    val values: Map<String, String> = emptyMap(),
    val arrays: Map<String, List<String>> = emptyMap(),
    val refused: List<String> = emptyList(),
) {
    val errorMessage: String? get() = values["errmsg"]

    /** Stands in for a value that threw when it was read, so [from] refuses that key alone. */
    internal object Unreadable

    companion object {
        fun from(raw: Map<String, Any?>): LocalePluginReturnedVariables {
            val values = linkedMapOf<String, String>()
            val arrays = linkedMapOf<String, List<String>>()
            val refused = mutableListOf<String>()
            var unnamed = 0
            var chars = 0
            for ((key, value) in raw) {
                val name = localName(key)
                val items: List<String>? = when (value) {
                    is String -> listOf(value)
                    is Array<*> -> value.takeIf { it.isArrayOf<String>() }?.map { it as String? ?: "" }
                    is List<*> -> value.takeIf { list -> list.all { it is String? } }?.map { it as String? ?: "" }
                    else -> null
                }
                // Each array item costs one more, so a list of a million empty strings isn't free.
                val size = when (value) {
                    is String -> value.length
                    else -> items?.sumOf { it.length + 1 } ?: 0
                }
                val reason = when {
                    value === Unreadable -> "couldn't be read"
                    name == null -> "not a local variable name"
                    items == null -> "not text or a list of text"
                    values.size + arrays.size >= LocalePluginContract.MAX_RETURNED_VARIABLES -> "too many variables"
                    chars + size > LocalePluginContract.MAX_RETURNED_TEXT_CHARS -> "too much text"
                    else -> null
                }
                if (reason != null || name == null || items == null) {
                    if (refused.size < MAX_REFUSED_NAMED) refused += "${key.take(64)} ($reason)" else unnamed++
                    continue
                }
                chars += size
                if (value is String) values[name] = value else arrays[name] = items
            }
            if (unnamed > 0) refused += "$unnamed more"
            return LocalePluginReturnedVariables(values, arrays, refused)
        }

        /** The run-log line names this many left-out keys and counts the rest. */
        private const val MAX_REFUSED_NAMED = 16

        /** Tasker sends `%name`; a global name or one without its `%` isn't the plugin's to set. */
        private fun localName(key: String): String? =
            key.takeIf { it.startsWith('%') }
                ?.let(VariableNamePolicy::normalize)
                ?.takeUnless(VariableNamePolicy::isGlobal)
    }
}

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
    /** How long the plugin asked the host to wait for it, already within what a step allows. */
    val requestedTimeoutMs: Long? = null,
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

    /**
     * Fires a setting the way Tasker does when it waits: an ordered broadcast carrying a completion
     * intent, then the plugin's answer, or null when none came within [timeoutMs]. A transport with
     * no plugin behind it sends it plainly and reports no answer, which reads as a plain Locale plugin.
     */
    suspend fun fireSettingAndWait(intent: Intent, timeoutMs: Long): LocalePluginSettingReply? {
        sendSetting(intent)
        return LocalePluginSettingReply(LocalePluginContract.RESULT_CODE_UNKNOWN)
    }
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

    /**
     * The completion intent is an action only this fire knows, with a random token in it, sent to
     * OpenTasker's package, so a receiver registered for just this wait gets it and nothing else
     * can answer for the plugin. The receiver goes away when the wait ends either way.
     */
    override suspend fun fireSettingAndWait(intent: Intent, timeoutMs: Long): LocalePluginSettingReply? =
        withContext(dispatcher) {
            requireDeliverable(intent)
            val completionAction = "${appContext.packageName}.PLUGIN_FINISHED.${LocalePluginCompletion.newToken()}"
            val completion = CompletableDeferred<LocalePluginSettingReply>()
            val completionReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, received: Intent) {
                    if (received.action != completionAction) return
                    completion.complete(LocalePluginCompletion.reply(received))
                }
            }
            ContextCompat.registerReceiver(
                appContext,
                completionReceiver,
                IntentFilter(completionAction),
                ContextCompat.RECEIVER_EXPORTED,
            )
            try {
                // A step waits on it, so it goes in the foreground queue rather than behind
                // background broadcasts while a cold plugin starts.
                intent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                val completionIntent = Intent(completionAction).setPackage(appContext.packageName)
                intent.putExtra(LocalePluginContract.EXTRA_COMPLETION_INTENT, completionIntent.toUri(Intent.URI_INTENT_SCHEME))
                intent.putExtra(
                    LocalePluginContract.EXTRA_HINTS,
                    Bundle().apply { putInt(LocalePluginContract.HINT_TIMEOUT_MS, timeoutMs.toInt()) },
                )
                withTimeoutOrNull(timeoutMs) {
                    val ordered = suspendCancellableCoroutine { continuation ->
                        val resultReceiver = object : BroadcastReceiver() {
                            override fun onReceive(context: Context, ignored: Intent?) {
                                if (!continuation.isActive) return
                                continuation.resume(LocalePluginCompletion.reply(resultCode, getResultExtras(false)))
                            }
                        }
                        appContext.sendOrderedBroadcast(
                            intent,
                            null,
                            resultReceiver,
                            null,
                            LocalePluginContract.RESULT_CODE_UNKNOWN,
                            null,
                            null,
                        )
                    }
                    // Some plugins send their answer before letting the broadcast go, without saying PENDING.
                    if (completion.isCompleted || ordered.resultCode == LocalePluginContract.RESULT_CODE_PENDING) {
                        completion.await()
                    } else {
                        ordered
                    }
                }
            } finally {
                runCatching { appContext.unregisterReceiver(completionReceiver) }
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
 * Unparceling a Parcelable or Serializable builds the class it names and runs that class's code.
 * What a plugin sends is read with the boot class loader, which has only Android's and Java's own
 * classes, so a value naming a class packed in OpenTasker fails to read instead of being built.
 * Set it before anything unparcels: the system hands receivers the app's own loader.
 */
internal object PluginParcels {
    val classLoader: ClassLoader = checkNotNull(Bundle::class.java.classLoader)

    /**
     * Reads part of what a plugin sent. A value that can't be read throws a RuntimeException, and
     * one nested deep enough runs out of stack while needing nothing but Android's own classes.
     */
    inline fun <T> read(read: () -> T, unreadable: (Throwable) -> T): T = try {
        read()
    } catch (e: RuntimeException) {
        unreadable(e)
    } catch (e: StackOverflowError) {
        unreadable(e)
    }
}

/** The token in a waited-for fire's completion action, and reading what came back. */
internal object LocalePluginCompletion {
    private val random = SecureRandom()

    fun newToken(): String = ByteArray(16).also(random::nextBytes).joinToString("") { "%02x".format(it) }

    /** The answer a plugin sent back through its completion intent. */
    fun reply(received: Intent): LocalePluginSettingReply {
        received.setExtrasClassLoader(PluginParcels.classLoader)
        return reply(
            { received.getIntExtra(LocalePluginContract.EXTRA_RESULT_CODE, LocalePluginContract.RESULT_CODE_UNKNOWN) },
            { received.getBundleExtra(LocalePluginContract.EXTRA_VARIABLES) },
        )
    }

    /** The answer a plugin set on the ordered fire broadcast. */
    fun reply(code: Int, resultExtras: Bundle?): LocalePluginSettingReply {
        resultExtras?.classLoader = PluginParcels.classLoader
        return reply({ code }, { resultExtras?.getBundle(LocalePluginContract.EXTRA_VARIABLES) })
    }

    /**
     * Reading what a plugin sent unparcels it, which throws for a class this process doesn't have.
     * A code that can't be read is a failure with nothing returned. Variables that can't be read
     * leave the plugin's code standing, and a value that can't be read is refused on its own.
     */
    fun reply(readCode: () -> Int, readVariables: () -> Bundle?): LocalePluginSettingReply {
        val code = PluginParcels.read(readCode) { e ->
            return LocalePluginSettingReply(
                LocalePluginContract.RESULT_CODE_FAILED,
                LocalePluginReturnedVariables(refused = listOf("the plugin's answer couldn't be read (${e.javaClass.simpleName})")),
            )
        }
        val variables = PluginParcels.read(
            { readVariables()?.let(::readEach)?.let(LocalePluginReturnedVariables::from) ?: LocalePluginReturnedVariables() },
        ) { e ->
            LocalePluginReturnedVariables(refused = listOf("the plugin's variables couldn't be read (${e.javaClass.simpleName})"))
        }
        return LocalePluginSettingReply(code, variables)
    }

    /** Android 13 and newer read each value on first use, so one is refused alone; older ones read all at once. */
    @Suppress("DEPRECATION")
    private fun readEach(bundle: Bundle): Map<String, Any?> {
        bundle.classLoader = PluginParcels.classLoader
        return bundle.keySet().associateWith { key ->
            PluginParcels.read({ bundle.get(key) }) { LocalePluginReturnedVariables.Unreadable }
        }
    }
}

/**
 * What a waited-for fire means for the step, by Tasker's result codes: OK and OK with minor
 * failures finish it, UNKNOWN is a plugin that never touched the code (a plain Locale plugin, sent
 * its settings and done), and anything else is the plugin saying it failed, with `%errmsg` if it
 * sent one. No answer within the wait is a failure too, as Tasker times a plugin step out.
 */
internal object LocalePluginSettingOutcome {
    fun of(reply: LocalePluginSettingReply?, target: String, timeoutMs: Long): LocalePluginResult {
        if (reply == null) {
            return LocalePluginResult(
                success = false,
                message = "Plugin $target didn't finish within $timeoutMs ms.",
                resultCode = LocalePluginContract.RESULT_CODE_PENDING,
            )
        }
        val code = reply.resultCode
        val (success, message) = when (code) {
            LocalePluginContract.RESULT_CODE_OK -> true to "Plugin $target finished."
            LocalePluginContract.RESULT_CODE_OK_MINOR_FAILURES -> true to "Plugin $target finished with minor failures."
            LocalePluginContract.RESULT_CODE_UNKNOWN -> true to "Locale plugin setting dispatched to $target."
            else -> false to buildString {
                append("Plugin $target failed (result code $code)")
                reply.variables.errorMessage?.takeIf(String::isNotBlank)?.let { append(": ").append(it.take(500)) }
                append('.')
            }
        }
        return LocalePluginResult(success, message, code, reply.variables)
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
        val depth = JsonNesting.depth(trimmed)
        require(depth <= LocalePluginContract.MAX_BUNDLE_DEPTH) {
            if (depth == JsonNesting.NOT_JSON) {
                "Plugin bundle JSON is malformed or nests deeper than a typed value."
            } else {
                "Plugin bundle JSON nests deeper than a typed value."
            }
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
            requireValueSize(key, decoded)
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
        // Only to keep the parser off the stack: an entry past MAX_BUNDLE_DEPTH is left out on its own below.
        if (!JsonNesting.within(bundleJson, MAX_EDITING_DEPTH)) return emptyMap()
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
            LocaleBundleValue.Text(text).also { requireValueSize(key, it) }
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

    private const val MAX_EDITING_DEPTH = 64

    private fun requireValidKey(key: String) {
        require(key.isNotBlank()) { "Plugin bundle keys must not be blank." }
        require(key.toByteArray(Charsets.UTF_8).size <= LocalePluginContract.MAX_BUNDLE_KEY_BYTES) {
            "Plugin bundle key ${key.take(40)}... is too large."
        }
    }

    private fun requireValueSize(key: String, value: LocaleBundleValue) {
        require(encodeValue(value).toString().toByteArray(Charsets.UTF_8).size <= LocalePluginContract.MAX_BUNDLE_JSON_BYTES) {
            "Plugin bundle value for $key is too large."
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

    /**
     * A plugin's saved configuration with its types kept, so firing it sends what the plugin wrote.
     * Each value is read with [PluginParcels.classLoader] on its own, so on Android 13 and newer one
     * that can't be read is named in the refusal. Older versions read every value at once.
     */
    @Suppress("DEPRECATION")
    fun fromBundleTyped(bundle: Bundle): Map<String, LocaleBundleValue> {
        bundle.classLoader = PluginParcels.classLoader
        return sanitizeTypedValues(
            bundle.keySet().associateWith { key ->
                PluginParcels.read({ bundle.get(key) }) { error("Plugin bundle value for ${key.take(64)} couldn't be read.") }
            },
        )
    }

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
                // The bundle JSON can't carry NaN or infinity back, so they're refused here rather than at run time.
                is Float -> LocaleBundleValue.FloatNumber(value.also { require(it.isFinite()) { "Plugin bundle value for $key isn't a finite float." } })
                is Double -> LocaleBundleValue.DoubleNumber(value.also { require(it.isFinite()) { "Plugin bundle value for $key isn't a finite double." } })
                is Array<*> -> LocaleBundleValue.TextArray(stringItems(key, value.asList()))
                is ArrayList<*> -> LocaleBundleValue.TextList(stringItems(key, value))
                else -> error("Plugin bundle value for $key is a ${value.javaClass.simpleName}, which OpenTasker can't store.")
            }
            requireValueSize(key, converted)
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
        resultIntent.setExtrasClassLoader(PluginParcels.classLoader)
        val values = readingPluginData {
            val bundle = resultIntent.getBundleExtra(LocalePluginContract.EXTRA_BUNDLE)
                ?: error("Locale plugin configuration returned no bundle.")
            LocalePluginBundleCodec.fromBundleTyped(bundle)
        }
        return LocalePluginConfigurationResult(
            bundleJson = LocalePluginBundleCodec.encodeBundle(values),
            blurb = readingPluginData { resultIntent.getStringExtra(LocalePluginContract.EXTRA_BLURB) }.orEmpty().take(120),
            requestedTimeoutMs = readingPluginData {
                resultIntent.takeIf { it.hasExtra(LocalePluginContract.EXTRA_REQUESTED_TIMEOUT) }
                    ?.getIntExtra(LocalePluginContract.EXTRA_REQUESTED_TIMEOUT, -1)
            }?.let(LocalePluginContract::waitForRequestedTimeout),
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
    } catch (e: StackOverflowError) {
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
        // Tells a Tasker plugin it may ask for a wait and return variables, as it would with Tasker.
        if (!condition) intent.putExtra(LocalePluginContract.EXTRA_HOST_CAPABILITIES, LocalePluginContract.HOST_CAPABILITIES)
        return edit
    }

    /**
     * Sends the plugin its settings and, unless [LocalePluginRequest.timeoutMs] is 0, waits up to
     * that long for it to finish, by Tasker's synchronous protocol. 0 sends and goes on, as Tasker
     * does for a plugin step with no timeout; a plugin that needs the wait, like iBeacon Tasker,
     * refuses that.
     */
    suspend fun fireSetting(request: LocalePluginRequest): LocalePluginResult {
        LocalePluginBundleCodec.validatePackageName(request.packageName)
        require(request.timeoutMs in 0..LocalePluginContract.MAX_SETTING_WAIT_MS) {
            "Plugin wait must be between 0 and ${LocalePluginContract.MAX_SETTING_WAIT_MS} ms."
        }

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
        ).putExtra(LocalePluginContract.EXTRA_HOST_CAPABILITIES, LocalePluginContract.HOST_CAPABILITIES)

        val receiverName = component.flattenToShortString()
        return try {
            if (request.timeoutMs == 0L) {
                withTimeoutOrNull(SETTING_DISPATCH_TIMEOUT_MS) { transport.sendSetting(intent) }
                    ?: return LocalePluginResult(false, "Locale plugin setting couldn't be sent to $receiverName within $SETTING_DISPATCH_TIMEOUT_MS ms.")
                LocalePluginResult(true, "Locale plugin setting dispatched to $receiverName.")
            } else {
                val reply = transport.fireSettingAndWait(intent, request.timeoutMs)
                LocalePluginSettingOutcome.of(reply, receiverName, request.timeoutMs)
            }
        } catch (error: SecurityException) {
            LocalePluginResult(false, error.message ?: "Locale plugin setting was not deliverable.")
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

    /**
     * The bundle goes in [LocalePluginContract.EXTRA_BUNDLE] and, as Tasker sends it, on the intent
     * itself too: plugins written against Tasker, Termux:Tasker among them, read some settings with
     * `intent.getStringExtra`. The host's own extras go on last so a bundle key can't replace them.
     */
    private fun pluginBroadcastIntent(
        action: String,
        request: LocalePluginRequest,
        pluginBundle: Bundle,
        target: ComponentName,
    ): Intent = Intent(action)
        .setComponent(target)
        .putExtras(pluginBundle)
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

/** A step with no wait still gets this long for the broadcast to leave. */
private const val SETTING_DISPATCH_TIMEOUT_MS = 5_000L

/**
 * Whether [packageName] has the receiver a plugin step sends to. That's the only setup a plugin step
 * has, so it's what the step's "Needs setup" pill goes by. Asks the package manager, so not on the
 * main thread.
 */
fun localePluginReceiverInstalled(context: Context, packageName: String, condition: Boolean): Boolean =
    localePluginReceiverResolves(AndroidLocalePluginComponentResolver(context.packageManager), packageName, condition)

/**
 * A step saves only its package, and firing or querying it refuses a package with no receiver for
 * the step's action or more than one, so the pill asks for exactly one too.
 */
internal fun localePluginReceiverResolves(
    resolver: LocalePluginComponentResolver,
    packageName: String,
    condition: Boolean,
): Boolean =
    PackageNamePolicy.isValid(packageName) &&
        resolver.broadcastReceivers(
            packageName,
            if (condition) LocalePluginContract.ACTION_QUERY_CONDITION else LocalePluginContract.ACTION_FIRE_SETTING,
        ).size == 1

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
