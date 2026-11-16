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
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
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
    /**
     * False until the database has actually answered.
     *
     * The default state of this class is indistinguishable from a real empty
     * portfolio, so without this a populated database rendered "nothing here
     * yet" and a total of zero for a frame on every cold start.
     */
    val loaded: Boolean = false,
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

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class PortfolioViewModel @Inject constructor(
    private val portfolio: PortfolioRepository,
    private val settings: SettingsDataStore,
    private val backfill: PortfolioBackfill,
    private val intraday: com.eddies.app.data.repo.PortfolioIntraday,
    private val fx: com.eddies.app.data.price.FxRepository,
    val icons: IconResolver,
) : ViewModel() {

    private val range = MutableStateFlow(ChartRange.MONTH)
    private val scrubbed = MutableStateFlow<ChartPoint?>(null)
    private val scope = MutableStateFlow(PortfolioScope.ALL)

    /**
     * The chart's past, up to but not including now.
     *
     * 1D and 1W are replayed by the hour on request. Everything longer reads
     * the stored daily history. Until an hourly replay answers, the line that
     * was already on screen stays there, which is better than a blank chart.
     */
    private val history: kotlinx.coroutines.flow.Flow<List<ChartPoint>> =
        combine(range, scope) { r, s -> r to s }.flatMapLatest { (r, s) ->
            if (r.intraday) {
                portfolio.historyInputs.mapLatest {
                    runCatching { intraday.points(r.days) }.getOrDefault(emptyList()).mapNotNull { point ->
                        val value = s.assetClass
                            ?.let { point.perClass[it]?.value }
                            ?: point.value.takeIf { s.assetClass == null }
                        value?.let { ChartPoint(point.at, it.toDouble()) }
                    }
                }
            } else {
                portfolio.history(r.days, s).map { points ->
                    points.map { ChartPoint(it.day.dayToEpoch(), it.value.toDouble()) }
                }
            }
        }

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
            history = points + listOfNotNull(livePoint(holdingsFor(summary, s), r)),
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
            loaded = true,
            advanced = cfg.advancedMode,
            hidden = cfg.hideBalances,
            compact = cfg.compactRows,
            scrubbed = scrub,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PortfolioUiState())

    init {
        // Replays the value chart whenever something it is built from changes,
        // and once on arrival so it reaches yesterday without waiting for the
        // daily worker.
        //
        // It used to run only when the chart was empty. A transaction added
        // with a past date then changed the holdings and left the line exactly
        // as it was until the next day, which is the moment someone is looking
        // straight at it. The debounce is for a CSV import, which is one edit
        // to the user and several hundred to the database.
        viewModelScope.launch {
            portfolio.historyInputs
                .debounce(REPLAY_DEBOUNCE_MS)
                .collectLatest { runCatching { backfill.run() } }
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

    private companion object {
        const val REPLAY_DEBOUNCE_MS = 400L
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

/**
 * Today's point on the value chart, taken from the live total.
 *
 * The stored history stops at yesterday, because today has no close yet. Using
 * the same holdings the headline figure is summed from means the end of the
 * line and the number above it are one number, which they were not while
 * today's point came from whichever candles happened to exist that morning.
 *
 * Null while any held position is still waiting for a price: a total that is
 * missing a holding would draw a dip that closes itself a second later.
 */
private fun livePoint(holdings: List<Holding>, range: ChartRange): ChartPoint? {
    val held = holdings.filter { it.totalQuantity.signum() > 0 }
    if (held.isEmpty() || held.any { !it.hasPrice }) return null
    // On an hourly chart the live point is this moment, not midnight, or it
    // would sit to the left of hours that have already been drawn.
    val at = if (range.intraday) System.currentTimeMillis()
    else java.time.LocalDate.now().toString().dayToEpoch()
    return ChartPoint(at, held.sumOf { it.marketValue }.toDouble())
}
