package mihon.feature.translate

import android.content.Context
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.util.zip.GZIPInputStream

/**
 * "Is this an English word?", answered offline.
 *
 * The recognizer's mistakes on comic lettering are *systematic* — this font
 * turns every L into a V and every R into a Z — so both recognition passes make
 * the same mistake and agree with each other. Nothing about the shape of
 * "VEARN" says it is wrong; only knowing that no such word exists does.
 *
 * Backed by a Bloom filter rather than a set of strings: 370k words would cost
 * tens of megabytes as a `HashSet`, against ~550 KB here. The trade is a small
 * chance of calling a non-word a word, which fails safe — an unrecognised
 * garble is simply left alone, never rewritten into something wrong.
 */
class EnglishLexicon(private val context: Context) {

    private val bits by lazy { load() }

    /** True when [word] is (probably) an English word; false is certain. */
    fun contains(word: String): Boolean {
        val filter = bits ?: return false
        val normalized = word.lowercase()
        if (normalized.length < MIN_WORD_LENGTH) return false
        var hash = normalized.hashCode().toLong() and 0xFFFFFFFFL
        val step = (normalized.reversed().hashCode().toLong() and 0xFFFFFFFFL) or 1L
        repeat(HASH_COUNT) {
            val bit = ((hash % TOTAL_BITS) + TOTAL_BITS) % TOTAL_BITS
            if (filter[(bit ushr 6).toInt()] and (1L shl (bit and 63L).toInt()) == 0L) return false
            hash += step
        }
        return true
    }

    /** Warms the filter up off the critical path; safe to call repeatedly. */
    fun preload() {
        bits
    }

    private fun load(): LongArray? = try {
        val filter = LongArray((TOTAL_BITS / 64).toInt())
        context.assets.open(ASSET).use { raw ->
            GZIPInputStream(raw).bufferedReader().forEachLine { line ->
                val word = line.trim()
                if (word.length >= MIN_WORD_LENGTH) add(filter, word)
            }
        }
        filter
    } catch (e: Exception) {
        logcat(LogPriority.WARN, e) { "English lexicon unavailable; word checks disabled" }
        null
    }

    private fun add(filter: LongArray, word: String) {
        var hash = word.hashCode().toLong() and 0xFFFFFFFFL
        val step = (word.reversed().hashCode().toLong() and 0xFFFFFFFFL) or 1L
        repeat(HASH_COUNT) {
            val bit = ((hash % TOTAL_BITS) + TOTAL_BITS) % TOTAL_BITS
            filter[(bit ushr 6).toInt()] = filter[(bit ushr 6).toInt()] or (1L shl (bit and 63L).toInt())
            hash += step
        }
    }

    private companion object {
        const val ASSET = "english_words.txt.gz"
        const val MIN_WORD_LENGTH = 2

        // ~370k words at eight hashes over 4.4M bits: about a 0.3% chance of
        // calling a non-word a word, for 550 KB of heap
        const val TOTAL_BITS = 4_400_000L
        const val HASH_COUNT = 8
    }
}
