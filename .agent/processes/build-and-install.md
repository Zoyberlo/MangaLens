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

## 2. Build

```bash
./gradlew spotlessApply :app:assembleDebug
```

Always run `spotlessApply` in the same invocation — ktlint failures are the most
common reason a build breaks after an edit. For a quick syntax check without
packaging, `:app:compileDebugKotlin` is much faster.

Output: `app/build/outputs/apk/debug/`

| APK | Use |
|-----|-----|
| `app-arm64-v8a-debug.apk` (~69 MB) | Any modern phone — the default choice |
| `app-universal-debug.apk` (~136 MB) | Unknown target device |
| `app-armeabi-v7a`, `app-x86*`| Old or emulator targets |

## 3. Install

```bash
adb devices                                                   # confirm the device is listed
adb install -r app/build/outputs/apk/debug/app-arm64-v8a-debug.apk
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

## 4. Verify

There are no automated tests for the fork's feature. Check by hand on the device
and report exactly what was and was not verified. For the translation feature:

1. Settings → Reader → Translation: language pair and provider are set.
2. Open a chapter, wait a moment for warm-up.
3. Translate button → drag over a bubble → overlay appears with sensible text.
4. Zoom and pan — the box stays on the bubble.
5. Tap the box → X appears → tap the X → the box disappears.
6. Repeat in the other reading mode (paged vs webtoon).

Logs while reproducing: `adb logcat --pid $(adb shell pidof app.mihon.tl.dev)`.
