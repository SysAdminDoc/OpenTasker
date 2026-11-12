package com.opentasker.core.contexts

import android.content.Context
import android.os.SystemClock
import com.opentasker.core.logging.AppLogger
import com.opentasker.core.model.ContextType
import com.opentasker.core.model.Profile
import com.opentasker.core.plugins.locale.LocalePluginHost
import com.opentasker.core.plugins.locale.LocalePluginRequest
import com.opentasker.core.plugins.locale.LocalePluginRequestQueryEvents
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.withTimeoutOrNull

class LocalePluginConditionContextSource : ContextSource {
    override val type = "plugin"

    /**
     * One poll loop for the whole process, shared by every PLUGIN context.
     *
     * Each collector used to start its own loop that queried *all* registered subscriptions, so N
     * plugin contexts issued N x N ordered broadcasts every interval - each one holding a
     * broadcast and a timeout on the plugin app. The results are identical for every collector,
     * which is exactly what a shared upstream is for.
     */
    override fun events(app: Context): Flow<ContextEvent> = sharedEvents(app.applicationContext)

    private fun pollEvents(app: Context): Flow<ContextEvent> = callbackFlow {
        val host = LocalePluginHost(app)
        val plan = PluginConditionPollPlan()
        val wake = Channel<Unit>(Channel.CONFLATED)
        // A plugin sends REQUEST_QUERY when its answer may have changed. Without this its profile
        // waited for the next 30 second poll.
        val requestJob = launch {
            LocalePluginRequestQueryEvents.events(app).collect { event ->
                val activityClass = event.metadata["activityClass"] ?: return@collect
                val subscribed = PluginConditionSubscriptions.snapshot().map { it.packageName }
                val asking = packagesAskingForQuery(activityClass, subscribed, host::conditionEditActivityClasses)
                if (asking.isEmpty()) return@collect
                plan.request(asking)
                wake.trySend(Unit)
            }
        }
        val pollJob = launch {
            while (isActive) {
                val due = plan.take(SystemClock.elapsedRealtime())
                    ?.let { pass -> PluginConditionSubscriptions.snapshot().filter { pass.includes(it.packageName) } }
                    .orEmpty()
                for (sub in due) {
                    if (!isActive) break
                    val result = try {
                        host.queryCondition(
                            LocalePluginRequest(
                                packageName = sub.packageName,
                                bundleJson = sub.bundleJson,
                                blurb = sub.blurb,
                                timeoutMs = sub.timeoutMs,
                            )
                        )
                    } catch (ex: Exception) {
                        AppLogger.warn(TAG, "Plugin condition query failed for ${sub.packageName}: ${ex.message}")
                        continue
                    }
                    trySend(
                        ContextEvent(
                            type = "plugin",
                            matched = true,
                            metadata = mapOf(
                                "package" to sub.packageName,
                                "bundleJson" to sub.bundleJson,
                                "state" to result.state.serializedName,
                                "message" to result.message,
                            ) + listOfNotNull(sub.blurb.takeIf(String::isNotEmpty)?.let { "blurb" to it }),
                        )
                    )
                }
                withTimeoutOrNull(plan.waitMs(SystemClock.elapsedRealtime())) { wake.receive() }
            }
        }
        awaitClose {
            pollJob.cancel()
            requestJob.cancel()
        }
    }

    companion object {
        private const val TAG = "PluginConditionSource"

        private val sharedScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val sharedLock = Any()
        private var shared: Flow<ContextEvent>? = null

        private fun LocalePluginConditionContextSource.sharedEvents(app: Context): Flow<ContextEvent> =
            synchronized(sharedLock) {
                shared ?: pollEvents(app)
                    .shareIn(
                        scope = sharedScope,
                        // Stops polling once the last context unsubscribes, and survives a brief
                        // gap while the engine rebuilds matchers on a profile reload.
                        started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000),
                        replay = 0,
                    )
                    .also { shared = it }
            }
    }
}

/**
 * When the shared plugin poll queries whom. Every subscription is queried each [fullIntervalMs].
 * A plugin that sends REQUEST_QUERY is queried on its own in between, at most once per
 * [requeryGapMs] (Locale lets a host throttle a plugin that keeps asking), and asking never moves
 * the next full pass, so a busy plugin can't hold back the others.
 */
internal class PluginConditionPollPlan(
    private val fullIntervalMs: Long = 30_000L,
    private val requeryGapMs: Long = 1_000L,
) {
    /** One pass over the subscriptions; [packages] null means all of them. */
    data class Pass(val packages: Set<String>?) {
        fun includes(packageName: String): Boolean = packages == null || packageName in packages
    }

    private var nextFullAt: Long? = null
    private var lastPassAt = 0L
    private val asked = linkedSetOf<String>()

    @Synchronized
    fun request(packages: Collection<String>) {
        asked += packages
    }

    /** The pass to run at [now], or null when nothing is due. */
    @Synchronized
    fun take(now: Long): Pass? {
        val fullDue = nextFullAt?.let { now >= it } ?: true
        val pass = when {
            fullDue -> Pass(null).also { nextFullAt = now + fullIntervalMs }
            asked.isNotEmpty() && now >= lastPassAt + requeryGapMs -> Pass(asked.toSet())
            else -> return null
        }
        asked.clear()
        lastPassAt = now
        return pass
    }

    /** How long after [now] the next pass is due, unless a request comes in first. */
    @Synchronized
    fun waitMs(now: Long): Long {
        val fullAt = nextFullAt ?: return 0
        val dueAt = if (asked.isEmpty()) fullAt else minOf(fullAt, lastPassAt + requeryGapMs)
        return (dueAt - now).coerceAtLeast(0)
    }
}

/**
 * Which of [packageNames] a REQUEST_QUERY naming [activityClass] comes from. Locale's API names
 * the plugin only by the class of its condition edit screen, so that class is matched against
 * each subscribed package's own ([conditionEditClasses]).
 */
internal fun packagesAskingForQuery(
    activityClass: String,
    packageNames: Collection<String>,
    conditionEditClasses: (packageName: String) -> Collection<String>,
): Set<String> = packageNames.filterTo(linkedSetOf()) { activityClass in conditionEditClasses(it) }

/** The query each enabled profile's Plugin contexts need, one per distinct plugin question. */
fun pluginConditionSubscriptions(profiles: List<Profile>): Set<PluginConditionSubscription> =
    profiles.flatMap { profile ->
        profile.contexts
            .filter { it.type == ContextType.PLUGIN }
            .mapNotNull { spec ->
                val pkg = spec.config["package"]?.trim().orEmpty()
                if (pkg.isBlank()) return@mapNotNull null
                PluginConditionSubscription(
                    packageName = pkg,
                    bundleJson = spec.config["bundleJson"]?.trim().orEmpty().ifBlank { "{}" },
                    timeoutMs = spec.config["timeoutMs"]?.toLongOrNull()?.coerceIn(1_000, 30_000) ?: 5_000,
                    blurb = ContextMatchEvaluator.pluginConditionBlurb(spec.config),
                )
            }
    }.toSet()

data class PluginConditionSubscription(
    val packageName: String,
    val bundleJson: String = "{}",
    val timeoutMs: Long = 5_000,
    /** Sent with the query; some plugins pick their condition by it. */
    val blurb: String = "",
)

object PluginConditionSubscriptions {
    private val subscriptions = mutableSetOf<PluginConditionSubscription>()

    @Synchronized
    fun register(sub: PluginConditionSubscription) {
        subscriptions.add(sub)
    }

    @Synchronized
    fun replaceAll(subs: Collection<PluginConditionSubscription>) {
        subscriptions.clear()
        subscriptions.addAll(subs)
    }

    @Synchronized
    fun clear() {
        subscriptions.clear()
    }

    @Synchronized
    fun snapshot(): List<PluginConditionSubscription> = subscriptions.toList()
}
