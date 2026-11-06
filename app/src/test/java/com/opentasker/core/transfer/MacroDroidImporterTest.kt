package com.opentasker.core.transfer

import com.opentasker.core.model.ContextBooleanOperator
import com.opentasker.core.model.ContextType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MacroDroidImporterTest {
    @Test
    fun fullBackupProducesDisabledProfilesAndExactMigrationReport() {
        val report = MacroDroidImporter.parse(fullBackup(), "test", 123L)

        assertEquals(1, report.sourceMacroCount)
        assertEquals(1, report.sourceVariableCount)
        assertEquals(5, report.sourceActionCount)
        assertEquals(2, report.sourceTriggerCount)
        assertEquals(1, report.sourceConstraintCount)
        assertEquals(4, report.mappedActions.size)
        assertEquals(1, report.unsupportedActions.size)
        assertEquals(2, report.mappedTriggers.size)
        assertTrue(report.unsupportedTriggers.isEmpty())
        assertEquals(1, report.unsupportedConstraints.size)
        assertEquals(
            report.sourceActionCount,
            report.mappedActions.size + report.unsupportedActions.size,
        )

        val task = report.bundle.tasks.single()
        assertEquals("Morning setup", task.name)
        assertEquals(
            listOf("flow.wait", "var.set", MACRODROID_UNSUPPORTED_ACTION_ID, "text.split", "flow.foreach", "flow.endfor"),
            task.actions.map { it.type },
        )
        assertEquals("ShellScriptAction", task.actions[2].args["classType"])
        assertEquals("Output", task.actions[1].args["name"])

        val profile = report.bundle.profiles.single()
        assertFalse(profile.enabled)
        assertTrue(profile.requiresRiskAcknowledgement)
        assertEquals("Daily", profile.group)
        assertEquals(listOf(ContextType.EVENT, ContextType.STATE, ContextType.EVENT), profile.contexts.map { it.type })
        assertEquals(ContextBooleanOperator.AND, profile.contextExpression?.operator)
        assertEquals("Output", report.bundle.variables.single().name)
        assertTrue(report.lossyWarnings.any { it.contains("ShellScriptAction") })
        assertTrue(report.lossyWarnings.any { it.contains("WifiConstraint") })

        val preview = MacroDroidImportPlanner.preview(report)
        assertTrue(preview.canImport)
        assertEquals(1, preview.importProfileCount)
        assertEquals(4, preview.mappedActionCount)
        assertEquals(1, preview.unsupportedActionCount)
        assertTrue(preview.capabilityWarnings.any { it.contains(MACRODROID_UNSUPPORTED_ACTION_ID) })
        assertFalse(MacroDroidImportPlanner.confirmedBundle(report).profiles.single().enabled)
    }

    @Test
    fun singleMacroShareAndSignedGuidAreAccepted() {
        val report = MacroDroidImporter.parse(
            rawJson = """
                {
                  "macroExportVersion": 1,
                  "macro": {
                    "m_GUID": -9223372036854775808,
                    "m_name": "Screen off",
                    "m_enabled": true,
                    "m_category": "Display",
                    "m_isOrCondition": false,
                    "m_triggerList": [
                      {"m_classType":"ScreenOnOffTrigger","m_screenOn":false,"m_constraintList":[],"m_isDisabled":false}
                    ],
                    "m_actionList": [
                      {"m_classType":"SetAirplaneModeAction","m_state":1,"m_constraintList":[],"m_isDisabled":false}
                    ],
                    "m_constraintList": [],
                    "localVariables": []
                  }
                }
            """.trimIndent(),
            appVersion = "test",
            importedAtEpochMs = 0L,
        )

        assertEquals(1L, report.bundle.tasks.single().id)
        assertEquals("airplane.toggle", report.bundle.tasks.single().actions.single().type)
        assertEquals("off", report.bundle.tasks.single().actions.single().args["state"])
        assertEquals("off", report.bundle.profiles.single().contexts.single().config["value"])
    }

    // The action body is what MacroDroid 6.1.5 wrote for Fill Clipboard, only the text varies.
    private fun fillClipboardMacro(text: String): String = """
        {
          "macroExportVersion": 1,
          "macro": {
            "m_GUID": -5921165385892800725,
            "m_name": "Fill",
            "m_enabled": true,
            "m_isOrCondition": false,
            "m_triggerList": [
              {"m_classType":"ScreenOnOffTrigger","m_screenOn":false,"m_constraintList":[],"m_isDisabled":false}
            ],
            "m_actionList": [
              {"m_clipboardText":"$text","disableLogging":false,"m_SIGUID":-9016106706254424825,"m_classType":"ClipboardAction","m_comment":"","m_constraintList":[],"m_isDisabled":false,"m_isOrCondition":false}
            ],
            "m_constraintList": [],
            "localVariables": []
          }
        }
    """.trimIndent()

    @Test
    fun fillClipboardFromARealExportBecomesClipboardSet() {
        val report = MacroDroidImporter.parse(fillClipboardMacro("hello"), "test", 0L)

        val action = report.bundle.tasks.single().actions.single()
        assertEquals("clipboard.set", action.type)
        assertEquals("hello", action.args["text"])
        assertTrue(report.unsupportedActions.isEmpty())
        assertTrue(report.lossyWarnings.none { "ClipboardAction" in it })
    }

    @Test
    fun fillClipboardWithMagicTextKeepsTheTextAndSaysItWontBeFilledIn() {
        listOf("It is [battery] percent", "Hi {lv=name}", "Hi {v=name}").forEach { text ->
            val report = MacroDroidImporter.parse(fillClipboardMacro(text), "test", 0L)

            assertEquals(text, report.bundle.tasks.single().actions.single().args["text"])
            assertTrue(text, report.lossyWarnings.single { "ClipboardAction" in it }.contains("magic text"))
        }
    }

    @Test
    fun fillClipboardWithNoTextStaysAPlaceholder() {
        val report = MacroDroidImporter.parse(fillClipboardMacro(""), "test", 0L)

        assertEquals("macrodroid.unsupported", report.bundle.tasks.single().actions.single().type)
        assertEquals(1, report.unsupportedActions.size)
    }

    @Test
    fun unsupportedAndDisabledSourceItemsFailClosedAsPlaceholders() {
        val report = MacroDroidImporter.parse(
            rawJson = """
                {
                  "macroList": [{
                    "m_GUID": 7,
                    "m_name": "Guarded",
                    "m_triggerList": [
                      {"m_classType":"VariableTrigger","m_constraintList":[],"m_isDisabled":false}
                    ],
                    "m_actionList": [
                      {"m_classType":"PauseAction","m_delayInSeconds":1,"m_constraintList":[],"m_isDisabled":true},
                      {"m_classType":"FutureAction","m_constraintList":[],"m_isDisabled":false}
                    ],
                    "m_constraintList": [],
                    "localVariables": []
                  }],
                  "variables": []
                }
            """.trimIndent(),
            appVersion = "test",
            importedAtEpochMs = 0L,
        )

        assertEquals(2, report.unsupportedActions.size)
        assertEquals(1, report.unsupportedTriggers.size)
        assertTrue(report.bundle.tasks.single().actions.all { it.type == MACRODROID_UNSUPPORTED_ACTION_ID })
        assertEquals("macrodroid_unsupported", report.bundle.profiles.single().contexts.single().config["event"])
    }

    @Test
    fun sourceAndDecodedModelBudgetsApplyBeforeImport() {
        val raw = fullBackup()
        val rawError = runCatching {
            MacroDroidImporter.parse(raw, "test", 0L, ImportResourceBudget.Default.copy(maxJsonChars = 32))
        }.exceptionOrNull()
        assertNotNull(rawError)
        assertTrue(rawError!!.message.orEmpty().contains("JSON characters"))

        val actionError = runCatching {
            MacroDroidImporter.parse(raw, "test", 0L, ImportResourceBudget.Default.copy(maxActions = 2))
        }.exceptionOrNull()
        assertNotNull(actionError)
        assertTrue(actionError!!.message.orEmpty().contains("actions"))

        val entityError = runCatching {
            MacroDroidImporter.parse(raw, "test", 0L, ImportResourceBudget.Default.copy(maxEntities = 1))
        }.exceptionOrNull()
        assertNotNull(entityError)
        assertTrue(entityError!!.message.orEmpty().contains("entities"))
    }

    @Test
    fun unrelatedJsonIsRejectedAsNotMacroDroid() {
        val error = runCatching {
            MacroDroidImporter.parse("{\"schemaVersion\":2}", "test", 0L)
        }.exceptionOrNull()

        assertNotNull(error)
        assertTrue(error!!.message.orEmpty().contains("no macros"))
    }

    private fun fullBackup(): String = """
        {
          "exportFormat": 1,
          "macroList": [{
            "m_GUID": -5155100316473922741,
            "m_name": "Morning setup",
            "m_enabled": true,
            "m_category": "Daily",
            "m_description": "A source description",
            "m_isOrCondition": false,
            "m_triggerList": [
              {"m_classType":"BootTrigger","m_constraintList":[],"m_isDisabled":false},
              {"m_classType":"BatteryLevelTrigger","m_batteryLevel":85,"m_decreasesTo":false,"m_option":0,"m_constraintList":[],"m_isDisabled":false}
            ],
            "m_actionList": [
              {"m_classType":"PauseAction","m_delayInSeconds":7,"m_delayInMilliSeconds":0,"m_useAlarm":true,"m_constraintList":[],"m_isDisabled":false},
              {"m_classType":"SetVariableAction","m_newStringValue":"ready","m_variable":{"m_name":"output","m_type":2,"isLocal":false},"m_constraintList":[],"m_isDisabled":false},
              {"m_classType":"ShellScriptAction","m_script":"id","m_constraintList":[],"m_isDisabled":false},
              {"m_classType":"LoopAction","m_option":0,"m_fixedOptionCount":3,"m_constraintList":[],"m_isDisabled":false},
              {"m_classType":"EndLoopAction","m_constraintList":[],"m_isDisabled":false}
            ],
            "m_constraintList": [
              {"m_classType":"WifiConstraint","m_isDisabled":false}
            ],
            "localVariables": []
          }],
          "variables": [
            {"m_name":"output","m_type":2,"m_stringValue":"idle","m_booleanValue":false,"m_intValue":0,"m_decimalValue":0.0,"isLocal":false}
          ]
        }
    """.trimIndent()
}
