package eu.kanade.presentation.more

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.more.settings.widget.TextPreferenceWidget
import eu.kanade.tachiyomi.data.updater.AppUpdateChecker
import eu.kanade.tachiyomi.ui.more.NewUpdateScreen
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import logcat.LogPriority
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.release.interactor.GetApplicationRelease
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * The "check for updates" action, shared by More and About.
 *
 * This fork installs from GitHub releases rather than a store, so checking is
 * the only way a user learns a version exists — which is why the action is
 * offered in two places and its behavior lives in exactly one.
 */
@Stable
class AppUpdateCheck internal constructor(
    private val scope: CoroutineScope,
    private val context: Context,
    private val navigator: Navigator,
) {

    var isChecking by mutableStateOf(false)
        private set

    fun start() {
        if (isChecking) return
        isChecking = true
        scope.launch {
            val checker = AppUpdateChecker()
            withUIContext {
                try {
                    when (val result = withIOContext { checker.checkForUpdate(forceCheck = true) }) {
                        is GetApplicationRelease.Result.NewUpdate -> navigator.push(
                            NewUpdateScreen(
                                versionName = result.release.version,
                                changelogInfo = result.release.info,
                                releaseLink = result.release.releaseLink,
                                downloadLink = result.release.downloadLink,
                            ),
                        )
                        is GetApplicationRelease.Result.NoNewUpdate ->
                            context.toast(MR.strings.update_check_no_new_updates)
                        is GetApplicationRelease.Result.OsTooOld ->
                            context.toast(MR.strings.update_check_eol)
                    }
                } catch (e: Exception) {
                    context.toast(e.message)
                    logcat(LogPriority.ERROR, e)
                } finally {
                    isChecking = false
                }
            }
        }
    }
}

@Composable
fun rememberAppUpdateCheck(): AppUpdateCheck {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val navigator = LocalNavigator.currentOrThrow
    return remember(scope, context, navigator) { AppUpdateCheck(scope, context, navigator) }
}

/** The row itself, so the spinner behaves the same wherever the action appears. */
@Composable
fun CheckForUpdatesWidget(
    isChecking: Boolean,
    onClick: () -> Unit,
    subtitle: String? = null,
    icon: ImageVector? = null,
) {
    TextPreferenceWidget(
        title = stringResource(MR.strings.check_for_updates),
        subtitle = subtitle,
        icon = icon,
        widget = {
            AnimatedVisibility(visible = isChecking) {
                CircularProgressIndicator(
                    modifier = Modifier.size(28.dp),
                    strokeWidth = 3.dp,
                )
            }
        },
        onPreferenceClick = { if (!isChecking) onClick() },
    )
}
