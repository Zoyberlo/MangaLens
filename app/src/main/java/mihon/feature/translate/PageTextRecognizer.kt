package mihon.feature.translate

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * On-device OCR for manga pages via bundled ML Kit text recognition models.
 */
class PageTextRecognizer {

    private val recognizers = mutableMapOf<TranslationSourceLanguage, TextRecognizer>()

    suspend fun recognize(bitmap: Bitmap, language: TranslationSourceLanguage): List<RecognizedBlock> {
        val recognizer = synchronized(recognizers) {
            recognizers.getOrPut(language) {
                TextRecognition.getClient(
                    when (language) {
                        TranslationSourceLanguage.JAPANESE -> JapaneseTextRecognizerOptions.Builder().build()
                        TranslationSourceLanguage.CHINESE -> ChineseTextRecognizerOptions.Builder().build()
                        TranslationSourceLanguage.KOREAN -> KoreanTextRecognizerOptions.Builder().build()
                        TranslationSourceLanguage.ENGLISH -> TextRecognizerOptions.DEFAULT_OPTIONS
                    },
                )
            }
        }

        val result = recognizer.process(InputImage.fromBitmap(bitmap, 0)).await()

        // CJK scripts don't use spaces between the lines of a speech bubble
        val lineSeparator = when (language) {
            TranslationSourceLanguage.JAPANESE, TranslationSourceLanguage.CHINESE -> ""
            else -> " "
        }

        return result.textBlocks.mapNotNull { block ->
            val bounds = block.boundingBox ?: return@mapNotNull null
            val text = block.lines.joinToString(lineSeparator) { it.text }.trim()
            if (text.isEmpty()) return@mapNotNull null
            RecognizedBlock(text, Rect(bounds))
        }
    }

    private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { continuation ->
        addOnSuccessListener { continuation.resume(it) }
        addOnFailureListener { continuation.resumeWithException(it) }
        addOnCanceledListener { continuation.cancel() }
    }
}
