package com.opentasker.core.contexts

import java.util.Calendar
import java.util.GregorianCalendar
import java.util.SimpleTimeZone
import java.util.TimeZone
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tan

/**
 * Tasker's Get Sunrise/Sunset Times (action 473), worked the way Tasker 6.7.8 works it so an
 * imported profile turns on at the minute it would in Tasker.
 *
 * The times are NOAA's solar calculation spreadsheet, worked once for local noon of the day, and
 * every time is cut down to its whole minute. That isn't [SunEventCalculator]'s formula, so the two
 * can be a minute apart. Day or night is a separate, older formula (the one Android's night light
 * used), which counts the hours between its own -6 degree dawn and dusk as day.
 */
object TaskerSunTimes {
    data class Times(
        val sunrise: Long,
        val sunset: Long,
        val solarNoon: Long,
        val civilDawn: Long,
        val civilDusk: Long,
        val nauticalDawn: Long,
        val nauticalDusk: Long,
        val astronomicalDawn: Long,
        val astronomicalDusk: Long,
        /** One per custom elevation, in the order they were given. */
        val customMorning: List<Long>,
        val customEvening: List<Long>,
        val isDay: Boolean,
        val sunlightMinutes: Int,
    )

    /**
     * The times for the day [epochMs] falls on in [zone], as epoch seconds. [customElevations] are
     * sun elevations in degrees, negative below the horizon, the way Tasker's field takes them.
     */
    fun calculate(
        latitude: Double,
        longitude: Double,
        epochMs: Long,
        zone: TimeZone,
        customElevations: List<Double> = emptyList(),
    ): Times {
        val offsetHours = zone.getOffset(epochMs) / 1000.0 / 60.0 / 60.0
        val julianDay = spreadsheetDay(epochMs, zone) + 2415018.5 + NOON - offsetHours / 24.0
        val century = (julianDay - 2451545.0) / 36525.0
        val meanLongitude = mod((((3.032E-4 * century) + 36000.76983) * century) + 280.46646, 360.0)
        val meanAnomaly = ((35999.05029 - (1.537E-4 * century)) * century) + 357.52911
        val eccentricity = 0.016708634 - (((1.267E-7 * century) + 4.2037E-5) * century)
        val anomalyRadians = Math.toRadians(meanAnomaly)
        val center = (sin(anomalyRadians) * (1.914602 - (((1.4E-5 * century) + 0.004817) * century))) +
            (sin(2.0 * anomalyRadians) * (0.019993 - (1.01E-4 * century))) +
            (sin(anomalyRadians * 3.0) * 2.89E-4)
        val trueLongitude = meanLongitude + center
        val omega = 125.04 - (1934.136 * century)
        val apparentLongitude = (trueLongitude - 0.00569) - (sin(Math.toRadians(omega)) * 0.00478)
        val meanObliquity = ((((21.448 - (((century * (5.9E-4 - (0.001813 * century))) + 46.815) * century)) / 60.0) + 26.0) / 60.0) + 23.0
        val obliquity = meanObliquity + (cos(Math.toRadians(omega)) * 0.00256)
        val declination = Math.toDegrees(asin(sin(Math.toRadians(obliquity)) * sin(Math.toRadians(apparentLongitude))))
        val y = tan(Math.toRadians(obliquity / 2.0)).pow(2.0)
        val longitudeRadians = Math.toRadians(meanLongitude)
        val equationOfTime = Math.toDegrees(
            ((((sin(2.0 * longitudeRadians) * y) - ((2.0 * eccentricity) * sin(anomalyRadians))) +
                ((((4.0 * eccentricity) * y) * sin(anomalyRadians)) * cos(2.0 * longitudeRadians))) -
                (((0.5 * y) * y) * sin(4.0 * longitudeRadians))) -
                (((1.25 * eccentricity) * eccentricity) * sin(2.0 * anomalyRadians)),
        ) * 4.0
        val solarNoon = (((720.0 - (longitude * 4.0)) - equationOfTime) + (60.0 * offsetHours)) / 1440.0

        fun crossing(zenith: Double) = Crossing(zenith, solarNoon, latitude, declination)
        val sun = crossing(SUNRISE_ZENITH)
        val civil = crossing(CIVIL_ZENITH)
        val nautical = crossing(NAUTICAL_ZENITH)
        val astronomical = crossing(ASTRONOMICAL_ZENITH)
        val custom = customElevations.map { crossing(90.0 - it) }

        fun at(fraction: Double) = clockMinute(fraction, epochMs, offsetHours) / 1000
        return Times(
            sunrise = at(sun.morning),
            sunset = at(sun.evening),
            solarNoon = at(solarNoon),
            civilDawn = at(civil.morning),
            civilDusk = at(civil.evening),
            nauticalDawn = at(nautical.morning),
            nauticalDusk = at(nautical.evening),
            astronomicalDawn = at(astronomical.morning),
            astronomicalDusk = at(astronomical.evening),
            customMorning = custom.map { at(it.morning) },
            customEvening = custom.map { at(it.evening) },
            isDay = isDay(epochMs, latitude, longitude),
            sunlightMinutes = (sun.hourAngle * 8.0).roundToInt(),
        )
    }

    /** Where the sun crosses [zenith] degrees from straight up, as fractions of the local day. */
    private class Crossing(zenith: Double, noon: Double, latitude: Double, declination: Double) {
        val hourAngle: Double
        val morning: Double
        val evening: Double

        init {
            val latitudeRadians = Math.toRadians(latitude)
            val declinationRadians = Math.toRadians(declination)
            val cosine = (cos(Math.toRadians(zenith)) / (cos(latitudeRadians) * cos(declinationRadians))) -
                (tan(latitudeRadians) * tan(declinationRadians))
            // Past the poles' limits the sun never crosses: a whole day around noon, or none.
            hourAngle = when {
                cosine < -1.0 -> 180.0
                cosine > 1.0 -> 0.0
                else -> Math.toDegrees(acos(cosine))
            }
            val half = (4.0 * hourAngle) / 1440.0
            morning = noon - half
            evening = noon + half
        }
    }

    /**
     * The day number NOAA's spreadsheet uses (Excel's, which counts a 29 February 1900 that never
     * was), counted the way Tasker counts it: whole days between two local midnights. While the
     * clock is further ahead than the zone had it in 1900, summer time included, that comes out a
     * day short and Tasker works the sun out for the day before, so this does too. The times still
     * land on the right day, a minute or so off the true ones.
     */
    private fun spreadsheetDay(epochMs: Long, zone: TimeZone): Double {
        fun calendar() = GregorianCalendar(zone)
        val midnight = calendar().apply {
            timeInMillis = epochMs
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        fun dayIn1900(month: Int, day: Int) = calendar().apply {
            set(1900, month, day, 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val days = (midnight.timeInMillis - dayIn1900(Calendar.JANUARY, 1).timeInMillis) / DAY_MS
        return (if (midnight.after(dayIn1900(Calendar.FEBRUARY, 28))) days + 2 else days + 1).toDouble()
    }

    /** [fraction] of the day [epochMs] falls on, as a clock time cut down to its minute. */
    private fun clockMinute(fraction: Double, epochMs: Long, offsetHours: Double): Long =
        GregorianCalendar(SimpleTimeZone((offsetHours * 3_600_000.0).toInt(), "Solar")).apply {
            timeInMillis = epochMs
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            add(Calendar.MINUTE, (fraction * 1440.0).toInt())
        }.timeInMillis

    /**
     * Whether [epochMs] falls between dawn and dusk by the older formula, worked in single
     * precision where Tasker's is. A sun that never sets counts as day and one that never rises as
     * night.
     *
     * The days since 2000 keep their fraction, as in Android's TwilightCalculator. Tasker 6.7.8
     * drops it, which picks the day before's dawn and dusk for part of every day, so it calls noon
     * in Sydney night, and 7 AM on a June morning in New York.
     */
    private fun isDay(epochMs: Long, latitude: Double, longitude: Double): Boolean {
        val days = (epochMs - J2000_MS).toFloat() / DAY_MS
        val anomaly = (0.01720197f * days) + 6.24006f
        val anomalyDouble = anomaly.toDouble()
        val eclipticLongitude = (0.0334196f.toDouble() * sin(anomalyDouble)) + anomalyDouble +
            (3.49066E-4f.toDouble() * sin((2 * anomaly).toDouble())) +
            (5.236E-6f.toDouble() * sin((3 * anomaly).toDouble())) + 1.796593063 + Math.PI
        val westward = -longitude / 360.0
        val transit = (Math.round((days - 9.0E-4f).toDouble() - westward) + 9.0E-4f).toDouble() + westward +
            (sin(anomalyDouble) * 0.0053) + (sin(2.0 * eclipticLongitude) * -0.0069)
        val declination = asin(sin(eclipticLongitude) * sin(OBLIQUITY_RADIANS))
        val latitudeRadians = 0.017453292f.toDouble() * latitude
        val cosine = (sin(TWILIGHT_RADIANS) - (sin(latitudeRadians) * sin(declination))) /
            (cos(latitudeRadians) * cos(declination))
        if (cosine >= 1.0) return false
        if (cosine <= -1.0) return true
        val half = (acos(cosine) / (2.0 * Math.PI)).toFloat().toDouble()
        val dusk = Math.round((transit + half) * DAY_MS.toDouble()) + J2000_MS
        val dawn = Math.round((transit - half) * DAY_MS.toDouble()) + J2000_MS
        return dawn < epochMs && dusk > epochMs
    }

    private fun mod(value: Double, divisor: Double): Double {
        val rest = value % divisor
        return if (rest < 0.0) rest + divisor else rest
    }

    private const val NOON = 0.5
    private const val SUNRISE_ZENITH = 90.833
    private const val CIVIL_ZENITH = 96.0
    private const val NAUTICAL_ZENITH = 102.0
    private const val ASTRONOMICAL_ZENITH = 108.0
    private const val DAY_MS = 86_400_000L
    private const val J2000_MS = 946_728_000_000L
    private const val OBLIQUITY_RADIANS = 0.4092797040939331
    private const val TWILIGHT_RADIANS = -0.10471975803375244
}
