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
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import tachiyomi.core.common.util.system.logcat
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * Text recognition through the user's own cloud accounts, for the lettering
 * the on-device model cannot read. Every engine here is opt-in (it does
 * nothing without a key), metered against a monthly limit the user sets, and
 * returns null rather than throwing on any failure — callers keep whatever the
 * on-device pass produced, so a bad key or a dead network only costs quality.
 */
class CloudTextRecognizer(
    private val networkHelper: NetworkHelper,
    private val json: Json,
    private val readerPreferences: ReaderPreferences,
    quotaNotifier: QuotaNotifier,
) {

    private val client by lazy {
        networkHelper.client.newBuilder()
            .callTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    private val quotas = mapOf(
        OcrEngine.GOOGLE_VISION to QuotaTracker(
            QuotaKind.CLOUD_OCR,
            readerPreferences.visionMonthlyLimit,
            readerPreferences.visionUsageCount,
            readerPreferences.visionUsagePeriod,
            quotaNotifier,
        ),
        OcrEngine.AZURE_READ to QuotaTracker(
            QuotaKind.AZURE_OCR,
            readerPreferences.azureMonthlyLimit,
            readerPreferences.azureUsageCount,
            readerPreferences.azureUsagePeriod,
            quotaNotifier,
        ),
        OcrEngine.GEMINI to QuotaTracker(
            QuotaKind.GEMINI_OCR,
            readerPreferences.geminiMonthlyLimit,
            readerPreferences.geminiUsageCount,
            readerPreferences.geminiUsagePeriod,
            quotaNotifier,
        ),
    )

    /** True once the engine has everything it needs to run. */
    fun isConfigured(engine: OcrEngine): Boolean = when (engine) {
        OcrEngine.ON_DEVICE -> true
        OcrEngine.GOOGLE_VISION -> readerPreferences.visionApiKey.get().isNotBlank()
        OcrEngine.AZURE_READ -> readerPreferences.azureApiKey.get().isNotBlank() &&
            readerPreferences.azureEndpoint.get().isNotBlank()
        OcrEngine.GEMINI -> readerPreferences.geminiApiKey.get().isNotBlank()
    }

    /** Requests already spent this month, for the settings subtitle. */
    fun usedThisMonth(engine: OcrEngine): Int = quotas[engine]?.used() ?: 0

    fun monthlyLimit(engine: OcrEngine): Int = quotas[engine]?.limit() ?: 0

    /**
     * Recognizes [bitmap] with [engine], or returns null when the engine is
     * not configured, out of quota, or the request failed.
     */
    suspend fun recognize(
        engine: OcrEngine,
        bitmap: Bitmap,
        language: TranslationSourceLanguage,
    ): List<RecognizedBlock>? {
        if (engine == OcrEngine.ON_DEVICE || !isConfigured(engine)) return null
        val quota = quotas[engine] ?: return null
        if (!quota.canSpend(1)) return null

        return try {
            val blocks = when (engine) {
                OcrEngine.GOOGLE_VISION -> requestGoogleVision(bitmap, language)
                OcrEngine.AZURE_READ -> requestAzureRead(bitmap)
                OcrEngine.GEMINI -> requestGemini(bitmap, language)
                OcrEngine.ON_DEVICE -> emptyList()
            }
            quota.record(1)
            blocks
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "Cloud text recognition failed on ${engine.displayName}" }
            quota.reportFailure()
            null
        }
    }

    private fun Bitmap.toJpegBytes(): ByteArray = ByteArrayOutputStream().use { stream ->
        compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, stream)
        stream.toByteArray()
    }

    // region Google Cloud Vision

    private suspend fun requestGoogleVision(
        bitmap: Bitmap,
        language: TranslationSourceLanguage,
    ): List<RecognizedBlock> {
        val encoded = Base64.encodeToString(bitmap.toJpegBytes(), Base64.NO_WRAP)
        val payload = buildJsonObject {
            putJsonArray("requests") {
                add(
                    buildJsonObject {
                        putJsonObject("image") { put("content", encoded) }
                        putJsonArray("features") {
                            add(buildJsonObject { put("type", "DOCUMENT_TEXT_DETECTION") })
                        }
                        putJsonObject("imageContext") {
                            put("languageHints", buildJsonArray { add(language.langCode) })
                        }
                    },
                )
            }
        }

        val apiKey = readerPreferences.visionApiKey.get().trim()
        val request = POST(
            url = "https://vision.googleapis.com/v1/images:annotate?key=$apiKey",
            body = payload.toString().toRequestBody(jsonMime),
        )
        return parseGoogleVision(client.newCall(request).awaitSuccess().body.string())
    }

    /**
     * Pulls block rectangles and their text out of the fullTextAnnotation
     * tree (page -> block -> paragraph -> word -> symbol).
     */
    private fun parseGoogleVision(body: String): List<RecognizedBlock> {
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

    // endregion

    // region Azure AI Vision

    /**
     * Image Analysis 4.0 with the `read` feature: synchronous, unlike the
     * older Read API's submit-then-poll dance, and posts raw image bytes
     * rather than base64.
     */
    private suspend fun requestAzureRead(bitmap: Bitmap): List<RecognizedBlock> {
        val endpoint = readerPreferences.azureEndpoint.get().trim().trimEnd('/')
        val apiKey = readerPreferences.azureApiKey.get().trim()
        val request = POST(
            url = "$endpoint/computervision/imageanalysis:analyze?api-version=2024-02-01&features=read",
            headers = Headers.headersOf("Ocp-Apim-Subscription-Key", apiKey),
            body = bitmap.toJpegBytes().toRequestBody(OCTET_STREAM),
        )
        return parseAzureRead(client.newCall(request).awaitSuccess().body.string())
    }

    /**
     * Azure returns lines rather than paragraphs. That matches how the
     * on-device model behaves, so the usual block merging turns them back into
     * whole bubbles downstream.
     */
    private fun parseAzureRead(body: String): List<RecognizedBlock> {
        val root = json.parseToJsonElement(body).jsonObject
        root["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content?.let { error ->
            throw IllegalStateException(error)
        }
        val blocks = root["readResult"]?.jsonObject?.get("blocks")?.jsonArray ?: return emptyList()

        return blocks.flatMap { blockElement ->
            blockElement.jsonObject["lines"]?.jsonArray.orEmpty().mapNotNull { lineElement ->
                val line = lineElement.jsonObject
                val text = line["text"]?.jsonPrimitive?.content?.trim().orEmpty()
                if (text.isEmpty()) return@mapNotNull null
                val polygon = line["boundingPolygon"]?.jsonArray ?: return@mapNotNull null
                val xs = polygon.map { it.jsonObject["x"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0 }
                val ys = polygon.map { it.jsonObject["y"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0 }
                if (xs.isEmpty() || ys.isEmpty()) return@mapNotNull null

                RecognizedBlock(text, Rect(xs.min(), ys.min(), xs.max(), ys.max()))
            }
        }
    }

    // endregion

    // region Gemini

    /**
     * A vision model transcribing the crop it is given. It reads stylised
     * lettering far better than a dedicated OCR engine because it reads for
     * meaning, but it returns no usable geometry — hence the single block
     * spanning the whole bitmap, and why it is offered for block retries only.
     */
    private suspend fun requestGemini(
        bitmap: Bitmap,
        language: TranslationSourceLanguage,
    ): List<RecognizedBlock> {
        val encoded = Base64.encodeToString(bitmap.toJpegBytes(), Base64.NO_WRAP)
        val languageName = language.name.lowercase().replaceFirstChar { it.uppercase() }
        val payload = buildJsonObject {
            putJsonArray("contents") {
                add(
                    buildJsonObject {
                        putJsonArray("parts") {
                            add(buildJsonObject { put("text", geminiPrompt(languageName)) })
                            add(
                                buildJsonObject {
                                    putJsonObject("inline_data") {
                                        put("mime_type", "image/jpeg")
                                        put("data", encoded)
                                    }
                                },
                            )
                        }
                    },
                )
            }
            putJsonObject("generationConfig") {
                put("temperature", 0)
            }
        }

        val apiKey = readerPreferences.geminiApiKey.get().trim()
        val model = readerPreferences.geminiModel.get().trim().ifEmpty { DEFAULT_GEMINI_MODEL }
        val request = POST(
            url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey",
            body = payload.toString().toRequestBody(jsonMime),
        )
        val text = parseGemini(client.newCall(request).awaitSuccess().body.string())
        if (text.isBlank()) return emptyList()
        return listOf(RecognizedBlock(text, Rect(0, 0, bitmap.width, bitmap.height)))
    }

    private fun geminiPrompt(languageName: String) =
        "Transcribe the $languageName text in this comic panel exactly as written, in reading order. " +
            "Join words split across lines. Do not translate, explain, or add anything. " +
            "Reply with the transcription alone, or with nothing at all if there is no text."

    private fun parseGemini(body: String): String {
        val root = json.parseToJsonElement(body).jsonObject
        root["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content?.let { error ->
            throw IllegalStateException(error)
        }
        return root["candidates"]?.jsonArray?.firstOrNull()?.jsonObject
            ?.get("content")?.jsonObject
            ?.get("parts")?.jsonArray.orEmpty()
            .mapNotNull { it.jsonObject["text"]?.jsonPrimitive?.content }
            .joinToString("")
            .trim()
    }

    // endregion

    private companion object {
        const val JPEG_QUALITY = 90
        const val DEFAULT_GEMINI_MODEL = "gemini-2.5-flash-lite"
        val OCTET_STREAM = "application/octet-stream".toMediaType()
    }
}
