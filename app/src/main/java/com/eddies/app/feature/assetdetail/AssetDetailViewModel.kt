package com.eddies.app.feature.assetdetail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.eddies.app.core.ui.IconResolver
import com.eddies.app.data.prefs.SettingsDataStore
import com.eddies.app.data.repo.PortfolioRepository
import com.eddies.app.data.repo.TransactionRepository
import com.eddies.app.domain.Holding
import com.eddies.app.domain.Transaction
import com.eddies.app.navigation.AssetDetailRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
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
)

@HiltViewModel
class AssetDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    portfolio: PortfolioRepository,
    transactions: TransactionRepository,
    settings: SettingsDataStore,
    private val icons: IconResolver,
) : ViewModel() {

    val assetId: String = savedStateHandle.toRoute<AssetDetailRoute>().assetId

    val state: StateFlow<AssetDetailUiState> = combine(
        portfolio.summary.map { s -> s.holdings.firstOrNull { it.asset.id == assetId } },
        transactions.observeForAsset(assetId),
        settings.settings,
    ) { holding, txs, cfg ->
        AssetDetailUiState(
            holding = holding,
            transactions = txs,
            iconUri = icons.uriFor(holding?.asset?.iconSlug),
            advanced = cfg.advancedMode,
            hidden = cfg.hideBalances,
            currency = cfg.baseCurrency,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AssetDetailUiState())
}
