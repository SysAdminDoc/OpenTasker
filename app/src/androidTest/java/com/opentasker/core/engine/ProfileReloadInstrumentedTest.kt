package com.opentasker.core.engine

import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.opentasker.app.OpenTaskerApp_NoHilt
import com.opentasker.core.contexts.ApplicationContextEvents
import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.ContextSpec
import com.opentasker.core.model.ContextType
import com.opentasker.core.model.Profile
import com.opentasker.core.model.Task
import com.opentasker.core.storage.toEntity
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Collections
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The engine reloads its profiles each time it's started again and each time one is saved. A
 * profile that was on has to come out of that on, without its enter task running a second time: an
 * unedited one keeps its matcher, an edit to its tasks goes to the matcher it has, and an edit to its
 * contexts builds a new matcher that starts out on and runs the exit task if it no longer matches.
 */
@RunWith(AndroidJUnit4::class)
class ProfileReloadInstrumentedTest {
    private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
    private val db = OpenTaskerApp_NoHilt.db
    private val name = "reload ${System.nanoTime()}"
    private var profileId = 0L
    private val taskIds = mutableListOf<Long>()

    @Before
    fun registerRecorder() {
        ActionRegistry.register(object : Action {
            override val id = RECORD
            override val category = ActionCategory.FLOW
            override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
                ran += args["label"].orEmpty()
                return ActionResult.Success
            }
        })
    }

    @After
    fun cleanUp() {
        app.stopService(Intent(app, AutomationService::class.java))
        runBlocking {
            db.profileDao().getById(profileId)?.let { db.profileDao().delete(it) }
            taskIds.forEach { id -> db.taskDao().getById(id)?.let { db.taskDao().delete(it) } }
        }
    }

    private fun window(fromMinutes: Long, toMinutes: Long): List<ContextSpec> {
        val now = LocalTime.now()
        return listOf(
            ContextSpec(
                ContextType.TIME,
                mapOf("start" to now.plusMinutes(fromMinutes).format(CLOCK), "end" to now.plusMinutes(toMinutes).format(CLOCK)),
            ),
        )
    }

    private suspend fun task(label: String): Long =
        db.taskDao().insert(
            Task(name = "$name $label", actions = listOf(ActionSpec(type = RECORD, args = mapOf("label" to "$name $label")))).toEntity(),
        ).also { taskIds += it }

    private fun startEngine() {
        ContextCompat.startForegroundService(app, Intent(app, AutomationService::class.java))
    }

    private suspend fun startWithAProfileThatIsOn(contexts: List<ContextSpec> = window(-180, 180)): Profile {
        val profile = Profile(
            name = name,
            enabled = true,
            enterTaskId = task("enter"),
            exitTaskId = task("exit"),
            contexts = contexts,
        )
        profileId = db.profileDao().insert(profile.toEntity())
        startEngine()
        awaitRuns("enter", 1)
        return profile.copy(id = profileId)
    }

    private fun runs(label: String) = synchronized(ran) { ran.count { it == "$name $label" } }

    private suspend fun awaitRuns(label: String, count: Int) {
        val deadline = System.currentTimeMillis() + RUN_TIMEOUT_MS
        while (runs(label) < count && System.currentTimeMillis() < deadline) delay(100)
        assertEquals("$label runs", count, runs(label))
    }

    @Test
    fun startingTheEngineAgainDoesNotRunTheEnterTaskAgain() = runBlocking {
        startWithAProfileThatIsOn()

        startEngine()
        delay(SETTLE_MS)

        assertEquals("enter runs", 1, runs("enter"))
        assertEquals("exit runs", 0, runs("exit"))
    }

    /**
     * The edit builds a new matcher that reads the State source afresh, and whichever event it reads
     * first, the profile comes out of it on without a task running. Needs a device with no VPN on,
     * or the enter task never runs to begin with.
     */
    @Test
    fun aStateProfileEditedToAnotherMatchStaysOn() = runBlocking {
        val noVpn = ContextSpec(ContextType.STATE, mapOf("key" to "vpn", "value" to "false"))
        val profile = startWithAProfileThatIsOn(listOf(noVpn))

        db.profileDao().upsert(profile.copy(contexts = listOf(noVpn) + window(-180, 180)).toEntity())
        delay(SETTLE_MS)

        assertEquals("enter runs", 1, runs("enter"))
        assertEquals("exit runs", 0, runs("exit"))
    }

    /** The test stands in for the app usage monitor, which stays paused without usage access. */
    @Test
    fun aTaskEditKeepsTheProfileOnAndItsNextExitRunsTheNewTask() = runBlocking {
        ApplicationContextEvents.publishForeground(TEST_APP)
        val profile = startWithAProfileThatIsOn(listOf(ContextSpec(ContextType.APPLICATION, mapOf("package" to TEST_APP))))

        db.profileDao().upsert(profile.copy(exitTaskId = task("new exit")).toEntity())
        delay(SETTLE_MS)
        ApplicationContextEvents.publishForeground(OTHER_APP)
        awaitRuns("new exit", 1)

        assertEquals("enter runs", 1, runs("enter"))
        assertEquals("exit runs", 0, runs("exit"))
    }

    @Test
    fun anEditThatNoLongerMatchesRunsTheExitTask() = runBlocking {
        val profile = startWithAProfileThatIsOn()

        db.profileDao().upsert(profile.copy(contexts = window(300, 420)).toEntity())
        awaitRuns("exit", 1)
        delay(SETTLE_MS)

        assertEquals("enter runs", 1, runs("enter"))
        assertEquals("exit runs", 1, runs("exit"))
    }

    private companion object {
        const val RECORD = "test.reload.record"
        const val RUN_TIMEOUT_MS = 20_000L
        const val SETTLE_MS = 5_000L
        const val TEST_APP = "com.opentasker.reload.test.first"
        const val OTHER_APP = "com.opentasker.reload.test.second"
        val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
        val ran: MutableList<String> = Collections.synchronizedList(mutableListOf())
    }
}
