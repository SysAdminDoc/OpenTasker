package com.opentasker.core.transfer

import com.opentasker.core.engine.VariableStore
import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.Task
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tasker's maths Equals and Not Equals (condition ops 8 and 9) import as a pair of comparisons,
 * since there's no maths equals here, and go back out as the single op they came from. Tasker
 * settles an empty side before reading numbers (decompiled 6.7.8 `Expr.b`): one empty side makes
 * Equals false and Not Equals true.
 */
class TaskerMathsEqualityTransferTest {
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

    @Test
    fun aMathsNotEqualsWithNothingOnTheRightAlwaysHolds() {
        val guard = imported("9", "%count", "").first!!

        assertEquals("(%count is_set || %count not_set)", guard)
        listOf(null, "0", "5", "five").forEach { count -> assertTrue("$count", holds(guard, count)) }
    }

    @Test
    fun aMathsEqualsWithNothingOnTheRightNeverHolds() {
        val guard = imported("8", "%count", "").first!!

        listOf(null, "0", "5", "five").forEach { count -> assertFalse("$count", holds(guard, count)) }
    }

    @Test
    fun aMathsSideThatWouldSplitThePairIsDroppedWithANote() {
        val (guard, warnings) = imported("8", "%a||%b", "5")

        assertNull(guard)
        assertTrue(warnings.toString(), warnings.any { "pair of checks" in it })
    }

    @Test
    fun anAlwaysTrueNotEqualsGoesOutAsIsSetOrNotSet() {
        val xml = export("(%count is_set || %count not_set)").compactXml()

        assertTrue(xml, "<bool0>Or</bool0>" in xml)
        assertTrue(xml, "<lhs>%count</lhs><op>12</op>" in xml)
        assertTrue(xml, "<lhs>%count</lhs><op>13</op>" in xml)
    }

    private fun export(guard: String) = TaskerXmlExporter.export(
        emptyList(),
        listOf(Task(id = 1, name = "Maths", actions = listOf(ActionSpec(type = "var.set", args = mapOf("name" to "%OUT", "value" to "yes"), condition = guard)))),
    )

    private fun TaskerXmlExportReport.compactXml() = xml.replace(Regex(">\\s+<"), "><")

    private fun TaskerXmlExportReport.importedGuard() =
        TaskerXmlImporter.parse(xml, appVersion = "test", importedAtEpochMs = 0L).bundle.tasks.single().actions.single().condition

    @Test
    fun aMathsEqualsGoesOutAsOp8AndComesBackTheSame() {
        val guard = "(%count <= 5 && %count >= 5)"
        val report = export(guard)

        assertTrue(report.xml, "<lhs>%count</lhs><op>8</op><rhs>5</rhs>" in report.compactXml())
        assertFalse(report.xml, "<bool0>" in report.xml)
        assertTrue(report.warnings.toString(), report.warnings.none { "run-only-if" in it })
        assertEquals(guard, report.importedGuard())
    }

    @Test
    fun aMathsNotEqualsGoesOutAsOp9AndComesBackTheSame() {
        val guard = "(%count < 5 || %count > 5)"
        val report = export(guard)

        assertTrue(report.xml, "<lhs>%count</lhs><op>9</op><rhs>5</rhs>" in report.compactXml())
        assertFalse(report.xml, "<bool0>" in report.xml)
        assertEquals(guard, report.importedGuard())
    }

    @Test
    fun aMathsEqualsInsideALongerGuardKeepsItsJoiner() {
        val guard = "%mode ~ on && (%count <= 5 && %count >= 5)"
        val report = export(guard)
        val xml = report.compactXml()

        assertTrue(xml, "<bool0>And</bool0>" in xml)
        assertTrue(xml, "<lhs>%mode</lhs><op>2</op><rhs>on</rhs>" in xml)
        assertTrue(xml, "<lhs>%count</lhs><op>8</op><rhs>5</rhs>" in xml)
        assertEquals(guard, report.importedGuard())
    }

    @Test
    fun aPairOverDifferentSidesIsTwoConditions() {
        val xml = export("%a < 5 || %b > 5").compactXml()

        assertTrue(xml, "<bool0>Or</bool0>" in xml)
        assertTrue(xml, "<lhs>%a</lhs><op>6</op><rhs>5</rhs>" in xml)
        assertTrue(xml, "<lhs>%b</lhs><op>7</op><rhs>5</rhs>" in xml)
        assertFalse(xml, "<op>9</op>" in xml)
    }

    @Test
    fun aLoneLessOrEqualStillHasNoTaskerOp() {
        val report = export("%count <= 5")

        assertFalse(report.xml, "<ConditionList" in report.xml)
        assertTrue(report.warnings.toString(), report.warnings.any { "run-only-if" in it })
    }
}
