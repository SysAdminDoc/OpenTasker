package com.opentasker.core.contexts

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import java.util.Locale

private const val MILLIS_PER_MINUTE = 60_000L

object CalendarSunContextEvents {
    private val buses = ConcurrentHashMap<String, CalendarSunEventBus>()

    /**
     * Returns one shared, demand-counted stream per application process. The default is used by
     * the Context Inspector and intentionally observes both families; matchers pass their exact
     * event name so unrelated profiles do not start calendar reads or sun wakeups.
     */
    fun events(app: Context, requestedEvent: String? = null): Flow<ContextEvent> =
        busFor(app).events(demandFor(requestedEvent))

    internal fun demandFor(requestedEvent: String?): CalendarSunDemand = when {
        requestedEvent == null || requestedEvent.isBlank() -> CalendarSunDemand.ALL
        requestedEvent.trim().equals("calendar", ignoreCase = true) -> CalendarSunDemand.CALENDAR
        requestedEvent.trim().lowercase(Locale.US) in SUN_EVENTS -> CalendarSunDemand.SUN
        else -> CalendarSunDemand.NONE
    }

    private fun busFor(app: Context): CalendarSunEventBus {
        val application = app.applicationContext ?: app
        return buses.computeIfAbsent(application.packageName) { CalendarSunEventBus(application) }
    }

    private val SUN_EVENTS = setOf("sun_tick", "sunrise", "sunset")

    internal fun buildCalendarEvent(app: Context, nowMs: Long): List<ContextEvent> {
        if (!hasCalendarPermission(app)) {
            return listOf(
                ContextEvent(
                    type = "event",
                    matched = false,
                    metadata = mapOf("event" to "calendar", "state" to "permission_denied"),
                ),
            )
        }
        val instances = runCatching { queryCalendarInstances(app, nowMs) }
            .getOrElse {
                return listOf(
                    ContextEvent(
                        type = "event",
                        matched = false,
                        metadata = mapOf("event" to "calendar", "state" to "query_error"),
                    ),
                )
            }
        return listOf(selectCalendarEvent(instances, nowMs))
    }

    internal fun selectCalendarEvent(
        instances: List<CalendarInstance>,
        nowMs: Long,
        beforeWindowMinutes: Int = DEFAULT_BEFORE_WINDOW_MINUTES,
        zone: ZoneId = ZoneId.systemDefault(),
    ): ContextEvent {
        val relevant = instances
            .map { it.withLocalAllDayBounds(zone) }
            .filter { it.endMs > nowMs && it.availability != "free" }
            .sortedBy { it.beginMs }
        val during = relevant.firstOrNull { it.beginMs <= nowMs && it.endMs > nowMs }
        if (during != null) {
            return ContextEvent(
                type = "event",
                matched = true,
                metadata = during.metadata(
                    state = "during",
                    nowMs = nowMs,
                ),
            )
        }

        val upcoming = relevant.firstOrNull {
            val minutesUntilStart = minutesBetween(nowMs, it.beginMs)
            minutesUntilStart in 0..beforeWindowMinutes
        }
        if (upcoming != null) {
            return ContextEvent(
                type = "event",
                matched = true,
                metadata = upcoming.metadata(
                    state = "upcoming",
                    nowMs = nowMs,
                ),
            )
        }

        return ContextEvent(
            type = "event",
            matched = false,
            metadata = mapOf("event" to "calendar", "state" to "idle"),
        )
    }

    internal fun buildSunTick(nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): ContextEvent {
        val local = Instant.ofEpochMilli(nowMs).atZone(zone)
        return ContextEvent(
            type = "event",
            matched = true,
            metadata = mapOf(
                "event" to "sun_tick",
                "date" to local.toLocalDate().toString(),
                "time" to "%02d:%02d".format(local.hour, local.minute),
                "zone" to zone.id,
            ),
        )
    }

    /**
     * The provider filters on the raw stored bounds, and an all-day row's are midnight UTC, up to
     * a day away from the local day it means. The window is a day wider on each side so that row
     * still comes back, and [selectCalendarEvent] decides against the local bounds.
     */
    internal fun instanceQueryWindow(nowMs: Long): LongRange =
        (nowMs - MILLIS_PER_MINUTE - MILLIS_PER_DAY)..(nowMs + DEFAULT_LOOKAHEAD_MILLIS + MILLIS_PER_DAY)

    private fun queryCalendarInstances(app: Context, nowMs: Long): List<CalendarInstance> {
        val window = instanceQueryWindow(nowMs)
        val uriBuilder = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(uriBuilder, window.first)
        ContentUris.appendId(uriBuilder, window.last)

        app.contentResolver.query(
            uriBuilder.build(),
            CALENDAR_PROJECTION,
            null,
            null,
            "${CalendarContract.Instances.BEGIN} ASC",
        ).use { cursor ->
            if (cursor == null) return emptyList()
            val items = mutableListOf<CalendarInstance>()
            while (cursor.moveToNext()) {
                items += CalendarInstance(
                    calendarName = sanitizeCalendarName(cursor.getString(0)),
                    calendarId = cursor.getLong(1),
                    beginMs = cursor.getLong(2),
                    endMs = cursor.getLong(3),
                    allDay = cursor.getInt(4) == 1,
                    availability = availabilityLabel(cursor.getInt(5)),
                )
            }
            return items
        }
    }

    /**
     * CalendarProvider stores all-day instance BEGIN/END as midnight-UTC epoch millis.
     * Comparing those raw values against local wall-clock time shifts every all-day
     * event by the zone offset (e.g. 19:00 the previous day in UTC-5), so convert the
     * bounds to local-midnight before windowing.
     */
    private fun CalendarInstance.withLocalAllDayBounds(zone: ZoneId): CalendarInstance {
        if (!allDay) return this
        return copy(
            beginMs = beginMs - zoneOffsetMillis(zone, beginMs),
            endMs = endMs - zoneOffsetMillis(zone, endMs),
        )
    }

    private fun zoneOffsetMillis(zone: ZoneId, atMs: Long): Long =
        zone.rules.getOffset(Instant.ofEpochMilli(atMs)).totalSeconds * 1_000L

    private fun CalendarInstance.metadata(state: String, nowMs: Long): Map<String, String> = buildMap {
        put("event", "calendar")
        put("state", state)
        // The producer re-emits a matching event every minute for as long as the window lasts.
        // Without a stable identity PulseEventContinuity treats each minute as a fresh pulse, so a
        // 60-minute meeting ran its enter task ~60 times - once per minute - and the shipped
        // meeting-mode template sets no cooldown to blunt it. Identity is the occurrence plus the
        // state, so "upcoming" and "during" still fire once each.
        put("eventId", "$calendarId:$beginMs:$state")
        put("calendar", calendarName)
        put("calendarId", calendarId.toString())
        put("allDay", allDay.toString())
        put("availability", availability)
        put("minutesUntilStart", minutesBetween(nowMs, beginMs).coerceAtLeast(0).toString())
        put("minutesUntilEnd", minutesBetween(nowMs, endMs).coerceAtLeast(0).toString())
    }

    private fun hasCalendarPermission(app: Context): Boolean =
        ContextCompat.checkSelfPermission(app, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    private fun sanitizeCalendarName(value: String?): String =
        value.orEmpty().trim().take(MAX_CALENDAR_NAME_CHARS).ifBlank { "Calendar" }

    private fun availabilityLabel(value: Int): String = when (value) {
        CalendarContract.Events.AVAILABILITY_FREE -> "free"
        CalendarContract.Events.AVAILABILITY_TENTATIVE -> "tentative"
        else -> "busy"
    }

    private fun minutesBetween(startMs: Long, endMs: Long): Int =
        ((endMs - startMs) / MILLIS_PER_MINUTE).toInt()

    private val CALENDAR_PROJECTION = arrayOf(
        CalendarContract.Instances.CALENDAR_DISPLAY_NAME,
        CalendarContract.Instances.CALENDAR_ID,
        CalendarContract.Instances.BEGIN,
        CalendarContract.Instances.END,
        CalendarContract.Instances.ALL_DAY,
        CalendarContract.Instances.AVAILABILITY,
    )

    private const val MILLIS_PER_DAY = 24L * 60L * MILLIS_PER_MINUTE
    private const val DEFAULT_LOOKAHEAD_MILLIS = MILLIS_PER_DAY
    private const val DEFAULT_BEFORE_WINDOW_MINUTES = 30
    private const val MAX_CALENDAR_NAME_CHARS = 80
}

internal enum class CalendarSunDemand {
    NONE,
    CALENDAR,
    SUN,
    ALL;

    val includesCalendar: Boolean
        get() = this == CALENDAR || this == ALL

    val includesSun: Boolean
        get() = this == SUN || this == ALL
}

/** One producer loop shared by all engine matchers and the visible Context Inspector. */
private class CalendarSunEventBus(
    private val app: Context,
) {
    private val events = MutableSharedFlow<ContextEvent>(extraBufferCapacity = 32)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val demandCounts = mutableMapOf<CalendarSunDemand, Int>()
    private var producerJob: Job? = null

    fun events(demand: CalendarSunDemand): Flow<ContextEvent> = callbackFlow {
        val forwardJob = launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            events.collect { event -> send(event) }
        }
        addDemand(demand)

        awaitClose {
            forwardJob.cancel()
            removeDemand(demand)
        }
    }

    @Synchronized
    private fun addDemand(demand: CalendarSunDemand) {
        demandCounts[demand] = demandCounts.getOrDefault(demand, 0) + 1
        if (demand != CalendarSunDemand.NONE && producerJob == null) {
            producerJob = scope.launch { produce() }
        }
    }

    @Synchronized
    private fun removeDemand(demand: CalendarSunDemand) {
        val remaining = (demandCounts[demand] ?: 0) - 1
        if (remaining > 0) demandCounts[demand] = remaining else demandCounts.remove(demand)
        if (activeDemand() == CalendarSunDemand.NONE) {
            producerJob?.cancel()
            producerJob = null
        }
    }

    @Synchronized
    private fun activeDemand(): CalendarSunDemand {
        val hasCalendar = demandCounts.any { it.key.includesCalendar && it.value > 0 }
        val hasSun = demandCounts.any { it.key.includesSun && it.value > 0 }
        return when {
            hasCalendar && hasSun -> CalendarSunDemand.ALL
            hasCalendar -> CalendarSunDemand.CALENDAR
            hasSun -> CalendarSunDemand.SUN
            else -> CalendarSunDemand.NONE
        }
    }

    private suspend fun produce() {
        var lastMinute = -1L
        var lastDemand = CalendarSunDemand.NONE
        while (currentCoroutineContext().isActive) {
            val now = System.currentTimeMillis()
            val demand = activeDemand()
            if (demand == CalendarSunDemand.NONE) return
            val minute = now / MILLIS_PER_MINUTE
            if (minute != lastMinute || demand != lastDemand) {
                if (demand.includesCalendar) {
                    CalendarSunContextEvents.buildCalendarEvent(app, now).forEach(events::tryEmit)
                }
                if (demand.includesSun) events.tryEmit(CalendarSunContextEvents.buildSunTick(now))
                lastMinute = minute
                lastDemand = demand
            }
            delay(1_000)
        }
    }
}

data class CalendarInstance(
    val calendarName: String,
    val calendarId: Long,
    val beginMs: Long,
    val endMs: Long,
    val allDay: Boolean,
    val availability: String,
)
