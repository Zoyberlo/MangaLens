# Deferred work & known gaps

Things deliberately not done yet. Not a backlog of ideas — only work that has been
discussed and consciously postponed, plus known rough edges.

## Agreed queue (in order)

1. **Edit info**: per-manga custom title/cover/notes overrides.
2. **Merged series**: one library entry fed by several sources (largest, last).

Migration (`mihon.feature.migratefromapp`) now has two screens:
`MigrateFromAppScreen` (detect Mihon-family packages, optional all-files-access
storage scan, folder picker fallback) and `MigrateRestoreScreen` (decode the
backup, show new-vs-existing counts, skip-or-update overlapping entries by
restoring a filtered copy written to cacheDir, opt-in settings restore).

Shipped from the earlier queue: translator UX settings
(`translateResultDisplay` overlay/panel + `translateShowOriginalFirst` with
per-block on-demand translation) and More → Migrate from another app
(`mihon.feature.migratefromapp`, manifest `<queries>` for Mihon-family
packages, `.tachibk` folder scan → `RestoreBackupScreen`).

## Discussed, not built

- **On-device neural OCR engines.** `OcrEngine` was built to take them, but both
  candidates need model files that cannot be bundled, so they need a
  download-manage-delete story (and an ONNX Runtime dependency, ~10–15 MB)
  before either is worth starting:
  - **PaddleOCR PP-OCRv5 mobile** — the one that would improve the *default,
    offline* path. Recognition is only ~2M parameters and there is an
    English-specific variant; v5 targets exactly our weak spots (handwriting,
    vertical text, unusual glyphs). Needs the full PP-OCR pipeline: DB detection
    with unclip post-processing, CTC decode, charset dictionaries. Roughly
    +25–30 MB and a day or two of careful numeric work that cannot be verified
    without a device.
  - **manga-ocr** — best-in-class for Japanese manga specifically, and reads a
    whole multi-line bubble in one pass, which is where ML Kit fails. Japanese
    only, no detection stage (fine — our crops come from the user's selection),
    but ~110M parameters, so ~100 MB+ even quantized: an explicit opt-in
    download, like an offline translation pack.

- **Gemini doing OCR and translation in one call.** It currently only
  transcribes, so the user's chosen translation provider still applies. Letting
  it translate directly would read for context (onomatopoeia, ALL-CAPS, split
  bubbles) but breaks the uniform "engine returns source text" contract and
  bypasses the translation cache. Worth revisiting once the transcription path
  has been used in anger.

- **CI secrets are pending a human step.** `.github/workflows/build-fork.yml`
  expects `STORE_FILE_BASE64` / `STORE_PASSWORD` / `KEY_ALIAS` / `KEY_PASSWORD` in
  the repo secrets; the owner uploads them with
  `A:\Projects\.keys\set-github-secrets.ps1` (outside the repo). Until then CI
  release builds fail at signing.

## Known rough edges

- **Translation quality is provider-bound.** After normalization and block merging,
  remaining awkwardness comes from the backend itself: Google gtx translates short
  context-free lines literally. DeepL is noticeably better and is wired up — it only
  needs a user API key.
- **Overlay is per-page state.** Translations persist on disk (`TextTranslator`'s
  DiskLruCache), so re-translating is instant — but the boxes themselves are not
  restored when re-entering a page; the user re-selects.
- **No tests.** The feature is verified by hand on a device. Pure functions worth
  covering if tests are ever added: `normalizeForTranslation`, `mergeBlocks`,
  `shouldMerge`, `orderForReading`, `diskKey`.
- **`foss` flavor and ML Kit.** Bundled ML Kit needs no Play Services but is still a
  Google library; if the flavor's policy matters, the feature needs flavor-gating.
- **Stale preference keys.** `pref_auto_translate_source_lang` /
  `..._target_lang` are named after the removed auto-translate feature. Kept so
  users keep their language pair; rename only with a migration.
