# 0004 Far players are drawn to their full range by default

Status: accepted 2026-11-13 (VoX). Reverses "off by default" from issue #364 (2026-10-23).
Corrected the same day: the first version of this record blamed the far plane on 26.x.

## Decision

`far_players.extend_far_plane` defaults to on and takes effect while an LOD renderer (Voxy) is a
consumer. Without one it does nothing. The client status says how many far players the server
sends.

## Why

The server sends far players out to 2,048 blocks by default. What stops them being drawn differs
by version:

- **1.21.1:** the vanilla far plane is 768 blocks at render distance 12. The extension is what
  lets far players past it be drawn, and it was off by default.
- **26.x and 1.21.11:** the plane is already 2,048 by default. Far players fade out past about 900
  blocks on 26.x because of the game's atmospheric fog, which Voxy's default fog mode keeps. The
  extension does not change that.

## Consequences

- Off was the default and was never stored, so every player moves to on. A player who wants it off
  sets it and that is stored.
- The extension is a grow-only latch: the frame is vanilla until a far player comes within 32
  blocks of the plane (room for the model and the name tag). It does nothing while a shader pack
  is on.
- Downgrades: a player who turns it off gets `false` written to the experimental settings file.
  0.19 and older do not know the key and ignore that whole file (the Distant Horizons settings fall
  back to their defaults, with a warning). A player who had turned it on under 0.20 or 0.21 loses
  the stored `true` at the next save, so going back to 0.21 turns it off.
- It is client-side rendering only. It does not change who is shown.
- The "LOD renderer is a consumer" condition is new with the default (0.22.0). Under 0.20 and 0.21
  an explicit `true` moved the plane with no such condition. Where distant terrain is drawn by
  something this mod does not feed (the Distant Horizons client mod, or a Voxy this mod could not
  connect to), a player who had switched the option on no longer gets the extension. Found by the
  regression review of 2026-11-13 and left as it is: far players beside terrain this mod does not
  feed were never a supported combination, and a stored `true` cannot be told from the default
  after the next save. Revisit with a three-way setting if a player reports it.

## Fog stays the player's choice

On 26.x the fog is the real limit: far players fade out past about 900 blocks with the LOD mod's
fog on (Voxy's default). Exempting far players from fog was put to VoX, who decided on 2026-11-13
to leave it as it is: fog is the player's setting, and the option's tooltip and the README say to
turn the LOD mod's fog off. Not to be re-proposed without a new reason.

## Revisit when

A renderer or shader pack is reported to break with the extended far plane (then gate the default
on that case, not on everyone).
