package com.eddies.app.feature.merge

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.eddies.app.core.ui.Section
import com.eddies.app.data.repo.MergePreview
import com.eddies.app.domain.Asset
import com.eddies.app.domain.AssetIds

/**
 * Repairs one instrument recorded as two holdings.
 *
 * The whole screen is a preview. Merging rewrites which asset a user's
 * transactions belong to and there is no undo, so every row that would move is
 * counted and named before the button is offered, and the ids are shown in full:
 * two rows reading "IWDA.L" with the same name are indistinguishable otherwise,
 * and the id is the only thing that tells them apart.
 */
@Composable
fun MergeDuplicatesScreen(
    modifier: Modifier = Modifier,
    viewModel: MergeDuplicatesViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(
        modifier = modifier.fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp).padding(bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Spacer(Modifier.size(4.dp))

        if (state.scanning || state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())

        state.message?.let { msg ->
            Text(msg, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        }

        if (!state.scanning && state.duplicates.isEmpty()) {
            Section("Nothing to merge") {
                Row(
                    Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.size(12.dp))
                    Text(
                        "Every holding appears once. Nothing was changed.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }

        for (preview in state.duplicates) {
            DuplicateCard(preview, onMerge = { viewModel.ask(preview) })
        }
    }

    state.confirming?.let { preview ->
        AlertDialog(
            onDismissRequest = viewModel::dismiss,
            title = { Text("Merge ${preview.group.keep.symbol}?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "${preview.totalTransactions} " +
                            (if (preview.totalTransactions == 1) "transaction moves" else "transactions move") +
                            " onto ${preview.group.keep.id}, and " +
                            (if (preview.group.merge.size == 1) "the other entry is" else "the other entries are") +
                            " removed.",
                    )
                    Text(
                        "Nothing is deleted from your ledger. Cached prices for the " +
                            "removed entries are cleared and fetched again.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "There is no undo. Export a backup first if you want one.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = { TextButton(onClick = viewModel::confirm) { Text("Merge") } },
            dismissButton = { TextButton(onClick = viewModel::dismiss) { Text("Cancel") } },
        )
    }
}

@Composable
private fun DuplicateCard(preview: MergePreview, onMerge: () -> Unit) {
    val group = preview.group
    Section(
        title = "${group.keep.symbol}  ${group.keep.name}",
        subtitle = "${group.all.size} entries for what looks like one holding.",
    ) {
        AssetRow(group.keep, count = null, keep = true, isin = preview.isins[group.keep.id])
        for (asset in group.merge) {
            HorizontalDivider()
            AssetRow(
                asset,
                count = preview.transactionCounts[asset.id] ?: 0,
                keep = false,
                isin = preview.isins[asset.id],
            )
        }
        HorizontalDivider()
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                buildString {
                    append(preview.totalTransactions)
                    append(if (preview.totalTransactions == 1) " transaction moves" else " transactions move")
                    if (preview.custodyMoves > 0) append(", plus where it is stored")
                    if (preview.splitMoves > 0) append(", plus ${preview.splitMoves} split events")
                    append(".")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onMerge, modifier = Modifier.fillMaxWidth()) {
                Text("Merge into ${group.keep.symbol}")
            }
        }
    }
}

@Composable
private fun AssetRow(asset: Asset, count: Int?, keep: Boolean, isin: String?) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                if (keep) "Kept" else "Merged away",
                style = MaterialTheme.typography.labelMedium,
                color = if (keep) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                asset.id,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
            // The ISIN is the one thing that proves these are the same fund.
            // Two iShares World ETFs can look alike everywhere else.
            if (isin != null) {
                Text(
                    "ISIN $isin",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Text(
                asset.name,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            AssetIds.exchangeOf(asset.id)?.let { venue ->
                Text(
                    venue,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (count != null && count > 0) {
            Text(
                "$count",
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}
