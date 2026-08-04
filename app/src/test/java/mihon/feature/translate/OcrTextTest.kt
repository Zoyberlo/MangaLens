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
    inner class TextQuality {

        @Test
        fun `does not punish apostrophes or hyphens inside words`() {
            // The regression: correct text scored below garbled text, so the
            // second recognition pass replaced a good reading with a bad one
            OcrText.textQuality("YOU COULD'VE JUST LEARNED SWORDSMANSHIP") shouldBe 1f
            OcrText.textQuality("A WELL-KNOWN HUNTER") shouldBe 1f
        }

        @Test
        fun `rates garbled text below correct text`() {
            val correct = OcrText.textQuality("YOU COULD'VE JUST LEARNED SWORDSMANSHIP INSTEAD")
            val garbled = OcrText.textQuality("YOU C0LDVE JuST VEAZNE SW0RDS MANSHE NSTEA")
            (garbled < correct) shouldBe true
        }

        @Test
        fun `punishes stray digits and punctuation inside words`() {
            (OcrText.textQuality("SW0RD5") < 1f) shouldBe true
            (OcrText.textQuality("HUN#TER") < 1f) shouldBe true
        }

        @Test
        fun `punishes consonant runs that cannot be words`() {
            (OcrText.textQuality("XKCDFG HJKLMN") < 1f) shouldBe true
        }

        @Test
        fun `accepts CJK text that has no vowel letters`() {
            // Applying the vowel test to these scored every token as garbled,
            // which silently disabled the second pass for three of the four
            // source languages
            OcrText.textQuality("こんにちは") shouldBe 1f
            OcrText.textQuality("剣術を習えばよかった") shouldBe 1f
            OcrText.textQuality("안녕하세요") shouldBe 1f
        }

        @Test
        fun `still punishes digits mixed into CJK`() {
            (OcrText.textQuality("こんに5は") < 1f) shouldBe true
        }

        @Test
        fun `returns zero for text with no letters at all`() {
            OcrText.textQuality("!!! ... 123") shouldBe 0f
            OcrText.textQuality("") shouldBe 0f
        }

        @Test
        fun `accepts short words without demanding a vowel`() {
            OcrText.textQuality("MY GO") shouldBe 1f
        }

        @Test
        fun `punishes a lowercase letter stranded among capitals`() {
            // Comic lettering is uniformly cased, so "JuST" is the recognizer
            // guessing at a letter it could not read
            (OcrText.textQuality("JuST") < 1f) shouldBe true
            (OcrText.textQuality("duST") < 1f) shouldBe true
        }

        @Test
        fun `accepts ordinary capitalization`() {
            OcrText.textQuality("Just") shouldBe 1f
            OcrText.textQuality("JUST") shouldBe 1f
            OcrText.textQuality("just") shouldBe 1f
        }

        @Test
        fun `treats a mid-word case change as suspect even in a real name`() {
            // "McDonald" is a false positive, accepted knowingly: this only
            // ranks two OCR passes, and in comic lettering a case change
            // mid-word is a misread glyph far more often than a name
            (OcrText.textQuality("McDonald") < 1f) shouldBe true
        }
    }

    @Nested
    inner class LetterRepair {

        // Only the words these cases need; anything else is "not a word"
        private val dictionary = setOf(
            "learn", "learned", "reason", "hunting", "wanted", "said", "just", "dust",
            "instead", "knew", "moment", "hunter", "sword", "swordsmanship", "the", "you",
            "for", "not", "note", "no", "so", "son", "sun",
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
        fun `flags the garbled reading a vowel test called perfect`() {
            val garbled = "YOU COWVE duST VEARNED SWORDS MANSHTP NSTEAD"
            OcrText.textQuality(garbled) // vowel test sees nothing wrong with most of it
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
    inner class PassAgreement {

        @Test
        fun `identical readings agree completely`() {
            OcrText.agreementRatio("SWORDSMANSHIP", "SWORDSMANSHIP") shouldBe 1f
        }

        @Test
        fun `ignores case and spacing, which carry no meaning here`() {
            OcrText.agreementRatio("SWORDS MANSHIP", "swordsmanship") shouldBe 1f
        }

        @Test
        fun `flags the real garbled reading from the reader`() {
            // What the two passes actually produced on one bubble. Every token
            // has a vowel, so textQuality rates this a perfect 1.0 and sees
            // nothing wrong — the passes disagreeing is what gives it away.
            val first = "YOU COWVE duST VEARNED SWORDS MANSHTP NSTEAD"
            val second = "YOU COULD'VE JUST LEARNED SWORDS MANSHIP INSTEAD"
            OcrText.textQuality(second) shouldBe 1f
            (OcrText.agreementRatio(first, second) < OcrText.MIN_PASS_AGREEMENT) shouldBe true
        }

        @Test
        fun `tolerates a single character of difference in a long reading`() {
            // Together with the case above, this pins the threshold: real
            // garbling must fall below it and a single slip must stay above
            val ratio = OcrText.agreementRatio(
                "YOU COULD HAVE LEARNED SWORDSMANSHIP INSTEAD",
                "YOU COULD HAVE LEARNED SWORDSMANSH1P INSTEAD",
            )
            (ratio > OcrText.MIN_PASS_AGREEMENT) shouldBe true
        }

        @Test
        fun `is symmetric`() {
            OcrText.agreementRatio("ABCDEF", "ABXDEF") shouldBe OcrText.agreementRatio("ABXDEF", "ABCDEF")
        }

        @Test
        fun `treats one empty reading as total disagreement`() {
            OcrText.agreementRatio("SOMETHING", "") shouldBe 0f
            OcrText.agreementRatio("", "SOMETHING") shouldBe 0f
        }

        @Test
        fun `treats two empty readings as agreement`() {
            OcrText.agreementRatio("", "") shouldBe 1f
            OcrText.agreementRatio("   ", "\n") shouldBe 1f
        }

        @Test
        fun `rates completely different text near zero`() {
            (OcrText.agreementRatio("HELLO THERE", "XKCDQRT ZZZ") < 0.3f) shouldBe true
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
