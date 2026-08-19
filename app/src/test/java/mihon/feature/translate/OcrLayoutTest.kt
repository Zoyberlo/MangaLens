package mihon.feature.translate

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import kotlin.random.Random

/**
 * The recognizer is a black box: bitmap in, boxes and strings out. Everything
 * this app decides afterwards takes those boxes as input, so the boxes can
 * simply be written down — no image, no device, no model.
 *
 * A speech bubble arrives as one box per line. Getting it back together is
 * where mistakes turn into a bubble translated as three sentence fragments,
 * or two unrelated bubbles glued into nonsense.
 */
@Execution(ExecutionMode.CONCURRENT)
class OcrLayoutTest {

    // A 20px-tall line of text starting at (left, top)
    private fun line(left: Int, top: Int, width: Int = 100, height: Int = 20) =
        TextBox(left, top, left + width, top + height)

    private fun item(text: String, left: Int, top: Int, width: Int = 100, height: Int = 20) =
        TextItem(text, line(left, top, width, height))

    @Nested
    inner class ShouldMerge {

        @Test
        fun `joins consecutive lines of one bubble`() {
            OcrLayout.shouldMerge(line(0, 0), line(0, 25)) shouldBe true
        }

        @Test
        fun `keeps separate bubbles apart`() {
            OcrLayout.shouldMerge(line(0, 0), line(0, 100)) shouldBe false
        }

        @Test
        fun `joins boxes side by side on the same line`() {
            OcrLayout.shouldMerge(line(0, 0, width = 50), line(60, 0, width = 50)) shouldBe true
        }

        @Test
        fun `keeps boxes apart when the horizontal gap exceeds the threshold`() {
            // Gap of 50 against a 20px line: over the 1.5x allowance
            OcrLayout.shouldMerge(line(0, 0, width = 50), line(100, 0, width = 50)) shouldBe false
        }

        @Test
        fun `joins overlapping boxes`() {
            OcrLayout.shouldMerge(line(0, 0), line(10, 10)) shouldBe true
        }

        @Test
        fun `is symmetric`() {
            val random = Random(20260804)
            repeat(500) {
                val a = TextBox(random.nextInt(0, 500), random.nextInt(0, 500), 0, 0)
                    .let { TextBox(it.left, it.top, it.left + random.nextInt(1, 200), it.top + random.nextInt(1, 60)) }
                val b = TextBox(random.nextInt(0, 500), random.nextInt(0, 500), 0, 0)
                    .let { TextBox(it.left, it.top, it.left + random.nextInt(1, 200), it.top + random.nextInt(1, 60)) }
                OcrLayout.shouldMerge(a, b) shouldBe OcrLayout.shouldMerge(b, a)
            }
        }

        @Test
        fun `scales with lettering size rather than absolute pixels`() {
            // The same layout at 4x zoom must decide the same way
            val small = OcrLayout.shouldMerge(line(0, 0, 100, 20), line(0, 25, 100, 20))
            val large = OcrLayout.shouldMerge(line(0, 0, 400, 80), line(0, 100, 400, 80))
            small shouldBe true
            large shouldBe small
        }

        @Test
        fun `survives a zero-height box without dividing by zero`() {
            OcrLayout.shouldMerge(TextBox(0, 0, 100, 0), line(0, 0)) shouldBe true
        }
    }

    @Nested
    inner class ReadingOrder {

        @Test
        fun `reads a row left to right for latin scripts`() {
            val left = item("HELLO", 0, 0)
            val right = item("THERE", 120, 0)
            OcrLayout.orderForReading(right, left, TranslationSourceLanguage.ENGLISH)
                .map { it.text } shouldBe listOf("HELLO", "THERE")
        }

        @Test
        fun `reads a row right to left for japanese, whose columns run that way`() {
            val left = item("う", 0, 0)
            val right = item("あ", 120, 0)
            OcrLayout.orderForReading(left, right, TranslationSourceLanguage.JAPANESE)
                .map { it.text } shouldBe listOf("あ", "う")
        }

        @Test
        fun `reads stacked boxes top to bottom whatever the language`() {
            val top = item("FIRST", 0, 0)
            val bottom = item("SECOND", 0, 40)
            for (language in TranslationSourceLanguage.entries) {
                OcrLayout.orderForReading(bottom, top, language)
                    .map { it.text } shouldBe listOf("FIRST", "SECOND")
            }
        }

        @Test
        fun `treats a small vertical offset as the same row`() {
            // Deliberately arranged so the two rules disagree: ordering by x
            // gives LEFT first, ordering by y gives RIGHT first. Under half a
            // line height apart counts as one row, so x wins.
            val right = item("RIGHT", 120, 0)
            val left = item("LEFT", 0, 9)
            OcrLayout.orderForReading(right, left, TranslationSourceLanguage.ENGLISH)
                .map { it.text } shouldBe listOf("LEFT", "RIGHT")
        }

        @Test
        fun `treats a large vertical offset as a new row`() {
            // Half a line height or more apart: ordered by y, so the left-hand
            // box no longer wins
            val a = item("A", 0, 0)
            val b = item("B", 120, 10)
            OcrLayout.orderForReading(b, a, TranslationSourceLanguage.ENGLISH)
                .map { it.text } shouldBe listOf("A", "B")
            OcrLayout.orderForReading(item("A", 120, 0), item("B", 0, 10), TranslationSourceLanguage.ENGLISH)
                .map { it.text } shouldBe listOf("A", "B")
        }
    }

    @Nested
    inner class Merge {

        private fun merge(
            items: List<TextItem>,
            language: TranslationSourceLanguage = TranslationSourceLanguage.ENGLISH,
        ) = OcrLayout.merge(items, language)

        @Test
        fun `joins the lines of a bubble into one sentence`() {
            val result = merge(
                listOf(
                    item("YOU COULD'VE JUST", 0, 0),
                    item("LEARNED SWORDSMANSHIP", 0, 25),
                    item("INSTEAD...", 0, 50),
                ),
            )
            result.map { it.text } shouldBe listOf("YOU COULD'VE JUST LEARNED SWORDSMANSHIP INSTEAD...")
        }

        @Test
        fun `joins japanese lines without inserting spaces`() {
            val result = merge(
                listOf(item("剣術を", 0, 0), item("習えば", 0, 25)),
                TranslationSourceLanguage.JAPANESE,
            )
            result.map { it.text } shouldBe listOf("剣術を習えば")
        }

        @Test
        fun `joins korean lines with spaces, unlike japanese and chinese`() {
            val result = merge(
                listOf(item("안녕", 0, 0), item("하세요", 0, 25)),
                TranslationSourceLanguage.KOREAN,
            )
            result.map { it.text } shouldBe listOf("안녕 하세요")
        }

        @Test
        fun `keeps two distant bubbles separate`() {
            val result = merge(listOf(item("FIRST", 0, 0), item("SECOND", 0, 400)))
            result.map { it.text } shouldBe listOf("FIRST", "SECOND")
        }

        @Test
        fun `joins a chain through the middle box even when the ends are far apart`() {
            // A and C are 50px apart — too far on their own — but B bridges them
            val result = merge(listOf(item("A", 0, 0), item("B", 0, 25), item("C", 0, 50)))
            result.map { it.text } shouldBe listOf("A B C")
        }

        @Test
        fun `the merged box covers every box it came from`() {
            val result = merge(listOf(item("A", 0, 0, width = 100), item("B", 40, 25, width = 100)))
            result.single().box shouldBe TextBox(0, 0, 140, 45)
        }

        @Test
        fun `orders lines by position, not by the order they arrived in`() {
            val result = merge(listOf(item("THIRD", 0, 50), item("FIRST", 0, 0), item("SECOND", 0, 25)))
            result.map { it.text } shouldBe listOf("FIRST SECOND THIRD")
        }

        @Test
        fun `returns a single item unchanged`() {
            val only = item("ALONE", 10, 10)
            merge(listOf(only)) shouldBe listOf(only)
        }

        @Test
        fun `returns empty input unchanged`() {
            merge(emptyList()) shouldBe emptyList()
        }

        @Test
        fun `terminates on heavily overlapping input`() {
            // Every box touches every other. The loop restarts after each
            // merge, so a rule change that stops shrinking the list would hang
            // the reader rather than fail — this is the guard against that.
            val pile = (0 until 60).map { item("X$it", it, it) }
            merge(pile).size shouldBe 1
        }

        @Test
        fun `does not lose text from any box`() {
            val random = Random(20260804)
            repeat(200) {
                val items = (0 until random.nextInt(1, 8)).map {
                    item("W$it", random.nextInt(0, 300), random.nextInt(0, 300))
                }
                val merged = merge(items)
                val words = merged.flatMap { it.text.split(" ") }.filter { it.isNotEmpty() }
                words.sorted() shouldBe items.map { it.text }.sorted()
            }
        }
    }

    @Nested
    inner class Banding {

        @Test
        fun `leaves a region that already fits as one band`() {
            val region = TextBox(0, 0, 800, 1000)
            OcrLayout.splitIntoBands(region, 2560, 160) shouldBe listOf(region)
        }

        @Test
        fun `splits a tall region and covers all of it`() {
            val region = TextBox(0, 0, 800, 6000)
            val bands = OcrLayout.splitIntoBands(region, 2560, 160)
            (bands.size > 1) shouldBe true
            bands.first().top shouldBe 0
            bands.last().bottom shouldBe 6000
            bands.all { it.height <= 2560 } shouldBe true
        }

        @Test
        fun `leaves no gap between bands, so no line can fall through`() {
            val bands = OcrLayout.splitIntoBands(TextBox(0, 0, 800, 6000), 2560, 160)
            bands.zipWithNext().all { (a, b) -> b.top < a.bottom } shouldBe true
        }

        @Test
        fun `keeps the full width of the region`() {
            val bands = OcrLayout.splitIntoBands(TextBox(40, 0, 900, 9000), 2560, 160)
            bands.all { it.left == 40 && it.right == 900 } shouldBe true
        }

        @Test
        fun `terminates even when the overlap swallows the band height`() {
            // A step of zero would loop forever; this pins the guard
            val bands = OcrLayout.splitIntoBands(TextBox(0, 0, 100, 5000), 200, 500)
            (bands.size in 1..5000) shouldBe true
            bands.last().bottom shouldBe 5000
        }
    }

    @Nested
    inner class Deduplication {

        @Test
        fun `drops the same line caught by two overlapping bands`() {
            val first = item("FOR NO REASON", 0, 2400)
            val nearlySame = TextItem("FOR NO REASON", TextBox(2, 2402, 102, 2422))
            OcrLayout.dedupeOverlapping(listOf(first, nearlySame)) shouldBe listOf(first)
        }

        @Test
        fun `keeps the same words when they sit in different places`() {
            val top = item("HUNTER", 0, 0)
            val far = item("HUNTER", 0, 900)
            OcrLayout.dedupeOverlapping(listOf(top, far)) shouldBe listOf(top, far)
        }

        @Test
        fun `keeps different text in the same place`() {
            val a = item("HUNTER", 0, 0)
            val b = item("NOBLE", 0, 0)
            OcrLayout.dedupeOverlapping(listOf(a, b)) shouldBe listOf(a, b)
        }

        @Test
        fun `ignores case when comparing`() {
            val a = item("HUNTER", 0, 0)
            val b = TextItem("hunter", TextBox(1, 1, 101, 21))
            OcrLayout.dedupeOverlapping(listOf(a, b)) shouldBe listOf(a)
        }
    }

    @Nested
    inner class ScriptFallback {

        @Test
        fun `re-reads with latin when a CJK model returned no CJK characters`() {
            // The "swolos manshe" case: the Japanese model fed English
            OcrText.fallbackLanguageFor(TranslationSourceLanguage.JAPANESE, listOf("swolos manshe")) shouldBe
                TranslationSourceLanguage.ENGLISH
        }

        @Test
        fun `keeps the CJK result when it actually contains CJK`() {
            OcrText.fallbackLanguageFor(TranslationSourceLanguage.JAPANESE, listOf("剣術")) shouldBe null
            OcrText.fallbackLanguageFor(TranslationSourceLanguage.KOREAN, listOf("안녕")) shouldBe null
            OcrText.fallbackLanguageFor(TranslationSourceLanguage.CHINESE, listOf("剑术")) shouldBe null
        }

        @Test
        fun `keeps the CJK result when even one block has CJK`() {
            OcrText.fallbackLanguageFor(
                TranslationSourceLanguage.JAPANESE,
                listOf("SFX", "剣術", "!!"),
            ) shouldBe null
        }

        @Test
        fun `re-reads with japanese when the latin model found nothing at all`() {
            OcrText.fallbackLanguageFor(TranslationSourceLanguage.ENGLISH, emptyList()) shouldBe
                TranslationSourceLanguage.JAPANESE
        }

        @Test
        fun `does not re-read when a CJK model found nothing — there is nothing to judge`() {
            OcrText.fallbackLanguageFor(TranslationSourceLanguage.JAPANESE, emptyList()) shouldBe null
        }

        @Test
        fun `does not re-read a latin result that found something`() {
            OcrText.fallbackLanguageFor(TranslationSourceLanguage.ENGLISH, listOf("HELLO")) shouldBe null
        }

        @Test
        fun `recognises every CJK script, not just han`() {
            OcrText.hasCjk("剣") shouldBe true // han
            OcrText.hasCjk("ひらがな") shouldBe true // hiragana
            OcrText.hasCjk("カタカナ") shouldBe true // katakana
            OcrText.hasCjk("한글") shouldBe true // hangul
            OcrText.hasCjk("HELLO") shouldBe false
            OcrText.hasCjk("!?…") shouldBe false
        }
    }

    @Nested
    inner class ReplacedBy {

        // The bubble at 100..300 x 100..200, and the same bubble re-selected
        // by a slightly different drag
        private val first = TextBox(100, 100, 300, 200)
        private val retry = TextBox(90, 95, 310, 210)

        @Test
        fun `a repeat of the same bubble replaces the previous attempt`() {
            // This is the stacking bug: retrying a translation drew a new
            // block over the old one, the old text ghosting through behind it
            OcrLayout.replacedBy(first, retry) shouldBe true
        }

        @Test
        fun `a different bubble on the same page does not evict the first`() {
            val other = TextBox(400, 500, 600, 600)
            OcrLayout.replacedBy(first, other) shouldBe false
        }

        @Test
        fun `a neighbouring bubble touching the edge is not a repeat`() {
            // Shares a 20px strip — a sliver, not a re-selection
            val neighbour = TextBox(280, 100, 480, 200)
            OcrLayout.replacedBy(first, neighbour) shouldBe false
        }

        @Test
        fun `a small block inside a full-page selection is replaced`() {
            // Long-press translates the whole screen; every earlier
            // per-bubble block is being re-read and must yield
            val page = TextBox(0, 0, 1080, 2400)
            OcrLayout.replacedBy(first, page) shouldBe true
        }
    }
}
