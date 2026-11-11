package com.opentasker.app

import android.app.Application
import android.os.Build
import android.os.StrictMode
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.opentasker.core.registerCoreRuntime
import com.opentasker.core.actions.registerActionMetadata
import com.opentasker.core.logging.AppLogger
import com.opentasker.core.storage.AppDatabase
import com.opentasker.core.storage.AppDatabaseProvider
import com.opentasker.core.storage.ConfigurationSnapshotWorker
import com.opentasker.core.updates.UpdateCheckWorker
import com.opentasker.core.storage.DatabaseBackupManager
import com.opentasker.core.storage.DatabaseMigrations
import com.opentasker.core.storage.DatabaseSecurity
import com.opentasker.core.storage.PendingRestoreApplyResult
import com.opentasker.core.storage.VariableRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import com.opentasker.core.diagnostics.CrashLogHandler
import com.opentasker.core.diagnostics.AdvancedProtectionReader
import com.opentasker.core.engine.RunLogPruneWorker
import com.opentasker.core.engine.EngineWatchdogWorker
import com.opentasker.core.engine.reconcileExecutionJournal
import com.opentasker.core.engine.DirectBootTriggerStore
import com.opentasker.core.platform.AppVisibilityTracker
import com.opentasker.core.plugins.locale.LocaleConditionGrantStore
import com.opentasker.core.power.ShizukuPowerBackend
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Application singleton keeps startup deterministic while Hilt is not active.
/** The steps of preparing the database that can take long enough to see. */
enum class StartupStage {
    Opening,
    Restoring,
    Encrypting,
}

class OpenTaskerApp_NoHilt : Application() {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        @Volatile
        private var _db: AppDatabase? = null
        private val databaseReady = CountDownLatch(1)

        /**
         * Wall clock at process start, used to tell journal rows this process wrote apart from
         * rows an earlier process left behind.
         */
        internal val processStartedAtMs: Long = System.currentTimeMillis()

        /**
         * Blocks until startup finishes preparing the database.
         *
         * Preparation (applying a staged restore, then a possible plaintext→SQLCipher migration)
         * is seconds of disk I/O and crypto on a large database, so it runs off the main thread —
         * `Application.onCreate` and the 10 s boot-broadcast budget must not pay for it. Callers
         * that genuinely need the database wait here instead; Room already forbids main-thread
         * queries, so that wait belongs on a worker.
         */
        val db: AppDatabase
            get() {
                _db?.let { return it }
                databaseReady.await(DATABASE_READY_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                return _db ?: throw IllegalStateException("Database not initialized.")
            }

        /** The database if preparation has finished, without waiting. */
        val readyDb: AppDatabase?
            get() = _db

        private val _startupStage = MutableStateFlow(StartupStage.Opening)

        /** What preparation is doing, so the launch screen can say so instead of just spinning. */
        val startupStage: StateFlow<StartupStage> = _startupStage.asStateFlow()

        /**
         * Suspends until the database is ready, without blocking the caller's thread.
         *
         * UI must use this rather than [db]: the blocking getter waits up to 30 seconds, and the
         * launch that pays that cost is exactly the one after staging a restore - a full-file copy
         * of up to 100 MB plus a possible cipher migration - so composing against [db] parked the
         * main thread through the slowest start the app has, and threw if the deadline expired.
         */
        suspend fun awaitDb(): AppDatabase {
            _db?.let { return it }
            return withContext(Dispatchers.IO) { db }
        }

        private const val DATABASE_READY_TIMEOUT_SECONDS = 30L
    }

    @Volatile
    private var unlockedInitialized = false

    override fun onCreate() {
        super.onCreate()
        installStrictModeInDebug()
        if (DirectBootTriggerStore.isUserUnlocked(this)) {
            initializeAfterUnlock()
        }
    }

    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        if (level == TRIM_MEMORY_COMPLETE) {
            ShizukuPowerBackend.shutdown()
        }
        super.onTrimMemory(level)
    }

    @Suppress("DEPRECATION")
    override fun onTerminate() {
        ShizukuPowerBackend.shutdown()
        super.onTerminate()
    }

    /** Initializes credential-protected runtime state after the user has unlocked the device. */
    fun initializeAfterUnlock() {
        if (!DirectBootTriggerStore.isUserUnlocked(this)) return
        synchronized(this) {
            if (unlockedInitialized) return

            CrashLogHandler.install(this)
            AdvancedProtectionReader.start(this)
            AppVisibilityTracker.register(this)
            ShizukuPowerBackend.initialize(this)
            registerActionMetadata()
            registerCoreRuntime()

            applicationScope.launch {
                try {
                    if (_db == null) prepareDatabase()
                } finally {
                    databaseReady.countDown()
                }

                runCatching {
                    val recovery = reconcileExecutionJournal(db, processStartedAtMs = processStartedAtMs)
                    if (recovery.interrupted > 0 || recovery.logsWritten > 0) {
                        AppLogger.warn(
                            "OpenTasker",
                            "Recovered ${recovery.interrupted} interrupted execution(s); " +
                                "wrote ${recovery.logsWritten} durable run-log record(s)",
                        )
                    }
                }.onFailure { error ->
                    AppLogger.error("OpenTasker", "Execution journal recovery failed", error)
                }
                runCatching {
                    VariableRepository(db.variableDao()).migrateLegacySensitiveVariables()
                }.onFailure { error ->
                    AppLogger.error("OpenTasker", "Legacy secret migration failed", error)
                }
            }

            RunLogPruneWorker.enqueue(this)
            ConfigurationSnapshotWorker.enqueueIfEnabled(this)
            UpdateCheckWorker.enqueueIfEnabled(this)
            EngineWatchdogWorker.enqueue(this)
            unlockedInitialized = true
        }
    }

    /** Applies any staged restore, migrates a legacy plaintext file, then publishes the database. */
    private fun prepareDatabase() {
        if (DatabaseBackupManager.pendingRestoreFile(this).exists()) _startupStage.value = StartupStage.Restoring
        when (val restoreResult = DatabaseBackupManager.applyPendingRestoreIfPresent(this)) {
            is PendingRestoreApplyResult.Applied -> {
                LocaleConditionGrantStore(this).revokeAllContextGrants()
                AppLogger.info(
                    "OpenTasker",
                    "Applied pending database restore to ${restoreResult.databaseFile.name}; " +
                        "the database it replaced is kept as ${restoreResult.previousBackup?.name ?: "nothing (none existed or it failed validation)"}",
                )
            }
            is PendingRestoreApplyResult.Failed -> {
                AppLogger.error("OpenTasker", "Pending database restore failed", restoreResult.exception)
            }
            PendingRestoreApplyResult.NoPending -> Unit
        }

        _startupStage.value = StartupStage.Opening
        val databaseKey = DatabaseSecurity.prepareEncryptedDatabase(this, DatabaseBackupManager.DATABASE_NAME) {
            _startupStage.value = StartupStage.Encrypting
        }
        _db = Room.databaseBuilder(
            this,
            AppDatabase::class.java,
            DatabaseBackupManager.DATABASE_NAME,
        )
            .addMigrations(*DatabaseMigrations.getManualMigrations())
            .addCallback(object : RoomDatabase.Callback() {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    // Fresh installs skip every migration, so the default workspace has to
                    // be seeded here as well as in MIGRATION_8_9.
                    db.execSQL(DatabaseMigrations.SEED_DEFAULT_PROJECT)
                }
            })
            .openHelperFactory(SupportOpenHelperFactory(databaseKey.copyOf()))
            .build().also(AppDatabaseProvider::publish)
    }

    /**
     * In debug builds, surface accidental main-thread disk/network I/O and leaked closeables or
     * receivers/services to logcat (never crashes the app). Helps keep the automation engine's
     * work off the UI thread as the codebase evolves.
     */
    @Suppress("NewApi")
    private fun installStrictModeInDebug() {
        if (!BuildConfig.DEBUG) return
        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy.Builder()
                .detectDiskReads()
                .detectDiskWrites()
                .detectNetwork()
                .penaltyLog()
                .build(),
        )
        StrictMode.setVmPolicy(
            StrictMode.VmPolicy.Builder()
                .detectLeakedClosableObjects()
                .detectLeakedRegistrationObjects()
                .apply {
                    // Flags unsafe intent launches (the classic intent-redirection sink) in debug;
                    // available from Android 12 (API 31).
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        detectUnsafeIntentLaunch()
                    }
                    // Android 17 (API 37) still supplies implicit URI grants for a few
                    // Sharesheet/camera intents. Surface those call sites in debug before
                    // Android 18 removes the compatibility grant.
                    if (Build.VERSION.SDK_INT >= 37) {
                        detectImplicitUriPermissionGrant()
                    }
                }
                .penaltyLog()
                .build(),
        )
    }
}
