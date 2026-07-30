# specs/

One file per **feature currently being built**. A spec is the thing you agree on
*before* writing code, and the thing a reviewer reads instead of reconstructing
intent from a diff.

## When to write one

| Work | Artifact |
|------|----------|
| New feature touching the reader or upstream files | Spec, agreed before building |
| Reworking a large existing feature | Spec, agreed before building |
| Small fix, or work confined to `mihon/feature/translate/` | None — just build it |
| Can't judge the size | Ask |

## Structure

Copy `templates/spec.md`. It expects these sections, in this order:

- **Header** — status (`draft` / `in progress` / `done`), date, links to the
  `context/` docs the work touches.
- **Problem / goal** — one paragraph. What a user sees today and why it is wrong,
  not what the code does.
- **Scope** — explicitly in and out. The "out" list is the useful half.
- **Requirements** — numbered, so review comments can point at one.
- **Design / approach** — files touched, new files, preferences, strings, and
  **which upstream files it forces us to edit** (that is the expensive part in a
  fork). **Include illustrative code**: a data class, a function signature, a
  representative block. It does not need to compile; it is the fastest way for a
  reviewer to catch a problem before any code exists.
- **Acceptance criteria** — checkboxes, including the build gate and manual
  on-device verification.
- **Open questions** — anything that needs a human decision. Mark the ones that
  **block** building, so nobody starts on a coin-flip.

## Lifecycle

1. Draft the spec, get it agreed, then build.
2. Update the spec if the design changes during the build — a spec that describes
   something you did not ship is worse than none.
3. When it ships, move it to `specs/_archive/` and flip its row in `INDEX.md`.
   Keep archived specs: they explain why the code looks the way it does, and they
   are the only record of options that were considered and rejected.
