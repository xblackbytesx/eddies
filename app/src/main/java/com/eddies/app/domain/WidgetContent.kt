package com.eddies.app.domain

import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The three lines of the home screen widget, as text.
 *
 * Pure so the one decision that matters here is tested: what a widget is
 * allowed to say. It sits on the launcher, in front of the app lock and in view
 * of anyone who picks the phone up.
 */
data class WidgetContent(
    val value: String,
    /** Change since the last close, or empty when there is nothing to compare with. */
    val change: String,
    /** +1, 0 or -1, for coloring [change]. The sign and arrow are already in the text. */
    val direction: Int,
    val updated: String,
) {
    companion object {

        /**
         * [previousClose] is the portfolio's value at the last daily close, the
         * final point of the stored value chart.
         *
         * Amounts are masked when the user hides balances, and also when the
         * app lock is on: a lock that leaves the net worth on the home screen
         * is not protecting the number it exists to protect. With only the lock
         * on, the percentage still shows, since it says how the day is going
         * without saying how much there is. Hiding balances masks that too,
         * exactly as it does inside the app.
         */
        fun of(
            total: BigDecimal,
            previousClose: BigDecimal?,
            currency: String,
            hideBalances: Boolean,
            appLock: Boolean,
            at: Long,
            now: Long,
            zone: ZoneId,
        ): WidgetContent {
            val maskAmounts = hideBalances || appLock
            val delta = previousClose?.takeIf { it.signum() > 0 }?.let { total - it }
            val pct = delta?.let { it.divide(previousClose, MC).toDouble() * 100.0 }

            val change = when {
                delta == null || hideBalances -> ""
                else -> {
                    val arrow = if (delta.signum() >= 0) "▲" else "▼"
                    val percent = MoneyFormat.percent(pct)
                    if (maskAmounts) "$arrow $percent"
                    else "$arrow ${MoneyFormat.signedFiat(delta, currency)} ($percent)"
                }
            }
            return WidgetContent(
                value = MoneyFormat.fiat(total, currency, hidden = maskAmounts),
                change = change,
                direction = if (change.isEmpty()) 0 else delta?.signum() ?: 0,
                updated = stamp(at, now, zone),
            )
        }

        /**
         * When the number was true. Always shown: the widget has no live feed,
         * and a figure from this morning must not pass for one from now.
         */
        internal fun stamp(at: Long, now: Long, zone: ZoneId): String {
            val then = Instant.ofEpochMilli(at).atZone(zone)
            val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
            return if (then.toLocalDate() == today) TIME.format(then) else DAY.format(then)
        }

        private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
        private val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM, HH:mm")
    }
}
