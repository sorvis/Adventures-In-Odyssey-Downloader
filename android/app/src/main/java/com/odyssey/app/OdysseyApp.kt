package com.odyssey.app

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.odyssey.data.local.EpisodeDao
import com.odyssey.download.DiskLayoutMigrator
import com.odyssey.download.DownloadReconciler
import com.odyssey.nas.NasMirror
import com.odyssey.show.YshCatalog
import com.odyssey.show.backfillYshAlbums
import com.odyssey.work.WorkScheduler
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class OdysseyApp : Application(), Configuration.Provider {
    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var workScheduler: WorkScheduler
    @Inject lateinit var diskLayoutMigrator: DiskLayoutMigrator
    @Inject lateinit var yshCatalog: YshCatalog
    @Inject lateinit var downloadReconciler: DownloadReconciler
    @Inject lateinit var settings: SettingsRepo
    @Inject lateinit var nasMirror: NasMirror
    @Inject lateinit var episodes: EpisodeDao

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
            // provider's title-join hits work from launch.
            yshCatalog.load()
            // Fresh installs: kick a one-shot catalog refresh so YSH
            // ingestion has a populated index on the first daily
            // check, instead of waiting up to a week for the periodic
            // worker. Cheap (few-KB JSON), idempotent (worker is
            // unique-named); no-op when the cache is already warm.
            if (yshCatalog.state.value == null) {
                workScheduler.runYshCatalogRefreshNow()
            } else {
                // Warm cache already loaded: backfill album metadata onto
                // any pre-v0.1.84 YSH rows that were ingested without it,
                // so "Go to album" resolves for episodes already on the
                // phone. Fresh-refresh installs get this via the worker.
                backfillYshAlbums(episodes, yshCatalog.state.value!!)
            }
            // Recover from "file is fully on disk but filePath is null
            // in DB" stuck states left by pre-v0.1.51 download workers
            // that died mid-flight. Re-enqueues with cancellation to
            // break WorkManager's exponential backoff so the new
            // 416-recovery path runs immediately instead of waiting
            // hours for the next backoff tick. Idempotent — does
            // nothing when there are no orphans.
            val allowMetered = settings.flow.first().allowMeteredDownloads
            downloadReconciler.reconcile(allowMetered)
            // Sweep out any AIO rows whose downloadUrl belongs to a
            // different oneplace show — leaked from pre-v0.1.59 ingest
            // before the AioOneplaceProvider showId filter landed.
            // Idempotent; no-ops on a clean DB.
            downloadReconciler.cleanupCrossShowContamination()
            // Opportunistic NAS mirror so the local DB reflects the
            // full backup catalog without forcing the user to open
            // Sync first (v0.1.67). Albums' "on backup" counts and
            // the "stream from backup" path on Album detail rely on
            // local rows existing for NAS-side episodes. Skipped when
            // no NAS is configured (nothing to mirror) or when NAS is
            // unreachable on this network (silent — runs again next
            // launch). NasMirror returns Result, so the failure path
            // is just a logged warning, not a crash.
            if (settings.flow.first().nasConfigured) {
                nasMirror.run().onFailure {
                    com.odyssey.debug.DebugLogger.w(
                        "OdysseyApp",
                        "launch NAS mirror failed (likely off-LAN); will retry next launch",
                        it,
                    )
                }
            }
        }
    }
}
