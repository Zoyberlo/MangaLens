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
 * A block of text recognized on a page, in the coordinate space of the
 * bitmap that was fed to the recognizer.
 */
data class RecognizedBlock(
    val text: String,
    val bounds: Rect,
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
