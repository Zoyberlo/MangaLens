package mihon.feature.translate

import android.graphics.BitmapFactory
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

    /**
     * The backend that served the most recent AUTO-mode translation.
     */
    val lastAutoProvider: kotlinx.coroutines.flow.StateFlow<TranslationProvider?>
        get() = translator.lastAutoProvider

    /**
     * Warms up the slow-to-start pieces of the pipeline so the first real
     * translation is fast: loads the ML Kit model for the configured source
     * language and fires a throwaway translation (which also probes and
     * backs off dead Lingva instances). Safe to call repeatedly.
     */
    suspend fun warmUp() {
        val from = readerPreferences.autoTranslateSourceLanguage.get()
        val to = readerPreferences.autoTranslateTargetLanguage.get()

        try {
            val bitmap = android.graphics.Bitmap.createBitmap(64, 64, android.graphics.Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(android.graphics.Color.WHITE)
            try {
                recognizer.recognize(bitmap, from)
            } finally {
                bitmap.recycle()
            }
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "OCR warm-up failed" }
        }

        if (from.langCode != to) {
            try {
                translator.translate("hello", from.langCode, to)
            } catch (e: Exception) {
                logcat(LogPriority.WARN, e) { "Translator warm-up failed" }
            }
        }
    }

    /**
     * Translates a user-selected region of a page. [region] is in the
     * coordinate space of the *displayed* source image, whose width is
     * [regionSpaceWidth] — it may be downsampled relative to [imageBytes]
     * (long strips decoded through Coil), so coordinates are rescaled.
     * Returned block bounds are in displayed-source space via the returned
     * [PageTranslation] dimensions.
     */
    suspend fun translateRegion(
        imageBytes: ByteArray,
        region: android.graphics.Rect,
        regionSpaceWidth: Int,
    ): RegionTranslateResult {
        if (regionSpaceWidth <= 0) return RegionTranslateResult.NoText
        val from = readerPreferences.autoTranslateSourceLanguage.get()
        val to = readerPreferences.autoTranslateTargetLanguage.get()
        if (from.langCode == to) return RegionTranslateResult.Failed

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return RegionTranslateResult.NoText

        val scale = bounds.outWidth.toFloat() / regionSpaceWidth
        val imageRect = android.graphics.Rect(0, 0, bounds.outWidth, bounds.outHeight)
        val clamped = android.graphics.Rect(
            (region.left * scale).toInt(),
            (region.top * scale).toInt(),
            (region.right * scale).toInt(),
            (region.bottom * scale).toInt(),
        )
        if (!clamped.intersect(imageRect)) return RegionTranslateResult.NoText

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
        } ?: return RegionTranslateResult.NoText

        val recognition = try {
            recognizer.recognize(bitmap, from)
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "Text recognition failed" }
            return RegionTranslateResult.NoText
        } finally {
            bitmap.recycle()
        }
        // The recognizer may have fallen back to another script's model; the
        // translation has to follow it, not the configured setting
        val recognizedLanguage = recognition.language
        val recognized = recognition.blocks
        if (recognizedLanguage.langCode == to) return RegionTranslateResult.Failed

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

        val candidates = mergeBlocks(inSelection, recognizedLanguage)
            .filter { block -> block.text.length >= 2 && block.text.any { it.isLetter() } }
            .take(MAX_BLOCKS_PER_PAGE)
        if (candidates.isEmpty()) return RegionTranslateResult.NoText

        // Original-first mode (overlay display only): show the recognized text
        // untranslated; each block is translated on demand via translateSingle
        val originalFirst = readerPreferences.translateShowOriginalFirst.get() &&
            readerPreferences.translateResultDisplay.get() == TranslateResultDisplay.OVERLAY
        if (originalFirst) {
            val blocks = candidates.map { TranslatedBlock(it.text, "", it.bounds) }
            return RegionTranslateResult.Success(PageTranslation(bounds.outWidth, bounds.outHeight, blocks))
        }

        val blocks = translateBlocks(candidates, recognizedLanguage, to)
        if (blocks.isEmpty()) return RegionTranslateResult.Failed

        return RegionTranslateResult.Success(PageTranslation(bounds.outWidth, bounds.outHeight, blocks))
    }

    /**
     * Translates one block's text with the configured language pair; used by
     * the on-demand path of original-first mode.
     */
    suspend fun translateSingle(text: String): String? {
        val from = readerPreferences.autoTranslateSourceLanguage.get()
        val to = readerPreferences.autoTranslateTargetLanguage.get()
        val result = try {
            translator.translate(text, from.langCode, to)
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "Translation failed for block" }
            null
        }
        return result
    }

    /**
     * Writes a block's translation into the stored overlay for [pageKey] and
     * returns the updated overlay.
     */
    fun updateOverlayBlock(pageKey: String, block: TranslatedBlock, translation: String): PageTranslation? {
        val existing = overlayCache.get(pageKey) ?: return null
        val updated = existing.blocks.map {
            if (it === block || (it.sourceText == block.sourceText && it.bounds == block.bounds)) {
                it.copy(translatedText = translation)
            } else {
                it
            }
        }
        return PageTranslation(existing.imageWidth, existing.imageHeight, updated)
            .also { overlayCache.put(pageKey, it) }
    }

    /**
     * ML Kit often splits one speech bubble into a block per line. Merge
     * blocks that sit close together (relative to their line size) so the
     * whole bubble is translated as a single piece of text.
     */
    private fun mergeBlocks(
        blocks: List<RecognizedBlock>,
        from: TranslationSourceLanguage,
    ): List<RecognizedBlock> {
        val separator = when (from) {
            TranslationSourceLanguage.JAPANESE, TranslationSourceLanguage.CHINESE -> ""
            else -> " "
        }
        val list = blocks.toMutableList()
        var changed = true
        while (changed) {
            changed = false
            outer@ for (i in list.indices) {
                for (j in i + 1 until list.size) {
                    if (!shouldMerge(list[i].bounds, list[j].bounds)) continue
                    val a = list[i]
                    val b = list[j]
                    val ordered = orderForReading(a, b, from)
                    val union = android.graphics.Rect(a.bounds)
                    union.union(b.bounds)
                    list[i] = RecognizedBlock(
                        ordered.joinToString(separator) { it.text },
                        union,
                    )
                    list.removeAt(j)
                    changed = true
                    break@outer
                }
            }
        }
        return list
    }

    private fun shouldMerge(a: android.graphics.Rect, b: android.graphics.Rect): Boolean {
        val lineSize = minOf(a.height(), b.height()).coerceAtLeast(1)
        val verticalGap = maxOf(a.top, b.top) - minOf(a.bottom, b.bottom)
        val horizontalGap = maxOf(a.left, b.left) - minOf(a.right, b.right)
        return verticalGap < lineSize * 0.9f && horizontalGap < lineSize * 1.5f
    }

    private fun orderForReading(
        a: RecognizedBlock,
        b: RecognizedBlock,
        from: TranslationSourceLanguage,
    ): List<RecognizedBlock> {
        val lineSize = minOf(a.bounds.height(), b.bounds.height()).coerceAtLeast(1)
        val sameRow = kotlin.math.abs(a.bounds.top - b.bounds.top) < lineSize / 2
        return if (sameRow) {
            // Vertical Japanese columns read right to left
            if (from == TranslationSourceLanguage.JAPANESE) {
                listOf(a, b).sortedByDescending { it.bounds.left }
            } else {
                listOf(a, b).sortedBy { it.bounds.left }
            }
        } else {
            listOf(a, b).sortedBy { it.bounds.top }
        }
    }

    private suspend fun translateBlocks(
        recognized: List<RecognizedBlock>,
        from: TranslationSourceLanguage,
        to: String,
    ): List<TranslatedBlock> = coroutineScope {
        val semaphore = Semaphore(MAX_PARALLEL_TRANSLATIONS)
        // The other blocks of the selection serve as translation context
        // (consumed by DeepL; harmless for the other providers)
        val pageContext = recognized.joinToString("\n") { it.text }.take(MAX_CONTEXT_CHARS)
        recognized
            .map { block ->
                async {
                    semaphore.withPermit {
                        val translated = try {
                            translator.translate(block.text, from.langCode, to, pageContext)
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

    // region Overlay restore cache

    private val overlayCache = android.util.LruCache<String, PageTranslation>(OVERLAY_CACHE_SIZE)

    /** Last shown overlay for a page, so re-entering the page restores it. */
    fun cachedOverlay(pageKey: String): PageTranslation? = overlayCache.get(pageKey)

    /**
     * Merges [translation] into the stored overlay for [pageKey] and returns
     * the merged result (several selections on one page accumulate).
     */
    fun storeOverlay(pageKey: String, translation: PageTranslation): PageTranslation {
        val existing = overlayCache.get(pageKey)
        val merged = if (
            existing != null &&
            existing.imageWidth == translation.imageWidth &&
            existing.imageHeight == translation.imageHeight
        ) {
            PageTranslation(
                translation.imageWidth,
                translation.imageHeight,
                (existing.blocks + translation.blocks).distinct(),
            )
        } else {
            translation
        }
        overlayCache.put(pageKey, merged)
        return merged
    }

    /** Replaces the stored overlay blocks after the user dismissed some. */
    fun replaceOverlay(pageKey: String, blocks: List<TranslatedBlock>) {
        val existing = overlayCache.get(pageKey) ?: return
        if (blocks.isEmpty()) {
            overlayCache.remove(pageKey)
        } else {
            overlayCache.put(pageKey, PageTranslation(existing.imageWidth, existing.imageHeight, blocks))
        }
    }

    // endregion

    companion object {
        private const val MAX_BLOCKS_PER_PAGE = 24
        private const val MAX_PARALLEL_TRANSLATIONS = 4
        private const val MAX_CONTEXT_CHARS = 1500
        private const val OVERLAY_CACHE_SIZE = 30

        // Extra area around a manual selection so partially-selected text
        // blocks are still recognized in full
        private const val REGION_PADDING = 0.35f
        private const val MIN_REGION_PADDING_PX = 48

        // ML Kit accuracy degrades on very large inputs and huge bitmaps waste memory
        private const val MAX_OCR_DIMENSION = 2560
    }
}
