package mihon.feature.translate

/**
 * The pure text handling of the translation pipeline, kept apart from the
 * classes that own network clients, ML Kit and preferences so it can be tested
 * directly. Three bugs hid in these rules — apostrophes scored as damage,
 * digits never repaired, a vowel test applied to Japanese — each of which
 * silently traded correct text for garbled text with nothing to catch it.
 */
object OcrText {

    /**
     * Repairs digits the on-device model produced where a letter belongs.
     *
     * Comic lettering is all-caps, and at that weight `O/0`, `I/1`, `S/5`,
     * `B/8` and `Z/2` are near-identical shapes — so "SW0RD5" comes back
     * instead of "SWORDS", and the translator then faithfully mangles it.
     *
     * Only digits *inside* a word are touched, and only when the word is
     * mostly letters already, so genuine numbers survive: "CHAPTER 12", "1999"
     * and "LEVEL 5" all pass through untouched.
     */
    fun repairDigitConfusions(text: String): String {
        if (text.none { it.isDigit() }) return text
        return WORD_LIKE.replace(text) { match ->
            val token = match.value
            val letters = token.count { it.isLetter() }
            val digits = token.count { it.isDigit() }
            if (letters < 2 || digits == 0 || digits > letters) return@replace token

            val upperCase = token.count { it.isUpperCase() } >= letters - 1
            token.mapIndexed { index, char ->
                val replacement = DIGIT_LOOKALIKES[char]
                if (replacement != null && token.hasLetterBeside(index)) {
                    if (upperCase) replacement else replacement.lowercaseChar()
                } else {
                    char
                }
            }.joinToString("")
        }
    }

    /**
     * Rough share of tokens that look like real words, used to decide whether
     * a second recognition pass actually improved on the first. Garbled OCR
     * shows up as tokens with stray punctuation, digits or no vowels.
     *
     * Apostrophes and hyphens are deliberately not treated as stray: they sit
     * *inside* ordinary comic dialogue ("COULD'VE", and hyphens wherever a word
     * breaks across lines). Counting them against a token scored correct text
     * below garbled text — "COULD'VE" failed while "COLDVE" passed — which let
     * the second pass replace a good reading with a worse one.
     *
     * The vowel test only applies to alphabetic scripts. Japanese, Chinese and
     * Korean have no vowel letters, so applying it there marked every token
     * garbled, both passes scored zero, and the comparison silently always kept
     * the first — the refinement pass may as well not have existed for three of
     * the four source languages.
     */
    fun textQuality(text: String): Float {
        val tokens = text.split(WHITESPACE).filter { it.any(Char::isLetter) }
        if (tokens.isEmpty()) return 0f
        val good = tokens.count { token ->
            val body = token.trim(*TRIMMED_PUNCTUATION).filterNot { it in WORD_PUNCTUATION }
            val letters = body.count { it.isLetter() }
            when {
                letters < body.length -> false
                body.any { it.isCjk() } -> true
                else -> letters <= 2 || body.any { it.lowercaseChar() in VOWELS }
            }
        }
        return good.toFloat() / tokens.size
    }

    /**
     * Comic lettering is typically all-caps and hard-wrapped, both of which
     * badly degrade machine translation. Rejoin hyphenated line breaks,
     * collapse whitespace, and convert shouty text to sentence case.
     */
    fun normalizeForTranslation(text: String): String {
        val joined = text
            .replace(HYPHEN_LINE_BREAK, "")
            .replace(WHITESPACE, " ")
            .trim()
        if (joined.isEmpty()) return joined

        val letters = joined.filter { it.isLetter() }
        val isShouting = letters.length >= 4 && letters.count { it.isUpperCase() } > letters.length * 0.8
        if (!isShouting) return joined

        val builder = StringBuilder(joined.lowercase())
        var startOfSentence = true
        for (i in builder.indices) {
            val c = builder[i]
            when {
                startOfSentence && c.isLetter() -> {
                    builder[i] = c.uppercaseChar()
                    startOfSentence = false
                }
                c in SENTENCE_END -> startOfSentence = true
            }
        }
        // Standalone "i" is a proper word in English and must stay capitalized
        return builder.toString().replace(STANDALONE_I, " I ")
    }

    /** A digit flanked by a letter is a misread glyph, not a number. */
    private fun String.hasLetterBeside(index: Int): Boolean =
        (index > 0 && this[index - 1].isLetter()) ||
            (index + 1 < length && this[index + 1].isLetter())

    /** Kana, CJK ideographs and hangul — the scripts with no vowel letters. */
    private fun Char.isCjk(): Boolean =
        this in '぀'..'ヿ' || // hiragana and katakana
            this in '㐀'..'䶿' || // CJK unified extension A
            this in '一'..'鿿' || // CJK unified
            this in 'ᄀ'..'ᇿ' || // hangul jamo
            this in '가'..'힯' // hangul syllables

    private val WORD_LIKE = Regex("""[\p{L}\p{Nd}'’-]+""")
    private val WHITESPACE = Regex("""\s+""")
    private val HYPHEN_LINE_BREAK = Regex("""(?<=\p{L})-\s+(?=\p{L})""")
    private val STANDALONE_I = Regex(""" i (?=\p{L})""")
    private val SENTENCE_END = charArrayOf('.', '!', '?', '…')

    // Only the pairs that are genuinely ambiguous in heavy all-caps lettering.
    // 4/A and 6/G are a stretch and stay out.
    private val DIGIT_LOOKALIKES = mapOf('0' to 'O', '1' to 'I', '5' to 'S', '8' to 'B', '2' to 'Z')

    private val TRIMMED_PUNCTUATION = charArrayOf('.', ',', '!', '?', '"', '\'', '’', '-', '…')
    private const val WORD_PUNCTUATION = "'’-"

    // Latin only, on purpose. Every source language is Latin or CJK
    // (`TranslationSourceLanguage`), and ML Kit ships no Cyrillic model, so
    // Cyrillic text never reaches this.
    private const val VOWELS = "aeiouy"
}
