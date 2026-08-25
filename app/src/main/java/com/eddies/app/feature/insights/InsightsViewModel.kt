package com.eddies.app.feature.insights

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.eddies.app.core.ui.IconResolver
import com.eddies.app.data.prefs.SettingsDataStore
import com.eddies.app.data.repo.CustodyRepository
import com.eddies.app.data.repo.PortfolioRepository
import com.eddies.app.domain.CustodyGroup
import com.eddies.app.domain.CustodyGrouper
import com.eddies.app.domain.Holding
import com.eddies.app.domain.PortfolioSummary
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class InsightsUiState(
    val summary: PortfolioSummary = PortfolioSummary.empty("EUR"),
    val allocation: List<Pair<Holding, Double>> = emptyList(),
    val movers: List<Holding> = emptyList(),
    val custodyGroups: List<CustodyGroup> = emptyList(),
    val advanced: Boolean = false,
    val hidden: Boolean = false,
)

@HiltViewModel
class InsightsViewModel @Inject constructor(
    portfolio: PortfolioRepository,
    settings: SettingsDataStore,
    custody: CustodyRepository,
    val icons: IconResolver,
) : ViewModel() {

    val state: StateFlow<InsightsUiState> = combine(
        portfolio.summary,
        settings.settings,
        custody.observeAll(),
    ) { summary, cfg, custodyByAsset ->
        InsightsUiState(
            summary = summary,
            allocation = summary.allocation().filter { it.second > 0.0 },
            // Best and worst by 24h move, which is the question people actually
            // ask of this screen.
            movers = summary.holdings
                .filter { it.price?.changePct24h != null }
                .sortedByDescending { it.price?.changePct24h ?: 0.0 },
            custodyGroups = CustodyGrouper.group(
                holdings = summary.holdings.filter { it.position.quantity.signum() > 0 },
                custodyByAsset = custodyByAsset.mapValues { (_, row) -> row.type to row.label },
            ),
            advanced = cfg.advancedMode,
            hidden = cfg.hideBalances,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), InsightsUiState())
}
