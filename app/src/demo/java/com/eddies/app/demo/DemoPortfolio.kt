package com.eddies.app.demo

import com.eddies.app.data.db.entity.CustodyType
import com.eddies.app.domain.AssetClass
import com.eddies.app.domain.AssetIds
import com.eddies.app.domain.TxType
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * The portfolio a screenshot shows.
 *
 * Chosen to exercise every feature that is worth showing rather than to look
 * impressive: a coin that stakes, a share that pays dividends and has a real
 * split in its history, a Tradegate holding, several custody locations, a
 * realised loss so the screenshots are not uniformly green, and a watchlist.
 *
 * Fixed dates rather than offsets from today. A position bought "three years
 * ago" that silently becomes four is fine, and it keeps every screenshot session
 * describing the same purchases.
 *
 * Nothing here is anyone's real portfolio. The quantities are round because real
 * ones are not, which is the point.
 */
object DemoPortfolio {

    data class DemoAsset(
        val id: String,
        val symbol: String,
        val name: String,
        val decimals: Int,
        val iconSlug: String?,
        val rank: Int?,
        val assetClass: AssetClass = AssetClass.CRYPTO,
        /** For Tradegate holdings: the Yahoo symbol charts are drawn from. */
        val yahooSymbol: String? = null,
    )

    // date sits directly after price so the common case reads positionally:
    // DemoTx(BTC, BUY, "0.25", "24800", "2024-02-14").
    data class DemoTx(
        val assetId: String,
        val type: TxType,
        val quantity: String,
        val price: String? = null,
        val date: String,
        val cash: String? = null,
        val note: String? = null,
    )

    data class DemoCustody(val assetId: String, val type: CustodyType, val label: String, val note: String? = null)

    val BTC = AssetIds.crypto("btc-bitcoin")
    val ETH = AssetIds.crypto("eth-ethereum")
    val ADA = AssetIds.crypto("ada-cardano")
    val SOL = AssetIds.crypto("sol-solana")
    val AAPL = AssetIds.stock("NASDAQ", "AAPL")
    val ASML = AssetIds.stock("AMSTERDAM", "ASML.AS")
    val IWDA = AssetIds.stock("TRADEGATE", "IE00B4L5Y983")

    val assets = listOf(
        DemoAsset(BTC, "BTC", "Bitcoin", 8, "btc", 1),
        DemoAsset(ETH, "ETH", "Ethereum", 6, "eth", 2),
        DemoAsset(ADA, "ADA", "Cardano", 6, "ada", 20),
        DemoAsset(SOL, "SOL", "Solana", 6, "sol", 6),
        DemoAsset(AAPL, "AAPL", "Apple Inc.", 4, null, null, AssetClass.STOCK),
        DemoAsset(ASML, "ASML.AS", "ASML Holding", 4, null, null, AssetClass.STOCK),
        DemoAsset(
            IWDA, "IWDA.L", "iShares Core MSCI World", 4, null, null, AssetClass.STOCK,
            yahooSymbol = "IWDA.L",
        ),
    )

    /**
     * Bought in instalments rather than all at once, because that is what a
     * real ledger looks like and it gives the cost basis something to average.
     */
    val transactions = listOf(
        // Crypto, accumulated over two years.
        DemoTx(BTC, TxType.BUY, "0.25", "24800", "2024-02-14"),
        DemoTx(BTC, TxType.BUY, "0.15", "41200", "2024-11-08"),
        DemoTx(BTC, TxType.BUY, "0.1", "58400", "2025-06-03"),
        DemoTx(ETH, TxType.BUY, "4", "2280", "2024-03-21"),
        DemoTx(ETH, TxType.BUY, "2", "3010", "2025-01-16"),
        DemoTx(ADA, TxType.BUY, "8000", "0.42", "2024-05-02"),
        DemoTx(ADA, TxType.BUY, "4000", "0.61", "2025-03-11"),

        // A position that did not work out. Screenshots that are all green read
        // as marketing; one realised loss reads as a tool.
        DemoTx(SOL, TxType.BUY, "30", "168", "2025-02-09"),
        DemoTx(SOL, TxType.SELL, "30", "132", "2025-09-22", "Cut it"),

        // Shares. Apple's real 4:1 split in 2020 predates these, so the split
        // machinery is exercised by the fetched history rather than faked here.
        DemoTx(AAPL, TxType.BUY, "40", "168.20", "2024-04-18"),
        DemoTx(AAPL, TxType.DIVIDEND, "0", cash = "9.60", date = "2024-08-15"),
        DemoTx(AAPL, TxType.DIVIDEND, "0", cash = "10.00", date = "2025-02-13"),
        DemoTx(AAPL, TxType.DIVIDEND, "0", cash = "10.40", date = "2025-08-14"),
        DemoTx(ASML, TxType.BUY, "6", "742.50", "2024-07-30"),
        DemoTx(ASML, TxType.BUY, "4", "638.00", "2025-04-07"),
        DemoTx(IWDA, TxType.BUY, "120", "88.40", "2024-01-11"),
        DemoTx(IWDA, TxType.BUY, "60", "103.10", "2025-05-19"),
    )

    /** Several locations, so the "Where it is kept" screen has something to group. */
    val custody = listOf(
        DemoCustody(BTC, CustodyType.HARDWARE_WALLET, "Ledger Nano X", "Drawer, seed in the safe"),
        DemoCustody(ETH, CustodyType.HARDWARE_WALLET, "Ledger Nano X"),
        DemoCustody(ADA, CustodyType.SOFTWARE_WALLET, "Eternl"),
        DemoCustody(AAPL, CustodyType.EXCHANGE, "DEGIRO"),
        DemoCustody(ASML, CustodyType.EXCHANGE, "DEGIRO"),
        DemoCustody(IWDA, CustodyType.EXCHANGE, "Tradegate"),
    )

    /** Watched but not held, so the watchlist is not empty in a screenshot. */
    val watchlist = listOf(
        AssetIds.crypto("dot-polkadot"),
        AssetIds.crypto("link-chainlink"),
    )

    /**
     * A real, public Cardano stake address.
     *
     * Deliberately not invented: the staking figure is fetched live like
     * everything else, so the demo exercises the real Koios path rather than a
     * fake one. It is somebody's public address, visible on any explorer, and
     * carries no private information.
     */
    const val STAKE_ADDRESS = "stake1uyrx65wjqjgeeksd8hptmcgl5jfyrqkfq0xe8xlp367kphsckq250"

    fun epochMillis(date: String): Long =
        LocalDate.parse(date).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
}
