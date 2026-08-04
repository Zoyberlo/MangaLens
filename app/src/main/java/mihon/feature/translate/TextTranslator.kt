package mihon.feature.translate

import android.util.LruCache
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.network.parseAs
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import logcat.LogPriority
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import tachiyomi.core.common.util.system.logcat

/**
 * Text translation with an in-memory cache.
 * Strategy: Lingva instances (Google Translate proxy) first, MyMemory as fallback.
 */
class TextTranslator(
    private val context: android.app.Application,
    private val networkHelper: NetworkHelper,
    private val json: Json,
    private val readerPreferences: eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences,
    quotaNotifier: QuotaNotifier,
) {

    // DeepL bills characters rather than requests, so this is the one tracker
    // whose cost is the length of the text
    private val deeplQuota = QuotaTracker(
        QuotaKind.DEEPL,
        readerPreferences.deeplMonthlyCharLimit,
        readerPreferences.deeplUsageChars,
        readerPreferences.deeplUsagePeriod,
        quotaNotifier,
    )

    /** Characters already spent on DeepL this month. */
    fun deeplUsedThisMonth(): Int = deeplQuota.used()

    // Short call timeout: a dead Lingva instance should fail fast so the
    // fallback chain stays responsive
    private val client by lazy {
        networkHelper.client.newBuilder()
            .callTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    private val cache = LruCache<String, String>(1000)

    // Persistent cache so re-reading a chapter works instantly and offline
    private val diskCache by lazy {
        try {
            com.jakewharton.disklrucache.DiskLruCache.open(
                java.io.File(context.cacheDir, "translations"),
                DISK_CACHE_VERSION,
                1,
                DISK_CACHE_SIZE_BYTES,
            )
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "Failed to open translation disk cache" }
            null
        }
    }

    private fun diskKey(key: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-1").digest(key.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun diskGet(key: String): String? = try {
        diskCache?.get(diskKey(key))?.use { it.getString(0) }
    } catch (e: Exception) {
        null
    }

    private fun diskPut(key: String, value: String) {
        try {
            diskCache?.edit(diskKey(key))?.apply {
                set(0, value)
                commit()
            }
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "Failed to write translation disk cache" }
        }
    }

    /**
     * The backend that served the most recent AUTO-mode translation;
     * surfaced in settings as "Auto (Google)".
     */
    val lastAutoProvider = kotlinx.coroutines.flow.MutableStateFlow<TranslationProvider?>(null)

    // Instances that recently failed are skipped for a cooldown period
    private val instanceBackoffUntil = mutableMapOf<String, Long>()

    /**
     * [context] is optional surrounding text (the other bubbles of the page);
     * only DeepL consumes it, but callers may always pass it. It is not part
     * of the cache key.
     */
    suspend fun translate(text: String, from: String, to: String, context: String? = null): String? {
        val trimmed = OcrText.normalizeForTranslation(text)
        if (trimmed.isEmpty() || from == to) return null

        val key = "$from:$to:${trimmed.lowercase()}"
        cache.get(key)?.let { return it }
        diskGet(key)?.let {
            cache.put(key, it)
            return it
        }

        val selectedProvider = readerPreferences.translationProvider.get()
            .let {
                // A DeepL selection that cannot run — no key, or the monthly
                // character budget is spent — falls back to the automatic chain
                if (it == TranslationProvider.DEEPL &&
                    (readerPreferences.deeplApiKey.get().isBlank() || !deeplQuota.canSpend(trimmed.length))
                ) {
                    TranslationProvider.AUTO
                } else {
                    it
                }
            }

        val result = when (selectedProvider) {
            TranslationProvider.AUTO -> {
                translateViaGoogle(trimmed, from, to)
                    ?.also { lastAutoProvider.value = TranslationProvider.GOOGLE }
                    ?: translateViaLingva(trimmed, from, to)
                        ?.also { lastAutoProvider.value = TranslationProvider.LINGVA }
                    ?: translateViaMyMemory(trimmed, from, to)
                        ?.also { lastAutoProvider.value = TranslationProvider.MYMEMORY }
            }
            TranslationProvider.GOOGLE -> translateViaGoogle(trimmed, from, to)
            TranslationProvider.DEEPL -> translateViaDeepL(trimmed, from, to, context)
            TranslationProvider.LINGVA -> translateViaLingva(trimmed, from, to)
            TranslationProvider.MYMEMORY -> translateViaMyMemory(trimmed, from, to)
        }

        if (result != null) {
            cache.put(key, result)
            diskPut(key, result)
        }
        return result
    }

    /**
     * Word/phrase lookup with alternatives: the main translation first, then
     * dictionary variants from Google's gtx endpoint (dt=bd). Falls back to
     * just the main translation when the dictionary section is unavailable.
     */
    suspend fun lookupVariants(text: String, from: String, to: String): List<String> {
        val variants = mutableListOf<String>()
        translate(text, from, to)?.let { variants += it }
        try {
            val url = "https://translate.googleapis.com/translate_a/single".toHttpUrl().newBuilder()
                .addQueryParameter("client", "gtx")
                .addQueryParameter("sl", from)
                .addQueryParameter("tl", to)
                .addQueryParameter("dt", "bd")
                .addQueryParameter("q", OcrText.normalizeForTranslation(text))
                .build()
            val body = client.newCall(GET(url)).awaitSuccess().body.string()
            val root = json.parseToJsonElement(body).jsonArray
            (root.getOrNull(1) as? kotlinx.serialization.json.JsonArray)?.forEach { entry ->
                (entry.jsonArray.getOrNull(1) as? kotlinx.serialization.json.JsonArray)?.forEach { term ->
                    term.jsonPrimitive.content.takeIf { it.isNotBlank() }?.let { variants += it }
                }
            }
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "Variant lookup failed" }
        }
        return variants.distinctBy { it.lowercase() }.take(MAX_VARIANTS)
    }

    /**
     * Unofficial keyless Google Translate endpoint (the same one browser
     * extensions use). Fast and reliable, but not an official API.
     */
    private suspend fun translateViaGoogle(text: String, from: String, to: String): String? {
        return try {
            val url = "https://translate.googleapis.com/translate_a/single".toHttpUrl().newBuilder()
                .addQueryParameter("client", "gtx")
                .addQueryParameter("sl", from)
                .addQueryParameter("tl", to)
                .addQueryParameter("dt", "t")
                .addQueryParameter("q", text)
                .build()
            val body = client.newCall(GET(url)).awaitSuccess().body.string()
            json.parseToJsonElement(body)
                .jsonArray[0]
                .jsonArray
                .joinToString("") { segment -> segment.jsonArray[0].jsonPrimitive.content }
                .takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "Google translation failed" }
            null
        }
    }

    /**
     * Official DeepL API; needs a user-supplied key (reader settings).
     * Free-tier keys end in ":fx" and use the api-free host.
     */
    private suspend fun translateViaDeepL(text: String, from: String, to: String, context: String? = null): String? {
        val apiKey = readerPreferences.deeplApiKey.get().trim()
        if (apiKey.isEmpty()) {
            logcat(LogPriority.WARN) { "DeepL selected but no API key is set" }
            return null
        }
        if (!deeplQuota.canSpend(text.length)) return null
        return try {
            val host = if (apiKey.endsWith(":fx")) "api-free.deepl.com" else "api.deepl.com"
            val body = FormBody.Builder()
                .add("text", text)
                .add("source_lang", from.uppercase())
                .add("target_lang", to.uppercase())
                .apply { if (!context.isNullOrBlank()) add("context", context) }
                .build()
            val request = POST("https://$host/v2/translate", body = body)
                .newBuilder()
                .header("Authorization", "DeepL-Auth-Key $apiKey")
                .build()
            val response = client.newCall(request).awaitSuccess()
            with(json) { response.parseAs<DeepLResponse>() }
                .translations
                ?.firstOrNull()
                ?.text
                ?.takeIf { it.isNotBlank() }
                ?.also { deeplQuota.record(text.length) }
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "DeepL translation failed" }
            null
        }
    }

    private suspend fun translateViaLingva(text: String, from: String, to: String): String? {
        for (instance in LINGVA_INSTANCES) {
            val backoffUntil = synchronized(instanceBackoffUntil) { instanceBackoffUntil[instance] ?: 0L }
            if (System.currentTimeMillis() < backoffUntil) continue
            try {
                val url = instance.toHttpUrl().newBuilder()
                    .addPathSegment("api")
                    .addPathSegment("v1")
                    .addPathSegment(from)
                    .addPathSegment(to)
                    .addPathSegment(text)
                    .build()
                val response = client.newCall(GET(url)).awaitSuccess()
                val translation = with(json) { response.parseAs<LingvaResponse>() }.translation
                if (!translation.isNullOrBlank()) {
                    return translation
                }
            } catch (e: Exception) {
                logcat(LogPriority.WARN, e) { "Lingva translation failed on $instance" }
                synchronized(instanceBackoffUntil) {
                    instanceBackoffUntil[instance] = System.currentTimeMillis() + INSTANCE_BACKOFF_MS
                }
            }
        }
        return null
    }

    private suspend fun translateViaMyMemory(text: String, from: String, to: String): String? {
        return try {
            val url = "https://api.mymemory.translated.net/get".toHttpUrl().newBuilder()
                .addQueryParameter("q", text)
                .addQueryParameter("langpair", "$from|$to")
                .build()
            val response = client.newCall(GET(url)).awaitSuccess()
            with(json) { response.parseAs<MyMemoryResponse>() }
                .responseData
                ?.translatedText
                ?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "MyMemory translation failed" }
            null
        }
    }

    @Serializable
    private data class DeepLResponse(
        val translations: List<Translation>? = null,
    ) {
        @Serializable
        data class Translation(
            val text: String? = null,
        )
    }

    @Serializable
    private data class LingvaResponse(
        val translation: String? = null,
    )

    @Serializable
    private data class MyMemoryResponse(
        val responseData: ResponseData? = null,
    ) {
        @Serializable
        data class ResponseData(
            val translatedText: String? = null,
        )
    }

    companion object {
        private const val INSTANCE_BACKOFF_MS = 5 * 60 * 1000L
        private const val DISK_CACHE_VERSION = 1
        private const val DISK_CACHE_SIZE_BYTES = 4L * 1024 * 1024
        private const val MAX_VARIANTS = 8

        private val LINGVA_INSTANCES = listOf(
            "https://lingva.ml",
            "https://translate.plausibility.cloud",
        )
    }
}
