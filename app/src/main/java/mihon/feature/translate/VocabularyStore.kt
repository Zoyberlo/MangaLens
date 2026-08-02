package mihon.feature.translate

import android.app.Application
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import logcat.LogPriority
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.i18n.MR
import java.io.File

/**
 * A word or phrase saved while reading.
 */
@Serializable
data class SavedWord(
    val word: String,
    val translation: String,
    val sourceLang: String,
    val targetLang: String,
    val savedAt: Long,
)

/**
 * The fork's own vocabulary: words saved from the translate overlay, persisted
 * as JSON in the app's files dir. Makes the app fully usable without the
 * companion words-app; sending an entry there is an optional export.
 */
class VocabularyStore(
    private val context: Application,
    private val translator: TextTranslator,
    private val readerPreferences: ReaderPreferences,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val file = File(context.filesDir, "saved_words.json")
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(SavedWord.serializer())

    private val _words = MutableStateFlow<List<SavedWord>>(emptyList())
    val words: StateFlow<List<SavedWord>> = _words

    init {
        scope.launch {
            _words.value = try {
                if (file.exists()) json.decodeFromString(serializer, file.readText()) else emptyList()
            } catch (e: Exception) {
                logcat(LogPriority.WARN, e) { "Failed to load saved words" }
                emptyList()
            }
        }
    }

    /**
     * Saves [word] to the local vocabulary; when [translation] is null it is
     * fetched first (fast for anything already in the translation cache).
     * Re-saving an existing word refreshes it. Shows a confirmation toast.
     */
    fun saveAsync(word: String, translation: String?) {
        val trimmed = word.trim()
        if (trimmed.isEmpty()) return
        val from = readerPreferences.autoTranslateSourceLanguage.get().langCode
        val to = readerPreferences.autoTranslateTargetLanguage.get()
        scope.launch {
            val resolved = translation
                ?: try {
                    translator.translate(trimmed, from, to)
                } catch (e: Exception) {
                    logcat(LogPriority.WARN, e) { "Word lookup failed" }
                    null
                }
                ?: ""
            val entry = SavedWord(trimmed, resolved, from, to, System.currentTimeMillis())
            _words.value = _words.value
                .filterNot { it.word.equals(trimmed, ignoreCase = true) && it.targetLang == to }
                .plus(entry)
            persist()
            withUIContext {
                context.toast(context.stringResource(MR.strings.translate_word_saved, trimmed))
            }
        }
    }

    fun remove(entry: SavedWord) {
        _words.value = _words.value - entry
        scope.launch { persist() }
    }

    private fun persist() {
        try {
            file.writeText(json.encodeToString(serializer, _words.value))
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "Failed to persist saved words" }
        }
    }
}
