package eu.kanade.presentation.reader.settings

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import eu.kanade.presentation.more.settings.widget.InfoWidget
import eu.kanade.tachiyomi.ui.reader.setting.ReaderSettingsViewModel
import eu.kanade.tachiyomi.util.system.LocaleHelper
import mihon.feature.translate.PageTranslator
import mihon.feature.translate.TARGET_LANGUAGES
import mihon.feature.translate.TranslateResultDisplay
import mihon.feature.translate.TranslationProvider
import mihon.feature.translate.TranslationSourceLanguage
import mihon.feature.translate.labelWithAutoHint
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.CheckboxItem
import tachiyomi.presentation.core.components.SelectItem
import tachiyomi.presentation.core.components.SettingsChipRow
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

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
    val deeplApiKey by viewModel.preferences.deeplApiKey.collectAsState()
    val pageTranslator = remember { Injekt.get<PageTranslator>() }
    val lastAutoProvider by pageTranslator.lastAutoProvider.collectAsState()
    SettingsChipRow(MR.strings.pref_translation_provider) {
        TranslationProvider.entries.map {
            // DeepL has no keyless tier, so it stays disabled until a key is set
            val enabled = it != TranslationProvider.DEEPL || deeplApiKey.isNotBlank()
            FilterChip(
                selected = it == provider,
                enabled = enabled,
                onClick = { viewModel.preferences.translationProvider.set(it) },
                label = { Text(it.labelWithAutoHint(lastAutoProvider)) },
            )
        }
    }
    val resultDisplay by viewModel.preferences.translateResultDisplay.collectAsState()
    SettingsChipRow(MR.strings.pref_translate_result_display) {
        TranslateResultDisplay.entries.map {
            FilterChip(
                selected = it == resultDisplay,
                onClick = { viewModel.preferences.translateResultDisplay.set(it) },
                label = {
                    Text(
                        stringResource(
                            when (it) {
                                TranslateResultDisplay.OVERLAY -> MR.strings.translate_display_overlay
                                TranslateResultDisplay.PANEL -> MR.strings.translate_display_panel
                            },
                        ),
                    )
                },
            )
        }
    }

    if (resultDisplay == TranslateResultDisplay.OVERLAY) {
        CheckboxItem(
            label = stringResource(MR.strings.pref_translate_original_first),
            pref = viewModel.preferences.translateShowOriginalFirst,
        )
    }

    if (deeplApiKey.isBlank()) {
        InfoWidget(text = stringResource(MR.strings.pref_deepl_api_key_summary))
    }
}
