package com.opentasker.core.transfer

import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.Task
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tasker's Variable Clear (code 549): arg0 the name or pattern, arg1 Pattern Matching, arg2 Local
 * Variables Only and arg3 Clear All Variables, read the way Tasker 6.7.8 reads them (`dq.I`).
 */
class TaskerVariableClearTransferTest {

    private fun parse(xml: String) =
        TaskerXmlImporter.parse(
            rawXml = "<TaskerData><Task sr=\"task1\"><id>1</id><nme>Imported</nme><Action sr=\"act0\">$xml</Action></Task></TaskerData>",
            appVersion = "test",
            importedAtEpochMs = 0L,
        )

    private fun action(xml: String): ActionSpec = parse(xml).bundle.tasks.single().actions.single()

    private fun clear(name: String, pattern: Int = 0, localOnly: Int = 0, clearAll: Int = 0) =
        """<code>549</code><Str sr="arg0" ve="3">$name</Str><Int sr="arg1" val="$pattern"/>""" +
            """<Int sr="arg2" val="$localOnly"/><Int sr="arg3" val="$clearAll"/>"""

    private fun exported(args: Map<String, String>): String =
        TaskerXmlExporter.export(emptyList(), listOf(Task(id = 1, name = "Exported", actions = listOf(ActionSpec(type = "var.clear", args = args))))).xml

    private val placeholder = TaskerXmlImporter.TASKER_UNSUPPORTED_ACTION_ID

    @Test
    fun noNameClearsEveryLocalAndClearAllCountsOnlyThen() {
        assertEquals(mapOf("name" to ""), action(clear("", pattern = 1, localOnly = 1)).args)
        assertEquals(mapOf("name" to "", "clear_all" to "true"), action(clear("", clearAll = 1)).args)
    }

    @Test
    fun aPatternComesOverWithItsLocalOnlySwitch() {
        assertEquals(mapOf("name" to "%item*", "pattern" to "true"), action(clear("%item*", pattern = 1)).args)
        assertEquals(
            mapOf("name" to "%a/%B", "pattern" to "true", "local_only" to "true"),
            action(clear("%a/%B", pattern = 1, localOnly = 1)).args,
        )
    }

    @Test
    fun whatTaskerWontClearStaysAPlaceholder() {
        listOf(
            // One key of a JSON variable, or an array element.
            clear("%json.key"),
            clear("%arr(1)"),
            // The name to clear is held in %which.
            clear("%%which"),
            clear("%%which", pattern = 1),
            // A switch taken from a variable.
            """<code>549</code><Str sr="arg0" ve="3">%a</Str><Int sr="arg1"><var>%usePattern</var></Int>""",
        ).forEach { assertEquals(it, placeholder, action(it).type) }
    }

    @Test
    fun anExportWritesEverySwitchBack() {
        assertTrue(
            exported(mapOf("name" to "%item*", "pattern" to "true", "local_only" to "true")).contains(
                """<Str sr="arg0" ve="3">%item*</Str>""" + "\n" +
                    """      <Int sr="arg1" val="1"/>""" + "\n" +
                    """      <Int sr="arg2" val="1"/>""" + "\n" +
                    """      <Int sr="arg3" val="0"/>""",
            ),
        )
        val all = exported(mapOf("name" to "", "clear_all" to "true"))
        assertTrue(all, """<Str sr="arg0" ve="3"></Str>""" in all && """<Int sr="arg3" val="1"/>""" in all)
        // A plain name goes out with the % Tasker needs.
        assertTrue("""<Str sr="arg0" ve="3">%Recording</Str>""" in exported(mapOf("name" to "Recording")))
    }

    @Test
    fun anExportImportsBackTheSame() {
        listOf(
            mapOf("name" to "%Recording"),
            mapOf("name" to "%item*", "pattern" to "true"),
            mapOf("name" to "%a/%b", "pattern" to "true", "local_only" to "true"),
            mapOf("name" to ""),
            mapOf("name" to "", "clear_all" to "true"),
        ).forEach { args ->
            val back = TaskerXmlImporter.parse(rawXml = exported(args), appVersion = "test", importedAtEpochMs = 0L)
                .bundle.tasks.single().actions.single()
            assertEquals(args.toString(), "var.clear" to args, back.type to back.args)
        }
    }
}
