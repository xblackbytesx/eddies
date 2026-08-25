package com.eddies.app.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.eddies.app.data.price.FxRepository
import com.eddies.app.data.price.PriceRepository
import com.eddies.app.data.repo.PortfolioRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The once-a-day housekeeping pass: record today's portfolio value, refresh the
 * ECB rates, and prune old price snapshots.
 *
 * The snapshot is the reason this exists. A portfolio value chart cannot be
 * derived from the ledger alone, because that needs a historical price for every
 * held asset back to the first purchase. One row per day going forward is exact,
 * costs nothing, and keeps working when a price API changes or disappears.
 */
@HiltWorker
class DailyWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val portfolio: PortfolioRepository,
    private val prices: PriceRepository,
    private val fx: FxRepository,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            fx.refreshIfStale()

            // first() rather than collect: this needs one settled value, and the
            // summary flow never completes on its own.
            val summary = portfolio.summary.first()
            if (summary.holdings.isNotEmpty()) {
                portfolio.snapshotToday(summary)
            }

            prices.prune()
            Result.success()
        } catch (e: Exception) {
            // Retried rather than failed: a missed day leaves a gap in the chart,
            // and the next run cannot fill it in retrospectively.
            Result.retry()
        }
    }

    companion object {
        const val NAME = "eddies-daily"
    }
}

@Singleton
class WorkScheduler @Inject constructor(
    private val workManager: WorkManager,
) {
    /**
     * KEEP rather than UPDATE: rescheduling on every launch would restart the
     * period each time and, for someone who opens the app daily, the job would
     * never actually run.
     */
    fun ensureScheduled() {
        workManager.enqueueUniquePeriodicWork(
            DailyWorker.NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<DailyWorker>(1, TimeUnit.DAYS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .build(),
        )
    }
}
