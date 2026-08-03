# Feature: Translation

Manual, user-triggered translation of a selected area of a manga page:
**select area → OCR on device → translate → show the result** (overlay boxes on
the page, or the bottom panel).

Two deliberate non-goals — see `context/decisions.md`:

- **no automatic whole-page translation** (there is an explicit long-press
  "translate everything on screen" instead);
- **no vocabulary/word-saving.** It existed and was removed: the app is a reader
  with convenient translation, not a study tool. The inspector panel is pure
  lookup; nothing is persisted except the translation caches.

## Files (all fork-only unless noted)

| File | Role |
|------|------|
| `mihon/feature/translate/TranslationModels.kt` | `TranslationSourceLanguage`, `TranslationProvider`, `TARGET_LANGUAGES`, `RecognizedBlock`, `TranslatedBlock`, `PageTranslation`, `RegionTranslateResult` |
| `mihon/feature/translate/PageTextRecognizer.kt` | ML Kit OCR; one cached recognizer per source language |
| `mihon/feature/translate/CloudTextRecognizer.kt` | Optional Google Cloud Vision OCR (user's own key), quota-capped |
| `mihon/feature/translate/TranslationQuota.kt` | Shared quota plumbing: `QuotaKind`, `QuotaLevel`, `QuotaNotifier`, `currentQuotaPeriod()` |
| `mihon/feature/translate/TextTranslator.kt` | Translation backends, text normalization, LRU cache, instance backoff |
| `mihon/feature/translate/PageTranslator.kt` | Orchestration: decode region → OCR → merge blocks → translate; also `warmUp()` |
| `mihon/feature/translate/TranslationOverlayView.kt` | Draws the boxes; tap-to-select, X-to-dismiss, word/phrase picking |
| `mihon/feature/translate/TranslateSelectionView.kt` | Full-screen rubber-band selector |
| `mihon/feature/translate/WordInspectorView.kt` | Bottom panel: tappable original text + variant chips, swipe-down to dismiss |
| `presentation/reader/settings/TranslationSettingsPage.kt` | The reader dialog's Translation tab |

Upstream call-outs are listed in `context/fork-vs-upstream.md`.

## Flow

1. **Button** — `ReaderBottomBar` shows a translate icon; `ReaderActivity.startTranslateSelection()`
   hides the menu and adds a `TranslateSelectionView` over `reader_container`.
2. **Selection** — the user drags a rect (view coordinates). A tap, or a drag
   smaller than 24dp, cancels.
3. **Routing** —
   - pager: `PagerViewer.currentPageHolder()?.translateRegion(rect)`
   - webtoon: `WebtoonViewer.translateRegionAt(rect)` maps the rect through the
     recycler's zoom matrix, finds the child under its center, and converts to that
     holder's coordinates.
4. **Coordinates** — the holder converts view → *displayed source* coordinates with
   `ReaderPageImageView.viewToSourceRect()`, and passes
   `sourceWidth()` so `PageTranslator` can rescale into *full-image* space (a long
   strip displayed through Coil is downsampled; the region decoder needs original
   pixels).
5. **OCR** — `PageTranslator.translateRegion()` pads the region by 35% (min 48px),
   decodes just that area with `BitmapRegionDecoder`, runs ML Kit, then keeps only
   blocks intersecting the *unpadded* selection. So a sloppy selection still
   captures a whole bubble.
6. **Merge** — `mergeBlocks()` unions blocks whose gap is under ~one line height,
   so a multi-line bubble is translated as one sentence (Japanese vertical columns
   are ordered right-to-left).
7. **Translate** — `TextTranslator.translate()` normalizes the text, checks the LRU
   cache, then calls the configured provider. Blocks run 4-at-a-time
   (`Semaphore`).
8. **Draw** — `ReaderPageImageView.setTranslation()` attaches/updates a
   `TranslationOverlayView`, invalidated on every scale/center change.

## Providers (`TranslationProvider`)

| Value | Notes |
|-------|-------|
| `AUTO` | Google → Lingva → MyMemory, first success wins. Records which one worked in `TextTranslator.lastAutoProvider`, shown in settings as "Auto (Google)". |
| `GOOGLE` | Keyless `translate.googleapis.com/translate_a/single` (`client=gtx`). Unofficial. |
| `DEEPL` | Needs `ReaderPreferences.deeplApiKey`. Host is `api-free.deepl.com` when the key ends in `:fx`, else `api.deepl.com`. Disabled in the UI until a key exists, and falls back to `AUTO` if selected without one. |
| `LINGVA` | Public Lingva instances; a failing instance is skipped for 5 minutes. |
| `MYMEMORY` | Slow but keyless fallback. |

The OkHttp client is `NetworkHelper.client` with an **8s call timeout** so a dead
instance fails fast.

## Text normalization (quality-critical)

`TextTranslator.normalizeForTranslation()` runs before every request:

- rejoins hyphenated line breaks (`IMPRES- SION` → `IMPRESSION`),
- collapses whitespace,
- converts SHOUTY ALL-CAPS lettering to sentence case (>80% uppercase letters).

Comic lettering is all-caps, and machine translation of all-caps text drops or
invents words. Do not remove this step.

## Overlay behavior

- Box positions come from `sourceToViewCoord`, so they track pan/zoom.
- Text size is chosen by binary search: the **largest** size in 11–40sp that fits.
- If the text cannot fit even at 11sp, the box grows (up to 1.6× wider, taller as
  needed) and is clamped to stay on screen.
- Tap a box → selected: the box turns warm-tinted and **shows the original text**,
  with an X (top-right) to remove it. A green + appears only for *untranslated*
  blocks (original-first mode) and translates that block in place.
- **Word/phrase pick:** tapping words inside the selected block's original text
  picks a word and extends to a phrase (blue highlight); the pick is sent to
  `ReaderActivity` which shows variants in the inspector panel. Word boundaries
  come from `StaticLayout` hit-testing (`wordRangeAt()`), not OCR boxes, so they
  match the rendered text. A tap that misses a word toggles back to the
  translation. Taps outside any box return `false` from `onTouchEvent`, so page
  gestures still work.
- **Panel dismissal:** the inspector hides on page change, after webtoon
  scrolling of about a third of a screen (`ReaderActivity.onReaderScrolled`),
  on swipe-down, and via its Cancel button.
- **Full-page translate:** long-pressing the bottom-bar translate button routes a
  screen-sized rect through the normal selection path
  (`ReaderActivity.translateFullPage`).
- **Overlay restore:** every shown result is merged into
  `PageTranslator`'s per-page overlay cache (30 pages, key
  `chapterId:pageIndex`); holders restore it after the image loads, and
  dismissals sync back via `onBlocksChanged` → `replaceOverlay`.

## Caching

Two layers in `TextTranslator`: an in-memory LRU (1000 entries) and a 4 MB
`DiskLruCache` in `cacheDir/translations` (key = SHA-1 of `from:to:text`). The disk
layer makes re-reading a chapter instant and offline. Region OCR itself is not
cached — only text→translation pairs.

## Cloud OCR (optional)

On-device ML Kit is the default and the only path that works without a key. When
`ReaderPreferences.visionApiKey` is set, `PageTranslator.recognizeBest()` tries
`CloudTextRecognizer` (Google Cloud Vision `images:annotate`,
`DOCUMENT_TEXT_DETECTION`) **first** and silently falls back to ML Kit on any
failure — network error, bad key, quota spent. Stylised comic lettering is where
it pays off; ML Kit's ceiling on it is the reason this exists.

Blocks come from `fullTextAnnotation.pages[].blocks[].paragraphs[].words[].symbols[]`;
paragraph bounding boxes map onto the same `RecognizedBlock` shape as ML Kit, so
everything downstream is unchanged.

## Quotas

Both paid services are metered so a user cannot silently run up a bill. The
mechanics live in `TranslationQuota.kt` and are identical for each:

| | Cloud Vision | DeepL |
|---|---|---|
| Billed by | requests | characters |
| Free tier | 1 000/month | 500 000/month |
| Limit pref | `visionMonthlyLimit` (default 900) | `deeplMonthlyCharLimit` (default 450 000) |
| Usage prefs | `visionUsageCount` / `visionUsagePeriod` | `deeplUsageChars` / `deeplUsagePeriod` |
| `QuotaKind` | `CLOUD_OCR` | `DEEPL` |

- The period is `"YYYY-MM"` from `currentQuotaPeriod()`; a mismatch on read
  resets the counter (rollover happens lazily, no scheduler).
- A limit of `0` means **no limit** — the check is skipped entirely.
- Crossing `QUOTA_APPROACHING_RATIO` (90%) emits `APPROACHING`; a call refused
  for being over the limit emits `REACHED`. `ReaderActivity` collects
  `QuotaNotifier.events` and toasts the matching string.
- Over the limit, Vision falls back to on-device OCR and DeepL falls back to the
  `AUTO` provider chain, so translation keeps working — it just stops costing
  money.

Usage is recorded **after** a successful response only, so failed calls are not
counted against the user.

## Settings

Language pair, provider, DeepL key, Vision key and both monthly limits live in
`ReaderPreferences` and are surfaced twice: globally in **Settings → Reader**
(`SettingsReaderScreen`) and in-reader in the **Translation** tab
(`TranslationSettingsPage`). Both edit the same preferences. The limit sliders
show live usage in their subtitle once the matching key is set. See
`context/features/reader-settings.md`.

## Warm-up

`ReaderActivity.onCreate` calls `PageTranslator.warmUp()` on IO: it loads the ML Kit
model for the current source language and fires a throwaway translation (which also
probes/backs off dead Lingva instances). Without it the first translation costs
10–20s.
