# Deferred work & known gaps

Things deliberately not done yet. Not a backlog of ideas — only work that has been
discussed and consciously postponed, plus known rough edges.

## Discussed, not built

- **words-app integration.** Tapping a translated block could send the word/phrase
  to the user's vocabulary app (`A:\Projects\words-app`, separate Expo/React Native
  project) over a deep link (`wordsapp://add?word=…&translation=…`). Agreed as the
  natural next step; nothing implemented.
- **Release signing.** Only debug APKs are produced, signed with the debug key.
  Sharing outside the developer's own devices should use a release build with a real
  keystore — no keystore exists in this repo, and none should be committed.

## Known rough edges

- **Translation quality is provider-bound.** After normalization and block merging,
  remaining awkwardness comes from the backend itself: Google gtx translates short
  context-free lines literally. DeepL is noticeably better and is wired up — it only
  needs a user API key.
- **Webtoon selections spanning two strip images.** The selection is routed to the
  holder under its *center*; text in the other image is ignored. Acceptable for
  bubble-sized selections, wrong for full-screen ones.
- **No persistence of translations.** Results live in `TextTranslator`'s in-memory
  LRU cache and the on-screen overlay; leaving the chapter discards them. There is
  no on-disk cache and no "translated" indicator.
- **No tests.** The feature is verified by hand on a device. Pure functions worth
  covering if tests are ever added: `normalizeForTranslation`, `mergeBlocks`,
  `shouldMerge`, `orderForReading`.
- **`foss` flavor and ML Kit.** Bundled ML Kit needs no Play Services but is still a
  Google library; if the flavor's policy matters, the feature needs flavor-gating.
- **Stale preference keys.** `pref_auto_translate_source_lang` /
  `..._target_lang` are named after the removed auto-translate feature. Kept so
  users keep their language pair; rename only with a migration.
