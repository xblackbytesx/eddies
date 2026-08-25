package com.eddies.app.domain

/** Every market-data source the app can name a symbol in. */
enum class PriceSourceId { KRAKEN, BINANCE, COINPAPRIKA, COINGECKO, KOIOS, MANUAL }

/**
 * An asset as the app knows it, independent of what any source calls it.
 *
 * [id] is the only identity anything downstream keys on, and it is prefixed by
 * class. That prefix is what lets Phase 3 equities land as a new AssetClass with
 * no migration of the ledger, the snapshots or the price tables.
 */
data class Asset(
    val id: String,
    val assetClass: AssetClass,
    val symbol: String,
    val name: String,
    val decimals: Int = 8,
    val iconSlug: String? = null,
    val rank: Int? = null,
)

/** How one source names one asset. Several rows per asset is the normal case. */
data class AssetSourceRef(
    val assetId: String,
    val source: PriceSourceId,
    val sourceSymbol: String,
    val quoteCurrency: String? = null,
)

object AssetIds {
    fun crypto(slug: String): String = "crypto:$slug"
    fun stock(exchange: String, ticker: String): String = "stock:$exchange:$ticker"
    fun cash(code: String): String = "cash:${code.uppercase()}"

    fun classOf(assetId: String): AssetClass? = when (assetId.substringBefore(':')) {
        "crypto" -> AssetClass.CRYPTO
        "stock" -> AssetClass.STOCK
        "cash" -> AssetClass.CASH
        else -> null
    }
}

/**
 * Translates between the app's asset ids and the symbols each source uses.
 *
 * This exists because the sources genuinely disagree: Kraken calls Bitcoin XBT,
 * Binance calls it BTC, CoinPaprika calls it btc-bitcoin. Worse, tickers are not
 * unique across assets, so nothing may key on a bare symbol.
 */
class AssetResolver(refs: List<AssetSourceRef>) {

    private val byAssetAndSource: Map<Pair<String, PriceSourceId>, List<AssetSourceRef>> =
        refs.groupBy { it.assetId to it.source }

    private val bySourceSymbol: Map<Pair<PriceSourceId, String>, String> =
        refs.associate { (it.source to it.sourceSymbol.uppercase()) to it.assetId }

    /**
     * The best symbol for [assetId] on [source], preferring a pair already
     * quoted in [preferredQuote] so the caller can skip an FX conversion.
     */
    fun toSource(assetId: String, source: PriceSourceId, preferredQuote: String? = null): AssetSourceRef? {
        val candidates = byAssetAndSource[assetId to source].orEmpty()
        if (candidates.isEmpty()) return null
        preferredQuote?.let { quote ->
            candidates.firstOrNull { it.quoteCurrency.equals(quote, ignoreCase = true) }?.let { return it }
        }
        return candidates.firstOrNull { it.quoteCurrency == null } ?: candidates.first()
    }

    /** The asset id a source symbol refers to, or null if the app does not track it. */
    fun fromSource(source: PriceSourceId, sourceSymbol: String): String? =
        bySourceSymbol[source to sourceSymbol.uppercase()]

    fun quotesFor(assetId: String, source: PriceSourceId): List<String> =
        byAssetAndSource[assetId to source].orEmpty().mapNotNull { it.quoteCurrency }
}

/**
 * Kraken's spellings, which differ per API version. Verified against the live
 * endpoints on 2026-08-25, because getting this wrong is silent.
 *
 * WebSocket v2 uses plain market symbols: "BTC/EUR", "DOGE/EUR". It rejects the
 * legacy spellings outright with "Currency pair not supported XBT/EUR".
 *
 * REST v1 (/0/public/AssetPairs, which is how we discover pairs) still returns
 * the legacy class-prefixed codes: base "XXBT", quote "ZEUR", and a `wsname` of
 * "XBT/EUR". That `wsname` is the *v1* socket name and is wrong for v2.
 *
 * Trusting `wsname` is the trap this object exists to prevent: BTC would simply
 * never receive a price, the app would quietly fall back to the REST aggregator,
 * and it would look slow rather than broken.
 */
object KrakenSymbols {
    private val legacyToMarket = mapOf("XBT" to "BTC", "XDG" to "DOGE")
    private val marketToLegacy = legacyToMarket.entries.associate { (k, v) -> v to k }

    /** The v2 WebSocket symbol for a pair, from market-symbol base and quote. */
    fun v2Symbol(base: String, quote: String): String =
        "${base.uppercase()}/${quote.uppercase()}"

    /**
     * The REST v1 altname for a pair, which is what /0/public/OHLC wants:
     * "XBTEUR", not the socket's "BTC/EUR". Sending the socket spelling here
     * returns "Unknown asset pair" and the chart silently stays empty.
     */
    fun restPair(base: String, quote: String): String =
        "${toLegacySymbol(base)}${quote.uppercase()}"

    /** Market symbol to Kraken's own: BTC to XBT, DOGE to XDG, everything else unchanged. */
    fun toLegacySymbol(marketSymbol: String): String {
        val upper = marketSymbol.uppercase()
        return marketToLegacy[upper] ?: upper
    }

    /**
     * Normalises an asset code from a REST v1 response into the market symbol
     * the v2 socket wants: XXBT to BTC, ZEUR to EUR, XDG to DOGE, ADA to ADA.
     */
    fun toMarketSymbol(krakenAssetCode: String): String {
        val stripped = stripLegacyPrefix(krakenAssetCode.uppercase())
        return legacyToMarket[stripped] ?: stripped
    }

    /**
     * Drops the legacy X (crypto) and Z (fiat) class prefixes, but only from
     * four-character codes. Stripping unconditionally would turn XRP into RP and
     * ZEC into EC, which are different assets or none at all.
     */
    private fun stripLegacyPrefix(symbol: String): String =
        if (symbol.length == 4 && (symbol[0] == 'X' || symbol[0] == 'Z')) symbol.substring(1) else symbol
}
