package com.odyssey.work

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Binds the DownloadEnqueuer interface to the concrete WorkScheduler
 * so DailyCheckWorker (which depends on the interface for testability)
 * can be injected at runtime.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class WorkModule {

    @Binds
    @Singleton
    abstract fun bindDownloadEnqueuer(impl: WorkScheduler): DownloadEnqueuer
}
