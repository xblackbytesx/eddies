package com.eddies.app.feature.addtransaction

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.eddies.app.core.ui.AssetIcon

import com.eddies.app.data.repo.label
import com.eddies.app.domain.MoneyFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * One screen, one scroll, one save.
 *
 * Deliberately not a wizard: adding a position is the single most repeated
 * action in the app, and every extra step is paid on every trade a user ever
 * records.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AddTransactionScreen(
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AddTransactionViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showAdvanced by remember { mutableStateOf(false) }
    var showDatePicker by remember { mutableStateOf(false) }

    LaunchedEffect(state.saved) { if (state.saved) onDone() }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        state.asset?.let { asset ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                AssetIcon(asset, state.iconUri, size = 44.dp)
                Spacer(Modifier.size(12.dp))
                Column {
                    Text(asset.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        asset.symbol,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // Type. Buy is first and preselected because it is the overwhelming
        // majority of what gets entered.
        Column {
            Text("Type", style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.size(6.dp))
            androidx.compose.foundation.layout.FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                state.availableTypes.forEach { type ->
                    FilterChip(
                        selected = state.type == type,
                        onClick = { viewModel.setType(type) },
                        label = { Text(type.label) },
                    )
                }
            }
        }

        if (state.isCashOnly) {
            // A dividend is cash received. Offering quantity and unit price here
            // would invite a row that changes a share count it should not touch.
            OutlinedTextField(
                value = state.cashInput,
                onValueChange = viewModel::setCash,
                label = { Text("Cash received (${state.currency})") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                supportingText = { Text("Does not change how many shares you hold.") },
                modifier = Modifier.fillMaxWidth(),
            )
        } else {

        // The amount, entered either way round.
        //
        // "I spent 500 euro" is how people remember a purchase; "I bought
        // 0.00713 BTC" is what a ledger needs. Offering only the latter sends
        // the user to a calculator on every entry.
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            AmountMode.entries.forEachIndexed { index, mode ->
                SegmentedButton(
                    selected = state.amountMode == mode,
                    onClick = { viewModel.setAmountMode(mode) },
                    shape = SegmentedButtonDefaults.itemShape(index, AmountMode.entries.size),
                    icon = {},
                    label = {
                        Text(
                            when (mode) {
                                AmountMode.QUANTITY -> "Quantity"
                                AmountMode.TOTAL -> "Total spent"
                            },
                        )
                    },
                )
            }
        }

        when (state.amountMode) {
            AmountMode.QUANTITY -> OutlinedTextField(
                value = state.quantityInput,
                onValueChange = viewModel::setQuantity,
                label = { Text("Quantity${state.asset?.let { " (${it.symbol})" } ?: ""}") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                supportingText = { state.derivedLabel?.let { Text(it) } },
                modifier = Modifier.fillMaxWidth(),
            )
            AmountMode.TOTAL -> OutlinedTextField(
                value = state.totalInput,
                onValueChange = viewModel::setTotal,
                label = { Text("Total spent (${state.currency})") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                supportingText = { state.derivedLabel?.let { Text(it) } },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        OutlinedTextField(
            value = state.priceInput,
            onValueChange = viewModel::setPrice,
            label = { Text("Price per unit (${state.currency})") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
            supportingText = {
                state.marketPrice?.let {
                    Text("Market: ${MoneyFormat.price(it, state.currency)}")
                }
            },
            trailingIcon = {
                if (state.marketPrice != null) {
                    TextButton(onClick = viewModel::useMarketPrice) { Text("Use") }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )

        }

        // The currency the trade was priced in, which is not always the one the
        // portfolio is kept in: a share quoted in dollars can be paid for in euro.
        if (state.availableCurrencies.size > 1) {
            Column {
                Text("Priced in", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.size(6.dp))
                androidx.compose.foundation.layout.FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    state.availableCurrencies.forEach { code ->
                        FilterChip(
                            selected = state.currency == code,
                            onClick = { viewModel.setCurrency(code) },
                            label = { Text(code) },
                        )
                    }
                }
                state.convertedPreview?.let { converted ->
                    Spacer(Modifier.size(6.dp))
                    Text(
                        "About ${MoneyFormat.fiat(converted, state.baseCurrency)} at the rate on " +
                            "${formatDate(state.timestamp)}. If you know what you were actually " +
                            "charged, enter that in ${state.baseCurrency} instead: it includes your " +
                            "broker's FX spread, and this does not.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        OutlinedButton(onClick = { showDatePicker = true }, modifier = Modifier.fillMaxWidth()) {
            Text("Date: ${formatDate(state.timestamp)}")
        }

        TextButton(onClick = { showAdvanced = !showAdvanced }) {
            Text(if (showAdvanced) "Fewer options" else "Fee and note")
        }

        AnimatedVisibility(showAdvanced) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = state.feeInput,
                    onValueChange = viewModel::setFee,
                    label = { Text("Fee (${state.currency})") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = state.note,
                    onValueChange = viewModel::setNote,
                    label = { Text("Note") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        state.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }

        Button(
            onClick = viewModel::save,
            enabled = state.canSave,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (state.isEdit) "Save changes" else "Add position")
        }

        if (state.isEdit) {
            OutlinedButton(onClick = viewModel::delete, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text("Delete transaction")
            }
        }
    }

    if (showDatePicker) {
        val pickerState = rememberDatePickerState(initialSelectedDateMillis = state.timestamp)
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let(viewModel::setTimestamp)
                    showDatePicker = false
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("Cancel") }
            },
        ) { DatePicker(state = pickerState) }
    }
}

private val dateFormat = DateTimeFormatter.ofPattern("d MMM yyyy")

private fun formatDate(ts: Long): String =
    dateFormat.format(Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault()))
