# Feature: Reader settings & preferences

## Where a reader preference lives

1. **Declaration** — `ui/reader/setting/ReaderPreferences.kt`, a plain class over
   `PreferenceStore`:

   ```kotlin
   val translationProvider: Preference<TranslationProvider> =
       preferenceStore.getEnum("pref_translation_provider", TranslationProvider.AUTO)
   ```

   Registered as a singleton in `di/PreferenceModule.kt`. Keys are string literals
   and are **persisted** — renaming one silently resets users' settings.

2. **Global UI** — `presentation/more/settings/screen/SettingsReaderScreen.kt`,
   declarative items: `SwitchPreference`, `ListPreference`, `SliderPreference`,
   `EditTextPreference`, grouped by `Preference.PreferenceGroup`.

3. **In-reader UI** — `presentation/reader/settings/*Page.kt`, driven by
   `ReaderSettingsViewModel` (which exposes `preferences`). Components come from
   `presentation-core`: `SelectItem` (dropdown), `SettingsChipRow` + `FilterChip`,
   `CheckboxItem`, `SliderItem`.

4. **Strings** — `i18n/src/commonMain/moko-resources/base/strings.xml`, referenced
   as `MR.strings.<name>`. Never hard-code user-facing text.

Compose reads a preference with `.collectAsState()` from
`tachiyomi.presentation.core.util`.

## Which surface to use

| Preference | Where |
|------------|-------|
| Rarely changed, or needs typing (API keys) | Global screen only |
| Changed while reading | Both: reader tab **and** global screen |
| Per-manga (reading mode, orientation) | Reader dialog's Reading mode tab — these write viewer flags on the manga, not `PreferenceStore` |

The fork's translation preferences are global (they apply to every manga). They
live in `ReaderPreferences` for historical reasons, but their **settings screen
is not the reader one** — see below.

## Translation preferences

| Preference | Key | Notes |
|------------|-----|-------|
| `autoTranslateSourceLanguage` | `pref_auto_translate_source_lang` | `TranslationSourceLanguage` enum; picks the ML Kit recognizer |
| `autoTranslateTargetLanguage` | `pref_auto_translate_target_lang` | plain language code from `TARGET_LANGUAGES` |
| `translationProvider` | `pref_translation_provider` | `TranslationProvider` enum |
| `deeplApiKey` | `pref_deepl_api_key` | empty string = DeepL unavailable |

The `pref_auto_translate_*` keys date from the removed auto-translate feature; they
were kept so existing installs keep their language pair.

## Where they are edited

| Surface | What it holds |
|---------|---------------|
| **Settings → Translation** (`SettingsTranslationScreen`) | Languages, provider, DeepL key and limit, result display, and a link onward to Text recognition |
| **Settings → Translation → Text recognition** (`SettingsRecognitionScreen`) | OCR engines, per-language overrides, every cloud key and quota |
| **Reader → Settings dialog → Translation tab** (`TranslationSettingsPage`) | Language pair, provider, display mode only — for changing them mid-chapter |

Translation is a **top-level** settings entry, not a group under Reader. Reader
settings are about how pages are displayed; these are about languages, services
and paid accounts, and having them in one list made both harder to scan. The
in-reader dialog tab stays because switching a language pair without leaving the
page is the common case while reading.

The class still lives in `ReaderPreferences` — moving the keys would reset every
existing install's configuration for no user-visible gain.

Adding one: `processes/add-reader-setting.md`.
