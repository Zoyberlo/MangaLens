package mihon.feature.translate

import android.content.Context
import android.util.LruCache
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
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
import kotlin.coroutines.cancellation.CancellationException

/**
 * Text translation with an in-memory cache.
 * Strategy: Lingva instances (Google Translate proxy) first, MyMemory as fallback.
 */
@Inject
@SingleIn(AppScope::class)
class TextTranslator(
    private val context: Context,
    private val networkHelper: NetworkHelper,
    private val json: Json,
    private val translationPreferences: TranslationPreferences,
    quotaNotifier: QuotaNotifier,
) {

    // DeepL bills characters rather than requests, so this is the one tracker
    // whose cost is the length of the text
    private val deeplQuota = QuotaTracker(
        QuotaKind.DEEPL,
        translationPreferences.deeplMonthlyCharLimit,
        translationPreferences.deeplUsageChars,
        translationPreferences.deeplUsagePeriod,
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
        // Logged because a cached answer survives fixes to the providers: a
        // bubble re-tested after a change can still show the old result
        cache.get(key)?.let {
            logcat(LogPriority.DEBUG) { "Translated (cached): '$trimmed' -> '$it'" }
            return it
        }
        diskGet(key)?.let {
            logcat(LogPriority.DEBUG) { "Translated (disk cache): '$trimmed' -> '$it'" }
            cache.put(key, it)
            return it
        }

        val selectedProvider = translationPreferences.translationProvider.get()
            .let {
                // A DeepL selection that cannot run — no key, or the monthly
                // character budget is spent — falls back to the automatic chain
                if (it == TranslationProvider.DEEPL &&
                    (translationPreferences.deeplApiKey.get().isBlank() || !deeplQuota.canSpend(trimmed.length))
                ) {
                    TranslationProvider.AUTO
                } else {
                    it
                }
            }

        val result = when (selectedProvider) {
            TranslationProvider.AUTO -> translateAutomatically(trimmed, from, to)
            TranslationProvider.GOOGLE -> translateViaGoogle(trimmed, from, to)
            TranslationProvider.DEEPL -> translateViaDeepL(trimmed, from, to, context)
            TranslationProvider.LINGVA -> translateViaLingva(trimmed, from, to)
            TranslationProvider.MYMEMORY -> translateViaMyMemory(trimmed, from, to)
        }

        if (result != null) {
            // Which provider said what: a wrong word in a bubble cannot be
            // traced to Google or MyMemory after the fact otherwise
            val via = when {
                selectedProvider != TranslationProvider.AUTO -> selectedProvider.displayName
                OcrText.isUntranslated(trimmed, result) -> "Auto, untranslated"
                else -> lastAutoProvider.value?.displayName ?: "Auto"
            }
            logcat(LogPriority.DEBUG) { "Translated via $via: '$trimmed' -> '$result'" }
            cache.put(key, result)
            diskPut(key, result)
        } else {
            logcat(LogPriority.WARN) {
                "No translation for '$trimmed' ($from to $to, ${selectedProvider.displayName})"
            }
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
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "Variant lookup failed" }
        }
        return variants.distinctBy { it.lowercase() }.take(MAX_VARIANTS)
    }

    /**
     * The free providers in order, first real answer wins.
     *
     * "Real" matters: a provider can answer HTTP 200 with the source handed
     * back untranslated, and the chain used to stop there — so whenever Google
     * stumbled, Lingva's echo filled the bubble with English and MyMemory,
     * which worked, was never asked. An echo now only stands if every provider
     * returns one, because then the text is probably untranslatable on its
     * own — a name, a sound effect.
     */
    private suspend fun translateAutomatically(text: String, from: String, to: String): String? {
        val chain = listOf<Pair<TranslationProvider, suspend () -> String?>>(
            TranslationProvider.GOOGLE to { translateViaGoogle(text, from, to) },
            TranslationProvider.LINGVA to { translateViaLingva(text, from, to) },
            TranslationProvider.MYMEMORY to { translateViaMyMemory(text, from, to) },
        )
        var echo: String? = null
        for ((provider, attempt) in chain) {
            val result = attempt() ?: continue
            if (OcrText.isUntranslated(text, result)) {
                logcat(LogPriority.WARN) { "${provider.displayName} returned the source untranslated" }
                if (echo == null) echo = result
                continue
            }
            lastAutoProvider.value = provider
            return result
        }
        return echo
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
        } catch (e: CancellationException) {
            throw e
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
        val apiKey = translationPreferences.deeplApiKey.get().trim()
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
        } catch (e: CancellationException) {
            throw e
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
            } catch (e: CancellationException) {
                throw e
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
            val parsed = with(json) { response.parseAs<MyMemoryResponse>() }
            // An exhausted daily quota still arrives as HTTP 200, with the
            // warning text in the translation field — shown as-is, it would put
            // "MYMEMORY WARNING: YOU USED ALL AVAILABLE FREE TRANSLATIONS" in
            // the bubble
            if (parsed.quotaFinished == true || (parsed.responseStatus ?: 200) != 200) {
                logcat(LogPriority.WARN) {
                    "MyMemory refused: status ${parsed.responseStatus}, quota finished ${parsed.quotaFinished}"
                }
                return null
            }
            parsed.responseData
                ?.translatedText
                ?.takeIf { it.isNotBlank() && !it.startsWith("MYMEMORY WARNING", ignoreCase = true) }
        } catch (e: CancellationException) {
            throw e
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
        val quotaFinished: Boolean? = null,
        // Sent as a number on success and as a string on some errors
        @Serializable(with = LenientIntSerializer::class)
        val responseStatus: Int? = null,
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

/**
 * MyMemory sends `responseStatus` as a number on success and as a quoted
 * string on some errors ("403"); a strict Int field would turn its error
 * replies into parse failures and hide the reason.
 */
private object LenientIntSerializer : kotlinx.serialization.KSerializer<Int?> {
    override val descriptor = kotlinx.serialization.descriptors.PrimitiveSerialDescriptor(
        "LenientInt",
        kotlinx.serialization.descriptors.PrimitiveKind.STRING,
    )

    override fun deserialize(decoder: kotlinx.serialization.encoding.Decoder): Int? {
        val element = (decoder as? kotlinx.serialization.json.JsonDecoder)?.decodeJsonElement() ?: return null
        return (element as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull()
    }

    override fun serialize(encoder: kotlinx.serialization.encoding.Encoder, value: Int?) {
        encoder.encodeString(value?.toString().orEmpty())
    }
}
