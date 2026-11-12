package com.opentasker.core.transfer

import com.opentasker.core.engine.VariableStore
import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.Task
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tasker 6.7.8's maths (decompiled `Expr.d`) strips Android's `\s` from a value, every Unicode
 * White_Space character, before its reader (`yd.a`) skips Java whitespace at the ends and then trims
 * control characters, so a space like U+2009 goes wherever it sits. And a Tasker Equals or Not
 * Equals whose right side is only spaces never holds, which the import now writes as a check that
 * never does instead of a pair with nothing on its right.
 */
class TaskerMathsTrimOrderTest {
    private fun isNumber(value: String): Boolean =
        VariableStore().apply { set("x", value) }.evaluateCondition("%x > 41")

    @Test
    fun aControlCharacterOutsideAThinSpaceStillLeavesANumber() {
        val thin = Char(THIN_SPACE).toString()
        val control = Char(START_OF_HEADING).toString()

        assertTrue(isNumber("42$thin$control"))
        assertTrue(isNumber("$control${thin}42"))
    }

    @Test
    fun aThinSpaceOutsideAControlCharacterGoesWithIt() {
        val thin = Char(THIN_SPACE).toString()
        val control = Char(START_OF_HEADING).toString()

        assertTrue(isNumber("42$control$thin"))
        assertTrue(isNumber("$thin${control}42"))
        assertTrue(isNumber("42$control"))
    }

    @Test
    fun aBlankRightSideImportsAsACheckThatNeverHolds() {
        listOf("8" to "   ", "9" to "   ", "8" to "").forEach { (op, rhs) ->
            val guard = imported(op, rhs)!!

            assertEquals("op $op", "(%count is_set && %count not_set)", guard)
            listOf(null, "0", "5", "five").forEach { count ->
                assertFalse("op $op $count", VariableStore().apply { if (count != null) set("count", count) }.evaluateCondition(guard))
            }
        }
        assertEquals("(%count is_set || %count not_set)", imported("9", ""))
    }

    @Test
    fun theNeverCheckGoesBackToTaskerAsTheSameTwoConditions() {
        val xml = TaskerXmlExporter.export(
            emptyList(),
            listOf(Task(id = 1, name = "Maths", actions = listOf(ActionSpec(type = "var.set", args = mapOf("name" to "%OUT", "value" to "yes"), condition = imported("9", "  ")!!)))),
        ).xml.replace(Regex(">\\s+<"), "><")

        assertTrue(xml, "<lhs>%count</lhs><op>12</op>" in xml)
        assertTrue(xml, "<lhs>%count</lhs><op>13</op>" in xml)
        assertFalse(xml, "<op>9</op>" in xml)
    }

    private fun imported(op: String, rhs: String): String? = TaskerXmlImporter.parse(
        rawXml = """
            <TaskerData>
                <Task sr="task1">
                    <id>1</id><nme>Maths</nme>
                    <Action sr="act0" ve="7">
                        <code>547</code>
                        <Str sr="arg0" ve="3">%out</Str>
                        <Str sr="arg1" ve="3">yes</Str>
                        <Int sr="arg2" val="0"/>
                        <Int sr="arg3" val="0"/>
                        <Int sr="arg4" val="0"/>
                        <ConditionList sr="if">
                            <Condition sr="c0" ve="3"><lhs>%count</lhs><op>$op</op><rhs>$rhs</rhs></Condition>
                        </ConditionList>
                    </Action>
                </Task>
            </TaskerData>
        """.trimIndent(),
        appVersion = "test",
        importedAtEpochMs = 0L,
    ).bundle.tasks.single().actions.single().condition

    private companion object {
        const val THIN_SPACE = 0x2009
        const val START_OF_HEADING = 0x0001
    }
}
