package com.opentasker.core.apps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class TargetSignerGuardTest {
    private val pkg = "com.example.plugin"
    private val signatures = FakeSignatures()
    private val pins = MapPins()
    private var now = 1_000L
    private val guard = TargetSignerGuard(signatures, pins) { now }

    @Test
    fun matchingSignerPasses() {
        pins.put(TargetSignerPin(pkg, listOf(A), TargetSignerPinSource.APPROVED, 10L))
        signatures.installed[pkg] = InstalledSigners(current = listOf(A), lineage = listOf(A))

        assertEquals(TargetSignerCheck.Match, guard.check(pkg))
        guard.requireTrusted(pkg)
        assertEquals(TargetSignerPin(pkg, listOf(A), TargetSignerPinSource.APPROVED, 10L), pins.get(pkg))
    }

    @Test
    fun mismatchRefusesAndNamesThePackage() {
        pins.put(TargetSignerPin(pkg, listOf(A), TargetSignerPinSource.APPROVED, 10L))
        signatures.installed[pkg] = InstalledSigners(current = listOf(B), lineage = listOf(B))

        val result = guard.check(pkg)

        assertTrue(result is TargetSignerCheck.Mismatch)
        val message = (result as TargetSignerCheck.Mismatch).message
        assertTrue(message, message.contains("Refused to send to $pkg"))
        assertTrue(message, message.contains("Re-approve $pkg in Setup"))
        assertEquals(listOf(A), pins.get(pkg)?.digests)
        val thrown = assertThrows(TargetSignerMismatchException::class.java) { guard.requireTrusted(pkg) }
        assertEquals(pkg, thrown.packageName)
        assertEquals(message, thrown.message)
        val asThrowable: Throwable = thrown
        assertFalse("A refusal must not read as a permission problem", asThrowable is SecurityException)
    }

    @Test
    fun missingPackageIsNotInstalledAndPinsNothing() {
        assertEquals(TargetSignerCheck.NotInstalled, guard.check(pkg))
        guard.requireTrusted(pkg)
        assertNull(pins.get(pkg))

        pins.put(TargetSignerPin(pkg, listOf(A), TargetSignerPinSource.APPROVED, 10L))
        assertEquals(TargetSignerCheck.NotInstalled, guard.check(pkg))
        assertEquals(listOf(A), pins.get(pkg)?.digests)
    }

    @Test
    fun firstUseMigrationPinsTheInstalledSignerAndProceeds() {
        signatures.installed[pkg] = InstalledSigners(current = listOf(A), lineage = listOf(A))
        now = 42L

        val first = guard.check(pkg)

        val expected = TargetSignerPin(pkg, listOf(A), TargetSignerPinSource.FIRST_USE, 42L)
        assertEquals(TargetSignerCheck.PinnedOnFirstUse(expected), first)
        assertEquals(expected, pins.get(pkg))
        assertEquals(TargetSignerCheck.Match, guard.check(pkg))

        signatures.installed[pkg] = InstalledSigners(current = listOf(B), lineage = listOf(B))
        assertTrue(guard.check(pkg) is TargetSignerCheck.Mismatch)
    }

    @Test
    fun reapproveRepinsAndDispatchResumes() {
        val old = TargetSignerPin(pkg, listOf(A), TargetSignerPinSource.FIRST_USE, 10L)
        pins.put(old)
        signatures.installed[pkg] = InstalledSigners(current = listOf(B), lineage = listOf(B))
        assertTrue(guard.check(pkg) is TargetSignerCheck.Mismatch)
        now = 99L

        val approval = guard.approve(pkg)

        assertEquals(TargetSignerPin(pkg, listOf(B), TargetSignerPinSource.APPROVED, 99L), approval?.pin)
        assertEquals(old, approval?.previous)
        assertEquals(TargetSignerCheck.Match, guard.check(pkg))

        guard.restore(pkg, approval!!.previous)
        assertTrue("Undo puts the refusal back", guard.check(pkg) is TargetSignerCheck.Mismatch)
    }

    @Test
    fun reapproveOfAMissingPackageChangesNothing() {
        pins.put(TargetSignerPin(pkg, listOf(A), TargetSignerPinSource.APPROVED, 10L))

        assertNull(guard.approve(pkg))
        assertEquals(listOf(A), pins.get(pkg)?.digests)
    }

    @Test
    fun undoOfAFirstApprovalRemovesThePin() {
        signatures.installed[pkg] = InstalledSigners(current = listOf(A))

        val approval = guard.approve(pkg)!!
        guard.restore(pkg, approval.previous)

        assertNull(pins.get(pkg))
    }

    @Test
    fun lineageRotationIsAccepted() {
        pins.put(TargetSignerPin(pkg, listOf(A), TargetSignerPinSource.APPROVED, 10L))
        signatures.installed[pkg] = InstalledSigners(current = listOf(B), lineage = listOf(A, B))

        assertEquals(TargetSignerCheck.Match, guard.check(pkg))
        assertEquals(
            "The pin follows the rotation and keeps how it was approved",
            TargetSignerPin(pkg, listOf(B), TargetSignerPinSource.APPROVED, 10L),
            pins.get(pkg),
        )

        signatures.installed[pkg] = InstalledSigners(current = listOf(C), lineage = listOf(A, C))
        assertTrue("A lineage that lacks the current pin is not a rotation of it", guard.check(pkg) is TargetSignerCheck.Mismatch)
    }

    @Test
    fun multipleSignersCompareAsASet() {
        signatures.installed[pkg] = InstalledSigners(current = listOf(B, A))
        assertTrue(guard.check(pkg) is TargetSignerCheck.PinnedOnFirstUse)
        assertEquals(listOf(A, B), pins.get(pkg)?.digests)

        signatures.installed[pkg] = InstalledSigners(current = listOf(A, B))
        assertEquals(TargetSignerCheck.Match, guard.check(pkg))

        signatures.installed[pkg] = InstalledSigners(current = listOf(A))
        assertTrue("One signer of a pinned pair is not the pair", guard.check(pkg) is TargetSignerCheck.Mismatch)

        signatures.installed[pkg] = InstalledSigners(current = listOf(A, B, C))
        assertTrue("An extra signer is not the pinned set", guard.check(pkg) is TargetSignerCheck.Mismatch)

        signatures.installed[pkg] = InstalledSigners(current = listOf(A), lineage = listOf(B, A))
        assertTrue("Lineage never stands in for a multi-signer pin", guard.check(pkg) is TargetSignerCheck.Mismatch)
    }

    @Test
    fun scriptApprovalPinsButNeverReplacesAMismatchedPin() {
        signatures.installed[pkg] = InstalledSigners(current = listOf(A))
        assertEquals(TargetSignerCheck.Match, guard.recordApproval(pkg))
        assertEquals(TargetSignerPinSource.APPROVED, pins.get(pkg)?.source)

        pins.put(TargetSignerPin(pkg, listOf(A), TargetSignerPinSource.FIRST_USE, 10L))
        guard.recordApproval(pkg)
        assertEquals("A matching first-use pin becomes an approval", TargetSignerPinSource.APPROVED, pins.get(pkg)?.source)

        signatures.installed[pkg] = InstalledSigners(current = listOf(B))
        assertTrue(guard.recordApproval(pkg) is TargetSignerCheck.Mismatch)
        assertEquals(listOf(A), pins.get(pkg)?.digests)

        signatures.installed.remove(pkg)
        assertEquals(TargetSignerCheck.NotInstalled, guard.recordApproval(pkg))
    }

    @Test
    fun statusesReportEachStateAndNeverPin() {
        pins.put(TargetSignerPin("p.match", listOf(A), TargetSignerPinSource.APPROVED, 1L))
        pins.put(TargetSignerPin("p.mismatch", listOf(A), TargetSignerPinSource.FIRST_USE, 2L))
        pins.put(TargetSignerPin("p.gone", listOf(A), TargetSignerPinSource.APPROVED, 3L))
        signatures.installed["p.match"] = InstalledSigners(current = listOf(A))
        signatures.installed["p.mismatch"] = InstalledSigners(current = listOf(B))
        signatures.installed["p.unpinned"] = InstalledSigners(current = listOf(C))

        val states = guard.statuses().associate { it.pin.packageName to it.state }

        assertEquals(
            mapOf(
                "p.gone" to TargetSignerState.NOT_INSTALLED,
                "p.match" to TargetSignerState.MATCH,
                "p.mismatch" to TargetSignerState.MISMATCH,
            ),
            states,
        )
        assertNull(pins.get("p.unpinned"))
    }

    @Test
    fun unreadableSignersFailInsteadOfPinningNothing() {
        signatures.installed[pkg] = InstalledSigners(current = emptyList())

        assertThrows(IllegalStateException::class.java) { guard.check(pkg) }
        assertNull(pins.get(pkg))
    }

    @Test
    fun codecRoundTripsAndRejectsMalformedPins() {
        val pin = TargetSignerPin(pkg, listOf(A, B), TargetSignerPinSource.FIRST_USE, 123L)

        assertEquals(pin, TargetSignerPinCodec.decode(pkg, TargetSignerPinCodec.encode(pin)))
        listOf(
            null,
            "",
            "approved|1",
            "unknown|1|$A",
            "approved|soon|$A",
            "approved|1|",
            "approved|1|${A.uppercase()}",
            "approved|1|abc",
        ).forEach { raw -> assertNull(raw, TargetSignerPinCodec.decode(pkg, raw)) }
    }

    @Test
    fun digestsAreLowercaseHexSha256() {
        val digest = sha256Hex("abc".toByteArray())

        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", digest)
        assertTrue(isSha256Hex(digest))
    }

    private class FakeSignatures : PackageSignatureSource {
        val installed = mutableMapOf<String, InstalledSigners>()
        override fun signers(packageName: String): InstalledSigners? = installed[packageName]
    }

    private class MapPins : TargetSignerPins {
        private val map = sortedMapOf<String, TargetSignerPin>()
        override fun get(packageName: String): TargetSignerPin? = map[packageName]
        override fun put(pin: TargetSignerPin) {
            map[pin.packageName] = pin
        }
        override fun remove(packageName: String) {
            map.remove(packageName)
        }
        override fun all(): List<TargetSignerPin> = map.values.toList()
    }

    companion object {
        val A = "a".repeat(64)
        val B = "b".repeat(64)
        val C = "c".repeat(64)
    }
}
