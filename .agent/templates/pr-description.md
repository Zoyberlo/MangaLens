# PR description template

## Summary

One or two sentences: what changed and why.

## Changes

- ...
- ...

## Affected areas

- Feature code (`mihon/feature/...`): <...>
- **Upstream files touched**: <list, or "none"> — merge-conflict surface
- Preferences: <new keys, or "none">
- Strings (`i18n`): <new keys, or "none">
- Dependencies (version catalog): <new entries, or "none">
- `.agent` docs: <which docs were updated, or "none needed">

## Testing

How this was verified (manual — the fork's feature has no automated tests):

- [ ] `./gradlew spotlessApply :app:assembleDebug`
- [ ] Installed on a device
- [ ] Paged mode: <what was checked>
- [ ] Webtoon mode: <what was checked>

## Notes for reviewers

Anything non-obvious, trade-offs taken, or follow-ups deliberately left out.
