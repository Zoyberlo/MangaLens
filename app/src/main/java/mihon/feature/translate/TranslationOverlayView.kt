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

    /** Called when the user taps the save button on a selected block. */
    var onSaveBlock: ((TranslatedBlock) -> Unit)? = null

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

    /** Called after the user removes a block, with the remaining blocks. */
    var onBlocksChanged: ((List<TranslatedBlock>) -> Unit)? = null

    /** Supplies the lowercased saved-word set for known-word highlighting. */
    var savedWordsProvider: (() -> Set<String>)? = null

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
    private var saveButtonCenterX = 0f
    private var saveButtonCenterY = 0f
    private var closeButtonVisible = false

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

    private val saveCirclePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF2E7D32.toInt()
        style = Paint.Style.FILL
    }

    // Marks words already saved to the vocabulary in the original text
    private val savedWordPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x59FFC107
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
                saveButtonCenterX = drawnRect.left
                saveButtonCenterY = drawnRect.top
                closeButtonVisible = true
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

            // Save-to-dictionary button: green circle with a plus
            canvas.drawCircle(saveButtonCenterX, saveButtonCenterY, radius, saveCirclePaint)
            canvas.drawLine(
                saveButtonCenterX - arm,
                saveButtonCenterY,
                saveButtonCenterX + arm,
                saveButtonCenterY,
                closeCrossPaint,
            )
            canvas.drawLine(
                saveButtonCenterX,
                saveButtonCenterY - arm,
                saveButtonCenterX,
                saveButtonCenterY + arm,
                closeCrossPaint,
            )
        }
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
                drawSavedWordHighlights(this, layout, text)
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

    /**
     * Draws a highlight behind every word of [text] that is already in the
     * saved vocabulary, so learners see their progress on the page.
     */
    private fun drawSavedWordHighlights(canvas: Canvas, layout: StaticLayout, text: String) {
        val saved = savedWordsProvider?.invoke().orEmpty()
        if (saved.isEmpty()) return

        fun isWordChar(c: Char) = c.isLetterOrDigit() || c == '\'' || c == '’' || c == '-'

        var index = 0
        while (index < text.length) {
            if (!isWordChar(text[index])) {
                index++
                continue
            }
            var end = index
            while (end < text.length && isWordChar(text[end])) end++
            val word = text.substring(index, end).trim('\'', '’', '-')
            if (word.lowercase() in saved) {
                val startLine = layout.getLineForOffset(index)
                if (startLine == layout.getLineForOffset(end - 1)) {
                    val x1 = layout.getPrimaryHorizontal(index)
                    val x2 = layout.getPrimaryHorizontal(end)
                    canvas.drawRoundRect(
                        RectF(
                            minOf(x1, x2),
                            layout.getLineTop(startLine).toFloat(),
                            maxOf(x1, x2),
                            layout.getLineBottom(startLine).toFloat(),
                        ),
                        2 * density,
                        2 * density,
                        savedWordPaint,
                    )
                }
            }
            index = end
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
        data object SaveButton : TouchTarget
        data class Block(val block: TranslatedBlock) : TouchTarget
    }

    private fun hitTest(x: Float, y: Float): TouchTarget? {
        if (closeButtonVisible) {
            val touchRadius = (CLOSE_RADIUS_DP + 8) * density
            if (hypot(x - closeButtonCenterX, y - closeButtonCenterY) <= touchRadius) {
                return TouchTarget.CloseButton
            }
            if (hypot(x - saveButtonCenterX, y - saveButtonCenterY) <= touchRadius) {
                return TouchTarget.SaveButton
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
                    is TouchTarget.SaveButton -> {
                        selectedBlock?.let { onSaveBlock?.invoke(it) }
                    }
                    is TouchTarget.Block -> {
                        if (target.block === selectedBlock) {
                            // Taps on words pick a word; further taps extend it
                            // to a phrase. A miss toggles back to the translation.
                            val range = wordRangeAt(event.x, event.y)
                            if (range != null) {
                                if (phraseStart < 0) {
                                    phraseStart = range.first
                                    phraseEnd = range.last + 1
                                } else {
                                    phraseStart = minOf(phraseStart, range.first)
                                    phraseEnd = maxOf(phraseEnd, range.last + 1)
                                }
                                val phrase = selectedLayoutText
                                    .substring(phraseStart, phraseEnd.coerceAtMost(selectedLayoutText.length))
                                    .trim('\'', '’', '-', ' ')
                                if (phrase.isNotBlank()) onPhraseSelected?.invoke(phrase)
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
    }
}
