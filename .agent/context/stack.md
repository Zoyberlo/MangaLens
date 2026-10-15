# Stack & tooling

## Runtime

| Concern | Choice |
|---------|--------|
| Language | Kotlin 2.4 (JVM target 17) |
| Build | Gradle 9.6 (wrapper) + AGP 9.3, convention plugins in `gradle/build-logic/` |
| UI | Jetpack Compose (Material 3) everywhere **except the reader**, which is Android Views |
| DI | [Metro](https://zacsweers.github.io/metro/) since 0.21 — see `architecture.md`; Injekt survives read-only, for extensions |
| Networking | OkHttp 5 via `NetworkHelper` (`core/common`) |
| Serialization | kotlinx.serialization (`Json` singleton from `AppBindings`) |
| Database | SQLDelight 2 + AndroidX SQLite driver (`data/` module) |
| Images | Coil 3, `SubsamplingScaleImageView` (tachiyomiorg fork), `PhotoView` |
| Strings | moko-resources — `MR.strings.*`, defined in `i18n/` |
| OCR (fork) | ML Kit on-device text recognition, bundled models (latin/ja/zh/ko) |

SDK levels live in `gradle/mihon.versions.toml`: min 26, target 36, compile 37.
Library versions live in `gradle/libs.versions.toml` — **always add dependencies
through the version catalog**, never as a hard-coded coordinate.

## Local toolchain (Windows)

The build needs **JDK 21** (Mihon 0.21's Metro plugin refuses 17; CI reads
`.github/.java-version`) and an Android SDK. On this machine a portable
Temurin 21 lives in the user's `.jdks` folder:

```powershell
$env:JAVA_HOME = "$env:USERPROFILE\.jdks\jdk-21.0.12.1+1"
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
```

`local.properties` must contain `sdk.dir` and **must not have a UTF-8 BOM** —
PowerShell's `Set-Content -Encoding utf8` writes one and Gradle then reports
"SDK location not found". Write it with `[System.IO.File]::WriteAllText(...)`.

## Commands

```bash
./gradlew spotlessApply            # format (ktlint) — run before committing
./gradlew :app:compileDebugKotlin  # fast compile check
./gradlew :app:assembleDebug       # build installable APKs
./gradlew spotlessCheck            # CI-style lint gate
```

APKs land in `app/build/outputs/apk/debug/` — one universal APK. Per-ABI splits
are built only with `-Psplit-abis`, which the release workflow passes.
Installing on a device: see `processes/build-and-install.md`.

## Tests

`./gradlew test` runs them all. The fork adds:

- `app/src/test/java/mihon/feature/translate/` — `OcrTextTest`, `OcrLayoutTest`,
  `WordFilterTest`. Everything in the translation pipeline that does not touch
  `Bitmap`/`Rect`/`Canvas`; `WordFilterTest` builds from the **real shipped
  asset**, because a lexicon that silently answers "no" to everything is
  invisible and shipped broken twice.
- `data/src/test/java/tachiyomi/data/release/ReleaseAssetSelectionTest.kt` —
  the release-asset-name ↔ updater contract.

What still has no coverage is anything below the device boundary: coordinate
rescaling in `translateRegion`, `TranslationOverlayView`, and the recognizers
themselves. Those are verified by building and using the app on a device — say
plainly which parts you did and did not verify.

## Build flavors

`debug`, `release`, `foss`, `preview`, `benchmark`. The **`foss`** flavor
deliberately excludes Google libraries. ML Kit's bundled text-recognition artifacts
do not require Play Services, but they are still Google libraries — if `foss` ever
needs to ship without them, the translation feature has to be flavor-gated.
