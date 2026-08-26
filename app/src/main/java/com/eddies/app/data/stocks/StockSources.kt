package com.eddies.app.data.stocks

import com.eddies.app.data.db.entity.CandleInterval
import com.eddies.app.data.prefs.SettingsDataStore
import com.eddies.app.data.price.Candle
import com.eddies.app.data.price.CandleSeries
import com.eddies.app.data.price.HistorySource
import com.eddies.app.data.price.PriceSource
import com.eddies.app.data.price.ResolvedSymbol
import com.eddies.app.data.repo.CorporateActionRepository
import com.eddies.app.domain.AssetClass
import com.eddies.app.domain.AssetIds
import com.eddies.app.domain.PriceSourceId
import com.eddies.app.domain.PriceTick
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Share prices, polled.
 *
 * There is no free realtime equity feed, and there is not going to be: exchanges
 * license that data. Everything free is delayed or end of day. So this is a
 * poller rather than a socket, and the tick is marked accordingly so the UI can
 * say "close" rather than implying a live price.
 *
 * Polling also backs off hard when the market is shut. A share price at 3am does
 * not move, and requesting it every minute is pure noise on someone's battery.
 */
@Singleton
class StockPriceSource @Inject constructor(
    private val yahoo: YahooApi,
    private val finnhub: FinnhubSource,
    private val settings: SettingsDataStore,
) : PriceSource {

    override val id = PriceSourceId.YAHOO
    override val isRealtime = false

    override suspend fun resolve(tickers: Map<String, String>, baseCurrency: String): Map<String, ResolvedSymbol> =
        tickers
            .filterKeys { AssetIds.classOf(it) == AssetClass.STOCK }
            .mapValues { (assetId, ticker) ->
                // The listing's own currency is not known until the quote comes
                // back, so this records the request, not the answer. The tick
                // carries the real currency and the FX layer converts.
                ResolvedSymbol(assetId, ticker, baseCurrency.uppercase())
            }

    override fun stream(symbols: Map<String, ResolvedSymbol>): Flow<PriceTick> = flow {
        if (symbols.isEmpty()) return@flow
        while (true) {
            var anyOpen = false
            for (resolved in symbols.values) {
                // The user's own key wins when there is one, since it is the
                // sanctioned source. Yahoo remains the fallback so the feature
                // works with no setup at all.
                val quote = finnhub.quote(resolved.assetId, resolved.symbol)
                    ?: yahoo.quote(resolved.assetId, resolved.symbol)
                    ?: continue
                if (!quote.marketClosed) anyOpen = true
                emit(
                    PriceTick(
                        assetId = quote.assetId,
                        price = quote.price,
                        currency = quote.currency,
                        changePct24h = quote.changePct,
                        at = quote.at.takeIf { it > 0 } ?: System.currentTimeMillis(),
                        source = PriceSourceId.YAHOO,
                        // A closed market's price is a close, not a live quote.
                        // Saying so is the difference between an honest number
                        // and one the user might act on.
                        stale = quote.marketClosed,
                    ),
                )
            }
            val base = settings.current().pollIntervalSeconds.coerceAtLeast(15)
            delay(if (anyOpen) base * 1000L else CLOSED_INTERVAL_MS)
        }
    }

    private companion object {
        /** Fifteen minutes while every held market is shut. */
        const val CLOSED_INTERVAL_MS = 15 * 60 * 1000L
    }
}

/**
 * Share history, and the splits that come with it.
 *
 * The splits are recorded as a side effect of fetching bars, which is
 * deliberate: they arrive in the same response, so a chart and the share count
 * can never disagree about which splits are known.
 */
@Singleton
class StockHistorySource @Inject constructor(
    private val yahoo: YahooApi,
    private val corporateActions: CorporateActionRepository,
) : HistorySource {

    override val id = PriceSourceId.YAHOO

    override suspend fun fetch(
        assetId: String,
        ticker: String,
        interval: CandleInterval,
        currency: String,
        since: Long?,
    ): CandleSeries? {
        if (AssetIds.classOf(assetId) != AssetClass.STOCK) return null

        // Yahoo takes a range rather than a start, and intraday history is only
        // retained for a couple of months, hence the different ceilings.
        val range = when {
            interval == CandleInterval.HOUR -> "1mo"
            since == null -> "10y"
            else -> {
                val days = (System.currentTimeMillis() - since) / 86_400_000L
                when {
                    days <= 5 -> "5d"
                    days <= 30 -> "1mo"
                    days <= 180 -> "6mo"
                    days <= 365 -> "1y"
                    else -> "10y"
                }
            }
        }
        val yahooInterval = if (interval == CandleInterval.HOUR) "1h" else "1d"

        val history = yahoo.history(assetId, ticker, range, yahooInterval) ?: return null
        if (history.splits.isNotEmpty()) corporateActions.record(history.splits)
        if (history.bars.isEmpty()) return null

        return CandleSeries(
            assetId = assetId,
            interval = interval,
            currency = history.currency,
            source = PriceSourceId.YAHOO,
            candles = history.bars.map { Candle(it.timestamp, it.close, it.high, it.low) },
        )
    }
}
