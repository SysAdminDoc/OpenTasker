package com.opentasker.core.transfer

import com.opentasker.core.engine.VariableStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How Tasker 6.7.8's maths reads one value (decompiled `Expr.d` and `yd`): spaces are dropped,
 * `0x` is hexadecimal, Java's decimal form takes a trailing `f` or `d`, a `+` or `-` inside an
 * exponent is an operator, and the result is rounded half up to 10 decimal places. Its maths
 * Equals and Not Equals (condition ops 8 and 9) compare numbers, not text.
 */
class TaskerMathsNumberReadingTest {

    private fun store(vararg values: Pair<String, String>) = VariableStore().apply {
        values.forEach { (name, value) -> set(name, value) }
    }

    private fun between(value: String, low: String, high: String) =
        store("x" to value).evaluateCondition("%x > $low && %x < $high")

    @Test
    fun aNumberIsReadTheWayTaskersMathsReadsIt() {
        assertTrue(between("4f", "3", "5"))
        assertTrue(between("4D", "3", "5"))
        assertTrue(between(" 4 ", "3", "5"))
        assertTrue(between("1 000", "999", "1001"))
        assertTrue(between("0x10", "15", "17"))
        assertTrue(between("0XfF", "254", "256"))
        assertTrue(between("1e3", "999", "1001"))
        assertTrue(between("-.5", "-1", "0"))
    }

    @Test
    fun anExponentWithASignOrAHexFloatIsNotANumber() {
        listOf("1e+3", "1e-3", "0x1p3", "0x", "--4", "4ff", "1,000").forEach { value ->
            val variables = store("x" to value)
            assertFalse(value, variables.evaluateCondition("%x < 100000"))
            assertFalse(value, variables.evaluateCondition("%x > -100000"))
        }
    }

    @Test
    fun bothSidesAreRoundedToTenDecimalPlaces() {
        val sum = store("x" to "0.30000000000000004")
        assertFalse(sum.evaluateCondition("%x > 0.3"))
        assertTrue(sum.evaluateCondition("%x <= 0.3"))
        assertTrue(sum.evaluateCondition("%x >= 0.3"))

        // Half up: 0.00000000005 is 0.0000000001, and 0.00000000004 is 0.
        assertTrue(store("x" to "0.00000000005").evaluateCondition("%x > 0"))
        assertFalse(store("x" to "0.00000000004").evaluateCondition("%x > 0"))
    }

    @Test
    fun evenAndOddReadTheSameNumbers() {
        assertTrue(store("x" to "0x11").evaluateCondition("%x is_odd"))
        assertTrue(store("x" to "4f").evaluateCondition("%x is_even"))
        assertFalse(store("x" to "1e+2").evaluateCondition("%x is_even"))
    }

    @Test
    fun anUnsetNameAfterAnEscapedPercentIsBlank() {
        assertEquals("%", store().expand("""\%%name"""))
        assertEquals("Hi %!", store().expand("""Hi \%%name!"""))
        assertEquals("%Ann", store("name" to "Ann").expand("""\%%name"""))
    }

    private fun importedCondition(op: String, rhs: String): String? = TaskerXmlImporter.parse(
        rawXml = """
            <TaskerData>
                <Task sr="task1">
                    <id>1</id><nme>Maths</nme>
                    <Action sr="act0">
                        <code>37</code>
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

    @Test
    fun anImportedMathsEqualsComparesNumbers() {
        val equals = importedCondition("8", "5")!!
        assertTrue(store("count" to "5.0").evaluateCondition(equals))
        assertTrue(store("count" to "5").evaluateCondition(equals))
        assertFalse(store("count" to "6").evaluateCondition(equals))
        assertFalse(store("count" to "five").evaluateCondition(equals))

        val zero = importedCondition("8", "0")!!
        assertTrue(store().evaluateCondition(zero))
        assertTrue(store("count" to "").evaluateCondition(zero))
    }

    @Test
    fun anImportedMathsNotEqualsComparesNumbers() {
        val notEquals = importedCondition("9", "5")!!
        assertFalse(store("count" to "5.0").evaluateCondition(notEquals))
        assertTrue(store("count" to "6").evaluateCondition(notEquals))
        assertTrue(store().evaluateCondition(notEquals))
        // Tasker's maths gives up on a side that isn't a number, so Not Equals is false too.
        assertFalse(store("count" to "five").evaluateCondition(notEquals))
    }

    @Test
    fun theTextEqualsStillComparesText() {
        val equals = importedCondition("0", "5")!!
        assertFalse(store("count" to "5.0").evaluateCondition(equals))
        assertTrue(store("count" to "5").evaluateCondition(equals))
    }
}
