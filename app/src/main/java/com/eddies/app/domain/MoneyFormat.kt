package com.eddies.app.domain

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

/**
 * How money and quantities are written on screen.
 *
 * Pure and framework-free so it is JVM-testable, which matters because a
 * formatting bug here is indistinguishable from an arithmetic one: the user sees
 * a wrong number either way.
 */
object MoneyFormat {

    private val symbols = mapOf(
        "EUR" to "€",
        "USD" to "$",
        "GBP" to "£",
        "JPY" to "¥",
        "CHF" to "CHF ",
        "AUD" to "A$",
        "CAD" to "C$",
    )

    fun symbolFor(currency: String): String = symbols[currency.uppercase()] ?: "${currency.uppercase()} "

    /**
     * A fiat amount. Two decimals normally; more for a value so small that two
     * would round it to zero and make a real holding look like nothing.
     */
    fun fiat(amount: BigDecimal, currency: String, hidden: Boolean = false): String {
        if (hidden) return "${symbolFor(currency)}••••"
        val abs = amount.abs()
        val decimals = when {
            abs.signum() == 0 -> 2
            abs < BigDecimal("0.01") -> 6
            abs < BigDecimal("1") -> 4
            else -> 2
        }
        val sign = if (amount.signum() < 0) "-" else ""
        return sign + symbolFor(currency) + group(abs, decimals)
    }

    /** A signed amount, for a gain or loss where the direction is the point. */
    fun signedFiat(amount: BigDecimal, currency: String, hidden: Boolean = false): String {
        if (hidden) return "${symbolFor(currency)}••••"
        val prefix = if (amount.signum() >= 0) "+" else "-"
        return prefix + symbolFor(currency) + group(amount.abs(), 2)
    }

    fun percent(value: Double?, hidden: Boolean = false): String {
        if (hidden) return "•••"
        if (value == null || value.isNaN() || value.isInfinite()) return "–"
        val prefix = if (value >= 0) "+" else "-"
        return "$prefix${DecimalFormat("0.00", DecimalFormatSymbols(Locale.US)).format(kotlin.math.abs(value))}%"
    }

    /**
     * A coin quantity. Trailing zeros are trimmed because "0.50000000 BTC" reads
     * as noise, but a whole number keeps no decimals at all.
     */
    fun quantity(amount: BigDecimal, decimals: Int = 8, hidden: Boolean = false): String {
        if (hidden) return "••••"
        val scaled = amount.setScale(decimals, RoundingMode.HALF_UP).stripTrailingZeros()
        val plain = if (scaled.scale() < 0) scaled.setScale(0) else scaled
        return group(plain.abs(), plain.scale().coerceAtLeast(0)).let {
            if (amount.signum() < 0) "-$it" else it
        }
    }

    /**
     * A price. Big numbers get two decimals, sub-cent tokens get enough places to
     * still say something: SHIB at eight decimals is the difference between a
     * price and a row of zeros.
     */
    fun price(amount: BigDecimal, currency: String, hidden: Boolean = false): String {
        if (hidden) return "${symbolFor(currency)}••••"
        val abs = amount.abs()
        val decimals = when {
            abs.signum() == 0 -> 2
            abs >= BigDecimal("1000") -> 2
            abs >= BigDecimal("1") -> 4
            abs >= BigDecimal("0.0001") -> 6
            else -> 10
        }
        return symbolFor(currency) + group(abs, decimals)
    }

    /** Compact notation for chart axes, where space is the constraint. */
    fun compact(amount: BigDecimal, currency: String? = null): String {
        val prefix = currency?.let { symbolFor(it) } ?: ""
        val abs = amount.abs()
        val sign = if (amount.signum() < 0) "-" else ""
        val (value, suffix) = when {
            abs >= BigDecimal("1000000000") -> abs.divide(BigDecimal("1000000000"), MC) to "B"
            abs >= BigDecimal("1000000") -> abs.divide(BigDecimal("1000000"), MC) to "M"
            abs >= BigDecimal("1000") -> abs.divide(BigDecimal("1000"), MC) to "k"
            else -> abs to ""
        }
        val decimals = if (suffix.isEmpty()) (if (abs < BigDecimal("1")) 2 else 0) else 1
        return "$sign$prefix${plain(value, decimals)}$suffix"
    }

    private fun group(value: BigDecimal, decimals: Int): String {
        val pattern = if (decimals > 0) "#,##0." + "0".repeat(decimals) else "#,##0"
        return DecimalFormat(pattern, DecimalFormatSymbols(Locale.US)).format(value)
    }

    private fun plain(value: BigDecimal, decimals: Int): String {
        val pattern = if (decimals > 0) "0." + "0".repeat(decimals) else "0"
        return DecimalFormat(pattern, DecimalFormatSymbols(Locale.US)).format(value)
    }
}
