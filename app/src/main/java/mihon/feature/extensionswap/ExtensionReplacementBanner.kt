package mihon.feature.extensionswap

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

/** One line in the Extensions list: the banner shows only when something can be replaced. */
fun LazyListScope.extensionReplacementItem() {
    item(key = "mangalens-extension-replacement") { ExtensionReplacementBanner() }
}

@Composable
private fun ExtensionReplacementBanner() {
    val context = LocalContext.current
    val replacer = remember { context.extensionReplacer }
    val candidates by remember { replacer.candidates }.collectAsStateWithLifecycle(emptyList())
    val progress by replacer.progress.collectAsStateWithLifecycle()
    var choosing by remember { mutableStateOf(false) }

    if (choosing) {
        ReplaceDialog(
            candidates = candidates,
            onDismiss = { choosing = false },
            onConfirm = { chosen ->
                choosing = false
                replacer.replace(chosen)
            },
        )
    }

    val working = progress as? ExtensionReplacer.Progress.Working
    val finished = progress as? ExtensionReplacer.Progress.Finished
    if (candidates.isEmpty() && working == null && finished == null) return

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when {
                working != null -> {
                    Text(text = stepText(working), style = MaterialTheme.typography.bodyMedium)
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = replacer::cancel) { Text(stringResource(MR.strings.action_cancel)) }
                    }
                }
                finished != null -> {
                    if (finished.replaced.isNotEmpty()) {
                        Text(
                            text = stringResource(MR.strings.ext_swap_done, finished.replaced.joinToString()),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    finished.failed.forEach { (name, failure) ->
                        Text(text = failureText(name, failure), style = MaterialTheme.typography.bodyMedium)
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = replacer::dismiss) { Text(stringResource(MR.strings.action_ok)) }
                    }
                }
                else -> {
                    Text(
                        text = stringResource(
                            MR.strings.ext_swap_banner,
                            candidates.joinToString {
                                it.installed.name
                            },
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { choosing = true }) { Text(stringResource(MR.strings.ext_swap_action)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReplaceDialog(
    candidates: List<ExtensionReplacement>,
    onDismiss: () -> Unit,
    onConfirm: (List<ExtensionReplacement>) -> Unit,
) {
    // Ones the library reads from are what matters; the rest are opt-in
    val chosen = remember(candidates) { candidates.map { it.inLibrary > 0 }.toMutableStateList() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(MR.strings.ext_swap_dialog_title)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(MR.strings.ext_swap_dialog_text), style = MaterialTheme.typography.bodyMedium)
                candidates.forEachIndexed { index, item ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { chosen[index] = !chosen[index] }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = chosen[index], onCheckedChange = { chosen[index] = it })
                        Column {
                            Text(item.installed.name, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                text = itemDetail(item),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = chosen.any { it },
                onClick = { onConfirm(candidates.filterIndexed { index, _ -> chosen[index] }) },
            ) { Text(stringResource(MR.strings.ext_swap_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(MR.strings.action_cancel)) }
        },
    )
}

@Composable
private fun itemDetail(item: ExtensionReplacement): String {
    val versions = "${item.installed.versionName} → ${item.replacement.versionName}"
    return if (item.inLibrary > 0) {
        stringResource(MR.strings.ext_swap_item_in_library, versions, item.replacement.store.name, item.inLibrary)
    } else {
        stringResource(MR.strings.ext_swap_item_unused, versions, item.replacement.store.name)
    }
}

@Composable
private fun stepText(working: ExtensionReplacer.Progress.Working): String = stringResource(
    when (working.step) {
        ExtensionReplacer.Step.CHECKING -> MR.strings.ext_swap_checking
        ExtensionReplacer.Step.UNINSTALLING -> MR.strings.ext_swap_uninstalling
        ExtensionReplacer.Step.INSTALLING -> MR.strings.ext_swap_installing
    },
    working.name,
    working.index,
    working.total,
)

@Composable
private fun failureText(name: String, failure: ExtensionReplacer.Failure): String = stringResource(
    when (failure) {
        ExtensionReplacer.Failure.UNREACHABLE -> MR.strings.ext_swap_failed_unreachable
        ExtensionReplacer.Failure.KEPT -> MR.strings.ext_swap_failed_kept
        ExtensionReplacer.Failure.INSTALL_FAILED -> MR.strings.ext_swap_failed_install
    },
    name,
)
