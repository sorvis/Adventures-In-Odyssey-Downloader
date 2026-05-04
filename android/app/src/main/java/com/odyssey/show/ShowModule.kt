package com.odyssey.show

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

/**
 * Multibinds every concrete ShowProvider into a Set<ShowProvider>
 * so DailyCheckWorker can iterate without knowing which providers
 * are registered. AIO is the only entry today; YSH and any future
 * RSS provider just add another @Binds @IntoSet line.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class ShowModule {

    @Binds
    @IntoSet
    abstract fun bindAioProvider(impl: AioOneplaceProvider): ShowProvider
}
