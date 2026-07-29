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

    private var imageWidth = 0
    private var imageHeight = 0
    private val blocks = mutableListOf<TranslatedBlock>()

    private var selectedBlock: TranslatedBlock? = null

    /** Screen rects of the blocks drawn in the last pass, newest layout. */
    private val hitRects = mutableListOf<Pair<RectF, TranslatedBlock>>()
    private var closeButtonCenterX = 0f
    private var closeButtonCenterY = 0f
    private var closeButtonVisible = false

    private var downTarget: TouchTarget? = null

    private val density = context.resources.displayMetrics.density
    private val touchSlop = android.view.ViewConfiguration.get(context).scaledTouchSlop

    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xF2FFFFFF.toInt()
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

            val drawnRect = drawBlock(canvas, block.translatedText, rect, cornerRadius)
            hitRects += drawnRect to block

            if (block === selectedBlock) {
                closeButtonCenterX = drawnRect.right
                closeButtonCenterY = drawnRect.top
                closeButtonVisible = true
            }
        }

        if (closeButtonVisible) {
            val radius = CLOSE_RADIUS_DP * density
            canvas.drawCircle(closeButtonCenterX, closeButtonCenterY, radius, closeCirclePaint)
            val arm = radius * 0.45f
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
        }
    }

    /**
     * Draws one translation box and returns the rect it occupied. The box
     * starts at the OCR bounds but grows (wider up to [MAX_WIDTH_GROWTH], and
     * as tall as needed) when the text doesn't fit at the minimum readable
     * size.
     */
    private fun drawBlock(canvas: Canvas, text: String, rect: RectF, cornerRadius: Float): RectF {
        val padding = 3 * density
        val minTextPx = MIN_TEXT_SP * density
        val availableWidth = (rect.width() - 2 * padding).toInt()
        if (availableWidth <= 0) return rect

        var textSize = (rect.height() * 0.3f).coerceIn(minTextPx, MAX_TEXT_SP * density)
        var layout = buildLayout(text, textSize, availableWidth)
        while (layout.height > rect.height() - 2 * padding && textSize > minTextPx) {
            textSize = (textSize * 0.85f).coerceAtLeast(minTextPx)
            layout = buildLayout(text, textSize, availableWidth)
        }

        val drawRect: RectF
        if (layout.height > rect.height() - 2 * padding) {
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

        canvas.drawRoundRect(drawRect, cornerRadius, cornerRadius, boxPaint)
        canvas.drawRoundRect(drawRect, cornerRadius, cornerRadius, borderPaint)
        canvas.withSave {
            clipRect(drawRect)
            val dy = drawRect.top + ((drawRect.height() - layout.height) / 2).coerceAtLeast(padding)
            translate(drawRect.left + padding, dy)
            layout.draw(this)
        }
        return drawRect
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
        data class Block(val block: TranslatedBlock) : TouchTarget
    }

    private fun hitTest(x: Float, y: Float): TouchTarget? {
        if (closeButtonVisible) {
            val touchRadius = (CLOSE_RADIUS_DP + 8) * density
            if (hypot(x - closeButtonCenterX, y - closeButtonCenterY) <= touchRadius) {
                return TouchTarget.CloseButton
            }
        }
        return hitRects.lastOrNull { (rect, _) -> rect.contains(x, y) }
            ?.let { (_, block) -> TouchTarget.Block(block) }
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
                    }
                    is TouchTarget.Block -> selectedBlock = target.block
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
        private const val MAX_TEXT_SP = 22f
        private const val MAX_WIDTH_GROWTH = 1.6f
        private const val CLOSE_RADIUS_DP = 12f
    }
}
