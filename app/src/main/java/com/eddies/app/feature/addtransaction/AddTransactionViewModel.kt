package com.eddies.app.feature.addtransaction

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.eddies.app.core.ui.IconResolver
import com.eddies.app.data.prefs.SettingsDataStore
import com.eddies.app.data.price.PriceRepository
import com.eddies.app.data.repo.AssetRepository
import com.eddies.app.data.repo.TransactionRepository
import com.eddies.app.data.repo.UserSelectableTxTypes
import com.eddies.app.data.repo.txTypesFor
import com.eddies.app.domain.Asset
import com.eddies.app.domain.MC
import com.eddies.app.domain.Transaction
import com.eddies.app.domain.TxType
import com.eddies.app.navigation.AddTransactionRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.math.BigDecimal
import javax.inject.Inject

/** Which field the user is typing the size of the trade into. */
enum class AmountMode { QUANTITY, TOTAL }

data class AddTxUiState(
    val asset: Asset? = null,
    val iconUri: String? = null,
    val type: TxType = TxType.BUY,
    val amountMode: AmountMode = AmountMode.QUANTITY,
    val quantityInput: String = "",
    val totalInput: String = "",
    val priceInput: String = "",
    val feeInput: String = "",
    val note: String = "",
    val cashInput: String = "",
    val timestamp: Long = System.currentTimeMillis(),
    /** The currency this trade was priced in. Not necessarily the base currency. */
    val currency: String = "EUR",
    val baseCurrency: String = "EUR",
    val availableCurrencies: List<String> = emptyList(),
    /**
     * What the entered price converts to in the base currency, at the rate in
     * force on the transaction date.
     *
     * Shown so the number can be sanity checked before saving. It is an
     * approximation of what a broker charged: the ECB reference rate carries no
     * FX spread, and the spread is real money. Anyone who knows the amount they
     * were actually debited should enter that instead, in the base currency.
     */
    val convertedPreview: java.math.BigDecimal? = null,
    val marketPrice: BigDecimal? = null,
    val editingId: Long = 0,
    val saving: Boolean = false,
    val saved: Boolean = false,
    val error: String? = null,
) {
    val isEdit: Boolean get() = editingId != 0L

    /** The quantity the form will actually save, whichever field was typed into. */
    val effectiveQuantity: BigDecimal?
        get() = when (amountMode) {
            AmountMode.QUANTITY -> quantityInput.toDecimalOrNull()
            AmountMode.TOTAL -> {
                val total = totalInput.toDecimalOrNull()
                val price = priceInput.toDecimalOrNull()
                if (total == null || price == null || price.signum() == 0) null
                else total.divide(price, MC)
            }
        }

    val effectivePrice: BigDecimal? get() = priceInput.toDecimalOrNull()

    /** The other half of the pair, shown live under the input being edited. */
    val derivedLabel: String?
        get() {
            val price = effectivePrice ?: return null
            return when (amountMode) {
                AmountMode.QUANTITY -> quantityInput.toDecimalOrNull()
                    ?.let { com.eddies.app.domain.MoneyFormat.fiat(it * price, currency) }
                    ?.let { "Total $it" }
                AmountMode.TOTAL -> effectiveQuantity
                    ?.let { com.eddies.app.domain.MoneyFormat.quantity(it, asset?.decimals ?: 8) }
                    ?.let { "$it ${asset?.symbol.orEmpty()}" }
            }
        }

    /** A dividend is cash with no shares, so it is the one type with no quantity. */
    val isCashOnly: Boolean get() = type == TxType.DIVIDEND

    /** The types that make sense for this asset. Staking on a share is nonsense. */
    val availableTypes: List<TxType>
        get() = asset?.let { txTypesFor(it.assetClass) } ?: UserSelectableTxTypes

    val canSave: Boolean
        get() = when {
            asset == null || saving -> false
            isCashOnly -> (cashInput.toDecimalOrNull()?.signum() ?: 0) > 0
            else -> (effectiveQuantity?.signum() ?: 0) > 0
        }
}

@HiltViewModel
class AddTransactionViewModel @Inject constructor(
    private val saved: SavedStateHandle,
    private val assets: AssetRepository,
    private val transactions: TransactionRepository,
    private val prices: PriceRepository,
    private val settings: SettingsDataStore,
    private val fx: com.eddies.app.data.price.FxRepository,
    private val icons: IconResolver,
) : ViewModel() {

    private val route = saved.toRoute<AddTransactionRoute>()

    private val _state = MutableStateFlow(AddTxUiState(editingId = route.transactionId))
    val state: StateFlow<AddTxUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val cfg = settings.current()
            _state.update {
                it.copy(
                    currency = cfg.baseCurrency,
                    baseCurrency = cfg.baseCurrency,
                    availableCurrencies = com.eddies.app.data.price.FxRepository.ALL_CURRENCIES,
                )
            }

            if (route.transactionId != 0L) {
                loadExisting(route.transactionId)
            } else {
                route.assetId?.let { selectAsset(it) }
            }

            // After loading, so a half typed edit wins over the stored original.
            restoreDraft()

            // Mirror every later keystroke, so the form survives the process
            // being killed while the screen is off or another app is in front.
            // A ViewModel alone does not: it dies with the process, and the user
            // comes back to an empty form with no idea what they had typed.
            _state.collect { saveDraft(it) }
        }
    }

    private fun saveDraft(s: AddTxUiState) {
        saved[KEY_TYPE] = s.type.name
        saved[KEY_MODE] = s.amountMode.name
        saved[KEY_QUANTITY] = s.quantityInput
        saved[KEY_TOTAL] = s.totalInput
        saved[KEY_PRICE] = s.priceInput
        saved[KEY_FEE] = s.feeInput
        saved[KEY_CASH] = s.cashInput
        saved[KEY_NOTE] = s.note
        saved[KEY_TIMESTAMP] = s.timestamp
        saved[KEY_CURRENCY] = s.currency
    }

    private fun restoreDraft() {
        // Absent means nothing was typed, so the loaded values stand.
        val type = saved.get<String>(KEY_TYPE) ?: return
        _state.update { s ->
            s.copy(
                type = runCatching { TxType.valueOf(type) }.getOrDefault(s.type),
                amountMode = saved.get<String>(KEY_MODE)
                    ?.let { m -> runCatching { AmountMode.valueOf(m) }.getOrNull() } ?: s.amountMode,
                quantityInput = saved.get<String>(KEY_QUANTITY) ?: s.quantityInput,
                totalInput = saved.get<String>(KEY_TOTAL) ?: s.totalInput,
                priceInput = saved.get<String>(KEY_PRICE) ?: s.priceInput,
                feeInput = saved.get<String>(KEY_FEE) ?: s.feeInput,
                cashInput = saved.get<String>(KEY_CASH) ?: s.cashInput,
                note = saved.get<String>(KEY_NOTE) ?: s.note,
                timestamp = saved.get<Long>(KEY_TIMESTAMP) ?: s.timestamp,
                currency = saved.get<String>(KEY_CURRENCY) ?: s.currency,
            )
        }
    }

    private suspend fun loadExisting(id: Long) {
        val tx = transactions.byId(id) ?: return
        val asset = assets.byId(tx.assetId)
        _state.update {
            it.copy(
                asset = asset,
                iconUri = icons.uriFor(asset?.iconSlug),
                type = tx.type,
                quantityInput = tx.quantity.stripTrailingZeros().toPlainString(),
                cashInput = tx.cashAmount?.stripTrailingZeros()?.toPlainString().orEmpty(),
                priceInput = tx.pricePerUnit?.stripTrailingZeros()?.toPlainString().orEmpty(),
                feeInput = tx.feeQuantity?.stripTrailingZeros()?.toPlainString().orEmpty(),
                note = tx.note.orEmpty(),
                timestamp = tx.timestamp,
                currency = tx.quoteCurrency,
            )
        }
    }

    fun selectAsset(assetId: String) {
        viewModelScope.launch {
            val asset = assets.byId(assetId) ?: return@launch
            _state.update { it.copy(asset = asset, iconUri = icons.uriFor(asset.iconSlug)) }
            assets.setTracked(assetId, true)
            prefillMarketPrice(assetId)
        }
    }

    /**
     * Prefills the price with the live market price, which is right far more
     * often than an empty field is, and is one fewer thing to look up while
     * entering a trade that just happened.
     */
    private suspend fun prefillMarketPrice(assetId: String) {
        val tick = runCatching { prices.prices.first() }.getOrNull()?.get(assetId) ?: return
        _state.update { s ->
            if (s.priceInput.isNotBlank()) s
            else s.copy(
                marketPrice = tick.price,
                priceInput = tick.price.stripTrailingZeros().toPlainString(),
            )
        }
    }

    fun setType(type: TxType) = _state.update { it.copy(type = type) }
    fun setAmountMode(mode: AmountMode) = _state.update { it.copy(amountMode = mode) }
    fun setQuantity(v: String) {
        _state.update { it.copy(quantityInput = v.sanitiseDecimal()) }
        refreshConversion()
    }

    fun setTotal(v: String) {
        _state.update { it.copy(totalInput = v.sanitiseDecimal()) }
        refreshConversion()
    }

    fun setPrice(v: String) {
        _state.update { it.copy(priceInput = v.sanitiseDecimal()) }
        refreshConversion()
    }
    fun setFee(v: String) = _state.update { it.copy(feeInput = v.sanitiseDecimal()) }
    fun setNote(v: String) = _state.update { it.copy(note = v) }

    /**
     * Changes the currency this trade was priced in.
     *
     * The stored transaction keeps its own currency and the cost basis engine
     * converts at the rate for its date, so a USD purchase in a EUR portfolio
     * stays honest about what it was.
     */
    fun setCurrency(code: String) {
        _state.update { it.copy(currency = code) }
        refreshConversion()
    }

    private fun refreshConversion() {
        viewModelScope.launch {
            val s = _state.value
            if (s.currency == s.baseCurrency) {
                _state.update { it.copy(convertedPreview = null) }
                return@launch
            }
            // The rate on the transaction's own date, not today's. Buying in
            // 2024 and entering it in 2026 must use the 2024 rate.
            runCatching { fx.ensureHistoryFrom(isoDay(s.timestamp)) }
            val table = runCatching { fx.historicalTable() }.getOrNull()
            val amount = if (s.isCashOnly) s.cashInput.toDecimalOrNull()
            else s.effectiveQuantity?.let { qty -> s.effectivePrice?.let { qty * it } }
            val rate = table?.rate(s.currency, s.baseCurrency, s.timestamp)
            _state.update {
                it.copy(convertedPreview = if (amount != null && rate != null) amount * rate else null)
            }
        }
    }

    private fun isoDay(ts: Long): String =
        java.time.Instant.ofEpochMilli(ts).atZone(java.time.ZoneOffset.UTC).toLocalDate().toString()
    fun setCash(v: String) = _state.update { it.copy(cashInput = v.sanitiseDecimal()) }
    fun setTimestamp(ts: Long) {
        _state.update { it.copy(timestamp = ts) }
        refreshConversion()
    }

    fun useMarketPrice() {
        _state.update { s ->
            s.marketPrice?.let { s.copy(priceInput = it.stripTrailingZeros().toPlainString()) } ?: s
        }
    }

    fun save() {
        val s = _state.value
        val asset = s.asset ?: return
        val qty = s.effectiveQuantity ?: java.math.BigDecimal.ZERO
        if (!s.canSave) return

        _state.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            runCatching {
                val accountId = transactions.defaultAccountId()
                transactions.save(
                    Transaction(
                        id = s.editingId,
                        accountId = accountId,
                        assetId = asset.id,
                        type = s.type,
                        quantity = if (s.isCashOnly) java.math.BigDecimal.ZERO else qty,
                        cashAmount = s.cashInput.toDecimalOrNull().takeIf { s.isCashOnly },
                        // A transfer has no price by nature; forcing one in would
                        // invent a cost basis the user never stated.
                        pricePerUnit = s.effectivePrice.takeIf { s.type.carriesPrice },
                        quoteCurrency = s.currency,
                        feeQuantity = s.feeInput.toDecimalOrNull(),
                        feeAssetId = s.feeInput.toDecimalOrNull()?.let { s.currency },
                        timestamp = s.timestamp,
                        note = s.note.takeIf { it.isNotBlank() },
                    ),
                )
            }.onSuccess {
                _state.update { it.copy(saving = false, saved = true) }
            }.onFailure { e ->
                _state.update { it.copy(saving = false, error = e.message ?: "Could not save.") }
            }
        }
    }

    fun delete() {
        val id = _state.value.editingId
        if (id == 0L) return
        viewModelScope.launch {
            transactions.delete(id)
            _state.update { it.copy(saved = true) }
        }
    }
}

private val TxType.carriesPrice: Boolean
    get() = this != TxType.TRANSFER_IN && this != TxType.TRANSFER_OUT

/**
 * Accepts what people actually type: a comma decimal separator, spaces, and a
 * pasted currency symbol. Rejecting those outright is the fastest way to make an
 * entry form feel hostile.
 */
internal fun String.sanitiseDecimal(): String {
    val cleaned = replace(',', '.').filter { it.isDigit() || it == '.' }
    val firstDot = cleaned.indexOf('.')
    if (firstDot < 0) return cleaned
    // Keep only the first separator; a second one is a typo, not a number.
    return cleaned.substring(0, firstDot + 1) + cleaned.substring(firstDot + 1).replace(".", "")
}

internal fun String.toDecimalOrNull(): BigDecimal? =
    trim().takeIf { it.isNotEmpty() }?.let { runCatching { BigDecimal(it) }.getOrNull() }

private const val KEY_TYPE = "draft_type"
private const val KEY_MODE = "draft_mode"
private const val KEY_QUANTITY = "draft_quantity"
private const val KEY_TOTAL = "draft_total"
private const val KEY_PRICE = "draft_price"
private const val KEY_FEE = "draft_fee"
private const val KEY_CASH = "draft_cash"
private const val KEY_NOTE = "draft_note"
private const val KEY_TIMESTAMP = "draft_timestamp"
private const val KEY_CURRENCY = "draft_currency"
