package com.opentasker.core.transfer

import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.ContextSpec
import com.opentasker.core.model.ContextType
import com.opentasker.core.model.Profile
import com.opentasker.core.model.Task
import com.opentasker.core.model.Variable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskerXmlExportTest {

    @Test
    fun exportsMappableActionsAndSkipsUnmappable() {
        val task = Task(
            id = 1,
            name = "Test task",
            actions = listOf(
                ActionSpec(type = "notify.show", args = mapOf("title" to "Hello", "text" to "World")),
                ActionSpec(type = "var.set", args = mapOf("name" to "%MODE", "value" to "on")),
                ActionSpec(type = "mqtt.publish", args = mapOf("topic" to "home/lights")),
            ),
        )
        val report = TaskerXmlExporter.export(emptyList(), listOf(task))

        assertEquals(1, report.exportedTaskCount)
        assertEquals(1, report.skippedActions.size)
        assertEquals("mqtt.publish", report.skippedActions[0].actionType)
        assertTrue(report.xml.contains("<code>523</code>"))
        assertTrue(report.xml.contains("<code>547</code>"))
        assertTrue(report.xml.contains("Hello"))
        assertTrue(report.xml.contains("%MODE"))
    }

    @Test
    fun exportsImportedFlowIfConditionInsteadOfFallbackTrue() {
        // Regression coverage for the import/export pairing: a flow.if imported from a Tasker
        // <ConditionList> carries its real test expression in args["condition"] (see
        // TaskerXmlImport's ConditionList handling), which this exporter reads directly at
        // line ~186. If that key were ever dropped again on import, every re-exported "if" would
        // silently degrade to the literal fallback "true" with no error.
        val task = Task(
            id = 1,
            name = "Imported if",
            actions = listOf(
                ActionSpec(type = "flow.if", condition = "%text is_set", args = mapOf("condition" to "%text is_set")),
            ),
        )
        val report = TaskerXmlExporter.export(emptyList(), listOf(task))

        assertTrue(report.xml.contains("<Str sr=\"arg0\" ve=\"3\">%text is_set</Str>"))
        assertFalse(report.xml.contains("<Str sr=\"arg0\" ve=\"3\">true</Str>"))
    }

    @Test
    fun exportsRunOnlyIfGuardAsConditionList() {
        val task = Task(
            id = 1,
            name = "Guarded stop",
            actions = listOf(
                ActionSpec(type = "flow.stop", condition = "%BATT < 20"),
            ),
        )
        val report = TaskerXmlExporter.export(emptyList(), listOf(task))

        assertTrue(report.xml.contains("<ConditionList sr=\"if\">"))
        assertTrue(report.xml.contains("<lhs>%BATT</lhs>"))
        assertTrue(report.xml.contains("<op>6</op>"))
        assertTrue(report.xml.contains("<rhs>20</rhs>"))
    }

    @Test
    fun exportsUnaryGuardWithTaskerIsSetOpCode() {
        val task = Task(
            id = 1,
            name = "Guarded set",
            actions = listOf(
                ActionSpec(type = "var.set", args = mapOf("name" to "%MODE", "value" to "on"), condition = "%text is_set"),
            ),
        )
        val report = TaskerXmlExporter.export(emptyList(), listOf(task))

        assertTrue(report.xml.contains("<lhs>%text</lhs>"))
        assertTrue(report.xml.contains("<op>12</op>"))
    }

    @Test
    fun stopGuardSurvivesAnExportImportRoundTrip() {
        // The round trip that used to lose the guard: an imported "Stop If" kept its condition in
        // ActionSpec.condition, but export dropped it, so a re-import stopped unconditionally.
        val task = Task(
            id = 1,
            name = "Guarded stop",
            actions = listOf(
                ActionSpec(type = "flow.stop", condition = "%pa_do == toggle"),
            ),
        )
        val exported = TaskerXmlExporter.export(emptyList(), listOf(task))
        val reimported = TaskerXmlImporter.parse(
            rawXml = exported.xml,
            appVersion = "test",
            importedAtEpochMs = 123L,
        )

        val action = reimported.bundle.tasks.single().actions.single()
        assertEquals("flow.stop", action.type)
        assertEquals("%pa_do == toggle", action.condition)
    }

    @Test
    fun flowIfGuardCopiedFromImportRoundTripsThroughConditionList() {
        // An imported flow.if carries the same ConditionList text in both args["condition"] and
        // the generic guard; exporting both the <Str> arg and the ConditionList reproduces that
        // exact state on re-import.
        val task = Task(
            id = 1,
            name = "Imported if",
            actions = listOf(
                ActionSpec(type = "flow.if", condition = "%text is_set", args = mapOf("condition" to "%text is_set")),
                ActionSpec(type = "flow.endif"),
            ),
        )
        val exported = TaskerXmlExporter.export(emptyList(), listOf(task))
        val reimported = TaskerXmlImporter.parse(
            rawXml = exported.xml,
            appVersion = "test",
            importedAtEpochMs = 123L,
        )

        val ifAction = reimported.bundle.tasks.single().actions.first()
        assertEquals("%text is_set", ifAction.condition)
        assertEquals("%text is_set", ifAction.args["condition"])
    }

    @Test
    fun flowIfWithDistinctGuardWarnsInsteadOfClobberingItsTest() {
        val task = Task(
            id = 1,
            name = "If with guard",
            actions = listOf(
                ActionSpec(type = "flow.if", condition = "%b == 2", args = mapOf("condition" to "%a == 1")),
                ActionSpec(type = "flow.endif"),
            ),
        )
        val report = TaskerXmlExporter.export(emptyList(), listOf(task))

        assertFalse(report.xml.contains("<ConditionList"))
        assertTrue(report.warnings.any { it.contains("flow.if") })
    }

    @Test
    fun unrepresentableGuardIsDroppedWithWarningNotExportedWrong() {
        val task = Task(
            id = 1,
            name = "At most guard",
            actions = listOf(
                ActionSpec(type = "var.set", args = mapOf("name" to "%MODE", "value" to "on"), condition = "%a <= 1"),
            ),
        )
        val report = TaskerXmlExporter.export(emptyList(), listOf(task))

        assertFalse(report.xml.contains("<ConditionList"))
        assertTrue(report.warnings.any { it.contains("no Tasker ConditionList equivalent") })
    }

    @Test
    fun exportsTimeContextsWithClockParts() {
        val profile = Profile(
            id = 1,
            name = "Morning",
            enterTaskId = 1,
            contexts = listOf(
                ContextSpec(ContextType.TIME, mapOf("start" to "08:00", "end" to "17:30")),
            ),
        )
        val report = TaskerXmlExporter.export(listOf(profile), emptyList())

        assertEquals(1, report.exportedProfileCount)
        assertTrue(report.xml.contains("<fh>8</fh>"))
        assertTrue(report.xml.contains("<fm>0</fm>"))
        assertTrue(report.xml.contains("<th>17</th>"))
        assertTrue(report.xml.contains("<tm>30</tm>"))
    }

    @Test
    fun exportsApplicationContexts() {
        val profile = Profile(
            id = 1,
            name = "App trigger",
            enterTaskId = 1,
            contexts = listOf(
                ContextSpec(ContextType.APPLICATION, mapOf("package" to "com.example.app")),
            ),
        )
        val report = TaskerXmlExporter.export(listOf(profile), emptyList())

        assertTrue(report.xml.contains("<pkg0>com.example.app</pkg0>"))
    }

    @Test
    fun exportsVariables() {
        val variable = Variable(name = "MODE", value = "silent", isGlobal = true)
        val report = TaskerXmlExporter.export(emptyList(), emptyList(), listOf(variable))

        assertEquals(1, report.exportedVariableCount)
        assertTrue(report.xml.contains("%MODE"))
        assertTrue(report.xml.contains("silent"))
    }

    @Test
    fun omitsSecretVariables() {
        val report = TaskerXmlExporter.export(
            emptyList(),
            emptyList(),
            listOf(
                Variable("MODE", "silent", isGlobal = true),
                Variable("API_TOKEN", "must-not-export", isGlobal = true, isSecret = true),
            ),
        )

        assertEquals(1, report.exportedVariableCount)
        assertTrue(report.xml.contains("silent"))
        assertTrue(!report.xml.contains("must-not-export"))
        assertTrue(report.warnings.any { it.contains("1 secret variable") })
    }

    @Test
    fun omitsSensitiveActionValuesAndWarnsForReentry() {
        val report = TaskerXmlExporter.export(
            emptyList(),
            listOf(
                Task(
                    id = 1,
                    name = "Credentials",
                    actions = listOf(
                        ActionSpec(
                            type = "var.set",
                            args = mapOf("name" to "API_TOKEN", "value" to "sentinel"),
                        ),
                        ActionSpec(
                            type = "url.open",
                            args = mapOf("url" to "https://example.test/api?token=sentinel"),
                        ),
                        ActionSpec(
                            type = "tts.speak",
                            args = mapOf("text" to "{{ global.API_TOKEN }}"),
                        ),
                    ),
                ),
            ),
            variables = listOf(Variable("API_TOKEN", "sentinel", isGlobal = true, isSecret = true)),
        )

        assertFalse(report.xml.contains("sentinel"))
        assertTrue(report.redactedActionFieldCount >= 3)
        assertTrue(report.warnings.any { it.contains("must be re-entered") })
    }

    @Test
    fun escapesXmlSpecialCharacters() {
        val task = Task(
            id = 1,
            name = "Test & <task>",
            actions = listOf(
                ActionSpec(type = "tts.speak", args = mapOf("text" to "value < 5 & done")),
            ),
        )
        val report = TaskerXmlExporter.export(emptyList(), listOf(task))

        assertTrue(report.xml.contains("Test &amp; &lt;task&gt;"))
        assertTrue(report.xml.contains("value &lt; 5 &amp; done"))
    }

    @Test
    fun warnsForUnexportableContextTypes() {
        val profile = Profile(
            id = 1,
            name = "Location",
            enterTaskId = 1,
            contexts = listOf(
                ContextSpec(ContextType.LOCATION, mapOf("lat" to "40.0", "lon" to "-74.0")),
            ),
        )
        val report = TaskerXmlExporter.export(listOf(profile), emptyList())

        assertTrue(report.warnings.any { it.contains("LOCATION") })
    }

    @Test
    fun producesValidXmlStructure() {
        val report = TaskerXmlExporter.export(
            listOf(Profile(id = 1, name = "P", enterTaskId = 1)),
            listOf(Task(id = 1, name = "T")),
        )

        assertTrue(report.xml.startsWith("<?xml"))
        assertTrue(report.xml.contains("<TaskerData"))
        assertTrue(report.xml.contains("</TaskerData>"))
    }

    @Test
    fun flowWaitConvertsMillisToSecondsAndRemainder() {
        val task = Task(
            id = 1,
            name = "Wait",
            actions = listOf(
                ActionSpec(type = "flow.wait", args = mapOf("millis" to "2500")),
            ),
        )
        val report = TaskerXmlExporter.export(emptyList(), listOf(task))

        assertTrue(report.xml.contains("<code>30</code>"))
        assertTrue(report.xml.contains("<Int sr=\"arg0\" val=\"500\"/>"))
        assertTrue(report.xml.contains("<Int sr=\"arg1\" val=\"2\"/>"))
    }

    @Test
    fun exportsSafeSettingsMediaNotificationVariableAndFlowBatch() {
        val task = Task(
            id = 1,
            name = "Safe batch",
            actions = listOf(
                ActionSpec(type = "notify.show", args = mapOf("title" to "Title", "text" to "Body")),
                ActionSpec(type = "var.set", args = mapOf("name" to "%MODE", "value" to "quiet")),
                ActionSpec(type = "tts.speak", args = mapOf("text" to "Hello")),
                ActionSpec(type = "vibrate", args = mapOf("millis" to "250")),
                ActionSpec(type = "volume.set", args = mapOf("stream" to "music", "level" to "4")),
                ActionSpec(type = "torch.set", args = mapOf("state" to "on")),
                ActionSpec(type = "sound.play", args = mapOf("path" to "content://media/song")),
                ActionSpec(type = "flow.if", args = mapOf("condition" to "%MODE = quiet")),
                ActionSpec(type = "flow.else"),
                ActionSpec(type = "flow.endif"),
                ActionSpec(type = "flow.foreach", args = mapOf("list" to "%ITEMS", "var" to "item")),
                ActionSpec(type = "flow.endfor"),
                ActionSpec(type = "flow.stop"),
            ),
        )

        val report = TaskerXmlExporter.export(emptyList(), listOf(task))

        assertTrue(report.skippedActions.isEmpty())
        listOf("523", "547", "559", "61", "307", "511", "445", "37", "43", "38", "39", "40", "137")
            .forEach { code -> assertTrue("missing Tasker code $code", report.xml.contains("<code>$code</code>")) }
        assertTrue(report.xml.contains("content://media/song"))
    }


    @Test
    fun variablesSurviveAnExportImportRoundTrip() {
        // The exporter writes <n>/<v>; the importer used to read only nme/val, so a file this app
        // produced and then re-imported dropped every variable as "skipped because it had no name".
        val report = TaskerXmlExporter.export(
            profiles = emptyList(),
            tasks = emptyList(),
            variables = listOf(
                Variable("MODE", "commute", isGlobal = true),
                Variable("THRESHOLD", "20", isGlobal = true),
            ),
        )

        val imported = TaskerXmlImporter.parse(report.xml, appVersion = "test")

        // Names carry Tasker's % sigil inside the bundle and are normalised at storage time.
        assertEquals(
            listOf("%MODE" to "commute", "%THRESHOLD" to "20"),
            imported.bundle.variables.map { it.name to it.value },
        )
        assertFalse(
            imported.lossyWarnings.any { it.contains("had no name") },
        )
    }
}
