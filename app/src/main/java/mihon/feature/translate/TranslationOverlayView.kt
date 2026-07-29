package mihon.feature.translate

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.View
import androidx.core.graphics.withSave
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView

/**
 * Draws translated text blocks over a manga page. Must be a sibling of the
 * [SubsamplingScaleImageView] (same parent, same size) so that view
 * coordinates line up; block positions are mapped through
 * [SubsamplingScaleImageView.sourceToViewCoord] so they stay pinned to the
 * image through pan/zoom. The hosting view invalidates this overlay whenever
 * the image scale or center changes.
 */
class TranslationOverlayView(context: Context) : View(context) {

    /** Supplies the image view to map coordinates through; set by the host. */
    var ssivProvider: (() -> SubsamplingScaleImageView?)? = null

    private var translation: PageTranslation? = null

    private val density = context.resources.displayMetrics.density

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

    init {
        // Let taps/gestures fall through to the image view underneath
        isClickable = false
        isFocusable = false
    }

    fun setTranslation(translation: PageTranslation?) {
        this.translation = translation
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val translation = translation ?: return
        val ssiv = ssivProvider?.invoke() ?: return
        if (!ssiv.isReady) return

        val scaleX = ssiv.sWidth.toFloat() / translation.imageWidth
        val scaleY = ssiv.sHeight.toFloat() / translation.imageHeight
        val cornerRadius = 3 * density

        for (block in translation.blocks) {
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

            canvas.drawRoundRect(rect, cornerRadius, cornerRadius, boxPaint)
            canvas.drawRoundRect(rect, cornerRadius, cornerRadius, borderPaint)
            drawTextInRect(canvas, block.translatedText, rect)
        }
    }

    private fun drawTextInRect(canvas: Canvas, text: String, rect: RectF) {
        val padding = 2 * density
        val availableWidth = (rect.width() - 2 * padding).toInt()
        val availableHeight = rect.height() - 2 * padding
        if (availableWidth <= 0 || availableHeight <= 0) return

        // Shrink the text size until the block fits (or the floor is hit)
        var textSize = (rect.height() * 0.3f).coerceIn(MIN_TEXT_SP * density, MAX_TEXT_SP * density)
        var layout = buildLayout(text, textSize, availableWidth)
        while (layout.height > availableHeight && textSize > MIN_TEXT_SP * density) {
            textSize = (textSize * 0.85f).coerceAtLeast(MIN_TEXT_SP * density)
            layout = buildLayout(text, textSize, availableWidth)
        }

        canvas.withSave {
            clipRect(rect)
            val dy = rect.top + ((rect.height() - layout.height) / 2).coerceAtLeast(padding)
            translate(rect.left + padding, dy)
            layout.draw(this)
        }
    }

    private fun buildLayout(text: String, textSize: Float, width: Int): StaticLayout {
        textPaint.textSize = textSize
        return StaticLayout.Builder.obtain(text, 0, text.length, textPaint, width)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setIncludePad(false)
            .build()
    }

    companion object {
        private const val MIN_TEXT_SP = 9f
        private const val MAX_TEXT_SP = 22f
    }
}
