package mihon.feature.translate

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import eu.kanade.tachiyomi.util.system.toast
import tachiyomi.i18n.MR
import uy.kohesive.injekt.injectLazy

/**
 * Speech-to-text for the translation editor.
 *
 * Runs the recognizer in-process rather than firing
 * `ACTION_RECOGNIZE_SPEECH` for a system dialog, because that dialog covers
 * the page the reader is trying to read off.
 *
 * The activity keeps only the permission launcher — that has to be registered
 * on the activity itself, before it starts — and hands the result back through
 * [onPermissionResult].
 *
 * @param editor the panel being dictated into, or null when it is not open.
 */
class ReaderDictation(
    private val activity: Activity,
    private val editor: () -> WordInspectorView?,
    private val requestPermission: () -> Unit,
) {

    private val translationPreferences: TranslationPreferences by injectLazy()

    private var recognizer: SpeechRecognizer? = null
    private var listening = false

    /** Editor content when dictation started, so speech appends instead of replacing. */
    private var prefix = ""

    /** Toggles: a second tap on the mic stops it. */
    fun toggle() {
        if (listening) {
            stop()
            return
        }
        val granted = ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) start() else requestPermission()
    }

    /** Call from the activity's permission callback. */
    fun onPermissionResult(granted: Boolean) {
        if (granted) start() else activity.toast(MR.strings.voice_input_permission_required)
    }

    fun stop() {
        if (!listening) return
        listening = false
        editor()?.setListening(false)
        recognizer?.stopListening()
    }

    fun destroy() {
        recognizer?.destroy()
        recognizer = null
        listening = false
    }

    private fun start() {
        if (!SpeechRecognizer.isRecognitionAvailable(activity)) {
            activity.toast(MR.strings.voice_input_unavailable)
            return
        }
        val editor = editor() ?: return
        val recognizer = recognizer
            ?: SpeechRecognizer.createSpeechRecognizer(activity).also { this.recognizer = it }

        prefix = editor.editorText().trimEnd()
        recognizer.setRecognitionListener(listener)

        val language = translationPreferences.autoTranslateSourceLanguage.get().langCode
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            // Some recognizer implementations reject requests without it
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, activity.packageName)
            if (language.isNotBlank() && language != "auto") {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
            }
        }
        listening = true
        editor.setListening(true)
        recognizer.startListening(intent)
    }

    /** Writes [spoken] after whatever was already in the editor. */
    private fun apply(spoken: String) {
        if (spoken.isBlank()) return
        editor()?.setEditorText(if (prefix.isBlank()) spoken else "$prefix $spoken")
    }

    private val listener = object : RecognitionListener {
        override fun onPartialResults(partialResults: Bundle?) {
            partialResults?.spokenText()?.let(::apply)
        }

        override fun onResults(results: Bundle?) {
            results?.spokenText()?.let(::apply)
            listening = false
            editor()?.setListening(false)
        }

        override fun onError(error: Int) {
            listening = false
            editor()?.setListening(false)
            // Silence and "no match" are normal ways to stop talking, not failures
            if (error != SpeechRecognizer.ERROR_NO_MATCH && error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                activity.toast(MR.strings.voice_input_unavailable)
            }
        }

        override fun onEndOfSpeech() {
            editor()?.setListening(false)
        }

        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private fun Bundle.spokenText(): String? =
        getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }
}
