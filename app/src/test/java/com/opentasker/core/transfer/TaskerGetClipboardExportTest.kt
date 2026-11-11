package com.opentasker.core.transfer

import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.Task
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The clipboard read step goes out as Tasker's Get Clipboard (402). Tasker's one argument there is
 * the bundle listing the variables it fills, the way real exports carry it, and it always fills
 * %cl_text, so a step reading into any other name is warned.
 */
class TaskerGetClipboardExportTest {
    private fun export(vararg args: Pair<String, String>): TaskerXmlExportReport =
        TaskerXmlExporter.export(
            emptyList(),
            listOf(Task(id = 1, name = "Paste", actions = listOf(ActionSpec(type = "clipboard.get", args = mapOf(*args))))),
        )

    @Test
    fun aReadIntoClTextGoesOutAsGetClipboardAndComesBackTheSame() {
        val report = export("var" to "%cl_text")
        val xml = report.xml.replace(Regex(">\\s+<"), "><")

        assertTrue(xml, """<code>402</code><Bundle sr="arg0"><Vals sr="val"><net.dinglisch.android.tasker.RELEVANT_VARIABLES>""" in xml)
        assertTrue(xml, "%cl_text" in xml)
        assertEquals(emptyList<SkippedExportAction>(), report.skippedActions)
        assertEquals(emptyList<String>(), report.warnings)

        val back = TaskerXmlImporter.parse(report.xml, appVersion = "test", importedAtEpochMs = 0L)
        val action = back.bundle.tasks.single().actions.single()
        assertEquals("clipboard.get", action.type)
        assertEquals(mapOf("var" to "%cl_text"), action.args)
    }

    @Test
    fun aReadIntoAnyOtherNameIsWarnedSinceTaskerAlwaysFillsClText() {
        val named = export("var" to "%paste")
        val unnamed = export()

        assertTrue(named.warnings.toString(), named.warnings.single().let { "%cl_text" in it && "%paste" in it })
        assertTrue(unnamed.warnings.toString(), unnamed.warnings.single().let { "%cl_text" in it && "%Clipboard" in it })
        assertTrue(named.xml, "<code>402</code>" in named.xml)
    }
}
