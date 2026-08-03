package mihon.feature.translate

import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Base64
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.network.jsonMime
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import logcat.LogPriority
import okhttp3.RequestBody.Companion.toRequestBody
import tachiyomi.core.common.preference.getAndSet
import tachiyomi.core.common.util.system.logcat
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * Text recognition through Google Cloud Vision — the same model family that
 * powers Lens, and far better than the on-device model on stylised comic
 * lettering. Opt-in: it only runs when the user supplies their own API key,
 * and it counts requests against a monthly limit they control, because the
 * Google free tier is finite and overruns are billed to them.
 */
class CloudTextRecognizer(
    private val networkHelper: NetworkHelper,
    private val json: Json,
    private val readerPreferences: ReaderPreferences,
    private val quotaNotifier: QuotaNotifier,
) {

    private val client by lazy {
        networkHelper.client.newBuilder()
            .callTimeout(25, TimeUnit.SECONDS)
            .build()
    }

    val isConfigured: Boolean
        get() = readerPreferences.visionApiKey.get().isNotBlank()

    /** Requests already spent in the current month. */
    fun usedThisMonth(): Int {
        rolloverIfNewMonth()
        return readerPreferences.visionUsageCount.get()
    }

    fun monthlyLimit(): Int = readerPreferences.visionMonthlyLimit.get()

    /**
     * Recognizes [bitmap] in the cloud, or returns null when it is not
     * configured, out of quota or the request failed — callers fall back to
     * the on-device recognizer, so a failure only costs quality.
     */
    suspend fun recognize(bitmap: Bitmap, language: TranslationSourceLanguage): List<RecognizedBlock>? {
        val apiKey = readerPreferences.visionApiKey.get().trim()
        if (apiKey.isEmpty()) return null

        rolloverIfNewMonth()
        val used = readerPreferences.visionUsageCount.get()
        val limit = readerPreferences.visionMonthlyLimit.get()
        if (limit in 1..used) {
            quotaNotifier.report(QuotaKind.CLOUD_OCR, QuotaLevel.REACHED)
            return null
        }

        return try {
            val blocks = request(bitmap, apiKey, language)
            readerPreferences.visionUsageCount.getAndSet { it + 1 }
            val spent = used + 1
            if (limit > 0 && spent >= limit) {
                quotaNotifier.report(QuotaKind.CLOUD_OCR, QuotaLevel.REACHED)
            } else if (limit > 0 && spent >= (limit * QUOTA_APPROACHING_RATIO).toInt()) {
                quotaNotifier.report(QuotaKind.CLOUD_OCR, QuotaLevel.APPROACHING)
            }
            blocks
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "Cloud text recognition failed" }
            quotaNotifier.report(QuotaKind.CLOUD_OCR, QuotaLevel.FAILED)
            null
        }
    }

    private suspend fun request(
        bitmap: Bitmap,
        apiKey: String,
        language: TranslationSourceLanguage,
    ): List<RecognizedBlock> {
        val encoded = ByteArrayOutputStream().use { stream ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, stream)
            Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
        }

        val payload = buildJsonObject {
            putJsonArray("requests") {
                add(
                    buildJsonObject {
                        putJsonObject("image") { put("content", encoded) }
                        putJsonArray("features") {
                            add(buildJsonObject { put("type", "DOCUMENT_TEXT_DETECTION") })
                        }
                        putJsonObject("imageContext") {
                            put(
                                "languageHints",
                                buildJsonArray { add(language.langCode) },
                            )
                        }
                    },
                )
            }
        }

        val request = POST(
            url = "https://vision.googleapis.com/v1/images:annotate?key=$apiKey",
            body = payload.toString().toRequestBody(jsonMime),
        )
        val body = client.newCall(request).awaitSuccess().body.string()
        return parseBlocks(body)
    }

    /**
     * Pulls block rectangles and their text out of the fullTextAnnotation
     * tree (page -> block -> paragraph -> word -> symbol).
     */
    private fun parseBlocks(body: String): List<RecognizedBlock> {
        val root = json.parseToJsonElement(body).jsonObject
        val response = root["responses"]?.jsonArray?.firstOrNull()?.jsonObject ?: return emptyList()
        response["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content?.let { error ->
            throw IllegalStateException(error)
        }
        val pages = response["fullTextAnnotation"]?.jsonObject?.get("pages")?.jsonArray ?: return emptyList()

        return pages.flatMap { page ->
            page.jsonObject["blocks"]?.jsonArray.orEmpty().mapNotNull { blockElement ->
                val block = blockElement.jsonObject
                val vertices = block["boundingBox"]?.jsonObject?.get("vertices")?.jsonArray ?: return@mapNotNull null
                val xs = vertices.map { it.jsonObject["x"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0 }
                val ys = vertices.map { it.jsonObject["y"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0 }
                if (xs.isEmpty() || ys.isEmpty()) return@mapNotNull null

                val text = block["paragraphs"]?.jsonArray.orEmpty().joinToString(" ") { paragraphElement ->
                    paragraphElement.jsonObject["words"]?.jsonArray.orEmpty().joinToString(" ") { wordElement ->
                        wordElement.jsonObject["symbols"]?.jsonArray.orEmpty().joinToString("") { symbolElement ->
                            symbolElement.jsonObject["text"]?.jsonPrimitive?.content.orEmpty()
                        }
                    }
                }.trim()
                if (text.isEmpty()) return@mapNotNull null

                RecognizedBlock(text, Rect(xs.min(), ys.min(), xs.max(), ys.max()))
            }
        }
    }

    /** Usage is per calendar month, matching how Google's free tier resets. */
    private fun rolloverIfNewMonth() {
        val period = currentQuotaPeriod()
        if (readerPreferences.visionUsagePeriod.get() != period) {
            readerPreferences.visionUsagePeriod.set(period)
            readerPreferences.visionUsageCount.set(0)
        }
    }

    private companion object {
        const val JPEG_QUALITY = 90
    }
}
