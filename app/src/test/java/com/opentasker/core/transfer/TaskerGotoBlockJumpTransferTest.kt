package com.opentasker.core.transfer

import com.opentasker.core.actions.registerActionMetadata
import com.opentasker.core.engine.FlowControl
import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.Task
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Tasker Goto kinds 2 to 4 (Top of Loop, End of Loop, End of If) come in and go back out. */
class TaskerGotoBlockJumpTransferTest {

    @Before
    fun setUp() {
        registerActionMetadata()
    }

    private val kinds = mapOf(2 to FlowControl.GOTO_LOOP_TOP, 3 to FlowControl.GOTO_LOOP_END, 4 to FlowControl.GOTO_IF_END)

    private fun parse(xml: String) =
        TaskerXmlImporter.parse(xml, appVersion = "test", importedAtEpochMs = 0L)

    private fun imported(action: String) = parse(
        "<TaskerData><Task sr=\"task1\"><id>1</id><nme>Imported</nme><Action sr=\"act0\">$action</Action></Task></TaskerData>",
    )

    private fun export(vararg actions: ActionSpec) =
        TaskerXmlExporter.export(emptyList(), listOf(Task(id = 1, name = "Exported", actions = actions.toList())))

    @Test
    fun eachKindImportsAsAGotoOfThatKind() {
        kinds.forEach { (kind, to) ->
            // The layout of a real Tasker Goto, which keeps arg1 and arg2 whatever the kind.
            val result = imported("""<code>135</code><Int sr="arg0" val="$kind"/><Int sr="arg1" val="1"/><Str sr="arg2" ve="3"/>""")
            val goto = result.bundle.tasks.single().actions.single()

            assertEquals("kind $kind", "flow.goto", goto.type)
            assertEquals("kind $kind", mapOf("to" to to), goto.args)
            assertTrue("kind $kind: ${result.lossyWarnings}", result.lossyWarnings.isEmpty())
        }
    }

    @Test
    fun anUnknownKindStaysAPlaceholder() {
        val goto = imported("""<code>135</code><Int sr="arg0" val="7"/><Int sr="arg1" val="1"/><Str sr="arg2" ve="3"/>""")
            .bundle.tasks.single().actions.single()

        assertEquals(TaskerXmlImporter.TASKER_UNSUPPORTED_ACTION_ID, goto.type)
    }

    @Test
    fun eachKindExportsAsTaskersKindAndReadsBackTheSame() {
        kinds.forEach { (kind, to) ->
            val result = export(ActionSpec(type = "flow.goto", args = mapOf("to" to to, "label" to "ignored")))
            val goto = result.xml.split("<Action").single { "<code>135</code>" in it }

            listOf("""<Int sr="arg0" val="$kind"/>""", """<Int sr="arg1" val="1"/>""", """<Str sr="arg2" ve="3"></Str>""").forEach {
                assertTrue("kind $kind: $goto", it in goto)
            }
            assertTrue("kind $kind: ${result.warnings}", result.warnings.isEmpty())
            assertEquals("kind $kind", mapOf("to" to to), parse(result.xml).bundle.tasks.single().actions.single().args)
        }
    }

    @Test
    fun anUnknownKindWarnsAndFallsBackToTheStepNumber() {
        val result = export(ActionSpec(type = "flow.goto", args = mapOf("to" to "sideways", "step" to "1")))
        val goto = result.xml.split("<Action").single { "<code>135</code>" in it }

        assertTrue(goto, """<Int sr="arg0" val="0"/>""" in goto && """<Int sr="arg1" val="1"/>""" in goto)
        assertTrue(result.warnings.toString(), result.warnings.any { "'sideways' has no Tasker match" in it })
    }
}
