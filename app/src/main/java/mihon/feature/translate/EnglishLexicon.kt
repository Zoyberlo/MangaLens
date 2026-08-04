package mihon.feature.translate

import android.content.Context
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

/**
 * "Is this an English word?", answered offline.
 *
 * The recognizer's mistakes on comic lettering are *systematic* — this font
 * turns L into V and R into Z — so both recognition passes make the same
 * mistake and agree with each other. Nothing about the shape of "VEARN" says it
 * is wrong; only knowing that no such word exists does.
 *
 * All this class does is feed the asset to [WordFilter]; the logic worth
 * testing lives there, where a test can reach it without a device.
 */
class EnglishLexicon(private val context: Context) {

    private val filter by lazy { load() }

    /** True when [word] is (probably) an English word; false is certain. */
    fun contains(word: String): Boolean = filter?.contains(word) == true

    /** Builds the filter off the critical path; safe to call repeatedly. */
    fun preload() {
        filter
    }

    private fun load(): WordFilter? = try {
        context.assets.open(ASSET).bufferedReader().useLines { WordFilter.build(it) }
    } catch (e: Exception) {
        logcat(LogPriority.WARN, e) { "English lexicon unavailable; word checks disabled" }
        null
    }

    companion object {
        /**
         * Plain text on purpose. A `.gz` asset is silently unpacked at build
         * time — the APK ends up holding `english_words.txt` while the code
         * asks for `english_words.txt.gz`, and the lexicon fails to open with
         * no symptom beyond word checks quietly answering "no" to everything.
         * The APK compresses it anyway: 4.2 MB down to 1.4 MB.
         */
        const val ASSET = "english_words.txt"
    }
}
