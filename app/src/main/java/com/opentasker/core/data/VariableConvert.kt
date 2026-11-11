package com.opentasker.core.data

import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.time.chrono.IsoChronology
import java.time.format.DateTimeFormatterBuilder
import java.time.format.FormatStyle
import java.util.Base64
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * Tasker's Variable Convert, one function per entry of [FUNCTIONS] in Tasker's own order, so an
 * export's function number is an index here. Every function returns null when the value doesn't
 * fit it, which the action reports as a failed conversion the way Tasker does.
 */
object VariableConvert {
    val FUNCTIONS = listOf(
        "bytes_to_kilobytes", "bytes_to_megabytes", "bytes_to_gigabytes",
        "date_time_to_seconds", "seconds_to_date_time", "seconds_to_medium_date_time", "seconds_to_long_date_time",
        "html_to_text",
        "celsius_to_fahrenheit", "fahrenheit_to_celsius",
        "centimetres_to_inches", "inches_to_centimetres", "metres_to_feet", "feet_to_metres",
        "kilograms_to_pounds", "pounds_to_kilograms", "kilometres_to_miles", "miles_to_kilometres",
        "url_decode", "url_encode",
        "binary_to_decimal", "decimal_to_binary", "hex_to_decimal", "decimal_to_hex",
        "base64_encode", "base64_decode", "md5_digest", "sha1_digest",
        "lower_case", "upper_case", "upper_case_first", "json_encode",
    )

    // Tasker's own factors, rounding and all (an inch is 2.5 cm to it).
    private val FACTORS = mapOf(
        "centimetres_to_inches" to 0.4, "inches_to_centimetres" to 2.5,
        "metres_to_feet" to 3.28083, "feet_to_metres" to 0.3048,
        "kilograms_to_pounds" to 2.2046226218, "pounds_to_kilograms" to 0.45359237,
        "kilometres_to_miles" to 0.621371192, "miles_to_kilometres" to 1.60934,
    )

    /**
     * Converts [value] with [function]. [htmlToText] is Android's own HTML reader, passed in so
     * the rest runs anywhere. [urlSafe] only matters to Base64 Decode.
     */
    fun convert(
        function: String,
        value: String,
        htmlToText: (String) -> String,
        urlSafe: Boolean = false,
        locale: Locale = Locale.getDefault(),
        zone: TimeZone = TimeZone.getDefault(),
    ): String? = runCatching {
        when (function) {
            "bytes_to_kilobytes" -> value.longOrNull()?.let { (it / 1024).toString() }
            "bytes_to_megabytes" -> value.doubleOrNull()?.let { humanReadable(it / 1_048_576.0, wholeAbove = 10.0) }
            "bytes_to_gigabytes" -> value.doubleOrNull()?.let { humanReadable(it / 1_073_741_824.0, wholeAbove = null) }
            "date_time_to_seconds" -> parseDateTime(value, locale, zone)?.let { (it / 1000).toString() }
            "seconds_to_date_time" -> value.epochMillisOrNull()?.let { millis ->
                val date = DateFormat.getDateInstance(DateFormat.SHORT, locale).apply { timeZone = zone }
                "${date.format(millis).replace('/', '-')} ${pattern("HH.mm", locale, zone).format(millis)}"
            }
            // Android reads the k in Tasker's "kk:mm" as H, so midnight is 00 here too.
            "seconds_to_medium_date_time" -> value.epochMillisOrNull()?.let { pattern("MMM dd, yyyy HH:mm", locale, zone).format(it) }
            "seconds_to_long_date_time" -> value.epochMillisOrNull()?.let { pattern("EEEE, MMMM dd, yyyy hh:mmaa", locale, zone).format(it) }
            "html_to_text" -> htmlToText(value)
            "celsius_to_fahrenheit" -> value.doubleOrNull()?.let { (it * 9.0 / 5.0 + 32.0).toString() }
            "fahrenheit_to_celsius" -> value.doubleOrNull()?.let { ((it - 32.0) * 5.0 / 9.0).toString() }
            in FACTORS -> value.doubleOrNull()?.let { (it * FACTORS.getValue(function)).toString() }
            "url_decode" -> URLDecoder.decode(value, "UTF-8")
            "url_encode" -> URLEncoder.encode(value, "UTF-8")
            "binary_to_decimal" -> java.lang.Long.parseLong(value, 2).toString()
            "decimal_to_binary" -> java.lang.Long.toBinaryString(java.lang.Long.parseLong(value, 10))
            "hex_to_decimal" -> java.lang.Long.parseLong(value, 16).toString()
            "decimal_to_hex" -> java.lang.Long.toHexString(java.lang.Long.parseLong(value, 10))
            "base64_encode" -> Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8))
            "base64_decode" -> String(base64Decode(value, urlSafe), Charsets.UTF_8)
            "md5_digest" -> digest("MD5", value)
            "sha1_digest" -> digest("SHA-1", value)
            "lower_case" -> value.lowercase(locale)
            "upper_case" -> value.uppercase(locale)
            "upper_case_first" -> value.take(1).uppercase(locale) + value.drop(1)
            "json_encode" -> jsonEncode(value)
            else -> null
        }
    }.getOrNull()

    // Tasker reads numbers with Java's own parsers: a whole number takes no spaces, while a
    // decimal may carry them, an exponent or a d suffix.
    private fun String.longOrNull(): Long? = runCatching { java.lang.Long.parseLong(this) }.getOrNull()
    private fun String.doubleOrNull(): Double? = runCatching { java.lang.Double.parseDouble(this) }.getOrNull()

    /** Seconds as epoch milliseconds, or null when that overflows rather than wrapping to a wrong date. */
    private fun String.epochMillisOrNull(): Long? = longOrNull()?.let { runCatching { Math.multiplyExact(it, 1000L) }.getOrNull() }

    /** Tasker keeps one decimal only when it isn't 0, and none at all above [wholeAbove]. */
    private fun humanReadable(amount: Double, wholeAbove: Double?): String {
        val text = String.format(Locale.US, "%f", amount)
        val point = text.indexOf('.')
        if (point == -1) return text
        val whole = (wholeAbove != null && amount > wholeAbove) || text.getOrNull(point + 1) == '0'
        return if (whole) text.substring(0, point) else text.substring(0, point + 2)
    }

    private fun pattern(pattern: String, locale: Locale, zone: TimeZone) =
        SimpleDateFormat(pattern, locale).apply { timeZone = zone }

    /**
     * Tasker's date-time reader: a date, then optionally a time and am or pm, split by single
     * white space. The date is yyyyMMdd or three numbers in [locale]'s date order (a first one of
     * four digits is always the year), the time two or three numbers. Out-of-range fields roll
     * over the way Tasker's lenient calendar rolls them. Returns epoch milliseconds.
     */
    fun parseDateTime(text: String, locale: Locale, zone: TimeZone): Long? {
        val parts = text.trim().split(Regex("""\s"""))
        if (parts.size !in 1..3) return null
        val date = dateFields(parts[0], locale) ?: return null
        val time = if (parts.size == 1) listOf(0, 0, 0) else timeFields(parts[1], parts.getOrNull(2)) ?: return null
        return Calendar.getInstance(zone, locale).apply {
            isLenient = true
            clear()
            set(date[0], date[1] - 1, date[2], time[0], time[1], time[2])
        }.timeInMillis
    }

    /** Year, month and day, in that order. */
    private fun dateFields(text: String, locale: Locale): List<Int>? {
        if (text.matches(Regex("[0-9]{8}"))) {
            return listOf(text.substring(0, 4), text.substring(4, 6), text.substring(6, 8)).map(String::toInt)
        }
        val fields = text.split(Regex("[/_.:-]"))
        if (fields.size != 3) return null
        val order = if (fields[0].length == 4) "yMd" else dateOrder(locale)
        val numbers = fields.map { it.toIntOrNull() ?: return null }
        val year = numbers[order.indexOf('y')].let { if (it < 100) it + 2000 else it }
        return listOf(year, numbers[order.indexOf('M')], numbers[order.indexOf('d')])
    }

    /** The order of day, month and year in [locale]'s short date, such as "Mdy" in the US. */
    fun dateOrder(locale: Locale): String {
        val pattern = DateTimeFormatterBuilder.getLocalizedDateTimePattern(FormatStyle.SHORT, null, IsoChronology.INSTANCE, locale)
        val order = pattern.replace(Regex("'[^']*'"), "").filter { it == 'd' || it == 'M' || it == 'y' }.toList().distinct().joinToString("")
        return order.takeIf { it.length == 3 } ?: "yMd"
    }

    // Tasker leaves 12 am at noon. Here it's midnight, which is what anyone writing it means.
    private fun timeFields(text: String, amPm: String?): List<Int>? {
        val fields = text.split(Regex("[.:-]"))
        if (fields.size !in 2..3) return null
        val numbers = fields.map { it.toIntOrNull() ?: return null }
        val hour = when (amPm?.trim()?.lowercase(Locale.ROOT)) {
            null -> numbers[0]
            "am" -> if (numbers[0] == 12) 0 else numbers[0]
            "pm" -> if (numbers[0] == 12) 12 else numbers[0] + 12
            else -> return null
        }
        if (hour !in 0..24) return null
        return listOf(hour, numbers[1], numbers.getOrElse(2) { 0 })
    }

    // Android's decoder skips anything outside the alphabet rather than failing, and the URL-safe
    // alphabet trades + and / for - and _.
    private fun base64Decode(text: String, urlSafe: Boolean): ByteArray {
        val alphabet = if (urlSafe) URL_SAFE_ALPHABET else STANDARD_ALPHABET
        val kept = text.takeWhile { it != '=' }.filter { it in alphabet }
        val standard = if (urlSafe) kept.replace('-', '+').replace('_', '/') else kept
        return Base64.getDecoder().decode(standard + "=".repeat((4 - standard.length % 4) % 4))
    }

    private const val STANDARD_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    private const val URL_SAFE_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    private fun digest(algorithm: String, text: String): String =
        MessageDigest.getInstance(algorithm).digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    /**
     * Text as it sits inside a JSON string, the way Gson writes it for Tasker: quotes, backslashes
     * and control characters escaped, plus the HTML-sensitive < > & = and ' and the two Unicode
     * line separators as unicode escapes.
     */
    private fun jsonEncode(text: String): String = buildString {
        text.forEach { c ->
            when (c.code) {
                '"'.code -> append("\\\"")
                '\\'.code -> append("\\\\")
                '\t'.code -> append("\\t")
                '\b'.code -> append("\\b")
                '\n'.code -> append("\\n")
                '\r'.code -> append("\\r")
                FORM_FEED -> append("\\f")
                '<'.code, '>'.code, '&'.code, '='.code, '\''.code, LINE_SEPARATOR, PARAGRAPH_SEPARATOR -> append(unicodeEscape(c))
                else -> if (c < ' ') append(unicodeEscape(c)) else append(c)
            }
        }
    }

    private const val FORM_FEED = 0x0C
    private const val LINE_SEPARATOR = 0x2028
    private const val PARAGRAPH_SEPARATOR = 0x2029

    private fun unicodeEscape(c: Char): String = "\\" + "u" + String.format(Locale.ROOT, "%04x", c.code)
}
