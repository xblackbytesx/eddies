package com.eddies.app.demo

import com.eddies.app.data.db.dao.AccountDao
import com.eddies.app.data.db.dao.AssetDao
import com.eddies.app.data.db.dao.AssetSourceRefDao
import com.eddies.app.data.db.dao.CustodyDao
import com.eddies.app.data.db.dao.TransactionDao
import com.eddies.app.data.db.dao.WatchlistDao
import com.eddies.app.data.db.entity.AccountEntity
import com.eddies.app.data.db.entity.AssetCustodyEntity
import com.eddies.app.data.db.entity.AssetEntity
import com.eddies.app.data.db.entity.AssetSourceRefEntity
import com.eddies.app.data.db.entity.TransactionEntity
import com.eddies.app.data.db.entity.WatchlistEntity
import com.eddies.app.data.prefs.SettingsDataStore
import com.eddies.app.data.stocks.TradegateSource
import com.eddies.app.domain.PriceSourceId
import com.eddies.app.domain.TxSource
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Writes the demo portfolio on first launch.
 *
 * Only ever compiled into the demo flavour, which has its own applicationId and
 * therefore its own database. It cannot reach the real ledger even if it tried.
 *
 * Everything downstream of the seed is the real app: real price feeds, real
 * Koios staking, real Yahoo history, real cost basis. Only the transactions are
 * invented, which is what makes the screenshots honest.
 */
@Singleton
class RealDemoSeeder @Inject constructor(
    private val assetDao: AssetDao,
    private val sourceRefDao: AssetSourceRefDao,
    private val accountDao: AccountDao,
    private val transactionDao: TransactionDao,
    private val custodyDao: CustodyDao,
    private val watchlistDao: WatchlistDao,
    private val settings: SettingsDataStore,
) : DemoSeeder {

    override suspend fun seedIfNeeded() = withContext(Dispatchers.IO) {
        // Every row carries a source of IMPORT_CSV with a demo externalId, and
        // the ledger has a unique index on that pair, so re-running is a no-op
        // rather than a doubled portfolio.
        if (transactionDao.heldAssetIds().isNotEmpty()) return@withContext

        assetDao.upsert(
            DemoPortfolio.assets.map {
                AssetEntity(
                    id = it.id,
                    assetClass = it.assetClass,
                    symbol = it.symbol,
                    name = it.name,
                    decimals = it.decimals,
                    iconSlug = it.iconSlug,
                    rank = it.rank,
                    tracked = true,
                )
            },
        )

        // The Tradegate holding needs both refs, or its chart has nothing to
        // draw from: Tradegate prices it, Yahoo supplies the history.
        DemoPortfolio.assets.filter { it.yahooSymbol != null }.forEach { asset ->
            runCatching {
                sourceRefDao.upsert(
                    listOf(
                        AssetSourceRefEntity(
                            asset.id, PriceSourceId.TRADEGATE,
                            asset.id.substringAfterLast(':'), TradegateSource.CURRENCY,
                        ),
                        AssetSourceRefEntity(asset.id, PriceSourceId.YAHOO, asset.yahooSymbol!!, null),
                    ),
                )
            }
        }

        val main = accountDao.upsert(AccountEntity(name = "Main"))
        // A real public stake address, so the staking figure is fetched live
        // through the real Koios path rather than faked.
        accountDao.upsert(
            AccountEntity(name = "Staking", stakingAddress = DemoPortfolio.STAKE_ADDRESS),
        )

        transactionDao.insertIgnoringDuplicates(
            DemoPortfolio.transactions.mapIndexed { index, tx ->
                TransactionEntity(
                    accountId = main,
                    assetId = tx.assetId,
                    type = tx.type,
                    quantity = tx.quantity,
                    pricePerUnit = tx.price,
                    quoteCurrency = "EUR",
                    timestamp = DemoPortfolio.epochMillis(tx.date),
                    note = tx.note,
                    source = TxSource.IMPORT_CSV,
                    externalId = "demo-$index",
                    cashAmount = tx.cash,
                )
            },
        )

        DemoPortfolio.custody.forEach {
            custodyDao.upsert(
                AssetCustodyEntity(assetId = it.assetId, type = it.type, label = it.label, note = it.note),
            )
        }

        DemoPortfolio.watchlist.forEach { watchlistDao.add(WatchlistEntity(assetId = it)) }

        // Screenshot-friendly defaults: balances visible, the extra columns on,
        // and no lock screen between launching and shooting.
        settings.setHideBalances(false)
        settings.setAdvancedMode(true)
        settings.setAppLockEnabled(false)
        // Cosmetic only: the demo policy ignores this outright. Set so a
        // screenshot of the settings screen does not show a toggle that is being
        // overridden underneath it.
        settings.setHideInRecents(false)
        settings.setOnboarded()
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class DemoModule {
    @Binds
    abstract fun bindSeeder(impl: RealDemoSeeder): DemoSeeder
}
