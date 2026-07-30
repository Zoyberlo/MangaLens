# Feature: Reader (upstream)

Upstream Mihon code. Documented here only to the depth the fork needs — the source
is the truth, and this file should stay a map, not a copy.

## Entry point

`ui/reader/ReaderActivity.kt` — the only reader Activity. Layout
`res/layout/reader_activity.xml`:

- `reader_container` (root)
  - `viewer_container` — FrameLayout that holds the current View-based viewer
  - `ReaderNavigationOverlayView` — tap-zone hints
  - `compose_overlay` — full-screen `ComposeView` for app bars, dialogs, brightness
    and color-filter overlays

`ReaderViewModel` owns state and chapter loading; `ReaderSettingsViewModel` backs
the settings dialog.

## Viewers

| Reading mode | Class | Notes |
|--------------|-------|-------|
| Paged (L2R / R2L / vertical) | `viewer/pager/PagerViewer.kt` (+ `PagerViewers.kt`) | `DirectionalViewPager`, one `PagerPageHolder` per page |
| Webtoon / continuous vertical | `viewer/webtoon/WebtoonViewer.kt` | `WebtoonRecyclerView` (zoomable RecyclerView) with `WebtoonPageHolder`s |

`ReadingMode.isPagerType(flag)` distinguishes them. The per-manga mode is stored as
a viewer flag on the manga (`ReaderViewModel.getMangaReadingMode()` /
`setMangaReadingMode()`); `ReadingMode.DEFAULT` means "use the global default".

**Fork addition:** `ReaderViewModel.maybeAutoDetectWebtoon()` bounds-decodes the
first ready page after a chapter loads and switches the series to webtoon mode when
it is ≥3× taller than wide — but only when the manga has no explicit per-series
mode, so a manual choice always wins. Runs once per reader session.

## Page holders & images

Both holders follow the same shape:

1. `loadPageAndProcessStatus()` collects `page.statusFlow`
   (`Queue → LoadPage → DownloadImage → Ready`).
2. On `Ready`, `setImage()` reads `page.stream!!` on IO, passes the bytes through
   `process(...)` (dual-page split / rotate-to-fit via `ImageUtil`), and hands a
   `BufferedSource` to `ReaderPageImageView.setImage(...)`.
3. `ReaderPageImageView` picks `SubsamplingScaleImageView` (static) or
   `PhotoView`/`AppCompatImageView` (animated), and reports back through
   `onImageLoaded` / `onImageLoadError` / `onScaleChanged` / `onViewClicked`.

Useful properties of `SubsamplingScaleImageView` (SSIV):

- `sWidth`/`sHeight` — dimensions of the **displayed source** image, which may be
  downsampled relative to the original file (long strips go through Coil).
- `sourceToViewCoord()` / `viewToSourceCoord()` — the mapping overlays need.
- `setOnStateChangedListener` — fires on scale and center changes.

`page.stream` can be re-read at any time; that is how the fork obtains
full-resolution pixels for OCR (`processes`-free path via `BitmapRegionDecoder`).

## Gestures

- `viewer/GestureDetectorWithLongTap.kt` → `Listener.onLongTapConfirmed`
- pager: `pager.longTapListener` → `ReaderActivity.onPageLongTap(page)` →
  `ReaderPageActionsDialog`
- tap zones: `viewer/ViewerNavigation.kt` + `viewer/navigation/*`

A View added on top of the page (like the fork's overlay) must return `false` from
`onTouchEvent` for touches it does not consume, or it will swallow page navigation.

## Settings dialog

`presentation/reader/settings/ReaderSettingsDialog.kt` — a `TabbedDialog` whose tab
titles and `when (page)` branches must stay in sync. Tabs: Reading mode, General,
Color filter, **Translation** (fork).
