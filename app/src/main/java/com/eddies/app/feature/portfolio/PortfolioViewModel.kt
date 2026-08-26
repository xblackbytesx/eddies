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
import com.eddies.app.domain.PortfolioScope
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
    val scope: PortfolioScope = PortfolioScope.ALL,
    val advanced: Boolean = false,
    val hidden: Boolean = false,
    val compact: Boolean = false,
    val scrubbed: ChartPoint? = null,
    /**
     * The same total in the user's second currency, or null when they have not
     * chosen one, it matches the main one, or no rate is known.
     *
     * Null rather than falling back to the main currency: showing the same
     * number twice under two different symbols would be worse than showing it
     * once.
     */
    val secondaryTotal: java.math.BigDecimal? = null,
    val secondaryCurrency: String = "",
    val onboarded: Boolean = false,
) {
    /** Only what the selected scope covers. */
    val holdings: List<Holding> get() = summary.holdings.filter { scope.matches(it.asset.id) }

    val isEmpty: Boolean get() = holdings.isEmpty()

    /**
     * Totals for the scope, derived from the same holdings the list shows, so
     * the headline figure and the rows under it can never disagree.
     */
    val scopedValue: java.math.BigDecimal get() = holdings.sumOf { it.marketValue }
    val scopedCost: java.math.BigDecimal get() = holdings.sumOf { it.position.costBasis }
    val scopedPnl: java.math.BigDecimal get() = holdings.sumOf { it.unrealizedPnl }
    val scopedIncome: java.math.BigDecimal get() = holdings.sumOf { it.incomeValue }
    val scopedRealized: java.math.BigDecimal get() = holdings.sumOf { it.position.realizedPnl }

    val scopedPnlPct: Double?
        get() = scopedCost.takeIf { it.signum() != 0 }
            ?.let { scopedPnl.divide(it, com.eddies.app.domain.MC).toDouble() * 100.0 }

    /** The selector only earns its place once more than one class is held. */
    val showScopeSelector: Boolean get() = summary.isMixed
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class PortfolioViewModel @Inject constructor(
    private val portfolio: PortfolioRepository,
    private val settings: SettingsDataStore,
    private val backfill: PortfolioBackfill,
    private val fx: com.eddies.app.data.price.FxRepository,
    val icons: IconResolver,
) : ViewModel() {

    private val range = MutableStateFlow(ChartRange.MONTH)
    private val scrubbed = MutableStateFlow<ChartPoint?>(null)
    private val scope = MutableStateFlow(PortfolioScope.ALL)

    private val history = combine(range, scope) { r, s -> r to s }
        .flatMapLatest { (r, s) -> portfolio.history(r.days, s) }

    val state: StateFlow<PortfolioUiState> = combine(
        portfolio.summary,
        history,
        settings.settings,
        combine(range, scope) { r, s -> r to s },
        // Paired because combine only has typed overloads up to five flows.
        combine(scrubbed, fx.converter()) { scrub, converter -> scrub to converter },
    ) { summary, points, cfg, (r, s), (scrub, converter) ->
        PortfolioUiState(
            summary = summary,
            history = points.map { ChartPoint(it.day.dayToEpoch(), it.value.toDouble()) },
            range = r,
            scope = s,
            secondaryTotal = converter
                .takeIf { cfg.secondaryCurrency.isNotBlank() && cfg.secondaryCurrency != cfg.baseCurrency }
                ?.convert(
                    holdingsFor(summary, s).sumOf { it.marketValue },
                    cfg.baseCurrency,
                    cfg.secondaryCurrency,
                ),
            secondaryCurrency = cfg.secondaryCurrency,
            onboarded = cfg.onboarded,
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

    fun setScope(s: PortfolioScope) {
        scope.value = s
        scrubbed.value = null
    }

    fun onScrub(point: ChartPoint?) { scrubbed.value = point }

    fun toggleHidden() {
        viewModelScope.launch {
            settings.setHideBalances(!settings.current().hideBalances)
        }
    }

    /**
     * Marks the app as used once there is anything in the ledger.
     *
     * Gives the flag an actual job: it separates "never started" from "sold
     * everything", which want different empty states. Someone who has cleared
     * out does not need to be welcomed again.
     */
    fun markOnboardedIfHolding() {
        viewModelScope.launch {
            val s = state.value
            if (s.summary.holdings.isNotEmpty() && !s.onboarded) settings.setOnboarded()
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

/**
 * Holdings within a scope.
 *
 * Duplicated from PortfolioUiState because the secondary total is computed while
 * building that state, before there is a state to ask.
 */
private fun holdingsFor(
    summary: com.eddies.app.domain.PortfolioSummary,
    scope: PortfolioScope,
) = summary.holdings.filter { scope.matches(it.asset.id) }
