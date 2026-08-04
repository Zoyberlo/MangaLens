# What's new

## 1.0.3

### Text recognition rebuilt around PaddleOCR

Reading text off a page is now done by PaddleOCR, and it is the default for
every language except Japanese, which still uses the previous engine. On the
stylised, hand-lettered fonts scanlations use, it reads whole lines the old
engine turned into nonsense.

Two things that used to quietly ruin a result are gone:

- **A tall selection is no longer squashed to fit.** Selecting a large area
  used to shrink the image before reading it, so the smallest lettering
  dissolved. Big selections are now read in bands at full size.
- **Misread letters are repaired against an English dictionary.** This lettering
  fails in patterns — every L coming back as V, every R as Z — so LEARN became
  VEARN and REASON became ZEASON. Those are now corrected. A name the dictionary
  has never heard of is left exactly as it was.

When a reading is too garbled to trust, the app says so instead of handing you a
confident translation of gibberish.

### Correct it yourself

Recognized text appears in a panel at the bottom of the screen, and you can edit
it. Type the correction, or dictate it — dictation happens in place, without a
system dialog covering the page you are reading.

### Retry a bad block on a better engine

Tapping a badly recognized block offers a retry that re-reads it with a cloud
engine of your choosing: Google Cloud Vision, Azure, or Gemini. It only runs
when you ask, so nothing is billed behind your back, and each engine has a
monthly cap you set yourself. DeepL has one too.

Every engine now tells you which one actually read the page, so "no improvement"
and "it silently fell back" stop looking identical.

### Settings

Translation has its own entry in Settings instead of hiding inside Reader.
Recognition engines can be picked per language. Every API key field has a **?**
next to it explaining where to get that key, with links.

There is also a self-test that renders a line of text and reads it back, so you
can confirm on-device recognition works without hunting for a page to try it on.

### Smaller downloads

Releases now ship one build per processor architecture. Updating downloads about
65 MB instead of 94 MB, with nothing left out.

### Elsewhere

- **Check for updates** moved onto the More screen, next to the version you are
  running, instead of being three screens deep.
- Sharing a crash log now opens an email to the developer rather than pointing
  at a chat channel this app has nothing to do with.
- Extensions are re-checked after migrating from another app, so restoring your
  extension repos stops the trust prompts immediately instead of after a
  restart.

## 1.0.2

The first releases under the MangaLens name: the fork's own icon, its own
in-app updater pointed at its own releases, and selective import of backups
from Mihon-family apps. Full history is on the releases page.
