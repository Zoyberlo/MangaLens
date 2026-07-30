# Spec: <feature name>

> Copy this file to `.agent/specs/<feature-name>.md` for active work. Move it to
> `.agent/specs/_archive/` (or delete it) once shipped.

**Status:** draft | in progress | done
**Date:** <YYYY-MM-DD>
**Related context:** <links to `.agent/context/...` docs>

## Problem / goal

What are we solving and why. One paragraph. If the current behavior is wrong,
describe what a user sees — not what the code does.

## Scope

- In scope:
- Out of scope:

## Requirements

1. ...
2. ...

## Design / approach

How it will be built. Name the files touched.

**Include illustrative code.** Sketch the shape of the key new pieces — a data
class, a function signature, a Compose block — in fenced code blocks. It does not
need to compile; it is the fastest way for a reviewer to see the design and catch
problems before any code is written.

- New files (`app/src/main/java/mihon/feature/...`):
- **Upstream files this forces us to edit** (see `context/fork-vs-upstream.md`):
- Preferences (`ReaderPreferences`): <new keys, or "none">
- Strings (`i18n/.../base/strings.xml`): <new keys, or "none">
- Dependencies (`gradle/libs.versions.toml`): <new entries, or "none">

## Acceptance criteria

- [ ] ...
- [ ] `./gradlew spotlessApply :app:assembleDebug` passes
- [ ] Verified on a device in **both** paged and webtoon modes (no automated tests)
- [ ] `.agent` docs updated (`fork-vs-upstream.md` if new upstream files were touched)

## Open questions

- ... (mark the ones that **block** building)
