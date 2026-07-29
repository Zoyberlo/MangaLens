package mihon.feature.translate

import android.graphics.BitmapFactory
import android.util.LruCache
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

/**
 * Orchestrates the page auto-translate pipeline:
 * decode (downsampled) bitmap -> on-device OCR -> translate each block.
 *
 * Results are cached in memory so re-entering a page is instant.
 */
class PageTranslator(
    private val recognizer: PageTextRecognizer,
    private val translator: TextTranslator,
    private val readerPreferences: ReaderPreferences,
) {

    val isEnabled: Boolean
        get() = readerPreferences.autoTranslate.get()

    private val pageCache = LruCache<String, PageTranslation>(PAGE_CACHE_SIZE)

    /**
     * Translates a page image. [cacheKey] must uniquely identify the page
     * (chapter + page index). Returns null when disabled, nothing was
     * recognized, or every translation failed.
     */
    suspend fun translatePage(cacheKey: String, imageBytes: ByteArray): PageTranslation? {
        if (!isEnabled) return null

        val from = readerPreferences.autoTranslateSourceLanguage.get()
        val to = readerPreferences.autoTranslateTargetLanguage.get()
        if (from.langCode == to) return null

        val fullKey = "$cacheKey:${from.langCode}:$to"
        pageCache.get(fullKey)?.let { return it }

        val bitmap = decodeSampled(imageBytes) ?: return null
        val imageWidth = bitmap.width
        val imageHeight = bitmap.height
        val recognized = try {
            recognizer.recognize(bitmap, from)
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "Text recognition failed" }
            return null
        } finally {
            bitmap.recycle()
        }

        val blocks = translateBlocks(recognized, from, to)
        if (blocks.isEmpty()) return null

        return PageTranslation(imageWidth, imageHeight, blocks)
            .also { pageCache.put(fullKey, it) }
    }

    /**
     * Translates a user-selected region of a page. [region] is in the
     * coordinate space of the full decoded image ([imageBytes]). Unlike
     * [translatePage] this ignores the auto-translate toggle — it's an
     * explicit user action. Returned block bounds are in full-image space.
     */
    suspend fun translateRegion(imageBytes: ByteArray, region: android.graphics.Rect): PageTranslation? {
        val from = readerPreferences.autoTranslateSourceLanguage.get()
        val to = readerPreferences.autoTranslateTargetLanguage.get()
        if (from.langCode == to) return null

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val imageRect = android.graphics.Rect(0, 0, bounds.outWidth, bounds.outHeight)
        val clamped = android.graphics.Rect(region)
        if (!clamped.intersect(imageRect)) return null

        // OCR a padded area so a partial selection still catches the whole
        // text block; blocks are then filtered by the original selection.
        val padded = android.graphics.Rect(clamped).apply {
            inset(
                -(clamped.width() * REGION_PADDING).toInt().coerceAtLeast(MIN_REGION_PADDING_PX),
                -(clamped.height() * REGION_PADDING).toInt().coerceAtLeast(MIN_REGION_PADDING_PX),
            )
        }
        padded.intersect(imageRect)

        var sampleSize = 1
        while (maxOf(padded.width(), padded.height()) / (sampleSize * 2) >= MAX_OCR_DIMENSION) {
            sampleSize *= 2
        }

        @Suppress("DEPRECATION")
        val decoder = android.graphics.BitmapRegionDecoder.newInstance(imageBytes, 0, imageBytes.size, false)
        val bitmap = try {
            decoder.decodeRegion(padded, BitmapFactory.Options().apply { inSampleSize = sampleSize })
        } finally {
            decoder.recycle()
        } ?: return null

        val recognized = try {
            recognizer.recognize(bitmap, from)
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "Text recognition failed" }
            return null
        } finally {
            bitmap.recycle()
        }

        val inSelection = recognized
            .map { block ->
                block.copy(
                    bounds = android.graphics.Rect(
                        padded.left + block.bounds.left * sampleSize,
                        padded.top + block.bounds.top * sampleSize,
                        padded.left + block.bounds.right * sampleSize,
                        padded.top + block.bounds.bottom * sampleSize,
                    ),
                )
            }
            .filter { android.graphics.Rect.intersects(it.bounds, clamped) }

        val blocks = translateBlocks(inSelection, from, to)
        if (blocks.isEmpty()) return null

        return PageTranslation(bounds.outWidth, bounds.outHeight, blocks)
    }

    private suspend fun translateBlocks(
        recognized: List<RecognizedBlock>,
        from: TranslationSourceLanguage,
        to: String,
    ): List<TranslatedBlock> = coroutineScope {
        val semaphore = Semaphore(MAX_PARALLEL_TRANSLATIONS)
        recognized
            .filter { block -> block.text.length >= 2 && block.text.any { it.isLetter() } }
            .take(MAX_BLOCKS_PER_PAGE)
            .map { block ->
                async {
                    semaphore.withPermit {
                        val translated = try {
                            translator.translate(block.text, from.langCode, to)
                        } catch (e: Exception) {
                            logcat(LogPriority.WARN, e) { "Translation failed for block" }
                            null
                        }
                        translated?.let { TranslatedBlock(block.text, it, block.bounds) }
                    }
                }
            }
            .awaitAll()
            .filterNotNull()
    }

    private fun decodeSampled(imageBytes: ByteArray): android.graphics.Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sampleSize = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sampleSize * 2) >= MAX_OCR_DIMENSION) {
            sampleSize *= 2
        }

        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        return BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, options)
    }

    companion object {
        private const val PAGE_CACHE_SIZE = 40
        private const val MAX_BLOCKS_PER_PAGE = 24
        private const val MAX_PARALLEL_TRANSLATIONS = 4

        // Extra area around a manual selection so partially-selected text
        // blocks are still recognized in full
        private const val REGION_PADDING = 0.35f
        private const val MIN_REGION_PADDING_PX = 48

        // ML Kit accuracy degrades on very large inputs and huge bitmaps waste memory
        private const val MAX_OCR_DIMENSION = 2560
    }
}
