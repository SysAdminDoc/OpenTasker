package com.opentasker.core.contexts

import android.content.Context
import com.opentasker.core.plugins.locale.LocalePluginRequestQueryEvents
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch

/**
 * Real EventContextSource backed by explicit app-owned event bridges.
 *
 * Supported events:
 *   - "notification": notification posted (requires NotificationListenerService)
 *   - "calendar": local CalendarProvider event windows (requires READ_CALENDAR)
 *   - "sun_tick": local minute tick used by sunrise/sunset event filters
 *   - "nfc": NFC tag scan
 *   - "bluetooth": Bluetooth device connected/disconnected
 *   - "usb": USB/input device attached/detached
 *   - "shake": accelerometer shake pulse
 *   - "camera" / "mic": active AppOps watcher pulse
 *   - "screen_recording": Android 15 screen-recording visibility callback pulse
 *   - "advanced_protection": Android 16 Advanced Protection state callback pulse
 *   - "sms_received": sanitized SMS/MMS delivery (standard/F-Droid builds only)
 *   - "incoming_call": the phone started ringing, with the caller's number when Android shares it
 *   - "package_added" / "package_removed" / "package_replaced": package changes
 *   - "locale_request_query": Locale condition plugin requested a host query
 *   - "boot_completed": manifest boot receiver restarted the engine
 *   - "tile_clicked": Quick Settings tile toggled
 *   - "push": authenticated UnifiedPush distributor delivery
 *   - "share": sanitized Android Sharesheet text, URI, or file delivery
 *   - "broadcast": a named intent from another app, with bounded string extras
 */
class EventContextSourceImpl : EventDemandContextSource {
    override val type = "event"

    override fun events(
        app: Context,
        requestedEvent: String?,
        onSubscribed: () -> Unit,
    ): Flow<ContextEvent> = channelFlow {
        val collectors = eventFlows(app, requestedEvent).map { upstream ->
            launch(start = CoroutineStart.UNDISPATCHED) {
                upstream.collect { event -> send(event) }
            }
        }

        // Managed pulse flows are subscribed by undispatched collectors before this callback. The
        // service waits for it before starting a newly required sensor, receiver, or AppOps watcher.
        onSubscribed()
        awaitClose { collectors.forEach { it.cancel() } }
    }

    private fun eventFlows(app: Context, requestedEvent: String?): List<Flow<ContextEvent>> = listOf(
        // Keep service-owned pulse bridges first so onSubscribed is a strict producer barrier.
        ShakeContextEvents.events,
        CameraMicContextEvents.flow,
        PackageContextEvents.events,
        BluetoothContextEvents.events,
        UsbDeviceContextEvents.events,
        CompanionContextEvents.events,
        ScreenRecordingContextEvents.events,
        AdvancedProtectionContextEvents.events,
        SmsContextEvents.events,
        CallContextEvents.events,
        NotificationContextEvents.events,
        NfcContextEvents.events,
        BootContextEvents.events,
        CalendarSunContextEvents.events(app, requestedEvent = requestedEvent),
        LocalePluginRequestQueryEvents.events(app),
        QuickSettingsTileContextEvents.events,
        PushContextEvents.events,
        ShareContextEvents.events,
        BroadcastContextEvents.events,
    )
}
