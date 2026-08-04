package mihon.feature.translate

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Runs on the device, against the shipped model and the real ONNX Runtime.
 *
 * Everything about this feature was verified on a desktop and then shipped
 * untested on hardware, twice over — a dictionary that silently answered "no"
 * to every word made it into two releases that way. Desktop agreement proves
 * the model; only this proves the app.
 */
class PaddleTextRecognizerTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val recognizer = PaddleTextRecognizer(context)

    /** White background, black text — a stand-in for a speech balloon. */
    private fun render(text: String, typeface: Typeface, sizePx: Float = 64f): Bitmap {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.typeface = typeface
            textSize = sizePx
            color = Color.BLACK
        }
        val width = paint.measureText(text).toInt() + 40
        val metrics = paint.fontMetrics
        val height = (metrics.bottom - metrics.top).toInt() + 40
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
            Canvas(bitmap).apply {
                drawColor(Color.WHITE)
                drawText(text, 20f, 20f - metrics.top, paint)
            }
        }
    }

    @Test
    fun modelLoadsOnDevice() {
        assertTrue("ONNX Runtime or the model asset failed to load", recognizer.isAvailable)
    }

    @Test
    fun readsPlainText() {
        val text = "HUNTER"
        assertEquals(text, recognizer.recognize(render(text, Typeface.DEFAULT)))
    }

    @Test
    fun readsTheSentenceThatStartedAllThis() {
        // The bubble ML Kit turned into "YOU COULVE duST VEARNED SWOLDS-
        // MANSHEP INSTEAD", apostrophe and all
        val text = "YOU COULD'VE JUST LEARNED SWORDSMANSHIP INSTEAD"
        assertEquals(text, recognizer.recognize(render(text, Typeface.SERIF)))
    }

    @Test
    fun readsLowercaseAndMixedCase() {
        val text = "You could have learned this instead"
        assertEquals(text, recognizer.recognize(render(text, Typeface.DEFAULT)))
    }

    @Test
    fun returnsNullForAnEmptyCrop() {
        val blank = Bitmap.createBitmap(120, 48, Bitmap.Config.ARGB_8888)
        blank.eraseColor(Color.WHITE)
        assertEquals(null, recognizer.recognize(blank))
    }

    @Test
    fun survivesACropTooSmallToContainText() {
        val tiny = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        tiny.eraseColor(Color.WHITE)
        assertEquals(null, recognizer.recognize(tiny))
    }
}
