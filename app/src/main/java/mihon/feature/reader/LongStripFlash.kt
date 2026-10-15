package mihon.feature.reader

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.tachiyomi.ui.reader.viewer.Viewer
import eu.kanade.tachiyomi.ui.reader.viewer.webtoon.WebtoonViewer
import mihon.core.metro.metroGraph
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.CheckboxItem
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.core.common.preference.Preference as PreferenceData

/**
 * The e-ink "flash on page change" is for page turns. A long strip has none:
 * its page number changes wherever two images meet on screen, so scrolling
 * back and forth over a seam blacked out the whole screen for ~100 ms on
 * every crossing — which a user took for a rendering bug. Long strips skip
 * the flash unless this is turned on.
 */
@Inject
@SingleIn(AppScope::class)
class LongStripFlashPreferences(preferenceStore: PreferenceStore) {
    val flashInLongStrip: PreferenceData<Boolean> =
        preferenceStore.getBoolean("pref_reader_flash_long_strip", false)
}

@ContributesTo(AppScope::class)
interface LongStripFlashGraph {
    val longStripFlashPreferences: LongStripFlashPreferences
}

private val Context.longStripFlashPreferences: LongStripFlashPreferences
    get() = metroGraph<LongStripFlashGraph>().longStripFlashPreferences

/** Whether a page change in [viewer] should flash. */
fun shouldFlashOnPageChange(context: Context, viewer: Viewer?): Boolean =
    viewer !is WebtoonViewer || context.longStripFlashPreferences.flashInLongStrip.get()

/** The checkbox for the reader's General tab, under the other flash options. */
@Composable
fun LongStripFlashCheckbox() {
    val context = LocalContext.current
    val preference = remember { context.longStripFlashPreferences.flashInLongStrip }
    CheckboxItem(
        label = stringResource(MR.strings.pref_flash_long_strip),
        pref = preference,
    )
}

/** The same option for Settings → Reader → E-Ink. */
@Composable
fun longStripFlashPreference(visible: Boolean): Preference.PreferenceItem.SwitchPreference {
    val context = LocalContext.current
    val preference = remember { context.longStripFlashPreferences.flashInLongStrip }
    return Preference.PreferenceItem.SwitchPreference(
        preference = preference,
        title = stringResource(MR.strings.pref_flash_long_strip),
        subtitle = stringResource(MR.strings.pref_flash_long_strip_summary),
        visible = visible,
    )
}
