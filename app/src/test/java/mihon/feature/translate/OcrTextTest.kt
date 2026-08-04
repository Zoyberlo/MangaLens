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
