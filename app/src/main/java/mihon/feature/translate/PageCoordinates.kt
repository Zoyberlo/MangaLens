package mihon.feature.translate

import android.graphics.PointF
import android.view.View
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import eu.kanade.tachiyomi.ui.reader.viewer.webtoon.WebtoonStripImageView

/**
 * Maps between a reader page's on-screen coordinates and the image it shows.
 *
 * "Source" is the displayed image's own pixel space; [PageTranslator] rescales
 * it to the file through [sourceWidth], so a downsampled decode is fine.
 *
 * Two views draw pages. The paged reader uses [SubsamplingScaleImageView],
 * which zooms and pans by itself. Since Mihon 0.21 the long strip uses
 * [WebtoonStripImageView], which draws the image at its own width, top-left
 * aligned — its pixels are the source space — and leaves zoom to the recycler,
 * which scales the overlay along with it. Before this existed every
 * translation in long-strip mode silently did nothing: the code only knew the
 * first view.
 */
sealed interface PageCoordinates {
    val sourceWidth: Int
    val sourceHeight: Int

    /** [x], [y] in the page view's coordinates. */
    fun viewToSource(x: Float, y: Float): PointF?

    fun sourceToView(x: Float, y: Float): PointF?

    private class Zoomable(private val view: SubsamplingScaleImageView) : PageCoordinates {
        override val sourceWidth get() = view.sWidth
        override val sourceHeight get() = view.sHeight
        override fun viewToSource(x: Float, y: Float) = view.viewToSourceCoord(x, y)
        override fun sourceToView(x: Float, y: Float) = view.sourceToViewCoord(x, y)
    }

    private class Strip(private val view: WebtoonStripImageView) : PageCoordinates {
        override val sourceWidth get() = view.width
        override val sourceHeight get() = view.height
        override fun viewToSource(x: Float, y: Float) = PointF(x - view.left, y - view.top)
        override fun sourceToView(x: Float, y: Float) = PointF(x + view.left, y + view.top)
    }

    companion object {
        /** Null while the page has nothing laid out to map, and for animated images. */
        fun of(pageView: View?): PageCoordinates? = when (pageView) {
            is SubsamplingScaleImageView -> Zoomable(pageView).takeIf { pageView.isReady }
            is WebtoonStripImageView -> Strip(pageView).takeIf { pageView.width > 0 && pageView.height > 0 }
            else -> null
        }
    }
}
