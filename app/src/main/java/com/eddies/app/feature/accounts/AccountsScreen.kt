package com.eddies.app.feature.accounts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.eddies.app.core.ui.EmptyHint
import com.eddies.app.core.ui.Section
import com.eddies.app.data.db.dao.AccountDao
import com.eddies.app.data.db.entity.AccountEntity
import com.eddies.app.feature.settings.InfoRow
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AccountsUiState(
    val accounts: List<AccountEntity> = emptyList(),
    val nameDraft: String = "",
    val stakeDraft: String = "",
)

@HiltViewModel
class AccountsViewModel @Inject constructor(
    private val dao: AccountDao,
) : ViewModel() {

    private val nameDraft = MutableStateFlow("")
    private val stakeDraft = MutableStateFlow("")

    val state: StateFlow<AccountsUiState> = combine(
        dao.observeAll(), nameDraft, stakeDraft,
    ) { accounts, name, stake ->
        AccountsUiState(accounts, name, stake)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AccountsUiState())

    fun setName(v: String) { nameDraft.value = v }
    fun setStake(v: String) { stakeDraft.value = v }

    fun add() {
        val name = nameDraft.value.trim()
        if (name.isEmpty()) return
        viewModelScope.launch {
            dao.upsert(
                AccountEntity(name = name, stakingAddress = stakeDraft.value.trim().takeIf { it.isNotEmpty() }),
            )
            nameDraft.value = ""
            stakeDraft.value = ""
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

        Section("Your accounts") {
            if (state.accounts.isEmpty()) {
                EmptyHint("No accounts yet. One called Main is created automatically when you add a position.")
            } else {
                state.accounts.forEachIndexed { i, account ->
                    if (i > 0) HorizontalDivider()
                    InfoRow(
                        title = account.name,
                        value = account.stakingAddress?.let { "staking" } ?: account.kind.lowercase(),
                    )
                }
            }
        }

        Section(
            title = "Add an account",
            subtitle = "A staking address lets Eddies import reward history automatically.",
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = state.nameDraft,
                    onValueChange = viewModel::setName,
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = state.stakeDraft,
                    onValueChange = viewModel::setStake,
                    label = { Text("Staking address (optional)") },
                    placeholder = { Text("stake1...") },
                    singleLine = true,
                    supportingText = {
                        Text("Cardano stake addresses are supported. Reward import arrives in a later release.")
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = viewModel::add,
                    enabled = state.nameDraft.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Add account") }
            }
        }
    }
}
