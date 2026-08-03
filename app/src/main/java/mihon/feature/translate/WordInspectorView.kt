package mihon.feature.translate

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.BackgroundColorSpan
import android.text.style.ClickableSpan
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.core.view.setPadding
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR

/**
 * Bottom panel for word lookup and panel-mode translation results. The shown
 * original text is word-tappable in both modes: taps pick a word, further
 * taps extend the pick to a phrase (highlighted), and the host is asked for
 * variants of the current pick through [onPhraseTap]. Read-only — nothing is
 * saved anywhere.
 */
@SuppressLint("ViewConstructor", "SetTextI18n")
class WordInspectorView(context: Context) : LinearLayout(context) {

    var onDismiss: (() -> Unit)? = null

    /** Asked to look up variants whenever the picked word/phrase changes. */
    var onPhraseTap: ((String) -> Unit)? = null

    private val dp = context.resources.displayMetrics.density

    private val wordView = TextView(context).apply {
        setTextColor(Color.WHITE)
        textSize = 17f
        maxLines = 8
        movementMethod = LinkMovementMethod.getInstance()
        highlightColor = Color.TRANSPARENT
    }

    private val progress = ProgressBar(context).apply {
        isIndeterminate = true
    }

    private val chipsRow = LinearLayout(context).apply {
        orientation = HORIZONTAL
    }

    private val closeButton = Button(context).apply {
        text = context.stringResource(MR.strings.action_cancel)
        setOnClickListener { onDismiss?.invoke() }
    }

    private var fullText: String = ""
    private var currentSelection: String = ""
    private var selStart = -1
    private var selEnd = -1

    // Swipe-down-to-dismiss
    private var swipeStartY = -1f

    override fun onInterceptTouchEvent(ev: android.view.MotionEvent): Boolean {
        when (ev.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> swipeStartY = ev.y
            android.view.MotionEvent.ACTION_MOVE -> {
                if (swipeStartY >= 0 && ev.y - swipeStartY > SWIPE_DISMISS_DP * dp) {
                    swipeStartY = -1f
                    onDismiss?.invoke()
                    return true
                }
            }
            android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> swipeStartY = -1f
        }
        return super.onInterceptTouchEvent(ev)
    }

    init {
        orientation = VERTICAL
        setPadding((16 * dp).toInt())
        background = GradientDrawable().apply {
            setColor(0xF2263238.toInt())
            cornerRadii = floatArrayOf(16 * dp, 16 * dp, 16 * dp, 16 * dp, 0f, 0f, 0f, 0f)
        }
        isClickable = true // consume touches so they don't reach the page

        addView(wordView)
        addView(progress, LayoutParams((24 * dp).toInt(), (24 * dp).toInt()).apply { topMargin = (8 * dp).toInt() })
        addView(
            HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
                addView(chipsRow)
            },
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = (8 * dp).toInt() },
        )
        addView(
            LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.END
                addView(closeButton)
            },
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
        )
    }

    /** Shows the panel for [word] with a loading spinner for its variants. */
    fun showLoading(word: String) {
        setText(word)
        showChipsLoading()
    }

    /**
     * Panel-mode result: shows the original with its translation as the
     * preselected variant; words remain tappable for narrower lookups.
     */
    fun showResult(original: String, translation: String) {
        setText(original)
        showVariantsFor(original, listOf(translation))
    }

    /** Replaces the spinner with variant chips for the current pick. */
    fun showVariantsFor(selection: String, variants: List<String>) {
        if (selection != currentSelection) return
        progress.isVisible = false
        chipsRow.removeAllViews()
        variants.forEach { variant ->
            val chip = TextView(context).apply {
                text = variant
                setTextColor(Color.WHITE)
                textSize = 15f
                setPadding((12 * dp).toInt(), (8 * dp).toInt(), (12 * dp).toInt(), (8 * dp).toInt())
                background = GradientDrawable().apply {
                    cornerRadius = 16 * dp
                    setColor(0x33FFFFFF)
                }
            }
            chipsRow.addView(
                chip,
                LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                    marginEnd = (8 * dp).toInt()
                },
            )
        }
    }

    private fun setText(text: String) {
        fullText = text
        currentSelection = text
        selStart = -1
        selEnd = -1
        renderText()
        isVisible = true
    }

    private fun showChipsLoading() {
        progress.isVisible = true
        chipsRow.removeAllViews()
    }

    private fun isWordChar(c: Char) = c.isLetterOrDigit() || c == '\'' || c == '’' || c == '-'

    private fun renderText() {
        val spannable = SpannableString(fullText)
        var i = 0
        while (i < fullText.length) {
            if (!isWordChar(fullText[i])) {
                i++
                continue
            }
            val start = i
            var end = i
            while (end < fullText.length && isWordChar(fullText[end])) end++
            spannable.setSpan(
                object : ClickableSpan() {
                    override fun onClick(widget: View) = onWordSpanTapped(start, end)
                    override fun updateDrawState(ds: TextPaint) {
                        ds.isUnderlineText = false
                        ds.color = Color.WHITE
                    }
                },
                start,
                end,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            i = end
        }
        if (selStart in 0 until selEnd) {
            spannable.setSpan(
                BackgroundColorSpan(0x5942A5F5),
                selStart,
                selEnd.coerceAtMost(fullText.length),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
        wordView.text = spannable
    }

    private companion object {
        const val SWIPE_DISMISS_DP = 56f
    }

    /**
     * A tap picks that one word; tapping a word directly next to the pick
     * extends it into a phrase; tapping the pick again clears it and returns
     * to the whole text. Words are never selected just for sitting between
     * two taps.
     */
    private fun onWordSpanTapped(start: Int, end: Int) {
        when {
            selStart < 0 -> {
                selStart = start
                selEnd = end
            }
            start >= selStart && end <= selEnd -> {
                selStart = -1
                selEnd = -1
                currentSelection = fullText
                renderText()
                showChipsLoading()
                onPhraseTap?.invoke(fullText)
                return
            }
            end <= selStart && isWordless(end, selStart) -> selStart = start
            start >= selEnd && isWordless(selEnd, start) -> selEnd = end
            else -> {
                selStart = start
                selEnd = end
            }
        }
        val phrase = fullText.substring(selStart, selEnd.coerceAtMost(fullText.length)).trim('\'', '’', '-', ' ')
        if (phrase.isBlank()) return
        currentSelection = phrase
        renderText()
        showChipsLoading()
        onPhraseTap?.invoke(phrase)
    }

    /** True when the range holds only separators, i.e. the words are neighbours. */
    private fun isWordless(from: Int, to: Int): Boolean {
        if (from >= to) return true
        return (from until to.coerceAtMost(fullText.length)).none { fullText[it].isLetterOrDigit() }
    }
}
