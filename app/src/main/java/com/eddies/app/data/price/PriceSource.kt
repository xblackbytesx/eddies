package com.eddies.app.data.price

import com.eddies.app.domain.PriceSourceId
import com.eddies.app.domain.PriceTick
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import java.math.BigDecimal

/** One asset, as one source names it, quoted in one currency. */
data class ResolvedSymbol(
    val assetId: String,
    val symbol: String,
    val quoteCurrency: String,
)

/**
 * A place prices come from. Implementations are either a live socket or a REST
 * poller; the repository merges them behind one flow and does not care which.
 */
interface PriceSource {
    val id: PriceSourceId

    /** True for a push feed, false for a poller. Decides the fallback ladder order. */
    val isRealtime: Boolean

    /**
     * Which of [tickers] this source can quote, and under what symbol. The map
     * is assetId to market ticker ("crypto:btc-bitcoin" to "BTC"), passed in
     * rather than parsed back out of the id, which is deliberately opaque.
     *
     * [baseCurrency] is a preference, not a requirement: a source that only
     * quotes USD returns USD and the repository converts.
     */
    suspend fun resolve(tickers: Map<String, String>, baseCurrency: String): Map<String, ResolvedSymbol>

    /** Emits ticks until cancelled. Reconnection is the implementation's job. */
    fun stream(symbols: Map<String, ResolvedSymbol>): Flow<PriceTick>
}

/**
 * Reads a JSON value as BigDecimal without going through Double.
 *
 * This matters because the two feeds disagree on encoding: Binance sends
 * "79403.15000000" as a string, Kraken v2 sends 68035.6 as a bare number.
 * JsonPrimitive.content hands back the raw text either way, so neither is ever
 * routed through a Double that would round it.
 */
internal fun JsonElement?.asBigDecimal(): BigDecimal? =
    (this as? JsonPrimitive)?.content?.let { runCatching { BigDecimal(it) }.getOrNull() }

internal fun JsonElement?.asDoubleOrNull(): Double? =
    (this as? JsonPrimitive)?.content?.toDoubleOrNull()

internal fun JsonElement?.asStringOrNull(): String? = (this as? JsonPrimitive)?.content
