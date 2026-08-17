package mihon.feature.translate

import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.preference.getEnum

/**
 * Every preference the translation feature owns.
 *
 * These lived in ReaderPreferences, which is upstream's file and one they keep
 * editing — 100 lines of ours sitting in the middle of it. Nothing here has an
 * upstream counterpart, and only three of the eighty-odd call sites are outside
 * the fork's own code, so the whole block simply did not need to be there.
 *
 * **The keys are unchanged on purpose.** They are what a user's stored settings
 * are filed under; renaming one silently resets it to its default.
 */
class TranslationPreferences(
    private val preferenceStore: PreferenceStore,
) {

    val autoTranslateSourceLanguage: Preference<TranslationSourceLanguage> = preferenceStore.getEnum(
        "pref_auto_translate_source_lang",
        TranslationSourceLanguage.JAPANESE,
    )

    val autoTranslateTargetLanguage: Preference<String> = preferenceStore.getString(
        "pref_auto_translate_target_lang",
        "en",
    )

    val translationProvider: Preference<TranslationProvider> = preferenceStore.getEnum(
        "pref_translation_provider",
        TranslationProvider.AUTO,
    )

    val deeplApiKey: Preference<String> = preferenceStore.getString("pref_deepl_api_key", "")

    // DeepL meters characters, not requests; the default sits below its free
    // tier of 500,000 per month
    val deeplMonthlyCharLimit: Preference<Int> = preferenceStore.getInt("pref_deepl_char_limit", 450_000)

    val deeplUsageChars: Preference<Int> = preferenceStore.getInt("pref_deepl_usage_chars", 0)

    val deeplUsagePeriod: Preference<String> = preferenceStore.getString("pref_deepl_usage_period", "")

    // Dictionary repair of single-letter misreadings. Off means the recognizer's
    // output is shown exactly as it came, which suits pages full of invented
    // names better.
    val repairRecognizedWords: Preference<Boolean> = preferenceStore.getBoolean("pref_repair_words", true)

    // The engine every automatic pass uses. PaddleOCR reads hand-lettered
    // comic fonts where ML Kit systematically misreads them, and both are free
    // and offline; a cloud engine here would bill for every selection. The
    // bundled PaddleOCR model is English-only, so other languages fall back to
    // ML Kit on their own.
    val ocrEngine: Preference<OcrEngine> = preferenceStore.getEnum("pref_ocr_engine", OcrEngine.ON_DEVICE_PADDLE)

    // What the retry button on a block runs. Gemini reads stylised lettering
    // best and its free tier is per-day rather than per-month, which suits an
    // occasional "this one came out wrong" retry.
    val ocrRetryEngine: Preference<OcrEngine> = preferenceStore.getEnum("pref_ocr_retry_engine", OcrEngine.GEMINI)

    // `LANGUAGE=ENGINE` entries; a language with no entry follows the global
    // setting above. Scripts differ enough that one engine rarely wins for all.
    val ocrEngineOverrides: Preference<Set<String>> = preferenceStore.getStringSet("pref_ocr_engine_overrides")

    val ocrRetryEngineOverrides: Preference<Set<String>> =
        preferenceStore.getStringSet("pref_ocr_retry_engine_overrides")

    // Cloud text recognition (Google Cloud Vision). Off unless a key is set;
    // the limit guards the user's own billing, and defaults below Google's
    // free monthly tier of 1000 requests.
    val visionApiKey: Preference<String> = preferenceStore.getString("pref_vision_api_key", "")

    val visionMonthlyLimit: Preference<Int> = preferenceStore.getInt("pref_vision_monthly_limit", 900)

    val visionUsageCount: Preference<Int> = preferenceStore.getInt("pref_vision_usage_count", 0)

    val visionUsagePeriod: Preference<String> = preferenceStore.getString("pref_vision_usage_period", "")

    // Azure AI Vision. Its free tier is 5000 transactions a month — five times
    // Google's — so the default limit sits just under that.
    val azureApiKey: Preference<String> = preferenceStore.getString("pref_azure_api_key", "")

    val azureEndpoint: Preference<String> = preferenceStore.getString("pref_azure_endpoint", "")

    val azureMonthlyLimit: Preference<Int> = preferenceStore.getInt("pref_azure_monthly_limit", 4500)

    val azureUsageCount: Preference<Int> = preferenceStore.getInt("pref_azure_usage_count", 0)

    val azureUsagePeriod: Preference<String> = preferenceStore.getString("pref_azure_usage_period", "")

    // Gemini. Its free tier is metered per day, not per month, so the monthly
    // limit here is only a backstop against an accidental billing account.
    val geminiApiKey: Preference<String> = preferenceStore.getString("pref_gemini_api_key", "")

    // Empty means "ask the API and pick". Google retires model ids on its own
    // schedule — a hardcoded default eventually 404s with "no longer available
    // to new users" and no way to discover the replacement from inside the app.
    val geminiModel: Preference<String> = preferenceStore.getString("pref_gemini_model", "")

    val geminiMonthlyLimit: Preference<Int> = preferenceStore.getInt("pref_gemini_monthly_limit", 3000)

    val geminiUsageCount: Preference<Int> = preferenceStore.getInt("pref_gemini_usage_count", 0)

    val geminiUsagePeriod: Preference<String> = preferenceStore.getString("pref_gemini_usage_period", "")

    val translateShowOriginalFirst: Preference<Boolean> = preferenceStore.getBoolean(
        "pref_translate_original_first",
        false,
    )

    /**
     * Which buttons the reader's bottom bar shows. Lives here rather than in
     * ReaderPreferences because that is upstream's file, and the bar only
     * became configurable to make room for this fork's own buttons.
     *
     * Unknown ids are ignored on read, so an action dropped in a future build
     * does not resurrect as a blank button.
     */
    val readerBarActions: Preference<Set<String>> = preferenceStore.getStringSet(
        "pref_reader_bar_actions",
        ReaderBarAction.DEFAULT,
    )

    /**
     * How many times the "drag to select" hint has been shown. The first few
     * also mention that a long-press translates the whole screen — a gesture
     * that has been in the app since the beginning and that nobody could
     * discover, because nothing anywhere said it existed.
     */
    val translateHintsShown: Preference<Int> = preferenceStore.getInt("pref_translate_hints_shown", 0)

    val translateResultDisplay: Preference<TranslateResultDisplay> = preferenceStore.getEnum(
        "pref_translate_result_display",
        TranslateResultDisplay.OVERLAY,
    )
}
