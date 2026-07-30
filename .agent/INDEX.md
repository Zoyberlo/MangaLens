# INDEX — the map

Find your topic, read the `.agent` doc, then open the source files. Open only
what the task needs.

**Status:** ✅ written · 🚧 planned (file not created yet — go straight to source).

## Start here

| Topic | `.agent` doc | Source of truth |
|-------|--------------|-----------------|
| What this fork changes vs upstream Mihon | ✅ `context/fork-vs-upstream.md` | `git diff upstream/main...HEAD` |
| Stack, build, SDK/JDK setup, commands | ✅ `context/stack.md` | `gradle/libs.versions.toml`, `gradle/mihon.versions.toml`, `app/build.gradle.kts` |
| Module layout & app architecture | ✅ `context/architecture.md` | `settings.gradle.kts`, `app/src/main/java/eu/kanade/tachiyomi/di/AppModule.kt` |
| Code conventions | ✅ `context/conventions.md` | `.editorconfig`, spotless config in `build.gradle.kts` |
| Design decisions ("why") | ✅ `context/decisions.md` | — |
| Glossary | ✅ `context/glossary.md` | — |
| Deferred work & known gaps | ✅ `context/deferred-work.md` | — |

## Features

| Topic | `.agent` doc | Source of truth |
|-------|--------------|-----------------|
| Translation (OCR → translate → overlay) | ✅ `context/features/translate.md` | `app/src/main/java/mihon/feature/translate/*` |
| Reader internals (viewers, holders, page images) | ✅ `context/features/reader.md` | `app/src/main/java/eu/kanade/tachiyomi/ui/reader/*` |
| Reader settings & preferences | ✅ `context/features/reader-settings.md` | `ui/reader/setting/ReaderPreferences.kt`, `eu/kanade/presentation/reader/settings/*` |
| Sources & extensions (upstream) | 🚧 planned | `app/src/main/java/eu/kanade/tachiyomi/extension/*`, `source-api/` |
| Library, downloads, tracking (upstream) | 🚧 planned | `app/src/main/java/eu/kanade/tachiyomi/data/*` |

## Processes (how-to playbooks)

| Task | `.agent` doc |
|------|--------------|
| Build the app and install it on a device | ✅ `processes/build-and-install.md` |
| Add a translation provider | ✅ `processes/add-translation-provider.md` |
| Add a reader preference + its UI | ✅ `processes/add-reader-setting.md` |
| Sync the fork with upstream Mihon | ✅ `processes/sync-with-upstream.md` |
| Triage / reproduce a bug | ✅ `processes/triage-bug.md` |

## Templates (scaffolds to copy)

| Scaffold | `.agent` path |
|----------|---------------|
| Feature spec document | ✅ `templates/spec.md` |
| PR description | ✅ `templates/pr-description.md` |

## Specs

| Spec | Status |
|------|--------|
| _none active_ | — |

New work goes in `specs/<feature-name>.md` (structure: `specs/README.md`,
scaffold: `templates/spec.md`). Add a row here when you start one, flip it when
it ships, and move the file to `specs/_archive/`.

---

When you create a planned (🚧) doc, flip its marker to ✅ in this file.
