package com.opentasker.core.transfer

import com.opentasker.core.actions.registerActionMetadata
import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.Task
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Tasker's For over ranges and written-out values comes in as For Each values and goes back out. */
class TaskerForValuesTransferTest {

    @Before
    fun setUp() {
        registerActionMetadata()
    }

    private fun parse(xml: String) = TaskerXmlImporter.parse(xml, appVersion = "test", importedAtEpochMs = 0L)

    private fun imported(action: String) = parse(
        "<TaskerData><Task sr=\"task1\"><id>1</id><nme>Imported</nme><Action sr=\"act0\">$action</Action></Task></TaskerData>",
    )

    private fun export(vararg actions: ActionSpec) =
        TaskerXmlExporter.export(emptyList(), listOf(Task(id = 1, name = "Exported", actions = actions.toList())))

    @Test
    fun aRangeOrWrittenOutListImportsAsValuesWithNoForWarning() {
        // The range is from a real Tasker export.
        listOf("1:%HTTPD(#)", "a,b,c", "4:0, 100, 0:8:2").forEach { items ->
            val result = imported("""<code>39</code><Str sr="arg0" ve="3">%i</Str><Str sr="arg1" ve="3">$items</Str>""")
            val loop = result.bundle.tasks.single().actions.single()

            assertEquals(items, "flow.foreach", loop.type)
            assertEquals(items, mapOf("values" to items, "var" to "%i"), loop.args)
            // %HTTPD still gets its own note, as a Tasker built-in this app doesn't fill in.
            assertTrue("$items: ${result.lossyWarnings}", result.lossyWarnings.none { "For" in it || items in it })
        }
    }

    @Test
    fun aWholeArrayStillImportsAsTheArrayName() {
        val loop = imported("""<code>39</code><Str sr="arg0" ve="3">%pair</Str><Str sr="arg1" ve="3">%input()</Str>""")
            .bundle.tasks.single().actions.single()

        assertEquals("input", loop.args["list"])
        assertNull(loop.args["values"])
    }

    @Test
    fun valuesExportAsTaskersItemsAndReadBackTheSame() {
        val result = export(ActionSpec(type = "flow.foreach", args = mapOf("values" to " 1:10, %arr() ", "var" to "i")))
        val loop = result.xml.split("<Action").single { "<code>39</code>" in it }

        assertTrue(loop, """<Str sr="arg0" ve="3">%i</Str>""" in loop)
        assertTrue(loop, """<Str sr="arg1" ve="3">1:10, %arr()</Str>""" in loop)
        assertTrue(result.warnings.toString(), result.warnings.isEmpty())
        assertEquals("1:10, %arr()", parse(result.xml).bundle.tasks.single().actions.single().args["values"])
    }

    @Test
    fun chipReferencesExportTheWayTaskerWritesThem() {
        val values = "1:{{ global.LAST }}, {{ array.names }}, {{count}}"
        val result = export(ActionSpec(type = "flow.foreach", args = mapOf("values" to values, "var" to "i")))
        val loop = result.xml.split("<Action").single { "<code>39</code>" in it }

        assertTrue(loop, """<Str sr="arg1" ve="3">1:%LAST, %names(), %count</Str>""" in loop)
        assertTrue(result.warnings.toString(), result.warnings.isEmpty())
    }

    @Test
    fun anyOtherTemplatePartExportsAsWrittenWithAWarning() {
        val result = export(ActionSpec(type = "flow.foreach", args = mapOf("values" to "{{ event.title }}, x", "var" to "i")))
        val loop = result.xml.split("<Action").single { "<code>39</code>" in it }

        assertTrue(loop, """<Str sr="arg1" ve="3">{{ event.title }}, x</Str>""" in loop)
        assertTrue(result.warnings.toString(), result.warnings.any { "For" in it && "{{ event.title }}" in it })
    }

    @Test
    fun theTemplatesTrueAndFalseAreNotVariables() {
        val result = export(ActionSpec(type = "flow.foreach", args = mapOf("values" to "{{ true }}, {{ global.FLAG }}", "var" to "i")))
        val loop = result.xml.split("<Action").single { "<code>39</code>" in it }

        assertTrue(loop, """<Str sr="arg1" ve="3">{{ true }}, %FLAG</Str>""" in loop)
        assertTrue(result.warnings.toString(), result.warnings.any { "{{ true }}" in it })
    }

    @Test
    fun anArrayNamedUnderAnotherKeyWinsOnExportLikeInARun() {
        val result = export(ActionSpec(type = "flow.foreach", args = mapOf("in" to "xs", "values" to "1:3", "var" to "i")))
        val loop = result.xml.split("<Action").single { "<code>39</code>" in it }

        assertTrue(loop, """<Str sr="arg1" ve="3">%xs()</Str>""" in loop)
    }

    @Test
    fun aNamedArrayWinsOverValuesOnExport() {
        val result = export(ActionSpec(type = "flow.foreach", args = mapOf("list" to "xs", "values" to "1:3", "var" to "i")))
        val loop = result.xml.split("<Action").single { "<code>39</code>" in it }

        assertTrue(loop, """<Str sr="arg1" ve="3">%xs()</Str>""" in loop)
    }
}
