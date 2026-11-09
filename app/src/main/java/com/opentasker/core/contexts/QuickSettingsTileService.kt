package com.opentasker.core.contexts

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.opentasker.app.OpenTaskerApp_NoHilt
import com.opentasker.core.external.AutomationTargetContract
import com.opentasker.core.external.InternalTaskRunSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

open class QuickSettingsTileService : TileService() {

    protected open val slot = QuickSettingsTileSlots.DEFAULT
    private val store by lazy { QuickSettingsTileStore(this) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // Refreshed each time the panel opens, because a tap has to decide without waiting on the
    // database and tasks can be deleted without this service hearing about it.
    private var boundTaskMissing = false

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
        val taskId = store.load(slot).taskId ?: return
        scope.launch {
            val missing = withContext(Dispatchers.IO) {
                runCatching { OpenTaskerApp_NoHilt.awaitDb().taskDao().getById(taskId) == null }.getOrDefault(false)
            }
            if (missing != boundTaskMissing) {
                boundTaskMissing = missing
                updateTile()
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onClick() {
        super.onClick()
        val config = store.load(slot)
        if (config.taskId == null || boundTaskMissing) {
            val configureIntent = Intent(this, QuickSettingsTileConfigActivity::class.java)
                .putExtra(QuickSettingsTileConfigActivity.EXTRA_SLOT, slot)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (Build.VERSION.SDK_INT >= 34) {
                startActivityAndCollapse(
                    PendingIntent.getActivity(
                        this,
                        slot,
                        configureIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    ),
                )
            } else {
                @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
                fun launchLegacyPreferences() = startActivityAndCollapse(configureIntent)
                launchLegacyPreferences()
            }
            return
        }
        val runBoundTask = Runnable {
            val updated = store.setState(slot, active = !config.active)
            updateTile(updated)
            QuickSettingsTileContextEvents.publishTileClicked(updated.active, slot)
            sendBroadcast(
                AutomationTargetContract.internalRunTaskIntent(
                    context = applicationContext,
                    taskId = requireNotNull(config.taskId),
                    source = InternalTaskRunSource.QUICK_SETTINGS_TILE,
                    variables = mapOf("tile_slot" to slot.toString(), "tile_active" to updated.active.toString()),
                ),
            )
        }

        // Quick Settings tiles are reachable from the lock screen, so without this anyone holding
        // the phone could run whatever task is bound to one: send an SMS, run a Termux script,
        // post to a webhook. The task is not refused, only deferred, so a tile still does what it
        // is for the moment the device is unlocked. On a device with no lock set, isLocked is
        // false and nothing changes.
        if (isLocked) unlockAndRun(runBoundTask) else runBoundTask.run()
    }

    private fun updateTile(config: QuickSettingsTileConfig = store.load(slot)) {
        qsTile?.let { tile ->
            val label = config.label.ifBlank { config.taskName.ifBlank { getString(com.opentasker.app.R.string.app_name) } }
            tile.label = label
            if (Build.VERSION.SDK_INT >= 29) {
                tile.subtitle = if (boundTaskMissing) {
                    getString(com.opentasker.app.R.string.qs_tile_subtitle_no_task)
                } else {
                    config.subtitle.ifBlank {
                        getString(
                            if (config.taskId == null) {
                                com.opentasker.app.R.string.qs_tile_subtitle_no_task
                            } else {
                                com.opentasker.app.R.string.qs_tile_subtitle_default
                            },
                        )
                    }
                }
            }
            tile.icon = config.icon(this)
            tile.state = config.tileState(taskMissing = boundTaskMissing)
            tile.updateTile()
        }
    }
}

/**
 * Never [Tile.STATE_UNAVAILABLE]: Android ignores taps on an unavailable tile, and a tap is how a tile
 * with no task opens its setup screen.
 */
internal fun QuickSettingsTileConfig.tileState(taskMissing: Boolean = false): Int =
    if (taskId != null && active && !taskMissing) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE

class QuickSettingsTileServiceSlot2 : QuickSettingsTileService() {
    override val slot: Int = 2
}

class QuickSettingsTileServiceSlot3 : QuickSettingsTileService() {
    override val slot: Int = 3
}

class QuickSettingsTileServiceSlot4 : QuickSettingsTileService() {
    override val slot: Int = 4
}
