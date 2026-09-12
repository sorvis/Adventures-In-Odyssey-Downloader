package com.odyssey.work

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Binds the enqueue interfaces to the concrete WorkScheduler so the
 * callers that depend on them for testability — DailyCheckWorker
 * (downloads), ArchiveBackfill (archives), PlaybackRecovery (both
 * downloads and restores) — can be injected at runtime.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class WorkModule {

    @Binds
    @Singleton
    abstract fun bindDownloadEnqueuer(impl: WorkScheduler): DownloadEnqueuer

    @Binds
    @Singleton
    abstract fun bindArchiveEnqueuer(impl: WorkScheduler): ArchiveEnqueuer

    @Binds
    @Singleton
    abstract fun bindRestoreEnqueuer(impl: WorkScheduler): RestoreEnqueuer
}
