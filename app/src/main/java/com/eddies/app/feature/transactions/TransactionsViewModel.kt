package com.eddies.app.feature.transactions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.eddies.app.core.ui.IconResolver
import com.eddies.app.data.db.dao.AssetDao
import com.eddies.app.data.prefs.SettingsDataStore
import com.eddies.app.data.repo.TransactionRepository
import com.eddies.app.data.repo.toDomain
import com.eddies.app.domain.Asset
import com.eddies.app.domain.Transaction
import com.eddies.app.domain.TxType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject

/** One month of the ledger, with its own heading. */
data class TransactionMonth(
    val label: String,
    val transactions: List<Transaction>,
)

data class TransactionsUiState(
    val months: List<TransactionMonth> = emptyList(),
    val assets: Map<String, Asset> = emptyMap(),
    val filter: TxType? = null,
    val hidden: Boolean = false,
    val total: Int = 0,
) {
    val isEmpty: Boolean get() = months.isEmpty()
}

/**
 * The whole ledger in one place.
 *
 * Transactions were previously only reachable per asset, so "what did I do last
 * month" could not be answered without opening every holding in turn. This is
 * also the only screen that shows a transaction for an asset no longer held,
 * which is otherwise invisible: a position sold in full disappears from the
 * portfolio but its realised profit is still in the totals.
 */
@HiltViewModel
class TransactionsViewModel @Inject constructor(
    transactions: TransactionRepository,
    assetDao: AssetDao,
    settings: SettingsDataStore,
    val icons: IconResolver,
) : ViewModel() {

    private val filter = MutableStateFlow<TxType?>(null)

    val state: StateFlow<TransactionsUiState> = combine(
        transactions.observeAll(),
        assetDao.observeAll(),
        settings.settings,
        filter,
    ) { txs, assetRows, cfg, type ->
        val filtered = if (type == null) txs else txs.filter { it.type == type }
        TransactionsUiState(
            // Already newest first from the DAO, so grouping preserves that and
            // the month headings come out in order without a second sort.
            months = filtered
                .groupBy { monthLabel(it.timestamp) }
                .map { (label, list) -> TransactionMonth(label, list) },
            assets = assetRows.associate { it.id to it.toDomain() },
            filter = type,
            hidden = cfg.hideBalances,
            total = txs.size,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TransactionsUiState())

    fun setFilter(type: TxType?) {
        filter.value = type
    }

    private fun monthLabel(ts: Long): String =
        MONTH.format(Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault()))

    private companion object {
        val MONTH: DateTimeFormatter = DateTimeFormatter.ofPattern("MMMM yyyy")
    }
}
