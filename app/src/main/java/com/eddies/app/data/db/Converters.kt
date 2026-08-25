package com.eddies.app.data.db

import androidx.room.TypeConverter
import com.eddies.app.data.db.entity.CandleInterval
import com.eddies.app.domain.AssetClass
import com.eddies.app.domain.PriceSourceId
import com.eddies.app.domain.TxSource
import com.eddies.app.domain.TxType
import java.math.BigDecimal

/**
 * Enums store as their name, and every read has a non-throwing fallback: a row
 * written by a newer build must degrade, never crash the query that reads it.
 *
 * BigDecimal stores as TEXT via toPlainString. Not REAL. SQLite REAL is an IEEE
 * double, which cannot represent an 18-decimal token balance, and the symptom
 * would be a silently wrong number on the user's net worth.
 */
class Converters {

    @TypeConverter
    fun bigDecimalToString(value: BigDecimal?): String? = value?.toPlainString()

    @TypeConverter
    fun stringToBigDecimal(value: String?): BigDecimal? =
        value?.let { runCatching { BigDecimal(it) }.getOrNull() }

    @TypeConverter
    fun assetClassToString(value: AssetClass): String = value.name

    @TypeConverter
    fun stringToAssetClass(value: String): AssetClass =
        runCatching { AssetClass.valueOf(value) }.getOrDefault(AssetClass.CRYPTO)

    @TypeConverter
    fun txTypeToString(value: TxType): String = value.name

    @TypeConverter
    fun stringToTxType(value: String): TxType =
        runCatching { TxType.valueOf(value) }.getOrDefault(TxType.BUY)

    @TypeConverter
    fun txSourceToString(value: TxSource): String = value.name

    @TypeConverter
    fun stringToTxSource(value: String): TxSource =
        runCatching { TxSource.valueOf(value) }.getOrDefault(TxSource.MANUAL)

    @TypeConverter
    fun candleIntervalToString(value: CandleInterval): String = value.name

    @TypeConverter
    fun stringToCandleInterval(value: String): CandleInterval =
        runCatching { CandleInterval.valueOf(value) }.getOrDefault(CandleInterval.DAY)

    @TypeConverter
    fun priceSourceToString(value: PriceSourceId): String = value.name

    @TypeConverter
    fun stringToPriceSource(value: String): PriceSourceId =
        runCatching { PriceSourceId.valueOf(value) }.getOrDefault(PriceSourceId.MANUAL)
}
