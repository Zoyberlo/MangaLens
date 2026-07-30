# Stack & tooling

## Runtime

| Concern | Choice |
|---------|--------|
| Language | Kotlin 2.4 (JVM target 17) |
| Build | Gradle 9.6 (wrapper) + AGP 9.3, convention plugins in `gradle/build-logic/` |
| UI | Jetpack Compose (Material 3) everywhere **except the reader**, which is Android Views |
| DI | [Injekt](https://github.com/mihonapp/injekt) — `Injekt.get()` / `by injectLazy()` |
| Networking | OkHttp 5 via `NetworkHelper` (`core/common`) |
| Serialization | kotlinx.serialization (`Json` singleton from `AppModule`) |
| Database | SQLDelight 2 + AndroidX SQLite driver (`data/` module) |
| Images | Coil 3, `SubsamplingScaleImageView` (tachiyomiorg fork), `PhotoView` |
| Strings | moko-resources — `MR.strings.*`, defined in `i18n/` |
| OCR (fork) | ML Kit on-device text recognition, bundled models (latin/ja/zh/ko) |

SDK levels live in `gradle/mihon.versions.toml`: min 26, target 36, compile 37.
Library versions live in `gradle/libs.versions.toml` — **always add dependencies
through the version catalog**, never as a hard-coded coordinate.

## Local toolchain (Windows)

The build needs a JDK 17 and an Android SDK. On this machine:

```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot"
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

APKs land in `app/build/outputs/apk/debug/` (per-ABI splits + a universal one).
Installing on a device: see `processes/build-and-install.md`.

## Tests

Upstream has unit tests in a few modules (`./gradlew test`), but **the fork's
translation feature has none** — it is verified by building and using the app on a
device. Say plainly which parts you did and did not verify.

## Build flavors

`debug`, `release`, `foss`, `preview`, `benchmark`. The **`foss`** flavor
deliberately excludes Google libraries. ML Kit's bundled text-recognition artifacts
do not require Play Services, but they are still Google libraries — if `foss` ever
needs to ship without them, the translation feature has to be flavor-gated.
