package eu.kanade.presentation.more.settings.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.tachiyomi.util.system.LocaleHelper
import mihon.feature.translate.PageTranslator
import mihon.feature.translate.TARGET_LANGUAGES
import mihon.feature.translate.TextTranslator
import mihon.feature.translate.TranslateResultDisplay
import mihon.feature.translate.TranslationPreferences
import mihon.feature.translate.TranslationProvider
import mihon.feature.translate.TranslationSourceLanguage
import mihon.feature.translate.labelWithAutoHint
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Everything about turning text on a page into text the reader understands.
 *
 * Its own top-level entry rather than a group inside Reader: reading settings
 * are about how pages are displayed, this is about languages, services and
 * accounts, and mixing the two made both harder to find. The reader's own
 * Translation dialog tab stays — that one is for changing a language pair
 * mid-chapter without leaving the page.
 */
object SettingsTranslationScreen : SearchableSettings {

    @ReadOnlyComposable
    @Composable
    override fun getTitleRes() = MR.strings.pref_category_translation

    @Composable
    override fun getPreferences(): List<Preference> {
        val translationPreferences = remember { Injekt.get<TranslationPreferences>() }
        return listOf(
            getLanguageGroup(translationPreferences),
            getProviderGroup(translationPreferences),
            getDisplayGroup(translationPreferences),
        )
    }

    @Composable
    private fun getLanguageGroup(translationPreferences: TranslationPreferences): Preference.PreferenceGroup {
        return Preference.PreferenceGroup(
            title = stringResource(MR.strings.pref_category_translation_languages),
            preferenceItems = listOf(
                Preference.PreferenceItem.ListPreference(
                    preference = translationPreferences.autoTranslateSourceLanguage,
                    entries = TranslationSourceLanguage.entries
                        .associateWith { LocaleHelper.getDisplayName(it.langCode) },
                    title = stringResource(MR.strings.pref_auto_translate_source),
                ),
                Preference.PreferenceItem.ListPreference(
                    preference = translationPreferences.autoTranslateTargetLanguage,
                    entries = TARGET_LANGUAGES.associateWith { LocaleHelper.getDisplayName(it) },
                    title = stringResource(MR.strings.pref_auto_translate_target),
                ),
            ),
        )
    }

    @Composable
    private fun getProviderGroup(translationPreferences: TranslationPreferences): Preference.PreferenceGroup {
        val pageTranslator = remember { Injekt.get<PageTranslator>() }
        val lastAutoProvider by pageTranslator.lastAutoProvider.collectAsState()
        val deeplApiKey by translationPreferences.deeplApiKey.collectAsState()
        val deeplLimit by translationPreferences.deeplMonthlyCharLimit.collectAsState()
        val textTranslator = remember { Injekt.get<TextTranslator>() }
        val deeplUsed = remember(deeplApiKey, deeplLimit) { textTranslator.deeplUsedThisMonth() }
        val retryEngine by translationPreferences.ocrRetryEngine.collectAsState()
        val navigator = LocalNavigator.currentOrThrow
        var guide by remember { mutableStateOf<ApiKeyGuide?>(null) }
        guide?.let { ApiKeyGuideDialog(guide = it, onDismissRequest = { guide = null }) }

        return Preference.PreferenceGroup(
            title = stringResource(MR.strings.pref_category_translation_service),
            preferenceItems = listOf(
                Preference.PreferenceItem.ListPreference(
                    preference = translationPreferences.translationProvider,
                    // DeepL has no keyless tier, so it is only offered once a key is set
                    entries = TranslationProvider.entries
                        .filter { it != TranslationProvider.DEEPL || deeplApiKey.isNotBlank() }
                        .associateWith { it.labelWithAutoHint(lastAutoProvider) },
                    title = stringResource(MR.strings.pref_translation_provider),
                ),
                Preference.PreferenceItem.EditTextPreference(
                    preference = translationPreferences.deeplApiKey,
                    title = stringResource(MR.strings.pref_deepl_api_key),
                    subtitle = stringResource(MR.strings.pref_deepl_api_key_summary),
                    onHelpClick = { guide = ApiKeyGuide.DEEPL },
                ),
                Preference.PreferenceItem.SliderPreference(
                    // Stepped in thousands: character budgets are large numbers
                    value = deeplLimit / 1000,
                    valueRange = 0..1000,
                    steps = 39,
                    title = stringResource(MR.strings.pref_deepl_monthly_limit),
                    subtitle = if (deeplApiKey.isBlank()) {
                        stringResource(MR.strings.pref_deepl_monthly_limit_summary)
                    } else {
                        stringResource(MR.strings.pref_deepl_usage, deeplUsed, deeplLimit)
                    },
                    valueString = if (deeplLimit == 0) {
                        stringResource(MR.strings.pref_vision_no_limit)
                    } else {
                        "${deeplLimit / 1000}k"
                    },
                    onValueChanged = { translationPreferences.deeplMonthlyCharLimit.set(it * 1000) },
                ),
                Preference.PreferenceItem.TextPreference(
                    title = stringResource(MR.strings.pref_category_recognition),
                    subtitle = stringResource(MR.strings.pref_recognition_summary, retryEngine.displayName),
                    onClick = { navigator.push(SettingsRecognitionScreen) },
                ),
            ),
        )
    }

    @Composable
    private fun getDisplayGroup(translationPreferences: TranslationPreferences): Preference.PreferenceGroup {
        val resultDisplay by translationPreferences.translateResultDisplay.collectAsState()
        return Preference.PreferenceGroup(
            title = stringResource(MR.strings.pref_category_display),
            preferenceItems = listOfNotNull(
                Preference.PreferenceItem.ListPreference(
                    preference = translationPreferences.translateResultDisplay,
                    entries = TranslateResultDisplay.entries.associateWith {
                        stringResource(
                            when (it) {
                                TranslateResultDisplay.OVERLAY -> MR.strings.translate_display_overlay
                                TranslateResultDisplay.PANEL -> MR.strings.translate_display_panel
                            },
                        )
                    },
                    title = stringResource(MR.strings.pref_translate_result_display),
                ),
                // Only overlay mode can show an untranslated block, so the
                // option is meaningless in panel mode
                Preference.PreferenceItem.SwitchPreference(
                    preference = translationPreferences.translateShowOriginalFirst,
                    title = stringResource(MR.strings.pref_translate_original_first),
                    subtitle = stringResource(MR.strings.pref_translate_original_first_summary),
                ).takeIf { resultDisplay == TranslateResultDisplay.OVERLAY },
            ),
        )
    }
}
