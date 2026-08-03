package mihon.feature.support

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Coffee
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.more.settings.widget.TextPreferenceWidget
import eu.kanade.presentation.util.Screen
import tachiyomi.core.common.Constants
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.icons.CustomIcons
import tachiyomi.presentation.core.icons.OpenCollective
import tachiyomi.presentation.core.icons.Patreon

/**
 * Fork version of the screen: MangaLens takes no donations, so this credits
 * Mihon and points donations at them, and offers the fork's own repository
 * and issue tracker instead.
 */
class SupportUsScreen : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val uriHandler = LocalUriHandler.current

        Scaffold(
            topBar = {
                AppBar(
                    title = stringResource(MR.strings.label_support_us),
                    navigateUp = navigator::pop,
                )
            },
        ) { paddingValues ->
            Column(
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
                modifier = Modifier.verticalScroll(rememberScrollState()).padding(
                    remember(paddingValues) {
                        object : PaddingValues {
                            override fun calculateLeftPadding(layoutDirection: LayoutDirection): Dp {
                                return paddingValues.calculateLeftPadding(layoutDirection) +
                                    MaterialTheme.padding.medium
                            }

                            override fun calculateTopPadding(): Dp {
                                return 0.dp
                            }

                            override fun calculateRightPadding(layoutDirection: LayoutDirection): Dp {
                                return paddingValues.calculateRightPadding(layoutDirection) +
                                    MaterialTheme.padding.medium
                            }

                            override fun calculateBottomPadding(): Dp {
                                return 0.dp
                            }
                        }
                    },
                ),
            ) {
                Spacer(modifier = Modifier.height(paddingValues.calculateTopPadding()))

                Text(
                    text = stringResource(MR.strings.supportUsScreen_forkIntro),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = MaterialTheme.padding.medium),
                )

                SupportItem(
                    icon = Icons.Outlined.Code,
                    title = stringResource(MR.strings.supportUsScreen_sourceCode),
                    onClick = { uriHandler.openUri(FORK_REPO_URL) },
                )
                SupportItem(
                    icon = Icons.Outlined.BugReport,
                    title = stringResource(MR.strings.supportUsScreen_reportIssue),
                    onClick = { uriHandler.openUri("$FORK_REPO_URL/issues") },
                )

                // Shown only once a tip link is configured, so the screen never
                // carries a dead button
                if (TIP_URL.isNotBlank()) {
                    SupportItem(
                        icon = Icons.Outlined.Coffee,
                        title = stringResource(MR.strings.supportUsScreen_tip),
                        onClick = { uriHandler.openUri(TIP_URL) },
                    )
                }

                Text(
                    text = stringResource(MR.strings.supportUsScreen_upstreamCredit),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = MaterialTheme.padding.medium),
                )

                SupportItem(
                    icon = CustomIcons.Patreon,
                    title = stringResource(MR.strings.supportUsScreen_donationPlatform_patreon),
                    onClick = { uriHandler.openUri(Constants.URL_DONATE_PATREON) },
                )
                SupportItem(
                    icon = CustomIcons.OpenCollective,
                    title = stringResource(MR.strings.supportUsScreen_donationPlatform_opencollective),
                    onClick = { uriHandler.openUri(Constants.URL_DONATE_OPENCOLLECTIVE) },
                )

                Spacer(modifier = Modifier.height(paddingValues.calculateBottomPadding()))
            }
        }
    }

    @Composable
    private fun SupportItem(
        icon: ImageVector,
        title: String,
        onClick: () -> Unit,
    ) {
        Card {
            TextPreferenceWidget(
                title = title,
                icon = icon,
                widget = {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                        contentDescription = null,
                    )
                },
                onPreferenceClick = onClick,
            )
        }
    }
}

private const val FORK_REPO_URL = "https://github.com/Zoyberlo/MangaLens"

/**
 * Tip link for the fork's own work (monobank jar, Ko-fi, Patreon…). Leave
 * blank to hide the entry entirely.
 */
private const val TIP_URL = ""
