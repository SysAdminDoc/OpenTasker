package com.opentasker.core.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tasker's For items: values, variables, array parts and number ranges. */
class ForItemsTest {

    private fun variables() = VariableStore().apply {
        set("fruit", "banana")
        set("pair", "x,y")
        set("end", "5")
        set("skip", "2")
        set("arr1", "apple")
        set("arr2", "pear, ripe")
        set("arr3", "plum")
        setArray("parts", listOf("one", "two, three"))
    }

    // The % stage alone; the runner and preview also fill in {{ }} references, which TaskRunnerForValuesTest covers.
    private fun resolve(raw: String, variables: VariableStore = variables()) = ForItems.resolve(raw, variables) { piece ->
        variables.expandTracked(piece, conditionals = false).let { ForItems.Expanded(it.text, it.isSecretDerived) }
    }

    private fun items(raw: String, variables: VariableStore = variables()): List<String> {
        val resolved = resolve(raw, variables)
        assertNull(resolved.problem)
        return resolved.items.toList()
    }

    @Test
    fun writtenOutValuesAndRangesFollowTaskersExamples() {
        assertEquals(listOf("apple", "pear", "banana"), items("apple,pear,banana"))
        assertEquals(listOf("1", "2", "3", "4", "5"), items("1:5"))
        assertEquals(listOf("8", "6", "4", "2"), items("8:2:-2"))
        assertEquals(listOf("4", "3", "2", "1", "0", "100", "0", "2", "4", "6", "8"), items("4:0, 100, 0:8:2"))
        assertEquals(listOf("0", "1", "2", "3", "4", "5", "6", "7", "8", "9", "10"), items("0:10"))
    }

    @Test
    fun aRangesDirectionComesFromItsEnds() {
        assertEquals(listOf("8", "6", "4", "2"), items("8:2:2"))
        assertEquals(listOf("0", "3", "6"), items("0:7:-3"))
        assertEquals(listOf("-2", "-1", "0", "1"), items("-2:1"))
        assertEquals(listOf("7"), items("7:7"))
    }

    @Test
    fun variablesAreFilledInAndThenSplitOnCommas() {
        assertEquals(listOf("banana"), items("%fruit"))
        assertEquals(listOf("x", "y", "z"), items("%pair,z"))
        assertEquals(listOf("1", "3", "5"), items("1:%end:%skip"))
        assertEquals(listOf("2", "4"), items("2:%end:%skip"))
        assertEquals(listOf("1", "2", "3"), items("1:%arr(#)"))
    }

    @Test
    fun aWholeArrayGivesItsElementsAndAPartIsSplitLikeAnyValue() {
        assertEquals(listOf("apple", "pear, ripe", "plum"), items("%arr()"))
        assertEquals(listOf("one", "two, three", "end"), items("%parts(),end"))
        // A slice comes out as text joined with commas, so an element holding one splits, as in Tasker.
        assertEquals(listOf("apple", "pear", "ripe"), items("%arr(1:2)"))
        assertEquals(listOf("start", "apple", "pear, ripe", "plum"), items("start, %arr()"))
    }

    @Test
    fun aCommaInsideParenthesesDoesNotSplitThePiece() {
        assertEquals(listOf("x;y", "c"), items("%pair(replace:,:;),c"))
    }

    @Test
    fun emptyPiecesAreDropped() {
        assertEquals(listOf("a", "b"), items(" a, ,b,"))
        assertEquals(emptyList<String>(), items(""))
    }

    @Test
    fun aWholeArrayThatIsNotSetGivesNothing() {
        // Like a For Each naming the array, so a plain variable with that name doesn't count.
        assertEquals(listOf("a"), items("%missing(), a"))
        assertEquals(emptyList<String>(), items("%fruit()"))
    }

    @Test
    fun anArrayChipIsAWholeArray() {
        assertEquals(listOf("one", "two, three"), items("{{ array.parts }}"))
        assertEquals(listOf("one", "two, three", "x"), items("{{array.parts}}, x"))
    }

    @Test
    fun aCommaInsideTemplateBracesDoesNotSplitThePiece() {
        val pieces = mutableListOf<String>()
        ForItems.resolve("{{ global.L | default:a,b }}, z, %v(split:,)", variables()) { piece ->
            pieces += piece
            ForItems.Expanded(piece, secret = false)
        }

        assertEquals(listOf("{{ global.L | default:a,b }}", "z", "%v(split:,)"), pieces)
    }

    @Test
    fun aStrayBracketOrBraceDoesNotSwallowTheRest() {
        assertEquals(listOf("sad :(", "apple", "pear, ripe", "plum"), items("sad :(, %arr()"))
        assertEquals(listOf("a", "{{", "apple", "pear, ripe", "plum"), items("a, {{, %arr()"))
        assertEquals(listOf("%x(", "apple", "pear, ripe", "plum"), items("%x(, %arr()"))
    }

    @Test
    fun aRangeAtTheVeryEndOfALongIsStillTooLong() {
        val written = resolve("-9223372036854775808:0:6")
        assertTrue(written.problem, written.problem!!.contains("longer than 15 digits"))

        val fromAVariable = resolve("1:%n", variables().apply { set("n", "-9223372036854775808") })
        assertTrue(fromAVariable.problem, fromAVariable.problem!!.contains("longer than 15 digits"))
    }

    @Test
    fun aWholeArrayWithAHyphenInItsNameIsStillWhole() {
        val variables = variables().apply { setArray("my-list", listOf("a, b", "c")) }

        assertEquals(listOf("a, b", "c"), items("%my-list()", variables))
    }

    @Test
    fun rangesTakeSignsAndSpaces() {
        assertEquals(listOf("1", "2", "3"), items("+1:3"))
        assertEquals(listOf("5", "3", "1"), items("5 : 1 : 2"))
        assertEquals(listOf("0010", "1:x"), items("0010, 1:x"))
    }

    @Test
    fun aRangeWithNumbersTooLongToCountIsAProblem() {
        val long = resolve("1:9999999999999999")
        assertTrue(long.problem, long.problem!!.contains("longer than 15 digits"))

        val overflow = resolve("1:99999999999999999999")
        assertTrue(overflow.problem, overflow.problem!!.contains("longer than 15 digits"))
    }

    @Test
    fun manyValuesAndARangeComeOutInOrder() {
        val raw = (1..20_000).joinToString(",") { "v$it" } + ",1:3,%arr(),end"
        val resolved = resolve(raw)

        assertEquals(20_007, resolved.items.size)
        assertEquals(listOf("v1", "v2"), resolved.items.take(2))
        assertEquals("v20000", resolved.items[19_999])
        assertEquals(listOf("1", "2", "3", "apple", "pear, ripe", "plum", "end"), resolved.items.drop(20_000))
        assertEquals(20_007, resolved.items.count())
    }

    @Test
    fun aHugeRangeIsCountedWithoutBeingWrittenOut() {
        val resolved = resolve("1:1000000000,last")

        assertNull(resolved.problem)
        assertEquals(1_000_000_001, resolved.items.size)
        assertEquals("1", resolved.items.first())
        assertEquals("1000000000", resolved.items[999_999_999])
        assertEquals("last", resolved.items.last())
    }

    @Test
    fun aJumpOfZeroOrARangeTooBigToCountIsAProblem() {
        val zero = resolve("1:5:0")
        assertTrue(zero.problem, zero.problem!!.contains("steps of 0"))
        assertTrue(zero.items.isEmpty())

        val huge = resolve("1:999999999999999")
        assertTrue(huge.problem, huge.problem!!.contains("more items than a loop can count"))

        val together = resolve("1:2000000000,1:2000000000")
        assertTrue(together.problem, together.problem!!.contains("more items than a loop can count"))
    }

    @Test
    fun aSecretValueOrArrayMarksTheItemsSensitive() {
        assertFalse(resolve("%fruit,1:3").sensitive)

        val secretValue = variables().apply { set("token", "abc", sensitive = true) }
        assertTrue(resolve("a,%token", secretValue).sensitive)

        val secretArray = variables().apply { setArray("keys", listOf("k1", "k2"), sensitive = true) }
        assertTrue(resolve("%keys()", secretArray).sensitive)
    }
}
