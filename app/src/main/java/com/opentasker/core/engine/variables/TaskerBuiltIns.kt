package com.opentasker.core.engine.variables

import com.opentasker.core.engine.ActiveExecutionRegistry
import com.opentasker.core.engine.AutomationLiveConditionState
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.time.Clock
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Tasker's built-in variables, read-only values like `%TIME` and `%BATT` that Tasker works out
 * when a task reads them. Tasker won't let a task or its Variables tab make a variable with one of
 * these names, so imported tasks only ever read them. Here a value stored under the name is still
 * what a read gets (that's how it worked before these existed), and Preflight names the clash.
 */
object TaskerBuiltIns {
    /** Every built-in Tasker 6.7 knows, without the `%`. */
    val NAMES: Set<String> = setOf(
        "AIR", "AIRR", "BATT", "BLUE", "CALS", "CALTITLE", "CALDESCR", "CALLOC", "CDATE", "CNAME", "CNUM", "CTIME",
        "CODATE", "CODUR", "CONAME", "CONUM", "COTIME", "CELLID", "CELLSIG", "CELLSRV", "CLIP", "CPUFREQ", "CPUGOV",
        "DATE", "DAYM", "DAYW", "DEVID", "DEVMAN", "DEVMOD", "DEVPROD", "DEVTID", "BRIGHT", "DTOUT", "EFROM", "ECC",
        "ESUBJ", "EDATE", "ETIME", "MEMF", "GPS", "HEART", "HTTPR", "HTTPD", "HTTPL", "HUMIDITY", "IMETHOD",
        "INTERRUPT", "KEYG", "LAPP", "FOTO", "LIGHT", "LOC", "LOCACC", "LOCALT", "LOCSPD", "LOCTMS", "LOCN",
        "LOCNACC", "LOCNTMS", "MFIELD", "MTRACK", "MUTED", "NIGHT", "NTITLE", "PNUM", "PRESSURE", "PACTIVE",
        "PENABLED", "ROAM", "ROOT", "SCREEN", "SDK", "SILENT", "SIMNUM", "SIMSTATE", "SPHONE", "SPEECH", "TRUN",
        "TNET", "SMSRF", "SMSRN", "SMSRB", "SMSRD", "MMSRS", "SMSRT", "TEMP", "TETHER", "TIME", "TIMEMS", "TIMES",
        "UIMODE", "UPS", "VOICE", "VOLA", "VOLC", "VOLD", "VOLM", "VOLN", "VOLR", "VOLS", "WIFII", "WIFI", "WIMAX",
        "WIN",
    )

    private val CLOCK_NAMES = setOf("TIME", "TIMES", "TIMEMS", "DATE", "DAYW", "DAYM")
    private val DEVICE_NAMES = setOf(
        "BATT", "BRIGHT", "DEVID", "DEVMAN", "DEVMOD", "DEVPROD", "DTOUT", "LOC", "LOCN", "SCREEN", "SDK", "UPS",
        "VOLA", "VOLC", "VOLD", "VOLM", "VOLN", "VOLR", "VOLS",
    )
    private val ENGINE_NAMES = setOf("PACTIVE", "PENABLED", "TRUN")

    /**
     * The built-ins this app fills in. The device ones need [installDevice] first, and the profile
     * lists need [watchProfiles].
     */
    val SUPPORTED: Set<String> = CLOCK_NAMES + DEVICE_NAMES + ENGINE_NAMES

    private val REFERENCE = Regex("%([A-Za-z][A-Za-z0-9_]*)")

    @Volatile
    internal var clockForTests: Clock? = null

    @Volatile
    internal var localeForTests: Locale? = null

    @Volatile
    private var device: ((String) -> String?)? = null

    @Volatile
    private var watchedProfiles: Map<Long, String> = emptyMap()

    /** [read] answers a device built-in by name, or null when the device can't say. */
    fun installDevice(read: (String) -> String?) {
        device = read
    }

    /** The enabled profiles the engine watches now, names by id, which `%PENABLED` and `%PACTIVE` list. */
    fun watchProfiles(names: Map<Long, String>) {
        watchedProfiles = names
    }

    internal fun resetForTests() {
        clockForTests = null
        localeForTests = null
        device = null
        watchedProfiles = emptyMap()
    }

    fun isBuiltIn(name: String): Boolean = name in NAMES

    /**
     * The built-in's value right now, or null when [name] isn't one this app fills in or the device
     * can't say. Names are case-sensitive, as in Tasker.
     */
    fun value(name: String): String? = when (name) {
        in CLOCK_NAMES -> clockValue(name)
        in DEVICE_NAMES -> runCatching { device?.invoke(name) }.getOrNull()
        in ENGINE_NAMES -> engineValue(name)
        else -> null
    }

    /** The built-ins [text] reads that this app doesn't fill in, in the order they first show up. */
    fun unsupportedReads(text: String): List<String> =
        REFERENCE.findAll(text).map { it.groupValues[1] }.filter { it in NAMES && it !in SUPPORTED }.distinct().toList()

    private fun engineValue(name: String): String? = when (name) {
        // Tasker sorts these by when each profile was made, and ids here count up the same way.
        "PENABLED" -> taskerList(watchedProfiles.toSortedMap().values)
        "PACTIVE" -> watchedProfiles.let { names -> taskerList(AutomationLiveConditionState.activeProfileIds().mapNotNull(names::get)) }
        else -> taskerList(ActiveExecutionRegistry.active.value.map { it.taskName })
    }

    /**
     * Tasker's list form, `,a,b,` with each name once, or null when there's none, since Tasker
     * leaves the variable unset then.
     */
    private fun taskerList(names: Collection<String>): String? =
        names.filter { it.isNotBlank() }.distinct().takeIf { it.isNotEmpty() }?.joinToString(",", prefix = ",", postfix = ",")

    private fun clockValue(name: String): String {
        val clock = clockForTests ?: Clock.systemDefaultZone()
        val locale = localeForTests ?: Locale.getDefault()
        val zone = TimeZone.getTimeZone(clock.zone)
        val millis = clock.millis()
        val calendar = Calendar.getInstance(zone, locale).apply { timeInMillis = millis }
        return when (name) {
            // Tasker pads both: 09.05.
            "TIME" -> String.format(Locale.ROOT, "%02d.%02d", calendar.get(Calendar.HOUR_OF_DAY), calendar.get(Calendar.MINUTE))
            "TIMES" -> (millis / 1000).toString()
            "TIMEMS" -> millis.toString()
            // The locale's short date with its slashes made dashes, 7-4-25 in the US.
            "DATE" -> DateFormat.getDateInstance(DateFormat.SHORT, locale).apply { timeZone = zone }.format(Date(millis)).replace('/', '-')
            "DAYW" -> SimpleDateFormat("EEEE", locale).apply { timeZone = zone }.format(Date(millis))
            else -> calendar.get(Calendar.DAY_OF_MONTH).toString()
        }
    }
}
