package com.opentasker.core.contexts

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.opentasker.core.logging.AppLogger
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Bridges Bluetooth ACL connect/disconnect broadcasts into `event=bluetooth` context events.
 * The final disconnect also emits `event=bluetooth_all_disconnected`.
 *
 * Emits metadata:
 *   - "event": "bluetooth"
 *   - "state": "connected" | "disconnected"
 *   - "device": human-readable device name (or "Unknown" when name is unavailable)
 *   - "address": device MAC address (used for precise filtering; never written to run logs)
 *
 * Profiles match via the shared event matcher: set `event=bluetooth`, optionally `state=connected`
 * (or `disconnected`), and use the `filter` field to match a device name or address.
 *
 * Reading the device name requires BLUETOOTH_CONNECT on Android 12+; failures fall back to a
 * generic name so a connect/disconnect can still trigger by state or address.
 */
/**
 * Flags for a receiver of broadcasts a system app sends, such as the Bluetooth app. Below Android 13
 * ContextCompat emulates RECEIVER_NOT_EXPORTED with a signature permission the sender has to hold.
 * Only the system server is exempt from that check, so those broadcasts would never arrive. The
 * actions registered with this are protected broadcasts, so exporting doesn't let an app fake one.
 */
internal fun systemAppReceiverFlags(sdkInt: Int = Build.VERSION.SDK_INT): Int =
    if (sdkInt >= 33) ContextCompat.RECEIVER_NOT_EXPORTED else ContextCompat.RECEIVER_EXPORTED

object BluetoothContextEvents {
    private val events_ = MutableSharedFlow<ContextEvent>(
        extraBufferCapacity = 16,
    )

    val events: SharedFlow<ContextEvent> = events_.asSharedFlow()

    const val STATE_CONNECTED = "connected"
    const val STATE_DISCONNECTED = "disconnected"
    const val EVENT_ALL_DISCONNECTED = "bluetooth_all_disconnected"
    const val EVENT_SOME_CONNECTED = "bluetooth_some_connected"
    const val STATE_ALL_DISCONNECTED = "all_disconnected"
    const val STATE_SOME_CONNECTED = "some_connected"
    const val EVENT_KEY_MISSING = "bluetooth_key_missing"
    const val EVENT_ENCRYPTION_CHANGE = "bluetooth_encryption_change"
    const val STATE_KEY_MISSING = "key_missing"
    const val STATE_ENCRYPTED = "encrypted"
    const val STATE_UNENCRYPTED = "unencrypted"
    const val UNKNOWN_DEVICE = "Unknown"

    private val connectionTracker = BluetoothConnectionTracker()

    /**
     * Drops the tracked connections, which the engine calls when it starts the Bluetooth receiver.
     *
     * Devices disconnect while the receiver is unregistered and nothing tells us, so carrying the
     * old set into a fresh registration reports the aggregate transitions wrongly from then on.
     */
    fun resetConnections() = connectionTracker.reset()

    val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (Build.VERSION.SDK_INT >= ANDROID_16_API && isApi36SecurityAction(intent.action)) {
                handleApi36SecurityIntent(intent)
                return
            }
            val state = when (intent.action) {
                BluetoothDevice.ACTION_ACL_CONNECTED -> STATE_CONNECTED
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> STATE_DISCONNECTED
                else -> return
            }
            val device = intent.bluetoothDevice()
            val name = device.safeName()
            val address = device.safeAddress()
            val identity = address.ifBlank { "name:$name" }
            // Log device name + state only; the address is intentionally omitted from logs.
            AppLogger.debug(TAG, "Bluetooth event: state=$state, device=$name")
            events_.tryEmitPulse("bluetooth", buildEvent(state, name, address))
            if (state == STATE_CONNECTED) {
                if (connectionTracker.onConnected(identity)) {
                    events_.tryEmitPulse("bluetooth", buildSomeConnectedEvent())
                }
            } else if (connectionTracker.onDisconnected(identity)) {
                events_.tryEmitPulse("bluetooth", buildAllDisconnectedEvent())
            }
        }
    }

    /** Pure event builder so matching/metadata can be unit-tested without Android broadcasts. */
    fun buildEvent(
        state: String,
        deviceName: String,
        deviceAddress: String = "",
    ): ContextEvent = ContextEvent(
        type = "event",
        matched = true,
        metadata = buildMap {
            put("event", "bluetooth")
            put("state", state)
            put("device", deviceName.ifBlank { UNKNOWN_DEVICE })
            if (deviceAddress.isNotBlank()) {
                put("address", deviceAddress)
            }
        },
    )

    fun buildAllDisconnectedEvent(): ContextEvent = ContextEvent(
        type = "event",
        matched = true,
        metadata = mapOf(
            "event" to EVENT_ALL_DISCONNECTED,
            "state" to STATE_ALL_DISCONNECTED,
            "connectedCount" to "0",
        ),
    )

    fun buildSomeConnectedEvent(): ContextEvent = ContextEvent(
        type = "event",
        matched = true,
        metadata = mapOf(
            "event" to EVENT_SOME_CONNECTED,
            "state" to STATE_SOME_CONNECTED,
            "connectedCount" to "1+",
        ),
    )

    fun buildKeyMissingEvent(
        deviceName: String,
        deviceAddress: String = "",
        bondLossReason: Int? = null,
    ): ContextEvent = ContextEvent(
        type = "event",
        matched = true,
        metadata = buildMap {
            put("event", EVENT_KEY_MISSING)
            put("state", STATE_KEY_MISSING)
            put("device", deviceName.ifBlank { UNKNOWN_DEVICE })
            if (deviceAddress.isNotBlank()) put("address", deviceAddress)
            bondLossReason?.let { put("bondLossReason", it.toString()) }
        },
    )

    fun buildEncryptionChangeEvent(
        deviceName: String,
        deviceAddress: String = "",
        enabled: Boolean,
        status: Int,
        algorithm: Int,
        keySize: Int,
        transport: Int,
    ): ContextEvent = ContextEvent(
        type = "event",
        matched = true,
        metadata = buildMap {
            put("event", EVENT_ENCRYPTION_CHANGE)
            put("state", if (enabled) STATE_ENCRYPTED else STATE_UNENCRYPTED)
            put("device", deviceName.ifBlank { UNKNOWN_DEVICE })
            if (deviceAddress.isNotBlank()) put("address", deviceAddress)
            put("enabled", enabled.toString())
            put("status", status.toString())
            put("algorithm", algorithm.toString())
            put("keySize", keySize.toString())
            put("transport", transport.toString())
        },
    )

    fun intentFilter(): IntentFilter = IntentFilter().apply {
        addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
        addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        if (Build.VERSION.SDK_INT >= ANDROID_16_API) addApi36SecurityActions(this)
    }

    internal fun supportsSecurityTriggers(sdkInt: Int): Boolean = sdkInt >= ANDROID_16_API

    @RequiresApi(ANDROID_16_API)
    private fun addApi36SecurityActions(filter: IntentFilter) {
        filter.addAction(BluetoothDevice.ACTION_KEY_MISSING)
        filter.addAction(BluetoothDevice.ACTION_ENCRYPTION_CHANGE)
    }

    @RequiresApi(ANDROID_16_API)
    private fun isApi36SecurityAction(action: String?): Boolean =
        action == BluetoothDevice.ACTION_KEY_MISSING || action == BluetoothDevice.ACTION_ENCRYPTION_CHANGE

    @RequiresApi(ANDROID_16_API)
    private fun handleApi36SecurityIntent(intent: Intent) {
        val device = intent.bluetoothDevice()
        val name = device.safeName()
        val address = device.safeAddress()
        when (intent.action) {
            BluetoothDevice.ACTION_KEY_MISSING -> {
                val reason = intent
                    .getIntExtra(BOND_LOSS_REASON_EXTRA, BluetoothDevice.ERROR)
                    .takeUnless { it == BluetoothDevice.ERROR }
                AppLogger.debug(TAG, "Bluetooth security event: key missing, device=$name")
                events_.tryEmitPulse("bluetooth", buildKeyMissingEvent(name, address, reason))
            }

            BluetoothDevice.ACTION_ENCRYPTION_CHANGE -> {
                val enabled = intent.getBooleanExtra(BluetoothDevice.EXTRA_ENCRYPTION_ENABLED, false)
                AppLogger.debug(TAG, "Bluetooth security event: encryption=${if (enabled) "on" else "off"}, device=$name")
                events_.tryEmitPulse("bluetooth", 
                    buildEncryptionChangeEvent(
                        deviceName = name,
                        deviceAddress = address,
                        enabled = enabled,
                        status = intent.getIntExtra(BluetoothDevice.EXTRA_ENCRYPTION_STATUS, BluetoothDevice.ERROR),
                        algorithm = intent.getIntExtra(BluetoothDevice.EXTRA_ENCRYPTION_ALGORITHM, BluetoothDevice.ERROR),
                        keySize = intent.getIntExtra(BluetoothDevice.EXTRA_KEY_SIZE, BluetoothDevice.ERROR),
                        transport = intent.getIntExtra(BluetoothDevice.EXTRA_TRANSPORT, BluetoothDevice.ERROR),
                    ),
                )
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun Intent.bluetoothDevice(): BluetoothDevice? =
        if (Build.VERSION.SDK_INT >= 33) {
            getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }

    private fun BluetoothDevice?.safeName(): String =
        try {
            this?.name?.takeIf { it.isNotBlank() } ?: UNKNOWN_DEVICE
        } catch (_: SecurityException) {
            // BLUETOOTH_CONNECT not granted on Android 12+; name is unavailable.
            UNKNOWN_DEVICE
        }

    private fun BluetoothDevice?.safeAddress(): String =
        try {
            this?.address.orEmpty()
        } catch (_: SecurityException) {
            ""
        }

    private const val ANDROID_16_API = 36
    private const val BOND_LOSS_REASON_EXTRA = "android.bluetooth.device.extra.BOND_LOSS_REASON"
    private const val TAG = "BluetoothContextEvents"
}
