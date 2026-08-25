package com.eddies.app.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import com.eddies.app.data.db.entity.AccountEntity
import com.eddies.app.data.db.entity.AssetEntity
import com.eddies.app.data.db.entity.AssetSourceRefEntity
import com.eddies.app.data.db.entity.FxRateEntity
import com.eddies.app.data.db.entity.PortfolioSnapshotEntity
import com.eddies.app.data.db.entity.PriceSnapshotEntity
import com.eddies.app.data.db.entity.TransactionEntity
import com.eddies.app.data.db.entity.WatchlistEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AssetDao {
    @Upsert
    suspend fun upsert(assets: List<AssetEntity>)

    @Upsert
    suspend fun upsert(asset: AssetEntity)

    @Query("SELECT * FROM assets WHERE id = :id")
    suspend fun byId(id: String): AssetEntity?

    @Query("SELECT * FROM assets WHERE id IN (:ids)")
    suspend fun byIds(ids: List<String>): List<AssetEntity>

    @Query("SELECT * FROM assets WHERE id IN (:ids)")
    fun observeByIds(ids: List<String>): Flow<List<AssetEntity>>

    @Query("SELECT * FROM assets")
    fun observeAll(): Flow<List<AssetEntity>>

    /**
     * Search ranks an exact ticker first, then a prefix, then anything
     * containing the term, and orders by market cap inside each band. Without
     * the banding, typing "BTC" surfaces every wrapped and bridged derivative
     * before Bitcoin itself.
     */
    @Query(
        """
        SELECT * FROM assets
        WHERE symbol LIKE :term || '%' OR name LIKE '%' || :term || '%'
        ORDER BY
            CASE WHEN UPPER(symbol) = UPPER(:term) THEN 0
                 WHEN UPPER(symbol) LIKE UPPER(:term) || '%' THEN 1
                 WHEN UPPER(name) LIKE UPPER(:term) || '%' THEN 2
                 ELSE 3 END,
            CASE WHEN rank IS NULL THEN 1 ELSE 0 END,
            rank ASC,
            name ASC
        LIMIT :limit
        """,
    )
    suspend fun search(term: String, limit: Int = 50): List<AssetEntity>

    @Query("SELECT * FROM assets ORDER BY CASE WHEN rank IS NULL THEN 1 ELSE 0 END, rank ASC LIMIT :limit")
    suspend fun topByRank(limit: Int = 100): List<AssetEntity>

    @Query("UPDATE assets SET tracked = :tracked WHERE id = :id")
    suspend fun setTracked(id: String, tracked: Boolean)

    @Query("SELECT COUNT(*) FROM assets")
    suspend fun count(): Int
}

@Dao
interface AssetSourceRefDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(refs: List<AssetSourceRefEntity>)

    @Query("SELECT * FROM asset_source_refs")
    fun observeAll(): Flow<List<AssetSourceRefEntity>>

    @Query("SELECT * FROM asset_source_refs WHERE assetId IN (:assetIds)")
    suspend fun forAssets(assetIds: List<String>): List<AssetSourceRefEntity>

    @Query("DELETE FROM asset_source_refs WHERE assetId = :assetId AND source = :source")
    suspend fun clearFor(assetId: String, source: String)
}

@Dao
interface AccountDao {
    @Upsert
    suspend fun upsert(account: AccountEntity): Long

    @Query("SELECT * FROM accounts ORDER BY name")
    fun observeAll(): Flow<List<AccountEntity>>

    @Query("SELECT * FROM accounts WHERE id = :id")
    suspend fun byId(id: Long): AccountEntity?

    @Query("SELECT * FROM accounts WHERE stakingAddress IS NOT NULL AND stakingAddress != ''")
    suspend fun withStakingAddress(): List<AccountEntity>

    @Query("SELECT * FROM accounts ORDER BY name")
    suspend fun all(): List<AccountEntity>

    @Query("DELETE FROM accounts WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT COUNT(*) FROM accounts")
    suspend fun count(): Int
}

@Dao
interface TransactionDao {
    @Upsert
    suspend fun upsert(tx: TransactionEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnoringDuplicates(txs: List<TransactionEntity>): List<Long>

    @Query("SELECT * FROM transactions ORDER BY timestamp DESC")
    fun observeAll(): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions WHERE assetId = :assetId ORDER BY timestamp DESC")
    fun observeForAsset(assetId: String): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions WHERE id = :id")
    suspend fun byId(id: Long): TransactionEntity?

    @Query("SELECT * FROM transactions")
    suspend fun all(): List<TransactionEntity>

    /** The assets to subscribe prices for. An empty portfolio opens no socket. */
    @Query("SELECT DISTINCT assetId FROM transactions")
    fun observeHeldAssetIds(): Flow<List<String>>

    @Query("SELECT DISTINCT assetId FROM transactions")
    suspend fun heldAssetIds(): List<String>

    @Query("DELETE FROM transactions WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM transactions")
    suspend fun deleteAll()

    @Query("SELECT MIN(timestamp) FROM transactions")
    suspend fun earliestTimestamp(): Long?
}

@Dao
interface PriceDao {
    @Upsert
    suspend fun upsert(snapshots: List<PriceSnapshotEntity>)

    /** The most recent snapshot per asset, for a cold start before the feed connects. */
    @Query(
        """
        SELECT p.* FROM price_snapshots p
        INNER JOIN (SELECT assetId, MAX(timestamp) AS ts FROM price_snapshots GROUP BY assetId) latest
        ON p.assetId = latest.assetId AND p.timestamp = latest.ts
        """,
    )
    suspend fun latestPerAsset(): List<PriceSnapshotEntity>

    @Query("SELECT * FROM price_snapshots WHERE assetId = :assetId AND timestamp >= :since ORDER BY timestamp")
    suspend fun history(assetId: String, since: Long): List<PriceSnapshotEntity>

    @Query("DELETE FROM price_snapshots WHERE timestamp < :before")
    suspend fun prune(before: Long)
}

@Dao
interface FxDao {
    @Upsert
    suspend fun upsert(rates: List<FxRateEntity>)

    @Query("SELECT * FROM fx_rates WHERE day = (SELECT MAX(day) FROM fx_rates)")
    fun observeLatest(): Flow<List<FxRateEntity>>

    @Query("SELECT * FROM fx_rates WHERE quote = :quote AND day <= :day ORDER BY day DESC LIMIT 1")
    suspend fun onOrBefore(quote: String, day: String): FxRateEntity?

    @Query("SELECT * FROM fx_rates")
    suspend fun all(): List<FxRateEntity>

    @Query("SELECT * FROM fx_rates")
    fun observeAll(): Flow<List<FxRateEntity>>

    @Query("SELECT MAX(day) FROM fx_rates")
    suspend fun latestDay(): String?
}

@Dao
interface PortfolioSnapshotDao {
    @Upsert
    suspend fun upsert(snapshot: PortfolioSnapshotEntity)

    @Upsert
    suspend fun upsert(snapshots: List<PortfolioSnapshotEntity>)

    @Query("SELECT * FROM portfolio_snapshots WHERE day >= :fromDay ORDER BY day")
    fun observeFrom(fromDay: String): Flow<List<PortfolioSnapshotEntity>>

    @Query("SELECT * FROM portfolio_snapshots ORDER BY day")
    suspend fun all(): List<PortfolioSnapshotEntity>

    @Query("SELECT COUNT(*) FROM portfolio_snapshots")
    suspend fun count(): Int

    @Query("DELETE FROM portfolio_snapshots")
    suspend fun deleteAll()
}

@Dao
interface WatchlistDao {
    @Upsert
    suspend fun add(entry: WatchlistEntity)

    @Query("DELETE FROM watchlist WHERE assetId = :assetId")
    suspend fun remove(assetId: String)

    @Query("SELECT * FROM watchlist ORDER BY addedAt DESC")
    fun observeAll(): Flow<List<WatchlistEntity>>

    @Query("SELECT assetId FROM watchlist")
    suspend fun assetIds(): List<String>
}
