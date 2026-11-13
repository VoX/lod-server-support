# 0001 Lines are tiered by who uses them

Status: accepted 2026-11-13 (VoX). Supersedes the practice of treating all six lines alike.

## Decision

| Tier | Lines | What it gets |
| --- | --- | --- |
| full | 26.3 (main), 1.21.1 | every release, betas, the real-stack test matrix |
| maintained | 26.2, 1.21.11 | every stable release: fixes, and features that port cleanly |
| frozen | 26.1, 1.21.10 | 0.21.0 stays published; crash and security fixes only |

Sponge ships from main only.

## Why

Downloads of 0.20.0 by line: 26.3 48.8 %, 1.21.1 28.0 %, 26.2 10.6 %, 1.21.11 8.8 %, 26.1 3.3 %,
1.21.10 0.4 % (270 downloads). NeoForge on 1.21.1 alone is 21.5 % of all downloads. Sponge is
1.2 %. Every change was being ported to, gated on and pinned across all six.

## Consequences

- 1.21.1 is no longer "correct, not perfect": it gets the same test attention as main.
- A feature that does not port cleanly to a maintained line is left off that line and said so in
  its release notes.
- Frozen lines keep their branches, tags and published files. Their README says so.

## Revisit when

A frozen line's share of a release's downloads passes 5 %, a maintained line's falls under 3 % for
two releases, or a new Minecraft line appears (it becomes main; the old main becomes maintained).
