package eu.kanade.presentation.more.onboarding

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.util.system.LocaleHelper
import mihon.feature.translate.TARGET_LANGUAGES
import mihon.feature.translate.TranslationSourceLanguage
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.SelectItem
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Onboarding step for the language pair used by the translate feature. Without
 * it the pair stays at its ja→en default and new users never find the setting.
 */
internal class TranslationStep : OnboardingStep {

    override val isComplete: Boolean = true

    private val readerPreferences: ReaderPreferences = Injekt.get()

    @Composable
    override fun Content() {
        val sourceLanguage by readerPreferences.autoTranslateSourceLanguage.collectAsState()
        val targetLanguage by readerPreferences.autoTranslateTargetLanguage.collectAsState()

        Column {
            Text(
                text = stringResource(MR.strings.onboarding_translation_info),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )

            SelectItem(
                label = stringResource(MR.strings.pref_auto_translate_source),
                options = TranslationSourceLanguage.entries
                    .map { LocaleHelper.getDisplayName(it.langCode) }
                    .toTypedArray(),
                selectedIndex = TranslationSourceLanguage.entries.indexOf(sourceLanguage).coerceAtLeast(0),
                onSelect = {
                    readerPreferences.autoTranslateSourceLanguage.set(TranslationSourceLanguage.entries[it])
                },
            )

            SelectItem(
                label = stringResource(MR.strings.pref_auto_translate_target),
                options = TARGET_LANGUAGES.map { LocaleHelper.getDisplayName(it) }.toTypedArray(),
                selectedIndex = TARGET_LANGUAGES.indexOf(targetLanguage).coerceAtLeast(0),
                onSelect = { readerPreferences.autoTranslateTargetLanguage.set(TARGET_LANGUAGES[it]) },
            )
        }
    }
}
