# Code conventions

Follow the surrounding upstream code first — matching its style keeps merges clean.

## Formatting

- **spotless + ktlint 1.8** is the gate. Run `./gradlew spotlessApply` before
  committing; CI-style check is `./gradlew spotlessCheck`.
- 4-space indent, 120-column limit, trailing commas (`.editorconfig`).
- Imports must be explicit and sorted — ktlint fails on wildcards and on
  out-of-order imports. Prefer a real import over a fully-qualified name inline.

## Kotlin

- Coroutines everywhere; no RxJava in new code (`rxJava` exists only for legacy
  source-API compatibility).
- Use the project's dispatcher helpers from `tachiyomi.core.common.util.lang`:
  `launchIO`, `withIOContext`, `withUIContext`, `launchNonCancellable`.
- Log with `logcat { }` / `logcat(LogPriority.WARN, e)` from
  `tachiyomi.core.common.util.system` — not `android.util.Log`, not `println`.
- Catch narrowly and log; never swallow an exception silently. User-facing failures
  should surface as a toast or an error state, not a blank result.
- Public classes and non-obvious functions get a KDoc block; do not comment
  self-evident code. Comments explain **why**, not what.

## Compose

- Material 3 only. Reuse `presentation-core` components (`SelectItem`,
  `SettingsChipRow`, `CheckboxItem`, `SliderItem`) instead of raw widgets.
- Read preferences with `.collectAsState()`; do not call `.get()` during
  composition for values that can change.

## Strings

All user-facing text goes in `i18n/src/commonMain/moko-resources/base/strings.xml`
and is used as `MR.strings.<name>`. Only `base/` is edited by hand — other locales
come from translators. Never hard-code a string in Kotlin.

## Dependencies

Add through the version catalog (`gradle/libs.versions.toml`): a `[versions]` entry,
one or more `[libraries]` entries, and a `[bundles]` entry when several artifacts
always travel together. Then reference `libs.bundles.x` / `libs.x` in the module's
`build.gradle.kts`.

## Fork-specific

- New feature code lives under `mihon.feature.<name>`, not `eu.kanade.*`.
- Keep edits to upstream files minimal — a call-out, not a body. Record every newly
  touched upstream file in `context/fork-vs-upstream.md` **in the same change**.
- Commit messages: imperative mood, a short subject line, a body explaining why.
  Upstream uses Conventional-Commits-ish prefixes for some changes but does not
  enforce them; consistency within the fork matters more.
