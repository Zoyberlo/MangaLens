package mihon.feature.translate

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.MotionEvent
import android.view.View
import androidx.core.graphics.withSave
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import kotlin.math.hypot

/**
 * Draws translated text blocks over a manga page. Must be a sibling of the
 * [SubsamplingScaleImageView] (same parent, same size) so that view
 * coordinates line up; block positions are mapped through
 * [SubsamplingScaleImageView.sourceToViewCoord] so they stay pinned to the
 * image through pan/zoom. The hosting view invalidates this overlay whenever
 * the image scale or center changes.
 *
 * Tapping a block selects it and shows a close button; tapping the close
 * button removes that block. Touches outside any block fall through to the
 * page view underneath.
 */
class TranslationOverlayView(context: Context) : View(context) {

    /** Supplies the image view to map coordinates through; set by the host. */
    var ssivProvider: (() -> SubsamplingScaleImageView?)? = null

    /**
     * Called when the user taps the + button of a selected but still
     * untranslated block (original-first mode) to translate it in place.
     */
    var onTranslateBlock: ((TranslatedBlock) -> Unit)? = null

    /**
     * Called when the user taps the pencil button of a selected block to fix
     * its recognized text in the bottom panel.
     */
    var onEditBlock: ((TranslatedBlock) -> Unit)? = null

    /**
     * Called when the user taps the retry button of a selected block to read
     * it again with the paid cloud recognizer.
     */
    var onCloudRetryBlock: ((TranslatedBlock) -> Unit)? = null

    /** Whether the cloud-retry button is offered; false without an API key. */
    var cloudRetryAvailable = false

    /**
     * The block currently being re-read in the cloud. Its retry button becomes
     * a spinner: these calls take seconds, and without it the page just sits
     * there looking like nothing happened.
     */
    private var busyBlock: TranslatedBlock? = null

    fun setBusyBlock(block: TranslatedBlock?) {
        busyBlock = block
        invalidate()
    }

    // Real icons rather than hand-drawn strokes, which read as a scribble and a
    // letter C at 24dp
    private val editIcon = androidx.appcompat.content.res.AppCompatResources
        .getDrawable(context, eu.kanade.tachiyomi.R.drawable.ic_edit_24dp)
        ?.mutate()
        ?.apply { setTint(0xFFFFFFFF.toInt()) }

    private val retryIcon = androidx.appcompat.content.res.AppCompatResources
        .getDrawable(context, eu.kanade.tachiyomi.R.drawable.ic_refresh_24dp)
        ?.mutate()
        ?.apply { setTint(0xFFFFFFFF.toInt()) }

    /**
     * Called with the currently picked word/phrase (taps on further words
     * extend the range), or null when the pick is cleared.
     */
    var onPhraseSelected: ((String?) -> Unit)? = null

    // Character range of the picked phrase inside the selected block's text
    private var phraseStart = -1
    private var phraseEnd = -1

    private val phrasePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x5942A5F5
        style = Paint.Style.FILL
    }

    private fun clearPhrase(notify: Boolean) {
        phraseStart = -1
        phraseEnd = -1
        if (notify) onPhraseSelected?.invoke(null)
    }

    /**
     * Updates the pick for a tapped word spanning [start, end): a tap on the
     * current pick clears it, a tap on a word directly next to it extends the
     * phrase, and anything else starts a fresh single-word pick. Never selects
     * words the user did not tap through.
     */
    private fun applyWordPick(start: Int, end: Int) {
        val text = selectedLayoutText
        when {
            phraseStart < 0 -> {
                phraseStart = start
                phraseEnd = end
            }
            start >= phraseStart && end <= phraseEnd -> {
                clearPhrase(notify = true)
                return
            }
            end <= phraseStart && text.isWordless(end, phraseStart) -> phraseStart = start
            start >= phraseEnd && text.isWordless(phraseEnd, start) -> phraseEnd = end
            else -> {
                phraseStart = start
                phraseEnd = end
            }
        }
        val phrase = text.substring(phraseStart, phraseEnd.coerceAtMost(text.length)).trim('\'', '’', '-', ' ')
        if (phrase.isNotBlank()) onPhraseSelected?.invoke(phrase)
    }

    /** True when the range holds only separators, i.e. the words are neighbours. */
    private fun String.isWordless(from: Int, to: Int): Boolean {
        if (from >= to) return true
        return (from until to.coerceAtMost(length)).none { this[it].isLetterOrDigit() }
    }

    /** Called after the user removes a block, with the remaining blocks. */
    var onBlocksChanged: ((List<TranslatedBlock>) -> Unit)? = null

    // Layout of the currently selected block, kept for word hit-testing
    private var selectedLayout: StaticLayout? = null
    private var selectedLayoutLeft = 0f
    private var selectedLayoutTop = 0f
    private var selectedLayoutText: String = ""

    private var imageWidth = 0
    private var imageHeight = 0
    private val blocks = mutableListOf<TranslatedBlock>()

    private var selectedBlock: TranslatedBlock? = null

    /** Screen rects of the blocks drawn in the last pass, newest layout. */
    private val hitRects = mutableListOf<Pair<RectF, TranslatedBlock>>()
    private var closeButtonCenterX = 0f
    private var closeButtonCenterY = 0f
    private var translateButtonCenterX = 0f
    private var translateButtonCenterY = 0f
    private var editButtonCenterX = 0f
    private var editButtonCenterY = 0f
    private var retryButtonCenterX = 0f
    private var retryButtonCenterY = 0f
    private var closeButtonVisible = false
    private var translateButtonVisible = false

    private var downTarget: TouchTarget? = null

    private val density = context.resources.displayMetrics.density
    private val touchSlop = android.view.ViewConfiguration.get(context).scaledTouchSlop

    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xF2FFFFFF.toInt()
        style = Paint.Style.FILL
    }

    // Warm tint marks a selected block, which shows the original text
    private val selectedBoxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xF2FFF3D8.toInt()
        style = Paint.Style.FILL
    }

    private val translateCirclePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF2E7D32.toInt()
        style = Paint.Style.FILL
    }

    private val editCirclePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF1565C0.toInt()
        style = Paint.Style.FILL
    }

    private val retryCirclePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF6A1B9A.toInt()
        style = Paint.Style.FILL
    }

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x33000000
        style = Paint.Style.STROKE
        strokeWidth = density
    }

    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF1A1A1A.toInt()
    }

    private val closeCirclePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF424242.toInt()
        style = Paint.Style.FILL
    }

    private val closeCrossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
        strokeCap = Paint.Cap.ROUND
    }

    fun setTranslation(translation: PageTranslation?) {
        blocks.clear()
        selectedBlock = null
        clearPhrase(notify = false)
        if (translation != null) {
            imageWidth = translation.imageWidth
            imageHeight = translation.imageHeight
            blocks += translation.blocks
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        hitRects.clear()
        closeButtonVisible = false
        translateButtonVisible = false
        selectedLayout = null
        if (blocks.isEmpty() || imageWidth <= 0 || imageHeight <= 0) return
        val ssiv = ssivProvider?.invoke() ?: return
        if (!ssiv.isReady) return

        val scaleX = ssiv.sWidth.toFloat() / imageWidth
        val scaleY = ssiv.sHeight.toFloat() / imageHeight
        val cornerRadius = 3 * density

        for (block in blocks) {
            val topLeft = ssiv.sourceToViewCoord(
                block.bounds.left * scaleX,
                block.bounds.top * scaleY,
            ) ?: continue
            val bottomRight = ssiv.sourceToViewCoord(
                block.bounds.right * scaleX,
                block.bounds.bottom * scaleY,
            ) ?: continue

            val rect = RectF(topLeft.x, topLeft.y, bottomRight.x, bottomRight.y)
            if (rect.width() < 8 * density || rect.height() < 8 * density) continue
            if (rect.right < 0 || rect.bottom < 0 || rect.left > width || rect.top > height) continue

            val isSelected = block === selectedBlock
            // A selected block reveals the original text instead of the
            // translation; untranslated blocks (original-first mode) always
            // show the original
            val text = if (isSelected || block.translatedText.isBlank()) block.sourceText else block.translatedText
            val drawnRect = drawBlock(canvas, text, rect, cornerRadius, isSelected)
            hitRects += drawnRect to block

            if (isSelected) {
                closeButtonCenterX = drawnRect.right
                closeButtonCenterY = drawnRect.top
                translateButtonCenterX = drawnRect.left
                translateButtonCenterY = drawnRect.top
                editButtonCenterX = drawnRect.left
                editButtonCenterY = drawnRect.bottom
                retryButtonCenterX = drawnRect.right
                retryButtonCenterY = drawnRect.bottom
                closeButtonVisible = true
                // The + (translate-in-place) button only applies to blocks
                // that are still untranslated (original-first mode)
                translateButtonVisible = block.translatedText.isBlank()
            }
        }

        if (closeButtonVisible) {
            val radius = CLOSE_RADIUS_DP * density
            val arm = radius * 0.45f

            canvas.drawCircle(closeButtonCenterX, closeButtonCenterY, radius, closeCirclePaint)
            canvas.drawLine(
                closeButtonCenterX - arm,
                closeButtonCenterY - arm,
                closeButtonCenterX + arm,
                closeButtonCenterY + arm,
                closeCrossPaint,
            )
            canvas.drawLine(
                closeButtonCenterX - arm,
                closeButtonCenterY + arm,
                closeButtonCenterX + arm,
                closeButtonCenterY - arm,
                closeCrossPaint,
            )

            if (translateButtonVisible) {
                // Translate-in-place button: green circle with a plus
                canvas.drawCircle(translateButtonCenterX, translateButtonCenterY, radius, translateCirclePaint)
                canvas.drawLine(
                    translateButtonCenterX - arm,
                    translateButtonCenterY,
                    translateButtonCenterX + arm,
                    translateButtonCenterY,
                    closeCrossPaint,
                )
                canvas.drawLine(
                    translateButtonCenterX,
                    translateButtonCenterY - arm,
                    translateButtonCenterX,
                    translateButtonCenterY + arm,
                    closeCrossPaint,
                )
            }

            drawEditButton(canvas, radius)
            if (cloudRetryAvailable || isBusy) drawRetryButton(canvas, radius)
        }
    }

    /** True while this overlay's selected block is waiting on a cloud read. */
    private val isBusy: Boolean
        get() = busyBlock != null && busyBlock === selectedBlock

    /** Edit button on the selected block's bottom-left corner. */
    private fun drawEditButton(canvas: Canvas, radius: Float) {
        canvas.drawCircle(editButtonCenterX, editButtonCenterY, radius, editCirclePaint)
        editIcon.drawCentered(canvas, editButtonCenterX, editButtonCenterY, radius)
    }

    /**
     * Cloud re-recognition on the selected block's bottom-right corner, or the
     * spinner that replaces it while that request is in flight.
     */
    private fun drawRetryButton(canvas: Canvas, radius: Float) {
        canvas.drawCircle(retryButtonCenterX, retryButtonCenterY, radius, retryCirclePaint)
        if (isBusy) {
            val arm = radius * 0.55f
            val sweepStart = (android.os.SystemClock.uptimeMillis() % SPIN_PERIOD_MS) * 360f / SPIN_PERIOD_MS
            canvas.drawArc(
                RectF(
                    retryButtonCenterX - arm,
                    retryButtonCenterY - arm,
                    retryButtonCenterX + arm,
                    retryButtonCenterY + arm,
                ),
                sweepStart,
                SPIN_SWEEP_DEGREES,
                false,
                closeCrossPaint,
            )
            postInvalidateOnAnimation()
        } else {
            retryIcon.drawCentered(canvas, retryButtonCenterX, retryButtonCenterY, radius)
        }
    }

    private fun android.graphics.drawable.Drawable?.drawCentered(
        canvas: Canvas,
        centerX: Float,
        centerY: Float,
        radius: Float,
    ) {
        val icon = this ?: return
        val half = (radius * ICON_SCALE).toInt()
        icon.setBounds(
            (centerX - half).toInt(),
            (centerY - half).toInt(),
            (centerX + half).toInt(),
            (centerY + half).toInt(),
        )
        icon.draw(canvas)
    }

    /**
     * Draws one translation box and returns the rect it occupied. The box
     * starts at the OCR bounds but grows (wider up to [MAX_WIDTH_GROWTH], and
     * as tall as needed) when the text doesn't fit at the minimum readable
     * size.
     */
    private fun drawBlock(
        canvas: Canvas,
        text: String,
        rect: RectF,
        cornerRadius: Float,
        isSelected: Boolean = false,
    ): RectF {
        val padding = 3 * density
        val minTextPx = MIN_TEXT_SP * density
        val availableWidth = (rect.width() - 2 * padding).toInt()
        val availableHeight = rect.height() - 2 * padding
        if (availableWidth <= 0) return rect

        // Pick the largest size that still fits, so text fills big bubbles
        var layout = buildLayout(text, minTextPx, availableWidth)
        var low = minTextPx
        var high = minOf(MAX_TEXT_SP * density, availableHeight)
        repeat(FIT_SEARCH_STEPS) {
            if (high - low < 0.5f) return@repeat
            val mid = (low + high) / 2
            val candidate = buildLayout(text, mid, availableWidth)
            if (candidate.height <= availableHeight) {
                low = mid
                layout = candidate
            } else {
                high = mid
            }
        }

        val drawRect: RectF
        if (layout.height > availableHeight) {
            // Doesn't fit even at minimum size: grow the box instead of clipping
            val margin = 4 * density
            val grownWidth = (rect.width() * MAX_WIDTH_GROWTH)
                .coerceAtMost(width - 2 * margin)
                .coerceAtLeast(rect.width())
            layout = buildLayout(text, minTextPx, (grownWidth - 2 * padding).toInt())
            val newHeight = layout.height + 2 * padding
            drawRect = RectF(
                rect.centerX() - grownWidth / 2,
                rect.centerY() - newHeight / 2,
                rect.centerX() + grownWidth / 2,
                rect.centerY() + newHeight / 2,
            )
            // Keep the grown box on screen horizontally
            if (drawRect.left < margin) drawRect.offset(margin - drawRect.left, 0f)
            if (drawRect.right > width - margin) drawRect.offset(width - margin - drawRect.right, 0f)
        } else {
            drawRect = rect
        }

        canvas.drawRoundRect(drawRect, cornerRadius, cornerRadius, if (isSelected) selectedBoxPaint else boxPaint)
        canvas.drawRoundRect(drawRect, cornerRadius, cornerRadius, borderPaint)
        val textLeft = drawRect.left + padding
        val textTop = drawRect.top + ((drawRect.height() - layout.height) / 2).coerceAtLeast(padding)
        if (isSelected) {
            selectedLayout = layout
            selectedLayoutLeft = textLeft
            selectedLayoutTop = textTop
            selectedLayoutText = text
        }
        canvas.withSave {
            clipRect(drawRect)
            translate(textLeft, textTop)
            if (isSelected) {
                drawPhraseHighlight(this, layout)
            }
            layout.draw(this)
        }
        return drawRect
    }

    private fun drawPhraseHighlight(canvas: Canvas, layout: StaticLayout) {
        if (phraseStart < 0 || phraseEnd <= phraseStart) return
        val end = phraseEnd.coerceAtMost(layout.text.length)
        val startLine = layout.getLineForOffset(phraseStart)
        val endLine = layout.getLineForOffset(end - 1)
        for (line in startLine..endLine) {
            val left = if (line == startLine) layout.getPrimaryHorizontal(phraseStart) else layout.getLineLeft(line)
            val right = if (line == endLine) layout.getPrimaryHorizontal(end) else layout.getLineRight(line)
            canvas.drawRoundRect(
                RectF(
                    minOf(left, right),
                    layout.getLineTop(line).toFloat(),
                    maxOf(left, right),
                    layout.getLineBottom(line).toFloat(),
                ),
                2 * density,
                2 * density,
                phrasePaint,
            )
        }
    }

    private fun buildLayout(text: String, textSize: Float, width: Int): StaticLayout {
        textPaint.textSize = textSize
        return StaticLayout.Builder.obtain(text, 0, text.length, textPaint, width.coerceAtLeast(1))
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setIncludePad(false)
            .build()
    }

    private sealed interface TouchTarget {
        data object CloseButton : TouchTarget
        data object TranslateButton : TouchTarget
        data object EditButton : TouchTarget
        data object RetryButton : TouchTarget
        data class Block(val block: TranslatedBlock) : TouchTarget
    }

    private fun hitTest(x: Float, y: Float): TouchTarget? {
        if (closeButtonVisible) {
            val touchRadius = (CLOSE_RADIUS_DP + 8) * density
            if (hypot(x - closeButtonCenterX, y - closeButtonCenterY) <= touchRadius) {
                return TouchTarget.CloseButton
            }
            if (translateButtonVisible &&
                hypot(x - translateButtonCenterX, y - translateButtonCenterY) <= touchRadius
            ) {
                return TouchTarget.TranslateButton
            }
            if (hypot(x - editButtonCenterX, y - editButtonCenterY) <= touchRadius) {
                return TouchTarget.EditButton
            }
            // Swallow taps while a request is in flight rather than firing a
            // second billed one
            if (cloudRetryAvailable &&
                !isBusy &&
                hypot(x - retryButtonCenterX, y - retryButtonCenterY) <= touchRadius
            ) {
                return TouchTarget.RetryButton
            }
        }
        return hitRects.lastOrNull { (rect, _) -> rect.contains(x, y) }
            ?.let { (_, block) -> TouchTarget.Block(block) }
    }

    /**
     * Finds the word at a touch position inside the selected block's rendered
     * text via layout hit-testing. Returns null when the touch misses the
     * text (padding, gaps between lines).
     */
    private fun wordRangeAt(x: Float, y: Float): IntRange? {
        val layout = selectedLayout ?: return null
        val text = selectedLayoutText
        val localY = (y - selectedLayoutTop).toInt()
        if (localY < 0 || localY > layout.height) return null
        val line = layout.getLineForVertical(localY)
        var offset = layout.getOffsetForHorizontal(line, x - selectedLayoutLeft).coerceIn(0, text.length)

        fun isWordChar(c: Char) = c.isLetterOrDigit() || c == '\'' || c == '’' || c == '-'

        // The offset is a cursor position; it may sit just after the tapped word
        if ((offset >= text.length || !isWordChar(text[offset])) &&
            offset > 0 &&
            isWordChar(text[offset - 1])
        ) {
            offset--
        }
        if (offset >= text.length || !isWordChar(text[offset])) return null

        var start = offset
        var end = offset
        while (start > 0 && isWordChar(text[start - 1])) start--
        while (end < text.length && isWordChar(text[end])) end++
        return if (end > start) start until end else null
    }

    private var downX = 0f
    private var downY = 0f

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val target = hitTest(event.x, event.y)
                if (target == null) {
                    // Deselect and let the page handle this touch
                    if (selectedBlock != null) {
                        selectedBlock = null
                        clearPhrase(notify = true)
                        invalidate()
                    }
                    return false
                }
                downTarget = target
                downX = event.x
                downY = event.y
                return true
            }
            MotionEvent.ACTION_UP -> {
                val target = downTarget ?: return false
                downTarget = null
                if (hypot(event.x - downX, event.y - downY) > touchSlop) return true
                if (hitTest(event.x, event.y) != target) return true
                when (target) {
                    is TouchTarget.CloseButton -> {
                        blocks.remove(selectedBlock)
                        selectedBlock = null
                        clearPhrase(notify = true)
                        onBlocksChanged?.invoke(blocks.toList())
                    }
                    is TouchTarget.TranslateButton -> {
                        selectedBlock?.let { onTranslateBlock?.invoke(it) }
                    }
                    is TouchTarget.EditButton -> {
                        selectedBlock?.let { onEditBlock?.invoke(it) }
                    }
                    is TouchTarget.RetryButton -> {
                        selectedBlock?.let { onCloudRetryBlock?.invoke(it) }
                    }
                    is TouchTarget.Block -> {
                        if (target.block === selectedBlock) {
                            // A tap picks that one word; tapping a word next to
                            // the pick extends it into a phrase; tapping the
                            // pick again clears it. A miss deselects the block.
                            val range = wordRangeAt(event.x, event.y)
                            if (range != null) {
                                applyWordPick(range.first, range.last + 1)
                            } else {
                                selectedBlock = null
                                clearPhrase(notify = true)
                            }
                        } else {
                            selectedBlock = target.block
                            clearPhrase(notify = true)
                        }
                    }
                }
                invalidate()
                performClick()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                downTarget = null
                return false
            }
        }
        return downTarget != null
    }

    companion object {
        private const val MIN_TEXT_SP = 11f
        private const val MAX_TEXT_SP = 40f
        private const val MAX_WIDTH_GROWTH = 1.6f
        private const val CLOSE_RADIUS_DP = 12f
        private const val FIT_SEARCH_STEPS = 8
        private const val ICON_SCALE = 0.62f
        private const val SPIN_PERIOD_MS = 1000L
        private const val SPIN_SWEEP_DEGREES = 100f
    }
}
