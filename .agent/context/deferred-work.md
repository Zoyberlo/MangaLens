# Deferred work & known gaps

Things deliberately not done yet. Not a backlog of ideas — only work that has been
discussed and consciously postponed, plus known rough edges.

## Agreed queue (in order)

0. **The bottom translation panel's interaction is awkward.** Reported by the
   owner after v1.0.4 was verified working: recognition, dictation and the
   overlay all do the right thing, but *handling* the panel does not feel
   right. No specific defect was named, so the first job is to sit with it and
   write down what actually goes wrong — candidates seen while testing: it
   covers a third of the page with the keyboard up, the mic and Translate
   buttons sit far apart at the bottom-left, dismissing means finding a small
   ×, and nothing indicates the panel can be scrolled. **Do not start
   redesigning until the specific complaints are written down**; guessing here
   would produce a different panel, not a better one.

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

- **Replacing ML Kit entirely — decided against.** PaddleOCR shipped and is the
  default English reader, but ML Kit stays, for two reasons that are not going
  away cheaply:

  - **It finds the text.** PaddleOCR here is recognition only; the line boxes
    come from ML Kit and have been right on every page examined. Dropping it
    means PP-OCR's DB detection *and* its post-processing — binarisation,
    contour finding, polygon unclipping — the hardest numeric code in the
    feature and the least verifiable without a device. The detection model in
    the repo we use is **83 MB**, against the 21.2 MB of ML Kit models it would
    replace. Three times the size for the privilege.
  - **It reads Japanese.** PaddleOCR ships rec models for English, Chinese,
    Korean, Latin, Arabic, Greek, Hindi, Thai, Tamil, Telugu and East Slavic —
    **no Japanese**. A general PP-OCRv5 model covering zh/en/ja exists at 15 MB,
    but that is another unverified bet for a language we already read.

- **Cyrillic recognition — decided against.** PaddleOCR's `eslav` model would
  add something the app cannot do at all today: ML Kit has no Cyrillic model, so
  Russian and Ukrainian scans are unreadable. It is +12 MB down an already
  working code path, so the work is nearly free. It was still declined, on the
  owner's reasoning: readers of those languages overwhelmingly read the
  original, English, or another widely-scanlated language, so the audience is a
  narrow slice of a narrow slice, and everyone else pays the megabytes.

- **manga-ocr — dropped.** Best-in-class for Japanese manga and reads a whole
  multi-line bubble in one pass, but it is **Japanese only**, so it does nothing
  for the stylised Latin lettering that drove this. At ~110M parameters
  (~100 MB+ quantized) it buys a narrow audience a marginal gain over engines
  already present. Do not resurrect without a concrete Japanese-raws use case.

  Worth remembering *why* recognition kept getting the investment: nearly every
  "the translation is bad" report traced back to OCR. A faithful translation of
  garbled text reads as a translation bug. Fix recognition and the translation
  complaints mostly disappear on their own.

- **Gemini doing OCR and translation in one call.** It currently only
  transcribes, so the user's chosen translation provider still applies. Letting
  it translate directly would read for context (onomatopoeia, ALL-CAPS, split
  bubbles) but breaks the uniform "engine returns source text" contract and
  bypasses the translation cache. Worth revisiting once the transcription path
  has been used in anger.

- **CI signing secrets — done, no longer pending.** `STORE_FILE_BASE64` /
  `STORE_PASSWORD` / `KEY_ALIAS` / `KEY_PASSWORD` were uploaded 2026-08-03 and
  CI signed the v1.0.2 release with them. They come from
  `A:\Projects\.keys\set-github-secrets.ps1` (outside the repo), which reads
  `keystore.properties` and `mangalens.jks`; re-run it only if the key changes.
  The CI key and the local one are the same: a locally built release APK
  installs over the CI-published one without a signature conflict.

## Extension trust after migration

**The trust list can never be migrated.** `SourcePreferences.trustedExtensions`
uses `Preference.appStateKey`, and `PreferenceBackupCreator` strips every
`__APP_STATE_` key from backups — in Mihon, in Tachiyomi, in every fork. No
`.tachibk` from any of them contains it, so there is nothing to import.

What actually removes the prompts is **extension repos**. Extensions are
device-wide packages, so a fresh app sees every extension already installed and
finds none of them in its own empty trust set. `TrustExtension.isTrusted` also
passes anything whose signature matches a repo's `signingKey`, and repos *are*
in backups (`BackupExtensionStore`, proto 106). Restore those and the prompts go
away for everything those repos signed. Sideloaded extensions still need a
manual decision, correctly.

Two fixes shipped for this:

- `ExtensionManager.revalidateUntrustedExtensions()` re-runs the load (and so the
  signature check) for each untrusted extension after a restore brings repos
  back. Without it they stayed untrusted until the process restarted, because
  trust is evaluated once at `initExtensions()`. It grants nothing a fresh
  install would not have granted — anything failing the check stays untrusted.
- Extension repos got their own checkbox on the migrate screen. They used to
  ride on "Source settings", whose label gives no clue that it decides this.

## Known rough edges

- **Translation quality is provider-bound.** After normalization and block merging,
  remaining awkwardness comes from the backend itself: Google gtx translates short
  context-free lines literally. DeepL is noticeably better and is wired up — it only
  needs a user API key.
- **Overlay is per-page state.** Translations persist on disk (`TextTranslator`'s
  DiskLruCache), so re-translating is instant — but the boxes themselves are not
  restored when re-entering a page; the user re-selects.
- **Test coverage stops at the device boundary.** `OcrText` and `OcrLayout`
  are covered (53 cases in `app/src/test`). What is not, and cannot be without
  Robolectric or an instrumented run: the coordinate rescaling in
  `translateRegion` (view -> displayed-source -> full-image), region padding and
  `inSampleSize`, and everything in `TranslationOverlayView`. Those touch
  `Rect`, `Bitmap` and `Canvas` directly.
- **`foss` flavor and ML Kit.** Bundled ML Kit needs no Play Services but is still a
  Google library; if the flavor's policy matters, the feature needs flavor-gating.
- **Stale preference keys.** `pref_auto_translate_source_lang` /
  `..._target_lang` are named after the removed auto-translate feature. Kept so
  users keep their language pair; rename only with a migration.
