package com.odyssey.show

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

/**
 * Multibinds every concrete ShowProvider into a Set<ShowProvider>
 * so DailyCheckWorker can iterate without knowing which providers
 * are registered. AIO + YSH free-streaming pool today; the
 * YshOneplaceProvider lands in step 7 with the same `id = "ysh"`
 * so the same story coming from either source dedupes by externalId.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class ShowModule {

    @Binds
    @IntoSet
    abstract fun bindAioProvider(impl: AioOneplaceProvider): ShowProvider

    @Binds
    @IntoSet
    abstract fun bindYshFreeStreamProvider(impl: YshFreeStreamProvider): ShowProvider

    @Binds
    @IntoSet
    abstract fun bindYshOneplaceProvider(impl: YshOneplaceProvider): ShowProvider
}
