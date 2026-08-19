package eu.kanade.presentation.reader.settings

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import eu.kanade.presentation.more.settings.widget.InfoWidget
import mihon.feature.translate.ReaderBarAction
import mihon.feature.translate.TranslationPreferences
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.SettingsChipRow
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Picks which buttons the reader's bottom bar shows.
 *
 * Lives on the General tab: it started out at the bottom of the Translation
 * tab, and the owner — who had used it days before — could not find it again.
 * The bar is a reader-wide thing, so this is where a person looks.
 *
 * Reached through the gear on that very bar, which is why the gear itself is
 * not on the list: hiding it would leave no way back here.
 */
@Composable
internal fun ColumnScope.ReaderBarActionsSetting() {
    val preferences = remember { Injekt.get<TranslationPreferences>() }
    val enabled by preferences.readerBarActions.collectAsState()
    SettingsChipRow(MR.strings.pref_reader_bar_actions) {
        ReaderBarAction.entries.forEach { action ->
            FilterChip(
                selected = action.id in enabled,
                onClick = {
                    preferences.readerBarActions.set(
                        if (action.id in enabled) enabled - action.id else enabled + action.id,
                    )
                },
                label = { Text(stringResource(action.label)) },
            )
        }
    }
    InfoWidget(text = stringResource(MR.strings.pref_reader_bar_actions_summary))
}
