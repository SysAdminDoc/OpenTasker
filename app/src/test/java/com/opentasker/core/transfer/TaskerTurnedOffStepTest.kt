package com.opentasker.core.transfer

import com.opentasker.core.actions.registerActionMetadata
import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.Task
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** A step switched off in Tasker comes in switched off, and goes back out that way. */
class TaskerTurnedOffStepTest {

    @Before
    fun setUp() {
        registerActionMetadata()
    }

    private fun import(xml: String): List<ActionSpec> =
        TaskerXmlImporter.parse(xml, appVersion = "test", importedAtEpochMs = 0L).bundle.tasks.single().actions

    @Test
    fun aStepTaskerHasOffImportsOff() {
        val actions = import(
            """
                <TaskerData>
                    <Task sr="task7">
                        <id>7</id>
                        <nme>Partly off</nme>
                        <Action sr="act0" ve="7">
                            <code>523</code>
                            <Str sr="arg0" ve="3">Title</Str>
                            <Str sr="arg1" ve="3">Text</Str>
                        </Action>
                        <Action sr="act1" ve="7">
                            <code>137</code>
                            <on>false</on>
                        </Action>
                        <Action sr="act2" ve="7">
                            <code>9999</code>
                            <label>Later</label>
                            <on>false</on>
                        </Action>
                        <Action sr="act3" ve="7">
                            <code>137</code>
                            <on>true</on>
                        </Action>
                    </Task>
                </TaskerData>
            """.trimIndent(),
        )

        assertEquals(listOf("notify.show", "flow.stop", TaskerXmlImporter.TASKER_UNSUPPORTED_ACTION_ID, "flow.stop"), actions.map { it.type })
        assertEquals(listOf(true, false, false, true), actions.map { it.enabled })
        assertEquals("Later", actions[2].label)
    }

    @Test
    fun aStepTurnedOffExportsOffAndReadsBackOff() {
        val xml = TaskerXmlExporter.export(
            emptyList(),
            listOf(
                Task(
                    id = 1,
                    name = "Exported",
                    actions = listOf(
                        ActionSpec(type = "notify.show", args = mapOf("title" to "Hi", "text" to "There")),
                        ActionSpec(type = "flow.stop", enabled = false),
                        ActionSpec(type = "var.set", args = mapOf("name" to "%a", "value" to "1"), enabled = false),
                    ),
                ),
            ),
        ).xml
        val (notify, stop, set) = xml.split("<Action").drop(1)

        assertFalse(notify, "<on>" in notify)
        listOf(stop, set).forEach { block -> assertEquals(block, 1, block.split("<on>false</on>").size - 1) }

        val back = import(xml)
        assertEquals(listOf("notify.show", "flow.stop", "var.set"), back.map { it.type })
        assertEquals(listOf(true, false, false), back.map { it.enabled })
    }

    @Test
    fun aBundleWritesOnlyTheStepsThatAreOff() {
        fun bundle(vararg actions: ActionSpec) = OpenTaskerBundleCodec.encode(
            OpenTaskerBundleCodec.build(
                appVersion = "test",
                exportedAtEpochMs = 0L,
                profiles = emptyList(),
                tasks = listOf(Task(id = 1, name = "T", actions = actions.toList())),
            ),
        )
        val set = ActionSpec(type = "var.set", args = mapOf("name" to "%a", "value" to "1"))

        // A task with every step on reads the same as before, so older builds still take it.
        assertFalse(bundle(set, set).contains("\"enabled\""))

        val json = bundle(set, set.copy(enabled = false))
        assertEquals(json, 1, json.split("\"enabled\": false").size - 1)
        assertTrue(json, "\"enabled\": true" !in json)
        assertEquals(listOf(true, false), OpenTaskerBundleCodec.decode(json).tasks.single().actions.map { it.enabled })
    }
}
