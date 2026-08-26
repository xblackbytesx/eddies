package com.eddies.app.data.staking

import com.eddies.app.data.price.asBigDecimal
import com.eddies.app.data.price.asStringOrNull
import com.eddies.app.domain.AssetIds
import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import java.math.BigDecimal
import java.math.MathContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Cardano staking, from Koios. No API key: the public tier allows 5000 requests
 * a day and a sync is one request.
 *
 * Reads `account_info`, not `account_rewards`. The rewards endpoint returns one
 * row per epoch going back to 2020, which is hundreds of rows describing money
 * that has mostly already been withdrawn. `rewards_available` is the single
 * number that matters: earned minus withdrawn, so it is exactly what is still
 * outstanding and can be added to a holding without double counting anything
 * already spent or recorded by hand.
 *
 * Verified against the live endpoint on 2026-08-25.
 */
@Singleton
class CardanoKoiosProvider @Inject constructor(
    private val http: HttpClient,
    private val json: Json,
) : StakingProvider {

    override val assetId: String = AssetIds.crypto("ada-cardano")

    /**
     * Cardano stake addresses are bech32 and start with `stake1` on mainnet or
     * `stake_test1` on the test networks. Checked so a payment address (`addr1`)
     * pasted by mistake fails with something better than an empty result.
     */
    override fun handles(address: String): Boolean {
        val trimmed = address.trim().lowercase()
        return trimmed.startsWith("stake1") || trimmed.startsWith("stake_test1")
    }

    override suspend fun fetch(stakeAddress: String): StakingSnapshot? = runCatching {
        val address = stakeAddress.trim()
        if (!handles(address)) return null

        val body = http.post("$BASE/account_info") {
            contentType(ContentType.Application.Json)
            setBody("""{"_stake_addresses":["$address"]}""")
        }.bodyAsText()

        val rows = json.parseToJsonElement(body) as? JsonArray ?: return null
        val row = rows.firstOrNull() as? JsonObject ?: return null

        // Everything is lovelace, as a string. Strings mean no precision is lost
        // on the way in, and the division below is exact.
        val pending = row["rewards_available"].asBigDecimal() ?: BigDecimal.ZERO
        val earned = row["rewards"].asBigDecimal() ?: BigDecimal.ZERO

        StakingSnapshot(
            stakeAddress = address,
            assetId = assetId,
            pending = pending.divide(LOVELACE, MathContext.DECIMAL128),
            totalEarned = earned.divide(LOVELACE, MathContext.DECIMAL128),
            poolId = row["delegated_pool"].asStringOrNull(),
        )
    }.getOrNull()

    private companion object {
        const val BASE = "https://api.koios.rest/api/v1"

        /** One ADA is 1,000,000 lovelace. */
        val LOVELACE: BigDecimal = BigDecimal("1000000")
    }
}
