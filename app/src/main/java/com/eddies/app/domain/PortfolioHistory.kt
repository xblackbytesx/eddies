package com.eddies.app.domain

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/** One known close for one asset on one calendar day, in the currency its source quoted. */
data class DailyClose(val day: LocalDate, val close: BigDecimal, val currency: String)

/**
 * One known price for one asset at one moment, in the currency its source quoted.
 *
 * [at] is when the price was true, so the end of an hourly candle, not its start.
 */
data class TimedClose(val at: Long, val close: BigDecimal, val currency: String)

/** One asset class on one day of the value chart. */
data class ClassDay(val value: BigDecimal, val costBasis: BigDecimal)

/** One day of the value chart, split by class so the parts always sum to the whole. */
data class HistoryDay(val day: LocalDate, val perClass: Map<AssetClass, ClassDay>) {
    val value: BigDecimal get() = perClass.values.sumOf { it.value }
    val costBasis: BigDecimal get() = perClass.values.sumOf { it.costBasis }
}

/** One moment of the intraday value chart, split by class like [HistoryDay]. */
data class HistoryPoint(val at: Long, val perClass: Map<AssetClass, ClassDay>) {
    val value: BigDecimal get() = perClass.values.sumOf { it.value }
}

/**
 * The portfolio value chart, replayed from the ledger and daily closes.
 *
 * Pure on purpose. This used to live in a repository between two DAOs, where
 * nothing could test it, and it was wrong in a way that only showed on a phone:
 * the chart was a square wave.
 *
 * **A day with no close is not a day worth nothing.** Share markets shut at the
 * weekend and on holidays, so a share has no candle for those days. Valuing it
 * at zero then dropped the whole stock side of the portfolio to the floor every
 * Saturday and lifted it back every Monday, and did the same to the newest
 * point on any morning before the market opened. A holding is worth its last
 * known close until a newer one arrives, which is what every statement a broker
 * sends does.
 *
 * **Before the first close, a holding is worth what was paid for it.** Free
 * history stops somewhere: about two years on Kraken, one on CoinPaprika. A
 * position older than that used to count for nothing until the data began and
 * then appear from the floor in a single day, a cliff that never happened. The
 * price on the user's own transaction is a real price on a real day, so it
 * stands in until the market data starts. Only a holding with neither a close
 * nor a priced transaction counts for nothing.
 */
object PortfolioHistory {

    /**
     * One [HistoryDay] per calendar day from the first transaction to [lastDay].
     *
     * [closes] may be unsorted, may hold several rows for one day (the newest
     * wins) and should reach a little before the first transaction, so a
     * purchase made on a Saturday has Friday's close to stand on.
     *
     * [fx] is the strict table and prices the cost basis: a rate that is not
     * known means no basis rather than a guessed one. [valueFx] converts closes
     * for the chart and may be more forgiving. A close it cannot convert is
     * skipped, which leaves the previous one in force, never a zero and never
     * an unconverted number plotted against the wrong axis.
     */
    fun build(
        transactions: List<Transaction>,
        closes: Map<String, List<DailyClose>>,
        baseCurrency: String,
        lastDay: LocalDate,
        zone: ZoneId,
        method: CostBasisMethod = CostBasisMethod.AVERAGE,
        fx: FxTable = IdentityFx,
        valueFx: FxTable = fx,
        includeFeesInBasis: Boolean = true,
        splitsByAsset: Map<String, List<SplitEvent>> = emptyMap(),
    ): List<HistoryDay> {
        if (transactions.isEmpty()) return emptyList()

        // The daily walk counts in calendar days: a transaction belongs to
        // the day it happened on in the user's zone, a close to its own date.
        val tracks = transactions.groupBy { it.assetId }.map { (assetId, txs) ->
            val sorted = txs.sortedBy { it.timestamp }
            Track(
                assetId = assetId,
                assetClass = AssetIds.classOf(assetId) ?: AssetClass.CRYPTO,
                txs = sorted,
                txTicks = sorted.map { dayOf(it.timestamp, zone).toEpochDay() },
                closes = closes[assetId].orEmpty().sortedBy { it.day }
                    .map { Step(it.day.toEpochDay(), it.close, it.currency, noon(it.day)) },
                splits = splitsByAsset[assetId].orEmpty(),
            )
        }
        val firstDay = LocalDate.ofEpochDay(tracks.minOf { it.txTicks.first() })
        if (lastDay.isBefore(firstDay)) return emptyList()

        val out = ArrayList<HistoryDay>()
        var day = firstDay
        while (!day.isAfter(lastDay)) {
            val totals = totalsAt(tracks, day.toEpochDay(), baseCurrency, method, fx, valueFx, includeFeesInBasis)
            if (totals.isNotEmpty()) out += HistoryDay(day, totals)
            day = day.plusDays(1)
        }
        return out
    }

    /**
     * The same replay at a finer grain, for the 1D and 1W ranges: one
     * [HistoryPoint] every [stepMs] from [from] to [to], both in epoch millis.
     *
     * Not stored anywhere. A week of hours is 168 points over a handful of
     * assets, which is cheaper to work out than to keep in step with the
     * ledger.
     *
     * [closes] should include something from before [from] for every asset,
     * normally its last daily closes, so the line starts at a real value. A
     * share has no hourly prices overnight or at the weekend, and is carried at
     * its last one exactly as the daily chart carries it. Moments before the
     * first transaction produce no point.
     */
    fun buildIntraday(
        transactions: List<Transaction>,
        closes: Map<String, List<TimedClose>>,
        baseCurrency: String,
        from: Long,
        to: Long,
        stepMs: Long,
        method: CostBasisMethod = CostBasisMethod.AVERAGE,
        fx: FxTable = IdentityFx,
        valueFx: FxTable = fx,
        includeFeesInBasis: Boolean = true,
        splitsByAsset: Map<String, List<SplitEvent>> = emptyMap(),
    ): List<HistoryPoint> {
        if (transactions.isEmpty() || stepMs <= 0 || to < from) return emptyList()

        val tracks = transactions.groupBy { it.assetId }.map { (assetId, txs) ->
            val sorted = txs.sortedBy { it.timestamp }
            Track(
                assetId = assetId,
                assetClass = AssetIds.classOf(assetId) ?: AssetClass.CRYPTO,
                txs = sorted,
                txTicks = sorted.map { it.timestamp },
                closes = closes[assetId].orEmpty().sortedBy { it.at }
                    .map { Step(it.at, it.close, it.currency, it.at) },
                splits = splitsByAsset[assetId].orEmpty(),
            )
        }

        val out = ArrayList<HistoryPoint>()
        var at = from
        while (at <= to) {
            val totals = totalsAt(tracks, at, baseCurrency, method, fx, valueFx, includeFeesInBasis)
            if (totals.isNotEmpty()) out += HistoryPoint(at, totals)
            at += stepMs
        }
        return out
    }

    /** Value and cost per class once every track has been walked up to [tick]. */
    private fun totalsAt(
        tracks: List<Track>,
        tick: Long,
        baseCurrency: String,
        method: CostBasisMethod,
        fx: FxTable,
        valueFx: FxTable,
        includeFeesInBasis: Boolean,
    ): Map<AssetClass, ClassDay> {
        val value = HashMap<AssetClass, BigDecimal>()
        val cost = HashMap<AssetClass, BigDecimal>()
        for (track in tracks) {
            track.advanceTo(tick, baseCurrency, method, fx, valueFx, includeFeesInBasis)
            val position = track.position ?: continue
            val price = track.lastClose ?: track.paidPrice
            val worth = if (price == null) BigDecimal.ZERO else position.quantity * price
            value[track.assetClass] = (value[track.assetClass] ?: BigDecimal.ZERO) + worth
            cost[track.assetClass] = (cost[track.assetClass] ?: BigDecimal.ZERO) + position.costBasis
        }
        return value.mapValues { (assetClass, v) -> ClassDay(v, cost[assetClass] ?: BigDecimal.ZERO) }
    }

    /** A close on the walk's own clock, plus the moment its exchange rate is looked up at. */
    private class Step(val tick: Long, val close: BigDecimal, val currency: String, val fxAt: Long)

    /**
     * One asset's walk through time: where its ledger and its closes have got to.
     *
     * Ticks are whatever the caller counts in, calendar days for the daily
     * chart and milliseconds for the intraday one. The walk only needs them to
     * be ordered.
     */
    private class Track(
        val assetId: String,
        val assetClass: AssetClass,
        val txs: List<Transaction>,
        val txTicks: List<Long>,
        val closes: List<Step>,
        val splits: List<SplitEvent>,
    ) {
        private var txIndex = 0
        private var closeIndex = 0

        /** Null until the first transaction has happened. */
        var position: PositionSnapshot? = null
            private set

        /** The newest close on or before the current tick, in the base currency. */
        var lastClose: BigDecimal? = null
            private set

        /** What the most recent priced transaction paid per unit, in the base currency. */
        var paidPrice: BigDecimal? = null
            private set

        fun advanceTo(
            tick: Long,
            baseCurrency: String,
            method: CostBasisMethod,
            fx: FxTable,
            valueFx: FxTable,
            includeFeesInBasis: Boolean,
        ) {
            val before = txIndex
            while (txIndex < txs.size && txTicks[txIndex] <= tick) {
                paidPriceOf(txs[txIndex], baseCurrency, valueFx)?.let { paidPrice = it }
                txIndex++
            }
            // The ledger is only refolded on a tick it changed. Folding every
            // asset for every day made this quadratic in the age of the
            // portfolio, and it ran on every launch.
            if (txIndex != before) {
                position = PositionCalculator.fold(
                    txs = txs.subList(0, txIndex),
                    method = method,
                    baseCurrency = baseCurrency,
                    fx = fx,
                    includeFeesInBasis = includeFeesInBasis,
                    assetId = assetId,
                    // Every split, including ones after this day. Providers
                    // publish split-adjusted closes, so the share count has to
                    // be in today's shares for the two to multiply correctly.
                    splits = splits,
                )
            }
            while (closeIndex < closes.size && closes[closeIndex].tick <= tick) {
                val row = closes[closeIndex]
                inBase(row.close, row.currency, baseCurrency, row.fxAt, valueFx)?.let { lastClose = it }
                closeIndex++
            }
        }

        /**
         * A transaction's own price, restated in today's shares.
         *
         * The share count is in post-split shares (see above), so a price paid
         * before a 4:1 split is divided by four to match. Otherwise the stand-in
         * value would be four times what was actually paid.
         */
        private fun paidPriceOf(tx: Transaction, baseCurrency: String, valueFx: FxTable): BigDecimal? {
            val price = tx.pricePerUnit?.takeIf { it.signum() > 0 } ?: return null
            val converted = inBase(price, tx.quoteCurrency, baseCurrency, tx.timestamp, valueFx) ?: return null
            val laterSplits = splits
                .filter { it.timestamp > tx.timestamp && it.ratio.signum() > 0 }
                .fold(BigDecimal.ONE) { acc, split -> acc * split.ratio }
            return converted.divide(laterSplits, MC)
        }
    }

    private fun inBase(
        amount: BigDecimal,
        currency: String,
        baseCurrency: String,
        atEpochMs: Long,
        fx: FxTable,
    ): BigDecimal? {
        val from = QuoteCurrency.fiatOf(currency)
        val to = baseCurrency.uppercase()
        if (from == to) return amount
        return fx.rate(from, to, atEpochMs)?.let { amount * it }
    }

    private fun dayOf(epochMs: Long, zone: ZoneId): LocalDate =
        Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate()

    /** Midday UTC, so a rate lookup lands on the close's own date in any zone. */
    private fun noon(day: LocalDate): Long =
        day.atTime(12, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
}
