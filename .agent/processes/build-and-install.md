# Process: Build and install on a device

## 1. Environment

The build needs JDK 17 and the Android SDK. On the developer's Windows machine:

```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot"
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
```

`local.properties` must hold `sdk.dir` **without a BOM**. If Gradle says "SDK
location not found" while the file clearly exists, that is the BOM — rewrite it:

```powershell
[System.IO.File]::WriteAllText("A:\Projects\MangaLens\local.properties", "sdk.dir=C\:\\Users\\zoybe\\AppData\\Local\\Android\\Sdk`n")
```

## 2. Which variant

**Iterate on `debug`. Do not install `release` over the owner's app.**

| Variant | applicationId | Name on device |
|---------|---------------|----------------|
| `debug` | `app.mangalens.dev` | MangaLens Dev |
| `release` | `app.mangalens` | MangaLens |

They install side by side, so day-to-day work never touches the app the owner
actually reads with — that one is meant to update through the in-app updater
from a tagged GitHub release, nothing else.

Two consequences to keep in mind:

- **The dev app has its own, empty data.** Separate library, separate settings,
  separate API keys. Testing a cloud engine there means pasting the key again.
- **`debug` is not minified.** R8 has broken this app before (it stripped ML
  Kit's reflectively-loaded components — see `app/proguard-rules.pro`). A green
  dev build proves nothing about that. **Build and smoke-test `release` before
  tagging a version.**

## 3. Build

```bash
./gradlew spotlessApply :app:assembleDebug --build-cache
```

Always run `spotlessApply` in the same invocation — ktlint failures are the most
common reason a build breaks after an edit. For a quick syntax check without
packaging, `:app:compileDebugKotlin` is much faster.

Incremental debug builds land around **15 s**; a cold one is a couple of
minutes. Release is not dramatically slower — most of the time is Kotlin
compilation, not R8 — so pick the variant for *what it installs over*, not for
speed.

Output: `app/build/outputs/apk/debug/app-debug.apk` (splits are off by default;
pass `-Pall-abis` to also build the emulator-only x86 ABIs).

## 4. Install

```bash
adb devices                                                   # confirm the device is listed
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

`adb` lives in `$ANDROID_HOME/platform-tools/`.

If `adb devices` prints nothing:

- USB mode must be **File transfer**, not "Charging only";
- **USB debugging** must be enabled in Developer options;
- the "Allow USB debugging?" prompt must be accepted on the phone;
- after enabling it, `adb kill-server` then `adb devices` again.

`device offline` usually means the cable was replugged — same fix.

The fork installs alongside official Mihon (different `applicationId`); a debug
build additionally carries the `.dev` suffix.

## 5. Verify

The pure text rules have unit tests (section 7); everything else is verified by
hand on the device. Report exactly what was and was not verified. For the
translation feature:

1. Settings → Translation: language pair and provider are set.
2. Open a chapter, wait a moment for warm-up.
3. Translate button → drag over a bubble → overlay appears with sensible text.
4. Zoom and pan — the box stays on the bubble.
5. Tap the box → X appears → tap the X → the box disappears.
6. Repeat in the other reading mode (paged vs webtoon).

## 6. Logs

```bash
adb logcat --pid=$(adb shell pidof app.mangalens.dev)
```

Filtering by pid matters — the device buffer rotates fast enough that grepping
the whole log loses entries within a minute.

**Release builds do log**, contrary to the obvious guess: `App.kt` installs
`AndroidLogcatLogger` at `INFO` in release and `DEBUG` in debug, so anything
logged at `WARN` (which is what the translate feature uses for failures) appears
in both. Turning on **Settings → Advanced → Verbose logging** drops either build
to `VERBOSE`. The reason to prefer the dev build is that it does not overwrite
the owner's app, not that release is silent.

## 7. Tests

```bash
./gradlew :app:testDebugUnitTest --tests "mihon.feature.translate.*"
```

The recognizer is a black box: bitmap in, boxes and strings out. Nothing here
tests the model. Everything the app decides *afterwards* takes those boxes and
strings as input, so a test writes them down directly — no image, no device.
That is also where every bug so far has actually been.

- **`OcrText`** — digit repair, the two-pass quality comparison, normalization,
  and the script-mismatch fallback.
- **`OcrLayout`** — `TextBox`/`TextItem` and the rules that rebuild a speech
  bubble out of the per-line boxes ML Kit returns: `shouldMerge`,
  `orderForReading`, `merge`.

Both are free of Android and of the classes that own ML Kit, network clients and
preferences. `PageTranslator` and `PageTextRecognizer` only convert to and from
`Rect` and call them.

Every case in `OcrTextTest` is one that once went the wrong way in the app, so
treat a failure as a real regression rather than a strict assertion. When
changing a rule there, confirm the suite still has teeth: break the rule on
purpose, watch the matching test fail, then restore it. A test that passes
either way is not protecting anything.
