package com.opentasker.core.external

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.opentasker.ProductionSources
import java.io.File
import kotlin.io.path.readText
import javax.xml.parsers.DocumentBuilderFactory

class AutomationTargetContractTest {
    @Test
    fun validatesVariableNamesForExternalExtras() {
        assertTrue(AutomationTargetContract.isValidVariableName("User"))
        assertTrue(AutomationTargetContract.isValidVariableName("task_value_1"))
        assertFalse(AutomationTargetContract.isValidVariableName("1bad"))
        assertFalse(AutomationTargetContract.isValidVariableName("bad-name"))
        assertFalse(AutomationTargetContract.isValidVariableName(""))
    }

    @Test
    fun readsOnlyVariableExtrasAsStrings() {
        val read = mutableListOf<String>()
        val extras = mapOf(
            "com.opentasker.extra.PROTOCOL_VERSION" to null,
            "com.opentasker.extra.TASK_NAME" to "Morning briefing",
            "com.opentasker.var.User" to "demo",
        )

        val variables = AutomationTargetContract.extractVariableExtras(extras.keys) { key ->
            read += key
            extras[key]
        }

        assertEquals(mapOf("User" to "demo"), variables)
        assertEquals(listOf("com.opentasker.var.User"), read)
    }

    @Test
    fun buildsDocumentedVariableExtraNames() {
        assertEquals(
            "com.opentasker.var.User",
            AutomationTargetContract.variableExtraName("User"),
        )
    }

    @Test
    fun exposesHomeAssistantVocabularyAsNonBreakingAliases() {
        assertEquals("command_broadcast_intent", AutomationTargetContract.HOME_ASSISTANT_COMMAND_BROADCAST_INTENT)
        assertEquals("message", AutomationTargetContract.HOME_ASSISTANT_FIELD_MESSAGE)
        assertEquals("data", AutomationTargetContract.HOME_ASSISTANT_FIELD_DATA)
        assertEquals("intent_extras", AutomationTargetContract.HOME_ASSISTANT_FIELD_INTENT_EXTRAS)
        assertEquals(2, AutomationTargetContract.PROTOCOL_VERSION)
    }

    @Test
    fun rejectsInvalidVariableExtraNames() {
        val error = runCatching {
            AutomationTargetContract.variableExtraName("bad-name")
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun internalRunSourcesMapToCanonicalLogLabels() {
        assertEquals("Locale plugin", AutomationTargetContract.runSourceLabel("locale_plugin"))
        assertEquals("Scene overlay", AutomationTargetContract.runSourceLabel("scene_overlay"))
        assertEquals(
            AutomationTargetContract.DEFAULT_RUN_SOURCE,
            AutomationTargetContract.runSourceLabel("forged\nsource"),
        )
    }

    @Test
    fun everyInternalRunTaskProducerUsesTheCanonicalProtocolBuilder() {
        val receiver = loadMainSource("com/opentasker/core/external/AutomationTargetReceiver.kt")
        val locale = loadMainSource("com/opentasker/core/plugins/locale/LocalePluginTarget.kt")
        val scene = loadMainSource("com/opentasker/core/scenes/SceneOverlayService.kt")
        val service = loadMainSource("com/opentasker/core/engine/AutomationService.kt")

        assertTrue(receiver.contains("fun internalRunTaskIntent("))
        assertTrue(receiver.contains("putExtra(EXTRA_PROTOCOL_VERSION, PROTOCOL_VERSION)"))
        assertTrue(receiver.contains("Intent(context, AutomationTargetReceiver::class.java)"))
        assertTrue(locale.contains("AutomationTargetContract.internalRunTaskIntent("))
        assertTrue(locale.contains("InternalTaskRunSource.LOCALE_PLUGIN"))
        assertTrue(scene.contains("AutomationTargetContract.internalRunTaskIntent("))
        assertTrue(scene.contains("InternalTaskRunSource.SCENE_OVERLAY"))
        assertTrue(service.contains("AutomationTargetContract.EXTRA_RUN_SOURCE"))
        assertTrue(service.contains("runExternalTask("))
        assertTrue(service.contains("parentExecutionId = parentExecutionId"))

        // Exact-list equality over every production root. Walking app/ alone would have let a
        // second producer in a core module pass unnoticed.
        val rawProducers = ProductionSources.allKotlinFiles()
            .filter { sourceFile ->
                val text = sourceFile.readText()
                text.contains("\"com.opentasker.action.RUN_TASK\"") ||
                    text.contains("AutomationTargetContract.ACTION_RUN_TASK")
            }
            .map { path -> "com/opentasker/" + path.toUri().path.substringAfterLast("/com/opentasker/") }
            .sorted()

        assertEquals(
            "The RUN_TASK action literal must exist only beside its canonical builder/receiver",
            listOf("com/opentasker/core/external/AutomationTargetReceiver.kt"),
            rawProducers,
        )
    }

    @Test
    fun automationPermissionIsSignatureScoped() {
        val manifest = loadMainManifest()
        val permissions = manifest.getElementsByTagName("permission")
        val automationPermission = (0 until permissions.length)
            .asSequence()
            .map { permissions.item(it) }
            .first { it.attributes.getNamedItem("android:name").nodeValue == AutomationTargetContract.PERMISSION }

        assertEquals(
            "signature",
            automationPermission.attributes.getNamedItem("android:protectionLevel").nodeValue,
        )
    }

    @Test
    fun automationTargetReceiverRequiresAutomationPermission() {
        val manifest = loadMainManifest()
        val receivers = manifest.getElementsByTagName("receiver")
        val targetReceiver = (0 until receivers.length)
            .asSequence()
            .map { receivers.item(it) }
            .first {
                it.attributes.getNamedItem("android:name").nodeValue ==
                    "com.opentasker.core.external.AutomationTargetReceiver"
            }

        assertEquals(
            "true",
            targetReceiver.attributes.getNamedItem("android:exported").nodeValue,
        )
        assertEquals(
            AutomationTargetContract.PERMISSION,
            targetReceiver.attributes.getNamedItem("android:permission").nodeValue,
        )
    }

    @Test
    fun automationTargetReceiverAlwaysFinishesPendingResult() {
        val source = listOf(
            File("src/main/java/com/opentasker/core/external/AutomationTargetReceiver.kt"),
            File("app/src/main/java/com/opentasker/core/external/AutomationTargetReceiver.kt"),
        ).first { it.exists() }.readText()

        assertTrue("goAsync result cleanup should be protected by finally", source.contains("finally"))
        assertTrue("pending result should always finish", source.contains("pending.finish()"))
    }

    @Test
    fun externalVariableExtrasAreCountBounded() {
        val source = listOf(
            File("src/main/java/com/opentasker/core/external/AutomationTargetReceiver.kt"),
            File("app/src/main/java/com/opentasker/core/external/AutomationTargetReceiver.kt"),
        ).first { it.exists() }.readText()

        assertTrue("supplied variable count must be capped", source.contains("MAX_SUPPLIED_VARIABLES"))
        assertTrue("the cap must actually bound extraction", source.contains("take(MAX_SUPPLIED_VARIABLES)"))
    }

    private fun loadMainManifest() =
        DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(
                listOf(
                    File("src/main/AndroidManifest.xml"),
                    File("app/src/main/AndroidManifest.xml"),
                ).first { it.exists() }
            )
            .documentElement

    private fun loadMainSource(relativePath: String): String = ProductionSources.read(relativePath)
}
