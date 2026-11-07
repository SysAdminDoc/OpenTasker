package com.opentasker.core.contexts

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.core.location.LocationManagerCompat
import com.opentasker.core.logging.AppLogger

internal const val BT_NEAR_STATE_KEY = "bt_near"
internal const val BT_NEAR_CYCLE_MS = 60_000L
private const val BT_NEAR_SCAN_MS = 12_000L

/** Long enough to outlast a gap between classic scans, so a device doesn't flap between them. */
private const val BT_NEAR_FORGET_AFTER_MS = 6 * 60_000L
private const val BT_NEAR_CLASSIC_EVERY_CYCLES = 5
private const val BT_NEAR_MAX_DEVICES = 64

/** Well below what controllers offload; past their limit a filter silently stops matching with the screen off. */
private const val BT_NEAR_MAX_FILTERS = 16
private const val BT_NEAR_NAME_LIMIT = 248
private const val BT_NEAR_TAG = "BluetoothNear"

private val BLUETOOTH_ADDRESS = Regex("(?:[0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}")
private val DEVICE_TEXT_CONTROL = Regex("\\p{Cntrl}+")

internal data class NearbyBluetoothDevice(val name: String, val address: String)

internal data class BluetoothNearFilter(val name: String? = null, val address: String? = null)

/**
 * Who has been around lately. A scan misses a device often enough that one missed scan can't mean it
 * left, so a device stays listed until nothing has seen it for [forgetAfterMs].
 */
internal class NearbyBluetoothTracker(
    private val forgetAfterMs: Long = BT_NEAR_FORGET_AFTER_MS,
    private val maxDevices: Int = BT_NEAR_MAX_DEVICES,
) {
    private class Sighting(val name: String, val address: String, val lastSeenMs: Long)

    // Insertion order is oldest sighting first, which is what the size cap drops.
    private val sightings = LinkedHashMap<String, Sighting>()

    @Synchronized
    fun sighted(name: String?, address: String?, nowMs: Long) {
        val cleanName = cleanDeviceText(name)
        val cleanAddress = cleanDeviceText(address).uppercase()
        val key = cleanAddress.ifEmpty { cleanName }
        if (key.isEmpty()) return
        val previous = sightings.remove(key)
        sightings[key] = Sighting(cleanName.ifEmpty { previous?.name.orEmpty() }, cleanAddress, nowMs)
        while (sightings.size > maxDevices) sightings.remove(sightings.keys.first())
    }

    @Synchronized
    fun forgetStale(nowMs: Long) {
        sightings.values.removeAll { nowMs - it.lastSeenMs >= forgetAfterMs }
    }

    @Synchronized
    fun clear() = sightings.clear()

    /** One `name<TAB>address` line per device, in an order that only changes when the list does. */
    @Synchronized
    fun stateValue(): String = sightings.values
        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, Sighting::name).thenBy(Sighting::address))
        .joinToString("\n") { "${it.name}\t${it.address}" }
}

/** A device name comes over the air from anyone nearby, so it can't be allowed to fake a second line. */
private fun cleanDeviceText(text: String?): String =
    text.orEmpty().replace(DEVICE_TEXT_CONTROL, " ").trim().take(BT_NEAR_NAME_LIMIT)

internal fun bluetoothNearMatches(op: String, expectedRaw: String, state: Map<String, String>): Boolean {
    if (op != "=") return false
    val expected = expectedRaw.trim()
    if (expected.isEmpty()) return false
    return state[BT_NEAR_STATE_KEY].orEmpty().lineSequence().any { line ->
        line.split('\t').any { it.equals(expected, ignoreCase = true) }
    }
}

internal fun isBluetoothAddress(text: String): Boolean = BLUETOOTH_ADDRESS.matches(text.trim())

/**
 * The scan filters for what profiles are waiting for. Android delivers nothing to an unfiltered scan
 * with the screen off, and its name filter is case sensitive, so a paired device is also filtered by
 * the name and address it was paired under. A paired phone's rotating address resolves to that
 * address, which is how a phone that advertises no name can still be recognized.
 */
internal fun bluetoothNearFilters(
    targets: Set<String>,
    bonded: List<NearbyBluetoothDevice>,
    limit: Int = BT_NEAR_MAX_FILTERS,
): List<BluetoothNearFilter> =
    targets.flatMap { filtersForTarget(it.trim(), bonded) }.distinct().take(limit)

private fun filtersForTarget(target: String, bonded: List<NearbyBluetoothDevice>): List<BluetoothNearFilter> {
    if (target.isEmpty()) return emptyList()
    if (isBluetoothAddress(target)) return listOf(BluetoothNearFilter(address = target.uppercase()))
    val paired = bonded.filter { it.name.equals(target, ignoreCase = true) }
    return paired.flatMap { device ->
        listOf(BluetoothNearFilter(address = device.address.uppercase()), BluetoothNearFilter(name = device.name))
    } + BluetoothNearFilter(name = target)
}

/**
 * What to record from a scan result, or null when no profile asked about this device. The name a
 * device was paired under and the name it advertises can differ, so either can be what matched.
 * Null [targets] means list everything, for the Inspector.
 */
internal fun bluetoothNearSighting(
    names: List<String?>,
    address: String?,
    targets: Set<String>?,
): NearbyBluetoothDevice? {
    val known = names.mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }
    val cleanAddress = address?.trim().orEmpty()
    if (targets == null) {
        if (known.isEmpty() && cleanAddress.isEmpty()) return null
        return NearbyBluetoothDevice(known.firstOrNull().orEmpty(), cleanAddress)
    }
    val wanted = targets.map(String::trim)
    val matchedName = known.firstOrNull { name -> wanted.any { it.equals(name, ignoreCase = true) } }
    val matchedAddress = cleanAddress.isNotEmpty() && wanted.any { it.equals(cleanAddress, ignoreCase = true) }
    if (matchedName == null && !matchedAddress) return null
    return NearbyBluetoothDevice(matchedName ?: known.firstOrNull().orEmpty(), cleanAddress)
}

/** Classic discovery is costly and stutters Bluetooth audio, so it's rare and never runs over a call or music. */
internal fun bluetoothNearRunsClassicDiscovery(cycle: Int, audioConnected: Boolean): Boolean =
    cycle % BT_NEAR_CLASSIC_EVERY_CYCLES == 0 && !audioConnected

/** Below Android 12 a Bluetooth scan is gated on location instead of a Bluetooth permission. */
internal fun bluetoothScanPermission(sdkInt: Int = Build.VERSION.SDK_INT): String =
    if (sdkInt >= 31) Manifest.permission.BLUETOOTH_SCAN else Manifest.permission.ACCESS_FINE_LOCATION

internal fun bluetoothNearSetupGap(
    sdkInt: Int,
    hasBluetooth: Boolean,
    canScan: Boolean,
    isEnabled: Boolean,
    locationOn: Boolean,
): String? = when {
    !hasBluetooth -> "This device has no Bluetooth, so Bluetooth Near has nothing to scan with."
    !canScan -> "Open Setup and grant Nearby Bluetooth scan before using Bluetooth Near."
    !isEnabled -> "Turn Bluetooth on to use Bluetooth Near."
    sdkInt < 31 && !locationOn -> "Turn location on. Android 11 and older need it to scan for Bluetooth devices."
    else -> null
}

/** The devices enabled profiles are waiting for, which a state source can't learn from its key alone. */
internal object BluetoothNearTargets {
    @Volatile
    var current: Set<String> = emptySet()
        private set

    fun sync(targets: Set<String>) {
        current = targets
    }
}

/**
 * Runs one short scan each time [scanOnce] is called and publishes who is around. Scanning stays in
 * short bursts because Android moves a scan that runs past 30 minutes to a mode that sees almost
 * nothing, and allows only five scan starts in 30 seconds.
 *
 * @param targets what to look for; null lists everything, for the Inspector
 * @param publish receives the state value whenever a scan finishes or finds someone
 */
@SuppressLint("MissingPermission")
internal class NearbyBluetoothScanner(
    private val app: Context,
    private val targets: () -> Set<String>?,
    private val publish: (String) -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val tracker = NearbyBluetoothTracker()

    // A connected device usually stops advertising, so it would otherwise read as gone.
    private val connected = mutableMapOf<String, NearbyBluetoothDevice>()
    private var cycle = 0
    private var activeScanner: BluetoothLeScanner? = null
    private var startedDiscovery = false
    private var receiverRegistered = false

    private val finishScan = Runnable {
        synchronized(this) {
            stopRadios()
            tracker.forgetStale(SystemClock.elapsedRealtime())
            publish(tracker.stateValue())
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) = note(result)

        override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach(::note)

        override fun onScanFailed(errorCode: Int) {
            AppLogger.debug(BT_NEAR_TAG, "BLE scan failed with error $errorCode")
        }

        private fun note(result: ScanResult) = sighted(
            listOf(result.device.bluetoothNameOrNull(), result.scanRecord?.deviceName),
            result.device.address,
        )
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val device = IntentCompat.getParcelableExtra(intent, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                ?: return
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> sighted(
                    listOf(device.bluetoothNameOrNull(), intent.getStringExtra(BluetoothDevice.EXTRA_NAME)),
                    device.address,
                )
                BluetoothDevice.ACTION_ACL_CONNECTED -> noteConnected(device)
                // Stays listed until it's forgotten, like anything else that stops being seen.
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> synchronized(this@NearbyBluetoothScanner) {
                    connected.remove(device.address)
                }
            }
        }
    }

    private fun adapter(): BluetoothAdapter? = app.getSystemService(BluetoothManager::class.java)?.adapter

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(app, permission) == PackageManager.PERMISSION_GRANTED

    fun setupGap(): String? {
        val adapter = adapter()
        return bluetoothNearSetupGap(
            sdkInt = Build.VERSION.SDK_INT,
            hasBluetooth = adapter != null,
            canScan = hasPermission(bluetoothScanPermission()),
            isEnabled = adapter?.isEnabled == true,
            locationOn = Build.VERSION.SDK_INT >= 31 ||
                app.getSystemService(LocationManager::class.java)?.let(LocationManagerCompat::isLocationEnabled) == true,
        )
    }

    @Synchronized
    fun scanOnce() {
        if (activeScanner != null) return
        val wanted = targets()
        if (wanted != null && wanted.isEmpty()) {
            tracker.clear()
            publish(tracker.stateValue())
            return
        }
        val adapter = adapter() ?: return
        val leScanner = adapter.bluetoothLeScanner ?: return
        if (!receiverRegistered) {
            registerReceiver()
            seedConnectedDevices(adapter)
        }
        val filters = wanted?.let { bluetoothNearFilters(it, bondedDevices(adapter)).map(::toScanFilter) }.orEmpty()
        // An empty list means an unfiltered scan, which must only ever happen for the Inspector.
        if (wanted != null && filters.isEmpty()) return

        connected.values.toList().forEach { sighted(listOf(it.name), it.address) }
        runCatching {
            leScanner.startScan(filters, SCAN_SETTINGS, scanCallback)
            activeScanner = leScanner
        }.onFailure { AppLogger.debug(BT_NEAR_TAG, "BLE scan did not start: ${it.message}") }
        if (bluetoothNearRunsClassicDiscovery(cycle, audioConnected(adapter)) && !adapter.isDiscovering) {
            startedDiscovery = runCatching { adapter.startDiscovery() }.getOrDefault(false)
        }
        cycle++
        handler.removeCallbacks(finishScan)
        handler.postDelayed(finishScan, BT_NEAR_SCAN_MS)
    }

    @Synchronized
    fun stop() {
        handler.removeCallbacks(finishScan)
        stopRadios()
        if (receiverRegistered) runCatching { app.unregisterReceiver(receiver) }
        receiverRegistered = false
        connected.clear()
        tracker.clear()
        cycle = 0
    }

    private fun stopRadios() {
        // Throws if Bluetooth was turned off during the scan, in which case it already stopped.
        runCatching { activeScanner?.stopScan(scanCallback) }
        activeScanner = null
        if (startedDiscovery) runCatching { adapter()?.cancelDiscovery() }
        startedDiscovery = false
    }

    private fun sighted(names: List<String?>, address: String?) {
        val device = bluetoothNearSighting(names, address, targets()) ?: return
        tracker.sighted(device.name, device.address, SystemClock.elapsedRealtime())
        publish(tracker.stateValue())
    }

    private fun noteConnected(device: BluetoothDevice) {
        val entry = NearbyBluetoothDevice(device.bluetoothNameOrNull().orEmpty(), device.address)
        synchronized(this) { connected[device.address] = entry }
        sighted(listOf(entry.name), entry.address)
    }

    private fun registerReceiver() {
        ContextCompat.registerReceiver(
            app,
            receiver,
            IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_FOUND)
                addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
                addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            },
            systemAppReceiverFlags(),
        )
        receiverRegistered = true
    }

    /** The connection broadcasts only cover what happens from here on. */
    private fun seedConnectedDevices(adapter: BluetoothAdapter) {
        runCatching {
            app.getSystemService(BluetoothManager::class.java)
                ?.getConnectedDevices(BluetoothProfile.GATT)
                ?.forEach(::noteConnected)
        }
        listOf(BluetoothProfile.A2DP, BluetoothProfile.HEADSET).forEach { profile ->
            runCatching {
                adapter.getProfileProxy(
                    app,
                    object : BluetoothProfile.ServiceListener {
                        override fun onServiceConnected(connectedProfile: Int, proxy: BluetoothProfile) {
                            runCatching { proxy.connectedDevices.forEach(::noteConnected) }
                            adapter.closeProfileProxy(connectedProfile, proxy)
                        }

                        override fun onServiceDisconnected(disconnectedProfile: Int) = Unit
                    },
                    profile,
                )
            }
        }
    }

    private fun bondedDevices(adapter: BluetoothAdapter): List<NearbyBluetoothDevice> =
        runCatching {
            adapter.bondedDevices.orEmpty().map { NearbyBluetoothDevice(it.bluetoothNameOrNull().orEmpty(), it.address) }
        }.getOrDefault(emptyList())

    // Without the connect permission the answer is unknown, and discovery is what could hurt audio.
    private fun audioConnected(adapter: BluetoothAdapter): Boolean = runCatching {
        listOf(BluetoothProfile.A2DP, BluetoothProfile.HEADSET).any {
            adapter.getProfileConnectionState(it) == BluetoothAdapter.STATE_CONNECTED
        }
    }.getOrDefault(true)

    private fun toScanFilter(filter: BluetoothNearFilter): ScanFilter = ScanFilter.Builder().apply {
        filter.name?.let(::setDeviceName)
        filter.address?.let(::setDeviceAddress)
    }.build()

    private companion object {
        val SCAN_SETTINGS: ScanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_BALANCED)
            .build()
    }
}

// Reading a name needs the connect permission on Android 12 and newer, and scanning doesn't.
@SuppressLint("MissingPermission")
private fun BluetoothDevice.bluetoothNameOrNull(): String? =
    runCatching { name }.getOrNull()
