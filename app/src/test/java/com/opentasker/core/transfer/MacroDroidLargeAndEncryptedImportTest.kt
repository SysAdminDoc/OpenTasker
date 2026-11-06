package com.opentasker.core.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Base64

/** Issue #30: whole-app backups went over the token budget, and `.emdr` was scanned as JSON. */
class MacroDroidLargeAndEncryptedImportTest {
    @Test
    fun aBackupOverTheDefaultTokenBudgetImportsAndItsBundleStillValidates() {
        val raw = backup(macroCount = 400, filler = 1_000)
        try {
            ImportResourceGuard.requireJsonPreflight(raw)
            fail("The fixture has to be over the default budget to prove anything")
        } catch (expected: ImportBudgetExceededException) {
            assertEquals("JSON tokens", expected.budgetName)
        }

        val report = MacroDroidImporter.parse(rawJson = raw, appVersion = "test", importedAtEpochMs = 0)

        assertEquals(400, report.sourceMacroCount)
        // The write path validates against the default budget, so a preview that passes has to
        // be one that can actually be confirmed.
        OpenTaskerBundleCodec.validate(MacroDroidImportPlanner.confirmedBundle(report))
    }

    @Test
    fun deepNestingInsideABackupIsNoLongerAnObstacle() {
        val nested = "[".repeat(100) + "]".repeat(100)
        val raw = """{"macroList":[{"m_name":"Deep","m_GUID":1,"extra":$nested,"m_actionList":[],"m_triggerList":[]}]}"""

        assertEquals(1, MacroDroidImporter.parse(rawJson = raw, appVersion = "test", importedAtEpochMs = 0).sourceMacroCount)
    }

    @Test
    fun theSourceBudgetIsStillABudget() {
        val nested = "[".repeat(300) + "]".repeat(300)
        val raw = """{"macroList":[{"m_name":"Deep","extra":$nested}]}"""

        assertBudget("nesting depth") { MacroDroidImporter.parse(rawJson = raw, appVersion = "test", importedAtEpochMs = 0) }
    }

    @Test
    fun whatComesOutOfABackupKeepsTheDefaultEntityLimit() {
        val raw = backup(macroCount = ImportResourceBudget.Default.maxEntities.toInt() / 2 + 1, filler = 0)

        assertBudget("entities") { MacroDroidImporter.parse(rawJson = raw, appVersion = "test", importedAtEpochMs = 0) }
    }

    @Test
    fun anythingButAJsonObjectIsRejectedAsTheWrongFileNotAsTooBig() {
        val garbage = "[".repeat(500)
        try {
            MacroDroidImporter.parse(rawJson = garbage, appVersion = "test", importedAtEpochMs = 0)
            fail("Expected a rejection")
        } catch (error: IllegalArgumentException) {
            assertEquals("MacroDroid import root must be a JSON object.", error.message)
        }
    }

    /** Both vectors were made outside this code, with hashlib's PBKDF2 and OpenSSL's AES. */
    @Test
    fun decryptsAnExportMadeWithMacroDroidsParameters() {
        assertEquals(VECTOR_PLAINTEXT, MacroDroidEncryptedExport.decrypt(HUNTER2_CIPHERTEXT, "hunter2".toCharArray()))
        assertEquals(VECTOR_PLAINTEXT, MacroDroidEncryptedExport.decrypt(UMLAUT_CIPHERTEXT, "pässwörd".toCharArray()))

        val report = MacroDroidImporter.parse(rawJson = VECTOR_PLAINTEXT, appVersion = "test", importedAtEpochMs = 0)
        assertEquals("Encrypted macro", report.bundle.tasks.single().name)
    }

    @Test
    fun aWrongPasswordIsAnAnswerNotACrash() {
        assertNull(MacroDroidEncryptedExport.decrypt(HUNTER2_CIPHERTEXT, "hunter3".toCharArray()))
        assertNull(MacroDroidEncryptedExport.decrypt(HUNTER2_CIPHERTEXT, CharArray(0)))
    }

    @Test
    fun classifiesByContentBecauseCiphertextCanOpenWithAnyByte() {
        fun classify(bytes: ByteArray) = TaskerOrMacroDroidDocument.classify(bytes)

        assertEquals(
            TaskerOrMacroDroidDocument.Text(VECTOR_PLAINTEXT),
            classify(VECTOR_PLAINTEXT.toByteArray()),
        )
        assertEquals(TaskerOrMacroDroidDocument.Text("<TaskerData/>"), classify("<TaskerData/>".toByteArray()))
        assertEquals(TaskerOrMacroDroidDocument.MacroDroidEncrypted, classify(HUNTER2_CIPHERTEXT))

        val opensLikeJson = HUNTER2_CIPHERTEXT.copyOf().also { it[0] = '{'.code.toByte() }
        assertEquals(TaskerOrMacroDroidDocument.MacroDroidEncrypted, classify(opensLikeJson))

        val notBlocks = HUNTER2_CIPHERTEXT.copyOf(HUNTER2_CIPHERTEXT.size - 1)
        assertEquals(TaskerOrMacroDroidDocument.Unrecognized, classify(notBlocks))
    }

    @Test
    fun textInAnotherEncodingStillReachesTheParsers() {
        val latin1 = "<TaskerData name=\"café\"/>".toByteArray(Charsets.ISO_8859_1)
        assertTrue("A whole number of blocks would read as ciphertext", latin1.size % 16 != 0)
        val document = TaskerOrMacroDroidDocument.classify(latin1)

        assertTrue(document is TaskerOrMacroDroidDocument.Text)
    }

    private fun backup(macroCount: Int, filler: Int): String = buildString {
        append("""{"macroList":[""")
        repeat(macroCount) { index ->
            if (index > 0) append(',')
            append("""{"m_name":"Macro $index","m_GUID":${index + 1},"m_actionList":[],"m_triggerList":[],"padding":[""")
            append((0 until filler).joinToString(","))
            append("]}")
        }
        append("]}")
    }

    private fun assertBudget(name: String, block: () -> Unit) {
        try {
            block()
            fail("Expected the $name budget to refuse this")
        } catch (error: ImportBudgetExceededException) {
            assertEquals(name, error.budgetName)
        }
    }

    private companion object {
        const val VECTOR_PLAINTEXT =
            """{"macroList":[{"m_name":"Encrypted macro","m_GUID":1,"m_actionList":[],"m_triggerList":[]}]}"""
        val HUNTER2_CIPHERTEXT: ByteArray = Base64.getDecoder().decode(
            "F7qxRTzTW36aNWL64XRe6EHOVOOfM4XqAWkVukUkd7vLvcXRLZhOv4rguatMYA4OTrzL4qMtlTLP0bYjQUoGYF4kymosRbXOP5K6h7xJcxQffdGEz5YgLs3yfSTRVHbX",
        )
        val UMLAUT_CIPHERTEXT: ByteArray = Base64.getDecoder().decode(
            "V3wzCGSqT3ZE2QTOdTJ51Cn3q+BQ7gutANV2+TMzo/5wINqyR4R/Z2UDTUadJSLWJhIsNabdtglshF+AfFB6U9LHkknW/CWkRwaHtD86UOC8di/45Ss3R8mzhhiWm8Fv",
        )
    }
}
