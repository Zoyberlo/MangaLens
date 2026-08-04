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
 * Which engine reads text off the page.
 *
 * [ON_DEVICE] is free, offline and the default everywhere. The rest are the
 * user's own paid accounts, metered against a monthly limit they set.
 */
enum class OcrEngine(val displayName: String) {
    ON_DEVICE("On-device"),
    GOOGLE_VISION("Google Cloud Vision"),
    AZURE_READ("Azure AI Vision"),
    GEMINI("Gemini"),
    ;

    /**
     * Whether the engine returns per-bubble boxes. Gemini transcribes what it
     * is given but cannot be trusted for layout, so it can only re-read a
     * block whose bounds are already known — never drive the automatic pass.
     */
    val canDetectLayout: Boolean get() = this != GEMINI

    val isCloud: Boolean get() = this != ON_DEVICE
}

/**
 * Per-source-language engine overrides, stored as `LANGUAGE=ENGINE` entries so
 * a plain string set covers the whole mapping. A missing entry means "use the
 * global setting".
 */
fun Set<String>.ocrOverrideFor(language: TranslationSourceLanguage): OcrEngine? =
    firstOrNull { it.startsWith("${language.name}=") }
        ?.substringAfter('=')
        ?.let { name -> OcrEngine.entries.firstOrNull { it.name == name } }

fun Set<String>.withOcrOverride(language: TranslationSourceLanguage, engine: OcrEngine?): Set<String> {
    val rest = filterNot { it.startsWith("${language.name}=") }.toSet()
    return if (engine == null) rest else rest + "${language.name}=${engine.name}"
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
    /**
     * False when the two recognition passes disagreed about this text, i.e.
     * the model was guessing. Shown to the user rather than hidden — a
     * confident translation of an unreadable bubble is worse than none.
     */
    val confident: Boolean = true,
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
    /** See [RecognizedBlock.confident]. */
    val confident: Boolean = true,
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

/**
 * Outcome of re-reading one block through the paid cloud recognizer, which
 * additionally has to report "the user never set a key up".
 */
sealed interface CloudRetryResult {
    data class Success(val sourceText: String, val translation: String) : CloudRetryResult

    /**
     * Recognition worked but translating it did not. The request has already
     * been billed, so the better text is kept rather than thrown away — the
     * block shows it untranslated, with the usual + to translate in place.
     */
    data class RecognizedOnly(val sourceText: String) : CloudRetryResult

    data object NoText : CloudRetryResult
    data object NotConfigured : CloudRetryResult
    data object Failed : CloudRetryResult
}
