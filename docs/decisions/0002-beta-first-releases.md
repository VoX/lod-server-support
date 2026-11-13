# 0002 Releases go out as a beta first

Status: accepted 2026-11-13 (VoX).

## Decision

A release is published first as `X.Y.Z-beta.N` on the two full lines (26.3 and 1.21.1): a Modrinth
beta and a GitHub prerelease, never Latest, not featured, not on VSS or CurseForge. It runs on the
test server and soaks for 72 hours. If nothing breaks, `X.Y.Z` is built from the same code and
published on every full and maintained line. The aim is about one stable release a month.

The maintainer promotes a beta. A change to a privacy or visibility default waits for VoX.

## Why

0.21.0 went from plan to published on six lines in about ten hours and changed who is visible on
every server that updates. 36 releases shipped in eight months, each to everyone at once.

## Consequences

- A beta's jars carry the beta version; the stable release is a new build of the same code.
- A problem found in the soak gets a fix and `beta.N+1`, and the 72 hours start again.
- A crash or security fix may skip the soak. The release notes say that it did.

## Revisit when

Betas get too few downloads to find anything (then lengthen the soak or announce them), or a
quarter passes in which no beta found a problem and the delay cost players a fix they needed.
