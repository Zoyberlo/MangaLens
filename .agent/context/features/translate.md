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
| `mihon/feature/translate/CloudTextRecognizer.kt` | The cloud OCR engines (Google Vision, Azure, Gemini), each on the user's own key and quota |
| `mihon/feature/translate/TranslationQuota.kt` | Shared quota plumbing: `QuotaKind`, `QuotaLevel`, `QuotaNotifier`, `QuotaTracker` |
| `presentation/more/settings/screen/SettingsRecognitionScreen.kt` | The engine picker, per-language overrides and every key/limit |
| `mihon/feature/translate/TextTranslator.kt` | Translation backends, text normalization, LRU cache, instance backoff |
| `mihon/feature/translate/PageTranslator.kt` | Orchestration: decode region → OCR → merge blocks → translate; also `warmUp()` |
| `mihon/feature/translate/TranslationOverlayView.kt` | Draws the boxes; tap-to-select, X-to-dismiss, word/phrase picking |
| `mihon/feature/translate/TranslateSelectionView.kt` | Full-screen rubber-band selector |
| `mihon/feature/translate/WordInspectorView.kt` | Bottom panel: tappable original text + variant chips, the text editor, swipe-down to dismiss |
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
  plus up to four corner buttons:

  | Corner | Button | Shown when |
  |--------|--------|------------|
  | top-right | grey ✕ — remove this block | always |
  | top-left | green + — translate in place | block is untranslated (original-first mode) |
  | bottom-left | blue pencil — open the text in the editor | always |
  | bottom-right | purple ↻ — re-read with Cloud Vision | a Vision key is set |
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

## Text editor and manual input

The bottom panel has a second mode: the text becomes an `EditText` with a mic
button and a Translate button. It is reached three ways:

- **pencil on a selected overlay block** — the recognized text opens in the
  editor, and the result of translating it replaces that block in place
  (`PageTranslator.updateOverlayBlock(…, sourceText = …)`);
- **pencil in the panel itself** — panel display mode, correcting the text that
  was just recognized;
- **keyboard button in the reader's bottom bar** — an empty editor, for text
  that is not on the page at all.

Dictation runs **in-process** via `SpeechRecognizer` with partial results
streaming into the editor, in the configured source language. It deliberately
does not use `startActivityForResult` with `ACTION_RECOGNIZE_SPEECH`: the system
speech dialog covers the middle of the screen, which is exactly the bubble the
user is reading the text off. The mic turns red and a "listening" label appears;
tapping it again stops. Speech is appended after whatever was already typed.

This needs `RECORD_AUDIO` (requested at first use) and a `<queries>` entry for
`android.speech.RecognitionService`, without which binding fails on API 30+.

The panel raises the keyboard itself and pads for
`WindowInsetsCompat.Type.ime()`. `ReaderActivity` sets `SOFT_INPUT_ADJUST_RESIZE`
purely so the IME reports insets below API 30 — layout is inset-driven, not
resize-driven. Swipe-to-dismiss and the webtoon scroll-dismissal are both
disabled while editing (`isEditingText`), so nothing yanks the panel away
mid-sentence.

## Recognition engines (`OcrEngine`)

| Engine | Where it runs | Notes |
|--------|---------------|-------|
| `ON_DEVICE` | ML Kit, offline | Free, the default for everything, and the fallback whenever anything else fails |
| `GOOGLE_VISION` | `vision.googleapis.com/v1/images:annotate` | `DOCUMENT_TEXT_DETECTION`; blocks from `fullTextAnnotation.pages[].blocks[]` |
| `AZURE_READ` | `{endpoint}/computervision/imageanalysis:analyze?features=read` | Image Analysis 4.0 — synchronous, posts raw bytes; returns **lines**, which merge into bubbles downstream like ML Kit's |
| `GEMINI` | `generativelanguage.googleapis.com/…:generateContent` | Reads for meaning, so it handles stylised lettering best — but returns no usable geometry |

`OcrEngine.canDetectLayout` is false for Gemini, which is why it is offered for
block retries only: the automatic pass needs per-bubble boxes, and Gemini's
result is one block spanning the whole crop.

Two settings decide who runs, each with a per-source-language override stored as
`LANGUAGE=ENGINE` entries in a string set (`ocrOverrideFor` / `withOcrOverride`):

- **`ocrEngine`** — the automatic pass, i.e. every area the user selects.
  Default `ON_DEVICE`, because a cloud engine here bills on every selection.
  `PageTranslator.primaryEngineFor()` silently drops back to on-device when the
  chosen engine is unconfigured or cannot do layout.
- **`ocrRetryEngine`** — the purple ↻ button on a selected block. Default
  `GEMINI`. `retryBlockWithCloud()` crops that one block from the original
  image, re-reads it, re-translates and writes **both** back. The crop is sent
  **unmodified** — the grayscale/contrast treatment in `enhanceForOcr` exists
  for ML Kit and only degrades what the cloud engines see. On failure or spent
  quota the on-device result is left alone.

  If recognition succeeds but the translation fails, the result is
  `RecognizedOnly`: the request was already billed, so the better text is kept
  and the block goes back to showing it untranslated, with the green + to
  translate in place. Never throw away something the user paid for.

The ↻ button is hidden entirely when no language resolves to a configured cloud
engine (`isRetryEngineUsable`), so it never appears as a dead control.

Everything lives in **Settings → Translation → Text recognition**
(`SettingsRecognitionScreen`).

Every engine group has a **Test key** row that makes one real request and shows
the service's own reply verbatim, and Gemini additionally has **Available
models**, which asks the API which models the key may call for `generateContent`
and lets the user pick one — Google retires model ids often enough that a
hardcoded default eventually 404s with no way to discover the replacement.

This exists because `awaitSuccess()` closes the response and throws bare
`HttpException(code)`, discarding the JSON body that says *why* — a disabled
Generative Language API, a retired model, a wrong endpoint. `CloudTextRecognizer`
uses its own `awaitBody()` that reads the body first and puts `error.message`
into the exception, keeps it in `lastError(engine)`, and shows it as the Test
key row's subtitle. Never swallow an error only the user can fix.

Every key field carries a **?** button (`EditTextPreference.onHelpClick` →
`ApiKeyGuideDialog`) with the steps to obtain that key and a button that opens
the right console. `ApiKeyGuide` holds one entry per service, including DeepL's,
which lives on the reader screen. The guides deliberately spell out the parts
people trip over: Vision needs a billing account even for its free tier, DeepL
free keys must keep their `:fx` suffix, Azure needs the endpoint as well as the
key, and Gemini's free tier is not private.

### Not implemented: on-device neural engines

PaddleOCR PP-OCRv5 mobile and manga-ocr would both beat ML Kit *offline*, but
each needs an ONNX Runtime dependency plus model files that are too big to
bundle — so they need a download-and-manage story first. See
`context/deferred-work.md`.

## Quotas

Every paid service is metered so a user cannot silently run up a bill. One
`QuotaTracker` per service holds the whole mechanic — nobody re-implements
rollover or the warning thresholds:

| | Google Vision | Azure | Gemini | DeepL |
|---|---|---|---|---|
| Billed by | requests | requests | requests | characters |
| Free tier | 1 000/month | 5 000/month | resets **daily** | 500 000/month |
| Limit default | 900 | 4 500 | 3 000 | 450 000 |
| `QuotaKind` | `CLOUD_OCR` | `AZURE_OCR` | `GEMINI_OCR` | `DEEPL` |

Gemini's free tier is per-day, so its monthly number is only a backstop against
an attached billing account rather than a real match for the free tier.

- The period is `"YYYY-MM"` from `currentQuotaPeriod()`; a mismatch on read
  resets the counter (rollover happens lazily, no scheduler).
- A limit of `0` means **no limit** — the check is skipped entirely.
- Crossing `QUOTA_APPROACHING_RATIO` (90%) emits `APPROACHING`; a call refused
  for being over the limit emits `REACHED`. `ReaderActivity` collects
  `QuotaNotifier.events` and toasts the matching string.
- Over the limit, a cloud OCR call is refused (the on-device result stays) and
  DeepL falls back to the `AUTO` provider chain, so translation keeps working —
  it just stops costing money.

Usage is recorded **after** a successful response only, so failed calls are not
counted against the user.

## Settings

Everything lives in `ReaderPreferences` (historical — the keys predate the
split) but is edited from **Settings → Translation**
(`SettingsTranslationScreen`), a top-level entry, with engines and keys one
level deeper under **Text recognition**. The reader's own Translation dialog tab
(`TranslationSettingsPage`) keeps only what is worth changing mid-chapter:
language pair, provider, display mode. Limit sliders show live usage in their
subtitle once the matching key is set. See
`context/features/reader-settings.md`.

## Warm-up

`ReaderActivity.onCreate` calls `PageTranslator.warmUp()` on IO: it loads the ML Kit
model for the current source language and fires a throwaway translation (which also
probes/backs off dead Lingva instances). Without it the first translation costs
10–20s.
