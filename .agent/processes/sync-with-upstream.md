# Process: Sync the fork with upstream Mihon

Read `context/fork-vs-upstream.md` first — it lists exactly which files can
conflict.

## 1. Fetch

```bash
git fetch upstream
git log --oneline HEAD..upstream/main | head -50
```

If `upstream` is missing:
`git remote add upstream https://github.com/mihonapp/mihon.git`.

## 2. Choose merge or rebase

**Merge** (`git merge upstream/main`) is the default: the fork branch is published,
and a merge keeps the shared history honest.

**Rebase** only for a branch nobody has pulled. It replays every fork commit, so a
conflict in a hot file (e.g. `ReaderActivity.kt`) can resurface once per commit.

## 3. Resolve conflicts

Expect them only in the files listed in `context/fork-vs-upstream.md`. For each:

- **Keep upstream's structure, re-apply the fork's call-out.** Our edits are small
  additions (a method call, a parameter, one `when` branch) — port them onto the new
  upstream code rather than restoring our version of the whole block.
- Nothing under `mihon/feature/translate/` should ever conflict. If it does,
  someone edited fork-only code on both sides — check the history before resolving.
- Watch for renamed or resized upstream APIs the fork depends on:
  `ReaderPageImageView.pageView`, `SubsamplingScaleImageView` coordinate methods,
  `ReaderBottomBar`'s parameter list, `ReaderSettingsDialog`'s tab indices,
  `PagerViewer.currentPage`, `WebtoonRecyclerView`'s zoom matrix.

## 4. Re-check the version catalog

Upstream churns `gradle/libs.versions.toml` constantly (Renovate). Keep both sides:
their version bumps **and** our `mlkit-text` entries plus the `mlkit-text` bundle.

## 5. Verify

```bash
./gradlew spotlessApply :app:assembleDebug
```

A green build is not enough — the reader is where the fork lives:

- translate button appears in both paged and webtoon modes;
- selection overlay draws and dismisses;
- boxes stay pinned while zooming;
- Translation tab is still the 4th tab and shows the right controls;
- webtoon auto-detection still switches a long-strip series.

## 6. Record it

If upstream moved code such that the fork had to touch a **new** file, add a row to
`context/fork-vs-upstream.md`. If an upstream change invalidated something in
`context/features/reader.md`, fix it in the same commit.
