package com.eddies.app.feature.assetdetail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.eddies.app.core.design.ChartMath
import com.eddies.app.core.design.ChartPoint
import com.eddies.app.core.design.ChartRange
import com.eddies.app.data.db.entity.AssetCustodyEntity
import com.eddies.app.data.db.entity.CandleInterval
import com.eddies.app.data.db.entity.CustodyType
import com.eddies.app.data.price.PriceHistoryRepository
import com.eddies.app.core.ui.IconResolver
import com.eddies.app.data.prefs.SettingsDataStore
import com.eddies.app.data.repo.PortfolioRepository
import com.eddies.app.data.repo.CustodyRepository
import com.eddies.app.data.repo.TransactionRepository
import com.eddies.app.data.repo.WatchlistRepository
import com.eddies.app.domain.Holding
import com.eddies.app.domain.Transaction
import com.eddies.app.navigation.AssetDetailRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class AssetDetailUiState(
    val holding: Holding? = null,
    val transactions: List<Transaction> = emptyList(),
    val iconUri: String? = null,
    val advanced: Boolean = false,
    val hidden: Boolean = false,
    val currency: String = "EUR",
    val history: List<ChartPoint> = emptyList(),
    val range: ChartRange = ChartRange.MONTH,
    val loadingHistory: Boolean = false,
    val scrubbed: ChartPoint? = null,
    val watched: Boolean = false,
    val custody: AssetCustodyEntity? = null,
    val custodySuggestions: List<String> = emptyList(),
) {
    /** Change across the visible window, which is what the chart is showing. */
    val rangeChangePct: Double?
        get() = if (history.size < 2) null
        else ChartMath.percentChange(history.first().value, history.last().value)
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class AssetDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    portfolio: PortfolioRepository,
    transactions: TransactionRepository,
    settings: SettingsDataStore,
    private val history: PriceHistoryRepository,
    private val watchlist: WatchlistRepository,
    private val custody: CustodyRepository,
    private val icons: IconResolver,
) : ViewModel() {

    val assetId: String = savedStateHandle.toRoute<AssetDetailRoute>().assetId

    private val range = MutableStateFlow(ChartRange.MONTH)
    private val scrubbed = MutableStateFlow<ChartPoint?>(null)
    private val loading = MutableStateFlow(false)

    /**
     * Candles for the selected range, straight from the cache.
     *
     * A 1D chart needs hourly resolution; a 1Y chart at that granularity would
     * be nine thousand points for a line a few hundred pixels wide.
     */
    private val candles = range.flatMapLatest { r ->
        val interval = if (r == ChartRange.DAY) CandleInterval.HOUR else CandleInterval.DAY
        val since = System.currentTimeMillis() - r.days * 86_400_000L
        history.observe(assetId, interval, since)
    }

    val state: StateFlow<AssetDetailUiState> = combine(
        portfolio.summary.map { s -> s.holdings.firstOrNull { it.asset.id == assetId } },
        transactions.observeForAsset(assetId),
        settings.settings,
        candles,
        combine(
            range, scrubbed, loading, watchlist.assetIds, custody.observe(assetId),
        ) { r, scrub, isLoading, watched, custodyRow ->
            Extras(r, scrub, isLoading, assetId in watched, custodyRow)
        },
    ) { holding, txs, cfg, candleRows, extras ->
        val (r, scrub, isLoading, watched, custodyRow) = extras
        AssetDetailUiState(
            holding = holding,
            transactions = txs,
            iconUri = icons.uriFor(holding?.asset?.iconSlug),
            advanced = cfg.advancedMode,
            hidden = cfg.hideBalances,
            currency = cfg.baseCurrency,
            history = candleRows.map { row ->
                ChartPoint(row.timestamp, runCatching { row.close.toDouble() }.getOrDefault(0.0))
            },
            range = r,
            loadingHistory = isLoading,
            scrubbed = scrub,
            watched = watched,
            custody = custodyRow,
            custodySuggestions = suggestions.value,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AssetDetailUiState())

    init {
        // Fetch on first view, per range. Nothing is prefetched for coins the
        // user never opens.
        viewModelScope.launch {
            range.collect { r ->
                val interval = if (r == ChartRange.DAY) CandleInterval.HOUR else CandleInterval.DAY
                loading.value = true
                runCatching { history.ensureFresh(assetId, interval) }
                loading.value = false
            }
        }
    }

    fun setRange(r: ChartRange) {
        range.value = r
        scrubbed.value = null
    }

    fun onScrub(point: ChartPoint?) {
        scrubbed.value = point
    }

    fun toggleWatch() {
        viewModelScope.launch { watchlist.toggle(assetId, !state.value.watched) }
    }

    /** Names already used for other coins, offered as chips in the editor. */
    private val suggestions = MutableStateFlow<List<String>>(emptyList())

    fun refreshCustodySuggestions() {
        viewModelScope.launch { suggestions.value = custody.knownLabels() }
    }

    fun setCustody(type: CustodyType, label: String, note: String) {
        viewModelScope.launch {
            custody.set(assetId, type, label, note)
            suggestions.value = custody.knownLabels()
        }
    }

    fun clearCustody() {
        viewModelScope.launch { custody.clear(assetId) }
    }

    private data class Extras(
        val range: ChartRange,
        val scrubbed: ChartPoint?,
        val loading: Boolean,
        val watched: Boolean,
        val custody: AssetCustodyEntity?,
    )
}
