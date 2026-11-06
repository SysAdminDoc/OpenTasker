package com.opentasker.core.scripting

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The allowlist decides whether a script may run at all, so its rules are asserted here rather
 * than only through the store, which needs a Context.
 */
class ScriptAllowlistPolicyTest {
    private val path = "${TermuxScriptBackend.SCRIPT_DIRECTORY}/backup.sh"
    private val hash = "a".repeat(64)
    private val termux = ScriptAllowlistPolicy.termux
    private val shizuku = ScriptAllowlistPolicy.shizuku

    @Test
    fun anApprovalRoundTrips() {
        val script = ApprovedScript(path, hash)

        assertEquals(script, termux.decode(termux.encode(script)))
    }

    @Test
    fun aRowThisVersionDidNotWriteReadsAsAbsentRatherThanApproved() {
        listOf(
            null,
            "",
            hash,
            "$hash\n",
            "\n$path",
            "not-a-hash\n$path",
            "${hash.uppercase()}X\n$path",
            "$hash\n/etc/passwd",
            "$hash\n$path/../../escape.sh",
        ).forEach { row ->
            assertNull("A malformed row must not approve anything: '$row'", termux.decode(row))
        }
    }

    @Test
    fun aHashIsNormalisedBeforeItIsTrusted() {
        val decoded = termux.decode("  ${hash.uppercase()}  \n$path")

        assertEquals("Case and padding must not create a second approval", hash, decoded?.sha256)
    }

    @Test
    fun theApprovalGateRejectsBadInputAndEnforcesTheCap() {
        assertEquals(
            ScriptAllowlistSaveResult.SAVED,
            termux.admit(path, hash, alreadyApproved = false, approvedCount = 0),
        )
        assertEquals(
            ScriptAllowlistSaveResult.INVALID_PATH,
            termux.admit("/etc/passwd", hash, alreadyApproved = false, approvedCount = 0),
        )
        assertEquals(
            ScriptAllowlistSaveResult.INVALID_HASH,
            termux.admit(path, "short", alreadyApproved = false, approvedCount = 0),
        )
        assertEquals(
            "A bad path must be rejected before the cap is even consulted",
            ScriptAllowlistSaveResult.INVALID_PATH,
            termux.admit(
                "../escape.sh",
                hash,
                alreadyApproved = false,
                approvedCount = ScriptAllowlistStore.MAX_APPROVED_SCRIPTS,
            ),
        )
    }

    @Test
    fun theCapBlocksANewScriptButNeverAnUpdateToAnApprovedOne() {
        val full = ScriptAllowlistStore.MAX_APPROVED_SCRIPTS

        assertEquals(
            ScriptAllowlistSaveResult.FULL,
            termux.admit(path, hash, alreadyApproved = false, approvedCount = full),
        )
        assertEquals(
            "Re-approving a script already on the list must keep working when the list is full",
            ScriptAllowlistSaveResult.SAVED,
            termux.admit(path, hash, alreadyApproved = true, approvedCount = full),
        )
        assertEquals(
            ScriptAllowlistSaveResult.SAVED,
            termux.admit(path, hash, alreadyApproved = false, approvedCount = full - 1),
        )
    }

    @Test
    fun shizukuApprovesScriptsTheShellUserCanReachWithTheSameRules() {
        val shellScript = "/data/local/tmp/backup.sh"

        assertEquals(
            ScriptAllowlistSaveResult.SAVED,
            shizuku.admit(shellScript, hash, alreadyApproved = false, approvedCount = 0),
        )
        assertEquals(ApprovedScript(shellScript, hash), shizuku.decode("$hash\n$shellScript"))
        assertEquals(
            "A Termux home path means nothing to the shell user",
            ScriptAllowlistSaveResult.INVALID_PATH,
            shizuku.admit(path, hash, alreadyApproved = false, approvedCount = 0),
        )
        assertEquals(
            ScriptAllowlistSaveResult.FULL,
            shizuku.admit(
                shellScript,
                hash,
                alreadyApproved = false,
                approvedCount = ScriptAllowlistStore.MAX_APPROVED_SCRIPTS,
            ),
        )
    }

    @Test
    fun aRowApprovedForOneRunnerNeverApprovesAPathTheOtherWouldRefuse() {
        assertNull(termux.decode("$hash\n/data/local/tmp/backup.sh"))
        assertNull(shizuku.decode("$hash\n$path"))
        assertNull(shizuku.decode("$hash\n/data/local/tmp/../../etc/hosts"))
    }
}
