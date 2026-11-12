package com.opentasker.core.transfer

import com.opentasker.core.actions.registerActionMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tasker's Notify Sound (538) posts a notification that also plays a sound. It imports as the
 * notification step with its title and text, and the import names the settings that don't come
 * over. The first step is copied from a real export. The second has the same layout with the title
 * left empty and no icon.
 */
class TaskerNotifySoundImportTest {

    @Before
    fun setUp() {
        registerActionMetadata()
    }

    private fun parse(fields: String): TaskerXmlImportReport =
        TaskerXmlImporter.parse(
            rawXml = """
                <TaskerData>
                    <Task sr="task1">
                        <id>1</id><nme>Timer</nme>
                        <Action sr="act3" ve="7"><code>538</code>$fields</Action>
                    </Task>
                </TaskerData>
            """.trimIndent(),
            appVersion = "test",
            importedAtEpochMs = 0L,
        )

    @Test
    fun notifySoundImportsAsANotificationWithItsTitleAndText() {
        val report = parse(
            """<Str sr="arg0" ve="3">番茄钟</Str><Str sr="arg1" ve="3">好梦</Str>""" +
                """<Img sr="arg2" ve="2"><nme>mw_notification_confirmation_number</nme><tint>-1</tint></Img>""" +
                """<Int sr="arg3" val="0"/><Str sr="arg4" ve="3"/><Int sr="arg5" val="3"/><Int sr="arg6" val="0"/>""",
        )

        val step = report.bundle.tasks.single().actions.single()
        assertEquals("notify.show", step.type)
        assertEquals(mapOf("title" to "番茄钟", "text" to "好梦", "tag" to "番茄钟"), step.args)
        assertEquals(listOf("538" to "notify.show"), report.mappedActions.map { it.taskerCode to it.openTaskerActionId })
        assertEquals(1, report.lossyWarnings.size)
        assertTrue(report.lossyWarnings.toString(), "Tasker Notify Sound's sound" in report.lossyWarnings.single())
        assertTrue(report.lossyWarnings.toString(), "number" in report.lossyWarnings.single())
    }

    @Test
    fun anEmptyTitleFallsBackWithoutATag() {
        val report = parse(
            """<Str sr="arg0" ve="3"/><Str sr="arg1" ve="3">Break's over</Str>""" +
                """<Int sr="arg3" val="0"/><Str sr="arg4" ve="3"/><Int sr="arg5" val="3"/><Int sr="arg6" val="0"/>""",
        )

        val step = report.bundle.tasks.single().actions.single()
        assertEquals("notify.show", step.type)
        assertEquals(mapOf("title" to "Tasker notification", "text" to "Break's over"), step.args)
    }
}
