package mihon.feature.translate

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
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
 * Bottom panel shown when the user taps words in a translated block's
 * original text: displays the tapped word/phrase, translation variants as
 * tappable chips, and a save button. Lookup itself is driven by the host
 * (ReaderActivity) through [showLoading]/[showVariants].
 */
@SuppressLint("ViewConstructor", "SetTextI18n")
class WordInspectorView(context: Context) : LinearLayout(context) {

    var onSave: ((word: String, translation: String) -> Unit)? = null
    var onDismiss: (() -> Unit)? = null

    private val dp = context.resources.displayMetrics.density

    private val wordView = TextView(context).apply {
        setTextColor(Color.WHITE)
        textSize = 18f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

    private val progress = ProgressBar(context).apply {
        isIndeterminate = true
    }

    private val chipsRow = LinearLayout(context).apply {
        orientation = HORIZONTAL
    }

    private val saveButton = Button(context).apply {
        text = context.stringResource(MR.strings.action_add)
        isEnabled = false
        setOnClickListener {
            val translation = selectedVariant ?: return@setOnClickListener
            onSave?.invoke(currentWord, translation)
        }
    }

    private val closeButton = Button(context).apply {
        text = context.stringResource(MR.strings.action_cancel)
        setOnClickListener { onDismiss?.invoke() }
    }

    private var currentWord: String = ""
    private var selectedVariant: String? = null
    private val chipViews = mutableListOf<TextView>()

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
                addView(saveButton, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
            },
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
        )
    }

    /** Shows the panel for [word] with a loading spinner. */
    fun showLoading(word: String) {
        currentWord = word
        selectedVariant = null
        wordView.text = word
        chipsRow.removeAllViews()
        chipViews.clear()
        progress.isVisible = true
        saveButton.isEnabled = false
        isVisible = true
    }

    /** Replaces the spinner with variant chips; the first one is preselected. */
    fun showVariants(word: String, variants: List<String>) {
        if (word != currentWord) return
        progress.isVisible = false
        chipsRow.removeAllViews()
        chipViews.clear()
        variants.forEach { variant ->
            val chip = TextView(context).apply {
                text = variant
                setTextColor(Color.WHITE)
                textSize = 15f
                setPadding((12 * dp).toInt(), (8 * dp).toInt(), (12 * dp).toInt(), (8 * dp).toInt())
                setOnClickListener { select(variant) }
            }
            chipsRow.addView(
                chip,
                LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                    marginEnd = (8 * dp).toInt()
                },
            )
            chipViews += chip
        }
        variants.firstOrNull()?.let { select(it) }
    }

    private fun select(variant: String) {
        selectedVariant = variant
        saveButton.isEnabled = true
        chipViews.forEach { chip ->
            chip.background = GradientDrawable().apply {
                cornerRadius = 16 * dp
                setColor(if (chip.text == variant) 0xFF2E7D32.toInt() else 0x33FFFFFF)
            }
        }
    }
}
