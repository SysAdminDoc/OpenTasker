package com.opentasker.core.contexts

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.tan

object SunEventCalculator {
    /** Where the sun's centre sits at an ordinary sunrise or sunset, allowing for refraction and its radius. */
    const val OFFICIAL_ELEVATION_DEGREES = -0.833

    /** Twilight ends when the sun's centre is 6, 12 or 18 degrees below the horizon. */
    val NAMED_ELEVATIONS = mapOf("civil" to -6.0, "nautical" to -12.0, "astronomical" to -18.0)

    /**
     * The sun elevation a sun context's `elevation` setting names, in degrees above the horizon.
     * Blank is the ordinary sunrise or sunset; otherwise a twilight name or a number in -90..90.
     * Null means the setting can't be read, so the context never fires.
     */
    fun elevationDegrees(raw: String?): Double? {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return OFFICIAL_ELEVATION_DEGREES
        return NAMED_ELEVATIONS[value.lowercase(Locale.US)]
            ?: value.toDoubleOrNull()?.takeIf { it in -90.0..90.0 }
    }

    /**
     * The local minute the sun rises past, or sets below, [elevationDegrees] on [date]. Null when
     * it never crosses that elevation there that day, as with civil dusk in a polar summer.
     */
    fun eventMinuteOfDay(
        date: LocalDate,
        latitude: Double,
        longitude: Double,
        event: String,
        zone: ZoneId = ZoneId.systemDefault(),
        elevationDegrees: Double = OFFICIAL_ELEVATION_DEGREES,
    ): Int? = eventLocalDateTime(date, latitude, longitude, event, zone, elevationDegrees)
        ?.let { it.hour * MINUTES_PER_HOUR + it.minute }

    /**
     * When the sun rises past, or sets below, [elevationDegrees] for [date]'s almanac day, as a
     * local time in [zone]. That's usually on [date], but a late dusk far west in its time zone
     * lands after midnight on the next date, and an early sunrise far east can fall on the date
     * before. Null when it never crosses that elevation there that day.
     */
    fun eventLocalDateTime(
        date: LocalDate,
        latitude: Double,
        longitude: Double,
        event: String,
        zone: ZoneId = ZoneId.systemDefault(),
        elevationDegrees: Double = OFFICIAL_ELEVATION_DEGREES,
    ): LocalDateTime? {
        if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return null
        val sunrise = event.equals("sunrise", ignoreCase = true)
        val sunset = event.equals("sunset", ignoreCase = true)
        if (!sunrise && !sunset) return null

        val day = date.dayOfYear.toDouble()
        val lngHour = longitude / 15.0
        val approximateTime = day + ((if (sunrise) 6.0 else 18.0) - lngHour) / 24.0
        val meanAnomaly = 0.9856 * approximateTime - 3.289
        val trueLongitude = normalizeDegrees(
            meanAnomaly +
                1.916 * sinDeg(meanAnomaly) +
                0.020 * sinDeg(2.0 * meanAnomaly) +
                282.634,
        )
        var rightAscension = normalizeDegrees(radToDeg(atan(0.91764 * tanDeg(trueLongitude))))
        val longitudeQuadrant = (trueLongitude / 90.0).toInt() * 90.0
        val ascensionQuadrant = (rightAscension / 90.0).toInt() * 90.0
        rightAscension = (rightAscension + longitudeQuadrant - ascensionQuadrant) / 15.0

        val sinDec = 0.39782 * sinDeg(trueLongitude)
        val cosDec = cos(asin(sinDec))
        // The almanac writes this with the zenith, 90 degrees minus the elevation, so cos(zenith) is sin(elevation).
        val cosHour = (sinDeg(elevationDegrees) - sinDec * sinDeg(latitude)) / (cosDec * cosDeg(latitude))
        if (cosHour > 1.0 || cosHour < -1.0) return null

        val hourAngle = if (sunrise) {
            360.0 - radToDeg(acos(cosHour))
        } else {
            radToDeg(acos(cosHour))
        } / 15.0
        val localMeanTime = hourAngle + rightAscension - 0.06571 * approximateTime - 6.622
        // The almanac answers in hours of the day, so a dusk just past midnight comes out as 00:02
        // and would read as that same morning. The crossing is always within 12 hours of the 06:00
        // or 18:00 local mean time the almanac started from, which pins it to the right day.
        val nominalUtcHour = (if (sunrise) 6.0 else 18.0) - lngHour
        val utcHour = nominalUtcHour + normalizeHours(localMeanTime - lngHour - nominalUtcHour + 12.0) - 12.0
        return date.atStartOfDay(ZoneOffset.UTC)
            .plusMinutes((utcHour * MINUTES_PER_HOUR).roundToLong())
            .withZoneSameInstant(zone)
            .toLocalDateTime()
    }

    private fun normalizeDegrees(value: Double): Double = ((value % 360.0) + 360.0) % 360.0
    private fun normalizeHours(value: Double): Double = ((value % 24.0) + 24.0) % 24.0
    private fun sinDeg(value: Double): Double = sin(Math.toRadians(value))
    private fun cosDeg(value: Double): Double = cos(Math.toRadians(value))
    private fun tanDeg(value: Double): Double = tan(Math.toRadians(value))
    private fun radToDeg(value: Double): Double = Math.toDegrees(value)

    private const val MINUTES_PER_HOUR = 60
}
