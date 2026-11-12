package com.opentasker.core.actions

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.opentasker.app.OpenTaskerApp_NoHilt
import com.opentasker.app.R
import kotlinx.coroutines.suspendCancellableCoroutine
import com.opentasker.core.engine.ActionContext
import com.opentasker.core.engine.ActionResult
import com.opentasker.core.engine.isArgumentSensitive
import com.opentasker.core.model.VariableNamePolicy
import com.opentasker.core.platform.AndroidAudioHardening
import com.opentasker.core.platform.AudioOutputStream
import com.opentasker.core.platform.PromotedOngoingNotificationSupport
import java.util.concurrent.atomic.AtomicInteger

/**
 * Notification action — display a toast or heads-up notification.
 *
 * Args:
 *   - "title": notification title
 *   - "text": notification body
 *   - "duration": "short" or "long" (Toast duration only)
 */
class NotifyAction : DeclaredAction(ActionCatalog.require("notify.show")) {

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val title = args["title"] ?: "Notification"
        val text = args["text"] ?: ""
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx.app, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return ActionResult.Failure("Notification permission is not granted")
        }

        val nm = ctx.app.getSystemService(NotificationManager::class.java)
        val channelKey = args["channel"] ?: "default"
        val channelDef = NotificationChannels.resolve(channelKey)
        nm.createNotificationChannel(
            NotificationChannel(channelDef.id, channelDef.name, channelDef.importance),
        )

        val channel = nm.getNotificationChannel(channelDef.id)
        if (channel != null && channel.importance == NotificationManager.IMPORTANCE_NONE) {
            ctx.logger("Warning: channel '${channelDef.name}' is blocked by the user")
            return ActionResult.Failure("Notification channel '${channelDef.name}' is blocked by the user; open system settings to unblock")
        }

        val persistent = args["persistent"]?.toBooleanStrictOrNull() ?: false
        val tag = args["tag"]
        val notifId = args["id"]?.toIntOrNull() ?: nextNotificationId.getAndIncrement()

        val builder = NotificationCompat.Builder(ctx.app, channelDef.id)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ContextCompat.getColor(ctx.app, R.color.notification_accent))
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(!persistent)
            .setOngoing(persistent)

        val taskReferences = (1..NotificationTaskBindings.BUTTON_COUNT).mapNotNull { buttonIndex ->
            NotificationTaskBindings.parse(args, buttonIndex)?.let { buttonIndex to it }
        }.toMap()
        val taskCandidates = if (taskReferences.isEmpty()) {
            emptyList()
        } else {
            OpenTaskerApp_NoHilt.db.taskDao().getAll().map { NotificationTaskCandidate(it.id, it.name) }
        }

        for (i in 1..NotificationTaskBindings.BUTTON_COUNT) {
            val reference = taskReferences[i]
            if (reference == null) {
                when (val link = NotificationLinkButton.parse(args, i)) {
                    null -> Unit
                    // The scheme only. The address can carry a caller's number or a message.
                    is NotificationLinkButton.Blocked -> return ActionResult.Failure(
                        "Notification button $i cannot open ${link.scheme.ifEmpty { "<no scheme>" }} links",
                    )
                    is NotificationLinkButton.Link -> builder.addAction(
                        0,
                        link.label,
                        // A tap on a notification may start an activity from the background, which
                        // url.open can't do without Display over other apps.
                        PendingIntent.getActivity(
                            ctx.app,
                            PendingIntentRequestCodes.next(),
                            Intent(Intent.ACTION_VIEW, Uri.parse(link.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                        ),
                    )
                }
                continue
            }
            val resolution = NotificationTaskBindings.resolve(reference, taskCandidates)
            if (resolution !is NotificationTaskResolution.Bound) {
                return ActionResult.Failure(
                    "Notification button $i is not runnable: ${NotificationTaskBindings.failureMessage(resolution)}",
                )
            }
            val label = args["button${i}_label"] ?: resolution.task.name
            // A unique request code per button guarantees two notifications (even with adjacent
            // ids) never share a PendingIntent slot, so FLAG_UPDATE_CURRENT can't overwrite an
            // older button intent and fire the wrong task.
            val requestCode = PendingIntentRequestCodes.next()
            val buttonIntent = Intent(ctx.app, NotificationActionReceiver::class.java).apply {
                action = NotificationActionReceiver.ACTION_NOTIFICATION_BUTTON
                putExtra(NotificationActionReceiver.EXTRA_TASK_ID, resolution.task.id)
                putExtra(NotificationActionReceiver.EXTRA_BUTTON_LABEL, label)
                putExtra("_req", requestCode)
            }
            val pi = PendingIntent.getBroadcast(
                ctx.app,
                requestCode,
                buttonIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.addAction(0, label, pi)
        }

        val notification = builder.build()

        return try {
            NotificationManagerCompat.from(ctx.app).notify(tag, notifId, notification)
            ctx.logger("Notify: $title | $text (channel=${channelDef.name}, id=$notifId${if (tag != null) ", tag=$tag" else ""})")
            ActionResult.Success
        } catch (ex: SecurityException) {
            ActionResult.Failure("notification failed: ${ex.message}", ex)
        }
    }

    companion object {
        private val nextNotificationId = AtomicInteger(10_000)
    }
}

/** Posts an ordered progress notification on Android 16+ and a normal progress bar below it. */
class ProgressNotificationAction : DeclaredAction(ActionCatalog.require("notify.progress")) {

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx.app, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return ActionResult.Failure("Notification permission is not granted")
        }
        val progress = parseProgress(args["progress"])
            ?: return ActionResult.Failure("progress must be an integer from 0 to 100")
        if (progress !in 0..100) return ActionResult.Failure("progress must be an integer from 0 to 100")
        val channelDef = NotificationChannels.resolve(args["channel"] ?: "default")
        val manager = ctx.app.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(channelDef.id, channelDef.name, channelDef.importance))
        val title = args["title"]?.takeIf { it.isNotBlank() } ?: "Progress"
        val text = args["text"].orEmpty()
        val id = args["id"]?.toIntOrNull() ?: nextNotificationId.getAndIncrement()
        val tag = args["tag"]?.takeIf { it.isNotBlank() }
        val segments = parseSegmentLengths(args["segments"])
        val channelImportance = manager.getNotificationChannel(channelDef.id)?.importance ?: channelDef.importance

        val notification = if (Build.VERSION.SDK_INT >= 36) {
            val style = Notification.ProgressStyle()
                .setProgress(progress)
                .setStyledByProgress(true)
            if (segments.isNotEmpty()) {
                style.setProgressSegments(segments.map { Notification.ProgressStyle.Segment(it) })
            }
            val builder = Notification.Builder(ctx.app, channelDef.id)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(text)
                .setCategory(Notification.CATEGORY_PROGRESS)
                .setOnlyAlertOnce(true)
                .setOngoing(progress < 100)
                .setShortCriticalText("$progress%")
                .setStyle(style)
            PromotedOngoingNotificationSupport.build(
                builder = builder,
                manager = manager,
                channelImportance = channelImportance,
                title = title,
                ongoing = progress < 100,
            )
        } else {
            NotificationCompat.Builder(ctx.app, channelDef.id)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(text)
                .setProgress(100, progress, false)
                .setOnlyAlertOnce(true)
                .setOngoing(progress < 100)
                .build()
        }
        return try {
            NotificationManagerCompat.from(ctx.app).notify(tag, id, notification)
            ctx.logger("Progress notification: $progress% (id=$id)")
            ActionResult.Success
        } catch (ex: SecurityException) {
            ActionResult.Failure("progress notification failed: ${ex.message}", ex)
        }
    }

    private companion object {
        val nextNotificationId = AtomicInteger(20_000)
    }
}

internal fun parseProgress(value: String?): Int? = value?.trim()?.toIntOrNull()?.takeIf { it in 0..100 }

internal fun parseSegmentLengths(value: String?): List<Int> = value.orEmpty()
    .split(',', ';')
    .mapNotNull { it.trim().toIntOrNull() }
    .filter { it > 0 }

class NotifyCancelAction : DeclaredAction(ActionCatalog.require("notify.cancel")) {

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val tag = args["tag"]
        val notifId = args["id"]?.toIntOrNull()
        val nm = NotificationManagerCompat.from(ctx.app)

        return when {
            tag != null && notifId != null -> {
                nm.cancel(tag, notifId)
                ctx.logger("Cancel notification: tag=$tag, id=$notifId")
                ActionResult.Success
            }
            notifId != null -> {
                nm.cancel(notifId)
                ctx.logger("Cancel notification: id=$notifId")
                ActionResult.Success
            }
            tag != null -> {
                val mgr = ctx.app.getSystemService(NotificationManager::class.java)
                val cancelled = mgr.activeNotifications.filter { it.tag == tag }
                cancelled.forEach { nm.cancel(it.tag, it.id) }
                ctx.logger("Cancel notification: tag=$tag (${cancelled.size} cancelled)")
                ActionResult.Success
            }
            else -> ActionResult.Failure("Specify at least one of 'tag' or 'id' to cancel")
        }
    }
}

internal object NotificationChannels {
    data class ChannelDef(
        val id: String,
        val name: String,
        val importance: Int,
    )

    private val channels = mapOf(
        "quiet" to ChannelDef("opentasker.quiet", "OpenTasker quiet", NotificationManager.IMPORTANCE_LOW),
        "default" to ChannelDef("opentasker.actions", "OpenTasker actions", NotificationManager.IMPORTANCE_DEFAULT),
        "urgent" to ChannelDef("opentasker.urgent", "OpenTasker urgent", NotificationManager.IMPORTANCE_HIGH),
    )

    fun resolve(key: String): ChannelDef =
        channels[key.trim().lowercase()] ?: channels.getValue("default")

    fun allKeys(): Set<String> = channels.keys
}

/**
 * Variable set action.
 *
 * Args:
 *   - "name": variable name, or a dotted/bracketed path for nested JSON writes
 *     (e.g. "config.theme", "items[0]", "Config.user.name")
 *   - "value": new value (supports %expansion)
 */
class SetVariableAction : DeclaredAction(ActionCatalog.require("var.set")) {

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val name = args["name"] ?: return ActionResult.Failure("missing name")
        val value = args["value"] ?: ""
        if (name.contains('.') || name.contains('[')) {
            if (!ctx.variables.setAtPath(name, value)) {
                return ActionResult.Failure("invalid path, or the variable's JSON nests too deep: $name")
            }
            val baseName = name.takeWhile { it != '.' && it != '[' }
            val loggedValue = if (ctx.isArgumentSensitive("value") || ctx.variables.isSensitive(baseName)) {
                REDACTED_VARIABLE_VALUE
            } else {
                value
            }
            ctx.logger("Set path \$$name = $loggedValue")
        } else {
            ctx.variables.set(name, value)
            val loggedValue = if (ctx.isArgumentSensitive("value") || ctx.variables.isSensitive(name)) {
                REDACTED_VARIABLE_VALUE
            } else {
                value
            }
            ctx.logger("Set \$$name = $loggedValue")
        }
        return ActionResult.Success
    }
}

/**
 * Persist a variable to global scope.
 *
 * Copies the current value of a variable into the global namespace
 * so it survives across task invocations within the same service lifetime.
 *
 * Args:
 *   - "name": source variable name (local or global)
 *   - "global_name": target global variable name (auto-uppercased if needed)
 */
class PersistVariableAction : DeclaredAction(ActionCatalog.require("var.persist")) {

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val rawName = args["name"] ?: return ActionResult.Failure("missing name")
        val name = VariableNamePolicy.normalize(rawName)
            ?: return ActionResult.Failure("invalid variable name '$rawName'")
        val rawGlobalName = args["global_name"] ?: name
        val globalName = VariableNamePolicy.promoteToGlobal(rawGlobalName)
            ?: return ActionResult.Failure("invalid global variable name '$rawGlobalName'")
        val value = ctx.variables.get(name)
            ?: return ActionResult.Failure("variable '$name' is not set")
        val sensitive = ctx.variables.isSensitive(name)
        ctx.variables.set(globalName, value, sensitive = sensitive)
        ctx.logger("Persist \$$name → \$$globalName = ${if (sensitive) REDACTED_VARIABLE_VALUE else value}")
        return ActionResult.Success
    }
}

private const val REDACTED_VARIABLE_VALUE = ActionArgumentSensitivity.REDACTED

/**
 * Say (text-to-speech) action.
 *
 * Args:
 *   - "text": text to speak
 *   - "stream": music (default), alarm, ring or notification (optional)
 */
class SayAction : DeclaredAction(ActionCatalog.require("tts.speak")) {

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val text = args["text"]?.takeIf { it.isNotBlank() }
            ?: return ActionResult.Failure("missing text argument")
        if (text.length > MAX_TTS_CHARS) {
            return ActionResult.Failure("text exceeds $MAX_TTS_CHARS character limit (${text.length})")
        }
        val stream = AudioOutputStream.parse(args["stream"])
            ?: return ActionResult.Failure("invalid stream: ${args["stream"]}")
        AndroidAudioHardening.failureIfIneligible(
            ctx,
            "text-to-speech output",
            stream.usage,
            offersAlarmStream = stream != AudioOutputStream.ALARM,
        )?.let { return it }
        return suspendCancellableCoroutine { cont ->
            var tts: android.speech.tts.TextToSpeech? = null
            val resumed = java.util.concurrent.atomic.AtomicBoolean(false)
            fun completeOnce(result: ActionResult) {
                if (resumed.compareAndSet(false, true)) {
                    tts?.shutdown()
                    cont.resumeWith(Result.success(result))
                }
            }
            tts = android.speech.tts.TextToSpeech(ctx.app) { status ->
                if (status != android.speech.tts.TextToSpeech.SUCCESS) {
                    completeOnce(ActionResult.Failure("TTS engine initialization failed (status=$status)"))
                    return@TextToSpeech
                }
                val engine = tts ?: return@TextToSpeech
                engine.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) { completeOnce(ActionResult.Success) }
                    @Deprecated("Deprecated in API 21+")
                    override fun onError(utteranceId: String?) { completeOnce(ActionResult.Failure("TTS utterance failed")) }
                    override fun onStop(utteranceId: String?, interrupted: Boolean) {
                        completeOnce(ActionResult.Failure("TTS utterance was stopped before it finished"))
                    }
                })
                ctx.logger("TTS: ${text.take(80)}${if (text.length > 80) "..." else ""}")
                if (stream != AudioOutputStream.MUSIC) {
                    engine.setAudioAttributes(outputAudioAttributes(stream, android.media.AudioAttributes.CONTENT_TYPE_SPEECH))
                }
                val queued = engine.speak(text, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "opentasker_say")
                if (queued != android.speech.tts.TextToSpeech.SUCCESS) {
                    // No utterance callback will ever fire for a failed queue; fail fast
                    // instead of burning the whole action budget on a silent timeout.
                    completeOnce(ActionResult.Failure("TTS could not queue the utterance"))
                }
            }
            cont.invokeOnCancellation { tts.shutdown() }
        }
    }

    companion object {
        private const val MAX_TTS_CHARS = 4000
    }
}

/**
 * Wait action — pause task execution.
 *
 * Args:
 *   - "millis": milliseconds to wait
 */
class WaitAction : DeclaredAction(ActionCatalog.require("flow.wait")) {

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val rawMillis = args["millis"] ?: return ActionResult.Failure("missing millis")
        val ms = rawMillis.toLongOrNull() ?: return ActionResult.Failure("invalid millis: $rawMillis")
        if (ms < 0) {
            return ActionResult.Failure("wait duration must be non-negative")
        }
        if (ms > MAX_WAIT_MS) {
            return ActionResult.Failure("wait duration ${ms}ms exceeds maximum of ${MAX_WAIT_MS / 60_000} minutes")
        }
        if (ms > 0) {
            ctx.logger("Wait ${ms}ms")
            kotlinx.coroutines.delay(ms)
        }
        return ActionResult.Success
    }

    companion object {
        private const val MAX_WAIT_MS = 1_800_000L // 30 minutes
    }
}

/**
 * Bounded activity, broadcast, and service intent dispatch.
 *
 * Args:
 *   - "package": target package
 *   - "mode": activity, broadcast, or service (defaults to activity)
 *   - "component": explicit class for a broadcast/service and approved external target
 *   - "action": intent action (optional for activity/component dispatch)
 *   - "category": intent category (optional)
 *   - "uri", "mime_type": bounded data URI and MIME type
 *   - "flags": comma-separated allowlisted flag names
 *   - "extras": key=string:value, key=int:value, or key=bool:value lines
 *   - "result_variable": ordered-broadcast result code destination
 */
class LaunchIntentAction : DeclaredAction(ActionCatalog.require("intent.launch")) {

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val plan = when (val parsed = IntentDispatchPolicy.parse(args)) {
            is IntentDispatchParseResult.Valid -> parsed.plan
            is IntentDispatchParseResult.Invalid -> return ActionResult.Failure(parsed.message)
        }
        return try {
            val intent = buildIntent(ctx, plan)
                ?: return ActionResult.Failure("target component was not found or is not exported")
            applyIntentPayload(intent, plan)
            val dispatchResult = when (plan.mode) {
                IntentDispatchMode.ACTIVITY -> startActivityFromAction(ctx, intent, "the activity")
                IntentDispatchMode.BROADCAST -> if (plan.resultVariable == null) {
                    ctx.app.sendBroadcast(intent)
                } else {
                    sendOrderedBroadcast(ctx, intent, plan.resultVariable)
                }
                IntentDispatchMode.SERVICE -> ctx.app.startService(intent)
            }
            ctx.logger("Intent ${plan.mode.name.lowercase()} dispatch: ${plan.packageName}")
            if (dispatchResult is ActionResult.Failure) dispatchResult else ActionResult.Success
        } catch (ex: Exception) {
            ActionResult.Failure("intent ${plan.mode.name.lowercase()} dispatch failed: ${ex.message}", ex)
        }
    }

    private fun buildIntent(ctx: ActionContext, plan: IntentDispatchPlan): Intent? {
        val packageManager = ctx.app.packageManager
        val explicitComponent = plan.componentClassName?.let { ComponentName(plan.packageName, it) }
        val intent = if (plan.mode == IntentDispatchMode.ACTIVITY && plan.action == null && explicitComponent == null) {
            packageManager.getLaunchIntentForPackage(plan.packageName)
                ?: return null
        } else {
            Intent().apply {
                plan.action?.let(::setAction)
                setPackage(plan.packageName)
            }
        }.apply {
            explicitComponent?.let(::setComponent)
        }

        val component = intent.component ?: when (plan.mode) {
            IntentDispatchMode.ACTIVITY -> intent.resolveActivity(packageManager)
            IntentDispatchMode.BROADCAST, IntentDispatchMode.SERVICE -> null
        }
        if (plan.mode != IntentDispatchMode.ACTIVITY && component == null) return null
        if (component != null) {
            if (component.packageName != plan.packageName) return null
            if (component.packageName != ctx.app.packageName && !isExported(packageManager, plan.mode, component)) {
                return null
            }
            intent.component = component
        }
        if (plan.mode != IntentDispatchMode.ACTIVITY && plan.packageName != ctx.app.packageName) {
            // Broadcasts and services never use an external package-scoped implicit dispatch.
            if (explicitComponent == null) return null
        }
        if (plan.mode == IntentDispatchMode.ACTIVITY && plan.action != null &&
            plan.packageName != ctx.app.packageName && explicitComponent == null
        ) {
            // A package plus arbitrary action is not an approval of a particular exported
            // component; require the user to choose the class explicitly.
            return null
        }
        return intent
    }

    private fun isExported(
        packageManager: PackageManager,
        mode: IntentDispatchMode,
        component: ComponentName,
    ): Boolean = runCatching {
        when (mode) {
            IntentDispatchMode.ACTIVITY -> packageManager.getActivityInfo(component, 0).exported
            IntentDispatchMode.BROADCAST -> packageManager.getReceiverInfo(component, 0).exported
            IntentDispatchMode.SERVICE -> packageManager.getServiceInfo(component, 0).exported
        }
    }.getOrDefault(false)

    private fun applyIntentPayload(intent: Intent, plan: IntentDispatchPlan) {
        plan.category?.let(intent::addCategory)
        when {
            plan.uri != null && plan.mimeType != null -> intent.setDataAndType(Uri.parse(plan.uri), plan.mimeType)
            plan.uri != null -> intent.data = Uri.parse(plan.uri)
            plan.mimeType != null -> intent.type = plan.mimeType
        }
        if (plan.mode == IntentDispatchMode.ACTIVITY) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        plan.flags.forEach { flag ->
            intent.addFlags(
                when (flag) {
                    IntentDispatchFlag.ACTIVITY_NEW_TASK -> Intent.FLAG_ACTIVITY_NEW_TASK
                    IntentDispatchFlag.ACTIVITY_CLEAR_TOP -> Intent.FLAG_ACTIVITY_CLEAR_TOP
                    IntentDispatchFlag.ACTIVITY_SINGLE_TOP -> Intent.FLAG_ACTIVITY_SINGLE_TOP
                    IntentDispatchFlag.ACTIVITY_CLEAR_TASK -> Intent.FLAG_ACTIVITY_CLEAR_TASK
                    IntentDispatchFlag.GRANT_READ_URI -> Intent.FLAG_GRANT_READ_URI_PERMISSION
                    IntentDispatchFlag.GRANT_WRITE_URI -> Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                },
            )
        }
        plan.extras.forEach { extra ->
            when (extra.type) {
                IntentExtraType.STRING -> intent.putExtra(extra.key, extra.value)
                IntentExtraType.INT -> intent.putExtra(extra.key, extra.value.toInt())
                IntentExtraType.BOOL -> intent.putExtra(extra.key, extra.value.equals("true", ignoreCase = true))
            }
        }
    }

    private suspend fun sendOrderedBroadcast(
        ctx: ActionContext,
        intent: Intent,
        resultVariable: String,
    ): ActionResult = suspendCancellableCoroutine { continuation ->
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: android.content.Context, resultIntent: Intent?) {
                if (continuation.isActive) {
                    ctx.variables.set(resultVariable, resultCode.toString())
                    continuation.resumeWith(Result.success(ActionResult.Success))
                }
            }
        }
        try {
            ctx.app.sendOrderedBroadcast(
                intent,
                null,
                receiver,
                null,
                Activity.RESULT_CANCELED,
                null,
                null,
            )
        } catch (ex: Exception) {
            if (continuation.isActive) continuation.resumeWith(Result.success(ActionResult.Failure(ex.message ?: "ordered broadcast failed", ex)))
        }
    }
}
