package mihon.feature.translate

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View

/**
 * Full-screen rubber-band selector for "translate this area". While visible it
 * consumes all touches; dragging draws a selection rectangle and releasing
 * reports it through [onSelectionFinished] (null for a tap/too-small drag,
 * which callers should treat as a cancel).
 */
class TranslateSelectionView(context: Context) : View(context) {

    var onSelectionFinished: ((RectF?) -> Unit)? = null

    private var start: PointF? = null
    private var current: PointF? = null

    private val dimPaint = Paint().apply {
        color = 0x66000000
        style = Paint.Style.FILL
    }

    private val clearPaint = Paint().apply {
        xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR)
    }

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2 * context.resources.displayMetrics.density
    }

    init {
        // PorterDuff.CLEAR needs an offscreen layer to punch a hole in the dim
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    private fun selectionRect(): RectF? {
        val start = start ?: return null
        val current = current ?: return null
        return RectF(
            minOf(start.x, current.x),
            minOf(start.y, current.y),
            maxOf(start.x, current.x),
            maxOf(start.y, current.y),
        )
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                start = PointF(event.x, event.y)
                current = PointF(event.x, event.y)
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                current = PointF(event.x, event.y)
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val rect = selectionRect()
                start = null
                current = null
                invalidate()
                val minSize = 24 * context.resources.displayMetrics.density
                if (event.actionMasked == MotionEvent.ACTION_UP &&
                    rect != null &&
                    rect.width() >= minSize &&
                    rect.height() >= minSize
                ) {
                    onSelectionFinished?.invoke(rect)
                } else {
                    onSelectionFinished?.invoke(null)
                }
            }
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dimPaint)
        val rect = selectionRect() ?: return
        canvas.drawRect(rect, clearPaint)
        canvas.drawRect(rect, borderPaint)
    }
}
