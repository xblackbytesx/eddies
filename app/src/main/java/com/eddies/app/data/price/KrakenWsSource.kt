package com.eddies.app.data.price

import com.eddies.app.domain.BackoffPolicy
import com.eddies.app.domain.KrakenSymbols
import com.eddies.app.domain.PriceSourceId
import com.eddies.app.domain.PriceTick
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.isActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Kraken's public WebSocket v2 feed.
 *
 * No API key: the market-data channels are unauthenticated. Chosen as the
 * default for a EUR-based user because Kraken quotes EUR pairs natively, so the
 * common case needs no FX conversion at all.
 *
 * Symbols are market symbols ("BTC/EUR"), NOT the legacy XBT spelling the REST
 * v1 `wsname` field reports. See KrakenSymbols for why that distinction is
 * load-bearing.
 */
@Singleton
class KrakenWsSource @Inject constructor(
    private val http: HttpClient,
    private val json: Json,
) : PriceSource {

    override val id = PriceSourceId.KRAKEN
    override val isRealtime = true

    private val backoff = BackoffPolicy()

    /** Pair set from REST, cached for the process. Kraken lists a few thousand. */
    @Volatile private var pairCache: Set<String>? = null

    override suspend fun resolve(tickers: Map<String, String>, baseCurrency: String): Map<String, ResolvedSymbol> {
        val pairs = loadPairs() ?: return emptyMap()
        val out = LinkedHashMap<String, ResolvedSymbol>()
        for ((assetId, ticker) in tickers) {
            val symbol = symbolFor(ticker.uppercase(), baseCurrency, pairs) ?: continue
            out[assetId] = symbol.copy(assetId = assetId)
        }
        return out
    }

    /**
     * Prefers a pair already quoted in the user's currency, then USD, then any
     * major stablecoin. Each fallback costs one FX conversion, so the order is
     * "fewest conversions first" rather than "most liquid first".
     */
    private fun symbolFor(ticker: String, baseCurrency: String, pairs: Set<String>): ResolvedSymbol? {
        val quotes = listOf(baseCurrency.uppercase(), "USD", "EUR", "USDT")
        for (quote in quotes) {
            val candidate = KrakenSymbols.v2Symbol(ticker, quote)
            if (candidate in pairs) {
                return ResolvedSymbol(assetId = "", symbol = candidate, quoteCurrency = quote)
            }
        }
        return null
    }

    private suspend fun loadPairs(): Set<String>? {
        pairCache?.let { return it }
        return runCatching {
            val body = http.get("https://api.kraken.com/0/public/AssetPairs").bodyAsText()
            val result = json.parseToJsonElement(body).jsonObject["result"]?.jsonObject ?: return null
            // Build v2 symbols from base and quote rather than trusting `wsname`,
            // which is the v1 name and would give us XBT/EUR: a pair v2 rejects.
            val set = result.values.mapNotNull { entry ->
                val o = entry as? JsonObject ?: return@mapNotNull null
                val base = o["base"].asStringOrNull()?.let(KrakenSymbols::toMarketSymbol) ?: return@mapNotNull null
                val quote = o["quote"].asStringOrNull()?.let(KrakenSymbols::toMarketSymbol) ?: return@mapNotNull null
                KrakenSymbols.v2Symbol(base, quote)
            }.toSet()
            pairCache = set
            set
        }.getOrNull()
    }

    override fun stream(symbols: Map<String, ResolvedSymbol>): Flow<PriceTick> = channelFlow {
        if (symbols.isEmpty()) {
            awaitClose { }
            return@channelFlow
        }
        // symbol -> assetId, so an inbound tick can be attributed without a scan.
        val bySymbol = symbols.entries.associate { (assetId, r) -> r.symbol to (assetId to r.quoteCurrency) }
        var attempt = 0

        while (isActive) {
            try {
                val session = http.webSocketSession(WS_URL)
                attempt = 0
                val subscribe = buildString {
                    append("""{"method":"subscribe","params":{"channel":"ticker","symbol":[""")
                    append(bySymbol.keys.joinToString(",") { "\"$it\"" })
                    append("""]}}""")
                }
                session.send(subscribe)

                for (frame in session.incoming) {
                    if (frame !is Frame.Text) continue
                    val root = runCatching { json.parseToJsonElement(frame.readText()).jsonObject }.getOrNull() ?: continue
                    if (root["channel"].asStringOrNull() != "ticker") continue
                    val data = root["data"] as? JsonArray ?: continue
                    for (element in data) {
                        val o = element as? JsonObject ?: continue
                        val symbol = o["symbol"].asStringOrNull() ?: continue
                        val (assetId, quote) = bySymbol[symbol] ?: continue
                        val last = o["last"].asBigDecimal() ?: continue
                        send(
                            PriceTick(
                                assetId = assetId,
                                price = last,
                                currency = quote,
                                changePct24h = o["change_pct"].asDoubleOrNull(),
                                at = System.currentTimeMillis(),
                                source = PriceSourceId.KRAKEN,
                                stale = false,
                            ),
                        )
                    }
                }
                session.close()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // Any drop is a reconnect. Nothing is logged: the subscription
                // list is the user's holdings.
            }
            if (!isActive) break
            delay(backoff.delayMs(attempt))
            attempt++
        }
    }

    private companion object {
        const val WS_URL = "wss://ws.kraken.com/v2"
    }
}
