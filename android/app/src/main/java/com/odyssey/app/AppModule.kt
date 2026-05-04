package com.odyssey.app

import android.content.Context
import androidx.room.Room
import com.odyssey.data.local.MIGRATION_1_2
import com.odyssey.data.local.MIGRATION_2_3
import com.odyssey.data.local.OdysseyDb
import com.odyssey.data.local.EpisodeDao
import com.odyssey.data.local.PlaybackDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides @Singleton
    fun provideDb(@ApplicationContext ctx: Context): OdysseyDb =
        Room.databaseBuilder(ctx, OdysseyDb::class.java, "odyssey.db")
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
            .build()

    @Provides fun provideEpisodeDao(db: OdysseyDb): EpisodeDao = db.episodes()
    @Provides fun providePlaybackDao(db: OdysseyDb): PlaybackDao = db.playback()

    @Provides @Singleton
    fun provideOkHttp(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .build()
}
