# 0003 Old protocol support is measured before it is judged

Status: accepted 2026-11-13 (VoX). Amended the same day after plan review: no file on disk.

## Decision

The server counts each session once, under the protocol dialect it ended with (current, 19, 18, 16),
and shows the totals since start with the uptime they cover in diagnostics (`ended=` on the
`Dialects:` line). Support for protocol 16 and 18 clients stays exactly as it is. After a
month of data VoX decides whether a retire rule is written, for example "under 1 % of sessions for
60 days".

## Why

The compatibility paths are 51 dialect branches and 95 tests, kept "permanently" with no knowledge
of how many players use them. Each new protocol version also needs another hand-written rung,
because the session gate matches exact versions.

## Consequences

- Nothing about wire behaviour changes. Wire fidelity is never tiered.
- The counters are in memory and local to each server. Nothing is written or uploaded.
- The test server alone cannot answer the question: it attracts players on the newest build.
  Modrinth's downloads per version (how many clients older than a protocol are still being
  installed) and diagnostics pasted into issue reports are the other two sources.

## Revisit when

A month of those three sources exists.
