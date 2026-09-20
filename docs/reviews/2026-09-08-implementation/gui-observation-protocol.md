# Minimal release-jar GUI observation protocol

Read-only source/artifact review, 2026-09-08. No runtime, build, input or screenshot was performed. All commands below are exposed in the staged **LSS release jars**, except the separately documented Gradle dummy. Both final jars contain `ClientCommandActions` and `ClientTraceLog`; neither contains any `dev/vox/lss/benchmark/` classes. Setting benchmark/soak properties on a Prism release jar does not install their metric exporters.

## Command reference

Client commands go into the normal Minecraft chat box, with the slash. Console commands go to the owned GUI fixture's live stdin without a slash. These jars use the LSS brand (`/lss`, console `lsslod`), not VSS.

| Location | Exact command | What it exposes / changes |
|---|---|---|
| Client | `/lss diag` | Active connection/manager counters, scan/budget/cache identity, conditional far-player and Xaero lines. Early-returns **`LSS is not active on this server`** if manager absent OR server disabled. Thus it prints no Xaero/far gauges while receive is OFF. |
| Client | `/lss trace` | Toggle ON; feedback **`LSS trace STARTED: <path>`** supplies exact filename. Issue the SAME `/lss trace` again to toggle OFF and flush/close it; feedback says stopped. It is not `/lss trace on/off`. |
| Client | `/lss reset voxy-force` | Read-only storage probe/prompt; shows live and expected Voxy roots where readable. It may arm a60s force grant but **deletes nothing**. Do not append confirm for this observation. |
| Client | `/lss reset` | Ordinary combined reset. With an active LSS manager, drains/retire work, resets Voxy, then clears current-server LSS proof and re-streams. Follow actual outcome text. With no active manager it only prompts for a broader all-server reset: that is outside the active-session gate; do not confirm it. |
| Fixture console | `list` | Actual connected player names. Match the sole observer name to its row below; do not infer it from a profile directory name. |
| Fixture console | `lsslod stats` | Per-player `NAME: handshake=yes/no, sent=N sections (...), pending_sync=N, pending_gen=N, send_queue=N, requests=N`. **requests is the sum of received batch position counts**, not number of packets. |
| Fixture console | `lsslod diag` | Global/pool diagnostics, per-player queues, conditional far-player counters. Not a per-player complete wanted-position list. |
| Fixture console | `lsslod set` | Lists all effective runtime settable settings, including farPlayers/enabled. There is no need to guess a `get farPlayers` form. |
| Fixture console | `lsslod set farPlayers off` / `lsslod set farPlayers on` | Apply and persist the server far-player mode. Capture its reply, including any persistence-failure suffix. |
| Fixture console | `lsslod store status` | Live SQLite store/backfill status for initialization corroboration. Not Voxy's client-side storage path. |

Release trace files are beneath the selected clone's `minecraft/logs/lss-trace-YYYYMMDD-HHmmss.jsonl`. The path printed by the command is authoritative. Trace rows use `t` (event name) and `ms` (milliseconds since this trace started); they do not carry an absolute event timestamp. Keep a wall-clock timestamp beside each key action/capture. Stop explicitly before copying a completed trace; disconnect stops it automatically. Start a NEW trace after reconnect. Limit64Mi characters; writes normally buffer up to200ms. Avoid toggling twice within the same second and accidentally reusing the second-resolution filename.

## Receive OFF / ON: repeat this short gate on all four Sodium clones

Use fixture A (`localhost:25566`) with **only the observer connected**; do the far-player dummy separately. Clone IDs:

- `lss-astra-validation-fabric-1.21.1` — modern Sodium0.8.13-beta.2.
- `lss-astra-validation-neoforge-1.21.1` — modern Sodium0.8.12-beta.1 + Connector Voxy0.2.15.
- `lss-astra-validation-fabric-legacy-1.21.1` — Sodium0.6.13, no Voxy.
- `lss-astra-validation-neoforge-legacy-1.21.1` — Sodium0.6.13 + native Voxy0.2.9.

1. Capture loaded MC/loader/mod versions, selected clone and candidate hash. Connect, open the **actual Sodium LSS options page**, capture the receive option ON, and save an intentional change when testing the page's save hook. Run `/lss trace`, `/lss diag`; fixture `list`, `lsslod set`, `lsslod stats`. Establish actual nonempty acquisition using the observer's increasing server `requests`, client `Requests: total_requested`, or `scan` trace rows with `declared>0`. A fully converged warm idle run is insufficient as an OFF premise; use a newly staged clone/world or move within the disposable fixture to expose pending work.
2. Through the Sodium page set receive OFF and **Save/Apply**. Record the exact completion time. Return to game; `/lss diag` must report inactive. With the server still enabled and connection still listed, this corroborates acquisition-manager retirement. The message alone cannot distinguish absent manager from server-side disable, so retain the paired server facts.
3. After returning to game and allowing queued inbound events to settle, take console `lsslod stats` samples at approximately+2s,+5s,+10s. The observer's `requests` must remain unchanged over this settled interval. A late bounded response/section may finish; `sent` need not freeze at the same instant the menu saves. Empty withdrawal batches add zero to `requests`; a preserved row is expected because turning receive off does not disconnect the player or erase its privacy state. Do not claim zero received bytes globally from this sample.
4. Set receive ON and Save without reconnecting. `/lss diag` must return active diagnostics; the client log normally records a new `Server session config received ...` line. On work that remains needed, observer `requests` and responses must resume and useful Xaero/Voxy acquisition must progress. Counters attached to a newly created manager can restart; compare within each phase, not across manager identities. Stop `/lss trace`, preserving the printed file and phase timestamps.
5. Complementary path: save receive OFF, disconnect, then rejoin while OFF. Capture inactive `/lss diag` and the server's observer state; a fresh session may have **no LSS player row** until enabled. Turn ON and Save, keeping the same connection; require fresh negotiation, active diagnostics and actual progress. Start trace after this OFF join if needed; it must be restarted after every disconnect.

Evidence limits: `scan.declared>0` is logged **before** batch send; `clear_batch` is logged **before** the empty withdrawal attempt; client send-cycle/total-request counters count attempts. They are not wire acknowledgments. An OFF interval may contain multiple clear_batch events if sends fail: those are the designed retry attempts. The live server's requests total establishes received nonempty positions; there is no release command printing the complete want-set or an explicit manager ID/retirement generation. Trace net/scan rows stop while no manager ticks, so an empty trace interval alone is not proof that the game stayed alive. Capture inactive diag plus server samples. Do not induce network failure just to claim failed-withdraw retry correctness; exact failed-send/retry ownership is covered by deterministic tests, not this ordinary GUI smoke.

## Xaero: what `/lss diag` can actually prove

The release `XaeroMap:` line contains:
`state, queued, written, skipped_native, defer_events, dropped, dropped_overflow, dropped_stale, dropped_expired, commit_failures, load_requests, regions_waiting, buffer_updates, frame_flushes, rebuild_ms, rebuild_max_us, pending_updates, dropped_updates, dropped_unloaded, skipped_settings, cave_layer_waits, drops_reported, owed, owed_regions, owed_reported, owed_evicted, bp`, plus conditional crash/broken-settings/unbound flags.

Capture the complete line before OFF/disconnect, then after ON or reconnection to A/B, and again after ordinary quiet settling. `queued`, `pending_updates`, `owed` and `owed_regions` are current gauges; `written` and the reported/drop/rebuild values are counters, not uniformly per-session counters. Require nonnegative gauges, actual written/map progress and useful settling where native Xaero permits it. Positive owed/pending during legitimate region readiness waits is not automatically a leak. `bp=-1(inactive|wedged|stale)` and `(blocked)` suffixes are explicit state attributions; `state=active` principally reflects the configured bridge flag, not a generation ID.

**Bridge acquisition generation, receipt-origin identity and old/new-session report ownership are not printed in diag or the release trace.** `/lss diag` cannot inspect the title screen or receive-OFF debt because of its early return. Thus ordinary reconnect + map progress supports integration, but cannot certify the exact paused-worker cross-session race or claim an observed generation increment. Preserve those claims as deterministic-test evidence. No new production diagnostic is needed for this validation.

## Voxy roots and reset: NeoForge clones only

While connected and active, run `/lss reset voxy-force` **without confirm**. Save both printed lines: `Voxy's live storage root: ...` and `LSS's expected root: ...`, and the verdict/prompt. The first-stage sentence “Nothing has been deleted yet” is truthful; it only probes/arms. `/lss diag` prints the LSS cache identity, **not Voxy's actual path**. A root is not validated merely because the profile has no copied cache: require the live/expected paths to remain inside the selected disposable clone's game directory and the actual loaded backend to match the prepared native/Connector pairing. If unavailable, outside its fence, overridden unexpectedly, or unresolvable, stop the reset gate as inconclusive/refused; do not force-confirm or manually delete.

For matching contained roots with active LSS, use ordinary `/lss reset`. Outcome text distinguishes:

- `Voxy LODs cleared (disk + memory).` — reset completed.
- `Voxy engine reset (memory cleared) — the disk wipe was ...` — wipe skipped; following detail gives roots/reason. Not a disk-clear pass.
- `Voxy disk cache cleared (Voxy not running).` — no active-instance restart proof.
- unavailable / incomplete / failed-to-restart — not a Voxy live reset pass, even if LSS cache clearing proceeded.

Capture outcome, active diagnostics, renewed requests/receipts, then visible terrain reconstruction. This is the ordinary WI12 gate; it does not inject seated rendering exceptions or test the two-stage forced override. Fabric's no-Voxy rows should be recorded N/A for Voxy, not failures or passes.

## Far-player console gate: one ordinary observer + separate stationary dummy

Finish the receive gate first, with observer receive ON. Start the separately owned dummy only in its assigned runtime slot. Establish visible far proxy beyond vanilla tracking range on valid terrain, with both players stationary. Capture `/lss diag`: client `FarPlayers: tracked, epoch, rosters, updates, entries, dropped_epoch` and the renderer line. Console `lsslod diag` exposes `FarPlayers: subs, rosters, updates, entries, suppressed, bytes` once subscribers/counters exist; it does not replace `lsslod set` for the configured mode.

Run `lsslod set farPlayers off`; capture reply, `lsslod set`, client diag and disappearance. Then `lsslod set farPlayers on`; capture mode reply and proxy reappearance without movement/reconnect, plus roster/renderer evidence. Server subscriber count may survive OFF, and counters are cumulative, so neither subs=0 nor counter reset is required. End with the dummy's ordinary console kick and await its own exit. A task exiting0 or a setting saved successfully does not prove the graphical observation.

## Evidence extraction (read-only, after captures)

For each clone, retain its own `minecraft/logs/latest.log`, completed trace and captures under a distinct phase directory. Search exact feedback/diagnostic lines without collecting launcher authentication arguments:

```bash
rg -n 'LSS.*(trace|not active)|Server session config received|Requests:|Responses:|XaeroMap:|FarPlayers:|Voxy.*(root|cleared|reset|restart)|expected root|Failed to send (want-set|backpressure)' "$CLONE/minecraft/logs/latest.log"
```

Where client feedback is absent from the log, the captured chat is the evidence; do not infer it was emitted. Preserve fixture console output for the named observer's `lsslod stats` samples. To summarize a completed trace with Python:

```bash
python3 - "$TRACE" <<'PY'
import collections,json,sys
rows=[json.loads(s) for s in open(sys.argv[1]) if s.strip()]
print('events',dict(collections.Counter(r['t'] for r in rows)))
print('nonempty_scan_ms',[r['ms'] for r in rows if r['t']=='scan' and r.get('declared',0)>0])
print('clear_attempt_ms',[r['ms'] for r in rows if r['t']=='clear_batch'])
PY
```

Record actual wall-time phase boundaries separately. The protocol produces ordinary live integration evidence for each page/loader pairing; precise concurrency ownership and injected-failure claims remain tied to their already-run deterministic tests.

Source anchors: `ClientCommandActions.java:161` (diag), `ClientTraceLog.java:79` (toggle), `LodRequestManager.java:538/735/797` (trace-before-send/attempt semantics), `AbstractPlayerRequestState.java:273` (server request-position total), `DiagnosticsFormatter.java:398` (stats schema), `XaeroMapCompat.java:1590` (bridge line), `VoxyStorageOverride.java:190` (read-only prompt), `ResetCoordinator.java:218` (outcomes), `LSSServerCommands.java:35` (set tree).
