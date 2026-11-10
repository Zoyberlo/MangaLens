## 1.0.5

### Built on Mihon 0.21

MangaLens now runs on Mihon's newest release: much faster with big
libraries, reader pages loaded ahead of time, and many download, backup
and tracker fixes. The first start after updating converts your library,
which takes a moment and can't be undone, so make a backup first
(Settings → Data and storage).

### Extensions that could never update

- Extensions installed from a repo you no longer have can't update: Android only accepts an update signed with the same key
- The Extensions tab now offers to replace those with the same extension from one of your repos, keeping your library linked to them
- Extensions only update from the repo they came from, and show which one that is

### Translation

- Translating a bubble again replaces its box instead of stacking a second one on top
- Each line is read with a margin around it, so letters at the edges are no longer cut off
- Watermarks and lettering in the artwork no longer show up as untranslated boxes
- A number in a bubble no longer makes the whole translation fail
- When a free service hands the English back untranslated, the next service is tried

### Reader

- Choose which buttons the bottom bar shows (reader settings → General); the settings button always stays
- The area selector mentions, the first few times, that a long press on the translate button translates the whole screen
- "Flash on page change", meant for e-ink screens, no longer fires in long strip, where the page number changes wherever two images meet; "Also flash in long strip" turns it back on
- Reader settings tabs have names that fit

### Also in this update

1.0.4 was never published on its own, so its changes arrive now too:

- About → What's new shows these notes inside the app
- Check for updates is on the More screen, next to the version you are running

## 1.0.4

### Release notes in the app

About → What's new now shows what changed, written for readers. It used to be
a link into a browser showing a list of commit subjects.

### Elsewhere

- Check for updates is on the More screen, with the version you are running next to it
- The logo in More and About is the app's real artwork instead of the flat shape notifications use
- A help link says it opens Mihon's documentation, and that this app has none of its own yet, before it takes you there

### From Mihon 0.20.3 and 0.20.4

- Category filters on the Updates and Upcoming tabs
- The reader no longer loads forever in some situations
- Relative dates ("3 days ago") display correctly again
- MyAnimeList no longer errors on entries with partial dates
- App updates download without WorkManager, and the update check no longer repeats on rotation

## 1.0.3

### Text recognition

A new engine, PaddleOCR, now reads every language except Japanese. It handles
the stylised lettering scanlations use, where the old one often returned
nonsense.

- Large selections are read at full size instead of being shrunk, so small lettering survives
- Misread letters are corrected against a dictionary — VEARN is read back as LEARN
- A reading too garbled to trust is flagged, not translated as if it were fine

### Fix a bad reading yourself

Recognized text appears in a panel you can edit. Type the correction, or dictate
it without a system dialog covering the page.

### Retry on a cloud engine

Tap retry on a bad block to re-read it with Google Vision, Azure or Gemini. It
runs only when you ask, and each has a monthly cap you set.

### Smaller updates

One build per processor architecture: about 65 MB instead of 94 MB.

### Also

- Translation has its own settings menu, with per-language engines and a **?** explaining every API key
- Sharing a crash log opens an email to the developer

## 1.0.2

The first releases under the MangaLens name: its own icon, its own updater, and
selective import of backups from Mihon-family apps.
