# 0004 Far players are drawn to their full range by default

Status: accepted 2026-11-13 (VoX). Reverses "off by default" from issue #364 (2026-10-23).

## Decision

`far_players.extend_far_plane` defaults to on when an LOD renderer (Voxy) is present. Without one
it stays off. The client status says how many far players are sent and how many are beyond the
distance the client can draw.

## Why

The server sends far players out to 2,048 blocks by default. On 26.x the game stops drawing at
about 900 blocks unless the extension is on: 44 % of the radius and about a fifth of the area. The
feature looked broken to most players who had it.

## Consequences

- A player who set the option keeps their value. Only the default changes.
- The extension is client-side rendering only. It does not change who is shown.

## Revisit when

A renderer or shader pack is reported to break with the extended far plane (then gate the default
on that case, not on everyone).
