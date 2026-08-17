package eu.kanade.presentation.reader.appbars

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.ui.reader.setting.ReaderOrientation
import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
import mihon.feature.translate.ReaderBarAction
import mihon.feature.translate.TranslationPreferences
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

@Composable
fun ReaderBottomBar(
    readingMode: ReadingMode,
    onClickReadingMode: () -> Unit,
    orientation: ReaderOrientation,
    onClickOrientation: () -> Unit,
    cropEnabled: Boolean,
    onClickCropBorder: () -> Unit,
    onClickTranslateSelection: (() -> Unit)?,
    onLongClickTranslateSelection: (() -> Unit)?,
    onClickManualTranslate: (() -> Unit)?,
    onClickSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Fork: which buttons the user chose to keep. Read here rather than passed
    // down, so the change stops at this file instead of threading a parameter
    // through ReaderAppBars as well. The gear is deliberately not gated — it is
    // the way back to the screen that edits this list.
    val visibleActions by remember { Injekt.get<TranslationPreferences>().readerBarActions }.collectAsState()

    Row(
        modifier = modifier
            .pointerInput(Unit) {},
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (ReaderBarAction.READING_MODE.id in visibleActions) {
            IconButton(onClick = onClickReadingMode) {
                Icon(
                    painter = painterResource(readingMode.iconRes),
                    contentDescription = stringResource(MR.strings.viewer),
                )
            }
        }
        if (ReaderBarAction.ORIENTATION.id in visibleActions) {
            IconButton(onClick = onClickOrientation) {
                Icon(
                    imageVector = orientation.icon,
                    contentDescription = stringResource(MR.strings.rotation_type),
                )
            }
        }
        if (ReaderBarAction.CROP_BORDERS.id in visibleActions) {
            IconButton(onClick = onClickCropBorder) {
                Icon(
                    painter = painterResource(
                        if (cropEnabled) R.drawable.ic_crop_24dp else R.drawable.ic_crop_off_24dp,
                    ),
                    contentDescription = stringResource(MR.strings.pref_crop_borders),
                )
            }
        }
        if (onClickTranslateSelection != null && ReaderBarAction.TRANSLATE.id in visibleActions) {
            // Tap = select an area; long-press = translate the whole page
            Box(
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .clip(CircleShape)
                    .combinedClickable(
                        onClick = onClickTranslateSelection,
                        onLongClick = onLongClickTranslateSelection,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Translate,
                    contentDescription = stringResource(MR.strings.action_translate_selection),
                )
            }
        }

        if (onClickManualTranslate != null && ReaderBarAction.MANUAL_TRANSLATE.id in visibleActions) {
            IconButton(onClick = onClickManualTranslate) {
                Icon(
                    imageVector = Icons.Outlined.Keyboard,
                    contentDescription = stringResource(MR.strings.action_manual_translate),
                )
            }
        }

        IconButton(onClick = onClickSettings) {
            Icon(
                imageVector = Icons.Outlined.Settings,
                contentDescription = stringResource(MR.strings.action_settings),
            )
        }
    }
}
