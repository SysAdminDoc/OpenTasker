package com.opentasker.core.engine.variables

import com.opentasker.core.engine.VariableStore
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tasker 6.7.8's maths strips `\s` from a value anywhere in it (decompiled `Expr.d`), and Android's
 * `\s` is every Unicode White_Space character, not just ASCII spaces. Characters that look blank but
 * aren't White_Space stay in and keep the value from being a number.
 */
class TaskerMathsUnicodeSpaceTest {
    private fun isFortyTwo(value: String): Boolean =
        VariableStore().apply { set("x", value) }.evaluateCondition("%x > 41 && %x < 43")

    @Test
    fun everyWhiteSpaceCharacterIsStrippedFromInsideANumber() {
        WHITE_SPACE.forEach { code ->
            val space = Char(code).toString()

            assertTrue("%04x inside".format(code), isFortyTwo("4${space}2"))
            assertTrue("%04x around".format(code), isFortyTwo("${space}42$space"))
        }
    }

    @Test
    fun blankLookingCharactersOutsideWhiteSpaceStayIn() {
        listOf(ZERO_WIDTH_SPACE, WORD_JOINER, BYTE_ORDER_MARK).forEach { code ->
            assertFalse("%04x".format(code), isFortyTwo("4${Char(code)}2"))
            assertFalse("%04x".format(code), isFortyTwo("42${Char(code)}"))
        }
    }

    @Test
    fun aControlCharacterIsOnlyTrimmedAtTheEnds() {
        assertTrue(isFortyTwo("42" + Char(UNIT_SEPARATOR)))
        assertFalse(isFortyTwo("4${Char(UNIT_SEPARATOR)}2"))
    }

    private companion object {
        val WHITE_SPACE = (0x09..0x0D) + listOf(0x20, 0x85, 0xA0, 0x1680) + (0x2000..0x200A) +
            listOf(0x2028, 0x2029, 0x202F, 0x205F, 0x3000)
        const val ZERO_WIDTH_SPACE = 0x200B
        const val WORD_JOINER = 0x2060
        const val BYTE_ORDER_MARK = 0xFEFF
        const val UNIT_SEPARATOR = 0x001F
    }
}
