package com.odyssey.app

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.odyssey.download.DiskLayoutMigrator
import com.odyssey.show.YshCatalog
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
    @Inject lateinit var yshCatalog: YshCatalog

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        workScheduler.ensureDailyCheck()
        workScheduler.ensureYshCatalogRefresh()
        // Phone-disk layout: move legacy AIO downloads under /aio/
        // subdirectory on first launch of the YSH-aware build. Runs
        // off the main thread and is idempotent (sentinel-gated).
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            diskLayoutMigrator.migrateIfNeeded()
            // Load any cached YSH album catalog into memory so the
            // provider's title-join hits work from launch. Refresh
            // worker overwrites this asynchronously.
            yshCatalog.load()
        }
    }
}
