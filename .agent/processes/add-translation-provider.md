# Process: Add a translation provider

All backend code lives in `mihon/feature/translate/TextTranslator.kt`. No upstream
file changes for a provider that needs no new preference.

## 1. Add the enum value

`TranslationModels.kt`:

```kotlin
enum class TranslationProvider(val displayName: String) {
    AUTO("Auto"),
    GOOGLE("Google"),
    // ...
    MYNEWONE("My New One"),
}
```

The enum name is persisted by `getEnum` — do not rename existing entries.

## 2. Implement the call

In `TextTranslator`, next to the existing ones:

```kotlin
private suspend fun translateViaMyNewOne(text: String, from: String, to: String): String? {
    return try {
        val url = "https://example.test/translate".toHttpUrl().newBuilder()
            .addQueryParameter("q", text)
            .build()
        val response = client.newCall(GET(url)).awaitSuccess()
        with(json) { response.parseAs<MyNewOneResponse>() }.text?.takeIf { it.isNotBlank() }
    } catch (e: Exception) {
        logcat(LogPriority.WARN, e) { "MyNewOne translation failed" }
        null
    }
}
```

Rules that matter here:

- **Return `null` on failure, never throw** — the caller treats `null` as "try the
  next provider".
- Use the class's `client` (8s call timeout), `GET`/`POST` from
  `eu.kanade.tachiyomi.network`, and `awaitSuccess()`.
- Parse with `@Serializable` response classes and `parseAs<T>()`; for
  irregular JSON (like Google's array-of-arrays) use
  `json.parseToJsonElement(...)`.
- Do not normalize the text yourself — `translate()` already did.

## 3. Wire it into the dispatch

```kotlin
val result = when (selectedProvider) {
    TranslationProvider.AUTO -> ...
    TranslationProvider.MYNEWONE -> translateViaMyNewOne(trimmed, from, to)
    ...
}
```

If it belongs in the `AUTO` chain, add it there **and** record the winner:

```kotlin
?: translateViaMyNewOne(trimmed, from, to)
    ?.also { lastAutoProvider.value = TranslationProvider.MYNEWONE }
```

Order the chain fastest/most-reliable first.

## 4. If it needs a key or other config

1. Add a `Preference<String>` in `ReaderPreferences.kt`.
2. Read it inside the provider method; return `null` and log when it is missing.
3. Gate the UI: disable the chip in `TranslationSettingsPage.kt` and filter the
   entry out of the list in `SettingsReaderScreen.kt` — copy the DeepL pattern.
4. Add the fallback in `translate()` so a stale selection degrades to `AUTO`
   instead of failing every request.
5. New strings go in `i18n/.../base/strings.xml`.

## 5. Verify

```bash
./gradlew spotlessApply :app:assembleDebug
```

Then on-device: pick the provider explicitly, translate a bubble, and confirm the
"Auto (…)" label reports it when it wins the chain. Update
`context/features/translate.md`'s provider table, and
`context/fork-vs-upstream.md` if you touched a new upstream file.
