package mihon.feature.translate

import dev.icerock.moko.resources.StringResource
import tachiyomi.i18n.MR

/**
 * A button the reader's bottom bar can be told to show or hide.
 *
 * The settings gear is deliberately absent: it is the way back to this very
 * list, so hiding it would strand the user with a bar they cannot change.
 *
 * [id] is what gets stored, so **never rename one** — a changed id reads as an
 * unknown action and the button silently disappears for everyone who had it on.
 */
enum class ReaderBarAction(val id: String, val label: StringResource) {
    READING_MODE("reading_mode", MR.strings.viewer),
    ORIENTATION("orientation", MR.strings.rotation_type),
    CROP_BORDERS("crop_borders", MR.strings.pref_crop_borders),
    TRANSLATE("translate", MR.strings.action_translate_selection),
    MANUAL_TRANSLATE("manual_translate", MR.strings.action_manual_translate),
    ;

    companion object {
        /** Everything on, which is what the bar looked like before it was configurable. */
        val DEFAULT: Set<String> = entries.map { it.id }.toSet()
    }
}
