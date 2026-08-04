package mihon.feature.translate

/**
 * Bloom filter over a word list: "is this a word?" for ~370k entries in about
 * 550 KB, against tens of megabytes for a `HashSet` of strings.
 *
 * Deliberately free of Android so a test can build it from the real asset and
 * check real words — the version this replaced was only ever exercised against
 * a fake dictionary, and shipped broken.
 *
 * A false positive calls a non-word a word, which fails safe here: the garble
 * is left alone rather than rewritten into something wrong.
 */
class WordFilter private constructor(private val bits: LongArray) {

    fun contains(word: String): Boolean {
        val normalized = word.lowercase()
        if (normalized.length < MIN_WORD_LENGTH) return false
        forEachBit(normalized) { index, mask ->
            if (bits[index] and mask == 0L) return false
        }
        return true
    }

    companion object {
        const val MIN_WORD_LENGTH = 2

        // ~370k words over 8.8M bits with eleven hashes: about a 0.003% chance
        // of calling a non-word a word, for 1.1 MB of heap. The earlier 4.4M
        // sizing hit 0.3%, which was enough to wave a real garbled word
        // ("EAZN") through as English — and heap is not the scarce resource
        // next to the page bitmaps this app already holds.
        private const val TOTAL_BITS = 8_800_000L
        private const val HASH_COUNT = 11

        fun build(words: Sequence<String>): WordFilter {
            val bits = LongArray((TOTAL_BITS / 64).toInt())
            words.forEach { raw ->
                val word = raw.trim().lowercase()
                if (word.length >= MIN_WORD_LENGTH) {
                    forEachBit(word) { index, mask -> bits[index] = bits[index] or mask }
                }
            }
            return WordFilter(bits)
        }

        /**
         * Double hashing: two independent 31-bit hashes generate all eight bit
         * positions. Both building and querying go through this, so they cannot
         * drift apart.
         */
        private inline fun forEachBit(word: String, action: (index: Int, mask: Long) -> Unit) {
            var hash = fnv1a(word, FNV_OFFSET) % TOTAL_BITS
            val step = (fnv1a(word, FNV_SECOND_OFFSET) % (TOTAL_BITS - 1)) + 1
            repeat(HASH_COUNT) {
                action((hash ushr 6).toInt(), 1L shl (hash and 63L).toInt())
                hash = (hash + step) % TOTAL_BITS
            }
        }

        /**
         * Two FNV-1a passes with different seeds, rather than one of them being
         * [String.hashCode]. That pairing correlated enough to push the real
         * false-positive rate well past the design figure — "WVEARN" came back
         * as a word, which was enough to block a correct repair.
         */
        private fun fnv1a(word: String, seed: Long): Long {
            var hash = seed
            for (char in word) {
                hash = hash xor char.code.toLong()
                hash *= 0x100000001b3L
            }
            return (hash xor (hash ushr 32)) and 0x7FFFFFFFFFFFFFFL
        }

        private const val FNV_OFFSET = -0x340d631b7bdddcdbL
        private const val FNV_SECOND_OFFSET = 0x27220a95L
    }
}
