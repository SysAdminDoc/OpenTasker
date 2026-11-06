package com.opentasker.core.transfer

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs the MacroDroid importer on Android's regex engine. A bare closing brace in one of its
 * patterns passed every JVM test and then failed the importer's class load on a device, so every
 * MacroDroid file was refused whatever it held.
 */
@RunWith(AndroidJUnit4::class)
class MacroDroidImportInstrumentedTest {
    private fun fillClipboard(text: String): String = """
        {
          "macroExportVersion": 1,
          "macro": {
            "m_GUID": -5921165385892800725,
            "m_name": "Fill",
            "m_enabled": true,
            "m_isOrCondition": false,
            "m_triggerList": [
              {"m_classType":"BootTrigger","m_constraintList":[],"m_isDisabled":false}
            ],
            "m_actionList": [
              {"m_clipboardText":"$text","m_classType":"ClipboardAction","m_constraintList":[],"m_isDisabled":false}
            ],
            "m_constraintList": [],
            "localVariables": []
          }
        }
    """.trimIndent()

    @Test
    fun plainClipboardTextImportsWithoutAWarning() {
        val report = MacroDroidImporter.parse(fillClipboard("hello"), "instrumented-test", 0L)

        assertEquals("clipboard.set", report.bundle.tasks.single().actions.single().type)
        assertTrue(report.unsupportedActions.isEmpty())
        assertTrue(report.lossyWarnings.isEmpty())
    }

    @Test
    fun magicTextIsSpottedInEveryForm() {
        listOf("It is [battery] percent", "Hi {lv=name}", "Hi {v=name}").forEach { text ->
            val report = MacroDroidImporter.parse(fillClipboard(text), "instrumented-test", 0L)

            assertTrue(text, report.lossyWarnings.single().contains("magic text"))
        }
    }
}
