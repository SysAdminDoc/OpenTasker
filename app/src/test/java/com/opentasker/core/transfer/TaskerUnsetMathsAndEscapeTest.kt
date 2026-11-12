package com.opentasker.core.transfer

import com.opentasker.core.engine.VariableStore
import com.opentasker.core.expressions.TemplateExpressionEngine
import com.opentasker.core.expressions.TemplateScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Two things Tasker 6.7.8 does when it fills variables in (decompiled `dq.S` and `Expr`): a
 * backslash before a variable writes it as text, and in maths an unset or empty variable is 0.
 */
class TaskerUnsetMathsAndEscapeTest {

    private fun store(vararg values: Pair<String, String>) = VariableStore().apply {
        values.forEach { (name, value) -> set(name, value) }
    }

    @Test
    fun aBackslashBeforeAVariableWritesItAsText() {
        val variables = store("name" to "Ann")

        assertEquals("%name", variables.expand("""\%name"""))
        assertEquals("Hi %name!", variables.expand("""Hi \%name!"""))
        assertEquals("%Ann", variables.expand("""\%%name"""))
        assertEquals("""\Ann""", variables.expand("""\\%name"""))
        // A backslash before anything else stays, regex escapes included.
        assertEquals("""\d+ \% \n""", variables.expand("""\d+ \% \n"""))
    }

    @Test
    fun anEscapedReferenceKeepsItsBracketsAndIsNotRun() {
        val escaped = store("csv" to "a,b", "arr1" to "x")
        val plain = store("csv" to "a,b")

        assertEquals("%arr(1) %csv(split:,)", escaped.expand("""\%arr(1) \%csv(split:,)"""))

        // Running the split stores a two-item array. Escaped, it stores nothing.
        plain.expand("%csv(split:,)")
        assertEquals("2", plain.expand("%csv_split(#)"))
        assertNotEquals("2", escaped.expand("%csv_split(#)"))
    }

    @Test
    fun anUnsetOrEmptyVariableIsZeroInANumericComparison() {
        listOf(store(), store("x" to "")).forEach { variables ->
            assertTrue(variables.evaluateCondition("%x < 5"))
            assertFalse(variables.evaluateCondition("%x > 5"))
            assertTrue(variables.evaluateCondition("%x >= 0"))
            assertFalse(variables.evaluateCondition("%x <= -1"))
            assertTrue(variables.evaluateCondition("%x is_even"))
            assertFalse(variables.evaluateCondition("%x is_odd"))
            // Runs and previews read a condition through the template stage as well.
            assertTrue(variables.evaluateCondition("%x < 5", TemplateExpressionEngine()))
        }
    }

    @Test
    fun eachUnsetVariableIsZeroOnItsOwn() {
        // %a%b is 1 and then 0, so ten.
        val variables = store("a" to "1")

        assertTrue(variables.evaluateCondition("%a%b > 9"))
        assertTrue(variables.evaluateCondition("%a%b < 11"))
    }

    @Test
    fun aTextComparisonStillSeesAnUnsetVariableAsWritten() {
        assertTrue(store().evaluateCondition("%x ~ %*"))
    }

    @Test
    fun onlyAFiniteDecimalIsANumber() {
        listOf("0x1p3", "NaN", "Infinity", "-Infinity", "1e400", "four").forEach { value ->
            val variables = store("x" to value)
            assertFalse(value, variables.evaluateCondition("%x < 1000"))
            assertFalse(value, variables.evaluateCondition("%x > -1000"))
            assertFalse(value, variables.evaluateCondition("%x is_even"))
            assertFalse(value, variables.evaluateCondition("%x is_odd"))
        }
        listOf("4", "+4", "4.", ".5", "-2.5e1", "1E2").forEach { value ->
            assertTrue(value, store("x" to value).evaluateCondition("%x < 1000"))
        }
    }

    private fun imported(actionXml: String) =
        TaskerXmlImporter.parse(
            rawXml = "<TaskerData><Task sr=\"task1\"><id>1</id><nme>Imported</nme><Action sr=\"act0\">$actionXml</Action></Task></TaskerData>",
            appVersion = "test",
            importedAtEpochMs = 0L,
        ).bundle.tasks.single().actions.single()

    private fun doMaths(value: String) = imported(
        """<code>547</code><Str sr="arg0" ve="3">%total</Str><Str sr="arg1" ve="3">$value</Str>""" +
            """<Int sr="arg2" val="0"/><Int sr="arg3" val="1"/><Int sr="arg4" val="0"/>""",
    ).args.getValue("value")

    private fun fill(template: String, vararg task: Pair<String, String>) =
        TemplateExpressionEngine().expand(template, TemplateScope(task = mapOf(*task))).value

    @Test
    fun doMathsReadsAnUnsetOrEmptyVariableAsZero() {
        val sum = doMaths("%count + 1")
        assertEquals("1", fill(sum))
        assertEquals("1", fill(sum, "count" to ""))
        assertEquals("6", fill(sum, "count" to "5"))

        val plain = doMaths("%count")
        assertEquals("0", fill(plain))
        assertEquals("5", fill(plain, "count" to "5"))
    }

    @Test
    fun searchReplaceWritingAnEscapedElementImports() {
        // Before the escape was read, \%found1 was filled in from the array before the step and
        // stayed a placeholder. Tasker writes it as text, and now so does this app.
        val action = imported(
            """<code>598</code><Str sr="arg0" ve="3">%text</Str><Str sr="arg1" ve="3">\d+</Str><Int sr="arg2" val="0"/>""" +
                """<Int sr="arg3" val="0"/><Int sr="arg4" val="0"/><Str sr="arg5" ve="3">%found</Str>""" +
                """<Int sr="arg6" val="1"/><Str sr="arg7" ve="3">[\%found1]</Str>""",
        )

        assertEquals("text.replace", action.type)
        assertEquals("[%found1]", store("found1" to "old").expand(action.args.getValue("replacement")))
    }
}
