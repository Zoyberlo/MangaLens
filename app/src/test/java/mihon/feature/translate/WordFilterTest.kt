package mihon.feature.translate

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Builds the filter from the **real shipped asset**, not a fake dictionary.
 *
 * The previous version of this code was only ever tested against a hand-written
 * set of a dozen words, passed every test, and did nothing whatsoever on the
 * device. A word check that silently answers "no" to everything is invisible:
 * it repairs nothing and reports no error.
 */
class WordFilterTest {

    // Deliberately built from the constant the app itself opens: the word list
    // has already gone missing once by living at a path nobody checked, and
    // once more by being renamed underneath the code at build time
    private val asset = File("src/main/assets/${EnglishLexicon.ASSET}")

    private val filter by lazy {
        check(asset.exists()) { "Word list asset is missing at ${asset.absolutePath}" }
        asset.bufferedReader().useLines { WordFilter.build(it) }
    }

    @Test
    fun `the asset the app opens is the one that ships`() {
        asset.exists() shouldBe true
    }

    @Test
    fun `knows the words this reader actually needs`() {
        val words = listOf(
            "learn", "learned", "reason", "hunting", "hunter", "wanted", "said",
            "just", "instead", "swordsmanship", "noble", "insist", "becoming",
            "moment", "knew", "sword", "the", "you", "for", "no",
        )
        words.forEach { word ->
            withClue(word) { filter.contains(word) shouldBe true }
        }
    }

    @Test
    fun `is case insensitive, since comic lettering is all caps`() {
        filter.contains("HUNTING") shouldBe true
        filter.contains("Hunting") shouldBe true
    }

    @Test
    fun `rejects the garbled readings taken off real pages`() {
        // Every one of these was on screen in the reader. If the filter accepts
        // them the repair cannot fire and the confidence signal goes blind.
        val garbled = listOf(
            "vearn", "vearned", "zeason", "hunteng", "wante", "cowve",
            "manshtp", "nstead", "swolos", "manshe", "veazne", "becontng",
            "swolds", "manshep", "eazn", "coulve", "hunteg", "beconting",
        )
        garbled.forEach { word ->
            withClue(word) { filter.contains(word) shouldBe false }
        }
    }

    @Test
    fun `cannot catch a misreading that lands on a real word`() {
        // The price of a 370k dictionary. "YOU'RE" misread as "YOUZE" survives
        // because youze is a real dialect entry, and "JUST" as "DUST" because
        // dust plainly is a word. A word check has a ceiling.
        filter.contains("youze") shouldBe true // YOU'RE misread
        filter.contains("youre") shouldBe true // the apostrophe-less form is listed too
        filter.contains("dust") shouldBe true // JUST misread
        filter.contains("sai") shouldBe true // SAID misread
    }

    @Test
    fun `rejects obvious non-words`() {
        filter.contains("xqzptv") shouldBe false
        filter.contains("aaaaaaaaaa") shouldBe false
    }

    @Test
    fun `treats single characters as unknown rather than guessing`() {
        filter.contains("a") shouldBe false
        filter.contains("") shouldBe false
    }

    @Test
    fun `false positive rate stays near the design target`() {
        // Random letter strings are almost all non-words. Well above ~1% here
        // would mean the filter is too small or the hashes correlate, and
        // garbled text would start passing as English.
        val random = kotlin.random.Random(20260804)
        val samples = 20_000
        val accepted = (0 until samples).count {
            val length = random.nextInt(6, 11)
            val candidate = (0 until length).map { ('a' + random.nextInt(26)) }.joinToString("")
            filter.contains(candidate)
        }
        // 0.1% leaves a wide margin over the 0.003% design target while still
        // failing loudly if the sizing or the hashes regress
        withClue("accepted $accepted of $samples random strings") {
            (accepted < samples / 1000) shouldBe true
        }
    }

    @Test
    fun `repairs the readings from real pages, against the real dictionary`() {
        // The end-to-end check that was missing while the lexicon silently did
        // nothing: real word list, real strings off the reader's screen.
        val isWord: (String) -> Boolean = filter::contains
        fun repair(text: String) = OcrText.repairLetterConfusions(text, isWord)

        repair("TO VEARN HUNTENG FOR NO ZEASON") shouldBe "TO LEARN HUNTING FOR NO REASON"
        repair("NSIST") shouldBe "INSIST"
        repair("ZEASON...") shouldBe "REASON..."
        OcrText.repairDigitConfusions("HUNTER2", isWord) shouldBe "HUNTER"
    }

    @Test
    fun `gives up on a word with two errors in it`() {
        // "BECOMING" came back as "BECONTNG": two misreadings in one word, and
        // the repair is single-edit by design. Trying harder here is how a
        // corrector starts inventing words that were never on the page.
        val isWord: (String) -> Boolean = filter::contains
        OcrText.repairLetterConfusions("BECONTNG", isWord) shouldBe "BECONTNG"
    }

    @Test
    fun `leaves correct text untouched, against the real dictionary`() {
        val isWord: (String) -> Boolean = filter::contains
        val correct = "YOU COULD HAVE JUST LEARNED SWORDSMANSHIP INSTEAD"
        OcrText.repairLetterConfusions(correct, isWord) shouldBe correct
    }

    private inline fun <T> withClue(clue: String, block: () -> T): T = try {
        block()
    } catch (e: AssertionError) {
        throw AssertionError("$clue: ${e.message}", e)
    }
}
