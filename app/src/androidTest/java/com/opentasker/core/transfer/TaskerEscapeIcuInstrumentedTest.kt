package com.opentasker.core.transfer

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.opentasker.core.engine.VariableStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Tasker escape and maths reading under Android's ICU regex engine, which the JVM tests can't
 * reach: Search Replace's nested lookbehind that tells `\%found1` (text) from `\\%found1` (the
 * element), and the number pattern conditions read with.
 */
@RunWith(AndroidJUnit4::class)
class TaskerEscapeIcuInstrumentedTest {

    private fun searchReplace(replaceWith: String) =
        TaskerXmlImporter.parse(
            rawXml = "<TaskerData><Task sr=\"task1\"><id>1</id><nme>Imported</nme><Action sr=\"act0\">" +
                """<code>598</code><Str sr="arg0" ve="3">%text</Str><Str sr="arg1" ve="3">\d+</Str><Int sr="arg2" val="0"/>""" +
                """<Int sr="arg3" val="0"/><Int sr="arg4" val="0"/><Str sr="arg5" ve="3">%found</Str>""" +
                """<Int sr="arg6" val="1"/><Str sr="arg7" ve="3">$replaceWith</Str>""" +
                "</Action></Task></TaskerData>",
            appVersion = "test",
            importedAtEpochMs = 0L,
        ).bundle.tasks.single().actions.single()

    @Test
    fun oneBackslashWritesTheElementAsTextAndTwoStillReadIt() {
        assertEquals("text.replace", searchReplace("""[\%found1]""").type)
        assertEquals(TaskerXmlImporter.TASKER_UNSUPPORTED_ACTION_ID, searchReplace("""[\\%found1]""").type)
        assertEquals(TaskerXmlImporter.TASKER_UNSUPPORTED_ACTION_ID, searchReplace("""%found1""").type)
    }

    @Test
    fun conditionsReadNumbersTheWayTaskerDoes() {
        fun between(value: String, low: Int, high: Int) =
            VariableStore().apply { set("x", value) }.evaluateCondition("%x > $low && %x < $high")

        assertTrue(between("4f", 3, 5))
        assertTrue(between("0x10", 15, 17))
        assertTrue(between("1 000", 999, 1001))
        assertFalse(between("1e+3", 999, 1001))
        // Arabic-Indic digits aren't digits to Tasker's parser, though ICU's \d would take them.
        assertFalse(between(Char(ARABIC_INDIC_FOUR).toString(), 3, 5))
    }

    private companion object {
        const val ARABIC_INDIC_FOUR = 0x0664
    }
}
