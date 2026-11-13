# 0007 A red test is explained, not retried

Status: accepted 2026-11-13 (VoX).

## Decision

No retry, longer deadline or looser assertion goes in without a named mechanism and a test that is
red without the fix. CI retries a failed step once only when every failure matches an open,
unexpired entry in `config/test-flakes.json`.

## Why

22 of 36 catalogued "flakes" ended in a code fix, three of them in the mod. A fault test whose
assertion was loosened to accept either outcome tested nothing on three lines for three months.

## Consequences

- CI is red more often at first. Each red gets its cause found.
- An injected fault has an independent witness: the test proves the fault was reached, not only
  that the outcome looked acceptable.

## Revisit when

Never for the rule. The catalogue's entries each carry their own expiry.
