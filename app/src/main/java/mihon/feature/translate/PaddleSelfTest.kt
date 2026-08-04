package mihon.feature.translate

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface

/**
 * Renders a line of text and reads it back with [PaddleTextRecognizer].
 *
 * This exists because the model was verified on a desktop and the ONNX plumbing
 * only ever compiled — the device it ships to blocks instrumentation tests, and
 * this feature has already shipped silently doing nothing twice. One tap in
 * settings is the difference between "it should work" and knowing.
 */
class PaddleSelfTest(private val recognizer: PaddleTextRecognizer) {

    data class Result(val expected: String, val actual: String?, val engineAvailable: Boolean) {
        val passed: Boolean get() = actual == expected
    }

    fun run(): Result {
        if (!recognizer.isAvailable) return Result(SAMPLE, null, engineAvailable = false)
        val bitmap = render(SAMPLE)
        val actual = try {
            recognizer.recognize(bitmap)
        } finally {
            bitmap.recycle()
        }
        return Result(SAMPLE, actual, engineAvailable = true)
    }

    /** Black on white, like a speech balloon. */
    private fun render(text: String): Bitmap {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.SERIF
            textSize = TEXT_SIZE_PX
            color = Color.BLACK
        }
        val metrics = paint.fontMetrics
        val width = paint.measureText(text).toInt() + 2 * PADDING_PX
        val height = (metrics.bottom - metrics.top).toInt() + 2 * PADDING_PX
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
            Canvas(bitmap).apply {
                drawColor(Color.WHITE)
                drawText(text, PADDING_PX.toFloat(), PADDING_PX - metrics.top, paint)
            }
        }
    }

    private companion object {
        // The bubble ML Kit turned into "YOU COULVE duST VEARNED SWOLDS-
        // MANSHEP INSTEAD", apostrophe and all
        const val SAMPLE = "YOU COULD'VE JUST LEARNED SWORDSMANSHIP INSTEAD"
        const val TEXT_SIZE_PX = 64f
        const val PADDING_PX = 20
    }
}
