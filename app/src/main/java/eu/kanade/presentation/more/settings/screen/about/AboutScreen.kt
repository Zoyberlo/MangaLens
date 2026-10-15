package eu.kanade.presentation.more.settings.screen.about

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.more.CheckForUpdatesWidget
import eu.kanade.presentation.more.LogoHeader
import eu.kanade.presentation.more.rememberAppUpdateCheck
import eu.kanade.presentation.more.settings.widget.TextPreferenceWidget
import eu.kanade.presentation.util.LocalBackPress
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.data.updater.RELEASE_URL
import eu.kanade.tachiyomi.ui.more.NewUpdateScreen
import eu.kanade.tachiyomi.util.lang.toDateTimestampString
import eu.kanade.tachiyomi.util.system.copyToClipboard
import eu.kanade.tachiyomi.util.system.isFossBuildType
import eu.kanade.tachiyomi.util.system.isNightlyBuildType
import eu.kanade.tachiyomi.util.system.toast
import eu.kanade.tachiyomi.util.system.updaterEnabled
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import logcat.LogPriority
import mihon.app.di.appGraph
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Public
import mihon.icons.simpleicons.Discord
import mihon.icons.simpleicons.Facebook
import mihon.icons.simpleicons.Github
import mihon.icons.simpleicons.Reddit
import mihon.icons.simpleicons.SimpleIcons
import mihon.icons.simpleicons.X
import tachiyomi.core.common.Constants
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.release.interactor.GetApplicationRelease
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.LinkIcon
import tachiyomi.presentation.core.components.ScrollbarLazyColumn
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlin.time.Instant

private const val FORK_REPO_URL = "https://github.com/Zoyberlo/MangaLens"

object AboutScreen : Screen() {

    @Composable
    override fun Content() {
        val context = LocalContext.current
        val uriHandler = LocalUriHandler.current
        val handleBack = LocalBackPress.current
        val navigator = LocalNavigator.currentOrThrow
        val updateCheck = rememberAppUpdateCheck()
        val crashLogUtil = remember { context.appGraph.crashLogUtil }

        Scaffold(
            topBar = { scrollBehavior ->
                AppBar(
                    title = stringResource(MR.strings.pref_category_about),
                    navigateUp = if (handleBack != null) handleBack::invoke else null,
                    scrollBehavior = scrollBehavior,
                )
            },
        ) { contentPadding ->
            ScrollbarLazyColumn(
                contentPadding = contentPadding,
            ) {
                item {
                    LogoHeader(
                        iconPadding = PaddingValues(vertical = 56.dp),
                    )
                }

                item {
                    TextPreferenceWidget(
                        title = stringResource(MR.strings.version),
                        subtitle = getVersionName(withBuildDate = true),
                        onPreferenceClick = {
                            val deviceInfo = crashLogUtil.getDebugInfo()
                            context.copyToClipboard("Debug information", deviceInfo)
                        },
                    )
                }

                if (updaterEnabled) {
                    item {
                        CheckForUpdatesWidget(
                            isChecking = updateCheck.isChecking,
                            onClick = updateCheck::start,
                        )
                    }
                }

                item {
                    TextPreferenceWidget(
                        title = stringResource(MR.strings.whats_new),
                        subtitle = stringResource(MR.strings.whats_new_summary),
                        onPreferenceClick = { navigator.push(WhatsNewScreen()) },
                    )
                }

                item {
                    TextPreferenceWidget(
                        title = stringResource(MR.strings.licenses),
                        onPreferenceClick = { navigator.push(OpenSourceLicensesScreen()) },
                    )
                }

                // States what this fork is and where it came from, as Apache
                // 2.0 section 4b requires of distributed modifications
                item {
                    TextPreferenceWidget(
                        title = stringResource(MR.strings.about_fork_notice_title),
                        subtitle = stringResource(MR.strings.about_fork_notice_summary),
                        onPreferenceClick = { uriHandler.openUri("$FORK_REPO_URL/blob/main/NOTICE") },
                    )
                }

                // This fork has no site, no accounts and no analytics, so the
                // upstream social row and privacy policy are replaced by the
                // two links that actually belong to it.
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        LinkIcon(
                            label = "GitHub",
                            icon = SimpleIcons.Github,
                            url = FORK_REPO_URL,
                        )
                        LinkIcon(
                            label = stringResource(MR.strings.about_upstream_project),
                            icon = MaterialSymbols.Rounded.Public,
                            url = "https://mihon.app",
                        )
                    }
                }
            }
        }
    }

    fun getVersionName(withBuildDate: Boolean): String {
        return when {
            BuildConfig.DEBUG -> {
                "Debug ${BuildConfig.COMMIT_SHA}".let {
                    if (withBuildDate) {
                        "$it (${getFormattedBuildTime()})"
                    } else {
                        it
                    }
                }
            }
            isNightlyBuildType -> {
                "Nightly r${BuildConfig.COMMIT_COUNT}".let {
                    if (withBuildDate) {
                        "$it (${BuildConfig.COMMIT_SHA}, ${getFormattedBuildTime()})"
                    } else {
                        "$it (${BuildConfig.COMMIT_SHA})"
                    }
                }
            }
            else -> {
                val channel = if (isFossBuildType) "FOSS" else "Stable"
                "$channel v${BuildConfig.VERSION_NAME}".let {
                    if (withBuildDate) {
                        "$it (${getFormattedBuildTime()})"
                    } else {
                        it
                    }
                }
            }
        }
    }

    internal fun getFormattedBuildTime(): String {
        return try {
            Instant.parse(BuildConfig.BUILD_TIME)
                .toLocalDateTime(TimeZone.currentSystemDefault())
                .toDateTimestampString(
                    UiPreferences.dateFormat(
                        Injekt.get<Context>().appGraph.uiPreferences.dateFormat.get(),
                    ),
                )
        } catch (_: Exception) {
            BuildConfig.BUILD_TIME
        }
    }
}
