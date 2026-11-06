package com.opentasker.ui.screens

import com.opentasker.core.permissions.OemBatteryGuidance
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.coroutines.cancellation.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Issue #20: Setup and Settings crashed on open on a Galaxy S9 running Android 10, because one
 * platform check threw inside a coroutine nothing caught. These pin the isolation that keeps a
 * throwing check to its own row.
 */
class SetupProbesTest {
    private val logged = mutableListOf<Pair<String, Throwable>>()
    private val probes = SetupProbes { name, error -> logged += name to error }

    @Test
    fun aThrowingCheckBecomesAnUnavailableRowInsteadOfAnException() {
        val refusal = IllegalStateException(
            "Must declare uses-feature android.software.companion_device_setup in manifest to use this API",
        )

        val result = probes.granted("paired devices") { throw refusal }
        val row = row(granted = result == true, unavailable = result == null)

        assertNull(result)
        assertEquals(SetupRowStatus.UNAVAILABLE, row.status())
        assertFalse(row.granted)
        assertEquals(setOf("paired devices"), probes.failures)
        assertEquals(listOf("paired devices" to refusal), logged)
    }

    @Test
    fun checksAfterAThrowingOneStillRunAndAnswer() {
        val first = probes.granted("usage access") { throw SecurityException("denied") }
        val second = probes.granted("overlay") { true }
        val third = probes.read("plugin grants", emptyList<String>()) { listOf("grant") }

        assertNull(first)
        assertEquals(true, second)
        assertEquals(listOf("grant"), third)
        assertEquals(SetupRowStatus.READY, row(granted = second == true, unavailable = second == null).status())
        assertEquals(setOf("usage access"), probes.failures)
    }

    @Test
    fun anApiMissingFromTheDevicesBuildCostsOneRowToo() {
        // A method or class an old or trimmed OEM image doesn't ship throws a LinkageError, not a
        // RuntimeException, and would otherwise take the screen down the way #20 did.
        val missing = NoSuchMethodError("android.app.NotificationManager.canPostPromotedNotifications")

        val result = probes.read("promoted notifications", false) { throw missing }

        assertFalse(result)
        assertEquals(setOf("promoted notifications"), probes.failures)
        assertEquals(listOf("promoted notifications" to missing), logged)
    }

    @Test
    fun cancellationIsNotMistakenForAFailedCheck() {
        // CancellationException is an IllegalStateException; swallowing it would keep a cancelled
        // refresh writing stale values after the screen closed.
        try {
            probes.read("paired devices", emptyList<String>()) { throw CancellationException("screen closed") }
            fail("cancellation should propagate")
        } catch (expected: CancellationException) {
            assertTrue(probes.failures.isEmpty())
            assertTrue(logged.isEmpty())
        }
    }

    @Test
    fun rowStatusKeepsItsExistingMeanings() {
        assertEquals(SetupRowStatus.DETECTED, row(granted = true, optional = true).status())
        assertEquals(SetupRowStatus.OPTIONAL, row(granted = false, optional = true).status())
        assertEquals(SetupRowStatus.NEEDS_SETUP, row(granted = false).status())
        assertEquals(SetupRowStatus.UNAVAILABLE, row(granted = false, optional = true, unavailable = true).status())
    }

    @Test
    fun samsungGuidanceFillsTheSetupRowFormat() {
        // The Samsung-only row is the one part of Setup a stock emulator never renders, so its
        // format string is checked here against the guidance Setup builds on a Galaxy.
        val oem = OemBatteryGuidance.forDevice("samsung", "samsung")
        val body = String.format(
            stringResource("setup_oem_guidance_body"),
            oem.oemName,
            stringResource("setup_risk_high"),
            oem.steps.mapIndexed { index, step -> "${index + 1}. $step" }.joinToString("\n"),
            oem.dontKillMyAppUrl,
        )

        assertTrue(oem.needsExtraSteps)
        assertTrue(body, body.startsWith("Detected ${oem.oemName} (reliability risk: high)."))
        assertTrue(body, body.contains("1. ${oem.steps.first()}"))
        assertTrue(body, body.endsWith(oem.dontKillMyAppUrl))
    }

    @Test
    fun aReadyShizukuRowOffersDisablePowerModeAsANamedButton() {
        val ready = row(granted = true, optional = true).copy(
            actionLabel = "Disable power mode",
            action = PermissionAction.ShizukuKillSwitch(enabled = true),
            allowActionWhenGranted = true,
        )

        assertEquals(SetupRowControl.ACTION_BUTTON, ready.control())
    }

    @Test
    fun otherGrantedRowsKeepTheirChevronOrCheckMark() {
        val settingsPage = PermissionAction.ShizukuPermission
        val reviewable = row(granted = true).copy(action = settingsPage, allowActionWhenGranted = true)
        val finished = row(granted = true).copy(action = settingsPage)
        val missing = row(granted = false).copy(action = settingsPage)

        assertEquals(SetupRowControl.REVIEW_CHEVRON, reviewable.control())
        assertEquals(SetupRowControl.CHECK_MARK, finished.control())
        assertEquals(SetupRowControl.ACTION_BUTTON, missing.control())
        assertEquals(SetupRowControl.CHECK_MARK, row(granted = false).control())
    }

    private fun row(granted: Boolean, optional: Boolean = false, unavailable: Boolean = false) = PermissionSetupItem(
        title = "Row",
        body = "",
        granted = granted,
        actionLabel = "Open",
        action = PermissionAction.None,
        requiredFor = "",
        optional = optional,
        unavailable = unavailable,
    )

    private fun stringResource(name: String): String {
        val file = listOf(File("src/main/res/values/strings.xml"), File("app/src/main/res/values/strings.xml"))
            .first { it.exists() }
        val strings = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
            .getElementsByTagName("string")
        val raw = (0 until strings.length).map { strings.item(it) }
            .first { it.attributes.getNamedItem("name").nodeValue == name }
            .textContent
        return raw.replace("\\n", "\n").replace("\\'", "'")
    }
}
