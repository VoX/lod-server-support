# 0003 Old protocol support is measured before it is judged

Status: accepted 2026-11-13 (VoX).

## Decision

The server counts sessions by the protocol dialect they negotiated (16, 18, 20, and Via-translated)
and shows the totals in diagnostics. Support for protocol 16 and 18 clients stays exactly as it is.
After a month of data VoX decides whether a retire rule is written, for example "under 1 % of
sessions for 60 days".

## Why

The compatibility paths are 51 dialect branches and 95 tests, kept "permanently" with no knowledge
of how many players use them. Each new protocol version also needs another hand-written rung,
because the session gate matches exact versions.

## Consequences

- Nothing about wire behaviour changes. Wire fidelity is never tiered.
- The counters are local to each server. Nothing is uploaded; operators who report issues paste
  the diagnostics line as they do today.

## Revisit when

A month of diagnostics from the test server and from issue reports exists.
