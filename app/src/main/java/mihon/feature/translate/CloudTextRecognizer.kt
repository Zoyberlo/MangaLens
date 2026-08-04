package mihon.feature.translate

import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Base64
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.await
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
import okhttp3.Call
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

    private val lastErrors = mutableMapOf<OcrEngine, String>()

    /**
     * Why [engine] last failed, verbatim from the service. A generic "it did
     * not work" is useless when the cause is invariably something only the
     * user can fix — a disabled API, a retired model name, a wrong endpoint.
     */
    fun lastError(engine: OcrEngine): String? = lastErrors[engine]

    /**
     * Runs a real (billed) request against [engine] with a throwaway image.
     * Returns null when it worked, or the service's own error message.
     */
    suspend fun testEngine(engine: OcrEngine): String? {
        if (!isConfigured(engine)) return "Not configured"
        val bitmap = Bitmap.createBitmap(TEST_BITMAP_PX, TEST_BITMAP_PX, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.WHITE)
        return try {
            when (engine) {
                OcrEngine.GOOGLE_VISION -> requestGoogleVision(bitmap, TranslationSourceLanguage.ENGLISH)
                OcrEngine.AZURE_READ -> requestAzureRead(bitmap)
                OcrEngine.GEMINI -> requestGemini(bitmap, TranslationSourceLanguage.ENGLISH)
                OcrEngine.ON_DEVICE -> emptyList()
            }
            lastErrors.remove(engine)
            null
        } catch (e: Exception) {
            e.readableMessage().also { lastErrors[engine] = it }
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * Lists the Gemini models this key may call for generateContent. Google
     * retires model ids often enough that hardcoding one strands users on a
     * 404 with no way to discover the replacement.
     */
    suspend fun listGeminiModels(): Result<List<String>> {
        val apiKey = readerPreferences.geminiApiKey.get().trim()
        if (apiKey.isEmpty()) return Result.failure(IllegalStateException("No API key"))
        return try {
            val request = GET("https://generativelanguage.googleapis.com/v1beta/models?key=$apiKey&pageSize=200")
            val body = client.newCall(request).awaitBody()
            val root = json.parseToJsonElement(body).jsonObject
            root["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content?.let {
                throw IllegalStateException(it)
            }
            val models = root["models"]?.jsonArray.orEmpty().mapNotNull { element ->
                val model = element.jsonObject
                val methods = model["supportedGenerationMethods"]?.jsonArray.orEmpty()
                    .mapNotNull { it.jsonPrimitive.content }
                if ("generateContent" !in methods) return@mapNotNull null
                model["name"]?.jsonPrimitive?.content?.removePrefix("models/")
            }
            Result.success(models)
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "Listing Gemini models failed" }
            lastErrors[OcrEngine.GEMINI] = e.readableMessage()
            Result.failure(e)
        }
    }

    private fun Exception.readableMessage(): String {
        val direct = message?.takeIf { it.isNotBlank() } ?: this::class.simpleName.orEmpty()
        return direct.take(MAX_ERROR_CHARS)
    }

    /**
     * Reads the body whether or not the call succeeded, and puts the service's
     * own explanation into the exception. `awaitSuccess` closes the response
     * and throws bare `HttpException(code)` — which discards exactly the text
     * that says *why*: a disabled API, a retired model id, a wrong endpoint.
     * All three services return `{"error":{"message":…}}`.
     */
    private suspend fun Call.awaitBody(): String {
        val response = await()
        val body = response.use { it.body.string() }
        if (!response.isSuccessful) {
            throw IllegalStateException("HTTP ${response.code}: ${body.errorMessage()}")
        }
        return body
    }

    private fun String.errorMessage(): String {
        val parsed = try {
            json.parseToJsonElement(this).jsonObject["error"]?.jsonObject
                ?.get("message")?.jsonPrimitive?.content
        } catch (_: Exception) {
            null
        }
        return (parsed ?: trim()).take(MAX_ERROR_CHARS)
    }

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
            lastErrors.remove(engine)
            blocks
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "Cloud text recognition failed on ${engine.displayName}" }
            lastErrors[engine] = e.readableMessage()
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
        return parseGoogleVision(client.newCall(request).awaitBody())
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
        return parseAzureRead(client.newCall(request).awaitBody())
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
        return try {
            requestGemini(resolveGeminiModel(), bitmap, language, noThinking = true)
        } catch (e: IllegalStateException) {
            val message = e.message.orEmpty()
            when {
                // "This model is no longer available to new users" arrives as a
                // 404 at request time. Forget the stored id, ask the API what
                // exists now, and try once more rather than dead-ending.
                HTTP_NOT_FOUND in message -> {
                    readerPreferences.geminiModel.set("")
                    requestGemini(resolveGeminiModel(), bitmap, language, noThinking = true)
                }
                // Models predating thinkingConfig reject the field outright
                HTTP_BAD_REQUEST in message ->
                    requestGemini(resolveGeminiModel(), bitmap, language, noThinking = false)
                else -> throw e
            }
        }
    }

    /**
     * The model id to call. An empty preference means "work it out", which is
     * the default: Google retires ids on its own schedule, so anything baked
     * into the app eventually 404s with no way to discover the replacement.
     * The resolved id is stored so the extra round trip happens once.
     */
    private suspend fun resolveGeminiModel(): String {
        readerPreferences.geminiModel.get().trim().takeIf { it.isNotEmpty() }?.let { return it }
        val resolved = listGeminiModels().getOrThrow().let(::preferredGeminiModel)
            ?: throw IllegalStateException("This key cannot call any usable Gemini model")
        readerPreferences.geminiModel.set(resolved)
        return resolved
    }

    private suspend fun requestGemini(
        model: String,
        bitmap: Bitmap,
        language: TranslationSourceLanguage,
        noThinking: Boolean,
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
                put("maxOutputTokens", MAX_OUTPUT_TOKENS)
                if (noThinking) {
                    // Flash models reason before answering by default, which
                    // costs seconds the user spends staring at a bubble. There
                    // is nothing to reason about in "copy out this text".
                    putJsonObject("thinkingConfig") { put("thinkingBudget", 0) }
                }
            }
        }

        val apiKey = readerPreferences.geminiApiKey.get().trim()
        val request = POST(
            url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey",
            body = payload.toString().toRequestBody(jsonMime),
        )
        val text = parseGemini(client.newCall(request).awaitBody())
        if (text.isBlank()) return emptyList()
        return listOf(RecognizedBlock(text, Rect(0, 0, bitmap.width, bitmap.height)))
    }

    private fun geminiPrompt(languageName: String) =
        "Transcribe the $languageName text in this comic panel exactly as written, in reading order. " +
            "Join words split across lines. Do not translate, explain, or add anything. " +
            "Reply with the transcription alone, or with nothing at all if there is no text."

    /**
     * Picks the best model for reading a speech bubble out of whatever this key
     * can actually call. Ranked on substrings rather than a known list, so a
     * generation Google has not shipped yet still sorts correctly:
     *
     * - **flash** first — strong enough for stylised lettering and cheap;
     * - **flash-lite** next — weaker, but its free daily allowance is larger;
     * - **pro** last — best reader, but the smallest free allowance by far, and
     *   slow for something a user is waiting on.
     *
     * Within a tier the newest generation wins, and a stable id beats a
     * preview one.
     */
    internal fun preferredGeminiModel(models: List<String>): String? = models
        .filter { it.startsWith("gemini-") }
        .filterNot { model -> NON_TEXT_MODEL_HINTS.any { it in model } }
        // Every selector is "bigger is better", so the maximum is the pick
        .maxWithOrNull(
            compareBy(
                { geminiTierRank(it) },
                { geminiGeneration(it) },
                { if (PREVIEW_HINTS.any { hint -> hint in it }) 0 else 1 },
                { -it.length },
            ),
        )

    /** `flash-lite` has to be tested before `flash`, being a superstring of it. */
    private fun geminiTierRank(model: String): Int = when {
        "flash-lite" in model -> 2
        "flash" in model -> 3
        "pro" in model -> 1
        else -> 0
    }

    /** Generation in e.g. `gemini-2.5-flash`, scaled by ten; 0 when unreadable. */
    private fun geminiGeneration(model: String): Int {
        val version = model.removePrefix("gemini-").takeWhile { it.isDigit() || it == '.' }
        return ((version.toFloatOrNull() ?: 0f) * 10).toInt()
    }

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
        const val MAX_ERROR_CHARS = 400
        const val TEST_BITMAP_PX = 64
        const val HTTP_NOT_FOUND = "HTTP 404"
        const val HTTP_BAD_REQUEST = "HTTP 400"
        const val MAX_OUTPUT_TOKENS = 1024
        val OCTET_STREAM = "application/octet-stream".toMediaType()

        // Models that answer generateContent but cannot read a picture of text
        val NON_TEXT_MODEL_HINTS = listOf("embedding", "tts", "audio", "image", "veo", "imagen")
        val PREVIEW_HINTS = listOf("preview", "-exp", "experimental")
    }
}
