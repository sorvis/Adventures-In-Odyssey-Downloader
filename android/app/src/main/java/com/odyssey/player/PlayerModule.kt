package com.odyssey.player

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Binds the Player interface to the concrete PlayerController so RecentVm
 * (which depends on Player for testability) can be injected at runtime.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class PlayerModule {

    @Binds
    @Singleton
    abstract fun bindEpisodePlayer(impl: PlayerController): EpisodePlayer

    /**
     * The up-next chime. Bound rather than constructed inside
     * UpNextAnnouncer so the announcer stays free of Android types and
     * keeps running in the bare-kotlinc test lane.
     */
    @Binds
    abstract fun bindChime(impl: ToneChime): Chime
}
