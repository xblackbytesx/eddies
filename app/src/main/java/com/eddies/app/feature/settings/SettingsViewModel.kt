package com.eddies.app.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.eddies.app.core.design.ThemeMode
import com.eddies.app.data.prefs.Aggregator
import com.eddies.app.data.prefs.AppSettings
import com.eddies.app.data.prefs.RealtimeFeed
import com.eddies.app.data.prefs.SettingsDataStore
import com.eddies.app.data.price.FxRepository
import com.eddies.app.data.repo.TransactionRepository
import com.eddies.app.domain.CostBasisMethod
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val settings: AppSettings = AppSettings(),
    val currencies: List<String> = FxRepository.ALL_CURRENCIES,
    val geckoKeyDraft: String = "",
    val stockKeyDraft: String = "",
    val message: String? = null,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settings: SettingsDataStore,
    private val transactions: TransactionRepository,
) : ViewModel() {

    private val geckoDraft = MutableStateFlow("")
    private val stockDraft = MutableStateFlow("")
    private val message = MutableStateFlow<String?>(null)

    val state: StateFlow<SettingsUiState> = combine(
        settings.settings,
        geckoDraft,
        combine(stockDraft, message) { s, m -> s to m },
    ) { cfg, draft, (stock, msg) ->
        SettingsUiState(settings = cfg, geckoKeyDraft = draft, stockKeyDraft = stock, message = msg)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    fun setThemeMode(m: ThemeMode) = launch { settings.setThemeMode(m) }
    fun setDynamicColor(v: Boolean) = launch { settings.setDynamicColor(v) }
    fun setCompactRows(v: Boolean) = launch { settings.setCompactRows(v) }
    fun setBaseCurrency(c: String) = launch { settings.setBaseCurrency(c) }
    fun setSecondaryCurrency(c: String) = launch { settings.setSecondaryCurrency(c) }
    fun setAdvancedMode(v: Boolean) = launch { settings.setAdvancedMode(v) }
    fun setRealtimeFeed(f: RealtimeFeed) = launch { settings.setRealtimeFeed(f) }
    fun setAggregator(a: Aggregator) = launch { settings.setAggregator(a) }
    fun setPollInterval(s: Int) = launch { settings.setPollInterval(s) }
    fun setRemoteIcons(v: Boolean) = launch { settings.setRemoteIcons(v) }
    fun setCostBasisMethod(m: CostBasisMethod) = launch { settings.setCostBasisMethod(m) }
    fun setIncludeFees(v: Boolean) = launch { settings.setIncludeFeesInBasis(v) }
    fun setAppLock(v: Boolean) = launch { settings.setAppLockEnabled(v) }
    fun setAutoLock(s: Int) = launch { settings.setAutoLockSeconds(s) }
    fun setHideBalances(v: Boolean) = launch { settings.setHideBalances(v) }
    fun setHideInRecents(v: Boolean) = launch { settings.setHideInRecents(v) }

    fun setGeckoDraft(v: String) { geckoDraft.value = v }
    fun setStockKeyDraft(v: String) { stockDraft.value = v }

    fun saveStockKey() = launch {
        settings.setStockApiKey(stockDraft.value.trim())
        stockDraft.value = ""
        message.value = "Stock API key saved."
    }

    fun clearStockKey() = launch {
        settings.setStockApiKey("")
        message.value = "Stock API key removed."
    }

    fun saveGeckoKey() = launch {
        settings.setCoinGeckoKey(geckoDraft.value.trim())
        geckoDraft.value = ""
        message.value = "API key saved."
    }

    fun clearGeckoKey() = launch {
        settings.setCoinGeckoKey("")
        message.value = "API key removed."
    }

    fun eraseEverything() = launch {
        transactions.deleteAll()
        message.value = "All transactions deleted."
    }

    fun dismissMessage() { message.value = null }

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }
}
