package mihon.feature.translate

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.BackgroundColorSpan
import android.text.style.ClickableSpan
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.getSystemService
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.setPadding
import eu.kanade.tachiyomi.R
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR

/**
 * Bottom panel for translation results and lookups, in two modes.
 *
 * *Reading* — the original text is word-tappable: taps pick a word, further
 * taps extend the pick to a phrase (highlighted), and the host is asked for
 * variants of the current pick through [onPhraseTap].
 *
 * *Editing* — the same text becomes an [EditText] so OCR mistakes can be
 * corrected, or text typed/dictated from scratch, and re-translated through
 * [onTextSubmitted]. Nothing is saved anywhere in either mode.
 */
@SuppressLint("ViewConstructor", "SetTextI18n")
class WordInspectorView(context: Context) : LinearLayout(context) {

    var onDismiss: (() -> Unit)? = null

    /** Asked to look up variants whenever the picked word/phrase changes. */
    var onPhraseTap: ((String) -> Unit)? = null

    /** Asked to translate the edited text when the user confirms it. */
    var onTextSubmitted: ((String) -> Unit)? = null

    /** Asked to start speech recognition; the host feeds [setEditorText] back. */
    var onVoiceInput: (() -> Unit)? = null

    private val dp = context.resources.displayMetrics.density

    private val wordView = TextView(context).apply {
        setTextColor(Color.WHITE)
        textSize = 17f
        maxLines = 12
        // LinkMovementMethod also scrolls the text when it outgrows the panel
        movementMethod = LinkMovementMethod.getInstance()
        highlightColor = Color.TRANSPARENT
    }

    private val editView = EditText(context).apply {
        setTextColor(Color.WHITE)
        setHintTextColor(0x80FFFFFF.toInt())
        textSize = 17f
        maxLines = 6
        background = null
        setPadding(0, 0, 0, 0)
        hint = context.stringResource(MR.strings.translate_editor_hint)
        inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_FLAG_MULTI_LINE or
            InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        isVisible = false
    }

    private val progress = ProgressBar(context).apply {
        isIndeterminate = true
    }

    private val chipsRow = LinearLayout(context).apply {
        orientation = HORIZONTAL
    }

    private val chipsScroll = HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false
        addView(chipsRow)
    }

    private fun iconButton(iconRes: Int, description: String, onClick: () -> Unit) =
        ImageButton(context).apply {
            setImageResource(iconRes)
            imageTintList = android.content.res.ColorStateList.valueOf(0xDEFFFFFF.toInt())
            background = null
            setPadding((8 * dp).toInt())
            contentDescription = description
            setOnClickListener { onClick() }
        }

    private val editButton = iconButton(
        R.drawable.ic_edit_24dp,
        context.stringResource(MR.strings.action_edit_text),
    ) { startEditing(fullText) }

    private val micButton = iconButton(
        R.drawable.ic_mic_24dp,
        context.stringResource(MR.strings.action_voice_input),
    ) { onVoiceInput?.invoke() }

    private val applyButton = TextView(context).apply {
        text = context.stringResource(MR.strings.action_translate)
        setTextColor(Color.WHITE)
        textSize = 15f
        setPadding((16 * dp).toInt(), (8 * dp).toInt(), (16 * dp).toInt(), (8 * dp).toInt())
        background = GradientDrawable().apply {
            cornerRadius = 16 * dp
            setColor(0xFF1565C0.toInt())
        }
        setOnClickListener { submitEditedText() }
    }

    private val editorRow = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = android.view.Gravity.CENTER_VERTICAL
        isVisible = false
        addView(micButton, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        addView(
            applyButton,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginStart = (8 * dp).toInt() },
        )
    }

    private val closeButton = TextView(context).apply {
        text = "✕"
        setTextColor(0xB3FFFFFF.toInt())
        textSize = 18f
        setPadding((12 * dp).toInt(), (4 * dp).toInt(), (4 * dp).toInt(), (8 * dp).toInt())
        contentDescription = context.stringResource(MR.strings.action_close)
        setOnClickListener { onDismiss?.invoke() }
    }

    private var fullText: String = ""
    private var currentSelection: String = ""
    private var selStart = -1
    private var selEnd = -1

    private var isEditing = false

    /** True while the editor is open, so the host can leave the panel alone. */
    val isEditingText: Boolean get() = isEditing

    // Swipe-down-to-dismiss
    private var swipeStartY = -1f

    override fun onInterceptTouchEvent(ev: android.view.MotionEvent): Boolean {
        // While editing, vertical drags belong to the text field and the caret
        if (isEditing) return super.onInterceptTouchEvent(ev)
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

        // Keep content clear of the navigation bar — or of the keyboard while
        // editing — while the panel itself stays flush with the screen bottom
        ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            view.setPadding(
                (16 * dp).toInt(),
                (16 * dp).toInt(),
                (16 * dp).toInt(),
                (16 * dp).toInt() + maxOf(bars, ime),
            )
            insets
        }

        // Text with the edit and close affordances in its top-right corner, so
        // the panel spends its height on content instead of a button row
        addView(
            LinearLayout(context).apply {
                orientation = HORIZONTAL
                addView(
                    FrameLayout(context).apply {
                        addView(wordView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
                        addView(editView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
                    },
                    LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f),
                )
                addView(editButton, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
                addView(closeButton, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
            },
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
        )
        addView(progress, LayoutParams((24 * dp).toInt(), (24 * dp).toInt()).apply { topMargin = (8 * dp).toInt() })
        addView(
            chipsScroll,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = (8 * dp).toInt() },
        )
        addView(
            editorRow,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = (8 * dp).toInt() },
        )
    }

    /** Shows the panel for [word] with a loading spinner for its variants. */
    fun showLoading(word: String) {
        stopEditing()
        setText(word)
        showChipsLoading()
    }

    /**
     * Panel-mode result: shows the original with its translation as the
     * preselected variant; words remain tappable for narrower lookups.
     */
    fun showResult(original: String, translation: String) {
        stopEditing()
        setText(original)
        showVariantsFor(original, listOf(translation))
    }

    /** Replaces the spinner with variant chips for the current pick. */
    fun showVariantsFor(selection: String, variants: List<String>) {
        if (selection != currentSelection || isEditing) return
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

    // region editing

    /**
     * Opens the editor on [initial] (empty for typing or dictating from
     * scratch) and raises the keyboard.
     */
    fun startEditing(initial: String) {
        isEditing = true
        fullText = initial
        currentSelection = initial
        selStart = -1
        selEnd = -1
        editView.setText(initial)
        editView.setSelection(initial.length)
        editView.isVisible = true
        wordView.isVisible = false
        editButton.isVisible = false
        editorRow.isVisible = true
        progress.isVisible = false
        chipsScroll.isVisible = false
        isVisible = true
        editView.requestFocus()
        // Post so the keyboard is asked for after the panel is laid out
        editView.post {
            context.getSystemService<InputMethodManager>()
                ?.showSoftInput(editView, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    /** Replaces the editor's content, e.g. with a speech-recognition result. */
    fun setEditorText(text: String) {
        if (!isEditing) {
            startEditing(text)
            return
        }
        editView.setText(text)
        editView.setSelection(text.length)
    }

    /** Shows a spinner while the edited text is being translated. */
    fun showTranslating() {
        stopEditing()
        progress.isVisible = true
        chipsRow.removeAllViews()
    }

    private fun submitEditedText() {
        val text = editView.text.toString().trim()
        if (text.isBlank()) return
        onTextSubmitted?.invoke(text)
    }

    private fun stopEditing() {
        if (isEditing) {
            context.getSystemService<InputMethodManager>()
                ?.hideSoftInputFromWindow(editView.windowToken, 0)
        }
        isEditing = false
        editView.isVisible = false
        editorRow.isVisible = false
        wordView.isVisible = true
        editButton.isVisible = true
        chipsScroll.isVisible = true
    }

    /** Drops focus and the keyboard when the panel is hidden by the host. */
    fun onHidden() = stopEditing()

    // endregion

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
