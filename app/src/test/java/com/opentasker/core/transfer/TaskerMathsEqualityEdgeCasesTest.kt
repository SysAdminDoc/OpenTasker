package com.opentasker.core.transfer

import com.opentasker.core.engine.VariableStore
import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.Task
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The corners of Tasker's maths Equals and Not Equals (ops 8 and 9) around the pair this app writes
 * them as: sides that can't sit in a pair, a right side of spaces only, and the spaces Tasker 6.7.8
 * trims off a number (decompiled `Expr.d` strips Java's `\s`, then `yd` skips Java whitespace at the
 * ends and trims everything up to U+0020, which leaves the no-break spaces on).
 */
class TaskerMathsEqualityEdgeCasesTest {
    private fun imported(op: String, lhs: String, rhs: String): Pair<String?, List<String>> {
        val report = TaskerXmlImporter.parse(
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
                                <Condition sr="c0" ve="3"><lhs>$lhs</lhs><op>$op</op><rhs>$rhs</rhs></Condition>
                            </ConditionList>
                        </Action>
                    </Task>
                </TaskerData>
            """.trimIndent(),
            appVersion = "test",
            importedAtEpochMs = 0L,
        )
        return report.bundle.tasks.single().actions.single().condition to report.lossyWarnings
    }

    private fun holds(guard: String, count: String?) =
        VariableStore().apply { if (count != null) set("count", count) }.evaluateCondition(guard)

    private fun export(guard: String) = TaskerXmlExporter.export(
        emptyList(),
        listOf(Task(id = 1, name = "Maths", actions = listOf(ActionSpec(type = "var.set", args = mapOf("name" to "%OUT", "value" to "yes"), condition = guard)))),
    )

    private fun TaskerXmlExportReport.compactXml() = xml.replace(Regex(">\\s+<"), "><")

    @Test
    fun aNotEqualsWithARightSideThatCantSitInAPairIsDroppedWithANote() {
        // An export writes && as &amp;&amp;.
        listOf("%a||%b", "%a&amp;&amp;%b", "{{n}}", "(5", "5)").forEach { rhs ->
            val (guard, warnings) = imported("9", "%count", rhs)

            assertNull(rhs, guard)
            assertTrue("$rhs $warnings", warnings.any { "pair of checks" in it })
        }
    }

    @Test
    fun aTextOpWithAJoinerInItsRightSideIsNotTakenForAMathsPair() {
        val (_, warnings) = imported("2", "%mode", "on||off")

        assertTrue(warnings.toString(), warnings.none { "pair of checks" in it })
    }

    @Test
    fun aNotEqualsWithOnlySpacesOnTheRightNeverHolds() {
        val guard = imported("9", "%count", "   ").first!!

        assertNotEquals("(%count is_set || %count not_set)", guard)
        listOf(null, "0", "5", "five").forEach { count -> assertFalse("$count", holds(guard, count)) }
    }

    @Test
    fun aMathsPairOverATemplateStaysOutOfOp8And9() {
        listOf(
            "({{count}} <= 5 && {{count}} >= 5)",
            "(%count <= {{n}} && %count >= {{n}})",
            "({{count}} < 5 || {{count}} > 5)",
        ).forEach { guard ->
            val xml = export(guard).compactXml()

            assertFalse("$guard $xml", "<op>8</op>" in xml)
            assertFalse("$guard $xml", "<op>9</op>" in xml)
        }
    }

    @Test
    fun aPairOverDifferentNumbersIsNotNotEquals() {
        val xml = export("(%count < 5 || %count > 6)").compactXml()

        assertFalse(xml, "<op>9</op>" in xml)
        assertTrue(xml, "<lhs>%count</lhs><op>6</op><rhs>5</rhs>" in xml)
        assertTrue(xml, "<lhs>%count</lhs><op>7</op><rhs>6</rhs>" in xml)
    }

    @Test
    fun spacesTaskerTrimsOffANumberStillLeaveANumber() {
        listOf(THIN_SPACE, IDEOGRAPHIC_SPACE, UNIT_SEPARATOR, ' '.code).forEach { space ->
            val pad = Char(space).toString()
            listOf("42$pad", "${pad}42").forEach { value ->
                val variables = VariableStore().apply { set("x", value) }
                assertTrue("%04x".format(space), variables.evaluateCondition("%x > 41"))
                assertTrue("%04x".format(space), variables.evaluateCondition("%x is_even"))
            }
        }
    }

    @Test
    fun noBreakSpacesLeaveANumberUnread() {
        listOf(FIGURE_SPACE, NARROW_NO_BREAK_SPACE).forEach { space ->
            val variables = VariableStore().apply { set("x", "42" + Char(space)) }
            assertFalse("%04x".format(space), variables.evaluateCondition("%x > 41"))
            assertFalse("%04x".format(space), variables.evaluateCondition("%x is_even"))
        }
    }

    @Test
    fun anEqualsImportedFromTaskerReadsThoseSpacesTheSameWay() {
        val guard = imported("8", "%count", "42").first!!

        assertTrue(holds(guard, "42" + Char(THIN_SPACE)))
        assertFalse(holds(guard, "42" + Char(FIGURE_SPACE)))
        assertEquals("(%count <= 42 && %count >= 42)", guard)
    }

    private companion object {
        const val THIN_SPACE = 0x2009
        const val IDEOGRAPHIC_SPACE = 0x3000
        const val UNIT_SEPARATOR = 0x001F
        const val FIGURE_SPACE = 0x2007
        const val NARROW_NO_BREAK_SPACE = 0x202F
    }
}
