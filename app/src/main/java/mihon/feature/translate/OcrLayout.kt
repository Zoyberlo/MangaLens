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

    private const val VERTICAL_GAP_RATIO = 0.9f
    private const val HORIZONTAL_GAP_RATIO = 1.5f
}
