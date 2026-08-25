package com.eddies.app.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.eddies.app.data.db.dao.AccountDao
import com.eddies.app.data.db.dao.AssetDao
import com.eddies.app.data.db.dao.AssetSourceRefDao
import com.eddies.app.data.db.dao.FxDao
import com.eddies.app.data.db.dao.PortfolioSnapshotDao
import com.eddies.app.data.db.dao.PriceDao
import com.eddies.app.data.db.dao.TransactionDao
import com.eddies.app.data.db.dao.WatchlistDao
import com.eddies.app.data.db.entity.AccountEntity
import com.eddies.app.data.db.entity.AssetEntity
import com.eddies.app.data.db.entity.AssetSourceRefEntity
import com.eddies.app.data.db.entity.FxRateEntity
import com.eddies.app.data.db.entity.PortfolioSnapshotEntity
import com.eddies.app.data.db.entity.PriceSnapshotEntity
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
        PriceSnapshotEntity::class,
        FxRateEntity::class,
        PortfolioSnapshotEntity::class,
        WatchlistEntity::class,
    ],
    version = 1,
    exportSchema = false,
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

    companion object {
        const val NAME = "eddies.db"

        // Migrations land here as MIGRATION_1_2 and friends, each with a one-line
        // note saying what it adds, and are registered in DatabaseModule.
        //
        // If you write raw CREATE TABLE SQL in one, it must match Room's
        // generated schema exactly, column order included. Room compares them at
        // open time and a mismatch is a crash on launch, not a warning.
    }
}
