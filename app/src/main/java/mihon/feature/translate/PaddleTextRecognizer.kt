package mihon.feature.translate

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import androidx.core.graphics.scale
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.nio.FloatBuffer

/**
 * PP-OCRv5 English text recognition, on device and offline.
 *
 * ML Kit reads hand-lettered comic fonts badly and systematically — L as V, R
 * as Z — and no amount of dictionary repair fixes a word whose misreading is
 * itself a word. This model reads that lettering; verified before it was
 * written, by running the same preprocessing and CTC decode against text
 * rendered in a handwritten face, which came back exactly right where ML Kit
 * would have mangled it.
 *
 * **Recognition only.** It takes a crop that already holds one line of text —
 * ML Kit finds those boxes perfectly well, and it is the reading that fails, so
 * there is no reason to carry PP-OCR's detection stage too.
 */
class PaddleTextRecognizer(private val context: Context) {

    private val session: OrtSession? by lazy { loadSession() }
    private val characters: List<String> by lazy { loadCharacters() }

    val isAvailable: Boolean get() = session != null && characters.size > 1

    /** Builds the session off the critical path; safe to call repeatedly. */
    fun preload() {
        session
        characters
    }

    /**
     * Reads a single line of text out of [line], or null when the model is
     * unavailable or the crop is unusable. Callers keep the ML Kit reading in
     * that case, so a failure only costs quality.
     */
    fun recognize(line: Bitmap): String? {
        val session = this.session ?: return null
        if (line.width < MIN_CROP_PX || line.height < MIN_CROP_PX) return null

        return try {
            val width = (line.width * INPUT_HEIGHT / line.height)
                .coerceIn(MIN_INPUT_WIDTH, MAX_INPUT_WIDTH)
            val scaled = line.scale(width, INPUT_HEIGHT)
            val input = try {
                toNchwTensor(scaled, width)
            } finally {
                if (scaled !== line) scaled.recycle()
            }

            input.use { tensor ->
                session.run(mapOf(session.inputNames.first() to tensor)).use { result ->
                    @Suppress("UNCHECKED_CAST")
                    val logits = (result[0].value as Array<Array<FloatArray>>)[0]
                    decodeCtc(logits).takeIf { it.isNotBlank() }
                }
            }
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "PaddleOCR recognition failed" }
            null
        }
    }

    /**
     * NCHW float tensor, pixels mapped to [-1, 1]. This is PaddleOCR's own
     * recognition normalization; anything else produces confident nonsense.
     */
    private fun toNchwTensor(bitmap: Bitmap, width: Int): OnnxTensor {
        val pixels = IntArray(width * INPUT_HEIGHT)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, INPUT_HEIGHT)

        val plane = width * INPUT_HEIGHT
        val buffer = FloatBuffer.allocate(3 * plane)
        val data = FloatArray(3 * plane)
        for (index in pixels.indices) {
            val pixel = pixels[index]
            data[index] = (((pixel shr 16 and 0xFF) / 255f) - 0.5f) / 0.5f
            data[plane + index] = (((pixel shr 8 and 0xFF) / 255f) - 0.5f) / 0.5f
            data[2 * plane + index] = (((pixel and 0xFF) / 255f) - 0.5f) / 0.5f
        }
        buffer.put(data).rewind()

        return OnnxTensor.createTensor(
            environment,
            buffer,
            longArrayOf(1, 3, INPUT_HEIGHT.toLong(), width.toLong()),
        )
    }

    /**
     * Greedy CTC: take the best class per timestep, then drop blanks and runs
     * of the same class. Index 0 is the blank, which is why [characters] is
     * built with it at the front.
     */
    private fun decodeCtc(logits: Array<FloatArray>): String {
        val builder = StringBuilder()
        var previous = -1
        for (step in logits) {
            var best = 0
            for (index in step.indices) {
                if (step[index] > step[best]) best = index
            }
            if (best != BLANK_INDEX && best != previous && best < characters.size) {
                builder.append(characters[best])
            }
            previous = best
        }
        return builder.toString().trim()
    }

    private fun loadSession(): OrtSession? = try {
        val model = context.assets.open(MODEL_ASSET).use { it.readBytes() }
        environment.createSession(model, OrtSession.SessionOptions())
    } catch (e: Throwable) {
        logcat(LogPriority.WARN, e) { "PaddleOCR model unavailable" }
        null
    }

    /**
     * PaddleOCR's label order: a blank at index 0, then the dictionary, then a
     * space. The model emits 438 classes for a 436-entry dictionary, which is
     * what pins those two extras down.
     */
    private fun loadCharacters(): List<String> = try {
        val dictionary = context.assets.open(DICT_ASSET).bufferedReader().readLines()
        buildList {
            add("")
            addAll(dictionary)
            add(" ")
        }
    } catch (e: Exception) {
        logcat(LogPriority.WARN, e) { "PaddleOCR dictionary unavailable" }
        emptyList()
    }

    private companion object {
        const val MODEL_ASSET = "paddle_rec_en.onnx"
        const val DICT_ASSET = "paddle_rec_en_dict.txt"

        // The model accepts height 48 only. Its own published config says 32,
        // which is wrong and throws at load — checked against the real file.
        const val INPUT_HEIGHT = 48
        const val MIN_INPUT_WIDTH = 16
        const val MAX_INPUT_WIDTH = 1600
        const val MIN_CROP_PX = 4
        const val BLANK_INDEX = 0

        val environment: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }
    }
}
