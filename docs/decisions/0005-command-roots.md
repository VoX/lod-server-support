# 0005 Both builds accept both command names

Status: accepted 2026-11-13 (VoX).

## Decision

The LSS and Voxy Server Side builds each register `/lss` and `/vss`, and `/lsslod` and `/vsslod`.
Each build's own name stays the one shown in help, messages and documentation. Permission nodes
are accepted under both prefixes, as far players already are.

## Why

86 % of downloads are the VSS build, and its users read LSS documentation and issue replies that
name `/lss`. Seven of 43 issues came from install or brand confusion.

## Consequences

- Wire behaviour, mod id and file names do not change.
- A server that already has another plugin's `/vss` or `/lss` keeps that plugin's command; ours
  stays reachable under the other name.

## Revisit when

A real command collision is reported.
