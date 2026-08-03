package mihon.feature.translate

import android.graphics.Rect

/**
 * Source language of the manga being read. Determines which on-device
 * ML Kit text recognizer is used and the source language sent to the
 * translation backends.
 */
enum class TranslationSourceLanguage(val langCode: String) {
    JAPANESE("ja"),
    CHINESE("zh"),
    KOREAN("ko"),
    ENGLISH("en"),
}

/**
 * Which backend performs the translation. AUTO tries Google, then Lingva,
 * then MyMemory. DEEPL requires an API key set in reader settings.
 */
enum class TranslationProvider(val displayName: String) {
    AUTO("Auto"),
    GOOGLE("Google"),
    DEEPL("DeepL"),
    LINGVA("Lingva"),
    MYMEMORY("MyMemory"),
}

/**
 * Where translate-area results are presented: boxes drawn over the page, or
 * the bottom panel (keeps the artwork unobstructed).
 */
enum class TranslateResultDisplay {
    OVERLAY,
    PANEL,
}

/**
 * Display label for a provider; AUTO shows which backend it last used,
 * e.g. "Auto (Google)".
 */
fun TranslationProvider.labelWithAutoHint(lastAuto: TranslationProvider?): String =
    if (this == TranslationProvider.AUTO && lastAuto != null) {
        "$displayName (${lastAuto.displayName})"
    } else {
        displayName
    }

/**
 * Target languages offered in the translation settings.
 */
val TARGET_LANGUAGES = listOf("en", "uk", "de", "fr", "es", "it", "pl", "pt", "ru", "tr", "vi", "id", "th")

/**
 * A block of text recognized on a page, in the coordinate space of the
 * bitmap that was fed to the recognizer.
 */
data class RecognizedBlock(
    val text: String,
    val bounds: Rect,
)

/**
 * Recognized blocks plus the language whose model actually produced them,
 * which is not always the configured one — see [PageTextRecognizer.recognize].
 */
data class RecognitionResult(
    val blocks: List<RecognizedBlock>,
    val language: TranslationSourceLanguage,
)

/**
 * A recognized block together with its translation.
 */
data class TranslatedBlock(
    val sourceText: String,
    val translatedText: String,
    val bounds: Rect,
)

/**
 * Full translation result for a single page. [imageWidth]/[imageHeight]
 * are the dimensions of the (possibly downsampled) bitmap the OCR ran on;
 * block bounds are relative to them.
 */
data class PageTranslation(
    val imageWidth: Int,
    val imageHeight: Int,
    val blocks: List<TranslatedBlock>,
)

/**
 * Outcome of a translate-area request, so the UI can tell "nothing was
 * recognized" apart from "recognition worked but translation failed".
 */
sealed interface RegionTranslateResult {
    data class Success(val translation: PageTranslation) : RegionTranslateResult
    data object NoText : RegionTranslateResult
    data object Failed : RegionTranslateResult
}
