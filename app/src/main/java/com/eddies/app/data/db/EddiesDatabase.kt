package com.eddies.app.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import com.eddies.app.data.db.dao.AccountDao
import com.eddies.app.data.db.dao.AssetDao
import com.eddies.app.data.db.dao.AssetSourceRefDao
import com.eddies.app.data.db.dao.CustodyDao
import com.eddies.app.data.db.dao.StakingDao
import com.eddies.app.data.db.dao.FxDao
import com.eddies.app.data.db.dao.PortfolioSnapshotDao
import com.eddies.app.data.db.dao.PriceDao
import com.eddies.app.data.db.dao.TransactionDao
import com.eddies.app.data.db.dao.WatchlistDao
import com.eddies.app.data.db.entity.AccountEntity
import com.eddies.app.data.db.entity.AssetEntity
import com.eddies.app.data.db.entity.AssetCustodyEntity
import com.eddies.app.data.db.entity.AssetSourceRefEntity
import com.eddies.app.data.db.entity.FxRateEntity
import com.eddies.app.data.db.entity.PortfolioSnapshotEntity
import com.eddies.app.data.db.entity.PriceCandleEntity
import com.eddies.app.data.db.entity.PriceLatestEntity
import com.eddies.app.data.db.entity.StakingBalanceEntity
import com.eddies.app.data.db.entity.TransactionEntity
import com.eddies.app.data.db.entity.WatchlistEntity

/**
 * The ledger.
 *
 * Migrations here are explicit and hand-written, never destructive. Unlike a
 * cache of something a server owns, a transaction the user typed in by hand is
 * not refetchable from anywhere, so dropping the tables on a schema change would
 * destroy the only copy.
 */
@Database(
    entities = [
        AssetEntity::class,
        AssetSourceRefEntity::class,
        AccountEntity::class,
        TransactionEntity::class,
        PriceLatestEntity::class,
        PriceCandleEntity::class,
        AssetCustodyEntity::class,
        StakingBalanceEntity::class,
        FxRateEntity::class,
        PortfolioSnapshotEntity::class,
        WatchlistEntity::class,
    ],
    version = 4,
    // Exported and committed, unlike the sibling projects.
    //
    // app/schemas/<db>/N.json holds the CREATE statements Room actually
    // generates, so a hand-written migration can be diffed against them before
    // it ships. Room compares the two at open time and a mismatch is a crash on
    // launch, not a warning, which makes this the cheapest possible check.
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class EddiesDatabase : RoomDatabase() {
    abstract fun assetDao(): AssetDao
    abstract fun assetSourceRefDao(): AssetSourceRefDao
    abstract fun accountDao(): AccountDao
    abstract fun transactionDao(): TransactionDao
    abstract fun priceDao(): PriceDao
    abstract fun fxDao(): FxDao
    abstract fun portfolioSnapshotDao(): PortfolioSnapshotDao
    abstract fun watchlistDao(): WatchlistDao
    abstract fun custodyDao(): CustodyDao
    abstract fun stakingDao(): StakingDao

    companion object {
        const val NAME = "eddies.db"

        /**
         * v2: price_snapshots becomes price_latest (one row per asset) plus
         * price_candles (the chart series).
         *
         * The old table was written on every tick, which for a liquid pair is
         * tens of thousands of rows a day and an unbounded database.
         *
         * Dropping it is safe and is the only destructive step allowed anywhere
         * in this schema: every price is refetchable in one call. The ledger is
         * never touched, because a transaction typed in by hand has no other copy.
         *
         * The CREATE statements must match Room's generated schema exactly,
         * column order included. Room compares them at open time and a mismatch
         * is a crash on launch, not a warning.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL("DROP TABLE IF EXISTS `price_snapshots`")
                connection.execSQL(
                    "CREATE TABLE IF NOT EXISTS `price_latest` (" +
                        "`assetId` TEXT NOT NULL, `timestamp` INTEGER NOT NULL, " +
                        "`price` TEXT NOT NULL, `currency` TEXT NOT NULL, " +
                        "`source` TEXT NOT NULL, PRIMARY KEY(`assetId`))",
                )
                connection.execSQL(
                    "CREATE TABLE IF NOT EXISTS `price_candles` (" +
                        "`assetId` TEXT NOT NULL, `interval` TEXT NOT NULL, " +
                        "`timestamp` INTEGER NOT NULL, `close` TEXT NOT NULL, " +
                        "`high` TEXT, `low` TEXT, `currency` TEXT NOT NULL, " +
                        "`source` TEXT NOT NULL, " +
                        "PRIMARY KEY(`assetId`, `interval`, `timestamp`))",
                )
                connection.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_price_candles_assetId_interval` " +
                        "ON `price_candles` (`assetId`, `interval`)",
                )
            }
        }

        /**
         * v3: asset_custody, "where is this coin actually kept".
         *
         * Purely additive. The SQL below was diffed against app/schemas/3.json
         * before shipping, because Room compares them at open time and a
         * mismatch is a crash on launch rather than a warning.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    "CREATE TABLE IF NOT EXISTS `asset_custody` (" +
                        "`assetId` TEXT NOT NULL, `type` TEXT NOT NULL, " +
                        "`label` TEXT NOT NULL, `note` TEXT, " +
                        "`updatedAt` INTEGER NOT NULL, PRIMARY KEY(`assetId`))",
                )
            }
        }

        /** v4: staking_balances, the live per-address staking figures. Additive. */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    "CREATE TABLE IF NOT EXISTS `staking_balances` (" +
                        "`stakeAddress` TEXT NOT NULL, `assetId` TEXT NOT NULL, " +
                        "`accountId` INTEGER NOT NULL, `pending` TEXT NOT NULL, " +
                        "`totalEarned` TEXT NOT NULL, `poolId` TEXT, " +
                        "`syncedAt` INTEGER NOT NULL, `error` TEXT, " +
                        "PRIMARY KEY(`stakeAddress`))",
                )
            }
        }
    }
}
