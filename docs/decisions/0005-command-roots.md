# 0005 The Voxy Server Side client also answers to `/lss`

Status: accepted 2026-11-13 (VoX approved "both builds accept both names"); narrowed to one
direction the same day after the 0.22.0 plan review, and to the client command after the 0.23.0
plan review. Built in 0.23.0 ([plan](../planning/2026-11-13-v0.23.0-plan.md) §4, §6).

## Decision

The VSS client registers `/lss` beside its own `/vss`. Its own name stays the one shown in help
and messages. The extra name steps aside when the server has a command called `lss`. The LSS
build is unchanged: it does not gain `/vss`.

The VSS server commands keep one name, `/vsslod`, and one admin node, `vss.admin`.

## Why

86 % of downloads are the VSS build, and players are told to type `/lss status` by the README,
the Modrinth page and issue replies. Seven of 43 issues came from install or brand confusion. With
the VSS client answering to `/lss`, every player-facing document can say `/lss`.

The server half was not built. It needs a rewrite of the Paper `plugin.yml`, a weaker brand check
in the release tool and Sponge wiring, and accepting `lss.admin` has a hole: the node would be
undeclared on the VSS build, Bukkit gives an undeclared node to operators, and an operator whose
`vss.admin` was taken away would get the command back. Operators read a console and can translate
a name; players often cannot. The other direction (`/vss` on LSS) helps nobody.

## Consequences

- Wire behaviour, mod id and file names do not change.
- A server command called `lss` (another plugin's) keeps working for VSS players.

## Revisit when

Operators are found typing `/lsslod` on VSS servers, a real command collision is reported, or LSS
users are found following instructions that say `/vss`.
