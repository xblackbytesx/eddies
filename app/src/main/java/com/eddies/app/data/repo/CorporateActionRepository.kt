package com.eddies.app.data.repo

import com.eddies.app.data.db.dao.CorporateActionDao
import com.eddies.app.data.db.entity.SplitEventEntity
import com.eddies.app.domain.SplitEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.math.BigDecimal
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CorporateActionRepository @Inject constructor(
    private val dao: CorporateActionDao,
) {
    /** Splits per asset, ready to hand to PositionCalculator.fold. */
    val splitsByAsset: Flow<Map<String, List<SplitEvent>>> = dao.observeAll().map { rows ->
        rows.groupBy { it.assetId }.mapValues { (_, group) -> group.map { it.toDomain() } }
    }

    suspend fun forAsset(assetId: String): List<SplitEvent> = dao.forAsset(assetId).map { it.toDomain() }

    suspend fun record(events: List<SplitEvent>) {
        if (events.isEmpty()) return
        dao.upsert(
            events.map {
                SplitEventEntity(
                    assetId = it.assetId,
                    timestamp = it.timestamp,
                    numerator = it.numerator.toPlainString(),
                    denominator = it.denominator.toPlainString(),
                )
            },
        )
    }
}

internal fun SplitEventEntity.toDomain() = SplitEvent(
    assetId = assetId,
    timestamp = timestamp,
    numerator = runCatching { BigDecimal(numerator) }.getOrDefault(BigDecimal.ONE),
    denominator = runCatching { BigDecimal(denominator) }.getOrDefault(BigDecimal.ONE),
)
