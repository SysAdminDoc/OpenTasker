package com.opentasker.core.actions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

/**
 * Pure containment/TOCTOU coverage for the file-action sandbox resolver. These run on the host JVM,
 * so the symlink case skips gracefully where the platform cannot create symlinks (unprivileged
 * Windows), while the lexical-escape and control-char cases always run.
 */
class FileActionsSandboxTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun base(): File = temp.newFolder("user_files").canonicalFile

    @Test
    fun resolvesNormalPathWithinSandbox() {
        val baseDir = base()
        val resolved = resolveSandboxTarget(baseDir, "notes/today.txt")
        assertEquals(File(baseDir, "notes/today.txt"), resolved)
    }

    @Test
    fun rejectsLexicalParentEscape() {
        assertNull(resolveSandboxTarget(base(), "../../etc/passwd"))
    }

    @Test
    fun rejectsControlCharacters() {
        assertNull(resolveSandboxTarget(base(), "notes/	evil.txt"))
    }

    @Test
    fun rejectsBlankPath() {
        assertNull(resolveSandboxTarget(base(), "   "))
    }

    @Test
    fun refusesSymlinkComponentPointingOutsideSandbox() {
        val baseDir = base()
        val outside = temp.newFolder("outside").canonicalFile
        val link = File(baseDir, "escape")
        val created = runCatching {
            Files.createSymbolicLink(link.toPath(), outside.toPath())
        }.isSuccess
        assumeTrue("platform cannot create symlinks", created)

        // A path traversing the in-sandbox symlink into the outside directory must be refused.
        assertNull(resolveSandboxTarget(baseDir, "escape/secret.txt"))
        // The symlink itself, even as the final component, is refused.
        assertNull(resolveSandboxTarget(baseDir, "escape"))
    }

    @Test
    fun refusesSymlinkComponentWhoseTargetIsMissing() {
        val baseDir = base()
        val neverCreated = File(temp.root, "never-created")
        val link = File(baseDir, "dangling")
        val created = runCatching {
            Files.createSymbolicLink(link.toPath(), neverCreated.toPath())
        }.isSuccess
        assumeTrue("platform cannot create symlinks", created)

        assertNull(resolveSandboxTarget(baseDir, "dangling/secret.txt"))
        assertNull(resolveSandboxTarget(baseDir, "dangling"))
    }
}
