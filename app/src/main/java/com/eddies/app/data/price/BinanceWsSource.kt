package com.eddies.app.data.price

import com.eddies.app.domain.BackoffPolicy
import com.eddies.app.domain.PriceSourceId
import com.eddies.app.domain.PriceTick
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.isActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.math.BigDecimal
import java.math.MathContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Binance's public market stream.
 *
 * Uses the all-market mini-ticker (`!miniTicker@arr`) rather than per-symbol
 * streams: one connection carries every symbol at roughly one update per second,
 * so the number of coins held changes nothing about the connection, and the
 * subscription list never leaves the device because there is no subscription.
 *
 * The trade is bandwidth: the whole market arrives whether or not it is wanted,
 * and everything not held is discarded on receipt.
 *
 * Binance quotes almost everything against USDT rather than fiat, so a EUR user
 * pays one FX conversion on every asset. That is why Kraken is the default.
 */
@Singleton
class BinanceWsSource @Inject constructor(
    private val http: HttpClient,
    private val json: Json,
) : PriceSource {

    override val id = PriceSourceId.BINANCE
    override val isRealtime = true

    private val backoff = BackoffPolicy()

    @Volatile private var symbolCache: Set<String>? = null

    override suspend fun resolve(tickers: Map<String, String>, baseCurrency: String): Map<String, ResolvedSymbol> {
        val known = loadSymbols() ?: return emptyMap()
        val out = LinkedHashMap<String, ResolvedSymbol>()
        for ((assetId, ticker) in tickers) {
            val t = ticker.uppercase()
            // Fiat quotes first when Binance actually has them, then the
            // stablecoins. Each fallback costs one FX conversion.
            val quotes = listOf(baseCurrency.uppercase(), "USDT", "USDC", "USD")
            for (quote in quotes) {
                val candidate = "$t$quote"
                if (candidate in known) {
                    out[assetId] = ResolvedSymbol(assetId, candidate, quote)
                    break
                }
            }
        }
        return out
    }

    private suspend fun loadSymbols(): Set<String>? {
        symbolCache?.let { return it }
        return runCatching {
            val body = http.get("https://api.binance.com/api/v3/exchangeInfo?permissions=SPOT").bodyAsText()
            val symbols = json.parseToJsonElement(body).jsonObject["symbols"]?.jsonArray ?: return null
            val set = symbols.mapNotNull { element ->
                val o = element as? JsonObject ?: return@mapNotNull null
                if (o["status"].asStringOrNull() != "TRADING") return@mapNotNull null
                o["symbol"].asStringOrNull()?.uppercase()
            }.toSet()
            symbolCache = set
            set
        }.getOrNull()
    }

    override fun stream(symbols: Map<String, ResolvedSymbol>): Flow<PriceTick> = channelFlow {
        if (symbols.isEmpty()) {
            awaitClose { }
            return@channelFlow
        }
        val bySymbol = symbols.entries.associate { (assetId, r) -> r.symbol to (assetId to r.quoteCurrency) }
        var attempt = 0

        while (isActive) {
            try {
                val session = http.webSocketSession(WS_URL)
                attempt = 0
                for (frame in session.incoming) {
                    if (frame !is Frame.Text) continue
                    val arr = runCatching {
                        json.parseToJsonElement(frame.readText()) as? JsonArray
                    }.getOrNull() ?: continue

                    for (element in arr) {
                        val o = element as? JsonObject ?: continue
                        val symbol = o["s"].asStringOrNull()?.uppercase() ?: continue
                        val (assetId, quote) = bySymbol[symbol] ?: continue
                        val close = o["c"].asBigDecimal() ?: continue
                        val open = o["o"].asBigDecimal()
                        send(
                            PriceTick(
                                assetId = assetId,
                                price = close,
                                currency = quote,
                                changePct24h = percentChange(open, close),
                                at = o["E"].asStringOrNull()?.toLongOrNull() ?: System.currentTimeMillis(),
                                source = PriceSourceId.BINANCE,
                                stale = false,
                            ),
                        )
                    }
                }
                session.close()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // Reconnect. Binance closes idle connections at 24 hours, so this
                // path is expected traffic, not only an error path.
            }
            if (!isActive) break
            delay(backoff.delayMs(attempt))
            attempt++
        }
    }

    /** The mini-ticker carries open and close but no percentage, unlike Kraken. */
    private fun percentChange(open: BigDecimal?, close: BigDecimal): Double? {
        if (open == null || open.signum() == 0) return null
        return (close - open).divide(open, MathContext.DECIMAL64).toDouble() * 100.0
    }

    private companion object {
        const val WS_URL = "wss://stream.binance.com:9443/ws/!miniTicker@arr"
    }
}
