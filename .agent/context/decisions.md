# Design decisions — the "why"

Intentional, non-obvious choices in the fork. They look like bugs or clutter if you
don't know the reason — **do not "clean them up"** without understanding the
trade-off. If you change one, update this file.

## Manual selection instead of automatic page translation

**What:** There is no "translate every page" toggle. The user drags a rect and only
that area is translated.
**Why:** The first implementation did translate every page automatically. In
practice it burned network and battery on pages the reader never looked at, and
whole-page OCR on a downsampled image was *less* accurate than region OCR at full
resolution. The user asked for it to be removed.
**Don't:** Re-add a background per-page pipeline without agreeing it first. The
plumbing that made it possible (page cache, per-page keys) was deleted deliberately.

## Vocabulary saving was built, then removed

**What:** There is no way to save words. `VocabularyStore`, the Saved words
screen, known-word highlighting and the words-app deep-link bridge all existed
and were deleted (see `git log -- app/src/main/java/mihon/feature/translate`).
**Why:** The owner's call after using it: people reach for this app to *read*
with easy translation, not to build a study deck, and nobody revisits saved
words. Keeping a half-used feature meant extra buttons on every bubble.
**Don't:** Re-add saving without agreeing it first — the panel is intentionally
a read-only lookup. The code is recoverable from git history if that changes.

## A fork, not a Mihon extension

**What:** The feature is compiled into the app.
**Why:** Mihon extensions are *content sources* — they implement a catalog API and
have no hooks into the reader UI. There is no way for an extension to draw over a
page or intercept rendering.
**Don't:** Try to repackage this as an extension; it would require inventing a UI
plugin system first.

## `applicationId` changed, package names untouched

**What:** `applicationId = "app.mihon.tl"`, but the namespace stays
`eu.kanade.tachiyomi`.
**Why:** A different `applicationId` lets the fork install alongside official Mihon.
Renaming packages would touch every file in the repo and conflict with every
upstream merge, for no user-visible benefit.

## OCR runs on a padded region, results filtered by the original selection

**What:** `PageTranslator.translateRegion()` insets the selection by −35% before
decoding, then keeps blocks that intersect the *unpadded* rect.
**Why:** Users select bubbles roughly. Without padding, a selection cutting through
a line makes ML Kit see half-words; without the filter afterwards, a padded region
would pull in neighbouring bubbles.
**Don't:** Drop either half — they only work as a pair.

## Adjacent OCR blocks are merged before translating

**What:** `mergeBlocks()` unions blocks whose gap is under ~one line height and
joins their text.
**Why:** ML Kit returns one block per *line* for comic lettering. Translating lines
separately produced garbage ("HE DIDN'T" → "ВІН НІ"). One bubble must reach the
translator as one sentence.
**Don't:** Assume block order equals reading order — Japanese vertical columns are
ordered right-to-left in `orderForReading()`.

## All-caps text is converted to sentence case before translating

**What:** `TextTranslator.normalizeForTranslation()`.
**Why:** Comic lettering is all-caps, and every machine-translation backend degrades
badly on it — words get dropped or invented. Sentence case fixed a translation that
had lost an adjective and produced a non-word.
**Don't:** Remove it, and keep the standalone-"I" re-capitalization: lowercasing
English "I" is a real quality loss.

## Region coordinates are rescaled through `sourceWidth`

**What:** Holders pass `ReaderPageImageView.sourceWidth()` into
`translateRegion(...)`, which scales the rect by
`originalWidth / displayedSourceWidth`.
**Why:** Long webtoon strips are decoded through Coil at a reduced size, so SSIV's
source coordinates are **not** the file's pixel coordinates. Without the rescale,
OCR reads the wrong part of the image on exactly the content the feature is most
used for.
**Don't:** Assume `sWidth` equals the file width.

## The overlay is a sibling View, not a Compose layer

**What:** `TranslationOverlayView` is added as a child of `ReaderPageImageView`,
next to the image view.
**Why:** Boxes must be pinned to *image* coordinates through pan and zoom, which
requires `SubsamplingScaleImageView.sourceToViewCoord()` and its state-changed
callback. The Compose overlay (`compose_overlay`) is screen-space and sits above the
whole viewer — fine for dialogs, useless for per-page geometry.
**Don't:** Move it to Compose without solving the coordinate mapping.

## The overlay declines touches it does not use

**What:** `onTouchEvent` returns `false` when the down event misses every box.
**Why:** The overlay covers the whole page. Consuming all touches would break page
turns, zoom and the menu toggle.
**Don't:** Return `true` unconditionally, and do not make the view `clickable`.

## Translation text size is grown, not just shrunk

**What:** Binary search for the largest size that fits (11–40sp).
**Why:** The first version only shrank text to fit, so large bubbles got tiny text
floating in the middle.

## DeepL is gated behind an API key

**What:** The chip is disabled without a key, the option is hidden from the global
list, and a stale `DEEPL` selection silently falls back to `AUTO`.
**Why:** DeepL has no keyless tier. Selecting it without a key made every
translation fail while the UI blamed OCR ("no text recognized").

## `NoText` and `Failed` are different results

**What:** `RegionTranslateResult` distinguishes "OCR found nothing" from
"translation failed", with different toasts.
**Why:** One shared error message sent debugging in the wrong direction — a broken
provider looked like an OCR problem.
**Don't:** Collapse them back into a nullable return.

## A short OkHttp call timeout and per-instance backoff

**What:** 8s call timeout on the translator's client; a failing Lingva instance is
skipped for 5 minutes.
**Why:** The shared `NetworkHelper` client allows 2 minutes per call. With several
blocks per selection, one dead public instance made the whole feature feel broken.
