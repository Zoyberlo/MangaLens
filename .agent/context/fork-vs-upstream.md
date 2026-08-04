# Fork vs upstream Mihon

This repo is a fork of [mihonapp/mihon](https://github.com/mihonapp/mihon). Almost
everything is upstream code; the fork adds an on-device **translation** feature and
a small rebrand. Read this before editing anything — it tells you which files are
"ours" and which are shared with upstream.

## Remotes & branch

| Remote | Points at |
|--------|-----------|
| `origin` | the fork (`Zoyberlo/MangaLens`) |
| `upstream` | `mihonapp/mihon` |

Work happens on `feature/auto-translate`. The base commit the fork branched from is
the parent of the first fork commit; `git log upstream/main..HEAD` lists everything
we added.

## Licensing

Upstream is Apache 2.0, so this fork and everything derived from it stays Apache
2.0 — `LICENSE` is upstream's and must not be replaced. Apache 2.0 §4(b) requires
distributed modifications to be stated: that is what `NOTICE` and the fork banner
at the top of `README.md` are for. **Keep `NOTICE` current when the fork gains or
drops a feature.** The license grants no trademark rights: the Mihon name and
logo belong to the Mihon project.

## Rebrand

| What | Value | Where |
|------|-------|-------|
| App name | `MangaLens` (ex `Mihon TL`) | `i18n/.../base/strings.xml` → `app_name` |
| Application id | `app.mangalens` (ex `app.mihon.tl`) | `app/build.gradle.kts` → `defaultConfig.applicationId` |
| Launcher icon | own page-and-bubble mark, no Mihon branding | `app/src/main/res/drawable/ic_launcher_*.xml`, `values/colors.xml` |

The changed `applicationId` is what lets the fork be installed **alongside** the
official Mihon. Debug builds add `.dev` on top of it (upstream behavior). Package
names (`eu.kanade.tachiyomi`) and the namespace were deliberately **not** renamed —
that would cause a merge conflict in every file for no user-visible gain.

The id changed once already (`app.mihon.tl` → `app.mangalens`, 2026-08), so older
builds live on devices as a separate app; both ids stay listed in the
migrate-from-app screen and its manifest `<queries>` so their backups are still
found. Changing it again would orphan installs the same way — avoid.

## Fork-only code (safe to edit freely)

`app/src/main/java/mihon/feature/translate/` — the whole feature:
`PageTextRecognizer`, `TextTranslator`, `PageTranslator`, `TranslationModels`,
`TranslationOverlayView`, `TranslateSelectionView`. See
`context/features/translate.md`.

Plus fork-only files outside that package:

- `app/src/main/java/eu/kanade/presentation/reader/settings/TranslationSettingsPage.kt`
- `.github/workflows/build-fork.yml` — CI building signed release APKs (upstream's
  own workflows are untouched)

## Releases and the in-app updater

Tagging `v<version>` runs `.github/workflows/release-fork.yml`, which builds
signed APKs with `-Penable-updater -Psplit-abis`, renames them to
`mangalens-<tag>-<abi>.apk` plus `mangalens-<tag>-universal.apk`, checks all
three exist, and publishes a GitHub release. `ReleaseServiceImpl.downloadLinkFor`
matches an asset by `-<abi>` and falls back to the one naming no ABI, which is
why the universal build must keep a name the ABI list does not match —
`ReleaseAssetSelectionTest` in `:data` is what holds that contract.
`versionName`/`versionCode` in `app/build.gradle.kts` are the fork's own —
`GetApplicationRelease` compares the running `versionName` against the release
tag, so the two must move together. Upstream's `release.yml` is untouched; it is
gated on `github.repository == 'mihonapp/mihon'` and never fires here.

## Signing

`keystore.properties` (gitignored) points at the owner's keystore
`A:\Projects\.keys\mihon-tl.jks`; with it present Gradle signs release builds via
the same mechanism upstream uses for its GitHub releases. CI gets the keystore from
repo secrets (`STORE_FILE_BASE64` etc., uploaded by
`A:\Projects\.keys\set-github-secrets.ps1`). Losing the keystore means existing
installs cannot be updated — never commit it, never regenerate it casually.

## Upstream files the fork touches (merge-conflict surface)

Keep these edits **small and localized** — every line here is a line that can
conflict when syncing with upstream.

| File | Fork change |
|------|-------------|
| `app/build.gradle.kts` | `applicationId`, ML Kit dependency bundle |
| `app/proguard-rules.pro` | ML Kit keep rules (R8 strips its reflectively-loaded components in release builds) |
| `app/src/main/AndroidManifest.xml` | `<queries>` for Mihon-family packages (migrate-from-app detection) |
| `presentation/more/MoreScreen.kt`, `ui/more/MoreTab.kt` | Migrate from another app row |
| `ui/reader/viewer/ViewerNavigation.kt` | per-axis tap-zone scaling (`navigationTapZoneWidth` 65%, `navigationTapZoneHeight` 50%) |
| `data/updater/AppUpdateChecker.kt` | `GITHUB_REPO` points at this fork; upstream builds are signed with another key and cannot install over ours |
| `res/drawable/ic_mihon.xml`, `ic_mihon_splash.xml`, `ic_launcher_*` | our mark and launcher artwork; the `ic_mihon` file names are kept so the many references stay untouched |
| `presentation/more/onboarding/OnboardingScreen.kt` | adds `TranslationStep` (fork-only file) to first-run setup |
| `res/values/colors.xml`, `res/drawable/ic_launcher_*.xml` | the debug-flavor icon palette applied to all build types |
| `gradle/libs.versions.toml` | `mlkit-text` version, 4 libraries, `mlkit-text` bundle |
| `i18n/.../moko-resources/base/strings.xml` | `app_name` + translation strings |
| `di/AppModule.kt` | registers `PageTranslator` singleton |
| `ui/reader/setting/ReaderPreferences.kt` | translation preferences block |
| `ui/reader/ReaderActivity.kt` | warm-up call, selection overlay, translate button wiring |
| `ui/reader/ReaderViewModel.kt` | webtoon auto-detection (`maybeAutoDetectWebtoon`) |
| `ui/reader/viewer/ReaderPageImageView.kt` | hosts the overlay; `viewToSourceRect`, `sourceWidth`, `setTranslation` |
| `ui/reader/viewer/pager/PagerPageHolder.kt` | `translateRegion` |
| `ui/reader/viewer/pager/PagerViewer.kt` | `currentPageHolder()` |
| `ui/reader/viewer/webtoon/WebtoonPageHolder.kt` | `translateRegion` |
| `ui/reader/viewer/webtoon/WebtoonViewer.kt` | `translateRegionAt` (routes a selection to a holder) |
| `presentation/reader/appbars/ReaderAppBars.kt`, `ReaderBottomBar.kt` | translate button |
| `presentation/reader/settings/ReaderSettingsDialog.kt` | 4th tab |
| `presentation/more/settings/screen/SettingsReaderScreen.kt` | translation preference group |

**Rule:** prefer adding a method to a fork-only class over adding logic to an
upstream file. When an upstream file must change, keep it to a call-out (one
method call, one parameter) and put the body in `mihon/feature/translate/`.

Adding a row to this table is part of the change that touches a new upstream file.

## Syncing

See `processes/sync-with-upstream.md`.
