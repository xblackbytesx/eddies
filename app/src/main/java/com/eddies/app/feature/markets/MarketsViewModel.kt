package com.eddies.app.feature.markets

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.eddies.app.core.ui.IconResolver
import com.eddies.app.data.prefs.SettingsDataStore
import com.eddies.app.data.price.PriceRepository
import com.eddies.app.data.repo.AssetRepository
import com.eddies.app.domain.Asset
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
enum class SearchScope(val label: String) { COINS("Coins"), STOCKS("Stocks") }

data class MarketsUiState(
    val query: String = "",
    val searchScope: SearchScope = SearchScope.COINS,
    val results: List<Asset> = emptyList(),
    val prices: Map<String, PriceTick> = emptyMap(),
    val currency: String = "EUR",
    val advanced: Boolean = false,
    val searching: Boolean = false,
    val remoteAllowed: Boolean = false,
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
            }
            searching.value = false
            out
        }

    val state: StateFlow<MarketsUiState> = combine(
        query,
        results,
        prices.prices,
        settings.settings,
        combine(searching, searchScope) { s, scope -> s to scope },
    ) { q, list, priceMap, cfg, (isSearching, scope) ->
        MarketsUiState(
            query = q,
            searchScope = scope,
            results = list,
            prices = priceMap,
            currency = cfg.baseCurrency,
            advanced = cfg.advancedMode,
            searching = isSearching,
            remoteAllowed = cfg.remoteIcons,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MarketsUiState())

    fun setQuery(q: String) { query.value = q }

    fun setSearchScope(scope: SearchScope) { searchScope.value = scope }
}
