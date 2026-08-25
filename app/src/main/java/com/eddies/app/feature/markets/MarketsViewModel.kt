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

data class MarketsUiState(
    val query: String = "",
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

    /**
     * Debounced so a search runs per pause, not per keystroke. That is a
     * responsiveness win locally and, when the remote fallback is enabled, the
     * difference between one request and one per letter typed.
     */
    private val results = combine(
        query.debounce(220).distinctUntilChanged(),
        settings.settings.map { it.remoteIcons }.distinctUntilChanged(),
    ) { q, allowRemote -> q to allowRemote }
        .mapLatest { (q, allowRemote) ->
            searching.value = true
            val out = assets.search(q, allowRemote = allowRemote && q.length >= 2)
            searching.value = false
            out
        }

    val state: StateFlow<MarketsUiState> = combine(
        query,
        results,
        prices.prices,
        settings.settings,
        searching,
    ) { q, list, priceMap, cfg, isSearching ->
        MarketsUiState(
            query = q,
            results = list,
            prices = priceMap,
            currency = cfg.baseCurrency,
            advanced = cfg.advancedMode,
            searching = isSearching,
            remoteAllowed = cfg.remoteIcons,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MarketsUiState())

    fun setQuery(q: String) { query.value = q }
}
