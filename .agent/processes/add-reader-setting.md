# Process: Add a reader preference and its UI

Background: `context/features/reader-settings.md`.

## 1. Declare the preference

`app/src/main/java/eu/kanade/tachiyomi/ui/reader/setting/ReaderPreferences.kt`:

```kotlin
val myToggle: Preference<Boolean> = preferenceStore.getBoolean("pref_my_toggle", false)
val myChoice: Preference<MyEnum> = preferenceStore.getEnum("pref_my_choice", MyEnum.DEFAULT)
```

Put it in the region matching its topic. The string key is persisted — pick it once
and do not rename it later without a migration.

## 2. Add the string

`i18n/src/commonMain/moko-resources/base/strings.xml`:

```xml
<string name="pref_my_toggle">Do the thing</string>
<string name="pref_my_toggle_summary">Explains what the thing does</string>
```

Only edit `base/`. Reference as `MR.strings.pref_my_toggle`.

## 3. Surface it — global screen

`app/src/main/java/eu/kanade/presentation/more/settings/screen/SettingsReaderScreen.kt`,
inside the relevant `Preference.PreferenceGroup`:

```kotlin
Preference.PreferenceItem.SwitchPreference(
    preference = readerPreferences.myToggle,
    title = stringResource(MR.strings.pref_my_toggle),
    subtitle = stringResource(MR.strings.pref_my_toggle_summary),
),
```

Other item types: `ListPreference` (map of value → label), `SliderPreference`,
`EditTextPreference`. Use `enabled = …` to gate an item on another preference.

## 4. Surface it — in-reader tab (only if it is changed while reading)

`app/src/main/java/eu/kanade/presentation/reader/settings/*Page.kt`:

```kotlin
val myChoice by viewModel.preferences.myChoice.collectAsState()
SelectItem(
    label = stringResource(MR.strings.pref_my_choice),
    options = MyEnum.entries.map { it.displayName }.toTypedArray(),
    selectedIndex = MyEnum.entries.indexOf(myChoice).coerceAtLeast(0),
    onSelect = { viewModel.preferences.myChoice.set(MyEnum.entries[it]) },
)
```

`SelectItem` for more than ~4 options, `SettingsChipRow` + `FilterChip` for few,
`CheckboxItem` for booleans, `SliderItem` for ranges.

**Adding a whole new tab** means editing `ReaderSettingsDialog.kt` in two places —
`tabTitles` and the `when (page)` branch. They are matched by index; keep them in
sync.

## 5. Make the viewer react (if it changes rendering)

Reactive viewer config lives in `viewer/ViewerConfig.kt` (+ `PagerConfig`,
`WebtoonConfig`) using the `Preference<T>.register(assignment, onChanged)` helper.
Use it instead of reading the preference during drawing.

## 6. Verify

```bash
./gradlew spotlessApply :app:assembleDebug
```

Then on device: toggle the setting on both surfaces and confirm they stay in sync
(they read the same `Preference`), and that the behavior changes without a restart.
Record any newly touched upstream file in `context/fork-vs-upstream.md`.
