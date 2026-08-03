package eu.kanade.tachiyomi.ui.reader.viewer

import android.graphics.Color
import android.graphics.PointF
import android.graphics.RectF
import dev.icerock.moko.resources.StringResource
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.util.lang.invert
import tachiyomi.i18n.MR
import uy.kohesive.injekt.injectLazy

abstract class ViewerNavigation {

    sealed class NavigationRegion(val nameRes: StringResource, val color: Int) {
        data object MENU : NavigationRegion(MR.strings.action_menu, Color.argb(0xCC, 0x95, 0x81, 0x8D))
        data object PREV : NavigationRegion(MR.strings.nav_zone_prev, Color.argb(0xCC, 0xFF, 0x77, 0x33))
        data object NEXT : NavigationRegion(MR.strings.nav_zone_next, Color.argb(0xCC, 0x84, 0xE2, 0x96))
        data object LEFT : NavigationRegion(MR.strings.nav_zone_left, Color.argb(0xCC, 0x7D, 0x11, 0x28))
        data object RIGHT : NavigationRegion(MR.strings.nav_zone_right, Color.argb(0xCC, 0xA6, 0xCF, 0xD5))
    }

    data class Region(
        val rectF: RectF,
        val type: NavigationRegion,
    ) {
        fun invert(invertMode: ReaderPreferences.TappingInvertMode): Region {
            if (invertMode == ReaderPreferences.TappingInvertMode.NONE) return this
            return this.copy(
                rectF = this.rectF.invert(invertMode),
            )
        }
    }

    private var constantMenuRegion: RectF = RectF(0f, 0f, 1f, 0.05f)

    var invertMode: ReaderPreferences.TappingInvertMode = ReaderPreferences.TappingInvertMode.NONE

    protected abstract var regionList: List<Region>

    private val readerPreferences: ReaderPreferences by injectLazy()

    /**
     * Returns regions with the user's per-axis tap-zone sizes applied
     * (regions scale about the screen center: below 100% they shrink toward
     * the edges, enlarging the menu area) and inversion applied.
     */
    fun getRegions(): List<Region> {
        val widthFactor = 100f / readerPreferences.navigationTapZoneWidth.get().coerceIn(30, 150)
        val heightFactor = 100f / readerPreferences.navigationTapZoneHeight.get().coerceIn(30, 150)
        return regionList
            .map { region -> region.copy(rectF = region.rectF.scaleAboutCenter(widthFactor, heightFactor)) }
            .map { it.invert(invertMode) }
    }

    private fun RectF.scaleAboutCenter(widthFactor: Float, heightFactor: Float): RectF {
        // Edges already at the screen border stay pinned to it
        fun map(v: Float, factor: Float) =
            if (v <= 0f || v >= 1f) v else (0.5f + (v - 0.5f) * factor).coerceIn(0f, 1f)
        return RectF(map(left, widthFactor), map(top, heightFactor), map(right, widthFactor), map(bottom, heightFactor))
    }

    fun getAction(pos: PointF): NavigationRegion {
        val x = pos.x
        val y = pos.y
        val region = getRegions().find { it.rectF.contains(x, y) }
        return when {
            region != null -> region.type
            constantMenuRegion.contains(x, y) -> NavigationRegion.MENU
            else -> NavigationRegion.MENU
        }
    }
}
