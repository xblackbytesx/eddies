package com.eddies.app.feature.merge

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.eddies.app.data.repo.AssetMergeRepository
import com.eddies.app.data.repo.MergePreview
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class MergeUiState(
    val scanning: Boolean = true,
    val duplicates: List<MergePreview> = emptyList(),
    val confirming: MergePreview? = null,
    val busy: Boolean = false,
    val message: String? = null,
)

@HiltViewModel
class MergeDuplicatesViewModel @Inject constructor(
    private val repo: AssetMergeRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(MergeUiState())
    val state: StateFlow<MergeUiState> = _state.asStateFlow()

    init { scan() }

    fun scan() {
        _state.update { it.copy(scanning = true) }
        viewModelScope.launch {
            val found = repo.findDuplicates()
            _state.update { it.copy(scanning = false, duplicates = found) }
        }
    }

    fun ask(preview: MergePreview) = _state.update { it.copy(confirming = preview) }
    fun dismiss() = _state.update { it.copy(confirming = null) }
    fun dismissMessage() = _state.update { it.copy(message = null) }

    fun confirm() {
        val preview = _state.value.confirming ?: return
        _state.update { it.copy(confirming = null, busy = true) }
        viewModelScope.launch {
            val moved = repo.merge(preview.group)
            _state.update {
                it.copy(
                    busy = false,
                    message = "Merged into ${preview.group.keep.symbol}. " +
                        "$moved ${if (moved == 1) "transaction" else "transactions"} moved.",
                )
            }
            // Rescanning rather than removing the row locally: the merge may
            // have changed which asset holds the most history, and a three-way
            // duplicate is still a duplicate afterwards.
            scan()
        }
    }
}
