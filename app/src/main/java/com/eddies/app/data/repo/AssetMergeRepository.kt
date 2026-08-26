package com.eddies.app.data.repo

import androidx.room.withTransaction
import com.eddies.app.data.db.EddiesDatabase
import com.eddies.app.data.db.dao.AssetDao
import com.eddies.app.data.db.dao.CorporateActionDao
import com.eddies.app.data.db.dao.CustodyDao
import com.eddies.app.data.db.dao.PriceDao
import com.eddies.app.data.db.dao.StakingDao
import com.eddies.app.data.db.dao.TransactionDao
import com.eddies.app.data.db.dao.WatchlistDao
import com.eddies.app.data.db.dao.AssetSourceRefDao
import com.eddies.app.domain.DuplicateFinder
import com.eddies.app.domain.DuplicateGroup
import com.eddies.app.domain.PriceSourceId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** What a merge would move, shown before anything is written. */
data class MergePreview(
    val group: DuplicateGroup,
    /** Ledger rows that would change asset, per asset being merged away. */
    val transactionCounts: Map<String, Int>,
    val custodyMoves: Int,
    val splitMoves: Int,
    /** Asset id to ISIN, where one is known. Shown so the user can check the group. */
    val isins: Map<String, String> = emptyMap(),
) {
    val totalTransactions: Int get() = transactionCounts.values.sum()
}

/**
 * Combines assets that turned out to be the same instrument.
 *
 * This exists to repair ledgers written before Tradegate assets adopted the id
 * of the listing they resolve to. Before that, one ETF bought three times could
 * sit in the portfolio as two rows, and the position, cost basis and allocation
 * were all wrong as a result.
 *
 * Deliberately user-initiated with a preview rather than an automatic migration.
 * A migration cannot ask, cannot be declined, and cannot be undone, and the
 * matching rule (see [DuplicateFinder]) is a heuristic over the user's own data.
 * Being wrong here silently welds two real positions together.
 */
@Singleton
class AssetMergeRepository @Inject constructor(
    private val db: EddiesDatabase,
    private val assetDao: AssetDao,
    private val transactionDao: TransactionDao,
    private val custodyDao: CustodyDao,
    private val watchlistDao: WatchlistDao,
    private val sourceRefDao: AssetSourceRefDao,
    private val corporateActionDao: CorporateActionDao,
    private val stakingDao: StakingDao,
    private val priceDao: PriceDao,
) {

    /** Duplicates worth offering, newest counts each time rather than cached. */
    suspend fun findDuplicates(): List<MergePreview> = withContext(Dispatchers.IO) {
        val counts = transactionDao.countsByAsset().associate { it.assetId to it.count }
        val assets = assetDao.byIds(counts.keys.toList() + watchlistDao.assetIds())
            .map { it.toDomain() }
        val custody = custodyDao.all().map { it.assetId }.toSet()

        // The ISIN is what makes a suggestion trustworthy, so it is gathered
        // before matching and not merely displayed afterwards. Two holdings
        // with different ISINs are different instruments and must never be
        // offered as one, however alike their tickers look.
        val isins = sourceRefDao.forSource(PriceSourceId.TRADEGATE)
            .associate { it.assetId to it.sourceSymbol }
        DuplicateFinder.find(assets, counts, isins)
            .map { group -> preview(group, counts, custody, isins) }
    }

    private suspend fun preview(
        group: DuplicateGroup,
        counts: Map<String, Int>,
        custody: Set<String>,
        isins: Map<String, String>,
    ): MergePreview {
        var splits = 0
        for (asset in group.merge) splits += corporateActionDao.forAsset(asset.id).size
        return MergePreview(
            group = group,
            transactionCounts = group.merge.associate { it.id to (counts[it.id] ?: 0) },
            // The kept asset's own custody entry stays put and is not a move.
            custodyMoves = group.merge.count { it.id in custody },
            splitMoves = splits,
            isins = group.all.mapNotNull { a -> isins[a.id]?.let { a.id to it } }.toMap(),
        )
    }

    /**
     * Folds every asset in [group] into its `keep`, in one database transaction.
     *
     * All of it lands or none of it does. A partial merge would leave
     * transactions pointing at an asset row that no longer exists, and those
     * rows would then be invisible in every screen while still being in the
     * database.
     */
    suspend fun merge(group: DuplicateGroup): Int = withContext(Dispatchers.IO) {
        var moved = 0
        db.withTransaction {
            for (from in group.merge) {
                if (from.id == group.keep.id) continue
                moved += transactionDao.reassign(from.id, group.keep.id)

                // Each of these moves what it can and then drops whatever did
                // not move. A row is left behind when the target already has an
                // equivalent one, and only asset_source_refs has a foreign key
                // to cascade it away: without the clear, a custody entry outlives
                // the asset it describes and "stored at" reads from a ghost.
                custodyDao.reassign(from.id, group.keep.id)
                custodyDao.clear(from.id)
                watchlistDao.reassign(from.id, group.keep.id)
                watchlistDao.remove(from.id)
                corporateActionDao.reassign(from.id, group.keep.id)
                corporateActionDao.clear(from.id)
                stakingDao.reassign(from.id, group.keep.id)
                // Before deleting the asset: source refs cascade with it, and
                // the Tradegate ref is what keeps the merged position priced
                // from the venue the user actually holds it at.
                sourceRefDao.reassign(from.id, group.keep.id)
                priceDao.clearCandles(from.id)
                priceDao.clearLatest(from.id)
                assetDao.delete(from.id)
            }
        }
        moved
    }
}
