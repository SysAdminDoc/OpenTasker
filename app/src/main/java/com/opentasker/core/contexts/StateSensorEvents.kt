package com.opentasker.core.contexts

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.TetheringInterface
import android.net.TetheringManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.telephony.TelephonyManager
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * FOSS platform bridges for the low-privilege physical state contexts.
 *
 * The state source owns the merged snapshot; this object emits only patches for the requested
 * physical key. The Inspector requests a null key and receives all available patches. Every
 * unavailable capability emits a private `_setup_*` marker, which keeps matching fail-closed
 * while allowing the Inspector to explain that Setup is the next step.
 */
object StateSensorEvents {
    private const val RECHECK_MS = 30_000L
    private const val ACTIVITY_IDLE_MS = 15_000L
    private const val SPEED_MIN_TIME_MS = 15_000L
    private const val SPEED_MIN_DISTANCE_METERS = 5f

    /** Four of the location request's intervals with no fix, checked on the next recheck. */
    private const val SPEED_SETTLE_MS = 4 * SPEED_MIN_TIME_MS

    /** Steps-per-minute granularity published to matchers; see the activity registration. */
    private const val STEP_RATE_BUCKET = 10
    private const val AP_STATE_ACTION = "android.net.wifi.WIFI_AP_STATE_CHANGED"
    private const val AP_STATE_EXTRA = "wifi_state"
    private const val TETHER_STATE_ACTION = "android.net.conn.TETHER_STATE_CHANGED"

    /** `WifiManager.WIFI_AP_STATE_ENABLED`; the constant itself is not public API. */
    private const val WIFI_AP_STATE_ENABLED = 13

    /**
     * Names the platform has used for the "currently tethered interfaces" extra on
     * TETHER_STATE_CHANGED. Neither is public API, so both are read best-effort and an absent or
     * unreadable payload is reported as not tethering rather than assumed active.
     */
    private val TETHERED_INTERFACE_EXTRAS = listOf("tetherArray", "activeArray")

    private fun Intent.hasTetheredInterfaces(): Boolean = TETHERED_INTERFACE_EXTRAS.any { key ->
        runCatching { getStringArrayListExtra(key) }.getOrNull()?.isNotEmpty() == true
    }

    private val physicalKeys = setOf(
        "orientation",
        "proximity",
        "activity",
        "speed",
        "roaming",
        "tethering",
        "call_state",
        BT_NEAR_STATE_KEY,
    )

    fun events(app: Context, requestedStateKey: String? = null): Flow<Map<String, String>> = callbackFlow {
        val requested = requestedStateKey
            ?.let(::normalizeStateKey)
            ?.takeIf(String::isNotBlank)
        val registrations = mutableListOf<RecheckingRegistration>()

        fun wanted(key: String): Boolean = requested == null || requested == key
        fun emitPatch(patch: Map<String, String>) {
            if (patch.isNotEmpty()) trySend(patch)
        }

        if (wanted("orientation")) {
            registrations += orientationRegistration(app, ::emitPatch).also { it.start(this) }
        }
        if (wanted("proximity")) {
            registrations += proximityRegistration(app, ::emitPatch).also { it.start(this) }
        }
        if (wanted("activity")) {
            registrations += activityRegistration(app, ::emitPatch).also { it.start(this) }
        }
        if (wanted("speed")) {
            registrations += speedRegistration(app, ::emitPatch).also { it.start(this) }
        }
        if (wanted("roaming")) {
            registrations += roamingRegistration(app, ::emitPatch).also { it.start(this) }
        }
        if (wanted("call_state")) {
            registrations += callStateRegistration(app, ::emitPatch).also { it.start(this) }
        }
        if (wanted("tethering")) {
            registrations += tetheringRegistration(app, ::emitPatch).also { it.start(this) }
        }
        if (wanted(BT_NEAR_STATE_KEY)) {
            registrations += bluetoothNearRegistration(app, listEverything = requested == null, ::emitPatch)
                .also { it.start(this) }
        }
        if (requested != null && requested !in physicalKeys) {
            // Unknown state keys remain available to the existing broadcast-backed state source;
            // do not claim that an arbitrary imported key needs one of these sensor permissions.
        }

        awaitClose { registrations.forEach(RecheckingRegistration::close) }
    }

    private fun orientationRegistration(
        app: Context,
        emit: (Map<String, String>) -> Unit,
    ): RecheckingRegistration {
        val manager = app.getSystemService(SensorManager::class.java)
        var listener: SensorEventListener? = null
        var lastOrientation: String? = null

        fun stop() {
            listener?.let { manager?.unregisterListener(it) }
            listener = null
        }

        fun ensure() {
            if (manager == null) {
                emitSetup(emit, "orientation", "Open Setup to review orientation sensor support; Android did not expose a sensor service on this device.")
                return
            }
            if (listener != null) return
            val sensor = manager.getDefaultSensor(Sensor.TYPE_GRAVITY)
                ?: manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            if (sensor == null) {
                emitSetup(emit, "orientation", "Open Setup to review orientation sensor support; this device has no gravity or accelerometer sensor.")
                return
            }
            val candidate = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    if (event.values.size < 3) return
                    val orientation = DeviceOrientationClassifier.classify(
                        event.values[0],
                        event.values[1],
                        event.values[2],
                    )
                    if (orientation != lastOrientation) {
                        lastOrientation = orientation
                        emitReady(emit, "orientation", mapOf("orientation" to orientation))
                    }
                }

                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
            }
            if (runCatching { manager.registerListener(candidate, sensor, SensorManager.SENSOR_DELAY_UI) }.getOrDefault(false)) {
                listener = candidate
                emitReady(emit, "orientation")
            } else {
                emitSetup(emit, "orientation", "Open Setup to review orientation sensor support; Android could not register the available sensor.")
            }
        }

        return RecheckingRegistration(::ensure, ::stop, RECHECK_MS)
    }

    private fun proximityRegistration(
        app: Context,
        emit: (Map<String, String>) -> Unit,
    ): RecheckingRegistration {
        val manager = app.getSystemService(SensorManager::class.java)
        var listener: SensorEventListener? = null

        fun stop() {
            listener?.let { manager?.unregisterListener(it) }
            listener = null
        }

        fun ensure() {
            if (manager == null) {
                emitSetup(emit, "proximity", "Open Setup to review proximity sensor support; Android did not expose a sensor service on this device.")
                return
            }
            if (listener != null) return
            val sensor = manager.getDefaultSensor(Sensor.TYPE_PROXIMITY)
            if (sensor == null) {
                emitSetup(emit, "proximity", "Open Setup to review proximity sensor support; this device has no proximity sensor.")
                return
            }
            val candidate = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    val distance = event.values.firstOrNull() ?: return
                    val near = distance < sensor.maximumRange
                    emitReady(
                        emit,
                        "proximity",
                        mapOf(
                            "proximity" to if (near) "near" else "far",
                            "proximity_distance_cm" to distance.toDouble().trimForState(),
                        ),
                    )
                }

                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
            }
            if (runCatching { manager.registerListener(candidate, sensor, SensorManager.SENSOR_DELAY_UI) }.getOrDefault(false)) {
                listener = candidate
                emitReady(emit, "proximity")
            } else {
                emitSetup(emit, "proximity", "Open Setup to review proximity sensor support; Android could not register the available sensor.")
            }
        }

        return RecheckingRegistration(::ensure, ::stop, RECHECK_MS)
    }

    private fun activityRegistration(
        app: Context,
        emit: (Map<String, String>) -> Unit,
    ): RecheckingRegistration {
        val manager = app.getSystemService(SensorManager::class.java)
        var listener: SensorEventListener? = null
        var lastMotionAt = 0L
        val readings = ActivityReadings(STEP_RATE_BUCKET)
        val stepTimes = ArrayDeque<Long>()

        fun emitActivity(activity: String, stepsPerMinute: Int? = null, measured: Boolean = true) {
            if (activity == "walking" || activity == "running") lastMotionAt = System.currentTimeMillis()
            readings.next(activity, stepsPerMinute, measured)?.let { emitValues(emit, "activity", it, measured) }
        }

        fun stop() {
            listener?.let { manager?.unregisterListener(it) }
            listener = null
            stepTimes.clear()
        }

        fun ensure() {
            if (Build.VERSION.SDK_INT >= 29 && !hasPermission(app, Manifest.permission.ACTIVITY_RECOGNITION)) {
                stop()
                emitSetup(emit, "activity", "Open Setup and grant Physical activity permission before using Activity context values.")
                return
            }
            if (manager == null) {
                emitSetup(emit, "activity", "Open Setup to review activity sensor support; Android did not expose a sensor service on this device.")
                return
            }
            if (listener != null) {
                // No motion for that long while listening is a measured "stationary", which also
                // replaces the stand-in below when a step detector never saw a step.
                if (lastMotionAt > 0L && System.currentTimeMillis() - lastMotionAt > ACTIVITY_IDLE_MS) {
                    emitActivity("stationary", 0)
                }
                return
            }
            val stepSensor = manager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
            val fallbackSensor = manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            val sensor = stepSensor ?: fallbackSensor
            if (sensor == null) {
                emitSetup(emit, "activity", "Open Setup to review activity sensor support; this device has no step detector or accelerometer.")
                return
            }
            lastMotionAt = System.currentTimeMillis()
            // Nothing has been sensed yet, so this is a stand-in until the sensor or the idle check reports.
            emitActivity("stationary", 0, measured = false)
            val candidate = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    val now = System.currentTimeMillis()
                    if (sensor.type == Sensor.TYPE_STEP_DETECTOR) {
                        stepTimes.addLast(now)
                        while (stepTimes.firstOrNull()?.let { now - it > 60_000L } == true) stepTimes.removeFirst()
                        val stepsPerMinute = stepTimes.size
                        emitActivity(MotionActivityClassifier.fromStepRate(stepsPerMinute), stepsPerMinute)
                    } else if (event.values.size >= 3) {
                        emitActivity(MotionActivityClassifier.fromAcceleration(event.values[0], event.values[1], event.values[2]))
                    }
                }

                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
            }
            if (!runCatching { manager.registerListener(candidate, sensor, SensorManager.SENSOR_DELAY_UI) }.getOrDefault(false)) {
                emitSetup(emit, "activity", "Open Setup to review activity sensor support; Android could not register the available sensor.")
                return
            }
            listener = candidate
            emitReady(emit, "activity")
        }

        return RecheckingRegistration(::ensure, ::stop, RECHECK_MS)
    }

    @SuppressLint("MissingPermission")
    private fun speedRegistration(
        app: Context,
        emit: (Map<String, String>) -> Unit,
    ): RecheckingRegistration {
        val locationManager = app.getSystemService(LocationManager::class.java)
        val registration = LocationListenerRegistrationState { registeredListener ->
            runCatching { locationManager?.removeUpdates(registeredListener) }
        }
        val readings = SpeedReadings(freshForMs = SPEED_MIN_TIME_MS, settleAfterMs = SPEED_SETTLE_MS)

        fun stop() {
            registration.stop()
            readings.reset()
        }

        // Emitted under the readings' lock, so a settled 0 can't land after a newer live fix.
        fun emitLocation(location: Location, lastKnown: Boolean = false) {
            synchronized(readings) {
                val sample = location.toSpeedSample()
                val provider = location.provider ?: "unknown"
                val accuracyMps = if (location.hasSpeedAccuracy()) location.speedAccuracyMetersPerSecond.toDouble() else null
                val nowMs = System.currentTimeMillis()
                val reading = if (lastKnown) {
                    readings.onLastKnownFix(sample, provider, accuracyMps, nowMs)
                } else {
                    readings.onFix(sample, provider, accuracyMps, nowMs)
                }
                reading?.let { (values, measured) -> emitValues(emit, "speed", values, measured) }
            }
        }

        fun ensure() {
            if (!hasLocationPermission(app)) {
                stop()
                emitSetup(emit, "speed", "Open Setup and grant Foreground location permission before using Speed context values.")
                return
            }
            if (locationManager == null) {
                emitSetup(emit, "speed", "Open Setup to review speed context support; Android did not expose a location service on this device.")
                return
            }
            val providers = enabledProviders(locationManager).toSet()
            if (providers.isEmpty()) {
                stop()
                emitSetup(emit, "speed", "Open Setup and enable a GPS or network location provider before using Speed context values.")
                return
            }
            if (registration.isActiveFor(providers)) {
                synchronized(readings) {
                    readings.settle(System.currentTimeMillis())?.let { emitValues(emit, "speed", it, measured = true) }
                }
                return
            }
            readings.forgetPrevious()
            val candidate = object : LocationListener {
                override fun onLocationChanged(location: Location) = emitLocation(location)
                override fun onProviderEnabled(provider: String) = Unit
                override fun onProviderDisabled(provider: String) = Unit

                @Deprecated("Deprecated in Android framework")
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
            }
            try {
                registration.replaceProviders(providers, candidate) { provider, registeredListener ->
                    locationManager.requestLocationUpdates(
                        provider,
                        SPEED_MIN_TIME_MS,
                        SPEED_MIN_DISTANCE_METERS,
                        registeredListener,
                        Looper.getMainLooper(),
                    )
                }
            } catch (error: SecurityException) {
                stop()
                emitSetup(emit, "speed", "Open Setup and grant Foreground location permission before using Speed context values.")
                return
            } catch (_: RuntimeException) {
                stop()
                emitSetup(emit, "speed", "Open Setup to review speed context support; Android could not register a location provider.")
                return
            }
            providers.mapNotNull { provider -> runCatching { locationManager.getLastKnownLocation(provider) }.getOrNull() }
                .maxByOrNull(Location::getTime)
                ?.let { emitLocation(it, lastKnown = true) }
            emitReady(emit, "speed")
        }

        return RecheckingRegistration(::ensure, ::stop, RECHECK_MS)
    }

    @SuppressLint("MissingPermission")
    private fun roamingRegistration(
        app: Context,
        emit: (Map<String, String>) -> Unit,
    ): RecheckingRegistration {
        val manager = app.getSystemService(TelephonyManager::class.java)
        var receiver: BroadcastReceiver? = null

        fun stop() {
            receiver?.let { runCatching { app.unregisterReceiver(it) } }
            receiver = null
        }

        fun read() {
            if (!hasPermission(app, Manifest.permission.READ_PHONE_STATE)) return
            val value = runCatching { manager?.isNetworkRoaming }.getOrNull()
            if (value == null) {
                emitSetup(emit, "roaming", "Open Setup to grant Phone permission; this device did not expose network roaming state.")
            } else {
                emitReady(emit, "roaming", mapOf("roaming" to value.toString()))
            }
        }

        fun ensure() {
            if (!hasPermission(app, Manifest.permission.READ_PHONE_STATE)) {
                stop()
                emitSetup(emit, "roaming", "Open Setup and grant Phone permission before using Roaming context values.")
                return
            }
            if (manager == null || manager.phoneType == TelephonyManager.PHONE_TYPE_NONE) {
                stop()
                emitSetup(emit, "roaming", "Open Setup to review phone-state support; this device has no telephony service.")
                return
            }
            if (receiver == null) {
                val candidate = object : BroadcastReceiver() {
                    override fun onReceive(context: Context?, intent: Intent?) = read()
                }
                runCatching {
                    ContextCompat.registerReceiver(
                        app,
                        candidate,
                        IntentFilter().apply {
                            addAction(TelephonyManager.ACTION_PHONE_STATE_CHANGED)
                        },
                        ContextCompat.RECEIVER_NOT_EXPORTED,
                    )
                }.onSuccess { receiver = candidate }
                    .onFailure { emitSetup(emit, "roaming", "Open Setup and grant Phone permission before using Roaming context values.") }
            }
            read()
        }

        return RecheckingRegistration(::ensure, ::stop, RECHECK_MS)
    }

    @SuppressLint("MissingPermission")
    private fun callStateRegistration(
        app: Context,
        emit: (Map<String, String>) -> Unit,
    ): RecheckingRegistration {
        val manager = app.getSystemService(TelephonyManager::class.java)
        var receiver: BroadcastReceiver? = null

        fun stop() {
            receiver?.let { runCatching { app.unregisterReceiver(it) } }
            receiver = null
        }

        fun emitState(state: String) = emitReady(emit, "call_state", mapOf("call_state" to state))

        fun read() {
            if (!hasPermission(app, Manifest.permission.READ_PHONE_STATE)) return
            val state = runCatching { manager?.callState }.getOrNull()?.let(::callStateName)
            if (state == null) {
                emitSetup(emit, "call_state", "Open Setup to grant Phone permission; this device did not expose call state.")
            } else {
                emitState(state)
            }
        }

        fun ensure() {
            if (!hasPermission(app, Manifest.permission.READ_PHONE_STATE)) {
                stop()
                emitSetup(emit, "call_state", "Open Setup and grant Phone permission before using Phone call state values.")
                return
            }
            if (manager == null || manager.phoneType == TelephonyManager.PHONE_TYPE_NONE) {
                stop()
                emitSetup(emit, "call_state", "Open Setup to review phone-state support; this device has no telephony service.")
                return
            }
            if (receiver == null) {
                val candidate = object : BroadcastReceiver() {
                    override fun onReceive(context: Context?, intent: Intent?) {
                        val state = intent?.getStringExtra(TelephonyManager.EXTRA_STATE)
                        if (state != null) {
                            emitState(
                                when (state) {
                                    TelephonyManager.EXTRA_STATE_RINGING -> "ringing"
                                    TelephonyManager.EXTRA_STATE_OFFHOOK -> "offhook"
                                    else -> "idle"
                                },
                            )
                        } else {
                            read()
                        }
                    }
                }
                runCatching {
                    ContextCompat.registerReceiver(
                        app,
                        candidate,
                        IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED),
                        ContextCompat.RECEIVER_NOT_EXPORTED,
                    )
                }.onSuccess { receiver = candidate }
                    .onFailure { emitSetup(emit, "call_state", "Open Setup and grant Phone permission before using Phone call state values.") }
            }
            read()
        }

        return RecheckingRegistration(::ensure, ::stop, RECHECK_MS)
    }

    private fun bluetoothNearRegistration(
        app: Context,
        listEverything: Boolean,
        emit: (Map<String, String>) -> Unit,
    ): RecheckingRegistration {
        val scanner = NearbyBluetoothScanner(
            app,
            targets = { if (listEverything) null else BluetoothNearTargets.current },
            // A device on the list was seen. One left off it only counts as away once the scanner
            // has looked for long enough, so until then the list is a stand-in for the rest.
            publish = { devices, absenceSettled ->
                emitValues(emit, BT_NEAR_STATE_KEY, mapOf(BT_NEAR_STATE_KEY to devices), measured = absenceSettled)
            },
        )

        fun ensure() {
            val gap = scanner.setupGap()
            if (gap == null) {
                scanner.scanOnce()
                return
            }
            // Nothing can be seen now, so a device from the last scan must not keep a profile active.
            scanner.stop()
            emit(mapOf(BT_NEAR_STATE_KEY to "", standInMarkerKey(BT_NEAR_STATE_KEY) to ""))
            emitSetup(emit, BT_NEAR_STATE_KEY, gap)
        }

        return RecheckingRegistration(::ensure, scanner::stop, BT_NEAR_CYCLE_MS)
    }

    private fun tetheringRegistration(
        app: Context,
        emit: (Map<String, String>) -> Unit,
    ): RecheckingRegistration {
        var callback: TetheringManager.TetheringEventCallback? = null
        var receiver: BroadcastReceiver? = null
        var readings: TetheringReadings? = null
        var manager: TetheringManager? = null

        // Under the lock, so a stand-in or a settle can't land after a broadcast that came first.
        fun publish(current: TetheringReadings, measured: Boolean, read: TetheringReadings.() -> Map<String, String>?) {
            synchronized(current) { current.read()?.let { emitValues(emit, "tethering", it, measured) } }
        }

        fun stop() {
            if (Build.VERSION.SDK_INT >= 36) {
                val currentManager = manager
                val currentCallback = callback
                if (currentManager != null && currentCallback != null) {
                    runCatching { currentManager.unregisterTetheringEventCallback(currentCallback) }
                }
                callback = null
                manager = null
            }
            receiver?.let { runCatching { app.unregisterReceiver(it) } }
            receiver = null
            readings = null
        }

        fun ensure() {
            if (Build.VERSION.SDK_INT >= 36) {
                ensureApi36(app, emit, { current -> callback = current }, { current -> manager = current }, callback, manager)
                return
            }
            if (receiver != null) {
                readings?.let { current -> publish(current, measured = true) { settle() } }
                return
            }
            // Wi-Fi hotspot and cable/Bluetooth tethering arrive on two different broadcasts, so
            // hold both and report their union. TETHER_STATE_CHANGED fires on stop as well as
            // start, and it can be replayed stickily on registration - treating any delivery as
            // "tethering on" left the state stuck on after tethering had already stopped.
            val current = TetheringReadings()
            val candidate = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    when (intent?.action) {
                        AP_STATE_ACTION -> {
                            val state = intent.getIntExtra(AP_STATE_EXTRA, -1)
                            if (state < 0) return
                            publish(current, measured = true) { apState(state == WIFI_AP_STATE_ENABLED) }
                        }
                        TETHER_STATE_ACTION -> {
                            val tethered = intent.hasTetheredInterfaces()
                            publish(current, measured = true) { tetheredInterfaces(tethered) }
                        }
                    }
                }
            }
            runCatching {
                ContextCompat.registerReceiver(
                    app,
                    candidate,
                    IntentFilter().apply {
                        addAction(AP_STATE_ACTION)
                        addAction(TETHER_STATE_ACTION)
                    },
                    // On Android 11 and 12 the tethering module sends its broadcast from outside
                    // the system server.
                    systemAppReceiverFlags(),
                )
            }.onSuccess {
                receiver = candidate
                readings = current
                // Publish a value immediately, so the Inspector shows one. Reporting the source
                // ready with no value claimed it was healthy while showing nothing. It's a stand-in
                // until a sticky or later broadcast reports, or the next recheck settles it.
                publish(current, measured = false) { standIn() }
            }.onFailure {
                emitSetup(emit, "tethering", "Open Setup to review tethering support; Android could not register its tethering state callback.")
            }
        }

        return RecheckingRegistration(::ensure, ::stop, RECHECK_MS)
    }

    @RequiresApi(36)
    private fun ensureApi36(
        app: Context,
        emit: (Map<String, String>) -> Unit,
        setCallback: (TetheringManager.TetheringEventCallback) -> Unit,
        setManager: (TetheringManager) -> Unit,
        currentCallback: TetheringManager.TetheringEventCallback?,
        currentManager: TetheringManager?,
    ) {
        if (currentCallback != null) return
        val tetheringManager = app.getSystemService(TetheringManager::class.java)
        if (tetheringManager == null) {
            emitSetup(emit, "tethering", "Open Setup to review tethering support; Android did not expose a tethering service.")
            return
        }
        val candidate = object : TetheringManager.TetheringEventCallback {
            override fun onTetheredInterfacesChanged(interfaces: Set<TetheringInterface>) {
                emitReady(emit, "tethering", mapOf("tethering" to interfaces.isNotEmpty().toString()))
            }
        }
        runCatching {
            tetheringManager.registerTetheringEventCallback(ContextCompat.getMainExecutor(app), candidate)
        }.onSuccess {
            setManager(tetheringManager)
            setCallback(candidate)
            emitReady(emit, "tethering")
        }.onFailure {
            emitSetup(emit, "tethering", "Open Setup to review tethering support; Android could not register its tethering state callback.")
        }
    }

    private fun emitSetup(emit: (Map<String, String>) -> Unit, key: String, detail: String) {
        emit(mapOf("_setup_$key" to detail))
    }

    private fun emitReady(
        emit: (Map<String, String>) -> Unit,
        key: String,
        values: Map<String, String> = emptyMap(),
    ) {
        emit(values + ("_setup_$key" to ""))
    }

    /**
     * [values] for [key] from a source that can publish a stand-in before it has measured anything,
     * marked with [standInMarkerKey] until a [measured] value replaces it.
     */
    private fun emitValues(
        emit: (Map<String, String>) -> Unit,
        key: String,
        values: Map<String, String>,
        measured: Boolean,
    ) {
        emitReady(emit, key, values + (standInMarkerKey(key) to if (measured) "" else "1"))
    }

    private fun hasPermission(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private fun hasLocationPermission(context: Context): Boolean =
        hasPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ||
            hasPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)

    @SuppressLint("MissingPermission")
    private fun enabledProviders(manager: LocationManager): List<String> =
        listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .filter { provider -> runCatching { manager.isProviderEnabled(provider) }.getOrDefault(false) }

    @SuppressLint("MissingPermission")
    private fun Location.toSpeedSample(): SpeedSample = SpeedSample(
        latitude = latitude,
        longitude = longitude,
        observedAtMs = time.takeIf { it > 0L } ?: System.currentTimeMillis(),
        speedMps = if (hasSpeed()) speed.toDouble() else null,
    )

    private fun callStateName(state: Int): String = when (state) {
        TelephonyManager.CALL_STATE_RINGING -> "ringing"
        TelephonyManager.CALL_STATE_OFFHOOK -> "offhook"
        else -> "idle"
    }
}

private fun Double.trimForState(): String =
    if (isFinite()) "%.3f".format(java.util.Locale.US, this).trimEnd('0').trimEnd('.') else "0"

private class RecheckingRegistration(
    private val ensure: () -> Unit,
    private val stop: () -> Unit,
    private val intervalMs: Long,
) {
    private var job: Job? = null

    fun start(scope: CoroutineScope) {
        job = scope.launch {
            while (isActive) {
                runCatching(ensure)
                delay(intervalMs)
            }
        }
    }

    fun close() {
        job?.cancel()
        job = null
        runCatching(stop)
    }
}

internal object DeviceOrientationClassifier {
    /**
     * An accelerometer at rest reads +g along whichever device axis currently points upward - which
     * is why a face-up device reads z = +9.81. The same convention fixes the other two axes: held
     * upright the top edge is up, so y = +9.81 is portrait, and with the right edge up x = +9.81 is
     * landscape-left (the device rotated a quarter turn counter-clockwise).
     */
    fun classify(x: Float, y: Float, z: Float): String {
        val ax = abs(x)
        val ay = abs(y)
        val az = abs(z)
        return when {
            az >= ax && az >= ay -> if (z >= 0f) "face_up" else "face_down"
            ay >= ax -> if (y >= 0f) "portrait" else "portrait_upside_down"
            else -> if (x >= 0f) "landscape_left" else "landscape_right"
        }
    }
}

internal object MotionActivityClassifier {
    fun fromStepRate(stepsPerMinute: Int): String = when {
        stepsPerMinute >= 110 -> "running"
        stepsPerMinute >= 10 -> "walking"
        else -> "stationary"
    }

    fun fromAcceleration(x: Float, y: Float, z: Float): String {
        val magnitudeG = sqrt(x.toDouble().pow(2) + y.toDouble().pow(2) + z.toDouble().pow(2)) / SensorManager.GRAVITY_EARTH
        val movement = abs(magnitudeG - 1.0)
        return when {
            movement >= 0.45 -> "running"
            movement >= 0.12 -> "walking"
            else -> "stationary"
        }
    }
}

/** What the activity source publishes next, if anything. */
internal class ActivityReadings(private val stepRateBucket: Int) {
    private var lastActivity: String? = null
    private var lastStepBucket: Int? = null
    private var lastWasStandIn = false

    /**
     * The values for [activity], or null when they'd repeat the last ones. A measured value replaces
     * a stand-in even when it reads the same.
     */
    @Synchronized
    fun next(activity: String, stepsPerMinute: Int?, measured: Boolean): Map<String, String>? {
        // With a step detector the cadence changes on almost every step, which bypassed the dedupe
        // and re-evaluated every profile roughly twice a second while walking. Publish a bucketed
        // rate so only a meaningful change reaches the matcher.
        val bucket = stepsPerMinute?.let { (it / stepRateBucket) * stepRateBucket }
        if (activity == lastActivity && bucket == lastStepBucket && !(lastWasStandIn && measured)) return null
        lastActivity = activity
        lastStepBucket = bucket
        lastWasStandIn = !measured
        return buildMap {
            put("activity", activity)
            bucket?.let { put("activity_steps_per_minute", it.toString()) }
        }
    }
}

internal data class SpeedSample(
    val latitude: Double,
    val longitude: Double,
    val observedAtMs: Long,
    val speedMps: Double? = null,
)

/**
 * The speed source's values for each fix, telling a measured speed from the 0 it publishes when
 * nothing measured one: a fix with no speed of its own and no earlier fix to work one out from, or a
 * last-known fix more than [freshForMs] old. Once a fix has come in live, the location request
 * delivers another each time the device moves 5 m, so a live stand-in followed by nothing for
 * [settleAfterMs] means the device is standing still, and [settle] reports 0 as measured. A
 * last-known stand-in never settles, since a cold GPS sends nothing for a while even when moving,
 * and it leaves a live stand-in's quiet spell running.
 */
internal class SpeedReadings(private val freshForMs: Long, private val settleAfterMs: Long) {
    private var previous: SpeedSample? = null
    private var standInProvider: String? = null
    private var standInSinceMs = 0L
    private var heardLive = false

    /** The values for [current] from [provider], and whether they were measured. */
    @Synchronized
    fun onFix(
        current: SpeedSample,
        provider: String,
        accuracyMps: Double?,
        nowMs: Long,
        lastKnown: Boolean = false,
    ): Pair<Map<String, String>, Boolean> {
        if (!lastKnown) heardLive = true
        val stale = lastKnown && nowMs - current.observedAtMs >= freshForMs
        val measuredSpeed = if (stale) null else current.speedMps ?: SpeedCalculator.between(previous, current)
        // An old fix says where the device was, not where it starts from.
        previous = current.takeUnless { stale }
        val measured = measuredSpeed != null
        if (measured || !lastKnown) {
            standInProvider = provider.takeUnless { measured }
            standInSinceMs = nowMs
        }
        val values = speedValues(measuredSpeed ?: 0.0, provider) +
            (accuracyMps?.takeIf { measured }?.let { mapOf("speed_accuracy_mps" to it.trimForState()) } ?: emptyMap())
        return values to measured
    }

    /**
     * [onFix] for the last-known fix read after registering, or null when a live fix got in first:
     * Android can deliver one before the last-known fix is read, and it's the newer of the two.
     */
    @Synchronized
    fun onLastKnownFix(
        current: SpeedSample,
        provider: String,
        accuracyMps: Double?,
        nowMs: Long,
    ): Pair<Map<String, String>, Boolean>? =
        if (heardLive) null else onFix(current, provider, accuracyMps, nowMs, lastKnown = true)

    /** A measured 0 once a live stand-in has gone [settleAfterMs] with no fix after it, else null. */
    @Synchronized
    fun settle(nowMs: Long): Map<String, String>? {
        val provider = standInProvider ?: return null
        if (nowMs - standInSinceMs < settleAfterMs) return null
        standInProvider = null
        return speedValues(0.0, provider)
    }

    /** Starts over from the next fix, as a source does once it registers again. */
    @Synchronized
    fun forgetPrevious() {
        previous = null
        heardLive = false
    }

    @Synchronized
    fun reset() {
        previous = null
        standInProvider = null
        heardLive = false
    }

    private fun speedValues(speedMps: Double, provider: String): Map<String, String> {
        val speed = speedMps.coerceAtLeast(0.0)
        return mapOf(
            "speed" to speed.trimForState(),
            "speed_kmh" to (speed * 3.6).trimForState(),
            "speed_provider" to provider,
        )
    }
}

/**
 * The tethering value below Android 16: the union of the hotspot and tether broadcasts. Until one
 * of them arrives the source shows "false" as a stand-in. Both broadcasts are sticky, so a receiver
 * that has heard neither by the next recheck means nothing is shared, and [settle] reports "false"
 * as measured.
 */
internal class TetheringReadings {
    private var apEnabled = false
    private var interfacesTethered = false
    private var heard = false

    /** The stand-in to show once the receiver is registered, or null when a broadcast already came. */
    @Synchronized
    fun standIn(): Map<String, String>? = values().takeUnless { heard }

    @Synchronized
    fun apState(enabled: Boolean): Map<String, String> {
        apEnabled = enabled
        heard = true
        return values()
    }

    @Synchronized
    fun tetheredInterfaces(tethered: Boolean): Map<String, String> {
        interfacesTethered = tethered
        heard = true
        return values()
    }

    /** The stand-in again as measured, once, if no broadcast has come; else null. */
    @Synchronized
    fun settle(): Map<String, String>? {
        if (heard) return null
        heard = true
        return values()
    }

    private fun values(): Map<String, String> = mapOf("tethering" to (apEnabled || interfacesTethered).toString())
}

internal object SpeedCalculator {
    fun between(previous: SpeedSample?, current: SpeedSample): Double? {
        previous ?: return null
        val elapsedSeconds = (current.observedAtMs - previous.observedAtMs) / 1_000.0
        if (elapsedSeconds <= 0.0) return null
        return haversineMeters(previous.latitude, previous.longitude, current.latitude, current.longitude) / elapsedSeconds
    }

    private fun haversineMeters(latitude1: Double, longitude1: Double, latitude2: Double, longitude2: Double): Double {
        val earthRadiusMeters = 6_371_000.0
        val latDelta = Math.toRadians(latitude2 - latitude1)
        val lonDelta = Math.toRadians(longitude2 - longitude1)
        val a = sin(latDelta / 2).pow(2) +
            cos(Math.toRadians(latitude1)) * cos(Math.toRadians(latitude2)) * sin(lonDelta / 2).pow(2)
        return earthRadiusMeters * 2 * atan2(sqrt(a), sqrt(1 - a))
    }
}
