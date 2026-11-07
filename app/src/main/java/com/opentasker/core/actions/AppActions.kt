package com.opentasker.core.actions

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import com.opentasker.app.BuildConfig
import com.opentasker.core.engine.ActionContext
import com.opentasker.core.engine.ActionResult
import com.opentasker.core.platform.BackgroundActivityStart
import com.opentasker.widget.TaskShortcutHelper

/**
 * Launch an application.
 *
 * Args:
 *   - "package": package name (e.g., "com.spotify.music")
 */
class LaunchAppAction : DeclaredAction(ActionCatalog.require("app.launch")) {

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val pkg = args["package"] ?: return ActionResult.Failure("missing package")
        return try {
            val intent = ctx.app.packageManager.getLaunchIntentForPackage(pkg)
                ?: return ActionResult.Failure("app not found: $pkg")
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val result = startActivityFromAction(ctx, intent, pkg)
            ctx.logger("Launch: $pkg")
            result
        } catch (e: Exception) {
            ActionResult.Failure("launch failed: ${e.message}")
        }
    }
}

/**
 * Starts the activity whatever the odds, so a screen Android allows for a reason this app can't see
 * still opens, then says so when Android most likely dropped it. [what] names it for that message.
 */
internal fun startActivityFromAction(ctx: ActionContext, intent: Intent, what: String): ActionResult {
    val blocked = BackgroundActivityStart.likelyBlocked(ctx.app)
    ctx.app.startActivity(intent)
    return if (blocked) ActionResult.Failure(BackgroundActivityStart.failureMessage(what)) else ActionResult.Success
}

/** Publish a dynamic launcher shortcut or request a pinned shortcut for an existing task. */
class ShortcutPublishAction : DeclaredAction(ActionCatalog.require("shortcut.publish")) {

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val shortcutId = args["id"]?.trim().orEmpty()
        val taskId = args["task_id"]?.trim()?.toLongOrNull() ?: return ActionResult.Failure("task_id must be a positive number")
        val label = args["label"]?.trim().orEmpty()
        val mode = args["mode"]?.trim()?.lowercase().orEmpty().ifBlank { "dynamic" }
        val validation = TaskShortcutHelper.validatePublish(shortcutId, taskId, label, mode)
        if (!validation.isValid) return ActionResult.Failure(validation.error ?: "invalid shortcut")
        val publishMode = TaskShortcutHelper.PublishMode.valueOf(mode.uppercase())
        return if (TaskShortcutHelper.publishShortcut(ctx.app, shortcutId, taskId, label, publishMode)) {
            ctx.logger("Shortcut published: $shortcutId ($mode)")
            ActionResult.Success
        } else {
            ActionResult.Failure("shortcut request was declined or unsupported")
        }
    }
}

/**
 * Kill (force-stop) an application.
 *
 * Args:
 *   - "package": package name
 */
class KillAppAction : DeclaredAction(ActionCatalog.require("app.kill")) {

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val pkg = args["package"] ?: return ActionResult.Failure("missing package")
        ctx.logger("Kill: $pkg")
        return ActionResult.Failure("Killing apps is not supported without privileged app-management access")
    }
}

/**
 * Go to home screen (dismiss notifications, etc.).
 */
class GoHomeAction : DeclaredAction(ActionCatalog.require("home.go")) {

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val result = startActivityFromAction(ctx, intent, "the home screen")
        ctx.logger("Go to home")
        return result
    }
}

/**
 * Open a URL in the browser.
 *
 * Args:
 *   - "url": URL to open
 */
class OpenUrlAction : DeclaredAction(ActionCatalog.require("url.open")) {

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val url = args["url"] ?: return ActionResult.Failure("missing url")
        val uri = Uri.parse(url)
        // Fail closed on a missing scheme too. Skipping the allowlist when the scheme was null let
        // a scheme-relative value such as //host/path through to ACTION_VIEW unchecked.
        val scheme = uri.scheme?.lowercase()
        if (scheme == null || scheme !in ALLOWED_SCHEMES) {
            return ActionResult.Failure(
                "blocked URI scheme: ${scheme ?: "<none>"} (allowed: ${ALLOWED_SCHEMES.joinToString()})",
            )
        }
        return try {
            val intent = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val result = startActivityFromAction(ctx, intent, "the link")
            ctx.logger("Open URL: $url")
            result
        } catch (e: Exception) {
            ActionResult.Failure("open failed: ${e.message}")
        }
    }

    companion object {
        private val ALLOWED_SCHEMES = setOf("https", "http", "tel", "mailto", "geo")
        fun allowedSchemes(): Set<String> = ALLOWED_SCHEMES
    }
}

/**
 * Send an SMS.
 *
 * Args:
 *   - "number": recipient phone number
 *   - "message": SMS text
 */
class SendSmsAction : DeclaredAction(ActionCatalog.require("sms.send")) {

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        if (!BuildConfig.SMS_ACTION_AVAILABLE) {
            return ActionResult.Failure("SMS action is unavailable in this distribution because SMS permissions are omitted for Play policy compliance")
        }
        val number = args["number"] ?: return ActionResult.Failure("missing number")
        val message = args["message"] ?: ""
        if (ContextCompat.checkSelfPermission(ctx.app, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            return ActionResult.Failure("SMS permission is not granted")
        }
        if (message.isBlank()) return ActionResult.Failure("missing message")
        return try {
            val smsManager = if (android.os.Build.VERSION.SDK_INT >= 31) {
                ctx.app.getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            } ?: return ActionResult.Failure("SMS service not available")
            smsManager.sendTextMessage(number, null, message, null, null)
            // Mask the recipient in the persisted run log — full numbers are PII and run-log
            // redaction does not scrub phone numbers.
            ctx.logger("SMS sent to ${maskPhoneNumber(number)}")
            ActionResult.Success
        } catch (ex: Exception) {
            ActionResult.Failure("SMS send failed: ${ex.message}", ex)
        }
    }
}

/** Masks all but the last 4 characters of a recipient number for logging (e.g. "***6789"). */
internal fun maskPhoneNumber(number: String): String {
    val tail = number.takeLast(4)
    return if (number.length <= 4) "***" else "***$tail"
}

/**
 * Take a screenshot.
 *
 * Args:
 *   - "path": optional output file path
 */
class ScreenshotAction : DeclaredAction(ActionCatalog.require("screenshot.take")) {

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val path = args["path"] ?: args["filename"]
            ?: ctx.app.getExternalFilesDir(null)?.resolve("screenshot.png")?.absolutePath
            ?: "app-specific external storage/screenshot.png"
        ctx.logger("Screenshot: $path")
        return ctx.runShizukuScreenshot(path)
    }
}
