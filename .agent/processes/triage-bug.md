# Process: Triage / reproduce a bug

## 1. Gather context

- Which **reading mode**? Paged and webtoon take different code paths through the
  fork (`PagerPageHolder` vs `WebtoonViewer.translateRegionAt`).
- Which **source / manga**? A downsampled long strip behaves differently from a
  single page — that is where coordinate bugs show up.
- Which **provider** is selected, and is there a DeepL key?
- Exact symptom wording: the two translation toasts mean different things —
  "No text recognized" = OCR found nothing; "Translation failed" = OCR worked, the
  provider did not.

## 2. Reproduce with logs

If it happened on the dev build, read the **persistent log** first — it holds
every day since the last clear, so the failure may already be in it without
reproducing anything (`processes/build-and-install.md`, section 6):

```bash
adb shell run-as app.mangalens.dev ls files/devlog
```

Otherwise, live:

```bash
adb logcat --pid $(adb shell pidof app.mangalens.dev)
```

Every "Translate area" ends in one INFO line, `Translate area: <outcome>, <n> ms;
<manga> - <chapter>, page <n>, selection <rect>`, so a reported moment can be
found by its time and the page opened again to reproduce it. Before it, in
order:

- `region read: ...` (DEBUG) — what recognition read, block by block; `(?)`
  marks a reading with words the dictionary does not know;
- `No text: <reason>` — which check produced a "No text recognized" toast,
  including what the recognizer saw around the selection;
- `Translated via <provider>: '<source>' -> '<result>'` (DEBUG), or
  `(cached)` when the answer came from the cache rather than a provider;
- `No translation for '<source>'` and `Area translation failed: <reason>` —
  what lies behind a "Translation failed" toast.

Around them, the user's side: `Selection started` / `drawn` / `cancelled`,
`Panel: ...` and `Panel hidden: <reason>`, `Dictation started`, and two lines
that record a misreading outright — `Corrected by the user: '<read>' -> '<fixed>'`
(the pencil) and `Cloud re-read of '<old>': '<new>'`. Android's own messages
for the process (ML Kit, ONNX, OkHttp) are in `system-<date>.log` beside it.

Provider and recognizer failures log at WARN with their own prefixes:
`OCR warm-up failed`, `Text recognition failed`, `Translation failed for block`,
`Lingva translation failed on <instance>`, `Google translation failed`,
`DeepL translation failed`, `MyMemory refused`, `<provider> returned the source
untranslated`, `Speech recognition error <code>`.

## 3. Isolate by layer

| Symptom | Suspect | Look at |
|---------|---------|---------|
| Toast "No text recognized" on obvious text | OCR input: wrong region or wrong language | `PageTranslator.translateRegion` (padding, rescale), source-language preference |
| Toast "Translation failed" | Provider or network | `TextTranslator`, logcat for the provider line |
| Boxes drawn in the wrong place | Coordinate space mix-up | `viewToSourceRect`, `sourceWidth()`, `regionSpaceWidth` rescale, `sourceToViewCoord` in the overlay |
| Boxes drift when zooming | Missing invalidate | `ReaderPageImageView`'s `OnStateChangedListener` |
| Nothing happens after dragging | Selection routing | `ReaderActivity.startTranslateSelection`, `PagerViewer.currentPageHolder()`, `WebtoonViewer.translateRegionAt` |
| Page gestures dead after a translation | Overlay eating touches | `TranslationOverlayView.onTouchEvent` must return `false` on a miss |
| Garbled or truncated translation | Text pipeline | `mergeBlocks`, `normalizeForTranslation` |
| Text too small / clipped | Layout fitting | `TranslationOverlayView.drawBlock` |
| First translation very slow | Warm-up not running, or a dead instance not yet backed off | `ReaderActivity.onCreate` warm-up, `instanceBackoffUntil` |
| Translate button missing | Viewer null, or bottom-bar wiring | `ReaderActivity` `onClickTranslateSelection`, `ReaderBottomBar` |

## 4. Gotchas that look like bugs

- **Translations are cached in memory** (`TextTranslator`'s LRU). Re-testing a fix
  on the same bubble can return the old result — force-stop the app to clear it.
- **`sWidth` is not the file width** for Coil-decoded long strips. See
  `context/decisions.md`.
- **Webtoon selections are routed by their center**, so a selection spanning two
  strip images only translates one of them — by design.
- **Provider chosen ≠ provider used** in `AUTO` mode. The settings label shows the
  actual one.
- **A `DEEPL` selection without a key silently falls back to `AUTO`** — a "wrong"
  translation style may be that.

## 5. Confirm the fix

```bash
./gradlew spotlessApply :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Re-run the repro **in the same reading mode**, then sanity-check the other one.
There are no automated tests — state plainly what was verified and what was not.
