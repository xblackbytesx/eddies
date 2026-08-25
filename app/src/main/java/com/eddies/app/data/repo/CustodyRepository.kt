package com.eddies.app.data.repo

import com.eddies.app.data.db.dao.CustodyDao
import com.eddies.app.data.db.entity.AssetCustodyEntity
import com.eddies.app.data.db.entity.CustodyType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CustodyRepository @Inject constructor(
    private val dao: CustodyDao,
) {
    fun observe(assetId: String): Flow<AssetCustodyEntity?> = dao.observe(assetId)

    fun observeAll(): Flow<Map<String, AssetCustodyEntity>> =
        dao.observeAll().map { rows -> rows.associateBy { it.assetId } }

    suspend fun all(): List<AssetCustodyEntity> = dao.all()

    suspend fun knownLabels(): List<String> = dao.knownLabels()

    /** Blank label clears the entry rather than storing an empty place. */
    suspend fun set(assetId: String, type: CustodyType, label: String, note: String? = null) {
        val trimmed = label.trim()
        if (trimmed.isEmpty()) {
            dao.clear(assetId)
            return
        }
        dao.upsert(
            AssetCustodyEntity(
                assetId = assetId,
                type = type,
                label = trimmed,
                note = note?.trim()?.takeIf { it.isNotEmpty() },
            ),
        )
    }

    suspend fun clear(assetId: String) = dao.clear(assetId)
}
