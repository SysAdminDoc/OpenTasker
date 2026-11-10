package com.opentasker.core.transfer

import com.opentasker.core.actions.registerActionMetadata
import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.ContextSpec
import com.opentasker.core.model.ContextType
import com.opentasker.core.model.Profile
import com.opentasker.core.model.Task
import com.opentasker.core.model.Variable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Whatever an export can't carry has to be said out loud, or the copy quietly means something else. */
class TaskerExportWarningsTest {
    @Before
    fun setUp() {
        registerActionMetadata()
    }

    private val task = Task(id = 1, name = "Run", actions = listOf(ActionSpec(type = "vibrate")))

    private fun export(context: ContextSpec) = TaskerXmlExporter.export(
        listOf(Profile(id = 1, name = "P", enterTaskId = 1, contexts = listOf(context))),
        listOf(task),
    )

    @Test
    fun anInvertedContextReadsBackInverted() {
        val xml = export(ContextSpec(ContextType.APPLICATION, mapOf("package" to "com.a"), invert = true)).xml

        val imported = TaskerXmlImporter.parse(xml, appVersion = "test", importedAtEpochMs = 0L).bundle.profiles.single()

        assertTrue(imported.contexts.single().invert)
    }

    @Test
    fun aPlainContextIsNotMarkedInverted() {
        assertFalse(export(ContextSpec(ContextType.DAY, mapOf("days" to "MON"))).xml.contains("<pin>"))
    }

    @Test
    fun aStateComparisonThatCantBeWrittenIsNamed() {
        val report = export(ContextSpec(ContextType.STATE, mapOf("key" to "speed", "operator" to ">", "value" to "10")))

        assertTrue(report.warnings.toString(), report.warnings.any { it.contains("operator") })
    }

    @Test
    fun anEqualsComparisonLosesNothing() {
        val report = export(ContextSpec(ContextType.STATE, mapOf("key" to "wifi", "operator" to "=", "value" to "Home")))

        assertTrue(report.warnings.toString(), report.warnings.isEmpty())
    }

    @Test
    fun eventFiltersThatCantBeWrittenAreNamed() {
        val report = export(ContextSpec(ContextType.EVENT, mapOf("event" to "notification", "title" to "Door")))

        assertTrue(report.warnings.toString(), report.warnings.any { it.contains("title") })
    }

    @Test
    fun aNotificationTagThatCantBeWrittenIsNamed() {
        val tagged = Task(id = 1, name = "T", actions = listOf(ActionSpec(type = "notify.show", args = mapOf("title" to "a", "tag" to "door"))))

        val report = TaskerXmlExporter.export(emptyList(), listOf(tagged))

        assertTrue(report.warnings.toString(), report.warnings.any { it.contains("'door'") })
    }

    @Test
    fun whatTheFileCantDoWithoutComesFirst() {
        val report = TaskerXmlExporter.export(
            listOf(Profile(id = 1, name = "P", enterTaskId = 1, contexts = listOf(ContextSpec(ContextType.LOCATION, mapOf("lat" to "1"))))),
            listOf(task),
            listOf(Variable(name = "API_TOKEN", value = "x", isGlobal = true, isSecret = true)),
        )

        assertEquals(2, report.warnings.size)
        assertTrue(report.warnings.toString(), report.warnings.first().contains("secret"))
    }
}
