package mihon.feature.translate

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import eu.kanade.tachiyomi.util.system.toast
import tachiyomi.i18n.MR

/**
 * Sends a translated block to the companion vocabulary app (words-app) through
 * its deep link. The words app opens on its Add screen with the fields
 * prefilled; saving happens there.
 */
object WordsAppBridge {

    /**
     * Opens words-app with the original text and its translation. Shows a
     * toast when the app is not installed.
     */
    fun saveWord(context: Context, block: TranslatedBlock) {
        val uri = Uri.parse("wordsapp://add").buildUpon()
            .appendQueryParameter("word", block.sourceText)
            .appendQueryParameter("translation", block.translatedText)
            .build()
        val intent = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            context.toast(MR.strings.translate_save_no_app)
        }
    }
}
