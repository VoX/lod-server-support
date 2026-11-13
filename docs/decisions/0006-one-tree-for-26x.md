# 0006 The 26.x lines share one tree

Status: accepted 2026-11-13 (VoX), not built yet. Reopens the single-branch decision of 2026-08-28
for 26.3 and 26.2 only.

## Decision

26.2 is built from main's tree with a small per-version overlay, selected by a build property.
1.21.1 stays its own branch. 1.21.11 is decided after 26.2 has shipped one release this way.

## Why

26.2 differs from 26.3 in 14 files and 276 lines. 1.21.1 differs in 110 files and 4,591 lines. The
August evaluation tried to fold every line at once and was abandoned for the 1.21.x cost.

## Consequences

- One PR and one gate cover both 26.x versions.
- Building two versions from one tree leaves stale outputs unless each has its own build
  directory. The overlay work must solve that first.

## Revisit when

The overlay grows past about 40 files, or a 26.x release needs a change that the overlay cannot
express without conditionals in shared code.
