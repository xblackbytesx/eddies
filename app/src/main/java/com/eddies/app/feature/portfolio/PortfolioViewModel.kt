package com.eddies.app.feature.portfolio

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.eddies.app.core.design.ChartPoint
import com.eddies.app.core.design.ChartRange
import com.eddies.app.core.ui.IconResolver
import com.eddies.app.data.prefs.SettingsDataStore
import com.eddies.app.data.repo.PortfolioBackfill
import com.eddies.app.data.repo.PortfolioRepository
import com.eddies.app.domain.Holding
import com.eddies.app.domain.PortfolioSummary
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PortfolioUiState(
    val summary: PortfolioSummary = PortfolioSummary.empty("EUR"),
    val history: List<ChartPoint> = emptyList(),
    val range: ChartRange = ChartRange.MONTH,
    val advanced: Boolean = false,
    val hidden: Boolean = false,
    val compact: Boolean = false,
    val scrubbed: ChartPoint? = null,
) {
    val holdings: List<Holding> get() = summary.holdings
    val isEmpty: Boolean get() = summary.holdings.isEmpty()
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class PortfolioViewModel @Inject constructor(
    private val portfolio: PortfolioRepository,
    private val settings: SettingsDataStore,
    private val backfill: PortfolioBackfill,
    val icons: IconResolver,
) : ViewModel() {

    private val range = MutableStateFlow(ChartRange.MONTH)
    private val scrubbed = MutableStateFlow<ChartPoint?>(null)

    private val history = range.flatMapLatest { r ->
        portfolio.history(r.days)
    }

    val state: StateFlow<PortfolioUiState> = combine(
        portfolio.summary,
        history,
        settings.settings,
        range,
        scrubbed,
    ) { summary, points, cfg, r, scrub ->
        PortfolioUiState(
            summary = summary,
            history = points.map { ChartPoint(it.day.dayToEpoch(), it.value.toDouble()) },
            range = r,
            advanced = cfg.advancedMode,
            hidden = cfg.hideBalances,
            compact = cfg.compactRows,
            scrubbed = scrub,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PortfolioUiState())

    private var backfillTried = false

    /**
     * Runs once per process when there are holdings but no history yet.
     *
     * Without this a fresh install shows an empty chart until WorkManager gets
     * around to the first daily run, which is exactly when someone is most
     * likely to be looking at it.
     */
    fun backfillIfEmpty() {
        if (backfillTried) return
        backfillTried = true
        viewModelScope.launch {
            val s = state.value
            if (s.summary.holdings.isEmpty() || s.history.size >= 2) return@launch
            runCatching { backfill.run() }
        }
    }

    fun setRange(r: ChartRange) { range.value = r }

    fun onScrub(point: ChartPoint?) { scrubbed.value = point }

    fun toggleHidden() {
        viewModelScope.launch {
            settings.setHideBalances(!settings.current().hideBalances)
        }
    }

    /** Records today's total so the value chart has a point for today. */
    fun snapshotNow() {
        viewModelScope.launch {
            runCatching { portfolio.snapshotToday(state.value.summary) }
        }
    }
}

/** "2026-08-25" to epoch millis at local midnight. */
internal fun String.dayToEpoch(): Long = runCatching {
    java.time.LocalDate.parse(this)
        .atStartOfDay(java.time.ZoneId.systemDefault())
        .toInstant().toEpochMilli()
}.getOrDefault(0L)
