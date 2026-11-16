package com.eddies.app.data

import com.eddies.app.data.repo.toDomain
import com.eddies.app.data.repo.toEntity
import com.eddies.app.domain.AssetIds
import com.eddies.app.domain.Transaction
import com.eddies.app.domain.TxSource
import com.eddies.app.domain.TxType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.math.BigDecimal

/**
 * A transaction has to come back from the database as it went in.
 *
 * The mappers are two hand-written lists of fields, and a field missing from
 * both compiles, runs and loses data. `cashAmount` was missing from both, so
 * every dividend ever entered was stored as no cash at all, while the
 * calculator that sums dividends passed all of its tests.
 */
class TransactionMappingTest {

    /** Every field set to something other than its default. */
    private val full = Transaction(
        id = 42,
        accountId = 7,
        assetId = AssetIds.stock("NASDAQ", "AAPL"),
        type = TxType.DIVIDEND,
        quantity = BigDecimal("1.5"),
        pricePerUnit = BigDecimal("190.25"),
        quoteCurrency = "CHF",
        feeQuantity = BigDecimal("0.35"),
        feeAssetId = "CHF",
        timestamp = 1_700_000_000_000,
        note = "Q3 payout",
        source = TxSource.IMPORT_CSV,
        externalId = "row-12",
        cashAmount = BigDecimal("24.60"),
    )

    @Test
    fun `every field survives the round trip`() {
        assertEquals(full, full.toEntity().toDomain())
    }

    @Test
    fun `a dividend keeps its cash amount`() {
        assertEquals(0, BigDecimal("24.60").compareTo(full.toEntity().toDomain().cashAmount))
    }

    /**
     * Keeps the test above honest when a field is added.
     *
     * A new field left at its default in [full] would round-trip "correctly"
     * through mappers that ignore it. This fails until the sample sets it.
     */
    @Test
    fun `the sample leaves no field at its default`() {
        val blank = Transaction(assetId = "", type = TxType.BUY, quantity = BigDecimal.ZERO)
        for (field in Transaction::class.java.declaredFields) {
            if (java.lang.reflect.Modifier.isStatic(field.modifiers)) continue
            field.isAccessible = true
            assertNotEquals("set '${field.name}' in the sample", field.get(blank), field.get(full))
        }
    }
}
