package com.eddies.app.di

import android.content.Context
import androidx.room.Room
import com.eddies.app.core.crypto.DatabaseKeyProvider
import com.eddies.app.data.db.EddiesDatabase
import com.eddies.app.data.db.dao.AccountDao
import com.eddies.app.data.db.dao.AssetDao
import com.eddies.app.data.db.dao.AssetSourceRefDao
import com.eddies.app.data.db.dao.FxDao
import com.eddies.app.data.db.dao.PortfolioSnapshotDao
import com.eddies.app.data.db.dao.PriceDao
import com.eddies.app.data.db.dao.TransactionDao
import com.eddies.app.data.db.dao.WatchlistDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext context: Context,
        keyProvider: DatabaseKeyProvider,
    ): EddiesDatabase {
        // The native library must be loaded before the factory is constructed.
        // Doing it lazily inside the factory throws UnsatisfiedLinkError on the
        // first query instead of here, where the cause is obvious.
        System.loadLibrary("sqlcipher")

        val factory = SupportOpenHelperFactory(keyProvider.passphrase())
        return Room.databaseBuilder(context, EddiesDatabase::class.java, EddiesDatabase.NAME)
            .openHelperFactory(factory)
            // Never fallbackToDestructiveMigration: a hand-entered ledger has no
            // other copy to restore from. Migrations are explicit, and only the
            // price cache tables may be dropped in one.
            .addMigrations(EddiesDatabase.MIGRATION_1_2)
            .build()
    }

    @Provides fun provideAssetDao(db: EddiesDatabase): AssetDao = db.assetDao()
    @Provides fun provideAssetSourceRefDao(db: EddiesDatabase): AssetSourceRefDao = db.assetSourceRefDao()
    @Provides fun provideAccountDao(db: EddiesDatabase): AccountDao = db.accountDao()
    @Provides fun provideTransactionDao(db: EddiesDatabase): TransactionDao = db.transactionDao()
    @Provides fun providePriceDao(db: EddiesDatabase): PriceDao = db.priceDao()
    @Provides fun provideFxDao(db: EddiesDatabase): FxDao = db.fxDao()
    @Provides fun providePortfolioSnapshotDao(db: EddiesDatabase): PortfolioSnapshotDao = db.portfolioSnapshotDao()
    @Provides fun provideWatchlistDao(db: EddiesDatabase): WatchlistDao = db.watchlistDao()
}
