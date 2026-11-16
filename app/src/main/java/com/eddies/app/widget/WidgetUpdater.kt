package com.eddies.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import androidx.core.content.edit
import com.eddies.app.MainActivity
import com.eddies.app.R
import com.eddies.app.data.prefs.SettingsDataStore
import com.eddies.app.data.repo.PortfolioRepository
import com.eddies.app.domain.WidgetContent
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Works out what the net worth widget shows and draws it.
 *
 * The widget is plain RemoteViews, not Glance: two lines of text do not justify
 * a new dependency in an app that counts them.
 *
 * It has no live feed. The number is refreshed when the app goes to the
 * background and by [WidgetWorker] every half hour, and it always carries the
 * time it was true.
 *
 * What was last drawn is kept in a small preferences file, so the launcher can
 * be handed the same lines again after a restart without opening the encrypted
 * database from a broadcast receiver. It holds the text exactly as shown, so a
 * masked widget stores a masked value.
 */
@Singleton
class WidgetUpdater @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val portfolio: PortfolioRepository,
    private val settings: SettingsDataStore,
) {
    private val prefs by lazy { context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE) }

    fun hasWidgets(): Boolean = widgetIds().isNotEmpty()

    /**
     * Recomputes the widget from the portfolio. Returns false when no complete
     * total could be had, in which case the widget keeps what it showed.
     *
     * Waits for live prices for up to [liveWaitMs], then settles for cached
     * ones. A total with a holding missing is never drawn: a net worth that
     * dips because one price was slow is worse than one that is an hour old.
     */
    suspend fun refresh(liveWaitMs: Long = LIVE_WAIT_MS): Boolean = withContext(Dispatchers.Default) {
        if (!hasWidgets()) return@withContext false

        val summary = withTimeoutOrNull(liveWaitMs) {
            portfolio.summary.first { s -> s.holdings.isNotEmpty() && s.pricedInFull() && !s.anyStale }
        } ?: withTimeoutOrNull(CACHED_WAIT_MS) {
            portfolio.summary.first { s -> s.holdings.isEmpty() || s.pricedInFull() }
        } ?: return@withContext false

        val cfg = settings.current()
        val previousClose = runCatching { portfolio.history(LOOKBACK_DAYS).first().lastOrNull()?.value }.getOrNull()
        val now = System.currentTimeMillis()
        // A total built on cached prices is as old as the oldest of them.
        val at = summary.holdings.mapNotNull { it.price?.at }.minOrNull()?.takeIf { summary.anyStale } ?: now
        val content = WidgetContent.of(
            total = summary.totalValue,
            previousClose = previousClose,
            currency = summary.currency,
            hideBalances = cfg.hideBalances,
            appLock = cfg.appLockEnabled,
            at = at,
            now = now,
            zone = ZoneId.systemDefault(),
        )
        prefs.edit {
            putString(KEY_VALUE, content.value)
            putString(KEY_CHANGE, content.change)
            putInt(KEY_DIRECTION, content.direction)
            putLong(KEY_AT, at)
        }
        draw()
        true
    }

    /** Draws whatever was last computed. Cheap, synchronous, touches no database. */
    fun draw() {
        val ids = widgetIds()
        if (ids.isEmpty()) return
        val value = prefs.getString(KEY_VALUE, null)
        val change = prefs.getString(KEY_CHANGE, "").orEmpty()
        val at = prefs.getLong(KEY_AT, 0L)

        val views = RemoteViews(context.packageName, R.layout.widget_net_worth)
        views.setTextViewText(R.id.widget_value, value ?: context.getString(R.string.widget_empty))
        views.setTextViewText(R.id.widget_change, change)
        views.setViewVisibility(R.id.widget_change, if (change.isEmpty()) View.GONE else View.VISIBLE)
        views.setTextColor(
            R.id.widget_change,
            context.getColor(
                when (prefs.getInt(KEY_DIRECTION, 0)) {
                    1 -> R.color.widget_gain
                    -1 -> R.color.widget_loss
                    else -> R.color.widget_text_secondary
                },
            ),
        )
        views.setTextViewText(
            R.id.widget_updated,
            if (value == null || at == 0L) ""
            else WidgetContent.stamp(at, System.currentTimeMillis(), ZoneId.systemDefault()),
        )
        views.setOnClickPendingIntent(
            android.R.id.background,
            PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )
        AppWidgetManager.getInstance(context).updateAppWidget(ids, views)
    }

    private fun widgetIds(): IntArray = runCatching {
        AppWidgetManager.getInstance(context)
            .getAppWidgetIds(ComponentName(context, NetWorthWidget::class.java))
    }.getOrDefault(IntArray(0))

    private fun com.eddies.app.domain.PortfolioSummary.pricedInFull(): Boolean =
        holdings.none { it.totalQuantity.signum() > 0 && !it.hasPrice }

    private companion object {
        const val PREF_FILE = "eddies_widget"
        const val KEY_VALUE = "value"
        const val KEY_CHANGE = "change"
        const val KEY_DIRECTION = "direction"
        const val KEY_AT = "at"

        const val LIVE_WAIT_MS = 20_000L
        const val CACHED_WAIT_MS = 5_000L
        const val LOOKBACK_DAYS = 7
    }
}
