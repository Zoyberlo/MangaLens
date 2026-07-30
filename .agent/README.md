# .agent

Vendor-neutral working knowledge for **any AI coding agent** operating on this
repository. The goal is to let an agent answer "where do I find X" and "how do I
do Y" **without scanning the whole codebase** — saving context window, tokens,
and time.

This repo is a **fork of [Mihon](https://github.com/mihonapp/mihon)** that adds an
on-device translation feature. Most of the code is upstream's; the fork-specific
parts are small and clearly marked. Knowing which is which is the single most
useful thing an agent can learn here — see
[`context/fork-vs-upstream.md`](./context/fork-vs-upstream.md).

## How an agent should use this folder

1. **Always start at [`INDEX.md`](./INDEX.md).** It maps every topic to the
   relevant `.agent` doc *and* the real source file(s) — the "source of truth".
2. **Load only what the task needs.** Read the one or two index rows that match,
   open those docs, then open the named source files. Do not pre-load everything.
3. **Treat the source as authoritative.** If a `.agent` doc disagrees with the
   code, the code wins — and the doc should be corrected (see Maintenance below).
4. **Follow `processes/` step-by-step** for repeatable tasks instead of
   improvising.

## Folder purpose

| Folder        | Answers the question      | Contents |
|---------------|---------------------------|----------|
| `context/`    | "What do I need to know?" | Durable reference: architecture, stack, conventions, glossary, fork delta, per-feature explainers. Read-mostly. |
| `specs/`      | "What am I building?"     | Per-feature specifications for active work: requirements, acceptance criteria, decisions. Finished specs move to `specs/_archive/` or are deleted. |
| `processes/`  | "How do I do X?"          | Step-by-step playbooks for repeatable tasks (build and install, add a translation provider, add a reader setting, sync with upstream). |
| `templates/`  | "What does a new ___ look like?" | Boilerplate scaffolds to copy (spec doc, PR description). |

## Maintenance rules

- This folder is **documentation, not code** — keep it concise and current.
- Update the relevant doc in the **same change** whenever you (a) make an existing
  doc wrong, (b) add or expose a meaningful preference, provider, or pipeline
  stage, or (c) learn a non-obvious constraint that future agents would need and
  can't easily derive from the code. Prefer extending the closest existing doc
  over creating a new one.
- New durable knowledge → `context/`. New repeatable task → `processes/`.
- Every new doc must be added as a row in `INDEX.md`, or an agent will not find it.
- Keep docs short. Link to source paths rather than copying code into the doc.
- **Touching an upstream file is a documented event.** Every edit outside
  `app/src/main/java/mihon/feature/translate/` widens the merge surface with
  upstream — record it in `context/fork-vs-upstream.md` in the same change.
- Change history lives in git — use `git log -- .agent/` / `git blame`. Do not add
  a changelog file; it would only drift from the code.
- **Close knowledge gaps.** If a task made you ask a human (or dig through code)
  for something `.agent` did not explain, fold the durable part of the answer into
  the relevant doc as part of that same task. Capture reusable facts and decisions;
  skip one-off, conversation-specific details.
- **Name it, define it.** Before closing a task, check whether it *coined or leaned
  on* a term of art that `context/glossary.md` does not define — then add the row.
  Glossary drift is silent and it compounds.

## Relationship to your agent's rule file

Most AI coding tools auto-load a rule file from the repo root. Common ones:

- `AGENTS.md` — cross-tool standard (present in this repo)
- `CLAUDE.md` — Claude Code
- `.cursorrules` / `.cursor/rules/` — Cursor
- `.github/copilot-instructions.md` — GitHub Copilot

Whichever your tool uses, that file should hold **behavioral rules plus a pointer
to `.agent/INDEX.md`** — it must not duplicate the knowledge here. Division of
labour: the rule file holds the rules; `.agent` holds the detail.
