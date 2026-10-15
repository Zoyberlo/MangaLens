package eu.kanade.presentation.reader.appbars

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.platform.LocalContext
import eu.kanade.tachiyomi.ui.reader.setting.ReaderOrientation
import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
import mihon.feature.translate.ReaderBarAction
import mihon.feature.translate.translationGraph
import mihon.icons.custommaterialsymbols.CustomMaterialSymbols
import mihon.icons.custommaterialsymbols.rounded.CropOff
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Crop
import mihon.icons.materialsymbols.rounded.Keyboard
import mihon.icons.materialsymbols.rounded.Settings
import mihon.icons.materialsymbols.rounded.Translate
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState

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
    val context = LocalContext.current
    val visibleActions by remember { context.translationGraph.translationPreferences.readerBarActions }
        .collectAsState()

    Row(
        modifier = modifier
            .pointerInput(Unit) {},
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (ReaderBarAction.READING_MODE.id in visibleActions) {
            IconButton(onClick = onClickReadingMode) {
                Icon(
                    imageVector = readingMode.icon,
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
                    imageVector = if (cropEnabled) {
                        MaterialSymbols.Rounded.Crop
                    } else {
                        CustomMaterialSymbols.Rounded.CropOff
                    },
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
                    imageVector = MaterialSymbols.Rounded.Translate,
                    contentDescription = stringResource(MR.strings.action_translate_selection),
                )
            }
        }

        if (onClickManualTranslate != null && ReaderBarAction.MANUAL_TRANSLATE.id in visibleActions) {
            IconButton(onClick = onClickManualTranslate) {
                Icon(
                    imageVector = MaterialSymbols.Rounded.Keyboard,
                    contentDescription = stringResource(MR.strings.action_manual_translate),
                )
            }
        }

        IconButton(onClick = onClickSettings) {
            Icon(
                imageVector = MaterialSymbols.Rounded.Settings,
                contentDescription = stringResource(MR.strings.action_settings),
            )
        }
    }
}
