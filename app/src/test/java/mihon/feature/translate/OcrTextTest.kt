package mihon.feature.translate

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

/**
 * These rules decide which of two OCR readings the user actually sees, and
 * every case below is one that previously went the wrong way in the app.
 */
@Execution(ExecutionMode.CONCURRENT)
class OcrTextTest {

    @Nested
    inner class DigitRepair {

        @Test
        fun `repairs letters misread as digits inside a word`() {
            OcrText.repairDigitConfusions("SW0RD5") shouldBe "SWORDS"
            OcrText.repairDigitConfusions("1NSTEAD") shouldBe "INSTEAD"
            OcrText.repairDigitConfusions("8ATTLE") shouldBe "BATTLE"
            OcrText.repairDigitConfusions("AMA2ING") shouldBe "AMAZING"
        }

        @Test
        fun `leaves standalone numbers alone`() {
            OcrText.repairDigitConfusions("CHAPTER 12") shouldBe "CHAPTER 12"
            OcrText.repairDigitConfusions("IN 1999") shouldBe "IN 1999"
            OcrText.repairDigitConfusions("LEVEL 5") shouldBe "LEVEL 5"
        }

        @Test
        fun `leaves short mixed tokens alone rather than guessing`() {
            // One letter is not enough evidence that the digit is a misread
            OcrText.repairDigitConfusions("K2") shouldBe "K2"
            OcrText.repairDigitConfusions("B5") shouldBe "B5"
        }

        @Test
        fun `keeps a digit rather than inventing a word from it`() {
            // "HUNTER?" comes back as "HUNTER2". Swapping to Z would give
            // "HUNTERZ", which reads worse than the digit; with a dictionary
            // the stray mark is dropped instead.
            val isWord: (String) -> Boolean = { it.lowercase() in setOf("hunter", "swords") }
            OcrText.repairDigitConfusions("HUNTER2", isWord) shouldBe "HUNTER"
            OcrText.repairDigitConfusions("QWXYZ2", isWord) shouldBe "QWXYZ2"
        }

        @Test
        fun `still repairs a digit when the result is a real word`() {
            val isWord: (String) -> Boolean = { it.lowercase() in setOf("swords") }
            OcrText.repairDigitConfusions("SW0RD5", isWord) shouldBe "SWORDS"
        }

        @Test
        fun `does not touch a token that is mostly digits`() {
            OcrText.repairDigitConfusions("ROOM 1005") shouldBe "ROOM 1005"
        }

        @Test
        fun `matches the case of the word it is repairing`() {
            OcrText.repairDigitConfusions("sw0rds") shouldBe "swords"
            OcrText.repairDigitConfusions("SW0RDS") shouldBe "SWORDS"
        }

        @Test
        fun `returns digit-free text untouched`() {
            val text = "YOU COULD'VE JUST LEARNED SWORDSMANSHIP INSTEAD..."
            OcrText.repairDigitConfusions(text) shouldBe text
        }
    }

    @Nested
    inner class LetterRepair {

        // Only the words these cases need; anything else is "not a word"
        private val dictionary = setOf(
            "learn", "learned", "reason", "hunting", "wanted", "said", "just", "dust",
            "instead", "knew", "moment", "hunter", "sword", "swordsmanship", "the", "you",
            "for", "not", "note", "no", "so", "son", "sun", "insist",
        )
        private val isWord: (String) -> Boolean = { it.lowercase() in dictionary }

        private fun repair(text: String) = OcrText.repairLetterConfusions(text, isWord)

        @Test
        fun `repairs the systematic misreadings from the reader`() {
            // Every one of these came off a real page. Both recognition passes
            // agreed on them, so only the dictionary could tell they were wrong
            repair("VEARN") shouldBe "LEARN"
            repair("VEARNED") shouldBe "LEARNED"
            repair("ZEASON") shouldBe "REASON"
            repair("HUNTENG") shouldBe "HUNTING"
            repair("WANTE") shouldBe "WANTED"
        }

        @Test
        fun `repairs a whole sentence in place`() {
            repair("TO VEARN HUNTENG FOR NO ZEASON") shouldBe "TO LEARN HUNTING FOR NO REASON"
        }

        @Test
        fun `leaves real words alone`() {
            repair("YOU LEARNED THE MOMENT") shouldBe "YOU LEARNED THE MOMENT"
            repair("SWORDSMANSHIP") shouldBe "SWORDSMANSHIP"
        }

        @Test
        fun `leaves a word the recognizer got right but the dictionary lacks`() {
            // A character name must survive untouched
            repair("KAELTHAS") shouldBe "KAELTHAS"
        }

        @Test
        fun `refuses to choose when two repairs are both words`() {
            // "son" could be "sun" (o/a is in the table via a, not u) — use a
            // case where the table really does offer two: "sot" -> "sod"/"set"
            // is not in the table, so build the ambiguity from n/h and o/a
            val ambiguous = OcrText.repairLetterConfusions("NO", isWord)
            ambiguous shouldBe "NO"
        }

        @Test
        fun `will not repair a token that is already a word`() {
            // "dust" is a real word, so the J-read-as-d error is invisible here
            // — a dictionary cannot catch every misreading
            repair("DUST") shouldBe "DUST"
        }

        @Test
        fun `keeps the original capitalization`() {
            repair("Vearn") shouldBe "Learn"
            repair("vearn") shouldBe "learn"
            repair("VEARN") shouldBe "LEARN"
        }

        @Test
        fun `restores a swallowed first letter`() {
            // The opening letter of a line is the one the balloon edge clips
            repair("NSIST") shouldBe "INSIST"
            repair("NSTEAD") shouldBe "INSTEAD"
        }

        @Test
        fun `leaves very short tokens alone`() {
            repair("VE") shouldBe "VE"
        }

        @Test
        fun `preserves punctuation around a repaired word`() {
            repair("ZEASON...") shouldBe "REASON..."
            repair("\"VEARN\"") shouldBe "\"LEARN\""
        }
    }

    @Nested
    inner class UnknownWords {

        private val dictionary = setOf("you", "learned", "swords", "the", "moment", "for", "no", "reason")
        private val isWord: (String) -> Boolean = { it.lowercase() in dictionary }

        @Test
        fun `flags a garbled reading that looks like ordinary words`() {
            // Every token here has vowels and no stray punctuation, so the
            // shape-based scoring this replaced rated it perfect. Only knowing
            // the words do not exist gives it away.
            val garbled = "YOU COWVE duST VEARNED SWORDS MANSHTP NSTEAD"
            (OcrText.unknownWordRatio(garbled, isWord) > OcrText.MAX_UNKNOWN_WORDS) shouldBe true
        }

        @Test
        fun `accepts a clean reading`() {
            OcrText.unknownWordRatio("YOU LEARNED THE MOMENT", isWord) shouldBe 0f
        }

        @Test
        fun `tolerates the names and sound effects no dictionary holds`() {
            // One unknown word in four must not condemn the block
            val ratio = OcrText.unknownWordRatio("YOU LEARNED THE KAELTHAS", isWord)
            (ratio <= OcrText.MAX_UNKNOWN_WORDS) shouldBe true
        }

        @Test
        fun `ignores punctuation and very short tokens`() {
            OcrText.unknownWordRatio("...!? a", isWord) shouldBe 0f
        }
    }

    @Nested
    inner class Normalization {

        @Test
        fun `rejoins words broken across lines`() {
            OcrText.normalizeForTranslation("IMPRES- SION") shouldBe "Impression"
            OcrText.normalizeForTranslation("SWORDS-\nMANSHIP") shouldBe "Swordsmanship"
        }

        @Test
        fun `converts shouting to sentence case`() {
            OcrText.normalizeForTranslation("YOU COULD HAVE LEARNED THIS") shouldBe "You could have learned this"
        }

        @Test
        fun `starts a new sentence after terminal punctuation`() {
            OcrText.normalizeForTranslation("WHAT IS THIS? I DO NOT KNOW.") shouldBe "What is this? I do not know."
        }

        @Test
        fun `keeps standalone I capitalized`() {
            OcrText.normalizeForTranslation("WHY WOULD I DO THAT") shouldBe "Why would I do that"
        }

        @Test
        fun `leaves ordinary mixed-case text as it is`() {
            val text = "You could have learned this"
            OcrText.normalizeForTranslation(text) shouldBe text
        }

        @Test
        fun `collapses whitespace and trims`() {
            OcrText.normalizeForTranslation("  hello   there \n world  ") shouldBe "hello there world"
        }

        @Test
        fun `leaves very short shouts alone`() {
            // Under four letters there is not enough evidence of shouting, and
            // sound effects should keep their shape
            OcrText.normalizeForTranslation("NO!") shouldBe "NO!"
        }

        @Test
        fun `handles empty and blank input`() {
            OcrText.normalizeForTranslation("") shouldBe ""
            OcrText.normalizeForTranslation("   ") shouldBe ""
        }
    }
}
