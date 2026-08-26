package com.eddies.app.data.staking

import java.math.BigDecimal

/** What one chain reports about one staking address. */
data class StakingSnapshot(
    val stakeAddress: String,
    val assetId: String,
    /** Outstanding, un-withdrawn rewards, in whole coins. */
    val pending: BigDecimal,
    /** Lifetime rewards earned, in whole coins. */
    val totalEarned: BigDecimal,
    val poolId: String? = null,
)

/**
 * A chain that can report staking for an address.
 *
 * Cardano first. Solana, Cosmos and the rest are additional implementations,
 * not a change to anything here, which is the point of the interface.
 */
interface StakingProvider {
    /** The asset this provider stakes, for example crypto:ada-cardano. */
    val assetId: String

    /** True if the string looks like an address this provider handles. */
    fun handles(address: String): Boolean

    /** Null when the address is unknown or the call failed. */
    suspend fun fetch(stakeAddress: String): StakingSnapshot?
}
