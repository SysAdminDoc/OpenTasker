package com.opentasker.core.data

import android.content.ContextWrapper
import com.opentasker.core.actions.VarConvertAction
import com.opentasker.core.engine.ActionContext
import com.opentasker.core.engine.ActionRegistry
import com.opentasker.core.engine.ActionResult
import com.opentasker.core.engine.VariableStore
import com.opentasker.core.registerCoreRuntime
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class VariableConvertTest {
    private val utc = TimeZone.getTimeZone("UTC")

    @Before
    fun setUp() {
        registerCoreRuntime()
    }

    private fun convert(function: String, value: String, urlSafe: Boolean = false, locale: Locale = Locale.US): String? =
        VariableConvert.convert(function, value, htmlToText = { "html:$it" }, urlSafe = urlSafe, locale = locale, zone = utc)

    private fun parse(text: String, locale: Locale = Locale.US): Long? = VariableConvert.parseDateTime(text, locale, utc)?.div(1000)

    // Written this way so no escape sequence ever sits in the source as typed.
    private fun u(hex: String) = "\\" + "u" + hex

    @Test
    fun byteSizesRoundTheWayTaskersDo() {
        assertEquals("4", convert("bytes_to_kilobytes", "5000"))
        assertNull(convert("bytes_to_kilobytes", " 5000"))
        assertEquals("1.5", convert("bytes_to_megabytes", "1572864"))
        assertEquals("1", convert("bytes_to_megabytes", "1100000"))
        assertEquals("10", convert("bytes_to_megabytes", "11010048"))
        assertEquals("0.5", convert("bytes_to_megabytes", "524288"))
        assertEquals("1.5", convert("bytes_to_gigabytes", "1610612736"))
        assertEquals("15", convert("bytes_to_gigabytes", "16106127360"))
        assertEquals("15.5", convert("bytes_to_gigabytes", "16642998272"))
        assertNull(convert("bytes_to_megabytes", "lots"))
    }

    @Test
    fun unitsUseTaskersFactors() {
        assertEquals("212.0", convert("celsius_to_fahrenheit", " 100 "))
        assertEquals("100.0", convert("fahrenheit_to_celsius", "212"))
        assertEquals("4.0", convert("centimetres_to_inches", "1e1"))
        mapOf(
            "centimetres_to_inches" to "0.4", "inches_to_centimetres" to "2.5",
            "metres_to_feet" to "3.28083", "feet_to_metres" to "0.3048",
            "kilograms_to_pounds" to "2.2046226218", "pounds_to_kilograms" to "0.45359237",
            "kilometres_to_miles" to "0.621371192", "miles_to_kilometres" to "1.60934",
        ).forEach { (function, expected) -> assertEquals(function, expected, convert(function, "1")) }
        assertNull(convert("metres_to_feet", "tall"))
    }

    @Test
    fun textEncodingsMatchJavas() {
        assertEquals("a b c", convert("url_decode", "a%20b+c"))
        assertNull(convert("url_decode", "%zz"))
        assertEquals("a+b%26c%3Dd%2F%7E", convert("url_encode", "a b&c=d/~"))
        assertEquals("11", convert("binary_to_decimal", "1011"))
        assertNull(convert("binary_to_decimal", "102"))
        assertEquals("1011", convert("decimal_to_binary", "11"))
        assertEquals("255", convert("hex_to_decimal", "ff"))
        assertEquals("255", convert("hex_to_decimal", "FF"))
        assertEquals("ff", convert("decimal_to_hex", "255"))
        assertEquals("aGVsbG8/Pg==", convert("base64_encode", "hello?>"))
        assertEquals("hello?>", convert("base64_decode", "aGVsbG8_Pg", urlSafe = true))
        assertEquals("hello", convert("base64_decode", "aGVs\nbG8="))
        assertEquals("5d41402abc4b2a76b9719d911017c592", convert("md5_digest", "hello"))
        assertEquals("aaf4c61ddcc5e8a2dabede0f3b482cd9aea9434d", convert("sha1_digest", "hello"))
        assertEquals("html:<b>hi</b>", convert("html_to_text", "<b>hi</b>"))
    }

    @Test
    fun caseFunctionsUseTheLocale() {
        assertEquals("front door", convert("lower_case", "Front DOOR"))
        assertEquals("FRONT DOOR", convert("upper_case", "Front door"))
        assertEquals("Hello world", convert("upper_case_first", "hello world"))
        assertEquals("", convert("upper_case_first", ""))
    }

    @Test
    fun jsonEncodeEscapesWhatGsonEscapes() {
        val input = "a\"b\\c<d>'e\n\t=&" + Char(0x2028) + Char(1)
        val expected = "a\\\"b\\\\c" + u("003c") + "d" + u("003e") + u("0027") + "e\\n\\t" +
            u("003d") + u("0026") + u("2028") + u("0001")
        assertEquals(expected, convert("json_encode", input))
    }

    @Test
    fun secondsFormatAsTaskersThreeDateStyles() {
        assertEquals("1-1-70 00.00", convert("seconds_to_date_time", "0"))
        assertEquals("11-14-23 22.13", convert("seconds_to_date_time", "1700000000"))
        assertEquals("Nov 14, 2023 22:13", convert("seconds_to_medium_date_time", "1700000000"))
        assertEquals("Tuesday, November 14, 2023 10:13PM", convert("seconds_to_long_date_time", "1700000000"))
        assertNull(convert("seconds_to_date_time", "soon"))
    }

    @Test
    fun secondsTooBigForMillisecondsFailInsteadOfWrappingToAWrongDate() {
        val tooBig = (Long.MAX_VALUE / 1000 + 1).toString()
        listOf("seconds_to_date_time", "seconds_to_medium_date_time", "seconds_to_long_date_time").forEach {
            assertNull(it, convert(it, tooBig))
            assertNull(it, convert(it, "-$tooBig"))
        }
        assertEquals("Nov 14, 2023 22:13", convert("seconds_to_medium_date_time", "1700000000"))
    }

    @Test
    fun dateTimesParseInTheLocalesOrder() {
        assertEquals(1700000000L, parse("20231114 22.13.20"))
        assertEquals(1700000000L, parse("11/14/23 10:13:20 pm"))
        assertEquals(1700000000L, parse("14/11/23 22.13.20", Locale.UK))
        assertEquals(1700000000L, parse("2023-11-14 22:13:20", Locale.UK))
        assertEquals(1699920000L, parse("20231114"))
        assertEquals(1699921800L, parse("20231114 12.30 am"))
        assertEquals(1699965000L, parse("20231114 12.30 pm"))
        assertEquals(1700003580L, parse("20231114 22.73"))
        listOf("1:2:3:4", "tomorrow", "20231114 10.00 xm", "20231114 25.00").forEach { assertNull(it, parse(it)) }
        assertEquals("1700000000", convert("date_time_to_seconds", "20231114 22.13.20"))
    }

    @Test
    fun dateOrderFollowsTheLocalesShortDate() {
        assertEquals("Mdy", VariableConvert.dateOrder(Locale.US))
        assertEquals("dMy", VariableConvert.dateOrder(Locale.UK))
        assertEquals("yMd", VariableConvert.dateOrder(Locale.JAPAN))
        assertEquals("dMy", VariableConvert.dateOrder(Locale.GERMANY))
    }

    private fun act(variables: VariableStore, vararg args: Pair<String, String>): ActionResult =
        runBlocking { ActionRegistry.get("var.convert")!!.run(ActionContext(ContextWrapper(null), variables), mapOf(*args)) }

    @Test
    fun actionReplacesTheValueOrStoresItElsewhere() {
        val variables = VariableStore().apply { set("size", "1572864") }
        assertEquals(ActionResult.Success, act(variables, "name" to "%size", "function" to "bytes_to_kilobytes", "var" to "%kb"))
        assertEquals("1536", variables.get("kb"))
        assertEquals("1572864", variables.get("size"))
        assertEquals(ActionResult.Success, act(variables, "name" to "size", "function" to "bytes_to_megabytes"))
        assertEquals("1.5", variables.get("size"))
    }

    @Test
    fun actionKeepsASecretSecret() {
        val variables = VariableStore().apply { set("token", "hello", sensitive = true) }
        assertEquals(ActionResult.Success, act(variables, "name" to "token", "function" to "base64_encode", "var" to "encoded"))
        assertEquals("aGVsbG8=", variables.get("encoded"))
        assertTrue(variables.isSensitive("encoded"))
    }

    @Test
    fun actionFailsWithoutTouchingAnything() {
        val variables = VariableStore().apply { set("size", "big") }
        val unset = act(variables, "name" to "missing", "function" to "upper_case", "var" to "out")
        assertTrue(unset is ActionResult.Failure && "%missing" in unset.message)
        val bad = act(variables, "name" to "size", "function" to "bytes_to_kilobytes", "var" to "out")
        assertTrue(bad is ActionResult.Failure && "bytes_to_kilobytes" in bad.message)
        val unknown = act(variables, "name" to "size", "function" to "rot13")
        assertTrue(unknown is ActionResult.Failure && "rot13" in unknown.message)
        assertTrue(act(variables, "name" to "1 2", "function" to "upper_case") is ActionResult.Failure)
        assertNull(variables.get("out"))
        assertEquals("big", variables.get("size"))
    }

    @Test
    fun actionUsesTheHtmlReaderItIsGiven() {
        val variables = VariableStore().apply { set("page", "<p>Hi &amp; bye</p>") }
        val result = runBlocking {
            VarConvertAction(htmlToText = { it.removePrefix("<p>").removeSuffix("</p>").replace("&amp;", "&") })
                .run(ActionContext(ContextWrapper(null), variables), mapOf("name" to "page", "function" to "html_to_text"))
        }
        assertEquals(ActionResult.Success, result)
        assertEquals("Hi & bye", variables.get("page"))
    }
}
