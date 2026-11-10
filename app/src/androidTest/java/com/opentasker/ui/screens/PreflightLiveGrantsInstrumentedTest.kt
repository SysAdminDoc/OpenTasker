package com.opentasker.ui.screens

import android.os.ParcelFileDescriptor
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.opentasker.core.capabilities.SetupRequirement
import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.Task
import com.opentasker.core.storage.AppDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A-401: Preflight from the view model reads the device's grants, so a Write Settings grant made
 * in Settings stops showing as missing. Write Settings is an app-op, so flipping it doesn't
 * restart the app under test.
 */
@RunWith(AndroidJUnit4::class)
class PreflightLiveGrantsInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val store = ViewModelStore()
    private lateinit var db: AppDatabase
    private var writeSettingsMode = "default"

    @Before
    fun setUp() {
        // "No operations." when the op was never set, which is the default mode.
        writeSettingsMode = shell("appops get ${context.packageName} WRITE_SETTINGS").lineSequence()
            // A "Uid mode:" line can come first, and appops set below changes the package mode.
            .filterNot { it.trimStart().startsWith("Uid mode:") }
            .firstNotNullOfOrNull { Regex("WRITE_SETTINGS: (\\w+)").find(it)?.groupValues?.get(1) } ?: "default"
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
    }

    @After
    fun tearDown() {
        store.clear()
        db.close()
        shell("appops set ${context.packageName} WRITE_SETTINGS $writeSettingsMode")
    }

    @Test
    fun aWriteSettingsGrantMadeInSettingsIsNotListedAsMissing() = runBlocking<Unit> {
        shell("appops set ${context.packageName} WRITE_SETTINGS allow")

        val report = preflight(DIM)

        assertEquals(setOf(SetupRequirement.WRITE_SETTINGS), report.setupRequirements)
        assertTrue(report.missingSetupRequirements.toString(), report.missingSetupRequirements.isEmpty())
    }

    @Test
    fun aDeniedWriteSettingsGrantIsListedAsMissing() = runBlocking<Unit> {
        shell("appops set ${context.packageName} WRITE_SETTINGS deny")

        val report = preflight(DIM)

        assertEquals(setOf(SetupRequirement.WRITE_SETTINGS), report.missingSetupRequirements)
    }

    private suspend fun preflight(task: Task) = withTimeout(TIMEOUT_MS) {
        val viewModel = ViewModelProvider(store, ActiveAutomationViewModelFactory(db, context))[ActiveAutomationViewModel::class.java]
        viewModel.previewTaskPreflight(task)
        viewModel.preflightReview.first { it != null }!!.report
    }

    private fun shell(command: String): String {
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        // The command completes only once its output reaches EOF, so drain it before reading grants.
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes().decodeToString() }
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
        val DIM = Task(id = 4, name = "Dim", actions = listOf(ActionSpec(type = "brightness.set", args = mapOf("level" to "40"))))
    }
}
