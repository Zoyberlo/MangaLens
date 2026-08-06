package mihon.feature.translate

import android.graphics.RectF
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import dev.icerock.moko.resources.StringResource
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.viewer.pager.PagerViewer
import eu.kanade.tachiyomi.ui.reader.viewer.webtoon.WebtoonViewer
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.i18n.MR
import uy.kohesive.injekt.injectLazy
import kotlin.math.abs

/**
 * The reader's half of the translation feature: the bottom panel, the
 * rubber-band area selector, and the quota notices.
 *
 * All of this used to sit in ReaderActivity — a file upstream maintains and
 * has already restructured under us once. None of it is about being a reader
 * activity; it needs a container to put views in, a scope, and a way to close
 * the reader menu, and those are what it now asks for.
 *
 * @param currentViewer the reader's live viewer, which decides where a
 * selection is translated. Read on each call, never cached: it changes when
 * the user switches reading mode.
 * @param dictation resolved lazily — the panel owns the editor that dictation
 * writes into, and dictation owns the mic button the panel shows, so one of
 * the two references has to be late.
 */
class ReaderTranslationController(
    private val activity: ReaderActivity,
    /**
     * Resolved on each use, never at construction: the activity holds its
     * binding in a lateinit that only onCreate assigns, so reading it while
     * the activity's fields initialise throws.
     */
    private val container: () -> FrameLayout,
    private val scope: CoroutineScope,
    private val currentViewer: () -> Any?,
    private val closeMenu: () -> Unit,
    private val showHint: (StringResource) -> Unit,
    private val dictation: () -> ReaderDictation,
) {

    private val translationPreferences: TranslationPreferences by injectLazy()
    private val textTranslator: TextTranslator by injectLazy()
    private val pageTranslator: PageTranslator by injectLazy()

    private var panel: WordInspectorView? = null
    private var selector: TranslateSelectionView? = null
    private var lookupJob: Job? = null

    /**
     * Set while the panel's editor was opened from an overlay block, so the
     * corrected text and its translation can be written back into that block.
     */
    private var pendingEditTarget: ((String, String) -> Unit)? = null

    private var scrollAccumulator = 0f

    /** What dictation writes into, or null while the panel is closed. */
    val editor: WordInspectorView?
        get() = panel

    // region Panel

    /**
     * Shows the panel for a word or phrase picked in a translation overlay,
     * with its translation variants. Null hides the panel.
     */
    fun onPhraseSelected(phrase: String?) {
        if (phrase == null) {
            hidePanel()
            return
        }
        pendingEditTarget = null
        val panel = ensurePanel()
        scrollAccumulator = 0f
        panel.showLoading(phrase)
        reveal(panel)
        lookupVariants(panel, phrase)
    }

    /**
     * Panel result-display mode: shows a whole selection's translation in the
     * panel instead of overlay boxes.
     */
    fun showTranslationResult(translation: PageTranslation) {
        val original = translation.blocks.joinToString("\n\n") { it.sourceText }
        val translated = translation.blocks
            .mapNotNull { it.translatedText.takeIf(String::isNotBlank) }
            .joinToString("\n\n")
        if (original.isBlank() || translated.isBlank()) return
        pendingEditTarget = null
        val panel = ensurePanel()
        scrollAccumulator = 0f
        panel.showResult(original, translated)
        reveal(panel)
    }

    /**
     * Opens the editor on [initial] — empty for typing or dictating from
     * scratch. [onTranslated] receives the final text and its translation when
     * the editor is confirmed, so the caller can write the result back, e.g.
     * into the overlay block the text came from.
     */
    fun openTextEditor(initial: String, onTranslated: ((String, String) -> Unit)? = null) {
        pendingEditTarget = onTranslated
        val panel = ensurePanel()
        lookupJob?.cancel()
        scrollAccumulator = 0f
        reveal(panel)
        panel.startEditing(initial)
    }

    /**
     * Manual entry: opens the editor with nothing in it, for text that is not
     * on the page, or that recognition cannot read.
     */
    fun startManualTranslate() {
        closeMenu()
        openTextEditor("")
    }

    fun hidePanel() {
        lookupJob?.cancel()
        dictation().stop()
        pendingEditTarget = null
        panel?.onHidden()
        panel?.visibility = View.GONE
    }

    /**
     * Called by the webtoon viewer on every scroll: once the reader has moved
     * on — about a third of a screen — the panel is stale, so hide it.
     */
    fun onReaderScrolled(dy: Int) {
        val panel = panel ?: return
        if (panel.visibility != View.VISIBLE) return
        // Never yank the panel away mid-edit
        if (panel.isEditingText) return
        scrollAccumulator += abs(dy)
        if (scrollAccumulator > container().height / 3f) {
            hidePanel()
        }
    }

    private fun reveal(panel: WordInspectorView) {
        panel.visibility = View.VISIBLE
        panel.bringToFront()
    }

    private fun lookupVariants(panel: WordInspectorView, phrase: String) {
        lookupJob?.cancel()
        lookupJob = scope.launchIO {
            val from = translationPreferences.autoTranslateSourceLanguage.get().langCode
            val to = translationPreferences.autoTranslateTargetLanguage.get()
            val variants = textTranslator.lookupVariants(phrase, from, to)
            withUIContext { panel.showVariantsFor(phrase, variants) }
        }
    }

    /** Translates whatever the user typed, dictated or corrected. */
    private fun onEditorTextSubmitted(text: String) {
        val panel = panel ?: return
        panel.showTranslating()
        lookupJob?.cancel()
        lookupJob = scope.launchIO {
            val translated = pageTranslator.translateSingle(text)
            withUIContext {
                if (translated.isNullOrBlank()) {
                    activity.toast(MR.strings.translate_selection_failed)
                    panel.startEditing(text)
                } else {
                    panel.showResult(text, translated)
                    pendingEditTarget?.invoke(text, translated)
                }
            }
        }
    }

    private fun ensurePanel(): WordInspectorView {
        return panel ?: WordInspectorView(activity).also { view ->
            view.onDismiss = ::hidePanel
            view.onPhraseTap = { phrase -> lookupVariants(view, phrase) }
            view.onTextSubmitted = ::onEditorTextSubmitted
            view.onVoiceInput = { dictation().toggle() }
            panel = view
            container().addView(
                view,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM,
                ),
            )
        }
    }

    // endregion

    // region Selection

    /** Shows the "translate area" rubber-band selector over the current page. */
    fun startSelection() {
        closeMenu()
        val selector = selector ?: TranslateSelectionView(activity).also { view ->
            view.onSelectionFinished = { rect ->
                view.visibility = View.GONE
                if (rect != null) {
                    showHint(MR.strings.translate_in_progress)
                    translate(rect)
                }
            }
            selector = view
            container().addView(
                view,
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
        selector.visibility = View.VISIBLE
        selector.bringToFront()
        showHint(MR.strings.translate_selection_hint)
    }

    /**
     * Long-press on the translate button: translate everything currently on
     * screen, without drawing a selection.
     */
    fun translateFullPage(viewerWidth: Int, viewerHeight: Int) {
        closeMenu()
        showHint(MR.strings.translate_in_progress)
        when (val viewer = currentViewer()) {
            is PagerViewer -> viewer.currentPageHolder()?.let { holder ->
                holder.translateRegion(RectF(0f, 0f, holder.width.toFloat(), holder.height.toFloat()))
            }
            is WebtoonViewer -> viewer.translateRegionAt(
                RectF(0f, 0f, viewerWidth.toFloat(), viewerHeight.toFloat()),
            )
            else -> {}
        }
    }

    private fun translate(rect: RectF) {
        when (val viewer = currentViewer()) {
            is PagerViewer -> viewer.currentPageHolder()?.translateRegion(rect)
            is WebtoonViewer -> viewer.translateRegionAt(rect)
            else -> {}
        }
    }

    // endregion

    /** Which notice a metered service's quota event deserves. */
    fun quotaMessage(event: QuotaEvent): StringResource = when (event.kind) {
        QuotaKind.CLOUD_OCR -> when (event.level) {
            QuotaLevel.APPROACHING -> MR.strings.cloud_ocr_approaching_limit
            QuotaLevel.REACHED -> MR.strings.cloud_ocr_limit_reached
            QuotaLevel.RATE_LIMITED -> MR.strings.cloud_ocr_rate_limited
            QuotaLevel.FAILED -> MR.strings.cloud_ocr_failed
        }
        QuotaKind.AZURE_OCR -> when (event.level) {
            QuotaLevel.APPROACHING -> MR.strings.azure_ocr_approaching_limit
            QuotaLevel.REACHED -> MR.strings.azure_ocr_limit_reached
            QuotaLevel.RATE_LIMITED -> MR.strings.cloud_ocr_rate_limited
            QuotaLevel.FAILED -> MR.strings.azure_ocr_failed
        }
        QuotaKind.GEMINI_OCR -> when (event.level) {
            QuotaLevel.APPROACHING -> MR.strings.gemini_ocr_approaching_limit
            QuotaLevel.REACHED -> MR.strings.gemini_ocr_limit_reached
            QuotaLevel.RATE_LIMITED -> MR.strings.cloud_ocr_rate_limited
            QuotaLevel.FAILED -> MR.strings.gemini_ocr_failed
        }
        QuotaKind.DEEPL -> when (event.level) {
            QuotaLevel.APPROACHING -> MR.strings.deepl_approaching_limit
            QuotaLevel.REACHED -> MR.strings.deepl_limit_reached
            QuotaLevel.RATE_LIMITED -> MR.strings.cloud_ocr_rate_limited
            QuotaLevel.FAILED -> MR.strings.translate_selection_failed
        }
    }
}
