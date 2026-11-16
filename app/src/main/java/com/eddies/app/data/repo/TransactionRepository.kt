package com.eddies.app.data.repo

import com.eddies.app.data.db.dao.AccountDao
import com.eddies.app.data.db.dao.TransactionDao
import com.eddies.app.data.db.entity.AccountEntity
import com.eddies.app.data.db.entity.TransactionEntity
import com.eddies.app.domain.LedgerDedup
import com.eddies.app.domain.Transaction
import com.eddies.app.domain.TxSource
import com.eddies.app.domain.TxType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.math.BigDecimal
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TransactionRepository @Inject constructor(
    private val dao: TransactionDao,
    private val accountDao: AccountDao,
) {

    fun observeAll(): Flow<List<Transaction>> = dao.observeAll().map { list -> list.map { it.toDomain() } }

    fun observeForAsset(assetId: String): Flow<List<Transaction>> =
        dao.observeForAsset(assetId).map { list -> list.map { it.toDomain() } }

    suspend fun byId(id: Long): Transaction? = dao.byId(id)?.toDomain()

    suspend fun all(): List<Transaction> = dao.all().map { it.toDomain() }

    suspend fun save(tx: Transaction): Long = dao.upsert(tx.toEntity())

    suspend fun delete(id: Long) = dao.delete(id)

    suspend fun deleteAll() = dao.deleteAll()

    suspend fun earliestTimestamp(): Long? = dao.earliestTimestamp()

    /**
     * Bulk insert that skips rows already present. Returns how many were added.
     *
     * Two guards. [LedgerDedup] matches rows on their content, which is the
     * only thing a hand-entered row can be recognized by, so restoring a backup
     * twice adds nothing the second time. The unique index over
     * (source, externalId) then still refuses anything that slipped past it.
     */
    suspend fun importDeduplicated(txs: List<Transaction>): Int {
        val fresh = LedgerDedup.newRows(existing = all(), incoming = txs)
        if (fresh.isEmpty()) return 0
        val ids = dao.insertIgnoringDuplicates(fresh.map { it.toEntity() })
        return ids.count { it != -1L }
    }

    /** The default account, created on demand so the add flow never blocks on setup. */
    suspend fun defaultAccountId(): Long {
        accountDao.observeAll()
        val existing = accountDao.count()
        if (existing > 0) {
            return accountDao.withStakingAddress().firstOrNull()?.id
                ?: accountDao.byId(1)?.id
                ?: accountDao.upsert(AccountEntity(name = "Main"))
        }
        return accountDao.upsert(AccountEntity(name = "Main"))
    }
}

internal fun TransactionEntity.toDomain() = Transaction(
    id = id,
    accountId = accountId,
    assetId = assetId,
    type = type,
    quantity = runCatching { BigDecimal(quantity) }.getOrDefault(BigDecimal.ZERO),
    pricePerUnit = pricePerUnit?.let { runCatching { BigDecimal(it) }.getOrNull() },
    quoteCurrency = quoteCurrency,
    feeQuantity = feeQuantity?.let { runCatching { BigDecimal(it) }.getOrNull() },
    feeAssetId = feeAssetId,
    timestamp = timestamp,
    note = note,
    source = source,
    externalId = externalId,
    // Every column has to be named in both mappers. This one was missing from
    // both, so a dividend was saved as no cash at all and read back as zero.
    cashAmount = cashAmount?.let { runCatching { BigDecimal(it) }.getOrNull() },
)

internal fun Transaction.toEntity() = TransactionEntity(
    id = id,
    accountId = accountId,
    assetId = assetId,
    type = type,
    quantity = quantity.toPlainString(),
    pricePerUnit = pricePerUnit?.toPlainString(),
    quoteCurrency = quoteCurrency,
    feeQuantity = feeQuantity?.toPlainString(),
    feeAssetId = feeAssetId,
    timestamp = timestamp,
    note = note,
    source = source,
    externalId = externalId,
    cashAmount = cashAmount?.toPlainString(),
)

/** Types the user can pick in the add screen, in the order they are offered. */
val UserSelectableTxTypes = listOf(
    TxType.BUY,
    TxType.SELL,
    TxType.TRANSFER_IN,
    TxType.TRANSFER_OUT,
    TxType.DIVIDEND,
    TxType.STAKING_REWARD,
    TxType.AIRDROP,
)

/**
 * The types worth offering for an asset class.
 *
 * Staking on a share and dividends on a coin are both nonsense, and a form that
 * offers them invites a row that can never be right.
 */
fun txTypesFor(assetClass: com.eddies.app.domain.AssetClass): List<TxType> = when (assetClass) {
    com.eddies.app.domain.AssetClass.STOCK -> UserSelectableTxTypes - TxType.STAKING_REWARD
    com.eddies.app.domain.AssetClass.CRYPTO -> UserSelectableTxTypes - TxType.DIVIDEND
    com.eddies.app.domain.AssetClass.CASH -> listOf(TxType.TRANSFER_IN, TxType.TRANSFER_OUT)
}

val TxType.label: String
    get() = when (this) {
        TxType.BUY -> "Buy"
        TxType.SELL -> "Sell"
        TxType.TRANSFER_IN -> "Transfer in"
        TxType.TRANSFER_OUT -> "Transfer out"
        TxType.STAKING_REWARD -> "Staking reward"
        TxType.AIRDROP -> "Airdrop"
        TxType.FEE -> "Fee"
        TxType.DIVIDEND -> "Dividend"
    }

val TxSource.label: String
    get() = when (this) {
        TxSource.MANUAL -> "Manual"
        TxSource.IMPORT_CSV -> "CSV import"
        TxSource.IMPORT_KOIOS -> "Cardano rewards"
    }
