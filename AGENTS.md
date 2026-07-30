# AGENTS.md

Rule file for AI coding agents working in this repository.

This is a **fork of [Mihon](https://github.com/mihonapp/mihon)** (Kotlin Android
manga reader) that adds on-device translation of manga pages.

**Knowledge lives in [`.agent/`](./.agent/), not here.** Before exploring the
codebase, open **`.agent/INDEX.md`** — it maps every topic to its documentation and
its source files, so you can answer "where is X" and "how do I do Y" without
scanning the repo. This file holds behavioral rules only; it must not duplicate
what `.agent` documents.

Update the relevant `.agent` doc **in the same change** whenever you:

- make an existing doc wrong (renamed, moved, or removed code; changed behavior),
- add or expose a meaningful preference, provider, or pipeline stage,
- learn a non-obvious constraint a future agent could not derive from the code.

Small fact → extend the closest existing doc (a glossary row, a decision entry, an
index row). Do not create a new file for one sentence.

## Fork discipline

- Feature code belongs in `app/src/main/java/mihon/feature/<name>/`. Prefer adding
  a method there over adding logic to an upstream file.
- Every edit to an upstream file widens the merge surface. Keep it to a call-out —
  one method call, one parameter, one branch — and record newly touched files in
  `.agent/context/fork-vs-upstream.md` in the same change.
- Do not rename packages, reformat upstream files, or "tidy" upstream code. A diff
  that is not ours is a conflict waiting to happen.

## Environment & infrastructure

- Never create or modify environment variables unless explicitly asked.
- Never assume secrets, API keys, tokens, or credentials, and never commit a
  keystore or a `local.properties` with machine-specific paths beyond what is
  already there.
- Never modify CI, signing, or release configuration unless asked.
- If configuration is missing, **ask** — do not invent values.

## Response style

- Concise and direct. No restating the question, no unrequested summaries.
- Bullets over paragraphs; code over prose when code answers it.
- Do not explain Kotlin or Android basics.

## Code standards

See `.agent/context/conventions.md`. In short: match surrounding upstream style,
spotless/ktlint clean, coroutines with the project's dispatcher helpers, `logcat`
for logging, strings only through `MR.strings`, dependencies only through the
version catalog.

## Safety & boundaries

- Do not change the database schema or add migrations unless asked.
- Do not edit files outside the scope of the request.
- Change only the relevant part of a file — focused diffs.
- If required information is missing, ask instead of guessing.

## Ask before you build

- **Anything that touches an existing reader flow → ask first.** People read in this
  app; a regression in page turning or zoom is worse than a missing feature.
- **Any new feature → agree on a plan first**, regardless of size.
- When a design has **more than one sane option**, present the options rather than
  shipping your pick.
- **Dropping or replacing a capability** is the user's trade-off to accept, never
  something folded in silently.

## How much planning

| Work | Artifact |
|------|----------|
| New feature, or anything touching upstream files | Agree a plan first; write a spec in `.agent/specs/` if it spans several files |
| Change confined to `mihon/feature/translate/` | Just build it |
| Small fix | Just build it |
| Can't judge the size | Ask |

## Verification before handoff

- The gate is `./gradlew spotlessApply :app:assembleDebug`. A compile-only check is
  `:app:compileDebugKotlin`.
- The fork's feature has **no automated tests** — verify on a device
  (`.agent/processes/build-and-install.md`) and say plainly which parts you did and
  did not check. Reader changes need checking in **both** paged and webtoon modes.
- Do not claim a behavior works because the code looks right.
