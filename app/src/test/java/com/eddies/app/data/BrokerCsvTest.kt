package com.eddies.app.data

import com.eddies.app.data.backup.Broker
import com.eddies.app.data.backup.BrokerCsv
import com.eddies.app.domain.AssetClass
import com.eddies.app.domain.TxType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Broker exports, read as the brokers write them.
 *
 * The samples follow each broker's documented layout. They are not copies of a
 * real account's file, so a column a broker adds later shows up as a failed
 * import on a phone, not here.
 */
class BrokerCsvTest {

    private val amsterdam = ZoneId.of("Europe/Amsterdam")

    private fun assertNum(expected: String, actual: BigDecimal?) {
        assertNotNull("expected $expected but was null", actual)
        assertEquals("expected $expected but was $actual", 0, BigDecimal(expected).compareTo(actual))
    }

    private fun utc(y: Int, mo: Int, d: Int, h: Int, mi: Int, s: Int = 0) =
        LocalDateTime.of(y, mo, d, h, mi, s).toInstant(ZoneOffset.UTC).toEpochMilli()

    // ---- detection -------------------------------------------------------

    @Test
    fun `this app's own csv is not mistaken for a broker file`() {
        assertNull(BrokerCsv.parse("timestamp,date,type,asset_id,symbol,quantity\n1,2,BUY,crypto:btc-bitcoin,BTC,1\n"))
        assertNull(BrokerCsv.parse(""))
    }

    @Test
    fun `a byte order mark in front of the header does not hide the broker`() {
        val csv = "\uFEFF\"txid\",\"ordertxid\",\"pair\",\"time\",\"type\",\"ordertype\",\"price\",\"cost\",\"fee\",\"vol\"\n"
        assertEquals(Broker.KRAKEN, BrokerCsv.parse(csv)?.broker)
    }

    // ---- DEGIRO ----------------------------------------------------------

    private val degiroEn = """
        Date,Time,Product,ISIN,Reference,Venue,Quantity,Price,,Local value,,Value,,Exchange rate,Transaction and/or third party fees,,Total,,Order ID
        05-03-2024,09:04,VANGUARD FTSE ALL-WORLD UCITS ETF,IE00BK5BQT80,EAM,XAMS,10,112.5000,EUR,-1125.00,EUR,-1125.00,EUR,,-1.00,EUR,-1126.00,EUR,aaaa-bbbb
        12-06-2024,15:31,APPLE INC,US0378331005,NDQ,XNAS,-2,210.0000,USD,420.00,USD,391.20,EUR,1.0736,-2.00,EUR,389.20,EUR,cccc-dddd
    """.trimIndent()

    @Test
    fun `a degiro export is read with a sale as a negative quantity`() {
        val parsed = BrokerCsv.parse(degiroEn, amsterdam)!!
        assertEquals(Broker.DEGIRO, parsed.broker)
        assertEquals(0, parsed.skipped)
        assertEquals(2, parsed.rows.size)

        val buy = parsed.rows[0]
        assertEquals(AssetClass.STOCK, buy.assetClass)
        assertEquals(TxType.BUY, buy.type)
        assertEquals("IE00BK5BQT80", buy.isin)
        assertNum("10", buy.quantity)
        assertNum("112.5", buy.pricePerUnit)
        assertEquals("EUR", buy.currency)
        assertNum("1", buy.fee)
        assertEquals("EUR", buy.feeCurrency)
        // 09:04 in Amsterdam in March is 08:04 UTC.
        assertEquals(utc(2024, 3, 5, 8, 4), buy.timestamp)

        val sell = parsed.rows[1]
        assertEquals(TxType.SELL, sell.type)
        assertNum("2", sell.quantity)
        assertNum("210", sell.pricePerUnit)
        // The price is in the listing's currency, the fee in the account's.
        assertEquals("USD", sell.currency)
        assertEquals("EUR", sell.feeCurrency)
    }

    @Test
    fun `a dutch degiro export with decimal commas reads the same`() {
        val csv = """
            Datum,Tijd,Product,ISIN,Beurs,Uitvoeringsplaats,Aantal,Koers,,Lokale waarde,,Waarde,,Wisselkoers,Transactiekosten en/of kosten van derden,,Totaal,,Order ID
            05-03-2024,09:04,VANGUARD FTSE ALL-WORLD UCITS ETF,IE00BK5BQT80,EAM,XAMS,10,"112,5000",EUR,"-1125,00",EUR,"-1125,00",EUR,,"-1,00",EUR,"-1126,00",EUR,aaaa-bbbb
        """.trimIndent()
        val row = BrokerCsv.parse(csv, amsterdam)!!.rows.single()
        assertNum("112.5", row.pricePerUnit)
        assertNum("1", row.fee)
        assertNum("10", row.quantity)
    }

    @Test
    fun `an extra column in a newer degiro export does not shift the others`() {
        val csv = """
            Date,Time,Product,ISIN,Reference,Venue,Quantity,Price,,Local value,,Value,,Exchange rate,AutoFX Fee,,Transaction and/or third party fees,,Total,,Order ID
            12-06-2024,15:31,APPLE INC,US0378331005,NDQ,XNAS,3,210.0000,USD,-630.00,USD,-586.80,EUR,1.0736,-1.47,EUR,-2.00,EUR,-590.27,EUR,cccc-dddd
        """.trimIndent()
        val row = BrokerCsv.parse(csv, amsterdam)!!.rows.single()
        assertNum("3", row.quantity)
        assertEquals("USD", row.currency)
        assertNum("2", row.fee)
    }

    @Test
    fun `a degiro row with no isin is counted, not imported`() {
        val csv = degiroEn + "\n01-07-2024,10:00,SOMETHING,,X,Y,5,1.00,EUR,-5.00,EUR,-5.00,EUR,,,,-5.00,EUR,\n"
        val parsed = BrokerCsv.parse(csv, amsterdam)!!
        assertEquals(2, parsed.rows.size)
        assertEquals(1, parsed.skipped)
        assertTrue(parsed.errors.single().startsWith("Row 4"))
    }

    // ---- Kraken ----------------------------------------------------------

    private val krakenHeader =
        "\"txid\",\"ordertxid\",\"pair\",\"time\",\"type\",\"ordertype\",\"price\",\"cost\",\"fee\",\"vol\",\"margin\",\"misc\",\"ledgers\""

    @Test
    fun `a kraken trades export is read in utc with its fee in the quote currency`() {
        val csv = krakenHeader + "\n" +
            "\"TAAAAA-BBBBB-CCCCCC\",\"OAAAAA-BBBBB-CCCCCC\",\"XXBTZEUR\",\"2024-01-05 12:34:56.1234\",\"buy\",\"limit\",\"40000.00000\",\"400.00000\",\"1.04000\",\"0.01000000\",\"0.00000\",\"\",\"L1,L2\"\n" +
            "\"TDDDDD-EEEEE-FFFFFF\",\"ODDDDD-EEEEE-FFFFFF\",\"ADA/EUR\",\"2024-02-01 08:00:00\",\"sell\",\"market\",\"0.50000\",\"50.00000\",\"0.13000\",\"100.00000000\",\"0.00000\",\"\",\"L3,L4\"\n"
        val parsed = BrokerCsv.parse(csv)!!
        assertEquals(Broker.KRAKEN, parsed.broker)
        assertEquals(2, parsed.rows.size)

        val buy = parsed.rows[0]
        assertEquals(AssetClass.CRYPTO, buy.assetClass)
        assertEquals("BTC", buy.symbol)
        assertEquals("EUR", buy.currency)
        assertNum("0.01", buy.quantity)
        assertNum("40000", buy.pricePerUnit)
        assertNum("1.04", buy.fee)
        assertEquals(utc(2024, 1, 5, 12, 34, 56), buy.timestamp)
        assertEquals("kraken:TAAAAA-BBBBB-CCCCCC", buy.externalId)

        assertEquals(TxType.SELL, parsed.rows[1].type)
        assertEquals("ADA", parsed.rows[1].symbol)
    }

    @Test
    fun `every spelling kraken has used for a pair splits the same way`() {
        assertEquals("BTC" to "EUR", BrokerCsv.splitKrakenPair("XXBTZEUR"))
        assertEquals("BTC" to "EUR", BrokerCsv.splitKrakenPair("XBTEUR"))
        assertEquals("BTC" to "EUR", BrokerCsv.splitKrakenPair("BTC/EUR"))
        assertEquals("ETH" to "USD", BrokerCsv.splitKrakenPair("XETHZUSD"))
        assertEquals("DOGE" to "EUR", BrokerCsv.splitKrakenPair("XDGEUR"))
        assertEquals("ADA" to "USDT", BrokerCsv.splitKrakenPair("ADAUSDT"))
        // XRP and ZEC start with the letters Kraken uses as class prefixes.
        assertEquals("XRP" to "EUR", BrokerCsv.splitKrakenPair("XXRPZEUR"))
        assertEquals("XRP" to "EUR", BrokerCsv.splitKrakenPair("XRPEUR"))
        assertEquals("ZEC" to "EUR", BrokerCsv.splitKrakenPair("XZECZEUR"))
        assertNull(BrokerCsv.splitKrakenPair("EUR"))
    }

    @Test
    fun `a stablecoin quote is dollars and a coin quote is refused`() {
        val csv = krakenHeader + "\n" +
            "\"T1\",\"O1\",\"ADAUSDT\",\"2024-01-05 12:00:00\",\"buy\",\"limit\",\"0.5\",\"50\",\"0.1\",\"100\",\"0\",\"\",\"\"\n" +
            "\"T2\",\"O2\",\"ETHXBT\",\"2024-01-05 12:00:00\",\"buy\",\"limit\",\"0.05\",\"0.05\",\"0.0001\",\"1\",\"0\",\"\",\"\"\n"
        val parsed = BrokerCsv.parse(csv)!!
        assertEquals("USD", parsed.rows.single().currency)
        assertEquals(1, parsed.skipped)
        assertTrue(parsed.errors.single().contains("coin-to-coin"))
    }

    // ---- Bitvavo ---------------------------------------------------------

    private val bitvavoHeader =
        "Timezone,Date,Time,Type,Currency,Amount,Quote Currency,Quote Price,Received / Paid Currency,Received / Paid Amount,Fee currency,Fee amount,Status,Transaction ID,Address"

    @Test
    fun `a bitvavo history keeps trades and rewards and passes over euro movements`() {
        val csv = listOf(
            bitvavoHeader,
            "Europe/Amsterdam,2024-03-05,09:04:10,deposit,EUR,500,,,,,,,Completed,id-1,",
            "Europe/Amsterdam,2024-03-05,09:10:00,buy,BTC,0.005,EUR,60000,EUR,-300.75,EUR,0.75,Completed,id-2,",
            "Europe/Amsterdam,2024-04-01,00:00:00,staking,ADA,1.25,,,,,,,Distributed,id-3,",
            "Europe/Amsterdam,2024-05-01,10:00:00,sell,BTC,-0.002,EUR,55000,EUR,109.72,EUR,0.28,Completed,id-4,",
            "Europe/Amsterdam,2024-05-02,10:00:00,withdrawal,BTC,-0.003,,,,,BTC,0.0001,Completed,id-5,bc1q",
        ).joinToString("\n")
        val parsed = BrokerCsv.parse(csv)!!
        assertEquals(Broker.BITVAVO, parsed.broker)
        assertEquals(0, parsed.skipped)
        assertEquals(
            listOf(TxType.BUY, TxType.STAKING_REWARD, TxType.SELL, TxType.TRANSFER_OUT),
            parsed.rows.map { it.type },
        )

        val buy = parsed.rows[0]
        assertEquals("BTC", buy.symbol)
        assertNum("0.005", buy.quantity)
        assertNum("60000", buy.pricePerUnit)
        assertNum("0.75", buy.fee)
        assertEquals("EUR", buy.feeCurrency)
        assertEquals(utc(2024, 3, 5, 8, 10), buy.timestamp)
        assertEquals("bitvavo:id-2", buy.externalId)

        // A sale is a negative amount in the file and a positive quantity here.
        assertNum("0.002", parsed.rows[2].quantity)
        // A fee charged in the coin is not a cost in money.
        assertNull(parsed.rows[3].fee)
        assertNull(parsed.rows[1].pricePerUnit)
    }

    @Test
    fun `a bitvavo row that did not complete is not a transaction`() {
        val csv = bitvavoHeader + "\nUTC,2024-03-05,09:10:00,buy,BTC,0.005,EUR,60000,EUR,-300.75,EUR,0.75,Cancelled,id-9,"
        assertTrue(BrokerCsv.parse(csv)!!.rows.isEmpty())
    }

    // ---- Trade Republic ---------------------------------------------------

    private val trHeader = listOf(
        "datetime", "date", "account_type", "category", "type", "asset_class", "name", "symbol", "shares",
        "price", "amount", "fee", "tax", "currency", "original_amount", "original_currency", "fx_rate",
        "description", "transaction_id", "counterparty_name", "counterparty_iban", "payment_reference", "mcc_code",
    )

    /** One export row, every field quoted as the real file quotes them. */
    private fun tr(vararg values: Pair<String, String>, delimiter: String = ","): String {
        val byName = values.toMap()
        return trHeader.joinToString(delimiter) { "\"" + byName[it].orEmpty() + "\"" }
    }

    private fun trFile(vararg rows: String, delimiter: String = ",") =
        (listOf(trHeader.joinToString(delimiter) { "\"$it\"" }) + rows).joinToString("\r\n")

    private val trBuy = tr(
        "datetime" to "2025-01-06T10:00:00.000000Z", "date" to "2025-01-06", "account_type" to "DEFAULT",
        "category" to "TRADING", "type" to "BUY", "asset_class" to "STOCK", "name" to "Acme Corp",
        "symbol" to "DE0000000001", "shares" to "10.0000000000", "price" to "50.0000000000",
        "amount" to "-500.00", "fee" to "-1.00", "currency" to "EUR",
        "description" to "Buy trade DE0000000001 ACME CORP, quantity: 10", "transaction_id" to "id-3",
    )

    @Test
    fun `a trade republic export keeps trades and dividends and passes over the bank account`() {
        val csv = trFile(
            tr("datetime" to "2025-01-02T09:00:00.000Z", "category" to "CASH", "type" to "CUSTOMER_INPAYMENT",
                "amount" to "1000.00", "currency" to "EUR", "transaction_id" to "id-1"),
            trBuy,
            tr("datetime" to "2025-03-14T10:00:00.000000Z", "category" to "CASH", "type" to "DIVIDEND",
                "asset_class" to "STOCK", "name" to "Example Inc", "symbol" to "US0000000003",
                "shares" to "20.0000000000", "amount" to "12.000000", "tax" to "-3.17", "currency" to "EUR",
                "original_amount" to "13.00", "original_currency" to "USD", "transaction_id" to "id-12"),
            tr("datetime" to "2025-05-05T10:00:00.000000Z", "category" to "TRADING", "type" to "SELL",
                "asset_class" to "STOCK", "name" to "Acme Corp", "symbol" to "DE0000000001",
                "shares" to "-6.0000000000", "price" to "60.0000000000", "amount" to "360.00",
                "fee" to "-1.00", "tax" to "-2.37", "currency" to "EUR", "transaction_id" to "id-17"),
            tr("datetime" to "2025-08-20T15:00:04.759491Z", "category" to "CASH",
                "type" to "CARD_TRANSACTION_INTERNATIONAL", "name" to "A SHOP, SOMEWHERE", "amount" to "-53.060000",
                "fee" to "-1.00", "currency" to "EUR", "transaction_id" to "id-20", "mcc_code" to "6011"),
        )
        val parsed = BrokerCsv.parse(csv)!!
        assertEquals(Broker.TRADE_REPUBLIC, parsed.broker)
        assertEquals(0, parsed.skipped)
        assertEquals(listOf(TxType.BUY, TxType.DIVIDEND, TxType.SELL), parsed.rows.map { it.type })

        val buy = parsed.rows[0]
        assertEquals(AssetClass.STOCK, buy.assetClass)
        assertEquals("DE0000000001", buy.isin)
        assertNum("10", buy.quantity)
        assertNum("50", buy.pricePerUnit)
        assertNum("1", buy.fee)
        assertEquals("EUR", buy.currency)
        assertEquals(utc(2025, 1, 6, 10, 0), buy.timestamp)
        assertEquals("traderepublic:id-3", buy.externalId)

        // A dividend is what arrived: 12.00 gross less 3.17 withheld.
        val dividend = parsed.rows[1]
        assertEquals("US0000000003", dividend.isin)
        assertNum("0", dividend.quantity)
        assertNum("8.83", dividend.cashAmount)

        // The side comes from the type, so a negative share count is a magnitude.
        assertNum("6", parsed.rows[2].quantity)
        assertNum("60", parsed.rows[2].pricePerUnit)
    }

    @Test
    fun `the semicolon variant of the trade republic export reads the same`() {
        val row = tr(
            "datetime" to "2025-01-06T10:00:00.000000Z", "category" to "TRADING", "type" to "BUY",
            "asset_class" to "FUND", "symbol" to "IE0000000002", "shares" to "1.2345670000",
            "price" to "81.0000000000", "amount" to "-100.00", "currency" to "EUR", "transaction_id" to "id-5",
            delimiter = ";",
        )
        val parsed = BrokerCsv.parse(trFile(row, delimiter = ";"))!!
        assertEquals(Broker.TRADE_REPUBLIC, parsed.broker)
        assertNum("1.234567", parsed.rows.single().quantity)
    }

    @Test
    fun `trade republic crypto is named by ticker and a coin received is a transfer in`() {
        val csv = trFile(
            tr("datetime" to "2026-01-19T13:31:47.160Z", "category" to "DELIVERY", "type" to "FREE_RECEIPT",
                "asset_class" to "CRYPTO", "name" to "Ethereum", "symbol" to "ETH", "shares" to "0.0004910000",
                "price" to "2756.9800000000", "currency" to "EUR", "transaction_id" to "id-9"),
        )
        val row = BrokerCsv.parse(csv)!!.rows.single()
        assertEquals(AssetClass.CRYPTO, row.assetClass)
        assertEquals(TxType.TRANSFER_IN, row.type)
        assertEquals("ETH", row.symbol)
        assertNull(row.isin)
        assertNum("0.000491", row.quantity)
    }

    @Test
    fun `a missing price is worked out from the amount`() {
        val csv = trFile(
            tr("datetime" to "2025-01-06T10:00:00Z", "category" to "TRADING", "type" to "BUY",
                "asset_class" to "STOCK", "symbol" to "DE0000000001", "shares" to "4",
                "amount" to "-500.00", "currency" to "EUR", "transaction_id" to "id-30"),
        )
        assertNum("125", BrokerCsv.parse(csv)!!.rows.single().pricePerUnit)
    }

    @Test
    fun `a dividend correction is reported, not imported as income`() {
        val csv = trFile(
            tr("datetime" to "2025-03-15T10:00:00Z", "category" to "CASH", "type" to "DIVIDEND",
                "asset_class" to "STOCK", "symbol" to "US0000000003", "amount" to "-12.000000", "tax" to "3.17",
                "currency" to "EUR", "transaction_id" to "id-13"),
        )
        val parsed = BrokerCsv.parse(csv)!!
        assertTrue(parsed.rows.isEmpty())
        assertEquals(1, parsed.skipped)
        assertTrue(parsed.errors.single().contains("correction"))
    }

    // ---- numbers ---------------------------------------------------------

    @Test
    fun `numbers read in both conventions`() {
        assertNum("1234.56", BrokerCsv.number("1,234.56"))
        assertNum("1234.56", BrokerCsv.number("1.234,56"))
        assertNum("112.5", BrokerCsv.number("112,5000"))
        assertNum("-1.5", BrokerCsv.number("-1,5"))
        assertNum("0.01", BrokerCsv.number("0.01000000"))
        assertNull(BrokerCsv.number("n/a"))
        assertNull(BrokerCsv.number(""))
    }
}
