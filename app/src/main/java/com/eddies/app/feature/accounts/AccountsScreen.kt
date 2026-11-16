package com.eddies.app.feature.accounts

import com.eddies.app.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.eddies.app.core.ui.EmptyHint
import com.eddies.app.core.ui.Section
import com.eddies.app.data.db.dao.AccountDao
import com.eddies.app.data.db.entity.AccountEntity
import com.eddies.app.data.db.entity.StakingBalanceEntity
import com.eddies.app.data.staking.StakingRepository
import com.eddies.app.feature.settings.InfoRow
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import com.eddies.app.core.ui.Strings
import javax.inject.Inject

data class AccountsUiState(
    val accounts: List<AccountEntity> = emptyList(),
    val balances: List<StakingBalanceEntity> = emptyList(),
    val nameDraft: String = "",
    val stakeDraft: String = "",
    val syncing: Boolean = false,
    val message: String? = null,
)

@HiltViewModel
class AccountsViewModel @Inject constructor(
    private val dao: AccountDao,
    private val staking: StakingRepository,
    private val strings: Strings,
) : ViewModel() {

    private val nameDraft = MutableStateFlow("")
    private val stakeDraft = MutableStateFlow("")
    private val syncing = MutableStateFlow(false)
    private val message = MutableStateFlow<String?>(null)

    val state: StateFlow<AccountsUiState> = combine(
        dao.observeAll(), staking.observeBalances(), nameDraft, stakeDraft,
        combine(syncing, message) { s, m -> s to m },
    ) { accounts, balances, name, stake, (isSyncing, msg) ->
        AccountsUiState(accounts, balances, name, stake, isSyncing, msg)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AccountsUiState())

    /**
     * Refreshes every staking address. Also runs after adding one, so a newly
     * pasted address shows a figure straight away rather than after the next
     * daily job.
     */
    fun sync() {
        if (syncing.value) return
        syncing.value = true
        viewModelScope.launch {
            val updated = runCatching { staking.syncAll() }.getOrDefault(0)
            syncing.value = false
            message.value = if (updated > 0) strings.plural(R.plurals.msg_staking_updated, updated)
            else strings(R.string.msg_staking_none)
        }
    }

    fun dismissMessage() { message.value = null }

    fun setName(v: String) { nameDraft.value = v }
    fun setStake(v: String) { stakeDraft.value = v }

    fun add() {
        val name = nameDraft.value.trim()
        if (name.isEmpty()) return
        val stake = stakeDraft.value.trim()
        viewModelScope.launch {
            dao.upsert(AccountEntity(name = name, stakingAddress = stake.takeIf { it.isNotEmpty() }))
            nameDraft.value = ""
            stakeDraft.value = ""
            if (stake.isNotEmpty()) sync()
        }
    }

    fun delete(id: Long) {
        viewModelScope.launch { dao.delete(id) }
    }
}

@Composable
fun AccountsScreen(
    modifier: Modifier = Modifier,
    viewModel: AccountsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(
        modifier = modifier.fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp).padding(bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Spacer(Modifier.size(4.dp))

        Section(stringResource(R.string.accounts_yours)) {
            if (state.accounts.isEmpty()) {
                EmptyHint(stringResource(R.string.accounts_empty))
            } else {
                state.accounts.forEachIndexed { i, account ->
                    if (i > 0) HorizontalDivider()
                    val balance = state.balances.firstOrNull { it.accountId == account.id }
                    InfoRow(
                        title = account.name,
                        value = when {
                            balance?.error != null -> stringResource(R.string.accounts_sync_failed)
                            balance != null -> stringResource(R.string.accounts_pending, balance.pending)
                            account.stakingAddress != null -> stringResource(R.string.accounts_not_synced)
                            else -> account.kind.lowercase()
                        },
                    )
                }
                if (state.accounts.any { it.stakingAddress != null }) {
                    HorizontalDivider()
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            stringResource(R.string.accounts_rewards_note),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = viewModel::sync, enabled = !state.syncing) {
                            Text(stringResource(if (state.syncing) R.string.accounts_checking else R.string.action_refresh))
                        }
                    }
                }
            }
        }

        state.message?.let { msg ->
            Text(
                msg,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        Section(
            title = stringResource(R.string.accounts_add_title),
            subtitle = stringResource(R.string.accounts_add_hint),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = state.nameDraft,
                    onValueChange = viewModel::setName,
                    label = { Text(stringResource(R.string.accounts_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = state.stakeDraft,
                    onValueChange = viewModel::setStake,
                    label = { Text(stringResource(R.string.accounts_stake)) },
                    placeholder = { Text("stake1...") },
                    singleLine = true,
                    supportingText = {
                        Text(stringResource(R.string.accounts_stake_hint))
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = viewModel::add,
                    enabled = state.nameDraft.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.accounts_add)) }
            }
        }
    }
}
