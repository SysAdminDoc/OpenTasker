package com.opentasker.core.external

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.opentasker.app.OpenTaskerApp_NoHilt
import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.ContextSpec
import com.opentasker.core.model.ContextType
import com.opentasker.core.model.Profile
import com.opentasker.core.model.Task
import com.opentasker.core.storage.toEntity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The exported profile switch, sent ordered the way the Locale plugin host sends it. It runs against
 * the app's own database, so everything it saves is deleted again, and its profile watches an app
 * that isn't installed so it never fires while on.
 */
@RunWith(AndroidJUnit4::class)
class ProfileEnableBroadcastInstrumentedTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun aProfileWhoseTaskCallsItselfStaysOffAndTheSameProfileOnAPlainTaskSwitches() = runBlocking {
        val db = OpenTaskerApp_NoHilt.db
        val tag = "OT broadcast test ${System.nanoTime()}"
        // Ids start at 0, which matches no row, so cleanup works however far setup got.
        var loopId = 0L
        var plainId = 0L
        var profileId = 0L
        try {
            loopId = db.taskDao().insert(Task(name = "$tag first", actions = emptyList()).toEntity())
            plainId = db.taskDao().insert(
                Task(name = "$tag plain", actions = listOf(ActionSpec(type = "notify.show", args = mapOf("title" to "Hi"))))
                    .toEntity(),
            )
            db.taskDao().update(
                Task(id = loopId, name = "$tag first", actions = listOf(ActionSpec(type = "task.run", args = mapOf("task" to "$loopId"))))
                    .toEntity(),
            )
            profileId = db.profileDao().insert(
                Profile(
                    name = "$tag profile",
                    enabled = false,
                    contexts = listOf(ContextSpec(ContextType.APPLICATION, mapOf("package" to "com.example.never.installed"))),
                    enterTaskId = loopId,
                ).toEntity(),
            )

            val (refusedCode, refused) = switch(profileId, enabled = true)
            assertEquals(Activity.RESULT_CANCELED, refusedCode)
            assertTrue(refused.toString(), refused?.getString(AutomationTargetContract.EXTRA_ERROR).orEmpty().contains("in a loop ("))
            assertFalse(db.profileDao().getById(profileId)!!.enabled)

            val saved = db.profileDao().getById(profileId)!!
            db.profileDao().update(saved.copy(enterTaskId = plainId))
            val (onCode, _) = switch(profileId, enabled = true)
            assertEquals(Activity.RESULT_OK, onCode)
            assertEquals(saved.copy(enterTaskId = plainId, enabled = true), db.profileDao().getById(profileId))

            val (offCode, _) = switch(profileId, enabled = false)
            assertEquals(Activity.RESULT_OK, offCode)
            assertFalse(db.profileDao().getById(profileId)!!.enabled)
        } finally {
            db.profileDao().getById(profileId)?.let { db.profileDao().delete(it) }
            listOf(loopId, plainId).forEach { id -> db.taskDao().getById(id)?.let { db.taskDao().delete(it) } }
        }
    }

    private suspend fun switch(profileId: Long, enabled: Boolean): Pair<Int, Bundle?> {
        val result = CompletableDeferred<Pair<Int, Bundle?>>()
        context.sendOrderedBroadcast(
            Intent(AutomationTargetContract.ACTION_SET_PROFILE_ENABLED)
                .setPackage(context.packageName)
                .putExtra(AutomationTargetContract.EXTRA_PROFILE_ID, profileId)
                .putExtra(AutomationTargetContract.EXTRA_ENABLED, enabled),
            AutomationTargetContract.PERMISSION,
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    result.complete(resultCode to getResultExtras(false))
                }
            },
            null,
            UNTOUCHED_CODE,
            null,
            null,
        )
        return withTimeout(DELIVERY_TIMEOUT_MS) { result.await() }
    }

    private companion object {
        const val UNTOUCHED_CODE = 4242
        const val DELIVERY_TIMEOUT_MS = 10_000L
    }
}
