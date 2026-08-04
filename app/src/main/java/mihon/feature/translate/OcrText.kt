package mihon.feature.translate

/**
 * The pure text handling of the translation pipeline, kept apart from the
 * classes that own network clients, ML Kit and preferences so it can be tested
 * directly. Three bugs hid in these rules — apostrophes scored as damage,
 * digits never repaired, a vowel test applied to Japanese — each of which
 * silently traded correct text for garbled text with nothing to catch it.
 */
object OcrText {

    internal val TranslationSourceLanguage.isCjk: Boolean
        get() = this != TranslationSourceLanguage.ENGLISH

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
    fun repairDigitConfusions(text: String, isWord: ((String) -> Boolean)? = null): String {
        if (text.none { it.isDigit() }) return text
        return WORD_LIKE.replace(text) { match ->
            val token = match.value
            val letters = token.count { it.isLetter() }
            val digits = token.count { it.isDigit() }
            if (letters < 2 || digits == 0 || digits > letters) return@replace token

            // Punctuation misread as a digit: "HUNTER?" comes back as "HUNTER2".
            // Dropping the stray mark loses a question mark, which is a far
            // smaller error than inventing the word "HUNTERZ".
            val withoutTrailing = token.trimEnd { it.isDigit() }
            if (isWord != null && withoutTrailing != token && isWord(withoutTrailing)) {
                return@replace withoutTrailing
            }

            val upperCase = token.count { it.isUpperCase() } >= letters - 1
            val substituted = token.mapIndexed { index, char ->
                val replacement = DIGIT_LOOKALIKES[char]
                if (replacement != null && token.hasLetterBeside(index)) {
                    if (upperCase) replacement else replacement.lowercaseChar()
                } else {
                    char
                }
            }.joinToString("")

            // With a dictionary to hand, only take the swap when it produces a
            // real word — otherwise "HUNTER2" becomes "HUNTERZ", which reads
            // worse than the digit it replaced
            if (isWord != null && substituted != token && !isWord(substituted)) token else substituted
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
                hasStrayCase(body) -> false
                else -> letters <= 2 || body.any { it.lowercaseChar() in VOWELS }
            }
        }
        return good.toFloat() / tokens.size
    }

    /**
     * A lowercase letter surrounded by capitals — "JuST", "duST". Comic
     * lettering is uniformly cased, so this only happens when the recognizer
     * guessed a letter it could not read, which makes it one of the few
     * garbled readings a vowel test cannot see.
     */
    private fun hasStrayCase(body: String): Boolean {
        val letters = body.filter { it.isLetter() }
        if (letters.length < 3) return false
        val upper = letters.count { it.isUpperCase() }
        if (upper == 0 || upper == letters.length) return false // all one case
        // Anything but a plain Capitalized word: a case change mid-word is far
        // more often a misread glyph than a real name
        return !(letters[0].isUpperCase() && letters.drop(1).none { it.isUpperCase() })
    }

    /**
     * Repairs single-letter misreadings that only a dictionary can catch.
     *
     * This font's mistakes are systematic — every L comes back as V, every R as
     * Z — so both recognition passes make the same one and agree with each
     * other. "VEARN" is a perfectly plausible string; the only thing wrong with
     * it is that no such word exists.
     *
     * Deliberately narrow, because a general spell-corrector on OCR output does
     * more harm than good:
     * - only tokens [isWord] rejects are touched at all;
     * - only substitutions from [LETTER_LOOKALIKES], the shapes this lettering
     *   actually confuses, plus one dropped trailing letter;
     * - the result must itself be a word;
     * - and if two different repairs both produce words, neither is applied.
     *
     * So "VEARN" becomes "LEARN" and "ZEASON" becomes "REASON", while a name
     * the dictionary has never heard of is left exactly as it was.
     */
    fun repairLetterConfusions(text: String, isWord: (String) -> Boolean): String =
        WORD_LIKE.replace(text) { match ->
            val token = match.value
            val prefix = token.takeWhile { !it.isLetter() }
            val suffix = token.takeLastWhile { !it.isLetter() }
            val core = token.substring(prefix.length, token.length - suffix.length)
            // A word with punctuation *inside* it ("YOU'E") is left alone: the
            // repair works on letters, and there is no safe way to splice the
            // apostrophe back into a word that changed length
            if (core.length < MIN_REPAIRED_LENGTH || !core.all { it.isLetter() }) return@replace token
            if (isWord(core)) return@replace token

            val repaired = repairCandidates(core).firstNotNullOfOrNull { tier ->
                tier.filter(isWord).distinct().singleOrNull()
            } ?: return@replace token
            prefix + matchCase(repaired, core) + suffix
        }

    /**
     * Candidate repairs in order of how likely they are, most likely first.
     * Only the best tier that yields exactly one word is used.
     *
     * Swapping one letter for a shape it is genuinely confused with beats
     * inventing a letter the recognizer never reported, and treating the two as
     * equals let a spurious "WVEARN" block the obvious "LEARN".
     */
    private fun repairCandidates(letters: String): List<List<String>> {
        val lower = letters.lowercase()
        val substitutions = lower.indices.flatMap { index ->
            LETTER_LOOKALIKES[lower[index]].orEmpty().map { replacement ->
                lower.substring(0, index) + replacement + lower.substring(index + 1)
            }
        }
        // A swallowed final stroke: "WANTE" for "WANTED", "SAI" for "SAID".
        // Or a swallowed first one: "NSIST" for "INSIST" — the opening letter
        // of a line is the one the speech balloon's edge tends to clip.
        val inserted = TRAILING_LETTERS.map { lower + it } + LEADING_LETTERS.map { it + lower }
        return listOf(substitutions, inserted)
    }

    /** Keeps the repaired word looking like the one it replaced. */
    private fun matchCase(repaired: String, original: String): String = when {
        original.all { it.isUpperCase() } -> repaired.uppercase()
        original.first().isUpperCase() -> repaired.replaceFirstChar { it.uppercase() }
        else -> repaired
    }

    /**
     * Share of tokens that are not English words — the only signal that sees a
     * systematic misreading, since those produce agreeing passes and tokens
     * full of perfectly ordinary vowels.
     */
    fun unknownWordRatio(text: String, isWord: (String) -> Boolean): Float {
        val tokens = text.split(WHITESPACE)
            .map { it.filter(Char::isLetter) }
            .filter { it.length >= MIN_REPAIRED_LENGTH }
        if (tokens.isEmpty()) return 0f
        return tokens.count { !isWord(it) }.toFloat() / tokens.size
    }

    /**
     * Above this share of unrecognised words the reading is not trustworthy.
     * Comic dialogue carries names and sound effects that no dictionary holds,
     * so a third being unknown is normal; half is not.
     */
    const val MAX_UNKNOWN_WORDS = 0.5f

    /**
     * Below this the two passes read the bubble too differently to trust
     * either. Measured, not guessed: the garbled bubble in `OcrTextTest`
     * scores 0.81, while a single misread character in a full sentence scores
     * 0.98, so the boundary sits between them with room on both sides.
     */
    const val MIN_PASS_AGREEMENT = 0.9f

    /**
     * How closely two readings of the same text agree, 0 to 1, ignoring case
     * and spacing.
     *
     * The two recognition passes see the same bubble at different scales. When
     * they agree the reading is trustworthy; when they diverge the model was
     * guessing, and neither result should be presented as if it were fact.
     * This is the only confidence signal available without a dictionary, and
     * unlike a vowel test it catches "COWVE" and "MANSHTP".
     */
    fun agreementRatio(first: String, second: String): Float {
        val a = first.filterNot(Char::isWhitespace).lowercase().take(MAX_COMPARED_CHARS)
        val b = second.filterNot(Char::isWhitespace).lowercase().take(MAX_COMPARED_CHARS)
        if (a.isEmpty() && b.isEmpty()) return 1f
        if (a.isEmpty() || b.isEmpty()) return 0f
        if (a == b) return 1f
        return 1f - editDistance(a, b).toFloat() / maxOf(a.length, b.length)
    }

    /** Levenshtein over two rows; bubble text is short enough for it. */
    private fun editDistance(a: String, b: String): Int {
        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)
        for (i in 1..a.length) {
            current[0] = i
            for (j in 1..b.length) {
                val substitution = previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(substitution, previous[j] + 1, current[j - 1] + 1)
            }
            val swap = previous
            previous = current
            current = swap
        }
        return previous[b.length]
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

    /**
     * Which language's model to re-read with, or null to keep what was read.
     *
     * A CJK model fed Latin lettering returns plausible-looking nonsense —
     * "SWORDSMANSHIP" comes back as "swolos manshe" — so output from a CJK
     * model holding no CJK characters means the wrong model ran. The reverse
     * case is quieter: the Latin model simply finds nothing in Japanese.
     * Without this, a mismatched language setting silently ruins every result
     * with no error anywhere.
     */
    fun fallbackLanguageFor(
        language: TranslationSourceLanguage,
        recognized: List<String>,
    ): TranslationSourceLanguage? = when {
        language.isCjk && recognized.isNotEmpty() && recognized.none(::hasCjk) ->
            TranslationSourceLanguage.ENGLISH
        !language.isCjk && recognized.isEmpty() -> TranslationSourceLanguage.JAPANESE
        else -> null
    }

    /** True when the text holds Han, kana or Hangul characters. */
    fun hasCjk(text: String): Boolean = text.any { it.isCjk() }

    /** A digit flanked by a letter is a misread glyph, not a number. */
    private fun String.hasLetterBeside(index: Int): Boolean =
        (index > 0 && this[index - 1].isLetter()) ||
            (index + 1 < length && this[index + 1].isLetter())

    /** Han, kana and hangul — the scripts with no vowel letters. */
    private fun Char.isCjk(): Boolean = when (Character.UnicodeScript.of(code)) {
        Character.UnicodeScript.HAN,
        Character.UnicodeScript.HIRAGANA,
        Character.UnicodeScript.KATAKANA,
        Character.UnicodeScript.HANGUL,
        -> true
        else -> false
    }

    private val WORD_LIKE = Regex("""[\p{L}\p{Nd}'’-]+""")
    private val WHITESPACE = Regex("""\s+""")
    private val HYPHEN_LINE_BREAK = Regex("""(?<=\p{L})-\s+(?=\p{L})""")
    private val STANDALONE_I = Regex(""" i (?=\p{L})""")
    private val SENTENCE_END = charArrayOf('.', '!', '?', '…')

    // Only the pairs that are genuinely ambiguous in heavy all-caps lettering.
    // 4/A and 6/G are a stretch and stay out.
    private val DIGIT_LOOKALIKES = mapOf('0' to 'O', '1' to 'I', '5' to 'S', '8' to 'B', '2' to 'Z')

    // Letter pairs this hand-lettered comic face actually confuses, observed
    // from real pages: LEARN read as VEARN, REASON as ZEASON, HUNTING as
    // HUNTENG. Kept short on purpose — every extra pair is another chance to
    // rewrite a word that was right.
    private val LETTER_LOOKALIKES: Map<Char, List<Char>> = buildMap {
        listOf(
            'l' to 'v', 'r' to 'z', 'i' to 'e', 'c' to 'e', 'n' to 'h',
            'u' to 'v', 'o' to 'a', 't' to 'f', 's' to 'g', 'j' to 'd',
        ).forEach { (a, b) ->
            merge(a, listOf(b)) { old, new -> old + new }
            merge(b, listOf(a)) { old, new -> old + new }
        }
    }

    private val TRAILING_LETTERS = listOf('d', 's', 'e', 't', 'y', 'r')
    private val LEADING_LETTERS = listOf('i', 'l', 't', 'a', 's', 'b', 'w', 'h')

    private const val MIN_REPAIRED_LENGTH = 3

    private val TRIMMED_PUNCTUATION = charArrayOf('.', ',', '!', '?', '"', '\'', '’', '-', '…')
    private const val WORD_PUNCTUATION = "'’-"

    // Latin only, on purpose. Every source language is Latin or CJK
    // (`TranslationSourceLanguage`), and ML Kit ships no Cyrillic model, so
    // Cyrillic text never reaches this.
    private const val VOWELS = "aeiouy"

    private const val MAX_COMPARED_CHARS = 400
}
