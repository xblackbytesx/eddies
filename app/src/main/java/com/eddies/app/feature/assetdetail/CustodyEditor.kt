package com.eddies.app.feature.assetdetail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Savings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.eddies.app.data.db.entity.CustodyType

/** An icon per kind, so a location is recognisable at a glance in a list. */
fun CustodyType.icon(): ImageVector = when (this) {
    CustodyType.HARDWARE_WALLET -> Icons.Default.Memory
    CustodyType.EXCHANGE -> Icons.Default.AccountBalance
    CustodyType.SOFTWARE_WALLET -> Icons.Default.Smartphone
    CustodyType.COLD_STORAGE -> Icons.Default.AcUnit
    CustodyType.DEFI -> Icons.Default.Savings
    CustodyType.OTHER -> Icons.Default.MoreHoriz
}

/**
 * Where a coin is kept.
 *
 * Type first, then a name. The type is a closed set so it can carry an icon and
 * group; the name is free text because no curated list of exchanges would ever
 * match a real setup, and maintaining one forever is a cost with no payoff.
 *
 * Names already used elsewhere appear as chips. That is what stops the set
 * fragmenting into "Kraken", "kraken" and "Kraken exchange".
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CustodyDialog(
    initialType: CustodyType?,
    initialLabel: String,
    initialNote: String,
    suggestions: List<String>,
    onDismiss: () -> Unit,
    onSave: (CustodyType, String, String) -> Unit,
    onClear: () -> Unit,
) {
    var type by rememberSaveable { mutableStateOf(initialType ?: CustodyType.HARDWARE_WALLET) }
    var label by rememberSaveable { mutableStateOf(initialLabel) }
    var note by rememberSaveable { mutableStateOf(initialNote) }
    val hadValue = remember { initialLabel.isNotBlank() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Stored at") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Kind", style = androidx.compose.material3.MaterialTheme.typography.labelMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    CustodyType.entries.forEach { option ->
                        FilterChip(
                            selected = option == type,
                            onClick = { type = option },
                            label = { Text(option.label) },
                        )
                    }
                }

                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("Name") },
                    placeholder = { Text(placeholderFor(type)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                val unused = suggestions.filter { !it.equals(label, ignoreCase = true) }
                if (unused.isNotEmpty()) {
                    Text(
                        "Used elsewhere",
                        style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        unused.forEach { suggestion ->
                            AssistChip(onClick = { label = suggestion }, label = { Text(suggestion) })
                        }
                    }
                }

                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("Note (optional)") },
                    placeholder = { Text("Which drawer, which seed backup, a split") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.size(2.dp))
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(type, label, note) },
                enabled = label.isNotBlank(),
            ) { Text("Save") }
        },
        dismissButton = {
            if (hadValue) {
                TextButton(onClick = onClear) { Text("Remove") }
            } else {
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

private fun placeholderFor(type: CustodyType): String = when (type) {
    CustodyType.HARDWARE_WALLET -> "Ledger Nano X"
    CustodyType.EXCHANGE -> "Kraken"
    CustodyType.SOFTWARE_WALLET -> "Sparrow"
    CustodyType.COLD_STORAGE -> "Paper backup, safe"
    CustodyType.DEFI -> "Lido"
    CustodyType.OTHER -> "Where is it"
}
