package com.eddies.app.data.price

import com.eddies.app.data.prefs.Aggregator
import com.eddies.app.data.prefs.SettingsDataStore
import com.eddies.app.domain.Asset
import com.eddies.app.domain.AssetClass
import com.eddies.app.domain.AssetIds
import com.eddies.app.domain.PriceSourceId
import com.eddies.app.domain.PriceTick
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The REST fallback, covering the long tail of coins no exchange quotes.
 *
 * CoinPaprika by default: it needs no API key at all, permits commercial use,
 * and quotes EUR natively so the common case needs no FX hop. CoinGecko is
 * offered as an alternative for its wider coverage, but only with a key the user
 * supplies. An embedded key would be extracted from the APK within a day and
 * then shared by every install until it was revoked.
 */
@Singleton
class AggregatorSource @Inject constructor(
    private val http: HttpClient,
    private val json: Json,
    private val settings: SettingsDataStore,
) : PriceSource {

    override val id = PriceSourceId.COINPAPRIKA
    override val isRealtime = false

    /** Everything the aggregator carries is resolvable; the id is its own symbol. */
    override suspend fun resolve(tickers: Map<String, String>, baseCurrency: String): Map<String, ResolvedSymbol> =
        tickers.keys
            .filter { AssetIds.classOf(it) == AssetClass.CRYPTO }
            .associateWith { assetId ->
                ResolvedSymbol(assetId, assetId.substringAfter(':'), baseCurrency.uppercase())
            }

    override fun stream(symbols: Map<String, ResolvedSymbol>): Flow<PriceTick> = flow {
        if (symbols.isEmpty()) return@flow
        while (true) {
            val interval = settings.current().pollIntervalSeconds.coerceAtLeast(15)
            runCatching { fetch(symbols) }.getOrDefault(emptyList()).forEach { emit(it) }
            delay(interval * 1000L)
        }
    }

    private suspend fun fetch(symbols: Map<String, ResolvedSymbol>): List<PriceTick> {
        val cfg = settings.current()
        return when (cfg.aggregator) {
            Aggregator.COINPAPRIKA -> fetchPaprika(symbols, cfg.baseCurrency)
            Aggregator.COINGECKO -> fetchGecko(symbols, cfg.baseCurrency)
        }
    }

    /**
     * One request per coin. CoinPaprika's bulk /tickers endpoint returns every
     * asset it tracks, which is several megabytes for a handful of holdings, so
     * for a normal portfolio the per-coin calls are both smaller and faster.
     */
    private suspend fun fetchPaprika(symbols: Map<String, ResolvedSymbol>, base: String): List<PriceTick> =
        symbols.values.mapNotNull { resolved ->
            runCatching {
                val body = http.get("https://api.coinpaprika.com/v1/tickers/${resolved.symbol}") {
                    parameter("quotes", base.uppercase())
                }.bodyAsText()
                val quotes = json.parseToJsonElement(body).jsonObject["quotes"]?.jsonObject ?: return@runCatching null
                val q = quotes[base.uppercase()]?.jsonObject ?: return@runCatching null
                val price = q["price"].asBigDecimal() ?: return@runCatching null
                PriceTick(
                    assetId = resolved.assetId,
                    price = price,
                    currency = base.uppercase(),
                    changePct24h = q["percent_change_24h"].asDoubleOrNull(),
                    at = System.currentTimeMillis(),
                    source = PriceSourceId.COINPAPRIKA,
                    stale = false,
                )
            }.getOrNull()
        }

    private suspend fun fetchGecko(symbols: Map<String, ResolvedSymbol>, base: String): List<PriceTick> {
        val key = settings.coinGeckoKey()
        if (key.isBlank()) return emptyList()
        val ids = symbols.values.joinToString(",") { it.symbol }
        val body = runCatching {
            http.get("https://api.coingecko.com/api/v3/simple/price") {
                parameter("ids", ids)
                parameter("vs_currencies", base.lowercase())
                parameter("include_24hr_change", "true")
                header("x-cg-demo-api-key", key)
            }.bodyAsText()
        }.getOrNull() ?: return emptyList()

        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return emptyList()
        return symbols.values.mapNotNull { resolved ->
            val o = root[resolved.symbol]?.jsonObject ?: return@mapNotNull null
            val price = o[base.lowercase()].asBigDecimal() ?: return@mapNotNull null
            PriceTick(
                assetId = resolved.assetId,
                price = price,
                currency = base.uppercase(),
                changePct24h = o["${base.lowercase()}_24h_change"].asDoubleOrNull(),
                at = System.currentTimeMillis(),
                source = PriceSourceId.COINGECKO,
                stale = false,
            )
        }
    }

    /** Metadata for the bundled seed refresh and for coins the seed does not carry. */
    suspend fun searchCoins(term: String, limit: Int = 25): List<Asset> = runCatching {
        val body = http.get("https://api.coinpaprika.com/v1/search") {
            parameter("q", term)
            parameter("c", "currencies")
            parameter("limit", limit.toString())
        }.bodyAsText()
        val currencies = json.parseToJsonElement(body).jsonObject["currencies"] as? JsonArray ?: return emptyList()
        currencies.mapNotNull { element ->
            val o = element as? JsonObject ?: return@mapNotNull null
            val id = o["id"].asStringOrNull() ?: return@mapNotNull null
            val symbol = o["symbol"].asStringOrNull() ?: return@mapNotNull null
            Asset(
                id = AssetIds.crypto(id),
                assetClass = AssetClass.CRYPTO,
                symbol = symbol.uppercase(),
                name = o["name"].asStringOrNull() ?: symbol,
                iconSlug = symbol.lowercase(),
                rank = o["rank"].asDoubleOrNull()?.toInt(),
            )
        }
    }.getOrDefault(emptyList())
}
