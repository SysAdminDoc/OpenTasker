package com.opentasker.core.actions

import android.app.WallpaperManager
import android.net.Uri
import com.opentasker.core.engine.ActionContext
import com.opentasker.core.engine.ActionResult
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Schemes the content resolver opens on the device: a remote image would be a download first. */
private val LOCAL_IMAGE_SCHEMES = setOf("file", "content", "android.resource")

/**
 * Set the home screen or lock screen wallpaper from an image.
 *
 * Args:
 *   - "path": file path or URI. A picked image keeps its read grant across restarts.
 *   - "target": home, lock or both (default both)
 */
class SetWallpaperAction : DeclaredAction(ActionCatalog.require("wallpaper.set")) {

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val path = args["path"]?.takeIf { it.isNotBlank() } ?: return ActionResult.Failure("missing path")
        val flags = wallpaperFlags(args["target"])
            ?: return ActionResult.Failure("invalid target: ${args["target"]} (use home, lock or both)")
        if (!isLocalImage(path)) {
            return ActionResult.Failure("wallpaper.set accepts a file path or content URI, not $path")
        }
        // fromFile encodes a '#' or '?' in a file name, and the resolver only knows lowercase schemes.
        val uri = if (path.contains("://")) Uri.parse(path).normalizeScheme() else Uri.fromFile(File(path))

        val manager = WallpaperManager.getInstance(ctx.app)
        if (!manager.isWallpaperSupported || !manager.isSetWallpaperAllowed) {
            return ActionResult.Failure("this device doesn't let apps change the wallpaper")
        }
        return try {
            // Decoding and scaling a full-size photo takes long enough to stall the main thread.
            val id = withContext(Dispatchers.IO) {
                val stream = ctx.app.contentResolver.openInputStream(uri)
                    ?: throw IOException("could not open $path")
                stream.use { manager.setStream(it, null, true, flags) }
            }
            if (id == 0) {
                ActionResult.Failure("the wallpaper service rejected $path")
            } else {
                ctx.logger("Wallpaper set from $path")
                ActionResult.Success
            }
        } catch (ex: IOException) {
            // Without a media permission a typed path outside OpenTasker's own folders can't be read.
            val hint = if (uri.scheme == "file") ". Pick the image with Browse so OpenTasker can read it" else ""
            ActionResult.Failure("could not set the wallpaper: ${ex.message}$hint", ex)
        } catch (ex: SecurityException) {
            ActionResult.Failure("no access to $path; pick the image again: ${ex.message}", ex)
        }
    }

    companion object {
        /** The WallpaperManager `which` flags for a target, or null when it isn't one. */
        internal fun wallpaperFlags(target: String?): Int? = when (target?.trim()?.lowercase()) {
            null, "", "both" -> WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK
            "home" -> WallpaperManager.FLAG_SYSTEM
            "lock" -> WallpaperManager.FLAG_LOCK
            else -> null
        }

        /** A bare path reads as a file; a remote scheme is refused rather than fetched. */
        internal fun isLocalImage(path: String): Boolean =
            path.substringBefore("://", missingDelimiterValue = "file").lowercase() in LOCAL_IMAGE_SCHEMES
    }
}
