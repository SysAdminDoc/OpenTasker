package com.opentasker.core.transfer

import com.opentasker.core.engine.VariableStore
import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.Task
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tasker's Is Even and Is Odd (condition ops 10 and 11). The expected values follow Tasker 6.7.8's
 * own check (decompiled `Expr`): an empty value or one that isn't a number is neither, and
 * otherwise Even is `value % 2.0 == 0.0` and Odd is `value % 2.0 != 0.0`. The layouts are copied
 * from a real export, which leaves a stale right side on some of them that Tasker ignores.
 */
class TaskerEvenOddConditionTest {
    private fun holds(guard: String, ind: String): Boolean {
        val variables = VariableStore()
        variables.set("ind", ind)
        return variables.evaluateCondition(guard)
    }

    private fun importGuard(conditionListXml: String): Pair<String?, List<String>> {
        val report = TaskerXmlImporter.parse(
            rawXml = """
                <TaskerData>
                    <Task sr="task1">
                        <id>1</id><nme>Parity</nme>
                        <Action sr="act0" ve="7">
                            <code>547</code>
                            <Str sr="arg0" ve="3">%newsout</Str>
                            <Str sr="arg1" ve="3">%newsout chunk</Str>
                            <Int sr="arg2" val="0"/>
                            <Int sr="arg3" val="0"/>
                            <Int sr="arg4" val="0"/>
                            $conditionListXml
                        </Action>
                    </Task>
                </TaskerData>
            """.trimIndent(),
            appVersion = "test",
            importedAtEpochMs = 0L,
        )
        return report.bundle.tasks.single().actions.single().condition to report.lossyWarnings
    }

    @Test
    fun evenAndOddFollowTaskersRemainderRule() {
        mapOf(
            "4" to (true to false),
            "0" to (true to false),
            "4.0" to (true to false),
            "-6" to (true to false),
            "7" to (false to true),
            "-3" to (false to true),
            "2.5" to (false to true),
            "" to (false to false),
            "four" to (false to false),
            "NaN" to (false to false),
            "Infinity" to (false to false),
            "1e400" to (false to false),
        ).forEach { (value, expected) ->
            assertEquals("'$value' is_even", expected.first, holds("%ind is_even", value))
            assertEquals("'$value' is_odd", expected.second, holds("%ind is_odd", value))
        }
    }

    @Test
    fun anOddConditionImportsAndIgnoresTheRightSideTaskerLeftBehind() {
        val (guard, warnings) = importGuard(
            """
            <ConditionList sr="if">
                <Condition sr="c0" ve="3">
                    <lhs>%ind</lhs>
                    <op>11</op>
                    <rhs>4</rhs>
                </Condition>
            </ConditionList>
            """.trimIndent(),
        )

        assertEquals("%ind is_odd", guard)
        assertTrue(warnings.toString(), warnings.none { "Run only if" in it })
        assertTrue(holds(guard!!, "5"))
        assertFalse(holds(guard, "4"))
    }

    @Test
    fun anEvenConditionJoinsTheOthersInItsList() {
        val (guard, warnings) = importGuard(
            """
            <ConditionList sr="if">
                <bool0>And</bool0>
                <Condition sr="c0" ve="3">
                    <lhs>%ind</lhs>
                    <op>10</op>
                    <rhs>4</rhs>
                </Condition>
                <Condition sr="c1" ve="3">
                    <lhs>%ind</lhs>
                    <op>7</op>
                    <rhs>4</rhs>
                </Condition>
            </ConditionList>
            """.trimIndent(),
        )

        assertEquals("%ind is_even && %ind > 4", guard)
        assertTrue(warnings.toString(), warnings.none { "Run only if" in it })
        assertTrue(holds(guard!!, "6"))
        assertFalse(holds(guard, "4"))
        assertFalse(holds(guard, "7"))
    }

    @Test
    fun aLeftoverRightSideDoesNotDropAJoinedList() {
        val (guard, warnings) = importGuard(
            """
            <ConditionList sr="if">
                <bool0>And</bool0>
                <Condition sr="c0" ve="3">
                    <lhs>%ind</lhs>
                    <op>10</op>
                    <rhs>(</rhs>
                </Condition>
                <Condition sr="c1" ve="3">
                    <lhs>%ind</lhs>
                    <op>7</op>
                    <rhs>4</rhs>
                </Condition>
            </ConditionList>
            """.trimIndent(),
        )

        assertEquals("%ind is_even && %ind > 4", guard)
        assertTrue(warnings.toString(), warnings.none { "Run only if" in it })
    }

    @Test
    fun aJoinedEvenGuardGoesOutWithItsJoiner() {
        val guard = "%ind is_even && %ind > 4"
        val report = TaskerXmlExporter.export(
            emptyList(),
            listOf(Task(id = 1, name = "Parity", actions = listOf(ActionSpec(type = "var.set", args = mapOf("name" to "%OUT", "value" to "yes"), condition = guard)))),
        )
        val xml = report.xml.replace(Regex(">\\s+<"), "><")
        assertTrue(xml, "<bool0>And</bool0>" in xml)
        assertTrue(xml, "<lhs>%ind</lhs><op>10</op>" in xml)
        assertTrue(xml, "<lhs>%ind</lhs><op>7</op><rhs>4</rhs>" in xml)

        val back = TaskerXmlImporter.parse(report.xml, appVersion = "test", importedAtEpochMs = 0L)
            .bundle.tasks.single().actions.single().condition
        assertEquals(guard, back)
    }

    @Test
    fun evenAndOddGoOutAsOps10And11AndComeBackTheSame() {
        listOf("%ind is_even" to "10", "%ind is_odd" to "11").forEach { (guard, op) ->
            val report = TaskerXmlExporter.export(
                emptyList(),
                listOf(Task(id = 1, name = "Parity", actions = listOf(ActionSpec(type = "var.set", args = mapOf("name" to "%OUT", "value" to "yes"), condition = guard)))),
            )
            assertTrue(report.xml, "<lhs>%ind</lhs><op>$op</op>" in report.xml.replace(Regex(">\\s+<"), "><"))
            assertTrue(report.warnings.toString(), report.warnings.none { "run-only-if" in it })

            val back = TaskerXmlImporter.parse(report.xml, appVersion = "test", importedAtEpochMs = 0L)
                .bundle.tasks.single().actions.single().condition
            assertEquals(guard, back)
        }
    }

    @Test
    fun aComparisonWithTheWordItselfIsStillAComparison() {
        assertTrue(holds("%ind == is_even", "is_even"))
        assertFalse(holds("%ind == is_even", "4"))
    }
}
