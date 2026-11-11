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
import com.opentasker.core.model.ProfileLifetime
import com.opentasker.core.model.Task
import com.opentasker.core.storage.toEntity
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Collections
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A profile with an exit task and no enter task goes on without running anything, and runs its
 * exit task once when its contexts stop matching. The test stands in for the app usage monitor,
 * which stays paused without usage access.
 */
@RunWith(AndroidJUnit4::class)
class ExitOnlyProfileInstrumentedTest {
    private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
    private val db = OpenTaskerApp_NoHilt.db
    private val name = "exit only ${System.nanoTime()}"
    private var profileId = 0L
    private var taskId = 0L
    private var addedEnterTaskId = 0L

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
            db.taskDao().getById(taskId)?.let { db.taskDao().delete(it) }
            db.taskDao().getById(addedEnterTaskId)?.let { db.taskDao().delete(it) }
        }
    }

    private fun startEngine() {
        ContextCompat.startForegroundService(app, Intent(app, AutomationService::class.java))
    }

    private suspend fun startWithAnExitOnlyProfileThatIsOn(lifetime: ProfileLifetime = ProfileLifetime.NEVER) {
        ApplicationContextEvents.publishForeground(TEST_APP)
        taskId = db.taskDao().insert(
            Task(name = "$name exit", actions = listOf(ActionSpec(type = RECORD, args = mapOf("label" to name)))).toEntity(),
        )
        profileId = db.profileDao().insert(
            Profile(
                name = name,
                enabled = true,
                enterTaskId = 0,
                exitTaskId = taskId,
                contexts = listOf(ContextSpec(ContextType.APPLICATION, mapOf("package" to TEST_APP))),
                lifetime = lifetime,
            ).toEntity(),
        )
        startEngine()
        val deadline = System.currentTimeMillis() + RUN_TIMEOUT_MS
        while (AutomationLiveConditionState.profileState(profileId) != true && System.currentTimeMillis() < deadline) delay(100)
        assertEquals("profile on", true, AutomationLiveConditionState.profileState(profileId))
    }

    private fun runs() = synchronized(ran) { ran.count { it == name } }

    private suspend fun awaitRuns(count: Int) {
        val deadline = System.currentTimeMillis() + RUN_TIMEOUT_MS
        while (runs() < count && System.currentTimeMillis() < deadline) delay(100)
        assertEquals("exit runs", count, runs())
    }

    @Test
    fun goingOnRunsNothingAndGoingOffRunsTheExitTaskOnce() = runBlocking {
        startWithAnExitOnlyProfileThatIsOn()
        delay(SETTLE_MS)
        assertEquals("exit runs while on", 0, runs())

        ApplicationContextEvents.publishForeground(OTHER_APP)
        awaitRuns(1)
        delay(SETTLE_MS)

        assertEquals("exit runs", 1, runs())
    }

    @Test
    fun startingTheEngineAgainWhileOnRunsNothingAndKeepsItOn() = runBlocking {
        startWithAnExitOnlyProfileThatIsOn()

        startEngine()
        delay(SETTLE_MS)
        assertEquals("exit runs after the restart", 0, runs())

        ApplicationContextEvents.publishForeground(OTHER_APP)
        awaitRuns(1)
    }

    @Test
    fun aOneShotProfileIsUsedUpWhenItsExitTaskRuns() = runBlocking {
        startWithAnExitOnlyProfileThatIsOn(ProfileLifetime.ONCE)
        delay(SETTLE_MS)
        val whileOn = db.profileDao().getById(profileId)!!
        assertTrue("still on while it matches", whileOn.enabled && !whileOn.lifetimeConsumed)

        ApplicationContextEvents.publishForeground(OTHER_APP)
        awaitRuns(1)
        val used = db.profileDao().getById(profileId)!!
        assertTrue("used up", used.lifetimeConsumed)
        assertFalse("turned off", used.enabled)

        ApplicationContextEvents.publishForeground(TEST_APP)
        delay(SETTLE_MS)
        ApplicationContextEvents.publishForeground(OTHER_APP)
        delay(SETTLE_MS)
        assertEquals("exit runs", 1, runs())
    }

    @Test
    fun aOneShotProfileGivenAnEnterTaskWhileOnIsStillUsedUpWhenItGoesOff() = runBlocking {
        startWithAnExitOnlyProfileThatIsOn(ProfileLifetime.ONCE)
        val enterLabel = "$name enter"
        addedEnterTaskId = db.taskDao().insert(
            Task(name = enterLabel, actions = listOf(ActionSpec(type = RECORD, args = mapOf("label" to enterLabel)))).toEntity(),
        )
        val profile = db.profileDao().getById(profileId)!!.toDomain()
        db.profileDao().upsert(profile.copy(enterTaskId = addedEnterTaskId).toEntity())
        delay(SETTLE_MS)

        ApplicationContextEvents.publishForeground(OTHER_APP)
        awaitRuns(1)
        val used = db.profileDao().getById(profileId)!!
        assertTrue("used up", used.lifetimeConsumed)
        assertFalse("turned off", used.enabled)

        ApplicationContextEvents.publishForeground(TEST_APP)
        delay(SETTLE_MS)
        assertEquals("enter runs", 0, synchronized(ran) { ran.count { it == enterLabel } })
        assertEquals("exit runs", 1, runs())
    }

    @Test
    fun anEditThatStillMatchesKeepsItOnAndRunsNothing() = runBlocking {
        startWithAnExitOnlyProfileThatIsOn()
        val profile = db.profileDao().getById(profileId)!!.toDomain()
        val now = LocalTime.now()
        val aroundNow = ContextSpec(
            ContextType.TIME,
            mapOf("start" to now.minusMinutes(180).format(CLOCK), "end" to now.plusMinutes(180).format(CLOCK)),
        )

        db.profileDao().upsert(profile.copy(contexts = profile.contexts + aroundNow).toEntity())
        delay(SETTLE_MS)
        assertEquals("exit runs after the edit", 0, runs())
        assertEquals("profile on", true, AutomationLiveConditionState.profileState(profileId))

        ApplicationContextEvents.publishForeground(OTHER_APP)
        awaitRuns(1)
    }

    @Test
    fun anEditThatNoLongerMatchesRunsTheExitTaskOnce() = runBlocking {
        startWithAnExitOnlyProfileThatIsOn()
        val profile = db.profileDao().getById(profileId)!!.toDomain()

        db.profileDao().upsert(profile.copy(contexts = listOf(ContextSpec(ContextType.APPLICATION, mapOf("package" to OTHER_APP)))).toEntity())
        awaitRuns(1)
        delay(SETTLE_MS)

        assertEquals("exit runs", 1, runs())
    }

    private companion object {
        val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
        const val RECORD = "test.exitonly.record"
        const val RUN_TIMEOUT_MS = 20_000L
        const val SETTLE_MS = 5_000L
        const val TEST_APP = "com.opentasker.exitonly.test.first"
        const val OTHER_APP = "com.opentasker.exitonly.test.second"
        val ran: MutableList<String> = Collections.synchronizedList(mutableListOf())
    }
}
