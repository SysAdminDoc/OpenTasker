package com.opentasker.core.transfer

import com.opentasker.core.actions.declaredActionOutputs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tasker's Get Clipboard (code 402). It has no settings of its own (decompiled 6.7.8,
 * `OutputGetClipboard`, prefix `cl`), only a bundle naming the variables it fills: %cl_text and the
 * HTML, URI, image URI, MIME type and extras ones. The layout is copied from a real export.
 */
class TaskerGetClipboardImportTest {
    private fun parse(on: String = ""): TaskerXmlImportReport =
        TaskerXmlImporter.parse(
            rawXml = """
                <TaskerData>
                    <Task sr="task1">
                        <id>1</id><nme>Paste</nme>
                        <Action sr="act0" ve="7">
                            <code>402</code>
                            $on
                            <Bundle sr="arg0">
                                <Vals sr="val">
                                    <net.dinglisch.android.tasker.RELEVANT_VARIABLES>&lt;StringArray sr=""&gt;&lt;_array_net.dinglisch.android.tasker.RELEVANT_VARIABLES0&gt;%cl_extras
Extras
Optional extra info in JSON format that the clipboard may contains&lt;/_array_net.dinglisch.android.tasker.RELEVANT_VARIABLES0&gt;&lt;_array_net.dinglisch.android.tasker.RELEVANT_VARIABLES1&gt;%cl_text
Text
Current clipboard text&lt;/_array_net.dinglisch.android.tasker.RELEVANT_VARIABLES1&gt;&lt;/StringArray&gt;</net.dinglisch.android.tasker.RELEVANT_VARIABLES>
                                    <net.dinglisch.android.tasker.RELEVANT_VARIABLES-type>[Ljava.lang.String;</net.dinglisch.android.tasker.RELEVANT_VARIABLES-type>
                                </Vals>
                            </Bundle>
                        </Action>
                    </Task>
                </TaskerData>
            """.trimIndent(),
            appVersion = "test",
            importedAtEpochMs = 0L,
        )

    @Test
    fun getClipboardImportsAsAClipboardReadIntoClText() {
        val action = parse().bundle.tasks.single().actions.single()

        assertEquals("clipboard.get", action.type)
        assertEquals("cl_text", declaredActionOutputs("clipboard.get").first().resolveName(action.args))
    }

    @Test
    fun theImportSaysWhichOutputsDontComeOverAndWhenTheClipboardCanBeRead() {
        val warning = parse().lossyWarnings.single()

        listOf("%cl_html_text", "%cl_uri", "%cl_image_uri", "%cl_mimetypes()", "%cl_extras", "OpenTasker is open").forEach {
            assertTrue(warning, it in warning)
        }
    }

    @Test
    fun aStepTurnedOffInTaskerStaysOff() {
        val action = parse(on = "<on>false</on>").bundle.tasks.single().actions.single()

        assertEquals("clipboard.get", action.type)
        assertEquals(false, action.enabled)
    }
}
