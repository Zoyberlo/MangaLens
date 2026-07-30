# Glossary

Terms that mean something specific in this repo. Ambiguous ones are marked.

| Term | Meaning |
|------|---------|
| **Upstream** | `mihonapp/mihon`, the repo this is forked from. "Upstream file" = a file the fork edits but does not own. |
| **Viewer** | The reader's page-display strategy: `PagerViewer` (paged) or `WebtoonViewer` (continuous strip). Not a UI widget. |
| **Holder** | Per-page view object: `PagerPageHolder` (a View in the pager) or `WebtoonPageHolder` (a RecyclerView holder). Where page bytes are available. |
| **SSIV** | `SubsamplingScaleImageView` — tiled zoomable image view used for static pages. Source of the coordinate mapping the overlay depends on. |
| **Source coordinates** | Coordinates in the image SSIV is displaying (`sWidth`/`sHeight`). ⚠️ **Not** necessarily the original file's pixels — long strips are downsampled by Coil. |
| **Full-image coordinates** | Coordinates in the original decoded file, what `BitmapRegionDecoder` expects. Conversion factor: `outWidth / regionSpaceWidth`. |
| **View coordinates** | Pixels of the on-screen View, what a `MotionEvent` carries. |
| **Region** | The user's selection rectangle, in whichever space the surrounding code documents. Always check which. |
| **Block** | One unit of recognized text. `RecognizedBlock` (source text + bounds) → `TranslatedBlock` (adds the translation). ML Kit usually emits one per *line*, which is why `mergeBlocks()` exists. |
| **Page translation** | `PageTranslation` — the block list plus the image dimensions its bounds are relative to. The overlay needs both to scale correctly. |
| **Provider** | A translation backend (`TranslationProvider`): Auto, Google, DeepL, Lingva, MyMemory. |
| **Instance** | A specific public Lingva server URL. Instances fail independently, hence the per-instance backoff. |
| **Warm-up** | `PageTranslator.warmUp()` — loads the OCR model and probes providers when the reader opens, so the first real translation is not the slow one. |
| **Reading mode** | `ReadingMode` enum + per-manga viewer flag. `DEFAULT` means "fall back to the global preference", which is what webtoon auto-detection checks before overriding. |
| **Preference** | `Preference<T>` from `PreferenceStore`. Its string key is persisted — renaming a key resets that setting for existing users. |
| **MR** | moko-resources generated accessor: `MR.strings.foo` resolves a string from `i18n/`. |
| **Flavor `foss`** | Build flavor that excludes Google libraries. Relevant to ML Kit — see `context/stack.md`. |
