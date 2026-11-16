package com.eddies.app.data.backup

import com.eddies.app.BuildConfig
import com.eddies.app.core.backup.BackupCrypto
import com.eddies.app.data.db.dao.AccountDao
import androidx.room.withTransaction
import com.eddies.app.data.db.EddiesDatabase
import com.eddies.app.data.db.dao.AssetDao
import com.eddies.app.data.db.dao.AssetSourceRefDao
import com.eddies.app.data.db.dao.WatchlistDao
import com.eddies.app.data.db.entity.AssetSourceRefEntity
import com.eddies.app.data.db.entity.WatchlistEntity
import com.eddies.app.domain.PriceSourceId
import com.eddies.app.data.db.dao.CustodyDao
import com.eddies.app.data.db.entity.AccountEntity
import com.eddies.app.data.db.entity.AssetCustodyEntity
import com.eddies.app.data.db.entity.AssetEntity
import com.eddies.app.data.db.entity.CustodyType
import com.eddies.app.data.prefs.Aggregator
import com.eddies.app.data.prefs.RealtimeFeed
import com.eddies.app.data.prefs.SettingsDataStore
import com.eddies.app.data.repo.TransactionRepository
import com.eddies.app.core.design.ThemeMode
import com.eddies.app.domain.AssetClass
import com.eddies.app.domain.CostBasisMethod
import com.eddies.app.domain.Transaction
import com.eddies.app.domain.TxSource
import com.eddies.app.domain.TxType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.math.BigDecimal
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Writes and reads the passphrase-encrypted portfolio file.
 *
 * The envelope is [BackupCrypto]'s, which is independent of the Android Keystore
 * on purpose: a backup sealed with a Keystore key could not be opened on a new
 * phone, which is the one moment a backup is for.
 */
@Singleton
class BackupManager @Inject constructor(
    private val transactions: TransactionRepository,
    private val accountDao: AccountDao,
    private val assetDao: AssetDao,
    private val settings: SettingsDataStore,
    private val custodyDao: CustodyDao,
    private val corporateActionDao: com.eddies.app.data.db.dao.CorporateActionDao,
    private val sourceRefDao: AssetSourceRefDao,
    private val watchlistDao: WatchlistDao,
    private val db: EddiesDatabase,
    private val json: Json,
) {

    suspend fun create(options: BackupOptions, passphrase: CharArray): ByteArray = withContext(Dispatchers.IO) {
        val cfg = settings.current()
        val txs = if (options.portfolio) transactions.all() else emptyList()
        val accounts = if (options.portfolio) accountDao.all() else emptyList()

        val watched = if (options.portfolio) watchlistDao.assetIds() else emptyList()

        // Only the assets actually referenced, not the whole seed: the seed is in
        // the APK already and shipping 600 rows would bloat every backup.
        val referenced = (txs.map { it.assetId } + watched).distinct()
        val assets = if (referenced.isEmpty()) emptyList() else assetDao.byIds(referenced)
        val refs = if (referenced.isEmpty()) emptyList() else sourceRefDao.forAssets(referenced)

        val payload = BackupPayload(
            appVersion = BuildConfig.VERSION_NAME,
            settings = if (options.settings) cfg.toBackup() else null,
            accounts = accounts.map { it.toBackup() },
            assets = assets.map { it.toBackup() },
            transactions = txs.map { it.toBackup() },
            custody = if (options.portfolio) custodyDao.all().map { it.toBackup() } else emptyList(),
            splits = if (options.portfolio) corporateActionDao.all().map {
                BackupSplit(it.assetId, it.timestamp, it.numerator, it.denominator)
            } else emptyList(),
            sourceRefs = refs.map {
                BackupSourceRef(it.assetId, it.source.name, it.sourceSymbol, it.quoteCurrency)
            },
            watchlist = watched,
        )
        BackupCrypto.encrypt(json.encodeToString(payload).toByteArray(), passphrase)
    }

    /**
     * Validates the passphrase and reports what the file holds, without writing
     * anything. Restoring blind into an existing portfolio is how people lose
     * data they meant to keep.
     */
    suspend fun readManifest(data: ByteArray, passphrase: CharArray): BackupManifest = withContext(Dispatchers.IO) {
        val payload = decode(data, passphrase)
        BackupManifest(
            version = payload.version,
            createdAt = payload.createdAt,
            appVersion = payload.appVersion,
            transactionCount = payload.transactions.size,
            accountCount = payload.accounts.size,
            hasSettings = payload.settings != null,
        )
    }

    /**
     * Merges the file into what is already here. Returns how many transactions
     * were added.
     *
     * Additive rather than replacing, and safe to repeat: restoring the same
     * file twice adds nothing the second time. Transactions are recognized by
     * content (see [com.eddies.app.domain.LedgerDedup]) and accounts by name,
     * since neither has an id that means anything on another device.
     *
     * One database transaction, so a file that fails halfway leaves the ledger
     * exactly as it was rather than holding accounts with nothing in them.
     */
    suspend fun restore(data: ByteArray, passphrase: CharArray): Int = withContext(Dispatchers.IO) {
        val payload = decode(data, passphrase)
        val imported = db.withTransaction { merge(payload) }
        // Settings live in DataStore, outside the database, so they follow once
        // the ledger is safely in.
        payload.settings?.let { applySettings(it) }
        imported
    }

    private suspend fun merge(payload: BackupPayload): Int {
        if (payload.assets.isNotEmpty()) {
            // Whether an asset is tracked is this device's knowledge, and the
            // file does not carry it. Keep what is already here.
            val tracked = assetDao.byIds(payload.assets.map { it.id })
                .filter { it.tracked }
                .mapTo(HashSet()) { it.id }
            assetDao.upsert(payload.assets.map { it.toEntity().copy(tracked = it.id in tracked) })
        }

        // Account ids are per-device, so the file's ids are remapped onto
        // whatever the accounts are here. An account that already exists under
        // the same name is reused: inserting it again gave every restore a
        // second "Main" and split one wallet's rows across both.
        val here = accountDao.all().toMutableList()
        val idMap = HashMap<Long, Long>()
        for (account in payload.accounts) {
            val match = here.firstOrNull {
                it.name.trim().equals(account.name.trim(), ignoreCase = true) && it.kind == account.kind
            }
            if (match != null) {
                if (match.stakingAddress.isNullOrBlank() && !account.stakingAddress.isNullOrBlank()) {
                    accountDao.upsert(match.copy(stakingAddress = account.stakingAddress))
                }
                idMap[account.localId] = match.id
                continue
            }
            val created = AccountEntity(
                name = account.name,
                kind = account.kind,
                stakingAddress = account.stakingAddress,
            )
            val newId = accountDao.upsert(created)
            here += created.copy(id = newId)
            idMap[account.localId] = newId
        }

        val defaultAccount = transactions.defaultAccountId()
        val imported = transactions.importDeduplicated(
            payload.transactions.map { it.toDomain(idMap[it.accountLocalId] ?: defaultAccount) },
        )

        // Rows that key on an asset are only written for assets that exist
        // here, since a reference to a missing one is refused by the database
        // and would take the whole restore down with it.
        val wanted = (payload.sourceRefs.map { it.assetId } + payload.watchlist).distinct()
        val present = if (wanted.isEmpty()) emptySet() else assetDao.byIds(wanted).mapTo(HashSet()) { it.id }

        val refs = payload.sourceRefs.mapNotNull { ref ->
            val source = runCatching { PriceSourceId.valueOf(ref.source) }.getOrNull()
            if (source == null || ref.assetId !in present) null
            else AssetSourceRefEntity(ref.assetId, source, ref.sourceSymbol, ref.quoteCurrency)
        }
        if (refs.isNotEmpty()) sourceRefDao.upsert(refs)

        val alreadyWatched = watchlistDao.assetIds().toSet()
        payload.watchlist
            .filter { it in present && it !in alreadyWatched }
            .forEach { watchlistDao.add(WatchlistEntity(assetId = it)) }

        // Restored after the assets exist, since it keys on them.
        payload.custody.forEach { entry ->
            runCatching { custodyDao.upsert(entry.toEntity()) }
        }

        if (payload.splits.isNotEmpty()) {
            runCatching {
                corporateActionDao.upsert(
                    payload.splits.map {
                        com.eddies.app.data.db.entity.SplitEventEntity(
                            assetId = it.assetId,
                            timestamp = it.timestamp,
                            numerator = it.numerator,
                            denominator = it.denominator,
                        )
                    },
                )
            }
        }

        return imported
    }

    private fun decode(data: ByteArray, passphrase: CharArray): BackupPayload {
        val plain = BackupCrypto.decrypt(data, passphrase)
        return json.decodeFromString<BackupPayload>(String(plain))
    }

    private suspend fun applySettings(s: BackupSettings) {
        runCatching { settings.setThemeMode(ThemeMode.valueOf(s.themeMode)) }
        settings.setDynamicColor(s.dynamicColor)
        settings.setCompactRows(s.compactRows)
        settings.setBaseCurrency(s.baseCurrency)
        settings.setSecondaryCurrency(s.secondaryCurrency)
        settings.setAdvancedMode(s.advancedMode)
        runCatching { settings.setRealtimeFeed(RealtimeFeed.valueOf(s.realtimeFeed)) }
        runCatching { settings.setAggregator(Aggregator.valueOf(s.aggregator)) }
        settings.setPollInterval(s.pollIntervalSeconds)
        settings.setRemoteIcons(s.remoteIcons)
        runCatching { settings.setCostBasisMethod(CostBasisMethod.valueOf(s.costBasisMethod)) }
        settings.setIncludeFeesInBasis(s.includeFeesInBasis)
        settings.setHideBalances(s.hideBalances)
        settings.setHideInRecents(s.hideInRecents)
        settings.setHideNavOnScroll(s.hideNavOnScroll)
        // The API keys are deliberately NOT in the backup. They are sealed by
        // this device's Keystore, and a passphrase-protected file is a weaker
        // place for them than where they already are.
    }
}

private fun com.eddies.app.data.prefs.AppSettings.toBackup() = BackupSettings(
    themeMode = themeMode.name,
    dynamicColor = dynamicColor,
    compactRows = compactRows,
    baseCurrency = baseCurrency,
    secondaryCurrency = secondaryCurrency,
    advancedMode = advancedMode,
    realtimeFeed = realtimeFeed.name,
    aggregator = aggregator.name,
    pollIntervalSeconds = pollIntervalSeconds,
    remoteIcons = remoteIcons,
    costBasisMethod = costBasisMethod.name,
    includeFeesInBasis = includeFeesInBasis,
    hideBalances = hideBalances,
    hideInRecents = hideInRecents,
    hideNavOnScroll = hideNavOnScroll,
)

private fun AccountEntity.toBackup() = BackupAccount(
    localId = id,
    name = name,
    kind = kind,
    stakingAddress = stakingAddress,
)

private fun AssetEntity.toBackup() = BackupAsset(
    id = id,
    assetClass = assetClass.name,
    symbol = symbol,
    name = name,
    decimals = decimals,
    iconSlug = iconSlug,
    rank = rank,
)

private fun BackupAsset.toEntity() = AssetEntity(
    id = id,
    assetClass = runCatching { AssetClass.valueOf(assetClass) }.getOrDefault(AssetClass.CRYPTO),
    symbol = symbol,
    name = name,
    decimals = decimals,
    iconSlug = iconSlug,
    rank = rank,
)

private fun Transaction.toBackup() = BackupTransaction(
    accountLocalId = accountId,
    assetId = assetId,
    type = type.name,
    quantity = quantity.toPlainString(),
    pricePerUnit = pricePerUnit?.toPlainString(),
    quoteCurrency = quoteCurrency,
    feeQuantity = feeQuantity?.toPlainString(),
    feeAssetId = feeAssetId,
    timestamp = timestamp,
    note = note,
    source = source.name,
    externalId = externalId,
    cashAmount = cashAmount?.toPlainString(),
)

private fun BackupTransaction.toDomain(accountId: Long) = Transaction(
    id = 0,
    accountId = accountId,
    assetId = assetId,
    type = runCatching { TxType.valueOf(type) }.getOrDefault(TxType.BUY),
    quantity = runCatching { BigDecimal(quantity) }.getOrDefault(BigDecimal.ZERO),
    pricePerUnit = pricePerUnit?.let { runCatching { BigDecimal(it) }.getOrNull() },
    quoteCurrency = quoteCurrency,
    feeQuantity = feeQuantity?.let { runCatching { BigDecimal(it) }.getOrNull() },
    feeAssetId = feeAssetId,
    timestamp = timestamp,
    note = note,
    source = runCatching { TxSource.valueOf(source) }.getOrDefault(TxSource.MANUAL),
    externalId = externalId,
    cashAmount = cashAmount?.let { runCatching { BigDecimal(it) }.getOrNull() },
)

private fun AssetCustodyEntity.toBackup() = BackupCustody(
    assetId = assetId,
    type = type.name,
    label = label,
    note = note,
)

private fun BackupCustody.toEntity() = AssetCustodyEntity(
    assetId = assetId,
    type = runCatching { CustodyType.valueOf(type) }.getOrDefault(CustodyType.OTHER),
    label = label,
    note = note,
)
