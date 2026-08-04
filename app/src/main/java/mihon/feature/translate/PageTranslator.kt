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
    private val cloudRecognizer: CloudTextRecognizer,
    private val translator: TextTranslator,
    private val readerPreferences: ReaderPreferences,
    private val lexicon: EnglishLexicon,
    private val paddle: PaddleTextRecognizer,
) {

    /**
     * The engine that reads a fresh selection, for [language]. A per-language
     * override wins over the global setting; an engine that is not set up, or
     * cannot produce block geometry, falls back to on-device so the automatic
     * path never silently stops working.
     */
    fun primaryEngineFor(language: TranslationSourceLanguage): OcrEngine {
        val engine = readerPreferences.ocrEngineOverrides.get().ocrOverrideFor(language)
            ?: readerPreferences.ocrEngine.get()
        val usable = when {
            engine == OcrEngine.ON_DEVICE_PADDLE -> paddle.isAvailable
            engine.isCloud -> cloudRecognizer.isConfigured(engine)
            else -> true
        }
        return engine.takeIf { it.canDetectLayout && usable } ?: OcrEngine.ON_DEVICE
    }

    /** The engine the retry button on a block runs, for [language]. */
    fun retryEngineFor(language: TranslationSourceLanguage): OcrEngine =
        readerPreferences.ocrRetryEngineOverrides.get().ocrOverrideFor(language)
            ?: readerPreferences.ocrRetryEngine.get()

    /**
     * Whether the retry button is worth offering at all: it is not, when the
     * configured retry engine would just re-run the on-device model that
     * already produced the text.
     */
    val isRetryEngineUsable: Boolean
        get() = TranslationSourceLanguage.entries.any { language ->
            val engine = retryEngineFor(language)
            engine.isCloud && cloudRecognizer.isConfigured(engine)
        }

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

        // Building the word filter and the ONNX session takes a moment; do it
        // here rather than on the first bubble the user is waiting for
        try {
            lexicon.preload()
            if (primaryEngineFor(from) == OcrEngine.ON_DEVICE_PADDLE) paddle.preload()
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "Lexicon warm-up failed" }
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

        // A tall selection used to be downsampled to fit the recognizer's
        // input, which shrank the lettering and lost the smaller lines — so
        // selecting more of a bubble read it worse. Bands keep every part of
        // the selection at the resolution a small selection would have got.
        val bands = OcrLayout.splitIntoBands(
            TextBox(padded.left, padded.top, padded.right, padded.bottom),
            MAX_OCR_DIMENSION,
            BAND_OVERLAP_PX,
        )

        var fromCloud = false
        val recognition = try {
            val engine = primaryEngineFor(from)
            val perBand = bands.map { band ->
                val rect = android.graphics.Rect(band.left, band.top, band.right, band.bottom)
                recognizeBand(imageBytes, rect, engine, from).also { if (it.second) fromCloud = true }
            }
            val blocks = perBand.flatMap { (result, _) -> result.blocks }
            val language = perBand.firstOrNull { it.first.blocks.isNotEmpty() }?.first?.language ?: from
            RecognitionResult(blocks, language)
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "Text recognition failed" }
            return RegionTranslateResult.NoText
        }

        // The recognizer may have fallen back to another script's model; the
        // translation has to follow it, not the configured setting
        val recognizedLanguage = recognition.language
        val recognized = recognition.blocks
        if (recognizedLanguage.langCode == to) return RegionTranslateResult.Failed

        val onPage = OcrLayout.dedupeOverlapping(
            recognized.map {
                TextItem(it.text, TextBox(it.bounds.left, it.bounds.top, it.bounds.right, it.bounds.bottom))
            },
        ).map { RecognizedBlock(it.text, android.graphics.Rect(it.box.left, it.box.top, it.box.right, it.box.bottom)) }

        // Merge first, filter second. The other way round drops a bubble's
        // first line whenever the selection starts just below it — the line is
        // discarded before merging can rejoin it, and the padded area that was
        // supposed to make selections forgiving never gets a say. That is why
        // "I KNEW IT" kept vanishing off the top of its own bubble.
        val merged = mergeBlocks(onPage, recognizedLanguage)
            .filter { android.graphics.Rect.intersects(it.bounds, clamped) }
            .filter { block -> block.text.length >= 2 && block.text.any { it.isLetter() } }
            .take(MAX_BLOCKS_PER_PAGE)
        if (merged.isEmpty()) return RegionTranslateResult.NoText

        // Second pass: each bubble is re-read on its own at full resolution.
        // Scaling by the whole selection means a generous selection shrinks the
        // lettering; per-block scaling gives every bubble the same glyph size.
        //
        // Skipped entirely when the first pass came from the cloud: that pass is
        // both billed and better, and refinement is on-device, so it could only
        // ever trade a paid reading for a free worse one.
        val repairWords = readerPreferences.repairRecognizedWords.get()
        val candidates = if (fromCloud) {
            merged
        } else {
            merged.map { block ->
                // Repair before comparing, or a correct-but-digit-speckled
                // reading loses to a garbled one that merely has no digits
                // The dictionary only knows English, so it only gets a say
                // when English is what was read
                val checkWord = lexicon::contains.takeIf {
                    repairWords && recognizedLanguage == TranslationSourceLanguage.ENGLISH
                }
                val primary = OcrText.repairDigitConfusions(block.text, checkWord)
                val refined = refineBlockText(imageBytes, block, recognizedLanguage)
                    ?.let { OcrText.repairDigitConfusions(it, checkWord) }
                val best = if (refined != null && OcrText.textQuality(refined) > OcrText.textQuality(primary)) {
                    refined
                } else {
                    primary
                }
                // This font's mistakes are systematic, so both passes make the
                // same one and agree; only the dictionary sees those.
                val repaired = checkWord?.let { OcrText.repairLetterConfusions(best, it) } ?: best
                val passesAgree = refined == null ||
                    OcrText.agreementRatio(primary, refined) >= OcrText.MIN_PASS_AGREEMENT
                val readsAsEnglish = checkWord == null ||
                    OcrText.unknownWordRatio(repaired, checkWord) <= OcrText.MAX_UNKNOWN_WORDS
                block.copy(text = repaired, confident = passesAgree && readsAsEnglish)
            }
        }

        // Original-first mode (overlay display only): show the recognized text
        // untranslated; each block is translated on demand via translateSingle
        val originalFirst = readerPreferences.translateShowOriginalFirst.get() &&
            readerPreferences.translateResultDisplay.get() == TranslateResultDisplay.OVERLAY
        if (originalFirst) {
            val blocks = candidates.map { TranslatedBlock(it.text, "", it.bounds, it.confident) }
            return RegionTranslateResult.Success(PageTranslation(bounds.outWidth, bounds.outHeight, blocks))
        }

        val blocks = translateBlocks(candidates, recognizedLanguage, to)
        if (blocks.isEmpty()) return RegionTranslateResult.Failed

        return RegionTranslateResult.Success(PageTranslation(bounds.outWidth, bounds.outHeight, blocks))
    }

    /**
     * Re-reads one recognized block from the original image, cropped tight and
     * scaled so its lettering is large regardless of how much the user
     * selected. Returns null when the crop or recognition fails.
     */
    private suspend fun refineBlockText(
        imageBytes: ByteArray,
        block: RecognizedBlock,
        language: TranslationSourceLanguage,
    ): String? {
        return try {
            val bounds = block.bounds
            if (bounds.width() <= 0 || bounds.height() <= 0) return null

            val padX = (bounds.width() * BLOCK_PADDING).toInt().coerceAtLeast(6)
            val padY = (bounds.height() * BLOCK_PADDING).toInt().coerceAtLeast(6)
            val crop = android.graphics.Rect(bounds).apply { inset(-padX, -padY) }

            val info = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, info)
            if (!crop.intersect(android.graphics.Rect(0, 0, info.outWidth, info.outHeight))) return null

            @Suppress("DEPRECATION")
            val decoder = android.graphics.BitmapRegionDecoder
                .newInstance(imageBytes, 0, imageBytes.size, false)
            val decoded = try {
                decoder.decodeRegion(crop, BitmapFactory.Options())
            } finally {
                decoder.recycle()
            } ?: return null

            val scale = (TARGET_BLOCK_HEIGHT / decoded.height.toFloat()).coerceIn(1f, MAX_BLOCK_UPSCALE)
            val prepared = enhanceForOcr(decoded, scale)
            val text = try {
                recognizer.recognize(prepared, language).blocks.joinToString(" ") { it.text }.trim()
            } finally {
                prepared.recycle()
                decoded.recycle()
            }
            text.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "Block refinement failed" }
            null
        }
    }

    /**
     * How much to enlarge a region before OCR. Small selections carry too few
     * pixels per glyph for stylised lettering; the cap keeps the bitmap within
     * what ML Kit handles well.
     */
    private fun ocrUpscaleFor(bitmap: android.graphics.Bitmap): Float {
        val maxDim = maxOf(bitmap.width, bitmap.height)
        if (maxDim <= 0) return 1f
        val wanted = when {
            maxDim < 700 -> 3f
            maxDim < 1200 -> 2f
            maxDim < 1800 -> 1.5f
            else -> 1f
        }
        return minOf(wanted, MAX_OCR_DIMENSION.toFloat() / maxDim).coerceAtLeast(1f)
    }

    /**
     * Returns an enlarged, grey, contrast-boosted copy: manga bubbles are dark
     * lettering on a light fill, so pushing them apart helps the recognizer
     * far more than the extra pixels alone.
     */
    private fun enhanceForOcr(source: android.graphics.Bitmap, scale: Float): android.graphics.Bitmap {
        val width = (source.width * scale).toInt().coerceAtLeast(1)
        val height = (source.height * scale).toInt().coerceAtLeast(1)
        val result = android.graphics.Bitmap.createBitmap(
            width,
            height,
            android.graphics.Bitmap.Config.ARGB_8888,
        )
        val canvas = android.graphics.Canvas(result)
        val contrast = 1.6f
        val translate = -(0.5f * contrast - 0.5f) * 255f
        val matrix = android.graphics.ColorMatrix().apply {
            setSaturation(0f)
            postConcat(
                android.graphics.ColorMatrix(
                    floatArrayOf(
                        contrast, 0f, 0f, 0f, translate,
                        0f, contrast, 0f, 0f, translate,
                        0f, 0f, contrast, 0f, translate,
                        0f, 0f, 0f, 1f, 0f,
                    ),
                ),
            )
        }
        val paint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = android.graphics.ColorMatrixColorFilter(matrix)
        }
        canvas.drawBitmap(
            source,
            null,
            android.graphics.Rect(0, 0, width, height),
            paint,
        )
        return result
    }

    /**
     * Re-reads one block with the configured retry engine and re-translates
     * it. Every other path defaults to on-device precisely because the cloud
     * engines are billed per request, so this only spends quota when the user
     * taps retry on a block the cheap recognizer garbled.
     *
     * The crop is sent unmodified: the contrast/upscale treatment exists to
     * help ML Kit and only degrades what the cloud engines see.
     */
    suspend fun retryBlockWithCloud(imageBytes: ByteArray, block: TranslatedBlock): CloudRetryResult {
        val from = readerPreferences.autoTranslateSourceLanguage.get()
        val to = readerPreferences.autoTranslateTargetLanguage.get()
        val engine = retryEngineFor(from)
        if (!engine.isCloud || !cloudRecognizer.isConfigured(engine)) return CloudRetryResult.NotConfigured

        val bounds = block.bounds
        if (bounds.width() <= 0 || bounds.height() <= 0) return CloudRetryResult.NoText

        val text = try {
            val padX = (bounds.width() * BLOCK_PADDING).toInt().coerceAtLeast(6)
            val padY = (bounds.height() * BLOCK_PADDING).toInt().coerceAtLeast(6)
            val crop = android.graphics.Rect(bounds).apply { inset(-padX, -padY) }

            val info = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, info)
            if (!crop.intersect(android.graphics.Rect(0, 0, info.outWidth, info.outHeight))) {
                return CloudRetryResult.NoText
            }

            @Suppress("DEPRECATION")
            val decoder = android.graphics.BitmapRegionDecoder
                .newInstance(imageBytes, 0, imageBytes.size, false)
            val decoded = try {
                decoder.decodeRegion(crop, BitmapFactory.Options())
            } finally {
                decoder.recycle()
            } ?: return CloudRetryResult.NoText

            try {
                cloudRecognizer.recognize(engine, decoded, from)?.joinToString(" ") { it.text }?.trim()
            } finally {
                decoded.recycle()
            }
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "Cloud re-recognition failed" }
            null
        } ?: return CloudRetryResult.Failed

        if (text.isBlank()) return CloudRetryResult.NoText
        val translated = try {
            translator.translate(text, from.langCode, to)
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "Translation failed after cloud re-recognition" }
            null
        } ?: return CloudRetryResult.RecognizedOnly(text)
        return CloudRetryResult.Success(text, translated)
    }

    /**
     * Decodes one band, enhances it and recognizes it, returning blocks in
     * **full-image** coordinates. The second value says whether a cloud engine
     * produced them, which decides later whether the free refinement pass is
     * allowed to overwrite the result.
     */
    private suspend fun recognizeBand(
        imageBytes: ByteArray,
        band: android.graphics.Rect,
        engine: OcrEngine,
        language: TranslationSourceLanguage,
    ): Pair<RecognitionResult, Boolean> {
        @Suppress("DEPRECATION")
        val decoder = android.graphics.BitmapRegionDecoder.newInstance(imageBytes, 0, imageBytes.size, false)
        val decoded = try {
            decoder.decodeRegion(band, BitmapFactory.Options())
        } finally {
            decoder.recycle()
        } ?: return RecognitionResult(emptyList(), language) to false

        // Comic lettering is stylised and often small on the page; enlarging
        // and hardening the contrast before OCR is what turns "swolos manshe"
        // back into "swordsmanship"
        val upscale = ocrUpscaleFor(decoded)
        val bitmap = if (upscale > 1f) enhanceForOcr(decoded, upscale) else decoded

        var fromCloud = false
        val result = try {
            when (engine) {
                OcrEngine.ON_DEVICE_PADDLE -> recognizeWithPaddle(bitmap, language)
                else ->
                    cloudRecognizer.recognize(engine, bitmap, language)
                        ?.takeIf { it.isNotEmpty() }
                        ?.also { fromCloud = true }
                        ?.let { RecognitionResult(it, language) }
                        ?: recognizer.recognize(bitmap, language)
            }
        } finally {
            if (bitmap !== decoded) bitmap.recycle()
            decoded.recycle()
        }

        val toImage = 1f / upscale
        val moved = result.blocks.map { block ->
            block.copy(
                bounds = android.graphics.Rect(
                    band.left + (block.bounds.left * toImage).toInt(),
                    band.top + (block.bounds.top * toImage).toInt(),
                    band.left + (block.bounds.right * toImage).toInt(),
                    band.top + (block.bounds.bottom * toImage).toInt(),
                ),
            )
        }
        return RecognitionResult(moved, result.language) to fromCloud
    }

    /**
     * ML Kit finds the lines, PaddleOCR reads them.
     *
     * The layout ML Kit produces is accurate even on lettering it cannot read,
     * so only the reading is replaced. A line PaddleOCR declines is kept as ML
     * Kit had it, which means the worst case is what we had before.
     */
    private suspend fun recognizeWithPaddle(
        bitmap: android.graphics.Bitmap,
        language: TranslationSourceLanguage,
    ): RecognitionResult {
        val lines = recognizer.recognizeLines(bitmap, language)
        if (lines.isEmpty()) return recognizer.recognize(bitmap, language)

        val read = lines.map { line ->
            val crop = cropSafely(bitmap, line.bounds) ?: return@map line
            val text = try {
                paddle.recognize(crop)
            } finally {
                crop.recycle()
            }
            if (text.isNullOrBlank()) line else line.copy(text = text)
        }
        return RecognitionResult(read, language)
    }

    /** Crops [bounds] from [bitmap], clamped to it; null when nothing is left. */
    private fun cropSafely(
        bitmap: android.graphics.Bitmap,
        bounds: android.graphics.Rect,
    ): android.graphics.Bitmap? {
        val rect = android.graphics.Rect(bounds)
        if (!rect.intersect(android.graphics.Rect(0, 0, bitmap.width, bitmap.height))) return null
        if (rect.width() <= 0 || rect.height() <= 0) return null
        return try {
            android.graphics.Bitmap.createBitmap(bitmap, rect.left, rect.top, rect.width(), rect.height())
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "Line crop failed" }
            null
        }
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
     * returns the updated overlay. [sourceText] differs from the block's own
     * when the user corrected the recognized text before re-translating.
     */
    fun updateOverlayBlock(
        pageKey: String,
        block: TranslatedBlock,
        translation: String,
        sourceText: String = block.sourceText,
    ): PageTranslation? {
        val existing = overlayCache.get(pageKey) ?: return null
        val updated = existing.blocks.map {
            if (it === block || (it.sourceText == block.sourceText && it.bounds == block.bounds)) {
                it.copy(sourceText = sourceText, translatedText = translation)
            } else {
                it
            }
        }
        return PageTranslation(existing.imageWidth, existing.imageHeight, updated)
            .also { overlayCache.put(pageKey, it) }
    }

    /**
     * Rejoins the per-line boxes a recognizer returns into whole bubbles. The
     * rules are in [OcrLayout]; this only converts to and from `Rect`.
     */
    private fun mergeBlocks(
        blocks: List<RecognizedBlock>,
        from: TranslationSourceLanguage,
    ): List<RecognizedBlock> {
        val items = blocks.map { block ->
            TextItem(
                block.text,
                TextBox(block.bounds.left, block.bounds.top, block.bounds.right, block.bounds.bottom),
            )
        }
        return OcrLayout.merge(items, from).map { item ->
            RecognizedBlock(
                item.text,
                android.graphics.Rect(item.box.left, item.box.top, item.box.right, item.box.bottom),
            )
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
                        translated?.let { TranslatedBlock(block.text, it, block.bounds, block.confident) }
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

        // Bands overlap so a line of text never falls on a seam
        private const val BAND_OVERLAP_PX = 160

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

        // Second-pass settings: crop margin around a block, the height its
        // crop is scaled to, and the ceiling on that scaling
        private const val BLOCK_PADDING = 0.14f
        private const val TARGET_BLOCK_HEIGHT = 640f
        private const val MAX_BLOCK_UPSCALE = 4f
    }
}
