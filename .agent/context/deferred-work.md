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

- **On-device neural OCR engines.** `OcrEngine` was built to take one, but the
  case for either candidate got much weaker once the cloud engines landed, and
  both need model files too big to bundle — i.e. a download-manage-delete story
  plus an ONNX Runtime dependency (~10–15 MB) before any of it starts.

  - **PaddleOCR PP-OCRv5 mobile** — the only one still worth considering. It is
    the sole way to improve the *default, offline, keyless* path: recognition is
    ~2M parameters, there is an English-specific variant, and v5 targets our
    exact weak spots (handwriting, vertical text, unusual glyphs). Needs the
    full PP-OCR pipeline — DB detection with unclip post-processing, CTC decode,
    charset dictionaries — so ~+25–30 MB and a day or two of numeric work that
    cannot be verified without a device. Only pays off for users who refuse to
    create any API key at all.

  - **manga-ocr — dropped.** Best-in-class for Japanese manga and reads a whole
    multi-line bubble in one pass, but it is **Japanese only**, so it does
    nothing for the stylised Latin lettering that drove this whole thread. At
    ~110M parameters (~100 MB+ quantized) it buys a narrow audience a marginal
    gain over Gemini, which already handles Japanese and costs no download. Do
    not resurrect this without a concrete Japanese-raws use case.

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

- **CI secrets are pending a human step.** `.github/workflows/build-fork.yml`
  expects `STORE_FILE_BASE64` / `STORE_PASSWORD` / `KEY_ALIAS` / `KEY_PASSWORD` in
  the repo secrets; the owner uploads them with
  `A:\Projects\.keys\set-github-secrets.ps1` (outside the repo). Until then CI
  release builds fail at signing.

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
- **No tests.** The feature is verified by hand on a device. Pure functions worth
  covering if tests are ever added: `normalizeForTranslation`, `mergeBlocks`,
  `shouldMerge`, `orderForReading`, `diskKey`.
- **`foss` flavor and ML Kit.** Bundled ML Kit needs no Play Services but is still a
  Google library; if the flavor's policy matters, the feature needs flavor-gating.
- **Stale preference keys.** `pref_auto_translate_source_lang` /
  `..._target_lang` are named after the removed auto-translate feature. Kept so
  users keep their language pair; rename only with a migration.
