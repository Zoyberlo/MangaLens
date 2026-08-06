package mihon.feature.translate

import android.graphics.Rect
import android.graphics.RectF
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderPageImageView
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.CoroutineScope
import logcat.LogPriority
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.i18n.MR
import uy.kohesive.injekt.injectLazy

/**
 * Everything a reader page holder does for translation.
 *
 * Both holders — paged and webtoon — had their own copy of this: ~140 lines
 * each, identical apart from where the image view lives and how the page bytes
 * are read. Two copies of the same logic drifted, and both sat inside files
 * upstream edits, so every one of those lines was a merge waiting to happen.
 *
 * The holders now supply the four things that genuinely differ and delegate the
 * rest, which puts about a dozen of our lines in each of their files instead of
 * a hundred and forty.
 *
 * @param view the image view the overlay is drawn on. The paged holder *is*
 * one; the webtoon holder owns one in a field.
 * @param pageKey identifies the page for the overlay cache, or null when the
 * holder is not currently bound to a page.
 * @param pageBytes the decoded page, already run through the holder's own image
 * processing — which is private to it, and differs between the two.
 */
class PageTranslationBinder(
    private val scope: CoroutineScope,
    private val view: ReaderPageImageView,
    private val activity: () -> ReaderActivity,
    private val pageKey: () -> String?,
    private val pageBytes: suspend () -> ByteArray?,
) {

    private val pageTranslator: PageTranslator by injectLazy()
    private val translationPreferences: TranslationPreferences by injectLazy()

    /** Call from the holder's bind. */
    fun bind() = with(view) {
        setTranslation(null)
        onTranslationBlocksChanged = { blocks ->
            pageKey()?.let { pageTranslator.replaceOverlay(it, blocks) }
        }
        onTranslationPhraseSelected = { phrase -> activity().onTranslatePhraseSelected(phrase) }
        onTranslationBlockTranslateRequested = ::translateBlock
        onTranslationBlockEditRequested = ::editBlock
        onTranslationBlockCloudRetryRequested = ::cloudRetryBlock
        translationCloudRetryAvailable = pageTranslator.isRetryEngineUsable
    }

    /**
     * Call from the holder's recycle. The view is about to be reused for
     * another page, and a stale overlay would briefly draw over it.
     */
    fun recycle() {
        view.setTranslation(null)
    }

    /** Call once the image is on screen, to restore what was translated earlier. */
    fun restoreCached() {
        pageKey()?.let { key -> pageTranslator.cachedOverlay(key)?.let(view::setTranslation) }
    }

    /**
     * Translates a user-selected area. [viewRect] is in [view]'s coordinate
     * space, which is what the selection overlay reports.
     */
    fun translateRegion(viewRect: RectF) {
        val key = pageKey() ?: return
        val sourceRect = view.viewToSourceRect(viewRect) ?: return
        val sourceWidth = view.sourceWidth() ?: return
        scope.launchIO {
            val result = try {
                val bytes = pageBytes() ?: return@launchIO
                val region = Rect(
                    sourceRect.left.toInt(),
                    sourceRect.top.toInt(),
                    sourceRect.right.toInt(),
                    sourceRect.bottom.toInt(),
                )
                pageTranslator.translateRegion(bytes, region, sourceWidth)
            } catch (e: Exception) {
                logcat(LogPriority.WARN, e)
                RegionTranslateResult.Failed
            }
            withUIContext { showRegionResult(key, result) }
        }
    }

    private fun showRegionResult(key: String, result: RegionTranslateResult) {
        val activity = activity()
        when (result) {
            is RegionTranslateResult.Success -> {
                // Which engine actually read the page. Silent fallback to ML Kit
                // is why "I see no difference" was impossible to tell apart from
                // "the engine never ran".
                if (result.fellBack) {
                    activity.toast(
                        activity.stringResource(
                            MR.strings.translate_engine_fallback,
                            result.requestedEngine.displayName,
                            result.usedEngine.displayName,
                        ),
                    )
                }
                if (translationPreferences.translateResultDisplay.get() == TranslateResultDisplay.PANEL) {
                    activity.showTranslationResult(result.translation)
                } else {
                    view.setTranslation(pageTranslator.storeOverlay(key, result.translation))
                }
            }
            RegionTranslateResult.NoText -> activity.toast(MR.strings.translate_selection_no_text)
            RegionTranslateResult.Failed -> activity.toast(MR.strings.translate_selection_failed)
        }
    }

    /** Original-first mode: translates one block in place. */
    private fun translateBlock(block: TranslatedBlock) {
        val key = pageKey() ?: return
        scope.launchIO {
            val translated = pageTranslator.translateSingle(block.sourceText)
            withUIContext {
                if (translated != null) {
                    pageTranslator.updateOverlayBlock(key, block, translated)?.let(view::setTranslation)
                } else {
                    activity().toast(MR.strings.translate_selection_failed)
                }
            }
        }
    }

    /**
     * Opens the block's recognized text in the bottom panel; whatever the user
     * translates there replaces this block in place.
     */
    private fun editBlock(block: TranslatedBlock) {
        val key = pageKey() ?: return
        activity().openTextEditor(block.sourceText) { source, translation ->
            pageTranslator.updateOverlayBlock(key, block, translation, source)?.let(view::setTranslation)
        }
    }

    /** Reads one block again with the paid cloud recognizer, on request. */
    private fun cloudRetryBlock(block: TranslatedBlock) {
        val key = pageKey() ?: return
        view.setTranslationBusyBlock(block)
        scope.launchIO {
            val result = try {
                val bytes = pageBytes()
                if (bytes == null) {
                    CloudRetryResult.Failed
                } else {
                    pageTranslator.retryBlockWithCloud(bytes, block)
                }
            } catch (e: Exception) {
                logcat(LogPriority.WARN, e)
                CloudRetryResult.Failed
            }
            withUIContext {
                view.setTranslationBusyBlock(null)
                applyCloudRetry(key, block, result)
            }
        }
    }

    private fun applyCloudRetry(key: String, block: TranslatedBlock, result: CloudRetryResult) {
        val activity = activity()
        when (result) {
            is CloudRetryResult.Success ->
                pageTranslator.updateOverlayBlock(key, block, result.translation, result.sourceText)
                    ?.let(view::setTranslation)
            is CloudRetryResult.RecognizedOnly -> {
                pageTranslator.updateOverlayBlock(key, block, "", result.sourceText)
                    ?.let(view::setTranslation)
                activity.toast(MR.strings.translate_selection_failed)
            }
            CloudRetryResult.NoText -> activity.toast(MR.strings.translate_selection_no_text)
            CloudRetryResult.NotConfigured -> activity.toast(MR.strings.cloud_ocr_not_configured)
            CloudRetryResult.Failed -> activity.toast(MR.strings.translate_selection_failed)
        }
    }
}
