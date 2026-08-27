package com.eddies.app.data.backup

import kotlinx.serialization.Serializable

/**
 * The backup payload. Versioned because a restore has to keep working against
 * files written by older builds; every field added later must have a default so
 * an old file still decodes.
 */
@Serializable
data class BackupPayload(
    val version: Int = CURRENT_VERSION,
    val createdAt: Long = System.currentTimeMillis(),
    val appVersion: String = "",
    val settings: BackupSettings? = null,
    val accounts: List<BackupAccount> = emptyList(),
    val assets: List<BackupAsset> = emptyList(),
    val transactions: List<BackupTransaction> = emptyList(),
    val custody: List<BackupCustody> = emptyList(),
    val splits: List<BackupSplit> = emptyList(),
) {
    companion object {
        const val CURRENT_VERSION = 1
    }
}

/** Shown before a restore actually runs, so the user can see what they are about to import. */
@Serializable
data class BackupManifest(
    val version: Int,
    val createdAt: Long,
    val appVersion: String,
    val transactionCount: Int,
    val accountCount: Int,
    val hasSettings: Boolean,
)

@Serializable
data class BackupSettings(
    val themeMode: String,
    val dynamicColor: Boolean,
    val compactRows: Boolean,
    val baseCurrency: String,
    val secondaryCurrency: String,
    val advancedMode: Boolean,
    val realtimeFeed: String,
    val aggregator: String,
    val pollIntervalSeconds: Int,
    val remoteIcons: Boolean,
    val costBasisMethod: String,
    val includeFeesInBasis: Boolean,
    val hideBalances: Boolean,
    val hideInRecents: Boolean,
    /** Defaulted so a backup written before this setting existed still restores. */
    val hideNavOnScroll: Boolean = false,
)

@Serializable
data class BackupAccount(
    val localId: Long,
    val name: String,
    val kind: String,
    val stakingAddress: String? = null,
)

/**
 * Asset metadata travels with the backup so a restore onto a device with an
 * older seed still knows the names and tickers of everything in the ledger.
 * Without it a restored portfolio shows rows of raw ids.
 */
@Serializable
data class BackupAsset(
    val id: String,
    val assetClass: String,
    val symbol: String,
    val name: String,
    val decimals: Int,
    val iconSlug: String? = null,
    val rank: Int? = null,
)

@Serializable
data class BackupTransaction(
    val accountLocalId: Long,
    val assetId: String,
    val type: String,
    val quantity: String,
    val pricePerUnit: String? = null,
    val quoteCurrency: String,
    val feeQuantity: String? = null,
    val feeAssetId: String? = null,
    val timestamp: Long,
    val note: String? = null,
    val source: String,
    val externalId: String? = null,
    val cashAmount: String? = null,
)

/** What the user chose to include. */
data class BackupOptions(
    val settings: Boolean = true,
    val portfolio: Boolean = true,
)

/**
 * Where each coin is kept.
 *
 * In the backup because losing it defeats the point of recording it: the moment
 * you most need to know which wallet holds your BTC is when you are setting the
 * app up again on a new phone.
 */
@Serializable
data class BackupCustody(
    val assetId: String,
    val type: String,
    val label: String,
    val note: String? = null,
)

/**
 * Share splits.
 *
 * In the backup because a restored ledger without them reports a pre-split share
 * count, which is silently wrong rather than visibly missing. They are
 * refetchable, but only while the provider still serves that symbol.
 */
@Serializable
data class BackupSplit(
    val assetId: String,
    val timestamp: Long,
    val numerator: String,
    val denominator: String,
)
