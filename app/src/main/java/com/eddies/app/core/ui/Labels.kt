package com.eddies.app.core.ui

import androidx.annotation.StringRes
import com.eddies.app.R
import com.eddies.app.core.design.ChartRange
import com.eddies.app.core.design.ThemeMode
import com.eddies.app.data.db.entity.CustodyType
import com.eddies.app.domain.CostBasisMethod
import com.eddies.app.domain.PortfolioScope
import com.eddies.app.domain.TxSource
import com.eddies.app.domain.TxType

/*
 * The translated name of each fixed choice in the app.
 *
 * Kept here and not on the enums themselves, because the enums live in the
 * domain and data layers, which know nothing of Android resources and are
 * tested on a plain JVM. An exhaustive `when` per enum also means a new value
 * does not compile until someone has named it.
 */

@get:StringRes
val ThemeMode.labelRes: Int
    get() = when (this) {
        ThemeMode.SYSTEM -> R.string.theme_system
        ThemeMode.LIGHT -> R.string.theme_light
        ThemeMode.DARK -> R.string.theme_dark
        ThemeMode.OLED -> R.string.theme_oled
    }

@get:StringRes
val CostBasisMethod.labelRes: Int
    get() = when (this) {
        CostBasisMethod.AVERAGE -> R.string.basis_average
        CostBasisMethod.FIFO -> R.string.basis_fifo
        CostBasisMethod.LIFO -> R.string.basis_lifo
        CostBasisMethod.HIFO -> R.string.basis_hifo
    }

@get:StringRes
val TxType.labelRes: Int
    get() = when (this) {
        TxType.BUY -> R.string.tx_buy
        TxType.SELL -> R.string.tx_sell
        TxType.TRANSFER_IN -> R.string.tx_transfer_in
        TxType.TRANSFER_OUT -> R.string.tx_transfer_out
        TxType.STAKING_REWARD -> R.string.tx_staking_reward
        TxType.AIRDROP -> R.string.tx_airdrop
        TxType.FEE -> R.string.tx_fee
        TxType.DIVIDEND -> R.string.tx_dividend
    }

@get:StringRes
val TxSource.labelRes: Int
    get() = when (this) {
        TxSource.MANUAL -> R.string.source_manual
        TxSource.IMPORT_CSV -> R.string.source_csv
        TxSource.IMPORT_KOIOS -> R.string.source_koios
    }

@get:StringRes
val CustodyType.labelRes: Int
    get() = when (this) {
        CustodyType.HARDWARE_WALLET -> R.string.custody_hardware
        CustodyType.EXCHANGE -> R.string.custody_exchange
        CustodyType.SOFTWARE_WALLET -> R.string.custody_software
        CustodyType.COLD_STORAGE -> R.string.custody_cold
        CustodyType.DEFI -> R.string.custody_defi
        CustodyType.OTHER -> R.string.custody_other
    }

@get:StringRes
val PortfolioScope.labelRes: Int
    get() = when (this) {
        PortfolioScope.ALL -> R.string.scope_all
        PortfolioScope.CRYPTO -> R.string.class_crypto
        PortfolioScope.STOCKS -> R.string.class_stocks
    }

@get:StringRes
val ChartRange.labelRes: Int
    get() = when (this) {
        ChartRange.DAY -> R.string.range_day
        ChartRange.WEEK -> R.string.range_week
        ChartRange.MONTH -> R.string.range_month
        ChartRange.QUARTER -> R.string.range_quarter
        ChartRange.YEAR -> R.string.range_year
        ChartRange.ALL -> R.string.range_all
    }
