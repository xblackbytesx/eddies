package com.eddies.app.data.staking

import com.eddies.app.data.db.dao.AccountDao
import com.eddies.app.data.db.dao.StakingDao
import com.eddies.app.data.db.entity.StakingBalanceEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import javax.inject.Inject
import javax.inject.Singleton

/** What is outstanding per asset, summed across every address staking it. */
data class StakingTotals(val pendingByAsset: Map<String, BigDecimal>) {
    fun pendingFor(assetId: String): BigDecimal = pendingByAsset[assetId] ?: BigDecimal.ZERO

    companion object {
        val Empty = StakingTotals(emptyMap())
    }
}

@Singleton
class StakingRepository @Inject constructor(
    private val stakingDao: StakingDao,
    private val accountDao: AccountDao,
    private val providers: Set<@JvmSuppressWildcards StakingProvider>,
) {

    fun observeBalances(): Flow<List<StakingBalanceEntity>> = stakingDao.observeAll()

    /**
     * Outstanding rewards per asset. Several addresses can stake the same coin,
     * so these are summed rather than taking the first.
     */
    val totals: Flow<StakingTotals> = stakingDao.observeAll().map { rows ->
        StakingTotals(
            rows.groupBy { it.assetId }.mapValues { (_, group) ->
                group.fold(BigDecimal.ZERO) { acc, row ->
                    acc + (runCatching { BigDecimal(row.pending) }.getOrDefault(BigDecimal.ZERO))
                }
            },
        )
    }

    /**
     * Refreshes every account that has a staking address.
     *
     * A failure is stored on the row rather than thrown away, so the UI can say
     * "last synced two days ago, and here is why" instead of silently showing a
     * stale number as if it were current.
     */
    suspend fun syncAll(): Int = withContext(Dispatchers.IO) {
        stakingDao.pruneOrphans()
        var updated = 0
        for (account in accountDao.withStakingAddress()) {
            val address = account.stakingAddress?.trim().orEmpty()
            if (address.isEmpty()) continue
            val provider = providers.firstOrNull { it.handles(address) }
            if (provider == null) {
                stakingDao.upsert(
                    failureRow(address, account.id, assetId = "", message = "Not a supported staking address."),
                )
                continue
            }
            val snapshot = provider.fetch(address)
            if (snapshot == null) {
                stakingDao.upsert(
                    failureRow(address, account.id, provider.assetId, "Could not reach the chain."),
                )
                continue
            }
            stakingDao.upsert(
                StakingBalanceEntity(
                    stakeAddress = snapshot.stakeAddress,
                    assetId = snapshot.assetId,
                    accountId = account.id,
                    pending = snapshot.pending.stripTrailingZeros().toPlainString(),
                    totalEarned = snapshot.totalEarned.stripTrailingZeros().toPlainString(),
                    poolId = snapshot.poolId,
                    error = null,
                ),
            )
            updated++
        }
        updated
    }

    /** Keeps the last known figures rather than zeroing them on a failed sync. */
    private suspend fun failureRow(
        address: String,
        accountId: Long,
        assetId: String,
        message: String,
    ): StakingBalanceEntity {
        val existing = stakingDao.all().firstOrNull { it.stakeAddress == address }
        return StakingBalanceEntity(
            stakeAddress = address,
            assetId = existing?.assetId ?: assetId,
            accountId = accountId,
            pending = existing?.pending ?: "0",
            totalEarned = existing?.totalEarned ?: "0",
            poolId = existing?.poolId,
            syncedAt = existing?.syncedAt ?: 0L,
            error = message,
        )
    }

    suspend fun forget(stakeAddress: String) = stakingDao.delete(stakeAddress)
}
