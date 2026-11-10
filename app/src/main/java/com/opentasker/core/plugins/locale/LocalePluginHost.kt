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

object LocalePluginBundleCodec {
    fun validatePackageName(packageName: String) {
        require(PackageNamePolicy.isValid(packageName)) { "Invalid plugin package name." }
    }

    fun decodeStringBundle(bundleJson: String): Map<String, String> {
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
            require(key.isNotBlank()) { "Plugin bundle keys must not be blank." }
            require(key.toByteArray(Charsets.UTF_8).size <= LocalePluginContract.MAX_BUNDLE_KEY_BYTES) {
                "Plugin bundle key is too large."
            }
            val primitive = value as? JsonPrimitive ?: error("Plugin bundle values must be primitive strings, numbers, or booleans.")
            val stringValue = primitive.contentOrNull
                ?: primitive.booleanOrNull?.toString()
                ?: primitive.longOrNull?.toString()
                ?: primitive.intOrNull?.toString()
                ?: primitive.doubleOrNull?.toString()
                ?: error("Plugin bundle values must not be null.")
            require(stringValue.toByteArray(Charsets.UTF_8).size <= LocalePluginContract.MAX_BUNDLE_JSON_BYTES) {
                "Plugin bundle value is too large."
            }
            key to stringValue
        }
    }

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
        val bundle = resultIntent.getBundleExtra(LocalePluginContract.EXTRA_BUNDLE)
            ?: error("Locale plugin configuration returned no bundle.")
        val values = LocalePluginBundleCodec.fromBundle(bundle)
        return LocalePluginConfigurationResult(
            bundleJson = LocalePluginBundleCodec.encodeStringBundle(values),
            blurb = resultIntent.getStringExtra(LocalePluginContract.EXTRA_BLURB).orEmpty().take(120),
        )
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

    suspend fun fireSetting(request: LocalePluginRequest): LocalePluginResult {
        LocalePluginBundleCodec.validatePackageName(request.packageName)
        require(request.timeoutMs in 1_000..30_000) { "Plugin timeout must be between 1000 and 30000 ms." }

        val bundleValues = LocalePluginBundleCodec.decodeStringBundle(request.bundleJson)
        signerRefusal(request.packageName)?.let { refusal -> return LocalePluginResult(false, refusal) }
        val pluginBundle = LocalePluginBundleCodec.toBundle(bundleValues)
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

        val bundleValues = LocalePluginBundleCodec.decodeStringBundle(request.bundleJson)
        // Straight back as Unknown, not through the cache: a last known answer would hide the refusal.
        signerRefusal(request.packageName)?.let { refusal ->
            return LocalePluginConditionResult(state = LocalePluginConditionState.Unknown, message = refusal)
        }
        val pluginBundle = LocalePluginBundleCodec.toBundle(bundleValues)
        val cacheKey = LocalePluginConditionCacheKey(
            packageName = request.packageName,
            bundleValues = bundleValues.toSortedMap(),
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
): List<ComponentName> =
    pm.queryIntentActivities(Intent(action).setPackage(packageName), PackageManager.MATCH_DEFAULT_ONLY)
        .mapNotNull { resolveInfo ->
            val activityInfo = resolveInfo.activityInfo ?: return@mapNotNull null
            if (activityInfo.packageName != packageName) return@mapNotNull null
            ComponentName(packageName, activityInfo.name.toClassName(packageName))
        }
        .sortedBy { it.className }

private fun queryPackages(pm: PackageManager, action: String): Set<String> =
    pm.queryIntentActivities(Intent(action), PackageManager.MATCH_DEFAULT_ONLY)
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
