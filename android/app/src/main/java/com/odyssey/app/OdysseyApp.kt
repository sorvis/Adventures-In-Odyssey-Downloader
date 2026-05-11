package com.odyssey.app

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.odyssey.download.DiskLayoutMigrator
import com.odyssey.work.WorkScheduler
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class OdysseyApp : Application(), Configuration.Provider {
    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var workScheduler: WorkScheduler
    @Inject lateinit var diskLayoutMigrator: DiskLayoutMigrator

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        workScheduler.ensureDailyCheck()
        // Phone-disk layout: move legacy AIO downloads under /aio/
        // subdirectory on first launch of the YSH-aware build. Runs
        // off the main thread and is idempotent (sentinel-gated).
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            diskLayoutMigrator.migrateIfNeeded()
        }
    }
}
