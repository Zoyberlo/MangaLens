package eu.kanade.presentation.more.settings.screen.about

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.manga.components.MarkdownRender
import eu.kanade.presentation.util.Screen
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.LoadingScreen

/**
 * The release notes, read from a file inside the APK.
 *
 * Bundled rather than fetched so it works on a plane and cannot show notes for
 * a version other than the one running. The alternative — the link this
 * replaced — dropped the reader into a browser showing an auto-generated list
 * of commit subjects, which describes the work rather than the change.
 */
class WhatsNewScreen : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current

        val changelog by produceState<String?>(initialValue = null) {
            value = withIOContext {
                runCatching {
                    context.assets.open(CHANGELOG_ASSET).bufferedReader().use { it.readText() }
                }.getOrNull().orEmpty()
            }
        }

        Scaffold(
            topBar = { scrollBehavior ->
                AppBar(
                    title = stringResource(MR.strings.whats_new),
                    navigateUp = navigator::pop,
                    scrollBehavior = scrollBehavior,
                )
            },
        ) { contentPadding ->
            when (val content = changelog) {
                null -> LoadingScreen(Modifier.padding(contentPadding))
                else -> MarkdownRender(
                    content = content,
                    modifier = Modifier
                        .padding(contentPadding)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = MaterialTheme.padding.medium),
                    // Nothing in the changelog links to an image, and allowing
                    // them would put a network fetch behind an offline screen
                    loadImages = false,
                )
            }
        }
    }
}

internal const val CHANGELOG_ASSET = "CHANGELOG.md"
