package eu.kanade.presentation.reader.settings

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import eu.kanade.tachiyomi.ui.reader.setting.ReaderSettingsViewModel
import eu.kanade.tachiyomi.util.system.LocaleHelper
import mihon.feature.translate.TARGET_LANGUAGES
import mihon.feature.translate.TranslationProvider
import mihon.feature.translate.TranslationSourceLanguage
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.SelectItem
import tachiyomi.presentation.core.components.SettingsChipRow
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState

@Composable
internal fun ColumnScope.TranslationSettingsPage(viewModel: ReaderSettingsViewModel) {
    val sourceLanguage by viewModel.preferences.autoTranslateSourceLanguage.collectAsState()
    SelectItem(
        label = stringResource(MR.strings.pref_auto_translate_source),
        options = TranslationSourceLanguage.entries
            .map { LocaleHelper.getDisplayName(it.langCode) }
            .toTypedArray(),
        selectedIndex = TranslationSourceLanguage.entries.indexOf(sourceLanguage).coerceAtLeast(0),
        onSelect = { viewModel.preferences.autoTranslateSourceLanguage.set(TranslationSourceLanguage.entries[it]) },
    )

    val targetLanguage by viewModel.preferences.autoTranslateTargetLanguage.collectAsState()
    SelectItem(
        label = stringResource(MR.strings.pref_auto_translate_target),
        options = TARGET_LANGUAGES.map { LocaleHelper.getDisplayName(it) }.toTypedArray(),
        selectedIndex = TARGET_LANGUAGES.indexOf(targetLanguage).coerceAtLeast(0),
        onSelect = { viewModel.preferences.autoTranslateTargetLanguage.set(TARGET_LANGUAGES[it]) },
    )

    val provider by viewModel.preferences.translationProvider.collectAsState()
    SettingsChipRow(MR.strings.pref_translation_provider) {
        TranslationProvider.entries.map {
            FilterChip(
                selected = it == provider,
                onClick = { viewModel.preferences.translationProvider.set(it) },
                label = { Text(it.displayName) },
            )
        }
    }
}
