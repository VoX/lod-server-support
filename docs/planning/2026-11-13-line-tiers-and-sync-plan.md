# Line tiers and the sync model

Status: built 2026-11-13. Decisions [0001](../decisions/0001-line-tiers.md) and
[0008](../decisions/0008-main-is-the-reference.md). The rules as they stand are in
[support lines](../operations/support-lines.md); this page records why and what review changed.

## 1. Why

0.20.0 downloads by line: 26.3 48.8 %, 1.21.1 28.0 %, 26.2 10.6 %, 1.21.11 8.8 %, 26.1 3.3 %,
1.21.10 0.4 %. Every non-doc commit on any line cost a port to five other lines and a "six-line
re-pin": identical `source-refs.json`, `classification.json` (0.8 MB, a reviewed blob per adapted
file per line) and `port-batch.json` committed on all six lines, each pinning the others' pre-pin
commits. 52 of the 318 non-merge commits on main since 2026-09-20 touched only those files, and 61
commits touched the generated compatibility page while 4 changed a fact on it.

## 2. What was built

- **Tiers.** Full: 26.3 and 1.21.1. Maintained: 26.2 and 1.21.11. Frozen: 26.1 and 1.21.10. Sponge
  ships from main only.
- **Main is the reference.** A follower records the main commit it is synced to in
  `config/lines/sync.json`, with the paths that differ on purpose (`adapted`) and main-only paths
  it never carries (`absent` globs). Nothing on main points at a follower's moving head, so a main
  commit needs no bookkeeping and a port PR carries its own sync bump.
- **Role is written down.** `config/compatibility/line.json` says `reference`, `follower` or
  `frozen`, and `catalog.py validate` fails when the files a line carries disagree.
- **Checks** (`tools/lines/lines.py`, run by `tools/compat/ci.py`): shared files equal main at the
  sync point (content and mode); listed files really differ; `must_match` prefixes from main's
  `lines.json` cannot be adapted; the sync point is on main and only moves forward. Against the
  change's base: each adapted file main changed is compared with a mechanical replay of main's
  change, and an untouched one needs a `Not-ported:` trailer; a newly adapted file needs an
  `Adapted:` trailer; a changed wire corpus needs a `Corpus-regenerated:` trailer.
- **Release rule.** `tag_chain.sh` refuses a follower whose sync point differs from main's release
  tag in any shared path.
- **Deleted.** `classification.json`, `port-batch.json`, `source-refs.json`, `port_batch.py`, the
  `plan`/`prepare`/`resume` commands, `catalog.py render`, and the generated compatibility page
  and README table (now short hand-kept ones on main).

## 3. What this gives up

- An adapted file on a follower is no longer pinned byte for byte. The old pin was self-attested
  (every change turned the check red and re-recording cleared it), and no commit or record shows it
  ever exposing a dropped hunk. Its review now happens in the port PR, with the hand-made part of
  each file printed by CI.
- Followers are checked against main, not against each other.
- Parity is enforced when a line is synced, not on every main commit. The sync point shows how far
  behind a line is, and a release requires it to be current.
- Frozen branches are not touched now. They keep the tooling of their time until a first fix.

## 4. Review fold (two reviewers, 2026-11-13)

| Finding | Disposition |
| --- | --- |
| Release order was a hash cycle (`snapshot` in main's release commit vs followers synced to it) | `snapshot` removed with the generated page; order is main, then follower ports, then tags; "in step" is defined as no shared-path difference from main's tag and enforced in `tag_chain.sh` |
| Role by file presence failed open (a lost `sync.json` read as frozen) | role written in `line.json`; presence must agree; the port never copies `config/lines/` or `config/compatibility/` |
| Readers of the deleted files not listed | `required_matrix_status.py`, `verify.py`, the gate script and the removed tests updated; no Java test or workflow read them |
| `not_ported` entries evaporated at the next sync | replaced by commit trailers, which stay in history |
| `adapt` needed no reason; 1,236 identical paths could be forked by one list entry | `must_match` prefixes read from main, plus an `Adapted:` trailer for each newly listed path |
| Rename and mode traps in the changed-path rule | trees are compared entry by entry as (mode, blob); one existing mode drift fixed (`scripts/sponge_run_server.py`) |
| "The port touched the file" proved nothing (18 of 41 touched files in 0.21.0 were hand-edited) | replay rule: equal to the mechanical merge passes silently, hand edits are printed, untouched needs a reason |
| Per-line wire corpora became freely regenerable | corpus rule with a trailer |
| The generated compatibility page cost 61 commits for 4 fact changes | page and README table are hand-kept; `lines.json` holds tier and branch only |
| Follower-first fixes would be "solved" with `adapt` | written rule: the fix goes to main first |
| A design that survives folding 26.2 into main | `absent` globs cover main-only overlay paths with one entry |
| Three seeded breaks and two frozen-branch PRs were process weight | one seeded break; frozen branches left alone until a first fix |

Measured at 0.21.0: the first `adapted` lists are 97 paths (26.2), 202 (1.21.11) and 389 (1.21.1);
no path the old catalogue called identical differs on any follower. Replaying the rules over
0.20.0 to 0.21.0: 23 adapted files were mechanical ports, 18 hand-edited, and 1.21.1 would have
needed four written reasons (two files absent there, two that use a 26.x-only key-event API).
