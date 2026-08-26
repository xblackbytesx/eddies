package com.eddies.app.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import com.eddies.app.data.db.entity.AccountEntity
import com.eddies.app.data.db.entity.AssetEntity
import com.eddies.app.data.db.entity.AssetCustodyEntity
import com.eddies.app.data.db.entity.AssetSourceRefEntity
import com.eddies.app.domain.AssetClass
import com.eddies.app.data.db.entity.CandleInterval
import com.eddies.app.data.db.entity.PriceCandleEntity
import com.eddies.app.data.db.entity.PriceLatestEntity
import com.eddies.app.data.db.entity.SplitEventEntity
import com.eddies.app.data.db.entity.StakingBalanceEntity
import com.eddies.app.data.db.entity.FxRateEntity
import com.eddies.app.data.db.entity.PortfolioSnapshotEntity
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
    suspend fun upsertLatest(rows: List<PriceLatestEntity>)

    @Query("SELECT * FROM price_latest")
    suspend fun allLatest(): List<PriceLatestEntity>

    @Upsert
    suspend fun upsertCandles(rows: List<PriceCandleEntity>)

    @Query(
        """
        SELECT * FROM price_candles
        WHERE assetId = :assetId AND interval = :interval AND timestamp >= :since
        ORDER BY timestamp
        """,
    )
    fun observeCandles(assetId: String, interval: CandleInterval, since: Long): Flow<List<PriceCandleEntity>>

    @Query(
        """
        SELECT * FROM price_candles
        WHERE assetId = :assetId AND interval = :interval AND timestamp >= :since
        ORDER BY timestamp
        """,
    )
    suspend fun candles(assetId: String, interval: CandleInterval, since: Long): List<PriceCandleEntity>

    /**
     * The newest candle we hold, which is where a delta fetch resumes from.
     * Null means nothing is cached and the first fetch should pull everything.
     */
    @Query("SELECT MAX(timestamp) FROM price_candles WHERE assetId = :assetId AND interval = :interval")
    suspend fun newestCandle(assetId: String, interval: CandleInterval): Long?

    @Query("SELECT MIN(timestamp) FROM price_candles WHERE assetId = :assetId AND interval = :interval")
    suspend fun oldestCandle(assetId: String, interval: CandleInterval): Long?

    /** Daily closes for every asset in one query, for the portfolio backfill. */
    @Query(
        """
        SELECT * FROM price_candles
        WHERE interval = 'DAY' AND assetId IN (:assetIds) AND timestamp >= :since
        ORDER BY timestamp
        """,
    )
    suspend fun dailyForAssets(assetIds: List<String>, since: Long): List<PriceCandleEntity>

    /** Hourly candles age out; daily ones are the long-term series and are kept. */
    @Query("DELETE FROM price_candles WHERE interval = 'HOUR' AND timestamp < :before")
    suspend fun pruneHourly(before: Long)
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

    @Query(
        "SELECT * FROM portfolio_snapshots WHERE day >= :fromDay AND assetClass = :assetClass ORDER BY day",
    )
    fun observeFrom(fromDay: String, assetClass: AssetClass): Flow<List<PortfolioSnapshotEntity>>

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

@Dao
interface CustodyDao {
    @Upsert
    suspend fun upsert(entry: AssetCustodyEntity)

    @Query("SELECT * FROM asset_custody WHERE assetId = :assetId")
    fun observe(assetId: String): Flow<AssetCustodyEntity?>

    @Query("SELECT * FROM asset_custody")
    fun observeAll(): Flow<List<AssetCustodyEntity>>

    @Query("SELECT * FROM asset_custody")
    suspend fun all(): List<AssetCustodyEntity>

    @Query("DELETE FROM asset_custody WHERE assetId = :assetId")
    suspend fun clear(assetId: String)

    /**
     * Labels already in use, most recent first, for the suggestion row.
     *
     * This is what stops the set fragmenting into "Kraken", "kraken" and
     * "Kraken exchange": the second coin kept somewhere is a tap, not retyping.
     */
    @Query("SELECT DISTINCT label FROM asset_custody ORDER BY updatedAt DESC LIMIT :limit")
    suspend fun knownLabels(limit: Int = 12): List<String>
}

@Dao
interface StakingDao {
    @Upsert
    suspend fun upsert(entry: StakingBalanceEntity)

    @Query("SELECT * FROM staking_balances")
    fun observeAll(): Flow<List<StakingBalanceEntity>>

    @Query("SELECT * FROM staking_balances")
    suspend fun all(): List<StakingBalanceEntity>

    @Query("DELETE FROM staking_balances WHERE stakeAddress = :stakeAddress")
    suspend fun delete(stakeAddress: String)

    /** Drops rows whose account is gone, so a deleted wallet stops inflating totals. */
    @Query("DELETE FROM staking_balances WHERE accountId NOT IN (SELECT id FROM accounts)")
    suspend fun pruneOrphans()
}

@Dao
interface CorporateActionDao {
    @Upsert
    suspend fun upsert(events: List<SplitEventEntity>)

    @Query("SELECT * FROM corporate_actions WHERE assetId = :assetId ORDER BY timestamp")
    suspend fun forAsset(assetId: String): List<SplitEventEntity>

    @Query("SELECT * FROM corporate_actions ORDER BY timestamp")
    fun observeAll(): Flow<List<SplitEventEntity>>

    @Query("SELECT * FROM corporate_actions ORDER BY timestamp")
    suspend fun all(): List<SplitEventEntity>
}
