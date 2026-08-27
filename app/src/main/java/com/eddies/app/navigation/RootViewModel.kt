package com.eddies.app.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.eddies.app.core.design.ThemeMode
import com.eddies.app.data.prefs.SettingsDataStore
import com.eddies.app.data.price.FxRepository
import com.eddies.app.data.repo.AssetRepository
import com.eddies.app.data.staking.StakingRepository
import com.eddies.app.demo.DemoSeeder
import com.eddies.app.work.WorkScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class RootUiState(
    val loading: Boolean = true,
    val themeMode: ThemeMode = ThemeMode.DARK,
    val dynamicColor: Boolean = false,
    val hideInRecents: Boolean = true,
    val hideNavOnScroll: Boolean = false,
    val locked: Boolean = false,
    val onboarded: Boolean = false,
)

/**
 * Gates the splash and owns the app-lock state.
 *
 * Uses SharingStarted.Eagerly rather than WhileSubscribed because the splash
 * asks for `loading` before anything is collecting, and a lazily started flow
 * would still be reporting true.
 */
@HiltViewModel
class RootViewModel @Inject constructor(
    private val settings: SettingsDataStore,
    private val assets: AssetRepository,
    private val fx: FxRepository,
    private val work: WorkScheduler,
    private val staking: StakingRepository,
    private val transactions: com.eddies.app.data.repo.TransactionRepository,
    private val demoSeeder: DemoSeeder,
) : ViewModel() {

    private val unlocked = MutableStateFlow(false)

    val state: StateFlow<RootUiState> = combine(settings.settings, unlocked) { cfg, isUnlocked ->
        RootUiState(
            loading = false,
            themeMode = cfg.themeMode,
            dynamicColor = cfg.dynamicColor,
            hideInRecents = cfg.hideInRecents,
            hideNavOnScroll = cfg.hideNavOnScroll,
            locked = cfg.appLockEnabled && !isUnlocked,
            onboarded = cfg.onboarded,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, RootUiState())

    init {
        viewModelScope.launch {
            // All three are idempotent and cheap after the first run.
            // A no-op in the full flavour: the implementation is chosen at build
            // time, so the real app carries no demo code path at all.
            runCatching { demoSeeder.seedIfNeeded() }

            assets.ensureSeeded()
            fx.refreshIfStale()
            // Cover the ledger, not just today. A transaction priced in another
            // currency has no cost basis at all without a rate for its own date.
            runCatching {
                transactions.earliestTimestamp()?.let { earliest ->
                    fx.ensureHistoryFrom(
                        java.time.Instant.ofEpochMilli(earliest)
                            .atZone(java.time.ZoneOffset.UTC).toLocalDate().toString(),
                    )
                }
            }
            work.ensureScheduled()
            // Cheap and idempotent: one request per staking address, and none
            // at all when no account has one.
            runCatching { staking.syncAll() }
        }
    }

    fun onUnlocked() {
        unlocked.value = true
    }

    /**
     * Re-locks on backgrounding. Deliberately immediate rather than honouring the
     * auto-lock delay: the delay governs how long an unlocked session survives
     * while in use, not whether leaving the app relocks it.
     */
    fun onBackgrounded() {
        viewModelScope.launch {
            if (settings.current().appLockEnabled) unlocked.value = false
        }
    }
}
