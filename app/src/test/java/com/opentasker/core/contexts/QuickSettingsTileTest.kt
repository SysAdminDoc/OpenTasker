package com.opentasker.core.contexts

import android.service.quicksettings.Tile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

class QuickSettingsTileTest {
    @Test
    fun slotsMapToStableComponentsAndRejectOutOfRangeValues() {
        assertEquals(1, QuickSettingsTileSlots.normalize(1))
        assertEquals(4, QuickSettingsTileSlots.normalize(QuickSettingsTileSlots.COUNT))
        assertNull(QuickSettingsTileSlots.normalize(0))
        assertEquals(3, QuickSettingsTileSlots.slotForComponent(QuickSettingsTileServiceSlot3::class.java.name))
        assertEquals(QuickSettingsTileServiceSlot4::class.java, QuickSettingsTileSlots.componentClass(4))
    }

    @Test
    fun aTileWithNoTaskStaysTappableSoItsSetupCanOpen() {
        val unbound = QuickSettingsTileConfig(slot = 1, taskId = null, taskName = "")
        assertEquals(Tile.STATE_INACTIVE, unbound.tileState())
        assertEquals(Tile.STATE_INACTIVE, unbound.copy(active = true).tileState())

        val bound = QuickSettingsTileConfig(slot = 1, taskId = 7, taskName = "Focus")
        assertEquals(Tile.STATE_INACTIVE, bound.tileState())
        assertEquals(Tile.STATE_ACTIVE, bound.copy(active = true).tileState())
    }

    @Test
    fun stateUpdatesAreBoundedAndRejectUnknownIcons() {
        val current = QuickSettingsTileConfig(slot = 2, taskId = 7, taskName = "Focus")
        val updated = updateQuickSettingsTileConfig(
            current = current,
            active = true,
            label = " Focus\u0000 mode ",
            subtitle = "x".repeat(100),
            iconKey = "unknown",
        )

        assertTrue(updated.active)
        assertEquals("Focus mode", updated.label)
        assertEquals(80, updated.subtitle.length)
        assertEquals(QuickSettingsTileIcons.DEFAULT, updated.iconKey)
    }

    @Test
    fun tileEventsCarrySlotAndState() {
        val event = QuickSettingsTileContextEvents.buildEvent(active = true, slot = 3, nowMs = 42L)

        assertEquals("tile_clicked", event.metadata["event"])
        assertEquals("3", event.metadata["tileSlot"])
        assertEquals("true", event.metadata["tileActive"])
        assertEquals("42", event.metadata["observedAtEpochMs"])
    }

    /**
     * A tile is reachable from the lock screen, and the task behind one can send an SMS, run a
     * Termux script or post to a webhook. Anyone holding the phone could run it without unlocking.
     *
     * A source gate because `onClick` needs a bound TileService and a locked device, which is an
     * instrumented concern; what is checkable here is that the run is not reachable without
     * passing through the lock check.
     */
    @Test
    fun aTileRunIsDeferredUntilTheDeviceIsUnlocked() {
        val source = com.opentasker.ProductionSources.block(
            "com/opentasker/core/contexts/QuickSettingsTileService.kt",
            "override fun onClick()",
            "private fun updateTile(",
        )

        assertTrue(
            "the bound task must run through the lock check",
            "if (isLocked) unlockAndRun(runBoundTask) else runBoundTask.run()" in source,
        )
        assertTrue(
            "the broadcast that starts the task must sit inside the deferred block",
            source.indexOf("val runBoundTask") < source.indexOf("internalRunTaskIntent"),
        )
        assertTrue(
            "and nothing may start it before the check",
            source.indexOf("internalRunTaskIntent") < source.indexOf("if (isLocked)"),
        )
    }
}
