package com.opentasker.core.contexts

import androidx.core.content.ContextCompat
import com.opentasker.core.model.ContextSpec
import com.opentasker.core.model.ContextType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class BluetoothNearTest {
    private val phoneAddress = "AA:BB:CC:DD:EE:01"
    private val phoneLine = "Sam's Phone\t$phoneAddress"

    @Test
    fun aDeviceStaysListedThroughMissedScansThenIsForgotten() {
        val tracker = NearbyBluetoothTracker(forgetAfterMs = 1_000)
        tracker.sighted("Sam's Phone", phoneAddress, nowMs = 100)

        tracker.forgetStale(nowMs = 1_099)
        assertEquals(phoneLine, tracker.stateValue())

        tracker.forgetStale(nowMs = 1_100)
        assertEquals("", tracker.stateValue())
    }

    @Test
    fun seeingADeviceAgainRestartsItsClock() {
        val tracker = NearbyBluetoothTracker(forgetAfterMs = 1_000)
        tracker.sighted("Sam's Phone", phoneAddress, nowMs = 100)
        tracker.sighted("Sam's Phone", phoneAddress, nowMs = 900)

        tracker.forgetStale(nowMs = 1_500)

        assertEquals(phoneLine, tracker.stateValue())
    }

    @Test
    fun aLaterSightingWithoutANameKeepsTheOneItHad() {
        val tracker = NearbyBluetoothTracker()
        tracker.sighted("Sam's Phone", phoneAddress, nowMs = 100)
        tracker.sighted(null, phoneAddress.lowercase(), nowMs = 200)

        assertEquals(phoneLine, tracker.stateValue())
    }

    @Test
    fun aNameFromTheAirCannotFakeASecondDevice() {
        val tracker = NearbyBluetoothTracker()
        tracker.sighted("Evil\nSam's Phone\t$phoneAddress", "AA:BB:CC:DD:EE:02", nowMs = 100)

        val value = tracker.stateValue()
        assertEquals(1, value.lines().size)
        assertEquals(2, value.split('\t').size)
        assertFalse(stateMatches("bt_near=Sam's Phone", mapOf("bt_near" to value)))
    }

    @Test
    fun aCrowdedRoomCannotGrowTheListWithoutLimit() {
        val tracker = NearbyBluetoothTracker(maxDevices = 2)
        tracker.sighted("One", "AA:BB:CC:DD:EE:01", nowMs = 1)
        tracker.sighted("Two", "AA:BB:CC:DD:EE:02", nowMs = 2)
        tracker.sighted("Three", "AA:BB:CC:DD:EE:03", nowMs = 3)

        assertEquals("Three\tAA:BB:CC:DD:EE:03\nTwo\tAA:BB:CC:DD:EE:02", tracker.stateValue())
    }

    @Test
    fun theOrderOfSightingsDoesNotChangeThePublishedValue() {
        val first = NearbyBluetoothTracker()
        first.sighted("alpha", "AA:BB:CC:DD:EE:01", nowMs = 1)
        first.sighted("Beta", "AA:BB:CC:DD:EE:02", nowMs = 2)
        val second = NearbyBluetoothTracker()
        second.sighted("Beta", "AA:BB:CC:DD:EE:02", nowMs = 1)
        second.sighted("alpha", "AA:BB:CC:DD:EE:01", nowMs = 2)

        assertEquals(first.stateValue(), second.stateValue())
    }

    @Test
    fun aContextMatchesByNameOrAddressIgnoringCase() {
        val state = mapOf("bt_near" to "Car\tAA:BB:CC:DD:EE:09\n$phoneLine")

        assertTrue(stateMatches("bt_near=Sam's Phone", state))
        assertTrue(stateMatches("bt_near=sam's phone", state))
        assertTrue(stateMatches("bt_near = aa:bb:cc:dd:ee:01", state))
        assertTrue(stateMatches("bluetooth_near=Car", state))
        assertTrue(stateMatches("bt near=Car", state))
    }

    @Test
    fun aContextNeverMatchesWithoutAnExactDeviceOrWithAnotherComparison() {
        val state = mapOf("bt_near" to phoneLine)

        assertFalse(stateMatches("bt_near=Sam", state))
        assertFalse(stateMatches("bt_near=", state))
        assertFalse(stateMatches("bt_near>=Sam's Phone", state))
        assertFalse(stateMatches("bt_near=Sam's Phone", emptyMap()))
        assertFalse(stateMatches("bt_near=Sam's Phone", mapOf("bt_near" to "")))
    }

    @Test
    fun targetsComeOnlyFromNearbyDeviceContextsThatCanMatch() {
        fun target(vararg config: Pair<String, String>, type: ContextType = ContextType.STATE) =
            bluetoothNearTarget(ContextSpec(type, mapOf(*config)))

        assertEquals("Sam's Phone", target("key" to "bt_near", "operator" to "=", "value" to " Sam's Phone "))
        assertEquals("Car", target("key" to "bluetooth_near", "value" to "Car"))
        assertEquals("Car", target("predicate" to "bt_near=Car"))
        assertNull(target("key" to "wifi", "value" to "Car"))
        assertNull(target("key" to "bt_near", "operator" to ">", "value" to "Car"))
        assertNull(target("key" to "bt_near", "value" to "  "))
        assertNull(target("event" to "bt_near", "key" to "bt_near", "value" to "Car", type = ContextType.EVENT))

        assertEquals(
            setOf("Car", "Sam's Phone"),
            bluetoothNearTargets(
                listOf(
                    ContextSpec(ContextType.STATE, mapOf("key" to "bt_near", "value" to "Car")),
                    ContextSpec(ContextType.STATE, mapOf("key" to "bt_near", "value" to "Sam's Phone")),
                    ContextSpec(ContextType.STATE, mapOf("key" to "bt_near", "value" to "Car")),
                    ContextSpec(ContextType.STATE, mapOf("key" to "wifi", "value" to "Home")),
                ),
            ),
        )
    }

    @Test
    fun anAddressTargetBecomesTheUppercaseFilterAndroidRequires() {
        assertEquals(
            listOf(BluetoothNearFilter(address = phoneAddress)),
            bluetoothNearFilters(setOf(phoneAddress.lowercase()), emptyList()),
        )
    }

    @Test
    fun aPairedDeviceIsAlsoFilteredByItsPairedAddressAndExactName() {
        val filters = bluetoothNearFilters(
            setOf("sam's phone"),
            listOf(
                NearbyBluetoothDevice("Sam's Phone", phoneAddress),
                NearbyBluetoothDevice("Car", "AA:BB:CC:DD:EE:09"),
            ),
        )

        assertEquals(
            listOf(
                BluetoothNearFilter(address = phoneAddress),
                BluetoothNearFilter(name = "Sam's Phone"),
                BluetoothNearFilter(name = "sam's phone"),
            ),
            filters,
        )
    }

    @Test
    fun blankTargetsNeverTurnIntoAnUnfilteredScan() {
        assertTrue(bluetoothNearFilters(setOf("  "), emptyList()).isEmpty())
        assertTrue(bluetoothNearFilters(emptySet(), emptyList()).isEmpty())
    }

    @Test
    fun theFilterCountStaysBounded() {
        val filters = bluetoothNearFilters((1..50).map { "Device $it" }.toSet(), emptyList(), limit = 16)

        assertEquals(16, filters.size)
    }

    @Test
    fun onlyDevicesAProfileAskedAboutAreRecorded() {
        val targets = setOf("Sam's Phone")

        assertNull(bluetoothNearSighting(listOf("Neighbor's TV"), "AA:BB:CC:DD:EE:02", targets))
        assertEquals(
            NearbyBluetoothDevice("Sam's Phone", phoneAddress),
            bluetoothNearSighting(listOf("Galaxy S22", "Sam's Phone"), phoneAddress, targets),
        )
        assertEquals(
            NearbyBluetoothDevice("", phoneAddress),
            bluetoothNearSighting(listOf(null), phoneAddress, setOf(phoneAddress.lowercase())),
        )
    }

    @Test
    fun bluetoothBroadcastsCanReachAReceiverOnEveryAndroidVersion() {
        assertEquals(
            "below Android 13 a not-exported receiver refuses the Bluetooth app's broadcasts",
            ContextCompat.RECEIVER_EXPORTED,
            systemAppReceiverFlags(sdkInt = 32),
        )
        assertEquals(ContextCompat.RECEIVER_NOT_EXPORTED, systemAppReceiverFlags(sdkInt = 33))
    }

    @Test
    fun theLegacyBluetoothPermissionsCoverOnlyTheVersionsThatUseThem() {
        val manifest = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(
            listOf(File("src/main/AndroidManifest.xml"), File("app/src/main/AndroidManifest.xml")).first { it.exists() },
        )
        val declared = manifest.getElementsByTagName("uses-permission")
        val maxSdkByName = (0 until declared.length).associate { index ->
            val attributes = declared.item(index).attributes
            attributes.getNamedItem("android:name").nodeValue to attributes.getNamedItem("android:maxSdkVersion")?.nodeValue
        }

        assertEquals("30", maxSdkByName["android.permission.BLUETOOTH"])
        assertEquals("30", maxSdkByName["android.permission.BLUETOOTH_ADMIN"])
        assertTrue("android.permission.BLUETOOTH_SCAN" in maxSdkByName)
    }

    @Test
    fun everySpellingOfTheKeyIsTheSameContext() {
        listOf("bt_near", "bluetooth_near", "bt near", "BTNEAR").forEach { spelling ->
            assertEquals(
                spelling,
                BT_NEAR_STATE_KEY,
                stateContextKey(ContextSpec(ContextType.STATE, mapOf("key" to spelling, "value" to "Car"))),
            )
        }
    }

    @Test
    fun theInspectorSeesEverythingAround() {
        assertEquals(
            NearbyBluetoothDevice("Neighbor's TV", "AA:BB:CC:DD:EE:02"),
            bluetoothNearSighting(listOf(null, "Neighbor's TV"), "AA:BB:CC:DD:EE:02", targets = null),
        )
        assertNull(bluetoothNearSighting(listOf(null), null, targets = null))
    }

    @Test
    fun classicDiscoveryIsRareAndStaysOutOfTheWayOfAudio() {
        assertTrue(bluetoothNearRunsClassicDiscovery(cycle = 0, audioConnected = false))
        assertFalse(bluetoothNearRunsClassicDiscovery(cycle = 1, audioConnected = false))
        assertTrue(bluetoothNearRunsClassicDiscovery(cycle = 5, audioConnected = false))
        assertFalse(bluetoothNearRunsClassicDiscovery(cycle = 0, audioConnected = true))
    }

    @Test
    fun theScanPermissionFollowsTheAndroidVersion() {
        assertEquals("android.permission.BLUETOOTH_SCAN", bluetoothScanPermission(31))
        assertEquals("android.permission.ACCESS_FINE_LOCATION", bluetoothScanPermission(30))
    }

    @Test
    fun aSetupGapSaysWhatToDoFirst() {
        fun gap(
            sdkInt: Int = 36,
            hasBluetooth: Boolean = true,
            canScan: Boolean = true,
            isEnabled: Boolean = true,
            locationOn: Boolean = true,
        ) = bluetoothNearSetupGap(sdkInt, hasBluetooth, canScan, isEnabled, locationOn)

        assertNull(gap())
        assertNull("Android 12 and newer scan without location", gap(sdkInt = 31, locationOn = false))
        assertTrue(gap(hasBluetooth = false, canScan = false)!!.contains("no Bluetooth"))
        assertTrue(gap(canScan = false, isEnabled = false)!!.contains("Setup"))
        assertTrue(gap(isEnabled = false)!!.contains("Turn Bluetooth on"))
        assertTrue(gap(sdkInt = 30, locationOn = false)!!.contains("location"))
    }
}
