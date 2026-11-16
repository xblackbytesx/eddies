package com.eddies.app.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import com.eddies.app.work.WorkScheduler
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * The net worth widget's receiver.
 *
 * Does nothing slow. A broadcast receiver has a few seconds and no business
 * opening an encrypted database, so it redraws the last known lines at once and
 * leaves the recomputing to [WidgetWorker].
 *
 * The half-hourly refresh only exists while a widget does: it is scheduled when
 * the first one is placed and cancelled when the last one is removed, so
 * someone who never adds a widget never pays for one.
 */
@AndroidEntryPoint
class NetWorthWidget : AppWidgetProvider() {

    @Inject lateinit var updater: WidgetUpdater
    @Inject lateinit var scheduler: WorkScheduler

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        updater.draw()
        scheduler.refreshWidgetNow()
    }

    override fun onEnabled(context: Context) {
        scheduler.scheduleWidgetRefresh()
    }

    override fun onDisabled(context: Context) {
        scheduler.cancelWidgetRefresh()
    }
}
