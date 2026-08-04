package eu.kanade.presentation.more

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.R

/**
 * The full-colour app artwork, not the flat silhouette.
 *
 * That silhouette (`ic_mihon`) has to stay single-colour because Android draws
 * a notification's small icon as a mask — anything else arrives as a white
 * blob — but nothing forces the same restraint on a header, which was showing
 * a tinted monochrome mark while the launcher showed the real thing.
 */
@Composable
fun LogoHeader(
    iconPadding: PaddingValues = PaddingValues(),
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(
            painter = painterResource(R.drawable.ic_launcher_foreground),
            contentDescription = null,
            modifier = Modifier
                .padding(
                    // The artwork's own margin stands in for this much of the
                    // caller's padding, so the header keeps the height it had
                    top = (iconPadding.calculateTopPadding() - SafeZonePadding).coerceAtLeast(0.dp),
                    bottom = (iconPadding.calculateBottomPadding() - SafeZonePadding).coerceAtLeast(0.dp),
                )
                .size(CanvasSize),
        )

        HorizontalDivider()
    }
}

/**
 * The launcher artwork is drawn for an adaptive icon, so it carries a wide
 * transparent margin: on the 648px canvas of `ic_launcher_foreground.png` the
 * mark itself spans 360px, or 56%. Drawn at [MarkSize] it would look far
 * smaller than the silhouette it replaces, so the canvas is scaled up by that
 * factor and the resulting margin taken back out of the caller's padding.
 */
private val MarkSize = 64.dp
private val CanvasSize = MarkSize / 0.56f
private val SafeZonePadding = (CanvasSize - MarkSize) / 2
