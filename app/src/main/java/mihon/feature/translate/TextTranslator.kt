package mihon.feature.translate

import android.util.LruCache
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.network.parseAs
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import logcat.LogPriority
import okhttp3.HttpUrl.Companion.toHttpUrl
import tachiyomi.core.common.util.system.logcat

/**
 * Text translation with an in-memory cache.
 * Strategy: Lingva instances (Google Translate proxy) first, MyMemory as fallback.
 */
class TextTranslator(
    private val networkHelper: NetworkHelper,
    private val json: Json,
) {

    private val client by lazy { networkHelper.client }

    private val cache = LruCache<String, String>(1000)

    suspend fun translate(text: String, from: String, to: String): String? {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || from == to) return null

        val key = "$from:$to:${trimmed.lowercase()}"
        cache.get(key)?.let { return it }

        val result = translateViaLingva(trimmed, from, to)
            ?: translateViaMyMemory(trimmed, from, to)

        if (result != null) {
            cache.put(key, result)
        }
        return result
    }

    private suspend fun translateViaLingva(text: String, from: String, to: String): String? {
        for (instance in LINGVA_INSTANCES) {
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
        private val LINGVA_INSTANCES = listOf(
            "https://lingva.ml",
            "https://translate.plausibility.cloud",
        )
    }
}
