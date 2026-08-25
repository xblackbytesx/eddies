package com.eddies.app.feature.backup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.eddies.app.core.backup.BackupCrypto
import com.eddies.app.data.backup.BackupManager
import com.eddies.app.data.backup.BackupManifest
import com.eddies.app.data.backup.BackupOptions
import com.eddies.app.data.backup.CsvExchange
import com.eddies.app.data.db.dao.AssetDao
import com.eddies.app.data.prefs.SettingsDataStore
import com.eddies.app.data.repo.TransactionRepository
import com.eddies.app.data.repo.toDomain
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class BackupUiState(
    val passphrase: String = "",
    val includeSettings: Boolean = true,
    val includePortfolio: Boolean = true,
    val busy: Boolean = false,
    val manifest: BackupManifest? = null,
    val pendingBytes: ByteArray? = null,
    val message: String? = null,
    val error: String? = null,
) {
    val canExport: Boolean get() = passphrase.length >= MIN_PASSPHRASE && !busy
    val canPreview: Boolean get() = passphrase.isNotEmpty() && !busy

    companion object {
        /** Short enough to be typed twice, long enough that PBKDF2 is doing real work. */
        const val MIN_PASSPHRASE = 8
    }
}

@HiltViewModel
class BackupViewModel @Inject constructor(
    private val manager: BackupManager,
    private val transactions: TransactionRepository,
    private val assetDao: AssetDao,
    private val settings: SettingsDataStore,
) : ViewModel() {

    private val _state = MutableStateFlow(BackupUiState())
    val state: StateFlow<BackupUiState> = _state.asStateFlow()

    fun setPassphrase(v: String) = _state.update { it.copy(passphrase = v, error = null) }
    fun setIncludeSettings(v: Boolean) = _state.update { it.copy(includeSettings = v) }
    fun setIncludePortfolio(v: Boolean) = _state.update { it.copy(includePortfolio = v) }
    fun dismissMessage() = _state.update { it.copy(message = null, error = null) }
    fun dismissManifest() = _state.update { it.copy(manifest = null, pendingBytes = null) }

    fun export(write: suspend (ByteArray) -> Unit) {
        val s = _state.value
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            runCatching {
                val bytes = manager.create(
                    BackupOptions(settings = s.includeSettings, portfolio = s.includePortfolio),
                    s.passphrase.toCharArray(),
                )
                write(bytes)
            }.onSuccess {
                _state.update { it.copy(busy = false, message = "Backup written.", passphrase = "") }
            }.onFailure { e ->
                _state.update { it.copy(busy = false, error = e.message ?: "Could not write the backup.") }
            }
        }
    }

    /**
     * Decrypts and describes the file without importing anything, so the user
     * sees what they are about to merge before it happens.
     */
    fun preview(bytes: ByteArray) {
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            runCatching { manager.readManifest(bytes, _state.value.passphrase.toCharArray()) }
                .onSuccess { manifest ->
                    _state.update { it.copy(busy = false, manifest = manifest, pendingBytes = bytes) }
                }
                .onFailure { e ->
                    val msg = when (e) {
                        is BackupCrypto.BadPassphraseException -> "Wrong passphrase, or the file is damaged."
                        is BackupCrypto.InvalidBackupException -> e.message ?: "Not an Eddies backup."
                        else -> e.message ?: "Could not read the file."
                    }
                    _state.update { it.copy(busy = false, error = msg) }
                }
        }
    }

    fun confirmRestore() {
        val bytes = _state.value.pendingBytes ?: return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            runCatching { manager.restore(bytes, _state.value.passphrase.toCharArray()) }
                .onSuccess { count ->
                    _state.update {
                        it.copy(
                            busy = false, manifest = null, pendingBytes = null, passphrase = "",
                            message = "Imported $count transactions.",
                        )
                    }
                }
                .onFailure { e ->
                    _state.update { it.copy(busy = false, error = e.message ?: "Restore failed.") }
                }
        }
    }

    fun exportCsv(write: suspend (String) -> Unit) {
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            runCatching {
                val txs = transactions.all()
                val assets = assetDao.byIds(txs.map { it.assetId }.distinct())
                    .associate { it.id to it.toDomain() }
                write(CsvExchange.export(txs, assets))
            }.onSuccess {
                _state.update { it.copy(busy = false, message = "CSV written. It is not encrypted.") }
            }.onFailure { e ->
                _state.update { it.copy(busy = false, error = e.message ?: "Could not write the CSV.") }
            }
        }
    }

    fun importCsv(content: String) {
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            val result = CsvExchange.import(content, settings.current().baseCurrency)
            if (result.transactions.isEmpty()) {
                _state.update {
                    it.copy(busy = false, error = result.errors.firstOrNull() ?: "Nothing importable in that file.")
                }
                return@launch
            }
            val accountId = transactions.defaultAccountId()
            val imported = transactions.importDeduplicated(
                result.transactions.map { it.copy(accountId = accountId) },
            )
            _state.update {
                it.copy(
                    busy = false,
                    message = buildString {
                        append("Imported $imported transactions.")
                        if (result.skipped > 0) append(" Skipped ${result.skipped} unreadable rows.")
                    },
                )
            }
        }
    }
}
