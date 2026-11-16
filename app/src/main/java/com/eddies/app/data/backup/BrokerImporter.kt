package com.eddies.app.data.backup

import com.eddies.app.data.repo.AssetRepository
import com.eddies.app.data.repo.TransactionRepository
import com.eddies.app.domain.Asset
import com.eddies.app.domain.AssetClass
import com.eddies.app.domain.Transaction
import com.eddies.app.domain.TxSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** What became of a broker file. */
data class BrokerImportResult(
    val broker: Broker,
    val imported: Int,
    /** Rows already in the ledger, so left alone. */
    val alreadyHeld: Int,
    /** Rows the parser could not read. */
    val unreadable: Int,
    /** Instruments no asset could be found for, by ISIN or ticker, and how many rows each cost. */
    val unresolved: Map<String, Int>,
)

/**
 * Turns parsed broker rows into ledger rows.
 *
 * [BrokerCsv] knows what the file says. This knows what the app calls the
 * things in it: a coin by its ticker in the bundled seed, a share by looking
 * its ISIN up online.
 *
 * A row whose instrument cannot be identified is left out and reported by
 * name. Filing it under a guess would put a wrong holding in the portfolio,
 * where it is much harder to notice than a line in a summary.
 */
@Singleton
class BrokerImporter @Inject constructor(
    private val assets: AssetRepository,
    private val transactions: TransactionRepository,
) {
    suspend fun import(parsed: BrokerParse, accountId: Long): BrokerImportResult = withContext(Dispatchers.IO) {
        // One lookup per instrument, not per row: a year of monthly purchases
        // of one fund is one request.
        val resolved = HashMap<String, Asset?>()
        for (row in parsed.rows) {
            if (row.instrumentKey in resolved) continue
            resolved[row.instrumentKey] = when (row.assetClass) {
                AssetClass.STOCK -> row.isin?.let { assets.shareByIsin(it) }
                else -> row.symbol?.let { assets.coinBySymbol(it) }
            }
        }

        val unresolved = LinkedHashMap<String, Int>()
        val ledger = ArrayList<Transaction>()
        for (row in parsed.rows) {
            val asset = resolved[row.instrumentKey]
            if (asset == null) {
                val label = listOfNotNull(row.name, row.instrumentKey.takeIf { it.isNotEmpty() })
                    .joinToString(" ").ifEmpty { "an unnamed instrument" }
                unresolved.merge(label, 1, Int::plus)
                continue
            }
            ledger += Transaction(
                accountId = accountId,
                assetId = asset.id,
                type = row.type,
                quantity = row.quantity,
                pricePerUnit = row.pricePerUnit,
                quoteCurrency = row.currency,
                feeQuantity = row.fee,
                feeAssetId = row.feeCurrency,
                timestamp = row.timestamp,
                source = TxSource.IMPORT_CSV,
                externalId = row.externalId,
            )
        }

        resolved.values.filterNotNull().forEach { assets.setTracked(it.id, true) }
        val imported = transactions.importDeduplicated(ledger)
        BrokerImportResult(
            broker = parsed.broker,
            imported = imported,
            alreadyHeld = ledger.size - imported,
            unreadable = parsed.skipped,
            unresolved = unresolved,
        )
    }
}
