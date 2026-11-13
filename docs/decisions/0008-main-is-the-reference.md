# 0008 Main is the reference; support lines record one sync point

Status: accepted 2026-11-13. Supersedes the six-line re-pin.

## Decision

Main is the reference line. Each active support line records the main commit it is synced to
(`config/lines/sync.json`) and the paths that differ on purpose. Every other file must equal main.
A change on main needs no cross-line bookkeeping; a port PR carries its own sync bump. Rules and
commands: [support lines](../operations/support-lines.md).

## Why

One commit in six on main existed only to re-record pins, and the 0.8 MB pin file re-attested
whatever was committed. The check that mattered, "a shared file must not drift", needs one commit
id per line.

## Consequences

- A follower may lag main between releases. A release requires it to be in step.
- A fix a follower needs in a shared file goes to main first.
- Divergence that needs a reason is written as a commit trailer (`Adapted:`, `Not-ported:`,
  `Corpus-regenerated:`), so the reason stays in history next to the change.
- The compatibility page is a short hand-kept page on main.

## Revisit when

A port ships a wrong adaptation that an exact per-file pin would have stopped, or 26.2 has been
folded into main and the remaining followers no longer need the `adapted` list.
