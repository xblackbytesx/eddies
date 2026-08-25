package com.eddies.app.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.eddies.app.domain.AssetClass
import com.eddies.app.domain.PriceSourceId
import com.eddies.app.domain.TxSource
import com.eddies.app.domain.TxType

/**
 * An asset as the app knows it. The id is prefixed by class
 * ("crypto:btc-bitcoin", "stock:NASDAQ:AAPL") and every other table keys on that
 * opaque string, which is what lets Phase 3 equities arrive without migrating
 * the ledger.
 */
@Entity(tableName = "assets", indices = [Index("symbol"), Index("rank")])
data class AssetEntity(
    @PrimaryKey val id: String,
    val assetClass: AssetClass,
    val symbol: String,
    val name: String,
    val decimals: Int = 8,
    val iconSlug: String? = null,
    val rank: Int? = null,
    /** True once the user holds it or watches it, so search can rank it first. */
    val tracked: Boolean = false,
)

/**
 * How one source spells one asset. Several rows per asset is normal: Kraken
 * quotes XBT/USD and XBT/EUR, Binance quotes BTCUSDT, CoinPaprika says
 * btc-bitcoin.
 */
@Entity(
    tableName = "asset_source_refs",
    primaryKeys = ["assetId", "source", "sourceSymbol"],
    indices = [Index("source", "sourceSymbol"), Index("assetId")],
    foreignKeys = [
        ForeignKey(
            entity = AssetEntity::class,
            parentColumns = ["id"],
            childColumns = ["assetId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class AssetSourceRefEntity(
    val assetId: String,
    val source: PriceSourceId,
    val sourceSymbol: String,
    val quoteCurrency: String? = null,
)

/** A wallet, exchange or broker the user groups positions under. */
@Entity(tableName = "accounts")
data class AccountEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val kind: String = "WALLET",
    /** Cardano stake address and similar, for Phase 2 reward import. */
    val stakingAddress: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
)

/**
 * One ledger row. A position's quantity is derived from these and never stored,
 * so correcting a typo cannot leave a stale balance behind.
 *
 * Quantities and prices are TEXT holding a BigDecimal via
 * [com.eddies.app.data.db.Converters]. Not REAL: an 18-decimal token balance does
 * not survive a double, and the failure mode is a wrong net worth with nothing
 * in the logs.
 */
@Entity(
    tableName = "transactions",
    indices = [
        Index("assetId"),
        Index("timestamp"),
        Index("accountId"),
        // Makes the Phase 2 reward import idempotent: re-running a sync cannot
        // double-count an epoch.
        Index(value = ["source", "externalId"], unique = true),
    ],
)
data class TransactionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val accountId: Long = 0,
    val assetId: String,
    val type: TxType,
    val quantity: String,
    val pricePerUnit: String? = null,
    val quoteCurrency: String = "USD",
    val feeQuantity: String? = null,
    val feeAssetId: String? = null,
    val timestamp: Long,
    val note: String? = null,
    val source: TxSource = TxSource.MANUAL,
    val externalId: String? = null,
)

/**
 * Last known price per asset, so a cold start shows numbers before the socket
 * opens.
 *
 * One row per asset, upserted. NOT one row per tick: Kraken's ticker fires on
 * every trade, so a row per tick is tens of thousands of writes a day for a
 * single liquid pair, an unbounded table and constant disk I/O on a phone.
 * Charts read [PriceCandleEntity] instead.
 */
@Entity(tableName = "price_latest")
data class PriceLatestEntity(
    @PrimaryKey val assetId: String,
    val timestamp: Long,
    val price: String,
    val currency: String,
    val source: PriceSourceId = PriceSourceId.MANUAL,
)

/** How wide one candle is. Charts pick the coarsest that fills the range. */
enum class CandleInterval(val minutes: Int) {
    HOUR(60),
    DAY(1440),
}

/**
 * A historical price candle, fetched from whichever feed the user selected.
 *
 * The interval is part of the key so hourly and daily series for the same asset
 * coexist: a 1D chart needs hourly resolution and a 1Y chart would be unusable
 * at that granularity.
 */
@Entity(
    tableName = "price_candles",
    primaryKeys = ["assetId", "interval", "timestamp"],
    indices = [Index("assetId", "interval")],
)
data class PriceCandleEntity(
    val assetId: String,
    val interval: CandleInterval,
    val timestamp: Long,
    val close: String,
    val high: String? = null,
    val low: String? = null,
    val currency: String,
    val source: PriceSourceId = PriceSourceId.MANUAL,
)

/**
 * Daily FX against the pivot. The ECB publishes once per working day, so a day
 * granularity is the real resolution of the data, not a shortcut.
 */
@Entity(tableName = "fx_rates", primaryKeys = ["quote", "day"])
data class FxRateEntity(
    val quote: String,
    val day: String,
    val rate: String,
    val fetchedAt: Long = System.currentTimeMillis(),
)

/**
 * One row per day of total portfolio value.
 *
 * The portfolio value chart cannot be drawn from the ledger alone: it needs a
 * historical price for every held asset back to the first purchase. Snapshotting
 * forward is cheap, exact, and keeps working when a price API goes away.
 */
@Entity(tableName = "portfolio_snapshots")
data class PortfolioSnapshotEntity(
    @PrimaryKey val day: String,
    val totalValue: String,
    val costBasis: String,
    val currency: String,
    val takenAt: Long = System.currentTimeMillis(),
)

/** A coin the user is watching but does not hold. */
@Entity(tableName = "watchlist")
data class WatchlistEntity(
    @PrimaryKey val assetId: String,
    val addedAt: Long = System.currentTimeMillis(),
)
