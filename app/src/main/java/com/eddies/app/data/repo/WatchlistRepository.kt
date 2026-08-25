package com.eddies.app.data.repo

import com.eddies.app.data.db.dao.WatchlistDao
import com.eddies.app.data.db.entity.WatchlistEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Coins the user follows without holding.
 *
 * Watched assets join held ones in `PriceRepository.trackedAssetIds`, so adding
 * one starts pricing it live and removing one stops. That is the whole feature:
 * there is no separate polling path to keep in step.
 */
@Singleton
class WatchlistRepository @Inject constructor(
    private val dao: WatchlistDao,
) {
    val assetIds: Flow<Set<String>> = dao.observeAll().map { rows -> rows.map { it.assetId }.toSet() }

    suspend fun add(assetId: String) = dao.add(WatchlistEntity(assetId = assetId))

    suspend fun remove(assetId: String) = dao.remove(assetId)

    suspend fun toggle(assetId: String, watched: Boolean) {
        if (watched) add(assetId) else remove(assetId)
    }
}
