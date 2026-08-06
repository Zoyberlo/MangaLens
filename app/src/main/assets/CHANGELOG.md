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
