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

- **CI secrets are pending a human step.** `.github/workflows/build-fork.yml`
  expects `STORE_FILE_BASE64` / `STORE_PASSWORD` / `KEY_ALIAS` / `KEY_PASSWORD` in
  the repo secrets; the owner uploads them with
  `A:\Projects\.keys\set-github-secrets.ps1` (outside the repo). Until then CI
  release builds fail at signing.
- **Silent save to words-app.** Saving currently opens words-app's Add screen via
  deep link; a background save (no app switch) would need an exported receiver on
  the words-app side, which Expo makes awkward. Revisit only if the app-switch
  annoys in practice.

## Known rough edges

- **Translation quality is provider-bound.** After normalization and block merging,
  remaining awkwardness comes from the backend itself: Google gtx translates short
  context-free lines literally. DeepL is noticeably better and is wired up — it only
  needs a user API key.
- **The deep link needs a words-app dev/standalone build.** Expo Go registers its
  own scheme, not `wordsapp://` — the save button only reaches words-app when a real
  build of it is installed.
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
