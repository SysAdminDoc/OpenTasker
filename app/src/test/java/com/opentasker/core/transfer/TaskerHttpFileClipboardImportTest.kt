package com.opentasker.core.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tasker action codes 339 (HTTP Request), 105 (Set Clipboard) and 410 (Write File) used to import
 * as `tasker.unsupported` placeholders even though this app implements all three natively.
 *
 * Every fixture below reproduces the argument layout of a real Tasker export, including the
 * lexicographic child order Tasker actually writes (arg1, arg10, arg11, arg12, arg2, ...). That
 * ordering is the point: a positional reader lines the fields up wrongly against it.
 */
class TaskerHttpFileClipboardImportTest {

    private fun parseSingleAction(action: String) =
        TaskerXmlImporter.parse(
            rawXml = """
                <TaskerData>
                    <Task sr="task1">
                        <id>1</id>
                        <nme>Imported</nme>
                        $action
                    </Task>
                </TaskerData>
            """.trimIndent(),
            appVersion = "test",
            importedAtEpochMs = 0L,
        )

    @Test
    fun httpRequestImportsMethodUrlHeadersBodyAndTimeout() {
        val report = parseSingleAction(
            """
            <Action sr="act0">
                <code>339</code>
                <Int sr="arg1" val="1"/>
                <Int sr="arg10" val="0"/>
                <Int sr="arg11" val="0"/>
                <Int sr="arg12" val="1"/>
                <Str sr="arg2" ve="3">https://example.invalid/sync</Str>
                <Str sr="arg3" ve="3">X-API-Version:1</Str>
                <Str sr="arg4" ve="3"/>
                <Str sr="arg5" ve="3">{"content":"hello"}</Str>
                <Str sr="arg6" ve="3"/>
                <Str sr="arg7" ve="3"/>
                <Int sr="arg8" val="30"/>
                <Int sr="arg9" val="0"/>
            </Action>
            """.trimIndent(),
        )

        val action = report.bundle.tasks.single().actions.single()
        assertEquals("http.request", action.type)
        assertEquals("POST", action.args["method"])
        assertEquals("https://example.invalid/sync", action.args["url"])
        assertEquals("X-API-Version:1", action.args["headers"])
        assertEquals("""{"content":"hello"}""", action.args["body"])
        assertEquals("30", action.args["timeout_sec"])
        assertTrue(report.unsupportedActions.isEmpty())
    }

    @Test
    fun httpRequestMethodIndexZeroIsGet() {
        val report = parseSingleAction(
            """
            <Action sr="act0">
                <code>339</code>
                <Int sr="arg1" val="0"/>
                <Str sr="arg2" ve="3">https://example.invalid/get</Str>
                <Int sr="arg8" val="5"/>
            </Action>
            """.trimIndent(),
        )

        val action = report.bundle.tasks.single().actions.single()
        assertEquals("GET", action.args["method"])
        assertEquals("https://example.invalid/get", action.args["url"])
        assertEquals("5", action.args["timeout_sec"])
    }

    @Test
    fun httpRequestReportsTheFieldsItDoesNotImportInsteadOfGuessingThem() {
        val report = parseSingleAction(
            """
            <Action sr="act0">
                <code>339</code>
                <Int sr="arg1" val="0"/>
                <Str sr="arg2" ve="3">https://example.invalid/download</Str>
                <Str sr="arg4" ve="3">page=2</Str>
                <Str sr="arg6" ve="3">Documents/upload.bin</Str>
                <Str sr="arg7" ve="3">Pictures/out.jpg</Str>
            </Action>
            """.trimIndent(),
        )

        val action = report.bundle.tasks.single().actions.single()
        assertEquals("http.request", action.type)
        // The unmapped Tasker fields must not be smuggled into a field they do not belong to.
        assertNull(action.args["query"])
        assertNull(action.args["body"])
        assertNull(action.args["output_file"])
        assertNull(action.args["body_file"])
        val warning = report.lossyWarnings.single { it.contains("HTTP request fields not imported") }
        assertTrue(warning, warning.contains("query parameters"))
        assertTrue(warning, warning.contains("the file to send"))
        assertTrue(warning, warning.contains("the output file"))
    }

    @Test
    fun httpRequestWithAnUnknownMethodIndexImportsWithoutAMethodAndSaysSo() {
        val report = parseSingleAction(
            """
            <Action sr="act0">
                <code>339</code>
                <Int sr="arg1" val="99"/>
                <Str sr="arg2" ve="3">https://example.invalid/thing</Str>
            </Action>
            """.trimIndent(),
        )

        val action = report.bundle.tasks.single().actions.single()
        assertEquals("http.request", action.type)
        // Silently defaulting to GET would send a different request than the user wrote.
        assertNull(action.args["method"])
        assertTrue(report.lossyWarnings.any { it.contains("the request method (Tasker index 99)") })
    }

    @Test
    fun setClipboardImportsItsText() {
        val report = parseSingleAction(
            """
            <Action sr="act0">
                <code>105</code>
                <Str sr="arg0" ve="3">%YZM1</Str>
                <Int sr="arg1" val="0"/>
                <Str sr="arg2" ve="3"/>
            </Action>
            """.trimIndent(),
        )

        val action = report.bundle.tasks.single().actions.single()
        assertEquals("clipboard.set", action.type)
        assertEquals("%YZM1", action.args["text"])
        assertTrue(report.lossyWarnings.none { it.contains("clipboard") })
    }

    @Test
    fun setClipboardReportsTheAddOptionItCannotHonour() {
        val report = parseSingleAction(
            """
            <Action sr="act0">
                <code>105</code>
                <Str sr="arg0" ve="3">appended</Str>
                <Int sr="arg1" val="1"/>
            </Action>
            """.trimIndent(),
        )

        assertEquals("clipboard.set", report.bundle.tasks.single().actions.single().type)
        assertTrue(report.lossyWarnings.any { it.contains("clipboard \"Add\" option") })
    }

    @Test
    fun writeFileImportsPathAndText() {
        val report = parseSingleAction(
            """
            <Action sr="act0">
                <code>410</code>
                <Str sr="arg0" ve="3">Download/clipboard-online</Str>
                <Str sr="arg1" ve="3">%http_data[content]</Str>
                <Int sr="arg2" val="0"/>
                <Int sr="arg3" val="0"/>
            </Action>
            """.trimIndent(),
        )

        val action = report.bundle.tasks.single().actions.single()
        assertEquals("file.write", action.type)
        assertEquals("Download/clipboard-online", action.args["path"])
        assertEquals("%http_data[content]", action.args["text"])
    }

    @Test
    fun anAppendingWriteFileImportsAsAppendRatherThanTruncatingTheFile() {
        val report = parseSingleAction(
            """
            <Action sr="act0">
                <code>410</code>
                <Str sr="arg0" ve="3">Download/log.txt</Str>
                <Str sr="arg1" ve="3">line</Str>
                <Int sr="arg2" val="1"/>
                <Int sr="arg3" val="1"/>
            </Action>
            """.trimIndent(),
        )

        val action = report.bundle.tasks.single().actions.single()
        // Importing an appending Tasker action as file.write would destroy the file's contents.
        assertEquals("file.append", action.type)
        assertEquals("Download/log.txt", action.args["path"])
        assertEquals("line", action.args["text"])
        assertTrue(report.lossyWarnings.any { it.contains("Add Newline") })
    }

    @Test
    fun fieldsAreReadByArgumentIndexSoAnOmittedFieldDoesNotShiftTheRest() {
        // Tasker omits fields it considers unset. arg3 (headers) is absent here, so a positional
        // reader would slide the body into the headers field.
        val report = parseSingleAction(
            """
            <Action sr="act0">
                <code>339</code>
                <Int sr="arg1" val="1"/>
                <Str sr="arg2" ve="3">https://example.invalid/x</Str>
                <Str sr="arg5" ve="3">body-only</Str>
            </Action>
            """.trimIndent(),
        )

        val action = report.bundle.tasks.single().actions.single()
        assertNull(action.args["headers"])
        assertEquals("body-only", action.args["body"])
        assertEquals("https://example.invalid/x", action.args["url"])
    }
}
