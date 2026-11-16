package com.eddies.app.domain

import java.math.BigDecimal

/**
 * What a feed's currency label actually means in money.
 *
 * Two different lies get told in that field, and both produce a number that
 * looks fine and is wrong, or no number at all.
 */
object QuoteCurrency {

    /** A price together with the real currency it is in. */
    data class Money(val amount: BigDecimal, val currency: String)

    private val HUNDRED = BigDecimal(100)

    /**
     * Exchanges that quote in a hundredth of the currency, as Yahoo labels them.
     *
     * London quotes most shares in pence and Yahoo says so only through the
     * case of one letter: `GBp` rather than `GBP`. Uppercasing that label, which
     * is the obvious thing to do with a currency code, turns 6,400 pence into
     * 6,400 pounds and a holding into a hundred times its value.
     *
     * Matched on the raw label, before any case folding, for exactly that
     * reason. Johannesburg (`ZAc`) and Tel Aviv (`ILA`) do the same thing.
     */
    private val minorUnits = mapOf(
        "GBp" to "GBP",
        "GBX" to "GBP",
        "ZAc" to "ZAR",
        "ZAC" to "ZAR",
        "ILA" to "ILS",
    )

    /** The major currency a raw label is denominated in, and whether it is a hundredth of it. */
    fun isMinorUnit(rawCurrency: String?): Boolean = rawCurrency?.trim() in minorUnits

    /** The currency code to store for a raw label: `GBp` is GBP, `usd` is USD. */
    fun majorCode(rawCurrency: String?, fallback: String = "USD"): String {
        val raw = rawCurrency?.trim().orEmpty()
        return minorUnits[raw] ?: raw.uppercase().ifEmpty { fallback }
    }

    /** [amount] in the major currency: pence become pounds, everything else is untouched. */
    fun toMajor(amount: BigDecimal, rawCurrency: String?): BigDecimal =
        if (isMinorUnit(rawCurrency)) amount.divide(HUNDRED, MC) else amount

    fun normalise(amount: BigDecimal, rawCurrency: String?): Money =
        Money(toMajor(amount, rawCurrency), majorCode(rawCurrency))

    /**
     * Dollar stablecoins, which exchanges quote against instead of fiat.
     *
     * No central bank publishes a rate for USDT, so a price quoted in it has no
     * conversion and the holding ends up with no value at all. They are treated
     * as US dollars, which is what they track to within a fraction of a percent
     * and is a far smaller error than showing nothing.
     */
    private val dollarStablecoins = setOf("USDT", "USDC", "FDUSD", "BUSD", "TUSD", "DAI")

    /** The fiat currency an exchange quote can be converted as. */
    fun fiatOf(currency: String): String {
        val code = currency.trim().uppercase()
        return if (code in dollarStablecoins) "USD" else code
    }
}
