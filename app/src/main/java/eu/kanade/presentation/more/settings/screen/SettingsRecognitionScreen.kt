package eu.kanade.presentation.more.settings.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.util.system.LocaleHelper
import kotlinx.coroutines.launch
import mihon.feature.translate.CloudTextRecognizer
import mihon.feature.translate.OcrEngine
import mihon.feature.translate.TranslationSourceLanguage
import mihon.feature.translate.ocrOverrideFor
import mihon.feature.translate.withOcrOverride
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import tachiyomi.core.common.preference.Preference as PreferenceData

/**
 * Which engine reads text off the page, and with whose account. Deliberately
 * a screen of its own: the defaults are what almost everyone should use, and
 * every alternative here spends the user's own money.
 */
object SettingsRecognitionScreen : SearchableSettings {

    @ReadOnlyComposable
    @Composable
    override fun getTitleRes() = MR.strings.pref_category_recognition

    @Composable
    override fun getPreferences(): List<Preference> {
        val readerPreferences = remember { Injekt.get<ReaderPreferences>() }
        var guide by remember { mutableStateOf<ApiKeyGuide?>(null) }
        guide?.let { ApiKeyGuideDialog(guide = it, onDismissRequest = { guide = null }) }
        val showGuide: (ApiKeyGuide) -> Unit = { guide = it }

        val scope = rememberCoroutineScope()
        val recognizer = remember { Injekt.get<CloudTextRecognizer>() }
        var report by remember { mutableStateOf<String?>(null) }
        var busy by remember { mutableStateOf(false) }
        var models by remember { mutableStateOf<List<String>?>(null) }

        val testingLabel = stringResource(MR.strings.pref_engine_testing)
        val okLabel = stringResource(MR.strings.pref_engine_test_ok)

        report?.let { message ->
            EngineReportDialog(message = message, onDismissRequest = { report = null })
        }
        models?.let { list ->
            GeminiModelDialog(
                models = list,
                onPick = {
                    readerPreferences.geminiModel.set(it)
                    models = null
                },
                onDismissRequest = { models = null },
            )
        }

        val testEngine: (OcrEngine) -> Unit = { engine ->
            if (!busy) {
                busy = true
                report = testingLabel
                scope.launch {
                    val error = recognizer.testEngine(engine)
                    report = error ?: okLabel
                    busy = false
                }
            }
        }
        val fetchModels: () -> Unit = {
            if (!busy) {
                busy = true
                report = testingLabel
                scope.launch {
                    recognizer.listGeminiModels()
                        .onSuccess {
                            report = null
                            models = it
                        }
                        .onFailure { report = it.message ?: okLabel }
                    busy = false
                }
            }
        }

        return listOf(
            Preference.PreferenceItem.InfoPreference(
                stringResource(MR.strings.pref_recognition_info),
            ),
            getEngineGroup(readerPreferences),
            getPerLanguageGroup(
                readerPreferences.ocrEngineOverrides,
                MR.strings.pref_category_recognition_per_language,
                // The automatic pass needs block geometry, which Gemini has none of
                OcrEngine.entries.filter { it.canDetectLayout },
            ),
            getPerLanguageGroup(
                readerPreferences.ocrRetryEngineOverrides,
                MR.strings.pref_category_recognition_retry_per_language,
                OcrEngine.entries,
            ),
            getVisionGroup(readerPreferences, showGuide, testEngine),
            getAzureGroup(readerPreferences, showGuide, testEngine),
            getGeminiGroup(readerPreferences, showGuide, testEngine, fetchModels),
        )
    }

    /** "Test key" row: runs one real request and reports exactly what came back. */
    @Composable
    private fun testItem(
        engine: OcrEngine,
        onTest: (OcrEngine) -> Unit,
    ): Preference.PreferenceItem.TextPreference {
        val recognizer = remember { Injekt.get<CloudTextRecognizer>() }
        return Preference.PreferenceItem.TextPreference(
            title = stringResource(MR.strings.pref_engine_test),
            subtitle = recognizer.lastError(engine) ?: stringResource(MR.strings.pref_engine_test_summary),
            onClick = { onTest(engine) },
        )
    }

    @Composable
    private fun getEngineGroup(readerPreferences: ReaderPreferences): Preference.PreferenceGroup {
        return Preference.PreferenceGroup(
            title = stringResource(MR.strings.pref_category_recognition_engines),
            preferenceItems = listOf(
                Preference.PreferenceItem.ListPreference(
                    preference = readerPreferences.ocrEngine,
                    entries = OcrEngine.entries
                        .filter { it.canDetectLayout }
                        .associateWith { it.displayName },
                    title = stringResource(MR.strings.pref_ocr_engine),
                    subtitleProvider = { value, entries ->
                        "${entries[value]}\n${stringResource(MR.strings.pref_ocr_engine_summary)}"
                    },
                ),
                Preference.PreferenceItem.ListPreference(
                    preference = readerPreferences.ocrRetryEngine,
                    entries = OcrEngine.entries.associateWith { it.displayName },
                    title = stringResource(MR.strings.pref_ocr_retry_engine),
                    subtitleProvider = { value, entries ->
                        "${entries[value]}\n${stringResource(MR.strings.pref_ocr_retry_engine_summary)}"
                    },
                ),
            ),
        )
    }

    /**
     * One dropdown per source language, backed by the `LANGUAGE=ENGINE` set.
     * Scripts differ enough that the engine that wins for Japanese rarely wins
     * for stylised English lettering.
     */
    @Composable
    private fun getPerLanguageGroup(
        overrides: PreferenceData<Set<String>>,
        titleRes: dev.icerock.moko.resources.StringResource,
        engines: List<OcrEngine>,
    ): Preference.PreferenceGroup {
        val current by overrides.collectAsState()
        val followsGlobal = stringResource(MR.strings.pref_ocr_engine_default)
        val entries = buildMap {
            put("", followsGlobal)
            engines.forEach { put(it.name, it.displayName) }
        }
        return Preference.PreferenceGroup(
            title = stringResource(titleRes),
            preferenceItems = TranslationSourceLanguage.entries.map { language ->
                Preference.PreferenceItem.BasicListPreference(
                    value = current.ocrOverrideFor(language)?.name.orEmpty(),
                    entries = entries,
                    title = LocaleHelper.getDisplayName(language.langCode),
                    onValueChanged = { value ->
                        val engine = OcrEngine.entries.firstOrNull { it.name == value }
                        overrides.set(current.withOcrOverride(language, engine))
                    },
                )
            },
        )
    }

    @Composable
    private fun getVisionGroup(
        readerPreferences: ReaderPreferences,
        showGuide: (ApiKeyGuide) -> Unit,
        onTest: (OcrEngine) -> Unit,
    ): Preference.PreferenceGroup {
        val key by readerPreferences.visionApiKey.collectAsState()
        val limit by readerPreferences.visionMonthlyLimit.collectAsState()
        val used = rememberUsage(OcrEngine.GOOGLE_VISION, key, limit)
        return Preference.PreferenceGroup(
            title = OcrEngine.GOOGLE_VISION.displayName,
            preferenceItems = listOf(
                Preference.PreferenceItem.EditTextPreference(
                    preference = readerPreferences.visionApiKey,
                    title = stringResource(MR.strings.pref_vision_api_key),
                    subtitle = stringResource(MR.strings.pref_vision_api_key_summary),
                    onHelpClick = { showGuide(ApiKeyGuide.GOOGLE_VISION) },
                ),
                Preference.PreferenceItem.SliderPreference(
                    value = limit,
                    valueRange = 0..2000,
                    steps = 39,
                    title = stringResource(MR.strings.pref_vision_monthly_limit),
                    subtitle = usageSubtitle(key, used, limit, MR.strings.pref_vision_monthly_limit_summary),
                    valueString = limitLabel(limit),
                    onValueChanged = { readerPreferences.visionMonthlyLimit.set(it) },
                ),
                testItem(OcrEngine.GOOGLE_VISION, onTest),
            ),
        )
    }

    @Composable
    private fun getAzureGroup(
        readerPreferences: ReaderPreferences,
        showGuide: (ApiKeyGuide) -> Unit,
        onTest: (OcrEngine) -> Unit,
    ): Preference.PreferenceGroup {
        val key by readerPreferences.azureApiKey.collectAsState()
        val limit by readerPreferences.azureMonthlyLimit.collectAsState()
        val used = rememberUsage(OcrEngine.AZURE_READ, key, limit)
        return Preference.PreferenceGroup(
            title = OcrEngine.AZURE_READ.displayName,
            preferenceItems = listOf(
                Preference.PreferenceItem.EditTextPreference(
                    preference = readerPreferences.azureEndpoint,
                    title = stringResource(MR.strings.pref_azure_endpoint),
                    subtitle = stringResource(MR.strings.pref_azure_endpoint_summary),
                    onHelpClick = { showGuide(ApiKeyGuide.AZURE) },
                ),
                Preference.PreferenceItem.EditTextPreference(
                    preference = readerPreferences.azureApiKey,
                    title = stringResource(MR.strings.pref_azure_api_key),
                    subtitle = stringResource(MR.strings.pref_azure_api_key_summary),
                    onHelpClick = { showGuide(ApiKeyGuide.AZURE) },
                ),
                Preference.PreferenceItem.SliderPreference(
                    value = limit,
                    valueRange = 0..10000,
                    steps = 39,
                    title = stringResource(MR.strings.pref_azure_monthly_limit),
                    subtitle = usageSubtitle(key, used, limit, MR.strings.pref_azure_monthly_limit_summary),
                    valueString = limitLabel(limit),
                    onValueChanged = { readerPreferences.azureMonthlyLimit.set(it) },
                ),
                testItem(OcrEngine.AZURE_READ, onTest),
            ),
        )
    }

    @Composable
    private fun getGeminiGroup(
        readerPreferences: ReaderPreferences,
        showGuide: (ApiKeyGuide) -> Unit,
        onTest: (OcrEngine) -> Unit,
        onFetchModels: () -> Unit,
    ): Preference.PreferenceGroup {
        val key by readerPreferences.geminiApiKey.collectAsState()
        val limit by readerPreferences.geminiMonthlyLimit.collectAsState()
        val model by readerPreferences.geminiModel.collectAsState()
        val used = rememberUsage(OcrEngine.GEMINI, key, limit)
        return Preference.PreferenceGroup(
            title = OcrEngine.GEMINI.displayName,
            preferenceItems = listOf(
                Preference.PreferenceItem.EditTextPreference(
                    preference = readerPreferences.geminiApiKey,
                    title = stringResource(MR.strings.pref_gemini_api_key),
                    subtitle = stringResource(MR.strings.pref_gemini_api_key_summary),
                    onHelpClick = { showGuide(ApiKeyGuide.GEMINI) },
                ),
                Preference.PreferenceItem.EditTextPreference(
                    preference = readerPreferences.geminiModel,
                    title = stringResource(MR.strings.pref_gemini_model),
                    subtitle = if (model.isBlank()) {
                        stringResource(MR.strings.pref_gemini_model_auto)
                    } else {
                        "$model\n${stringResource(MR.strings.pref_gemini_model_summary)}"
                    },
                ),
                Preference.PreferenceItem.TextPreference(
                    title = stringResource(MR.strings.pref_gemini_fetch_models),
                    subtitle = stringResource(MR.strings.pref_gemini_fetch_models_summary),
                    onClick = onFetchModels,
                ),
                Preference.PreferenceItem.SliderPreference(
                    value = limit,
                    valueRange = 0..10000,
                    steps = 39,
                    title = stringResource(MR.strings.pref_gemini_monthly_limit),
                    subtitle = usageSubtitle(key, used, limit, MR.strings.pref_gemini_monthly_limit_summary),
                    valueString = limitLabel(limit),
                    onValueChanged = { readerPreferences.geminiMonthlyLimit.set(it) },
                ),
                testItem(OcrEngine.GEMINI, onTest),
            ),
        )
    }

    /** Re-read whenever the key or limit changes, which is when it can matter. */
    @Composable
    private fun rememberUsage(engine: OcrEngine, key: String, limit: Int): Int {
        val recognizer = remember { Injekt.get<CloudTextRecognizer>() }
        return remember(engine, key, limit) { recognizer.usedThisMonth(engine) }
    }

    @Composable
    private fun usageSubtitle(
        key: String,
        used: Int,
        limit: Int,
        emptyRes: dev.icerock.moko.resources.StringResource,
    ): String = if (key.isBlank()) {
        stringResource(emptyRes)
    } else {
        stringResource(MR.strings.pref_vision_usage, used, limit)
    }

    @Composable
    private fun limitLabel(limit: Int): String =
        if (limit == 0) stringResource(MR.strings.pref_vision_no_limit) else "$limit"
}
