package eu.kanade.presentation.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * Warns before a link leaves for Mihon's documentation.
 *
 * The fork inherited a dozen help links pointing at upstream's docs. They are
 * accurate — the features they describe are the same ones — so deleting them
 * would remove real help and leave the buttons pointing nowhere. But following
 * one silently hands the reader to another project's site with no hint that it
 * happened, and nothing there mentions translation, which is the reason this
 * fork exists.
 *
 * This wraps [LocalUriHandler] rather than each of the eleven call sites, so a
 * link added later is covered without anyone remembering to. Only `/docs` URLs
 * are intercepted: the credit link in About says "Mihon" on it already, and a
 * dialog repeating that would be noise.
 */
@Composable
fun UpstreamDocsNotice(content: @Composable () -> Unit) {
    val delegate = LocalUriHandler.current
    var pending by remember { mutableStateOf<String?>(null) }

    val handler = remember(delegate) {
        object : UriHandler {
            override fun openUri(uri: String) {
                if (uri.startsWith(UPSTREAM_DOCS)) pending = uri else delegate.openUri(uri)
            }
        }
    }

    CompositionLocalProvider(LocalUriHandler provides handler, content = content)

    pending?.let { uri ->
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text(text = stringResource(MR.strings.upstream_docs_title)) },
            text = { Text(text = stringResource(MR.strings.upstream_docs_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        pending = null
                        // The delegate, not the wrapper, or this loops
                        delegate.openUri(uri)
                    },
                ) {
                    Text(text = stringResource(MR.strings.action_open_in_browser))
                }
            },
            dismissButton = {
                TextButton(onClick = { pending = null }) {
                    Text(text = stringResource(MR.strings.action_cancel))
                }
            },
        )
    }
}

private const val UPSTREAM_DOCS = "https://mihon.app/docs"
