package com.odyssey.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.odyssey.show.YshCatalog
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Refreshes the YSH album catalog from yourstoryhour.org once a week
 * (or on demand from Settings). Cheap: small JSON, 5 pages today,
 * survives weeks of staleness without breaking provider behavior.
 *
 * Failure is non-fatal — providers fall back to the on-disk cache if
 * one exists, or to the unmatched-titles flow if there's no cache yet.
 * The worker reports retry so WorkManager backs off on transient
 * network errors without spamming the API.
 */
@HiltWorker
class YshCatalogRefreshWorker @AssistedInject constructor(
    @Assisted ctx: Context,
    @Assisted params: WorkerParameters,
    private val catalog: YshCatalog,
) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result =
        catalog.refresh().fold(
            onSuccess = { Result.success() },
            onFailure = { Result.retry() },
        )
}
