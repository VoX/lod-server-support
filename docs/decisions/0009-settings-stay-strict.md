# 0009 A settings file with a mistake does not load, and says so loudly

Status: accepted 2026-11-13 (maintainer, after two plan reviews). Replaces the "settings
tolerance" recommendation of the post-0.21.0 review. Evidence:
[0.23.0 plan](../planning/2026-11-13-v0.23.0-plan.md) §1 and §6.

## Decision

An unknown key, a wrong type or broken YAML still fails the whole file, on the server and on the
client, and the mod stays off until the file is fixed. The mod never guesses what a mistake meant
and never runs a default in its place. One spelling is accepted because its meaning is certain:
`true`, `false`, `yes`, `no`, `on` and `off` in any letter case for a boolean key.

What changes is that the mistake cannot be missed: every problem is listed at once with its line
and a suggestion, in the console twice, to admins in chat, at the top of `/lsslod`, and on the
client as the status verdict and a chat line.

## Why

- Nearly every key is a switch or a limit an operator set on purpose: who is visible, who gets
  terrain, x-ray masking, generation, bandwidth, disk. Ignoring a misspelt one runs the default,
  and the defaults are the open values. `far_player: {mode: off}` would turn far players on.
- Failing closed per key does not rescue it: forcing x-ray masking on drops the store twice, and
  forcing the map request cuts off every client older than 0.19.0.
- Running the last file that loaded was rejected: an edit that tightens a setting would silently
  not apply.
- A key from a newer version is a setting this version cannot honour.

What strictness buys is consent: the mod never runs a configuration nobody wrote.

## Consequences

- A downgrade still needs the newer keys deleted. The message says which lines.
- The boolean spellings (`yes`, `on`, …) are a one-way convenience: 0.22 and older refuse them,
  one line per restart. The README's downgrade paragraph says so.
- The hint for an unknown name starts with "check the spelling": "delete this line" alone would
  invite doing by hand what the mod refuses to do.
- A new server key that an older version may ignore is commented out in the template, with its
  default in code, so files created by the newer version still load on the older one.

## Revisit when

Operators keep losing the mod to typos although the message reaches them, or a way is found to
ignore a key that provably cannot change who sees what or what the server spends.
