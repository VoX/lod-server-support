# 0005 The Voxy Server Side build also answers to the LSS names

Status: accepted 2026-11-13 (VoX approved "both builds accept both names"); narrowed to one
direction the same day after plan review.

## Decision

The VSS build registers `/lss` and `/lsslod` beside its own `/vss` and `/vsslod`, and accepts
`lss.admin` beside `vss.admin`. Its own names stay the ones shown in its help and messages. The LSS
build is unchanged: it does not gain `/vss`.

## Why

86 % of downloads are the VSS build, and its users read LSS documentation and issue replies that
name `/lss`. Seven of 43 issues came from install or brand confusion. With the VSS build answering
to the LSS names, every document can say `/lss`. The other direction helps nobody: no LSS user is
told to type `/vss`, and it would add a second brand to tab completion and a second admin node to
every permission audit.

## Consequences

- Wire behaviour, mod id and file names do not change.
- A server that already has another plugin's `/lss` keeps that plugin's command; the VSS build
  stays reachable as `/vss`.

## Revisit when

A real command collision is reported, or LSS users are found following instructions that say `/vss`.
