package mihon.feature.translate

/**
 * Axis-aligned box in image pixels — the Android-free half of a
 * [RecognizedBlock]. The recognizer hands back boxes and strings; everything
 * this app then decides about them is plain arithmetic, so it lives here where
 * a test can reach it without a device or an image.
 */
data class TextBox(val left: Int, val top: Int, val right: Int, val bottom: Int) {

    val width: Int get() = right - left
    val height: Int get() = bottom - top

    fun union(other: TextBox) = TextBox(
        left = minOf(left, other.left),
        top = minOf(top, other.top),
        right = maxOf(right, other.right),
        bottom = maxOf(bottom, other.bottom),
    )
}

/** A recognized string and where it sat on the page. */
data class TextItem(val text: String, val box: TextBox)

/**
 * Turns the boxes a recognizer returns into the blocks the reader shows.
 *
 * ML Kit reports a speech bubble as one box per line, which would be
 * translated a line at a time — badly, since each line is a sentence fragment.
 * These rules put the bubble back together.
 */
object OcrLayout {

    /**
     * Repeatedly unions the first pair of boxes close enough to belong to the
     * same bubble, until nothing else can be joined. Each pass removes one
     * item, so at most `size - 1` merges happen and the loop always ends.
     */
    fun merge(items: List<TextItem>, language: TranslationSourceLanguage): List<TextItem> {
        // Japanese and Chinese do not put spaces between the lines of a bubble
        val separator = when (language) {
            TranslationSourceLanguage.JAPANESE, TranslationSourceLanguage.CHINESE -> ""
            else -> " "
        }
        val list = items.toMutableList()
        var changed = true
        while (changed) {
            changed = false
            outer@ for (i in list.indices) {
                for (j in i + 1 until list.size) {
                    if (!shouldMerge(list[i].box, list[j].box)) continue
                    val ordered = orderForReading(list[i], list[j], language)
                    list[i] = TextItem(
                        text = ordered.joinToString(separator) { it.text },
                        box = list[i].box.union(list[j].box),
                    )
                    list.removeAt(j)
                    changed = true
                    break@outer
                }
            }
        }
        return list
    }

    /**
     * Whether two boxes are close enough to be lines of one bubble. Distance is
     * measured in units of the smaller box's height, so the rule holds at any
     * zoom and for any lettering size. A negative gap means they overlap.
     */
    fun shouldMerge(a: TextBox, b: TextBox): Boolean {
        val lineSize = minOf(a.height, b.height).coerceAtLeast(1)
        val verticalGap = maxOf(a.top, b.top) - minOf(a.bottom, b.bottom)
        val horizontalGap = maxOf(a.left, b.left) - minOf(a.right, b.right)
        return verticalGap < lineSize * VERTICAL_GAP_RATIO && horizontalGap < lineSize * HORIZONTAL_GAP_RATIO
    }

    /**
     * Reading order for two boxes about to be joined. Boxes on the same row are
     * ordered horizontally — right to left for Japanese, whose vertical columns
     * run that way — and otherwise top to bottom.
     */
    fun orderForReading(
        a: TextItem,
        b: TextItem,
        language: TranslationSourceLanguage,
    ): List<TextItem> {
        val lineSize = minOf(a.box.height, b.box.height).coerceAtLeast(1)
        val sameRow = kotlin.math.abs(a.box.top - b.box.top) < lineSize / 2
        return when {
            !sameRow -> listOf(a, b).sortedBy { it.box.top }
            language == TranslationSourceLanguage.JAPANESE -> listOf(a, b).sortedByDescending { it.box.left }
            else -> listOf(a, b).sortedBy { it.box.left }
        }
    }

    /**
     * Splits a tall region into horizontal bands no taller than [maxHeight],
     * overlapping by [overlap] so no line of text falls on a seam.
     *
     * Without this a generous selection is downsampled to fit the recognizer's
     * input limit, which shrinks the lettering and loses the smaller lines
     * outright — selecting *more* of a bubble made it read *worse*, which is
     * the opposite of what anyone expects.
     */
    fun splitIntoBands(region: TextBox, maxHeight: Int, overlap: Int): List<TextBox> {
        if (region.height <= maxHeight || maxHeight <= 0) return listOf(region)
        val step = (maxHeight - overlap).coerceAtLeast(1)
        val bands = mutableListOf<TextBox>()
        var top = region.top
        while (top < region.bottom) {
            val bottom = (top + maxHeight).coerceAtMost(region.bottom)
            bands += TextBox(region.left, top, region.right, bottom)
            if (bottom >= region.bottom) break
            top += step
        }
        return bands
    }

    /**
     * Drops repeats of the same text produced by the overlap between bands.
     * Without it a line caught by two bands is merged into "TEXT TEXT".
     */
    fun dedupeOverlapping(items: List<TextItem>): List<TextItem> {
        val kept = mutableListOf<TextItem>()
        for (item in items) {
            val duplicate = kept.any { other ->
                other.text.equals(item.text, ignoreCase = true) && overlapRatio(other.box, item.box) > DUPLICATE_OVERLAP
            }
            if (!duplicate) kept += item
        }
        return kept
    }

    /** Shared area over the smaller box, so a sliver never counts as a repeat. */
    private fun overlapRatio(a: TextBox, b: TextBox): Float {
        val width = minOf(a.right, b.right) - maxOf(a.left, b.left)
        val height = minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)
        if (width <= 0 || height <= 0) return 0f
        val smaller = minOf(a.width * a.height, b.width * b.height)
        if (smaller <= 0) return 0f
        return width.toFloat() * height / smaller
    }

    private const val DUPLICATE_OVERLAP = 0.5f
    private const val VERTICAL_GAP_RATIO = 0.9f
    private const val HORIZONTAL_GAP_RATIO = 1.5f
}
