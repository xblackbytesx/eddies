package com.eddies.app.widget

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Brings the widget's number up to date in the background.
 *
 * Subscribing to the portfolio opens the price feeds for as long as it takes to
 * get one complete set of prices, at most twenty seconds, and then lets them
 * close again. Always succeeds: a refresh that found no network leaves the
 * widget showing what it had, with the time it was true, and the next run is
 * half an hour away anyway.
 */
@HiltWorker
class WidgetWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val updater: WidgetUpdater,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        runCatching { updater.refresh() }
        return Result.success()
    }

    companion object {
        const val PERIODIC = "eddies-widget"
        const val ONCE = "eddies-widget-now"
    }
}
