package com.eddies.app.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.eddies.app.data.price.FxRepository
import com.eddies.app.data.price.PriceRepository
import com.eddies.app.data.repo.PortfolioBackfill
import com.eddies.app.data.staking.StakingRepository
import com.eddies.app.widget.WidgetWorker
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The once-a-day housekeeping pass: refresh the ECB rates and the staking
 * figures, bring the value chart up to yesterday, and prune old candles.
 *
 * Opening the app does all of this too. The worker is what keeps the chart
 * current for someone who has not opened it for a week, and what makes the
 * first screen after that week instant instead of a fetch per holding.
 */
@HiltWorker
class DailyWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val prices: PriceRepository,
    private val fx: FxRepository,
    private val backfill: PortfolioBackfill,
    private val staking: StakingRepository,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            fx.refreshIfStale()
            runCatching { staking.syncAll() }
            // After the rates, since yesterday's closes are converted with them.
            runCatching { backfill.run() }
            prices.prune()
            Result.success()
        } catch (e: Exception) {
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

    /**
     * Half-hourly while a widget is on the home screen. Fifteen minutes is the
     * floor WorkManager allows, and a net worth does not need watching that
     * closely from a launcher.
     */
    fun scheduleWidgetRefresh() {
        workManager.enqueueUniquePeriodicWork(
            WidgetWorker.PERIODIC,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<WidgetWorker>(30, TimeUnit.MINUTES)
                .setConstraints(connected())
                .build(),
        )
    }

    fun cancelWidgetRefresh() {
        workManager.cancelUniqueWork(WidgetWorker.PERIODIC)
    }

    /** One refresh soon, for a widget that has just been placed or redrawn. */
    fun refreshWidgetNow() {
        workManager.enqueueUniqueWork(
            WidgetWorker.ONCE,
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<WidgetWorker>().setConstraints(connected()).build(),
        )
    }

    private fun connected(): Constraints =
        Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
}
