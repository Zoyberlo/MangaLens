# Architecture

## Modules (`settings.gradle.kts`)

| Module | Namespace / source root | Contents |
|--------|------------------------|----------|
| `:app` | `app/src/main/java/{eu.kanade, mihon}` | All Android UI: reader, settings, DI modules, downloads, tracking, sources glue |
| `:core:common` | `tachiyomi.core.common`, `eu.kanade.tachiyomi.network` | `NetworkHelper`, OkHttp interceptors, `PreferenceStore`, `ImageUtil`, logging, coroutine helpers |
| `:core:archive` | `mihon.core.archive` | libarchive wrapper (CBZ/ZIP page loading) |
| `:core:viewmodel` | `mihon.core.viewmodel` | ViewModel plumbing |
| `:data` | `tachiyomi.data` + `src/main/sqldelight` | SQLDelight schema/queries, repository implementations |
| `:domain` | `tachiyomi.domain`, `mihon.domain` | Models, interactors, `*Preferences` holders |
| `:presentation-core` | `tachiyomi.presentation.core` | Shared Compose components (`SelectItem`, `SettingsChipRow`, `SliderItem`, `collectAsState` for `Preference`) |
| `:presentation-widget` | — | Glance app widget |
| `:source-api`, `:source-local`, `:core-metadata` | — | Extension API (`Page`, `HttpSource`), local source, ComicInfo |
| `:i18n` | `tachiyomi.i18n.MR` | **All user-facing strings** (`i18n/src/commonMain/moko-resources/base/strings.xml`) |

Two package roots coexist in `:app`: `eu.kanade.*` (legacy Tachiyomi lineage) and
`mihon.*` (newer code). **Fork code goes under `mihon.feature.*`.**

## Dependency injection — Injekt

No Hilt/Koin/Dagger. Bootstrapped in `App.kt`:

```kotlin
Injekt.importModule(PreferenceModule(this))
Injekt.importModule(AppModule(this))
Injekt.importModule(DomainModule())
```

- `di/AppModule.kt` — services and singletons (`NetworkHelper`, `SourceManager`,
  `DownloadManager`, `PageTranslator`, …)
- `di/PreferenceModule.kt` — all `*Preferences` classes
- `domain/DomainModule.kt` — repositories + interactors

Consume with `Injekt.get<X>()` or `by injectLazy()`.

## Preferences

`Preference<T>` from `PreferenceStore` (`core/common`). A preference is:

1. declared as a field in a `*Preferences` class (e.g. `ReaderPreferences`),
2. read with `.get()` / observed with `.changes()`, or in Compose with
   `.collectAsState()` (from `tachiyomi.presentation.core.util`),
3. surfaced in a settings screen.

Enums use `preferenceStore.getEnum(key, default)`.

## Reader (the part the fork extends)

The reader is **Views, not Compose** — Compose is used only for its overlay layer
and dialogs.

```
ReaderActivity  (reader_activity.xml: viewer_container + compose_overlay)
└── Viewer                      PagerViewer | WebtoonViewer
    └── page holder             PagerPageHolder | WebtoonPageHolder
        └── ReaderPageImageView (FrameLayout)
            ├── SubsamplingScaleImageView  (static images; exposes source↔view coord mapping)
            │   or PhotoView/AppCompatImageView (animated)
            ├── ReaderProgressIndicator
            └── TranslationOverlayView     ← fork
```

- `ReaderViewModel` holds `State(viewerChapters, currentPage, viewer, dialog, …)`.
- A page is a `ReaderPage` with `stream: (() -> InputStream)?` — the page bytes can
  always be re-read from it, which is how the fork gets full-resolution pixels.
- Page bytes are transformed in `process(...)` on an IO dispatcher inside each
  holder (dual-page split, rotate-to-fit) before being handed to the image view.
- `SubsamplingScaleImageView` provides `sourceToViewCoord` / `viewToSourceCoord`
  and an `OnStateChangedListener` — the basis for pinning overlays to the image
  through pan/zoom.

Details: `context/features/reader.md`. The translation pipeline that hangs off it:
`context/features/translate.md`.
