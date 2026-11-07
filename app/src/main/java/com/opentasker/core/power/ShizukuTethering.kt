package com.opentasker.core.power

import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.os.IBinder
import android.os.Process
import com.opentasker.core.logging.AppLogger
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Supplier

/**
 * What a hotspot change can come back with. Zero and above are Android's own `TETHER_ERROR_*`
 * numbers, which go back to the host untouched, and the negatives are this route's own outcomes.
 */
internal object WifiHotspotPolicy {
    /** `TetheringManager` arrived in Android 11. Older versions hide tethering behind a class that can't be proxied. */
    const val MIN_SDK = 30

    const val OK = 0
    const val ANDROID_TOO_OLD = -1
    const val DID_NOT_CHANGE = -2
    const val NOT_REACHABLE = -3

    const val STATE_UNKNOWN = -4
    const val STATE_OFF = 0
    const val STATE_ON = 1

    /** `WifiManager.WIFI_AP_STATE_*`, which the public API doesn't expose. */
    const val AP_DISABLING = 10
    const val AP_DISABLED = 11
    const val AP_ENABLING = 12
    const val AP_ENABLED = 13
    const val AP_FAILED = 14

    private const val TETHER_SERVICE_UNAVAILABLE = 2
    private const val TETHER_UNSUPPORTED = 3
    private const val TETHER_PROVISIONING_FAILED = 11
    private const val TETHER_ENTITLEMENT_UNKNOWN = 13
    private const val TETHER_NO_CHANGE_PERMISSION = 14

    fun failureMessage(code: Int, enabled: Boolean): String = when (code) {
        ANDROID_TOO_OLD -> "The Wi-Fi hotspot action needs Android 11 or newer."
        DID_NOT_CHANGE -> if (enabled) {
            "The Wi-Fi hotspot didn't come on. Check that it starts from the phone's own settings."
        } else {
            "The Wi-Fi hotspot is still on."
        }
        NOT_REACHABLE -> "This phone's Android doesn't offer the tethering service to Shizuku."
        TETHER_SERVICE_UNAVAILABLE -> "Android's tethering service isn't available right now."
        TETHER_UNSUPPORTED -> "This phone doesn't support Wi-Fi tethering."
        TETHER_PROVISIONING_FAILED, TETHER_ENTITLEMENT_UNKNOWN ->
            "The carrier's plan check refused the Wi-Fi hotspot (tethering error $code)."
        TETHER_NO_CHANGE_PERMISSION -> "Android refused the Wi-Fi hotspot request from Shizuku."
        else -> "The Wi-Fi hotspot failed with tethering error $code."
    }

    /** Tethering checks the package name against the caller's uid, and the process has no package of its own. */
    fun opPackageFor(uid: Int): String? = when (uid) {
        2000 -> "com.android.shell"
        0 -> "root"
        else -> null
    }

    fun readState(rawApState: Int): Int = when (rawApState) {
        AP_ENABLING, AP_ENABLED -> STATE_ON
        AP_DISABLING, AP_DISABLED, AP_FAILED -> STATE_OFF
        else -> STATE_UNKNOWN
    }
}

internal interface TetheringGateway {
    /** Asks Android to start tethering and returns its answer, or [WifiHotspotPolicy.DID_NOT_CHANGE] if none came. */
    fun start(timeoutMillis: Long): Int
    fun stop()
    fun apState(): Int
}

/**
 * Returns [WifiHotspotPolicy.OK] only once Android reports the state that was asked for. A failed
 * state left over from an earlier attempt isn't an answer to this one, so only the wanted state or
 * the deadline ends the wait.
 */
internal fun changeWifiHotspot(
    enabled: Boolean,
    gateway: TetheringGateway,
    sdkInt: Int = Build.VERSION.SDK_INT,
    pollIntervalMillis: Long = 250L,
    timeoutMillis: Long = 10_000L,
    now: () -> Long = System::currentTimeMillis,
    sleep: (Long) -> Unit = { Thread.sleep(it) },
): Int {
    if (sdkInt < WifiHotspotPolicy.MIN_SDK) return WifiHotspotPolicy.ANDROID_TOO_OLD
    val wanted = if (enabled) WifiHotspotPolicy.AP_ENABLED else WifiHotspotPolicy.AP_DISABLED
    if (gateway.apState() == wanted) return WifiHotspotPolicy.OK
    if (enabled) {
        val answer = gateway.start(timeoutMillis)
        if (answer != WifiHotspotPolicy.OK) return answer
    } else {
        gateway.stop()
    }
    val deadline = now() + timeoutMillis
    while (now() < deadline) {
        if (gateway.apState() == wanted) return WifiHotspotPolicy.OK
        sleep(pollIntervalMillis)
    }
    return if (gateway.apState() == wanted) WifiHotspotPolicy.OK else WifiHotspotPolicy.DID_NOT_CHANGE
}

/**
 * Reaches [android.net.TetheringManager] from the Shizuku user service.
 *
 * None of this is public API, so it goes through reflection. A context taken straight from the
 * system context reports the package `android`, which doesn't match the shell uid, and Android
 * answers with tethering error 14. [CallerContext] reports the name that does match.
 */
internal class FrameworkTetheringGateway : TetheringGateway {
    private val context: Context by lazy {
        val threadClass = Class.forName("android.app.ActivityThread")
        val thread = threadClass.getMethod("currentActivityThread").invoke(null)
            ?: error("This process has no ActivityThread")
        val system = threadClass.getMethod("getSystemContext").invoke(thread) as Context
        val caller = WifiHotspotPolicy.opPackageFor(Process.myUid())
            ?: error("No package name for uid ${Process.myUid()}")
        CallerContext(system, caller)
    }

    private val managerClass by lazy { Class.forName(TETHERING_MANAGER) }

    private val manager: Any by lazy {
        val serviceManager = Class.forName("android.os.ServiceManager")
        val getService = serviceManager.getMethod("getService", String::class.java)
        val connector = Supplier { getService.invoke(null, TETHERING_SERVICE) as IBinder? }
        checkNotNull(connector.get()) { "The tethering service isn't registered" }
        val constructor = managerClass.getDeclaredConstructor(Context::class.java, Supplier::class.java)
        constructor.isAccessible = true
        constructor.newInstance(context, connector)
    }

    override fun start(timeoutMillis: Long): Int {
        val callbackClass = Class.forName("$TETHERING_MANAGER\$StartTetheringCallback")
        val answer = AtomicInteger(WifiHotspotPolicy.DID_NOT_CHANGE)
        val answered = CountDownLatch(1)
        val handler = InvocationHandler { proxy, method, args -> onCallback(proxy, method, args, answer, answered) }
        val callback = Proxy.newProxyInstance(callbackClass.classLoader, arrayOf(callbackClass), handler)
        managerClass.getMethod(
            "startTethering",
            Int::class.javaPrimitiveType,
            Executor::class.java,
            callbackClass,
        ).invokeUnwrapped(manager, TETHERING_WIFI, Executor { it.run() }, callback)
        answered.await(timeoutMillis, TimeUnit.MILLISECONDS)
        return answer.get()
    }

    override fun stop() {
        managerClass.getMethod("stopTethering", Int::class.javaPrimitiveType)
            .invokeUnwrapped(manager, TETHERING_WIFI)
    }

    override fun apState(): Int {
        val wifi = checkNotNull(context.getSystemService(Context.WIFI_SERVICE)) { "No Wi-Fi service" }
        return wifi.javaClass.getMethod("getWifiApState").invokeUnwrapped(wifi) as Int
    }

    private fun onCallback(
        proxy: Any,
        method: Method,
        args: Array<out Any?>?,
        answer: AtomicInteger,
        answered: CountDownLatch,
    ): Any? = when (method.name) {
        "onTetheringStarted" -> {
            answer.set(WifiHotspotPolicy.OK)
            answered.countDown()
            null
        }
        "onTetheringFailed" -> {
            answer.set(args?.firstOrNull() as? Int ?: WifiHotspotPolicy.DID_NOT_CHANGE)
            answered.countDown()
            null
        }
        "hashCode" -> System.identityHashCode(proxy)
        "equals" -> proxy === args?.firstOrNull()
        "toString" -> "OpenTasker tethering callback"
        else -> null
    }

    private fun Method.invokeUnwrapped(target: Any?, vararg args: Any?): Any? =
        try {
            invoke(target, *args)
        } catch (failure: InvocationTargetException) {
            throw failure.targetException
        }

    private class CallerContext(base: Context, private val caller: String) : ContextWrapper(base) {
        override fun getPackageName(): String = caller
        override fun getOpPackageName(): String = caller
        override fun getAttributionTag(): String? = null
    }

    private companion object {
        const val TETHERING_MANAGER = "android.net.TetheringManager"
        const val TETHERING_SERVICE = "tethering"
        /** `TetheringManager.TETHERING_WIFI` */
        const val TETHERING_WIFI = 0
    }
}

/** Service-side entry points. Anything the framework throws becomes a code the host can word. */
internal object ShizukuTethering {
    private const val TAG = "OpenTasker.Tethering"
    private val gateway by lazy { FrameworkTetheringGateway() }

    fun set(enabled: Boolean): Int = runCatching { changeWifiHotspot(enabled, gateway) }
        .getOrElse { failure ->
            AppLogger.warn(TAG, "Couldn't change the Wi-Fi hotspot", failure)
            WifiHotspotPolicy.NOT_REACHABLE
        }

    fun read(): Int {
        if (Build.VERSION.SDK_INT < WifiHotspotPolicy.MIN_SDK) return WifiHotspotPolicy.ANDROID_TOO_OLD
        return runCatching { WifiHotspotPolicy.readState(gateway.apState()) }
            .getOrElse { failure ->
                AppLogger.warn(TAG, "Couldn't read the Wi-Fi hotspot state", failure)
                WifiHotspotPolicy.NOT_REACHABLE
            }
    }
}
