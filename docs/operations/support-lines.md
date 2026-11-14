# Support lines

Main (Minecraft 26.3) is the reference. Each support line is a branch that follows main and records
the main commit it is synced to. The tiers and why: [decision 0001](../decisions/0001-line-tiers.md).
The design and its review: [plan](../planning/2026-11-13-line-tiers-and-sync-plan.md).

| Role | Lines | Carries | Checked by `tools/compat/ci.py` |
| --- | --- | --- | --- |
| reference | 26.3 (`main`) | `config/lines/lines.json` | its own facts, the line table |
| follower | 1.21.1, 26.2, 1.21.11 | `config/lines/sync.json` | its own facts, and that it is in step with main |
| frozen | 26.1, 1.21.10 | neither | its own facts |

A line's role is written in its `config/compatibility/line.json` and must agree with the file it
carries. A follower without `sync.json` is an error, not a frozen line.

## What a follower must keep true

- Every file equals main at the sync point, unless it is listed under `adapted` in `sync.json` or
  is line-local (`*.md`, `LICENSE`, `docs/`, `config/compatibility/`, `config/lines/`).
- Every listed file really differs. `lines.py tidy` drops entries that are identical again.
- Paths under main's `must_match` prefixes (the line tools, the release tools, most of `common`)
  can never be adapted.
- The sync point is a commit on main, and only moves forward. Main merges before a port.
- A fix a follower needs in a shared file goes to main first; then the line syncs. `adapt` is for
  behaviour that must differ, never for a fix main lacks.

Three things need a written reason, as a commit trailer on the line's branch:

```
Adapted: <path>: <why>              a shared file becomes line-specific
Not-ported: <path>: <why>           main changed an adapted file and the line left it alone
Corpus-regenerated: <path>: <why>   a captured wire corpus changed and main's did not
```

## Porting

In the follower's worktree, on a branch from the support branch:

```sh
python3 tools/lines/lines.py port --to <main commit> --dry   # classify only
python3 tools/lines/lines.py port --to <main commit>         # apply, move the sync point
python3 tools/lines/lines.py status                          # what still differs
```

`port` three-way merges each file main changed into the line's own version. Read every
`merged_clean` file: a clean merge can still be wrong. Resolve `conflict` files by hand, decide
each `target_missing` file, then run the line's tests. `status` must be clean before the PR.

CI then checks the PR against its target branch. For every adapted file main changed, it replays
main's change mechanically onto the line's old version. A port that equals the replay passes
silently. A hand-edited port passes and its difference from the replay is printed in the CI log
for the reviewer. A file the port left untouched fails unless a `Not-ported:` trailer says why.

## Releases

A release ships a follower only when its sync point is the release commit on main or a later one;
`tools/release/tag_chain.sh` refuses to push a follower's tag otherwise. See
[the release tools](../../tools/release/README.md).

## Frozen lines

26.1 and 1.21.10 keep their 0.21.0 branches, tags and published files. They are not synced. A
crash or security fix is made on the frozen branch itself. Those branches still carry the tooling
of their time; the first fix on one removes the old cross-line step from its `catalog.yml` and
sets `"role": "frozen"`, `"support_tier": "frozen"` in its `line.json`; its old `ci.py` then says
"frozen line" and compares nothing, so main's `gate.sh` runs on it unchanged. Such a fix ships as a
patch of that line's own version (0.21.1), tagged `v<x.y.z>+mc<line>` on the frozen branch's merge
commit and pushed with `LSS_RELEASE_LINES=<line>[,<line>] tools/release/tag_chain.sh <x.y.z>`: the
release tools skip the in-step check for frozen lines, so a release of frozen lines only needs no
tag on main. The line's own `release.yml` publishes it (`LINE_MAKE_LATEST=false`, never Latest).

## Other tools

`python3 tools/compat/catalog.py validate` checks this line's facts against `.github/line.env`,
Gradle properties and the renderer constant. `catalog.py inspect <jar>` reads a mod jar's embedded
metadata and hashes its bytes. `tools/verify/verify.py` selects test tasks (`fast`, `platform`,
`integration`, `full`; add `--run` to execute; a fast selection is advisory). Java 21 builds the
1.21.x lines and Java 25 the 26.x lines; common tests always run on Java 21. Client gametests run
on a private display (`xvfb-run`) and are absent on 1.21.1.
