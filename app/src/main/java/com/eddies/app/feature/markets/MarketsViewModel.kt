package com.eddies.app.feature.markets

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.eddies.app.core.ui.IconResolver
import com.eddies.app.data.prefs.SettingsDataStore
import com.eddies.app.data.price.PriceRepository
import com.eddies.app.data.repo.AssetRepository
import com.eddies.app.data.repo.TradegateLookup
import com.eddies.app.data.repo.lookupTradegate
import com.eddies.app.data.stocks.TradegateSource
import com.eddies.app.domain.Asset
import com.eddies.app.domain.MoneyFormat
import com.eddies.app.domain.PriceTick
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.ExperimentalCoroutinesApi
import javax.inject.Inject

/** Which universe the search box is pointed at. */
enum class SearchScope(val label: String) {
    COINS("Coins"),
    STOCKS("Stocks"),

    /**
     * Tradegate has no search endpoint and no ticker, only ISINs, so it gets its
     * own tab rather than being folded into the stock search where a name query
     * could never work.
     */
    TRADEGATE("Tradegate"),
}

data class MarketsUiState(
    val query: String = "",
    val searchScope: SearchScope = SearchScope.COINS,
    val results: List<Asset> = emptyList(),
    val prices: Map<String, PriceTick> = emptyMap(),
    val currency: String = "EUR",
    val advanced: Boolean = false,
    val searching: Boolean = false,
    val remoteAllowed: Boolean = false,
    val tradegateStatus: String? = null,
)

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
@HiltViewModel
class MarketsViewModel @Inject constructor(
    private val assets: AssetRepository,
    private val prices: PriceRepository,
    private val settings: SettingsDataStore,
    val icons: IconResolver,
) : ViewModel() {

    private val query = MutableStateFlow("")
    private val searching = MutableStateFlow(false)
    private val searchScope = MutableStateFlow(SearchScope.COINS)
    private val tradegateStatus = MutableStateFlow<String?>(null)

    /**
     * Debounced so a search runs per pause, not per keystroke. That is a
     * responsiveness win locally and, when the remote fallback is enabled, the
     * difference between one request and one per letter typed.
     */
    private val results = combine(
        query.debounce(220).distinctUntilChanged(),
        settings.settings.map { it.remoteIcons }.distinctUntilChanged(),
        searchScope,
    ) { q, allowRemote, scope -> Triple(q, allowRemote, scope) }
        .mapLatest { (q, allowRemote, scope) ->
            searching.value = true
            val out = when (scope) {
                // Coins are searched offline first, so typing a coin name
                // discloses nothing. Shares have no offline set to search.
                SearchScope.COINS -> assets.search(q, allowRemote = allowRemote && q.length >= 2)
                SearchScope.STOCKS -> assets.searchStocks(q)
                SearchScope.TRADEGATE -> lookupTradegate(q)
            }
            searching.value = false
            out
        }

    val state: StateFlow<MarketsUiState> = combine(
        query,
        results,
        prices.prices,
        settings.settings,
        combine(searching, searchScope, tradegateStatus) { s, scope, status -> Triple(s, scope, status) },
    ) { q, list, priceMap, cfg, (isSearching, scope, status) ->
        MarketsUiState(
            query = q,
            searchScope = scope,
            results = list,
            prices = priceMap,
            currency = cfg.baseCurrency,
            advanced = cfg.advancedMode,
            searching = isSearching,
            remoteAllowed = cfg.remoteIcons,
            tradegateStatus = status,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MarketsUiState())

    fun setQuery(q: String) { query.value = q }

    fun setSearchScope(scope: SearchScope) {
        searchScope.value = scope
        tradegateStatus.value = null
    }

    /**
     * Resolves a pasted ISIN, reporting why it failed rather than showing an
     * empty list. "Nothing found" for a valid ISIN that Tradegate simply does
     * not list is a different problem from a typo, and the fix differs too.
     */
    private suspend fun lookupTradegate(query: String): List<Asset> {
        val raw = query.trim()
        if (raw.isEmpty()) {
            tradegateStatus.value = null
            return emptyList()
        }
        return when (val result = assets.lookupTradegate(raw)) {
            is TradegateLookup.Found -> {
                tradegateStatus.value =
                    "Listed on Tradegate at ${MoneyFormat.price(result.price, TradegateSource.CURRENCY)}."
                listOf(result.asset)
            }
            is TradegateLookup.Invalid -> {
                tradegateStatus.value = result.reason
                emptyList()
            }
            TradegateLookup.NotListed -> {
                tradegateStatus.value = "That is a valid ISIN, but Tradegate does not list it."
                emptyList()
            }
            TradegateLookup.Unreachable -> {
                tradegateStatus.value = "Could not reach Tradegate just now."
                emptyList()
            }
        }
    }
}
