package eu.kanade.tachiyomi.ui.reader.viewer.pager

import android.annotation.SuppressLint
import android.content.Context
import android.view.LayoutInflater
import androidx.core.view.isVisible
import eu.kanade.presentation.util.formattedMessage
import eu.kanade.tachiyomi.databinding.ReaderErrorBinding
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.model.InsertPage
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderPageImageView
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderProgressIndicator
import eu.kanade.tachiyomi.ui.webview.WebViewActivity
import eu.kanade.tachiyomi.util.system.toast
import eu.kanade.tachiyomi.widget.ViewPagerAdapter
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import logcat.LogPriority
import mihon.feature.translate.PageTranslator
import okio.Buffer
import okio.BufferedSource
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.core.common.util.system.logcat
import tachiyomi.i18n.MR
import uy.kohesive.injekt.injectLazy

/**
 * View of the ViewPager that contains a page of a chapter.
 */
@SuppressLint("ViewConstructor")
class PagerPageHolder(
    readerThemedContext: Context,
    val viewer: PagerViewer,
    val page: ReaderPage,
) : ReaderPageImageView(readerThemedContext), ViewPagerAdapter.PositionableView {

    /**
     * Item that identifies this view. Needed by the adapter to not recreate views.
     */
    override val item
        get() = page

    /**
     * Loading progress bar to indicate the current progress.
     */
    private var progressIndicator: ReaderProgressIndicator? = null // = ReaderProgressIndicator(readerThemedContext)

    /**
     * Error layout to show when the image fails to load.
     */
    private var errorLayout: ReaderErrorBinding? = null

    private val scope = MainScope()

    private val pageTranslator: PageTranslator by injectLazy()

    private val readerPreferences: eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences by injectLazy()

    /**
     * Job for loading the page and processing changes to the page's status.
     */
    private var loadJob: Job? = null

    private val pageKey: String
        get() = "${page.chapter.chapter.id}:${page.index}"

    init {
        onTranslationBlocksChanged = { blocks -> pageTranslator.replaceOverlay(pageKey, blocks) }
        onTranslationPhraseSelected = { phrase -> viewer.activity.onTranslatePhraseSelected(phrase) }
        onTranslationBlockTranslateRequested = { block -> translateBlock(block) }
        onTranslationBlockEditRequested = { block -> editBlock(block) }
        onTranslationBlockCloudRetryRequested = { block -> cloudRetryBlock(block) }
        translationCloudRetryAvailable = pageTranslator.isRetryEngineUsable
        loadJob = scope.launch { loadPageAndProcessStatus() }
    }

    /** Original-first mode: translates one block in place. */
    private fun translateBlock(block: mihon.feature.translate.TranslatedBlock) {
        scope.launchIO {
            val translated = pageTranslator.translateSingle(block.sourceText)
            withUIContext {
                if (translated != null) {
                    pageTranslator.updateOverlayBlock(pageKey, block, translated)?.let { setTranslation(it) }
                } else {
                    viewer.activity.toast(MR.strings.translate_selection_failed)
                }
            }
        }
    }

    /**
     * Opens the block's recognized text in the bottom panel; whatever the user
     * translates there replaces this block in place.
     */
    private fun editBlock(block: mihon.feature.translate.TranslatedBlock) {
        val key = pageKey
        viewer.activity.openTextEditor(block.sourceText) { source, translation ->
            pageTranslator.updateOverlayBlock(key, block, translation, source)?.let { setTranslation(it) }
        }
    }

    /** Reads one block again with the paid cloud recognizer, on request. */
    private fun cloudRetryBlock(block: mihon.feature.translate.TranslatedBlock) {
        val key = pageKey
        val streamFn = page.stream ?: return
        setTranslationBusyBlock(block)
        scope.launchIO {
            val result = try {
                val bytes = streamFn().use { process(item, Buffer().readFrom(it)) }.readByteArray()
                pageTranslator.retryBlockWithCloud(bytes, block)
            } catch (e: Exception) {
                logcat(LogPriority.WARN, e)
                mihon.feature.translate.CloudRetryResult.Failed
            }
            withUIContext {
                setTranslationBusyBlock(null)
                applyCloudRetry(key, block, result)
            }
        }
    }

    private fun applyCloudRetry(
        key: String,
        block: mihon.feature.translate.TranslatedBlock,
        result: mihon.feature.translate.CloudRetryResult,
    ) {
        when (result) {
            is mihon.feature.translate.CloudRetryResult.Success ->
                pageTranslator.updateOverlayBlock(key, block, result.translation, result.sourceText)
                    ?.let { setTranslation(it) }
            is mihon.feature.translate.CloudRetryResult.RecognizedOnly -> {
                pageTranslator.updateOverlayBlock(key, block, "", result.sourceText)
                    ?.let { setTranslation(it) }
                viewer.activity.toast(MR.strings.translate_selection_failed)
            }
            mihon.feature.translate.CloudRetryResult.NoText ->
                viewer.activity.toast(MR.strings.translate_selection_no_text)
            mihon.feature.translate.CloudRetryResult.NotConfigured ->
                viewer.activity.toast(MR.strings.cloud_ocr_not_configured)
            mihon.feature.translate.CloudRetryResult.Failed ->
                viewer.activity.toast(MR.strings.translate_selection_failed)
        }
    }

    /**
     * Called when this view is detached from the window. Unsubscribes any active subscription.
     */
    @SuppressLint("ClickableViewAccessibility")
    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        loadJob?.cancel()
        loadJob = null
    }

    private fun initProgressIndicator() {
        if (progressIndicator == null) {
            progressIndicator = ReaderProgressIndicator(context)
            addView(progressIndicator)
        }
    }

    /**
     * Loads the page and processes changes to the page's status.
     *
     * Returns immediately if the page has no PageLoader.
     * Otherwise, this function does not return. It will continue to process status changes until
     * the Job is cancelled.
     */
    private suspend fun loadPageAndProcessStatus() {
        val loader = page.chapter.pageLoader ?: return

        supervisorScope {
            launchIO {
                loader.loadPage(page)
            }
            page.statusFlow.collectLatest { state ->
                when (state) {
                    Page.State.Queue -> setQueued()
                    Page.State.LoadPage -> setLoading()
                    Page.State.DownloadImage -> {
                        setDownloading()
                        page.progressFlow.collectLatest { value ->
                            progressIndicator?.setProgress(value)
                        }
                    }
                    Page.State.Ready -> setImage()
                    is Page.State.Error -> setError(state.error)
                }
            }
        }
    }

    /**
     * Called when the page is queued.
     */
    private fun setQueued() {
        initProgressIndicator()
        progressIndicator?.show()
        removeErrorLayout()
    }

    /**
     * Called when the page is loading.
     */
    private fun setLoading() {
        initProgressIndicator()
        progressIndicator?.show()
        removeErrorLayout()
    }

    /**
     * Called when the page is downloading.
     */
    private fun setDownloading() {
        initProgressIndicator()
        progressIndicator?.show()
        removeErrorLayout()
    }

    /**
     * Called when the page is ready.
     */
    private suspend fun setImage() {
        progressIndicator?.setProgress(0)

        val streamFn = page.stream ?: return

        try {
            val (source, isAnimated, background) = withIOContext {
                val source = streamFn().use { process(item, Buffer().readFrom(it)) }
                val isAnimated = ImageUtil.isAnimatedAndSupported(source)
                val background = if (!isAnimated && viewer.config.automaticBackground) {
                    ImageUtil.chooseBackground(context, source.peek().inputStream())
                } else {
                    null
                }
                Triple(source, isAnimated, background)
            }
            withUIContext {
                setImage(
                    source,
                    isAnimated,
                    Config(
                        zoomDuration = viewer.config.doubleTapAnimDuration,
                        minimumScaleType = viewer.config.imageScaleType,
                        cropBorders = viewer.config.imageCropBorders,
                        zoomStartPosition = viewer.config.imageZoomType,
                        landscapeZoom = viewer.config.landscapeZoom,
                    ),
                )
                if (!isAnimated) {
                    pageBackground = background
                }
                removeErrorLayout()
                // Restore translations shown on this page earlier in the session
                pageTranslator.cachedOverlay(pageKey)?.let { setTranslation(it) }
            }
        } catch (e: Throwable) {
            logcat(LogPriority.ERROR, e)
            withUIContext {
                setError(e)
            }
        }
    }

    private fun process(page: ReaderPage, imageSource: BufferedSource): BufferedSource {
        if (viewer.config.dualPageRotateToFit) {
            return rotateDualPage(imageSource)
        }

        if (!viewer.config.dualPageSplit) {
            return imageSource
        }

        if (page is InsertPage) {
            return splitInHalf(imageSource)
        }

        val isDoublePage = ImageUtil.isWideImage(imageSource)
        if (!isDoublePage) {
            return imageSource
        }

        onPageSplit(page)

        return splitInHalf(imageSource)
    }

    private fun rotateDualPage(imageSource: BufferedSource): BufferedSource {
        val isDoublePage = ImageUtil.isWideImage(imageSource)
        return if (isDoublePage) {
            val rotation = if (viewer.config.dualPageRotateToFitInvert) -90f else 90f
            ImageUtil.rotateImage(imageSource, rotation)
        } else {
            imageSource
        }
    }

    private fun splitInHalf(imageSource: BufferedSource): BufferedSource {
        var side = when {
            viewer is L2RPagerViewer && page is InsertPage -> ImageUtil.Side.RIGHT
            viewer !is L2RPagerViewer && page is InsertPage -> ImageUtil.Side.LEFT
            viewer is L2RPagerViewer && page !is InsertPage -> ImageUtil.Side.LEFT
            viewer !is L2RPagerViewer && page !is InsertPage -> ImageUtil.Side.RIGHT
            else -> error("We should choose a side!")
        }

        if (viewer.config.dualPageInvert) {
            side = when (side) {
                ImageUtil.Side.RIGHT -> ImageUtil.Side.LEFT
                ImageUtil.Side.LEFT -> ImageUtil.Side.RIGHT
            }
        }

        return ImageUtil.splitInHalf(imageSource, side)
    }

    private fun onPageSplit(page: ReaderPage) {
        val newPage = InsertPage(page)
        viewer.onPageSplit(page, newPage)
    }

    /**
     * Translates a user-selected area. [viewRect] is in this view's coordinate
     * space (the selection overlay matches it). Shows the result as an overlay
     * or a toast when nothing was recognized.
     */
    fun translateRegion(viewRect: android.graphics.RectF) {
        val sourceRect = viewToSourceRect(viewRect) ?: return
        val sourceWidth = sourceWidth() ?: return
        val streamFn = page.stream ?: return
        scope.launchIO {
            val result = try {
                val bytes = streamFn().use { process(item, Buffer().readFrom(it)) }.readByteArray()
                val region = android.graphics.Rect(
                    sourceRect.left.toInt(),
                    sourceRect.top.toInt(),
                    sourceRect.right.toInt(),
                    sourceRect.bottom.toInt(),
                )
                pageTranslator.translateRegion(bytes, region, sourceWidth)
            } catch (e: Exception) {
                logcat(LogPriority.WARN, e)
                mihon.feature.translate.RegionTranslateResult.Failed
            }
            withUIContext {
                when (result) {
                    is mihon.feature.translate.RegionTranslateResult.Success -> {
                        // Which engine actually read the page. Silent fallback
                        // to ML Kit is why "I see no difference" was impossible
                        // to tell apart from "the engine never ran".
                        if (result.fellBack) {
                            viewer.activity.toast(
                                viewer.activity.stringResource(
                                    MR.strings.translate_engine_fallback,
                                    result.requestedEngine.displayName,
                                    result.usedEngine.displayName,
                                ),
                            )
                        }
                        if (readerPreferences.translateResultDisplay.get() ==
                            mihon.feature.translate.TranslateResultDisplay.PANEL
                        ) {
                            viewer.activity.showTranslationResult(result.translation)
                        } else {
                            setTranslation(pageTranslator.storeOverlay(pageKey, result.translation))
                        }
                    }
                    mihon.feature.translate.RegionTranslateResult.NoText ->
                        viewer.activity.toast(MR.strings.translate_selection_no_text)
                    mihon.feature.translate.RegionTranslateResult.Failed ->
                        viewer.activity.toast(MR.strings.translate_selection_failed)
                }
            }
        }
    }

    /**
     * Called when the page has an error.
     */
    private fun setError(error: Throwable?) {
        progressIndicator?.hide()
        showErrorLayout(error)
    }

    override fun onImageLoaded() {
        super.onImageLoaded()
        progressIndicator?.hide()
    }

    /**
     * Called when an image fails to decode.
     */
    override fun onImageLoadError(error: Throwable?) {
        super.onImageLoadError(error)
        setError(error)
    }

    /**
     * Called when an image is zoomed in/out.
     */
    override fun onScaleChanged(newScale: Float) {
        super.onScaleChanged(newScale)
        viewer.activity.hideMenu()
    }

    private fun showErrorLayout(error: Throwable?): ReaderErrorBinding {
        if (errorLayout == null) {
            errorLayout = ReaderErrorBinding.inflate(LayoutInflater.from(context), this, true)
            errorLayout?.actionRetry?.viewer = viewer
            errorLayout?.actionRetry?.setOnClickListener {
                page.chapter.pageLoader?.retryPage(page)
            }
        }

        val imageUrl = page.imageUrl
        errorLayout?.actionOpenInWebView?.isVisible = imageUrl != null
        if (imageUrl != null) {
            if (imageUrl.startsWith("http", true)) {
                errorLayout?.actionOpenInWebView?.viewer = viewer
                errorLayout?.actionOpenInWebView?.setOnClickListener {
                    val sourceId = viewer.activity.viewModel.manga?.source

                    val intent = WebViewActivity.newIntent(context, imageUrl, sourceId)
                    context.startActivity(intent)
                }
            }
        }

        errorLayout?.errorMessage?.text = with(context) { error?.formattedMessage }
            ?: context.stringResource(MR.strings.decode_image_error)

        errorLayout?.root?.isVisible = true
        return errorLayout!!
    }

    /**
     * Removes the decode error layout from the holder, if found.
     */
    private fun removeErrorLayout() {
        errorLayout?.root?.isVisible = false
        errorLayout = null
    }
}
