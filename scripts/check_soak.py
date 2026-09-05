#!/usr/bin/env python3
"""Soak-harness invariant checker (stdlib only). Authoritative contract:
docs/planning/soak-test-design.md

Laws (evaluated as deltas between consecutive VERIFIED-QUIESCENT snapshots within
same-client-run, same-dimension windows; dimension/join boundaries get anomaly checks only):
  A1 requests:    d(client.requested_total) == d(responses.columns+up_to_date+not_generated) + d(server.service.duplicate_skips) + d(server.service.superseded) + d(server.service.range_filtered)
                  [v17 want-set: requested_total counts every DECLARED entry, re-declares included]
  A2 delivery:    d(server.service.columns_sent) == d(client.received_columns); d(server.service.bytes_sent) == d(client.received_bytes); d(client.dropped) == 0
  A3 sources:     d(service.columns_sent) <= d(service.in_memory + disk.successful + generation.completed)  [sanity bound, not exact]
  A4 generation:  d(generation.submitted) == d(generation.completed + generation.timeouts + generation.removed_in_flight)
  A5 disk triage: d(disk.not_found) == d(generation.submitted) + d(client.responses.not_generated)  [only when d(generation.timeouts)==0];
                  d(disk.successful) == d(disk.completed - not_found - all_air - errors - saturated)
  A6 monotonic:   server cumulative whitelist (disk totals, generation totals, service.*, bandwidth.total_bytes) over process lifetime;
                  client cumulative counters within one run AND one dimension segment only; server per-player rows are never checked
  A7 anomalies:   disk.errors / generation.timeouts / client.dropped > 0 always fail; disk.saturated fails unless the scenario opts in
  B2 pacing:      d(bandwidth.total_bytes) <= bytesPerSecondLimitGlobal * dt * 1.3 over every consecutive
                  server snapshot pair — armed only when the scenario config sets the global cap

Vacuous-pass guards: every scenario declares per-(run, dimension-segment) floors on the number
of client-laws windows actually evaluated (MIN_CLIENT_WINDOWS) — a run where A1/A2/A5 never
fired fails loudly instead of passing on zero evidence. --validate additionally rejects scenario
config overrides whose keys are not real lss-server-config.json fields (GSON silently ignores
typos, which would de-fang a scenario's whole premise).

Quiescence predicate: across >=2 consecutive server snapshots in the same join segment,
service.requests_received, service.columns_sent, disk.submitted, generation.submitted and
generation.completed are unchanged AND (at both endpoints) every players[] row has
held_sync == held_gen == send_queue == backlog == 0, disk.pending == 0, generation.active == 0,
dirty.pending == 0; joined to the nearest-in-wallMs client snapshot of the matching run
(<= 3 s skew) which must have tracker_in_flight == 0 and queued == 0.

v17 want-set note: service.requests_received going still IS convergence — a converged client
sends NOTHING (no heartbeat batch), so the stillness of that counter is load-bearing for every
law below. players[].backlog is the v17 addition: a retained (slot-gated) want entry is real
outstanding work with no other gauge, and it must drain before a window may open.
tracker_in_flight keeps its meaning (declared-and-unanswered — the client's awaiting-answer
set) and queued is still the client decode-queue depth; neither field changed shape.

Modes:
  check_soak.py --validate <scenario-name>      pre-flight scenario/config/registry validation
  check_soak.py <results-dir> <scenario-name>   evaluate laws + named checks, write verdict.json
  check_soak.py --selftest                      in-memory pass/catch self-test of every law
                                                (A1-A7, B2), quiescence, disc completeness,
                                                window floors, and all named checks
"""

import argparse
import json
import sys
from dataclasses import dataclass, field
from pathlib import Path

SCENARIO_DIR = Path(__file__).resolve().parent / "soak-scenarios"
SKEW_MS = 3000  # max |server.wallMs - client.wallMs| for a quiescence join
DEFAULT_DIRTY_BROADCAST_SECONDS = 5  # used when the scenario -config.json omits the key

# Scenarios with more than one client run (kick → rejoin); everything else has exactly 1.
EXPECTED_RUNS = {"warm-rejoin": 2, "dirty-while-offline": 2, "dimension-rejoin-warm": 2,
                 "warm-rejoin-summary": 2, "dirty-while-offline-summary": 2,
                 "stamp-heal-prime": 2}

# A7 opt-ins. Historically saturated/rate_limited were ONE opt-in pair (disk saturation
# surfaced to the client as rate_limited BY DESIGN). v17 retires the rate-limited response
# from the wire, so the pair is SPLIT and only "saturated" survives — deliberately kept
# rather than deleted with its twin: disk.saturated is still a real counter, and v17's
# hasHeadroom() gate should now hold it at 0, which makes an A7 hit here a STRONGER signal
# than before (the gate leaked), not a vacuous one. errors / timeouts / dropped can never
# be opted out.
ANOMALY_OPT_INS = {
    "fresh-backfill": frozenset({"saturated"}),
    "hybrid-boundary": frozenset({"saturated"}),  # same fresh-fill mechanics at 3x the radius (~8.6x the AREA: 145^2 vs 49^2 columns)
    "warm-rejoin": frozenset({"saturated"}),
    "dimension-trip": frozenset({"saturated"}),
    "dirty-broadcast": frozenset(),
    "rate-limit-storm": frozenset({"saturated"}),
    "disk-saturation": frozenset({"saturated"}),
    "generation-disabled": frozenset({"saturated"}),
    "generation-capacity-stress": frozenset({"saturated"}),
    "bandwidth-throttle": frozenset({"saturated"}),
    "cold-restart-resync": frozenset({"saturated"}),
    "enabled-false": frozenset(),
    "teleport-prune": frozenset({"saturated"}),
    "dirty-range-filter": frozenset({"saturated"}),
    "dirty-during-backfill": frozenset({"saturated"}),
    "dirty-while-offline": frozenset({"saturated"}),
    # Region summaries (plan §8): warm-rejoin-shaped loads with NO opt-ins (final
    # harness review): the clearcache re-serve is a superflat re-read wave the
    # headroom gate absorbs (the recorded greens all measured saturated == 0), and a
    # freshness-rung branch that leaked saturation would be exactly the regression
    # these scenarios exist to catch — keep A7 sharp here.
    "warm-rejoin-summary": frozenset(),
    "dirty-while-offline-summary": frozenset(),
    "evicted-tscache-rejoin": frozenset(),
    "stamp-heal-prime": frozenset(),
    "stamp-heal-rejoin": frozenset(),
    "clearcache-mid-session": frozenset({"saturated"}),
    "store-second-join": frozenset({"saturated"}),
    "store-offline-populate": frozenset({"saturated"}),
    "store-offline-mutate": frozenset(),
    "store-offline-verify": frozenset({"saturated"}),
    # C6 store-migration variant: a carried v0.9.x-downgraded store, warm 19-row serves
    # through the inverse translator while the background walk migrates — same
    # load-shaped opt-in as the other store rejoin legs.
    "store-migration-join": frozenset({"saturated"}),
    "store-save-storm": frozenset({"saturated"}),
    "store-save-storm-off": frozenset({"saturated"}),
    "dimension-rejoin-warm": frozenset({"saturated"}),
    # The gate's own scenario (Amendment 2 retention): gate_stops>0 is its PREMISE
    # (K=1 under a threads:2 flood must saturate and stop router passes), and gated
    # (park-overflow race armor) may legitimately fire small-or-zero during the same
    # flood, so BOTH are opted in; saturated stays UN-opted — its named check requires 0.
    "disk-read-gate": frozenset({"gated", "gate_stops"}),
    # Paper/Folia (SOAK_PLATFORM=paper|folia): cold-cache disc resync from the base world, like
    # warm-rejoin run 1, so the same load-shaped opt-ins apply.
    "paper-dirty-falling-block": frozenset({"saturated"}),
    "paper-store-unfired-event": frozenset({"saturated"}),
}

# Vacuous-pass floors: minimum number of client-laws windows (the quiescent pairs where
# A1/A2/A5 actually evaluated) per (client run, dimension segment). Calibrated against
# recorded green runs at roughly one third of observed counts: fresh-backfill 31, warm-rejoin
# 27/17, dimension-trip 6/22/16, dirty-broadcast 16. New scenarios get a conservative 3
# (their converged tails run 40+ s at 5 s snapshot cadence).
# Scenarios allowed to finish with zero nonzero-delta client-law windows (no LSS traffic).
TRAFFIC_FLOOR_EXEMPT = frozenset({"enabled-false", "store-offline-mutate"})

MIN_CLIENT_WINDOWS = {
    "fresh-backfill": {(1, 0): 10},
    # hybrid-boundary: a 30-min run whose lod-72 fill measures ~1,100-1,730 s at the
    # live-observed 12-19 gen/s — the tail holds >= 10 settled windows either way.
    "hybrid-boundary": {(1, 0): 10},
    "warm-rejoin": {(1, 0): 8, (2, 0): 5},
    "dimension-trip": {(1, 0): 2, (1, 1): 6, (1, 2): 4},
    "dirty-broadcast": {(1, 0): 5},
    "rate-limit-storm": {(1, 0): 3},
    "disk-saturation": {(1, 0): 3},
    # K=1 on degraded WSL2 IO can spend ~4-5 min converging the annulus (healthy IO:
    # seconds, since the park list keeps the permit fed); the floor only needs the
    # >=25 s converged tail (>=4 quiescent 5 s pairs) the 400 s timeline budgets.
    "disk-read-gate": {(1, 0): 4},
    "generation-disabled": {(1, 0): 3},
    "generation-capacity-stress": {(1, 0): 3},
    "bandwidth-throttle": {(1, 0): 3},
    "cold-restart-resync": {(1, 0): 5},
    "enabled-false": {(1, 0): 3},
    "teleport-prune": {(1, 0): 8},
    "dirty-range-filter": {(1, 0): 8},
    "dirty-during-backfill": {(1, 0): 5},
    "dirty-while-offline": {(1, 0): 3, (2, 0): 3},
    # The 160s clearcache splits run 1 into pre/post-action segments; the post
    # segment runs ~50 s converged (clearcache at t160, kick at t210), so its floor
    # is 4 like the other converged tails (final harness review — 2 was calibrated
    # against the earlier 150s/25s-tail timeline and passed vacuously).
    "warm-rejoin-summary": {(1, 0): 4, (1, 1): 4, (2, 0): 3},
    "dirty-while-offline-summary": {(1, 0): 4, (1, 1): 4, (2, 0): 3},
    "evicted-tscache-rejoin": {(1, 0): 3},
    "stamp-heal-prime": {(1, 0): 4, (2, 0): 3},
    "stamp-heal-rejoin": {(1, 0): 3},
    # clearcache splits run 1 into pre/post-action segments (the flushCache counter reset
    # is a segment boundary, like a dimension change)
    "clearcache-mid-session": {(1, 0): 4, (1, 1): 4},
    "store-second-join": {(1, 0): 4, (1, 1): 4},
    "store-offline-populate": {(1, 0): 4},
    "store-offline-mutate": {(1, 0): 3},
    "store-offline-verify": {(1, 0): 4},
    "store-migration-join": {(1, 0): 4},
    "store-save-storm": {(1, 0): 4, (1, 1): 4},
    "store-save-storm-off": {(1, 0): 4},
    "dimension-rejoin-warm": {(1, 0): 2, (1, 1): 5, (2, 0): 3, (2, 1): 3},
    "paper-dirty-falling-block": {(1, 0): 3},
    "paper-store-unfired-event": {(1, 0): 4, (1, 1): 4},
}

# The exclusion circle the client scanner never requests inside: min(client render distance,
# server view-distance), both pinned to 8 by soak.sh (server.properties view-distance=8,
# options.txt renderDistance:8). The scanned annulus is Chebyshev rings 9..lodDistanceChunks.
EXCLUSION_RADIUS = 8

# Every legal key of lss-server-config.json (ServerConfigBase fields — the Fabric soak server
# reads exactly these). GSON silently ignores unknown keys, so a typo in a scenario's
# -config.json would silently fall back to defaults and de-fang the scenario; --validate
# rejects unknown keys and wrong JSON types instead.
SERVER_CONFIG_BOOL_KEYS = frozenset({"enabled", "enableChunkGeneration", "useBackgroundReadPriority",
                                     # NBT->wire transcode kill switch (round 2, 2026-07-29):
                                     # scenarios may pin it off for object-path A/Bs.
                                     "useNbtTranscode",
                                     # The ping backstop's kill switch (Mechanism B) and the
                                     # send pacer's (send-pacing-plan.md) — both structurally
                                     # inert on loopback; listed so live-shaped A/B scenarios
                                     # can pin them (the S-8 same-commit rule; enablePingBackstop
                                     # was briefly MISFILED in the int set — a bool here would
                                     # have failed --validate as "must be a JSON integer").
                                     "enablePingBackstop", "enableSendPacing",
                                     # LOD-store backfill opt-in (Phase 4) — the key was
                                     # missing from this allowlist, so a backfill soak
                                     # scenario could not be written (4-agent round R4).
                                     "lodStoreBackfill",
                                     # Compressed-columns kill switch (protocol 19):
                                     # scenarios pin it off for the raw-path A/B arm.
                                     "useCompressedColumns",
                                     # Legacy-client shim toggle. Absent from this list
                                     # since it was introduced, so no scenario could
                                     # ever pin it — the same R4 hole as lodStoreBackfill
                                     # (v0.9.0 review).
                                     "enableV16Compat",
                                     # v18 compat rung kill switch (v0.9.1) — listed at
                                     # introduction so an A/B scenario CAN pin it, unlike
                                     # the two R4 holes above.
                                     "enableV18Compat",
                                     # v19 compat rung kill switch (v0.10.0 C1, protocol
                                     # 20) — listed at introduction per the same-commit
                                     # allowlist rule so a dialect A/B scenario CAN pin it.
                                     "enableV19Compat",
                                     # Transport yield (v0.10.0 A2, default false): the
                                     # writability gate is provably inert on loopback, so
                                     # an armed soak is expected-identical — the S-8
                                     # same-commit allowlist rule (the twice-shipped
                                     # R4-class defect).
                                     "lodYieldsToVanillaTransport",
                                     # Via cross-MC mismatch guard (v0.10.0 C5, XVER §7)
                                     # — listed at introduction per the same-commit
                                     # allowlist rule. Soaks run without Via, so the
                                     # probe is no-signal and either value is provably
                                     # inert (an A/B pins exactly that).
                                     "enableViaMismatchGuard",
                                     # Far players (E1, FARP §3.4) — registered with the
                                     # knobs (the R4 lesson). E1 soaks stay mode-off;
                                     # the E2/E3 coexist scenarios arm these.
                                     "farPlayersSendSpectators",
                                     # Region summaries (region-summary-sync-plan.md §9)
                                     # — listed at introduction per the same-commit
                                     # allowlist rule so the summary scenarios and their
                                     # kill-switch A/B arm can pin it.
                                     "enableRegionSummaries"})
SERVER_CONFIG_INT_KEYS = frozenset({
    "lodDistanceChunks", "bytesPerSecondLimitPerPlayer", "diskReaderThreads",
    # Disk-read concurrency gate K (disk-read-concurrency-gate-plan.md; 0 = AUTO,
    # store-conditional). Registered WITH the knob (the R4 lesson): every pre-existing
    # scenario pins it to a no-op (its diskReaderThreads value / the resolved default
    # pool) so their law baselines stay gate-free; disk-read-gate arms it. Precision
    # note (stage-B review ACC-5): store-offline-mutate's pin of 3 equals the FABRIC
    # vanilla AUTO pool; a >=8-core Paper standalone run of that phase resolves a
    # larger prioritized pool, where 3 would nominally bind — inert there
    # (enabled=false, no read traffic) and the A7 gated/gate_stops arms flag any leak
    # (since Amendment 2 the gate_stops arm fires FIRST — saturation binds before
    # overflow — so arming that phase, or copying its K pin into a new scenario on a
    # box whose AUTO pool exceeds it, needs the opt-in or a K=pool pin).
    "maxConcurrentDiskReads",
    "sendQueueLimitPerPlayer", "bytesPerSecondLimitGlobal",
    # Canonical bandwidth spellings since the 2026-08-08 key rename (staleness-sweep
    # finding: only the legacy byte spellings were listed, so a scenario written with
    # the modern keys would be rejected — the R4 lesson again). Values are MiB/s
    # doubles in the mod; scenarios may still write ints (validated as numeric below
    # via the int allowlist — a fractional override belongs in a new float set if ever
    # needed).
    "mbPerSecondLimitPerPlayer", "mbPerSecondLimitGlobal",
    "generationConcurrencyLimitGlobal", "generationTimeoutSeconds",
    "dirtyBroadcastIntervalSeconds",
    "generationConcurrencyLimitPerPlayer", "perDimensionTimestampCacheSizeMB",
    # Miss-memo TTL (0 = off): scenarios may pin the memo off for A/B of the read-churn
    # dynamics. NOTE ttl=0 restores pre-memo READ CHURN only, not pre-memo ORDERING —
    # the generation pacing rules are ttl-independent (unified 2026-07-19), so an
    # inversion A/B against a pre-memo recording is no longer apples-to-apples.
    "missMemoTtlSeconds",
    # X-ray masking cutoff (docs/planning/antixray-compat-design.md §3).
    "xrayMaxBlockHeight",
    # LOD-store periodic freshness re-sweep (Paper's stale bound; 0 = off).
    "lodStoreResweepSeconds",
    # LOD-store on-disk size cap (Phase 5 eviction).
    "lodStoreMaxMB",
    # LOD-store backfill pace (store-backfill-tuning-plan.md) — added with the knob itself
    # (the R4 lesson: a key missing from this allowlist means no scenario can ever set it).
    # lodStoreBackfillTickCeilingMillis was retired to a constant 2026-08-02: its clamp band
    # was 20..50 and both ends were degenerate by its own documentation.
    "lodStoreBackfillColumnsPerSecond",
    # Far players (E1): cadence + the distance ring (FARP §3.4).
    "farPlayersUpdateIntervalTicks", "farPlayersMaxDistanceBlocks",
    "farPlayersMinDistanceBlocks",
})
# X-ray masking tri-state ("auto"/"on"/"off"), the LOD-store switch ("off"/"full" —
# scenarios A/B store gates against it; "memory" retired 2026-08-02), + hidden-block
# id list — the only
# non-bool non-int server config keys; validated loosely (any string / list of strings).
SERVER_CONFIG_STRING_KEYS = frozenset({"xrayObfuscation", "lodStore",
                                       # Far players mode ("off"/"on"/"opt-in", E1).
                                       "farPlayers"})
# updateEvents is Paper-only (the Bukkit event class names driving dirty detection);
# it was absent here, so no Paper scenario could pin its dirty-detection surface.
SERVER_CONFIG_STRING_LIST_KEYS = frozenset({"xrayHiddenBlocks", "updateEvents",
                                            # Far players per-name/UUID privacy list (E1).
                                            "farPlayersExclude"})
SERVER_CONFIG_KEYS = (SERVER_CONFIG_BOOL_KEYS | SERVER_CONFIG_INT_KEYS
                      | SERVER_CONFIG_STRING_KEYS | SERVER_CONFIG_STRING_LIST_KEYS)

# Headroom for B2: per-tick allocation jitter across a 5 s wall window (ticks can lag and
# repay within the same wall budget, never sustainably exceed it).
B2_HEADROOM = 1.3

# Quiescence: counters that must be UNCHANGED across the consecutive server pair.
SERVER_MOVING = (
    "service.requests_received",
    "service.columns_sent",
    "disk.submitted",
    "generation.submitted",
    "generation.completed",
)
# Quiescence: gauges that must be ZERO at both endpoints of the pair.
# store.queue (the LOD-store write-batcher depth) is a strict drain: deposits only happen
# on serves, so a quiescent server has nothing left to batch and the queue must be empty.
# THE BATCHER CONTRACT THIS GATE IMPOSES (Phase 2 must be designed to it, or every
# store-on soak reds on law-coverage — Phase 0 correctness-review F1):
#   1. IDLE FLUSH: a "<64-row txn" batcher must also flush on a timer well inside the 5 s
#      snapshot cadence — a residual sub-batch tail held until the next serve would keep
#      store.queue nonzero at EVERY candidate pair and zero out the quiescent windows.
#   2. DRAIN-SIDE GAUGE: setQueueDepth must be updated when the batcher DRAINS, not only
#      on enqueue (a stale nonzero gauge has the same window-killing effect).
#   3. OFF-SERVE PRODUCERS STAY OFF THIS GAUGE: the Paper periodic re-sweep (autosave
#      cadence — 5 s on the Folia soak staging!) and other timer-driven store ops must
#      not ride store.queue; give them their own gauge OUTSIDE SERVER_DRAINS (cf.
#      dirty.pending's tolerance for exactly this shape).
SERVER_DRAINS = ("disk.pending", "generation.active", "dirty.pending", "store.queue")
PLAYER_DRAINS = ("held_sync", "held_gen", "send_queue", "backlog")
# backlog (v17) is a strict drain: a want entry retained by a full slot / exhausted disk pool is
# real outstanding work that no other gauge reports (held_sync/held_gen count ADMITTED work only).
# A nonzero backlog at either endpoint means the router still owes the client answers, so the
# window must not open. It reaches 0 both by draining and by the next replace superseding it —
# either way the client has stopped re-declaring, which is what convergence means.
# dirty.pending tolerates a small benign light-settle trickle in the quiescence predicate: loaded
# chunks re-light and re-mark dirty across save cycles (esp. after a dimension re-load), so it
# oscillates 0-N and rarely sits exactly at 0 — the same drift the dirty-resave check tolerates.
# disk.pending / generation.active stay strict (real work in flight). A broadcast storm / reload
# loop pushes dirty.pending far past this, and a real backlog also keeps the per-player send_queue
# (a strict PLAYER_DRAIN) nonzero, so genuine non-quiescence is still caught.
QUIESCENCE_DIRTY_PENDING_TOLERANCE = 8

# A6 whitelist — ONLY these are required to be monotonic. Gauges (disk.pending,
# generation.active, dirty.pending, players[].*) are deliberately absent.
SERVER_MONOTONIC = (
    "service.requests_received", "service.columns_sent", "service.bytes_sent",
    "service.duplicate_skips", "service.queue_full", "service.up_to_date",
    "service.in_memory", "service.disk_resolved", "service.gen_drained",
    # v17 want-set dispositions (replaced sync_rate_limited/gen_rate_limited, which left the
    # wire with RESPONSE_RATE_LIMITED). Both are load-bearing terms of law A1: superseded =
    # received-then-silently-dropped (mailbox overwrite, backlog replace, residual saturation
    # drop, dedup-primary departure), healed by the client's 1 Hz re-declaration;
    # range_filtered = dropped by the Chebyshev ingress guard (the movement race).
    "service.superseded", "service.range_filtered",
    # Send-pacer receipt (budget-stopped partial flush ticks) — attribution-only, but a
    # counter, so monotonicity is free armor (final-review consistency note).
    "service.paced_ticks",
    # Compressed columns (protocol 19, compressed-columns-implementation-plan.md §4):
    # wire_bytes = SHIPPED payload volume (zstd frames for capable sessions) — the
    # observed-bandwidth match next to the raw-denominated bytes_sent (law A2 stays
    # raw==raw); cols_zstd/cols_raw = per-payload codec outcomes at build. Monotonic
    # counters; no law consumes them yet — the compress-gate harness reads them.
    "service.wire_bytes", "service.cols_zstd", "service.cols_raw",
    # Far players (E1, FARP §3.2): a dedicated send lane with its OWN counters —
    # deliberately NOT part of service.bytes_sent/wire_bytes (the cross-identity
    # audits). Monotonic; the E1 baseline-neutrality check additionally requires them
    # to stay ZERO on every soak run (the client property gate keeps harness clients
    # unsubscribed — a nonzero here means the gate broke and the baselines shifted).
    "far_players.roster_frames", "far_players.update_frames",
    "far_players.entries", "far_players.suppressed", "far_players.bytes",
    # Server-owned generation: disk misses resolved into transient silent drops (law A5's
    # dedicated term — a subset of superseded events, counted separately because
    # backlog-replace supersession never touches disk).
    "service.miss_dropped",
    "disk.submitted", "disk.completed", "disk.not_found", "disk.all_air",
    "disk.errors", "disk.saturated", "disk.successful",
    # DiskReadGate refusals (disk-read-concurrency-gate-plan.md): a read bounced at the
    # expensive-path permit check. NEVER part of the submitted/completed partition (the
    # store-hit exclusion precedent), so A5's derived-successful identity is untouched;
    # A7 flags any increase unless the scenario opts in ("gated" — only disk-read-gate).
    "disk.gated",
    # Router passes stopped by gate saturation (Amendment 2 retention): one event per
    # stopped player-pass; retained entries carry NO disposition (the queue_full
    # precedent), so no conservation law reads this. Monotonic, still at convergence;
    # membership here also makes it required-present on every snapshot (both exporters
    # emit it unconditionally). A7 flags any increase unless opted in (disk-read-gate).
    "disk.gate_stops",
    # Miss-memo rung hits (v0.7.1 miss memo): a fresh memoized absence skipped the redundant
    # disk re-read and escalated straight to the generation ladder. Law A5 counts these as
    # VIRTUAL not-founds on its left side — each hit is dispositioned exactly like a real
    # miss (gen submit or miss_dropped), so the identity stays exact.
    "disk.memo_hits",
    # Header freshness rung hits (region-summary-sync-plan.md P1): a ts>0 read answered
    # up_to_date from the region header's save-second table without region IO. A mechanism
    # counter like memo_hits — no law consumes it (the answer is an ordinary up_to_date
    # response in A1; the read never entered the submitted/completed partition, so A5's
    # identities never see it). Expected ~0 on every scenario that keeps its tscache
    # intact (the rung fires only on a ts>0 ask that MISSED the tscache);
    # evicted-tscache-rejoin (summary_evicted.sh phase 2) deletes the persisted tscache
    # and FLOORS this counter — the rung's live gate.
    "disk.header_hits",
    "generation.submitted", "generation.completed", "generation.timeouts",
    "generation.removed_in_flight",
    # Ordering observability (miss-memo pacing): gate/pacing refusals + far-before-near
    # completion evidence. Monotonic counters; no law consumes them yet — read them from
    # the raw JSONL for A/B comparisons (soak_report does not surface them).
    "generation.order_gated", "generation.inversions",
    "dirty.broadcast_positions", "dirty.suppressed_total",
    "bandwidth.total_bytes",
    # The flagship a9bee8d honest-re-resolution counter — emitted every snapshot, cumulative.
    # Was assertable by nothing before round 2; now A6-monotonic and surfaced in the soak_report
    # mechanism digest. No scenario floor-checks it yet (no ingest-failure soak scenario exists),
    # so A6 on it is correct-but-quiescent until such a scenario is authored.
    "service.re_resolved",
    # Duplicate-serve grace (docs/planning/duplicate-serve-grace.md): crossing ts<=0
    # re-asks absorbed by the departure grace — each would otherwise have counted
    # re_resolved and cost a redundant disk read + send. Mechanism, not anomaly. A grace
    # skip is ALSO counted in service.duplicate_skips (law A1's disposition term — a
    # first fresh-backfill run imbalanced A1 by exactly the grace count until the subset
    # accounting landed); this counter is the observability subset, so no law reads it
    # directly.
    "service.grace_skipped",
    # LOD store (docs/planning/lod-store-implementation-plan.md): monotonic counter half
    # of the store family (all-zero while lodStore=off — the kill-switch A/B arm shape).
    # The gauges (store.queue is a SERVER_DRAIN; db_bytes/wal_bytes/
    # checkpoint_ms_max/read_avg_us) are deliberately absent from this whitelist.
    "store.hits", "store.misses", "store.deposits", "store.deposit_drops",
    "store.deposit_skips",
    "store.errors", "store.sweep_drops",
    "store.backfill_reads", "store.backfill_deposits", "store.backfill_skips",
    # Region summaries (region-summary-sync-plan.md §8): the dedicated send lane's own
    # counter family — deliberately NOT part of service.bytes_sent/wire_bytes (the
    # far-player lane precedent, cross-identity audits stay exact). All-zero on every
    # current scenario (harness clients never request — the property gate; the
    # summary-inert check mirrors far-players'). refresh_ms_hw is a GAUGE, absent here.
    "summary.requests", "summary.range_filtered", "summary.frames",
    "summary.tiles_known", "summary.tiles_never_clean", "summary.tiles_no_region",
    "summary.bytes",
    # Stamped up_to_date (stamped-up-to-date-plan.md §6): the verification-stamp
    # frames' own accounting — like summary.bytes, deliberately NOT part of
    # service.bytes_sent (law A2's raw==raw identity stays exact). Counted on
    # completed sends only; zero on every non-opt-in scenario (the inert check).
    "summary.stamps_frames", "summary.stamps_entries", "summary.stamps_bytes",
)
CLIENT_MONOTONIC = (
    "received_columns", "received_bytes", "dropped",
    "responses.columns", "responses.up_to_date", "responses.not_generated",
    "requested_total", "send_cycles",
)

# Fields the global laws index directly; verified against the first row of each series
# so a schema drift fails loudly with the field name instead of a stray KeyError.
GLOBAL_SERVER_FIELDS = ("wallMs", "players") + SERVER_MOVING + SERVER_DRAINS + SERVER_MONOTONIC
GLOBAL_CLIENT_FIELDS = (
    "wallMs", "dimension", "tracker_in_flight", "queued",
) + CLIENT_MONOTONIC

# Known top-level keys per row type — anything else is collected into ONE aggregated warning.
KNOWN_SERVER_KEYS = {
    # dedup/jvm/tscache and the *_hw / mspt_avg_window gauges are the round-2 data-capture
    # additions (sampled per tick by the driver); probe_hashes appears only when the server
    # JVM runs with -Dlss.soak.probes. All are observational — no law requires their presence.
    "snapshot": {"event", "wallMs", "tick", "service", "disk", "generation", "dirty",
                 "bandwidth", "players", "dedup", "jvm", "tscache", "store", "far_players",
                 "summary", "mailbox_depth_hw", "mspt_avg_window", "probe_hashes"},
    # mapped appears only on Folia runs, only when true: the driver acknowledged a timeline
    # command Folia unregisters (save-all) as a deliberate no-op instead of executing it.
    "command": {"event", "wallMs", "tick", "cmd", "anchor", "at", "ok", "mapped"},
    "join": {"event", "wallMs", "tick", "player", "joinIndex"},
    "end": {"event", "wallMs", "tick", "reason"},
}
KNOWN_CLIENT_KEYS = {
    # server_enabled and probes are optional additions (older recordings predate them):
    # server_enabled = the session-config enabled flag; probes = per-position timestamps
    # emitted only when the client runs with -Dlss.soak.probes.
    # effective_lod/rtt/ingest_failures are the round-2 client data-capture additions; like
    # probes they are presence-optional (older recordings predate them). request_queue left
    # with v17's drip-feed queue; rtt now measures last-declare->answer, not first-ask->answer.
    # queued_bytes: the decode-queue byte gauge (disk-read profile round, presence-optional
    # like the other late additions — older recordings predate it).
    # wire_received_bytes: shipped (codec-1 frame) volume next to the raw-denominated
    # received_bytes (compressed columns, protocol 19) — presence-optional like the
    # other late additions.
    # session_version: the ESTABLISHED session's protocol version (0 pre-config) — the
    # C6 negotiated-protocol observability (C3 review m8/m11); presence-optional for old
    # recordings, ASSERTED via --expect-session-version (soak.sh always passes it, so a
    # dialect-lever run that silently degraded to another rung reds instead of passing
    # format-blind laws on the wrong dialect).
    "snapshot": {"event", "wallMs", "dimension", "received_columns", "received_bytes",
                 "dropped", "responses", "requested_total", "send_cycles", "columns",
                 "scan", "tracker_in_flight", "queued", "queued_bytes", "server_enabled",
                 "probes", "effective_lod", "rtt", "ingest_failures",
                 "wire_received_bytes", "session_version",
                 # Region summaries (region-summary-sync-plan.md §8): the client half's
                 # attributability group — presence-optional like the other late
                 # additions (older recordings predate it); all-zero on every gated soak.
                 "summary"},
    # One scripted client-side action (-Dlss.soak.clientActionAt); resets the request
    # metrics, so the loader treats it as a client segment boundary.
    "action": {"event", "wallMs", "action", "atSeconds"},
}
KNOWN_CLIENT_KEYS["disconnect"] = KNOWN_CLIENT_KEYS["snapshot"] | {"reason"}


@dataclass
class Violation:
    law: str
    window: str
    message: str
    values: dict = field(default_factory=dict)

    def line(self):
        vals = " ".join(f"{k}={v}" for k, v in self.values.items())
        return f"[{self.law}] {self.window}: {self.message}" + (f" ({vals})" if vals else "")


def get_path(row, dotted):
    """Resolve a dotted path with direct-indexing semantics: a missing key raises
    KeyError(dotted) — never substitutes a default."""
    cur = row
    for part in dotted.split("."):
        if not isinstance(cur, dict) or part not in cur:
            raise KeyError(dotted)
        cur = cur[part]
    return cur


def delta(prev, cur, dotted):
    return get_path(cur, dotted) - get_path(prev, dotted)


# ---------------------------------------------------------------------------- parsing

def parse_jsonl(path, warnings, label):
    """Tolerant JSONL parse: blank lines skipped, malformed lines warn (with line number)."""
    rows = []
    with open(path, "r", encoding="utf-8") as f:
        for lineno, raw in enumerate(f, start=1):
            line = raw.strip()
            if not line:
                continue
            try:
                row = json.loads(line)
            except json.JSONDecodeError as e:
                warnings.append(f"{label}:{lineno}: malformed JSON line skipped ({e.msg})")
                continue
            if not isinstance(row, dict) or "event" not in row:
                warnings.append(f"{label}:{lineno}: row is not an event object, skipped")
                continue
            rows.append(row)
    return rows


def load_server(path, warnings, unknown_keys, unknown_events):
    """Returns dict with ordered snapshots (each tagged _seg by join segmentation),
    commands, joins, ends."""
    rows = parse_jsonl(path, warnings, path.name)
    out = {"snapshots": [], "commands": [], "joins": [], "ends": []}
    seg = 0
    for row in rows:
        ev = row["event"]
        known = KNOWN_SERVER_KEYS.get(ev)
        if known is None:
            unknown_events.add(f"server:{ev}")
            continue
        for k in row.keys() - known:
            unknown_keys.add(f"server.{ev}:{k}")
        if ev == "snapshot":
            row["_seg"] = seg
            out["snapshots"].append(row)
        elif ev == "join":
            seg += 1
            out["joins"].append(row)
        elif ev == "command":
            out["commands"].append(row)
        elif ev == "end":
            out["ends"].append(row)
    return out


def load_client_run(path, warnings, unknown_keys, unknown_events):
    """Returns (snaps, actions): snapshot-bearing rows (event snapshot or disconnect —
    the disconnect row carries the final snapshot inline), each tagged _seg, plus the
    scripted-action rows. Segments split on dimension change AND on action rows. Client
    counters are run-CUMULATIVE across segments (RequestMetrics.reset() clears only
    in-flight state, never the totals); segmentation exists because conservation windows
    must not span a boundary — the switch intentionally drops in-flight tracking, so a
    request answered after it increments requested_total with no response counter."""
    rows = parse_jsonl(path, warnings, path.name)
    snaps, actions = [], []
    seg, prev_dim = 0, None
    for row in rows:
        ev = row["event"]
        known = KNOWN_CLIENT_KEYS.get(ev)
        if known is None:
            unknown_events.add(f"client:{ev}")
            continue
        for k in row.keys() - known:
            unknown_keys.add(f"client.{ev}:{k}")
        if ev == "action":
            actions.append(row)
            if prev_dim is not None:
                seg += 1
                prev_dim = None  # suppress a second bump if the next row's dimension differs
            continue
        dim = row["dimension"] if "dimension" in row else None
        if prev_dim is not None and dim != prev_dim:
            seg += 1
        prev_dim = dim
        row["_seg"] = seg
        snaps.append(row)
    return snaps, actions


def client_segments(snaps):
    """[(seg_index, dimension, first_idx, last_idx)] in order."""
    segs = []
    for i, row in enumerate(snaps):
        if not segs or segs[-1][0] != row["_seg"]:
            segs.append([row["_seg"], row.get("dimension"), i, i])
        else:
            segs[-1][3] = i
    return [tuple(s) for s in segs]


def client_run_completion_violations(run_name, snaps):
    """A client run's completion gate: the client writes a disconnect row on EVERY controlled
    exit (kick between runs or server halt), then halt(0). Its absence means the client JVM died
    mid-run — the mirror of the server end-event requirement. As a silent pass this let a crash
    after the main phase (which also skips the synchronous cache flush) produce a clean PASS.
    Returns a list of Violations (empty when the run completed). Shared by run_checker and the
    selftest so the gate itself — not a re-implementation of it — is exercised."""
    if snaps and not any(s.get("event") == "disconnect" for s in snaps):
        return [Violation("run-completion", run_name,
                          "no disconnect event — client died mid-run (uncontrolled exit)", {})]
    return []


def session_version_violations(run_name, snaps, expected):
    """The negotiated-dialect assertion (C6, C3 review m8/m11): every client row that has
    ESTABLISHED a session (session_version != 0) must carry the expected version, and at
    least one row in the run must have established one at all — otherwise a client that
    never completed its handshake ladder would pass the assertion vacuously. Rows missing
    the key entirely (a pre-C6 client jar) are a violation too: the caller explicitly
    asked for the assertion, and an old jar cannot carry it. Shared by run_checker and
    the selftest."""
    if expected is None:
        return []
    out = []
    established = 0
    for i, s in enumerate(snaps):
        if s.get("event") not in ("snapshot", "disconnect"):
            continue
        if "session_version" not in s:
            return [Violation("session-version", run_name,
                              "rows lack session_version — the client jar predates the "
                              "C6 observability field, cannot assert the dialect", {})]
        v = s["session_version"]
        if v != 0:
            established += 1
            if v != expected:
                out.append(Violation("session-version", run_name,
                                     f"row {i}: established session_version {v} != expected "
                                     f"{expected} — the session degraded to another rung",
                                     {"row": i, "got": v, "expected": expected}))
    if not out and established == 0:
        out.append(Violation("session-version", run_name,
                             f"no row ever established a session (expected {expected}) — "
                             "the assertion would be vacuous", {}))
    return out


# ------------------------------------------------------------------------- quiescence

@dataclass
class QPoint:
    si: int        # server snapshot index (the LATER element of the stable pair)
    sseg: int      # server join segment (== client run number; 0 = before first join)
    run: int       # client run number
    ci: int        # index into that run's snapshot list (nearest — named checks/joins)
    cseg: int      # client dimension segment of the joined snapshot
    wall: int      # server snapshot wallMs
    cib: int | None = None  # bounded at-or-before law row (see find_quiescent)


def server_pair_quiescent(prev, cur):
    if prev["_seg"] != cur["_seg"]:
        return False
    for path in SERVER_MOVING:
        if get_path(prev, path) != get_path(cur, path):
            return False
    for snap in (prev, cur):
        for path in SERVER_DRAINS:
            limit = QUIESCENCE_DIRTY_PENDING_TOLERANCE if path == "dirty.pending" else 0
            if get_path(snap, path) > limit:
                return False
        for player in snap["players"]:
            for k in PLAYER_DRAINS:
                if player[k] != 0:
                    return False
    return True


def nearest_client(snaps, wall):
    """(index, row) of the snapshot nearest in wallMs, or (None, None)."""
    best, best_i = None, None
    for i, row in enumerate(snaps):
        d = abs(row["wallMs"] - wall)
        if best is None or d < best:
            best, best_i = d, i
    return (best_i, snaps[best_i]) if best_i is not None else (None, None)


def find_quiescent(server_snaps, runs):
    """runs: {run_number: [client snapshots]}. Returns ordered QPoints — server-side
    stability verified across the consecutive pair, client mirror verified on the
    nearest-in-wallMs snapshot of the matching run (skew <= SKEW_MS)."""
    qpoints = []
    for i in range(1, len(server_snaps)):
        prev, cur = server_snaps[i - 1], server_snaps[i]
        if not server_pair_quiescent(prev, cur):
            continue
        run = cur["_seg"]
        snaps = runs.get(run)
        if not snaps:
            continue  # no client connected (e.g. pre-join, or kick→rejoin gap)
        ci, crow = nearest_client(snaps, cur["wallMs"])
        if crow is None or abs(crow["wallMs"] - cur["wallMs"]) > SKEW_MS:
            continue
        if crow["tracker_in_flight"] != 0 or crow["queued"] != 0:
            continue
        # Law endpoint row (cib): the LATEST client row at-or-before this instant but
        # not older than the stillness pair's start. The pair guarantees zero server
        # activity and empty drains across [prev, cur], so a row inside that span holds
        # counter values exactly equal to the instant's — no forward-skew exposure
        # (a nearest-row pairing can lag INTO resumed traffic and desync the window).
        cib = None
        for j in range(len(snaps) - 1, -1, -1):
            w = snaps[j]["wallMs"]
            if w <= cur["wallMs"] and w >= prev["wallMs"] - SKEW_MS:
                if snaps[j]["tracker_in_flight"] == 0 and snaps[j]["queued"] == 0:
                    cib = j
                break
            if w < prev["wallMs"] - SKEW_MS:
                break
        qpoints.append(QPoint(si=i, sseg=cur["_seg"], run=run, ci=ci,
                              cseg=crow["_seg"], wall=cur["wallMs"], cib=cib))
    return qpoints


def window_label(ps, cs, run=None, dim=None):
    label = f"wallMs[{ps['wallMs']}..{cs['wallMs']}]"
    if run is not None:
        label += f" run{run}"
    if dim is not None:
        label += f" dim={dim}"
    return label


# ------------------------------------------------------------------------------- laws

def law_A1(ps, cs, pc, cc, window):
    """v17 request conservation. requested_total counts every DECLARED entry (re-declares
    included — a position unanswered for N scans is declared N times). Each server-received
    entry ends exactly one way by the time both endpoints are quiescent (backlog == 0):
    answered on the wire (columns / up_to_date / not_generated), duplicate-skipped (a
    re-declaration of a still-pending position — the DOMINANT disposition under 1 Hz
    re-declaration), superseded (silent drop, healed by re-declaration), or range-filtered
    at ingress (the movement race).

    queue_full is NOT a term: a send-queue-full break RETAINS its entries with no
    disposition at all, and backlog == 0 at both quiescent endpoints guarantees each
    retained entry later resolved or was superseded inside the window. It stays a pure
    diagnostic counter.

    LATENT FALSE POSITIVES (documented 2026-07-28, never yet seen live — the A5 latents'
    A1 siblings; see CLAUDE.md's flake catalog): (1) a client transport send that THROWS
    still counts requested_total (count-at-send is pinned — LodRequestManagerTest
    "a failed batch still counts as attempted") with no RHS term — a permanent imbalance
    of the batch size if it lands inside an evaluated window; (2) a server batch-frame
    send failure loses up to MAX_BATCH_RESPONSES dispositions the same way (the client
    re-declares, but the re-declared INSTANCE balances its own answer, never the lost
    one); (3) a declaration landing in the post-/reload unregistered window before the
    re-attach prompt heals it (Paper only). All three triggers are dying-connection or
    boundary shapes the quiescent-pair windowing excludes, which is why none has fired —
    an A1 red whose imbalance matches one batch/frame is one of these, not conservation
    rot."""
    d_req = delta(pc, cc, "requested_total")
    d_resp = sum(delta(pc, cc, f"responses.{k}")
                 for k in ("columns", "up_to_date", "not_generated"))
    d_dup = delta(ps, cs, "service.duplicate_skips")
    d_sup = delta(ps, cs, "service.superseded")
    d_rf = delta(ps, cs, "service.range_filtered")
    expected = d_resp + d_dup + d_sup + d_rf
    if d_req != expected:
        return [Violation("A1", window,
                          "requested_total delta != responses + duplicate_skips + "
                          "superseded + range_filtered",
                          {"d_requested_total": d_req, "d_responses": d_resp,
                           "d_duplicate_skips": d_dup, "d_superseded": d_sup,
                           "d_range_filtered": d_rf,
                           "expected": expected, "actual": d_req})]
    return []


def law_A2(ps, cs, pc, cc, window):
    out = []
    d_sent = delta(ps, cs, "service.columns_sent")
    d_recv = delta(pc, cc, "received_columns")
    if d_sent != d_recv:
        out.append(Violation("A2", window, "columns_sent != received_columns",
                             {"d_server_columns_sent": d_sent, "d_client_received_columns": d_recv}))
    d_bytes_s = delta(ps, cs, "service.bytes_sent")
    d_bytes_c = delta(pc, cc, "received_bytes")
    if d_bytes_s != d_bytes_c:
        out.append(Violation("A2", window, "bytes_sent != received_bytes",
                             {"d_server_bytes_sent": d_bytes_s, "d_client_received_bytes": d_bytes_c}))
    d_drop = delta(pc, cc, "dropped")
    if d_drop != 0:
        out.append(Violation("A2", window, "client dropped changed inside window",
                             {"d_dropped": d_drop, "expected": 0}))
    return out


def law_A3(ps, cs, window):
    d_sent = delta(ps, cs, "service.columns_sent")
    # store.hits joined the source side with the LOD store (Phase 1): a store hit serves
    # a column without touching disk.successful (the rung contract excludes hits from the
    # disk pair). Hits legitimately OVER-count the right side (an all-air hit resolves
    # without a column send) — safe for an inequality law.
    d_src = (delta(ps, cs, "service.in_memory")
             + delta(ps, cs, "disk.successful")
             + delta(ps, cs, "generation.completed")
             + delta(ps, cs, "store.hits"))
    if d_sent > d_src:
        return [Violation("A3", window,
                          "columns_sent exceeds in_memory + disk.successful + "
                          "generation.completed + store.hits",
                          {"d_columns_sent": d_sent, "d_sources": d_src})]
    return []


def law_A4(ps, cs, window):
    d_sub = delta(ps, cs, "generation.submitted")
    d_comp = delta(ps, cs, "generation.completed")
    d_to = delta(ps, cs, "generation.timeouts")
    d_rm = delta(ps, cs, "generation.removed_in_flight")
    if d_sub != d_comp + d_to + d_rm:
        return [Violation("A4", window,
                          "generation.submitted != completed + timeouts + removed_in_flight",
                          {"d_submitted": d_sub, "d_completed": d_comp,
                           "d_timeouts": d_to, "d_removed_in_flight": d_rm,
                           "expected": d_comp + d_to + d_rm, "actual": d_sub})]
    return []


def law_A5(ps, cs, pc, cc, window):
    out = []
    d_to = delta(ps, cs, "generation.timeouts")
    if d_to == 0:  # stated precondition: valid only when timeouts == 0 in window
        d_nf = delta(ps, cs, "disk.not_found")
        # An errored read (e.g. a 10 s IOWorker-starvation timeout) delivers an EMPTY
        # result, which the processor resolves through the same not-found ladder — it
        # lands on the right side (gen submit / miss_dropped) without ever counting
        # disk.not_found. Fold errors into the left side or IO pressure breaks the
        # identity by exactly the error count (seen live 2026-07-17, 20 timeouts).
        d_nf += delta(ps, cs, "disk.errors")
        # Miss-memo rung hits are VIRTUAL not-founds: the read was skipped (no disk.not_found
        # increment) but the hit was dispositioned exactly like a real miss — a gen submit or
        # a miss_dropped. Fold them into the left side; both memo dispositions then balance
        # by inspection (hit->submit: +1/+1 submitted; hit->drop: +1/+1 miss_dropped).
        d_nf += delta(ps, cs, "disk.memo_hits")
        d_gen_sub = delta(ps, cs, "generation.submitted")
        d_ng = delta(pc, cc, "responses.not_generated")
        # Server-owned generation: a miss can also resolve into a TRANSIENT silent drop
        # (gen slot full at the miss, capacity/removed-player reject, ghost delivery) —
        # counted in the dedicated service.miss_dropped (NOT service.superseded, whose
        # backlog-replace events never touch disk and would over-balance this identity).
        # Latent false-positive (documented, accepted): a PERMANENT generation failure
        # (extraction error / null chunk) lands on BOTH right-hand terms for one miss —
        # generation.submitted at submit AND responses.not_generated when the answer
        # reaches a still-connected client — so the identity OVER-counts the RHS by one
        # per failure while connected (a disconnect before the answer rebalances it).
        # Same family: the two generation-ticket drop paths in drainGenerationTicketRequests
        # (state gone / dimension mismatch, both platforms) skip miss_dropped, so a
        # near-boundary drop on dimension-trip UNDER-counts the RHS by one (a memo-hit-
        # admitted ticket dropped there leaves the +1 on disk.memo_hits unbalanced, same
        # window and magnitude). And with TWO LSS clients (no current scenario has them):
        # a dedup fan-out miss counts disk.not_found ONCE but every attached player runs
        # its own disposition — balanced only when all attachees admit (piggyback skips
        # totalSubmitted); each pacing/spread/slot-full REFUSED attachee adds an extra
        # miss_dropped, OVER-counting the RHS by one. No scenario currently produces
        # permanent gen failures; if A5 reds, check both counts against CLAUDE.md's flake
        # catalog before chasing conservation.
        d_md = delta(ps, cs, "service.miss_dropped")
        if d_nf != d_gen_sub + d_ng + d_md:
            out.append(Violation("A5", window,
                                 "disk.not_found != generation.submitted + not_generated "
                                 "responses + miss_dropped",
                                 {"d_not_found": d_nf, "d_gen_submitted": d_gen_sub,
                                  "d_not_generated": d_ng, "d_miss_dropped": d_md,
                                  "expected": d_gen_sub + d_ng + d_md,
                                  "actual": d_nf}))
    d_ok = delta(ps, cs, "disk.successful")
    d_part = (delta(ps, cs, "disk.completed") - delta(ps, cs, "disk.not_found")
              - delta(ps, cs, "disk.all_air") - delta(ps, cs, "disk.errors")
              - delta(ps, cs, "disk.saturated"))
    if d_ok != d_part:
        out.append(Violation("A5", window,
                             "disk.successful != completed - not_found - all_air - errors - saturated",
                             {"d_successful": d_ok, "expected": d_part, "actual": d_ok}))
    return out


def law_A6_server(snaps):
    """Server whitelist counters are monotonic over the whole process lifetime
    (joins do NOT reset them); per-player rows are deliberately never checked."""
    out = []
    for i in range(1, len(snaps)):
        prev, cur = snaps[i - 1], snaps[i]
        for path in SERVER_MONOTONIC:
            pv, cv = get_path(prev, path), get_path(cur, path)
            if cv < pv:
                out.append(Violation("A6", window_label(prev, cur),
                                     f"server counter {path} decreased",
                                     {"field": path, "prev": pv, "cur": cv}))
    return out


def law_A6_client(run, snaps):
    """Client counters are cumulative for the whole run — dimension/action boundaries do
    NOT reset them (RequestMetrics.reset() clears only in-flight state, never the totals),
    so monotonicity holds across segment boundaries too. Only a new process (run) resets."""
    out = []
    for i in range(1, len(snaps)):
        prev, cur = snaps[i - 1], snaps[i]
        for path in CLIENT_MONOTONIC:
            pv, cv = get_path(prev, path), get_path(cur, path)
            if cv < pv:
                out.append(Violation("A6", window_label(prev, cur, run=run, dim=cur.get("dimension")),
                                     f"client counter {path} decreased within one run",
                                     {"field": path, "prev": pv, "cur": cv}))
    return out


def _anomaly(prev, cur, path, window, label, opt_ins, opt_name, out):
    pv = 0 if prev is None else get_path(prev, path)
    cv = get_path(cur, path)
    d = cv - pv
    if d > 0 and (opt_name is None or opt_name not in opt_ins):
        suffix = "" if opt_name is None else " (scenario did not opt in)"
        out.append(Violation("A7", window, f"{label} increased{suffix}",
                             {"field": path, "delta": d, "prev": pv, "cur": cv}))


def law_A7_server(prev, cur, window, opt_ins):
    """prev may be None: the head window from process start (counters start at 0)."""
    out = []
    _anomaly(prev, cur, "disk.errors", window, "disk.errors", opt_ins, None, out)
    _anomaly(prev, cur, "generation.timeouts", window, "generation.timeouts", opt_ins, None, out)
    _anomaly(prev, cur, "disk.saturated", window, "disk.saturated", opt_ins, "saturated", out)
    # The DiskReadGate's refusals: the no-op pins on every pre-existing scenario mean a
    # nonzero delta there is a PERMIT LEAK (or a missing pin) — a stronger signal than
    # the saturated arm, same design. Only disk-read-gate opts in.
    _anomaly(prev, cur, "disk.gated", window, "disk.gated", opt_ins, "gated", out)
    # Router retention stops (Amendment 2): a nonzero delta on a no-op-pinned scenario
    # (K=pool) is structurally impossible unless the predicate leaked — same design as
    # the gated arm. Only disk-read-gate opts in.
    _anomaly(prev, cur, "disk.gate_stops", window, "disk.gate_stops", opt_ins,
             "gate_stops", out)
    return out


def law_A7_client(prev, cur, window, opt_ins):
    """prev may be None: the run-head window (counters are exactly 0 at process start).
    Later segment heads anchor at the last pre-boundary row instead — counters are
    run-cumulative, so pv=0 there would re-bill earlier segments' anomalies."""
    # v17: the client's only A7-relevant anomaly is `dropped` (never optable). The
    # responses.rate_limited arm left with RESPONSE_RATE_LIMITED — a slot bounce is no
    # longer observable at the client at all; it is a retained backlog entry server-side.
    out = []
    _anomaly(prev, cur, "dropped", window, "client dropped", opt_ins, None, out)
    return out


# law_B1 (rate-limit conservation: client rate_limited == sync + gen bounces + saturated)
# was DELETED with protocol v17. Its subject — the RESPONSE_RATE_LIMITED wire response —
# no longer exists: a full slot retains the want entry silently and the client's 1 Hz
# re-declaration heals it. The conservation it provided is not lost, it MOVED: the silent
# drops it used to make observable are now counted in service.superseded, which is a term
# of law A1. Do not resurrect B1 without a wire response to conserve.


def law_B2(snaps, cap_bytes_per_sec):
    """Bandwidth pacing: between every consecutive server snapshot pair, bytes on the wire
    must not outpace the configured global cap (with B2_HEADROOM for tick jitter). Evaluated
    over the raw series — pacing must hold DURING the storm, not just at quiescence."""
    out = []
    # Whole-run cumulative bound: the per-window headroom only absorbs snapshot-edge
    # transients; a sustained pacing regression (e.g. a divisor bug) hides inside it
    # window-by-window but not cumulatively. Recorded worst case is 1.6% over the cap,
    # so 5% headroom is generous yet tight.
    if len(snaps) >= 2:
        total_dt = (snaps[-1]["wallMs"] - snaps[0]["wallMs"]) / 1000.0
        total_bytes = (get_path(snaps[-1], "bandwidth.total_bytes")
                       - get_path(snaps[0], "bandwidth.total_bytes"))
        if total_dt > 30 and total_bytes > cap_bytes_per_sec * total_dt * 1.05:
            out.append(Violation("B2", f"wallMs[{snaps[0]['wallMs']}..{snaps[-1]['wallMs']}] whole-run",
                                 "cumulative bandwidth exceeded the configured cap by more than 5%",
                                 {"cap_bytes_per_s": cap_bytes_per_sec,
                                  "total_bytes": total_bytes,
                                  "wall_seconds": round(total_dt, 1)}))
    for i in range(1, len(snaps)):
        prev, cur = snaps[i - 1], snaps[i]
        dt = (cur["wallMs"] - prev["wallMs"]) / 1000.0
        if dt <= 0:
            continue
        d = delta(prev, cur, "bandwidth.total_bytes")
        allowed = cap_bytes_per_sec * dt * B2_HEADROOM
        if d > allowed:
            out.append(Violation("B2", window_label(prev, cur),
                                 "bandwidth.total_bytes outpaced the configured global cap",
                                 {"d_bytes": d, "allowed": round(allowed),
                                  "cap_bytes_per_sec": cap_bytes_per_sec,
                                  "dt_seconds": round(dt, 3)}))
    return out


def check_window_floors(floors, client_windows):
    """floors: {(run, cseg): min}; client_windows: {(run, cseg): evaluated count}."""
    out = []
    for (run, seg), need in sorted(floors.items()):
        got = client_windows.get((run, seg), 0)
        if got < need:
            out.append(Violation("law-coverage", f"run{run} seg{seg}",
                                 "fewer client-laws windows than the scenario floor — "
                                 "client-involving laws were (near-)vacuous",
                                 {"required": need, "evaluated": got}))
    return out


# ------------------------------------------------------------------ law orchestration

def evaluate_laws(ctx):
    """Returns (violations, windows_evaluated, client_windows_evaluated). Window kinds:
    - in-window quiescent pairs (same run + same dimension segment): A1-A5 + A7
    - boundary-spanning quiescent pairs (join/dimension boundary between them): A7 only
      (the spec: boundaries get drain+anomaly checks only)
    - head/tail anomaly coverage: process-start -> first qpoint and last qpoint -> final
      snapshot (server); segment-start -> first qpoint and last qpoint -> segment end
      per client (run, dimension segment). These do not count as evaluated windows.
    - B2 over every consecutive raw snapshot pair, armed only when the scenario config
      sets bytesPerSecondLimitGlobal.
    Per-(run, segment) client-laws window counts are checked against MIN_CLIENT_WINDOWS.
    """
    violations = []
    windows = 0
    client_windows = {}
    traffic_windows = 0
    snaps = ctx.server_snaps
    opt_ins = ANOMALY_OPT_INS.get(ctx.scenario, frozenset())

    # A6 over raw series.
    violations += law_A6_server(snaps)
    for run, csnaps in sorted(ctx.runs.items()):
        violations += law_A6_client(run, csnaps)

    # B2 over raw series, armed by the scenario's bandwidth cap override — either
    # spelling, with MB WINNING when both are present (final-review C-M3: this mirrors
    # the mod's resolveBandwidthKeys ladder exactly; the checker preferring bytes would
    # arm B2 at a value the server is not enforcing).
    cap = None
    mb = ctx.config.get("mbPerSecondLimitGlobal")
    if isinstance(mb, (int, float)) and not isinstance(mb, bool):
        cap = int(mb * 1024 * 1024)
    if cap is None:
        b = ctx.config.get("bytesPerSecondLimitGlobal")
        if isinstance(b, int) and not isinstance(b, bool):
            cap = b
    if isinstance(cap, int) and cap > 0:
        violations += law_B2(snaps, cap)

    # Quiescent-pair windows. Client-law endpoints use each qpoint's BOUNDED at-or-before
    # row (QPoint.cib): the stillness pair behind every qpoint guarantees zero server
    # activity and empty drains across its span, so a client row inside that span carries
    # counter values exactly equal to the instant's. This is skew-safe in both directions
    # (a nearest-row pairing could lag INTO resumed traffic — the original false-A2 bug —
    # while a two-sided-plateau requirement excluded every window that contained traffic,
    # making the laws structurally vacuous: the CRITICAL review finding).
    seg_first_window = {}

    def run_client_laws(ps, cs, pc, cc, win, key, skip_a5=False):
        nonlocal traffic_windows
        v = []
        v += law_A1(ps, cs, pc, cc, win)
        v += law_A2(ps, cs, pc, cc, win)
        if not skip_a5:
            v += law_A5(ps, cs, pc, cc, win)
        client_windows[key] = client_windows.get(key, 0) + 1
        if get_path(cc, "requested_total") - get_path(pc, "requested_total") > 0:
            traffic_windows += 1
        return v

    # Virtual run-start windows: each run's client counters are exactly zero at join,
    # and the last pre-join server snapshot is still by construction (no client, or the
    # kick gap). Anchoring the run's FIRST window there puts the entire backfill/resync
    # burst inside a conservation window — without it, traffic before the first natural
    # qpoint (i.e. the bulk of every scenario) is never law-checked.
    for run, join in enumerate(ctx.joins, start=1):
        csnaps = ctx.runs.get(run)
        if not csnaps:
            continue
        first_q = next((q for q in ctx.qpoints if q.run == run and q.cib is not None), None)
        if first_q is None or first_q.cseg != csnaps[0]["_seg"]:
            continue  # no in-segment quiescence — covered only by named checks
        pre = [sn for sn in snaps if sn["wallMs"] <= join["wallMs"]]
        if not pre:
            continue
        ps, cs = pre[-1], snaps[first_q.si]
        cc = csnaps[first_q.cib]
        pc = {"wallMs": join["wallMs"], "requested_total": 0, "received_columns": 0,
              "received_bytes": 0, "dropped": 0,
              "responses": {"columns": 0, "up_to_date": 0, "not_generated": 0}}
        win = window_label(ps, cs, run=run, dim=cc.get("dimension")) + " run-start"
        violations += run_client_laws(ps, cs, pc, cc, win, (run, first_q.cseg))
        windows += 1

    for k in range(1, len(ctx.qpoints)):
        q1, q2 = ctx.qpoints[k - 1], ctx.qpoints[k]
        ps, cs = snaps[q1.si], snaps[q2.si]
        same_window = (q1.run == q2.run and q1.cseg == q2.cseg and q1.sseg == q2.sseg)
        client_laws_ok = (same_window and q1.cib is not None and q2.cib is not None
                          and q1.cib < q2.cib)
        if same_window:
            win_dim = ctx.runs[q1.run][q1.ci].get("dimension")
            win = window_label(ps, cs, run=q1.run, dim=win_dim)
            if client_laws_ok:
                pc, cc = ctx.runs[q1.run][q1.cib], ctx.runs[q2.run][q2.cib]
                # A5 exempt in the first window after a segment change: a single request's
                # disk-notFound and its gen-escalation/ng can straddle the dimension
                # switch, splitting the identity's two sides across adjacent windows
                # (observed live as an exact off-by-one on Paper dimension-trip).
                first_after_boundary = seg_first_window.get((q1.run, q1.cseg), True)
                seg_first_window[(q1.run, q1.cseg)] = False
                violations += run_client_laws(ps, cs, pc, cc, win, (q1.run, q1.cseg),
                                              skip_a5=first_after_boundary and q1.cseg > 0)
            violations += law_A3(ps, cs, win)
            violations += law_A4(ps, cs, win)
            violations += law_A7_server(ps, cs, win, opt_ins)
        else:
            win = window_label(ps, cs) + " boundary"
            violations += law_A7_server(ps, cs, win, opt_ins)
        windows += 1

    # Server A7 head/tail coverage (and whole-run coverage when no quiescence exists).
    if snaps:
        first_q = snaps[ctx.qpoints[0].si] if ctx.qpoints else snaps[-1]
        violations += law_A7_server(None, first_q,
                                    f"wallMs[start..{first_q['wallMs']}] head", opt_ins)
        if ctx.qpoints and ctx.qpoints[-1].si < len(snaps) - 1:
            last_q, last = snaps[ctx.qpoints[-1].si], snaps[-1]
            violations += law_A7_server(last_q, last, window_label(last_q, last) + " tail", opt_ins)

    # Client A7 per (run, dimension segment): consecutive in-segment qpoints + head/tail.
    for run, csnaps in sorted(ctx.runs.items()):
        qs_by_seg = {}
        for q in ctx.qpoints:
            if q.run == run:
                qs_by_seg.setdefault(q.cseg, []).append(q.ci)
        for seg, dim, lo, hi in client_segments(csnaps):
            qcis = sorted(set(qs_by_seg.get(seg, [])))
            # Head anchor: None (pv=0) only for the run's first segment — counters are
            # run-cumulative, so later segments anchor at the last pre-boundary row for
            # an exact segment delta (pv=0 would re-bill earlier segments' anomalies).
            head = None if lo == 0 else csnaps[lo - 1]
            chain = [head] + [csnaps[ci] for ci in qcis]
            if not qcis or qcis[-1] < hi:
                chain.append(csnaps[hi])
            for j in range(1, len(chain)):
                prev, cur = chain[j - 1], chain[j]
                start = csnaps[lo]["wallMs"] if prev is None else prev["wallMs"]
                win = f"wallMs[{start}..{cur['wallMs']}] run{run} dim={dim}"
                violations += law_A7_client(prev, cur, win, opt_ins)

    # Vacuous-pass guard: enough client-laws windows actually evaluated per (run, segment).
    violations += check_window_floors(MIN_CLIENT_WINDOWS.get(ctx.scenario, {}), client_windows)
    # Traffic floor: the client laws must have fired on at least one window with REAL
    # deltas. Without this, a regression in window construction (e.g. the two-sided
    # endpoint skip this guards against) can leave every evaluated window zero-delta —
    # conservation 'verified' as 0 == 0. enabled-false is exempt: it legitimately has
    # no LSS traffic at all.
    if ctx.scenario not in TRAFFIC_FLOOR_EXEMPT and traffic_windows == 0:
        violations.append(Violation("law-coverage", "entire run",
                                    "no client-laws window carried nonzero request deltas — "
                                    "conservation laws never fired on real traffic",
                                    {"client_windows": sum(client_windows.values())}))
    violations += far_players_inert_violations(ctx.server_snaps)
    violations += summary_inert_violations(ctx.server_snaps, ctx.scenario, ctx.runs)
    return violations, windows, sum(client_windows.values())


def far_players_inert_violations(snapshots):
    """E1 baseline neutrality (FARP §3.3 property gate): soak clients must NEVER
    subscribe to far players — their capability bit is property-gated off — so every
    far_players counter must read zero on every scenario. A nonzero here means the gate
    broke and every soak baseline silently shifted. Presence-tolerant: pre-E1
    recordings without the block pass vacuously. Scans EVERY snapshot (review NIT):
    ``subscribers`` is a gauge, so a viewer that subscribed mid-run and quit before
    scenario end would read 0 in the final snapshot alone."""
    snaps = snapshots if isinstance(snapshots, list) else [snapshots]
    moved = {}
    for snap in snaps:
        for k, v in snap.get("far_players", {}).items():
            if isinstance(v, (int, float)) and v != 0:
                moved[k] = max(moved.get(k, 0), v)
    if moved:
        return [Violation("far-players-inert", "entire run",
                          "far_players counters moved on a soak run — the client "
                          "property gate must keep harness clients unsubscribed", moved)]
    return []


# Scenarios that deliberately arm the region-summary exchange (the client opts back in
# via -Dlss.soak.summary) — the ONLY runs where summary.* counters may move.
SUMMARY_OPT_IN_SCENARIOS = frozenset({"warm-rejoin-summary", "dirty-while-offline-summary",
                                      "stamp-heal-prime", "stamp-heal-rejoin"})


def summary_inert_violations(snapshots, scenario=None, client_runs=None):
    """Region-summary baseline neutrality (region-summary-sync-plan.md §6/§8 — the
    far-players mirror): soak clients never REQUEST summaries (the -Dlss.soak property
    gate), so every summary counter must read zero on every scenario except the
    explicit opt-ins. A nonzero elsewhere means the gate broke: suppression removes
    client asks, so every requested_total/up_to_date baseline silently shifted.
    CLIENT counters are scanned too (harness review m4): evicted-tscache-rejoin's
    client is harness-gated OFF summaries (no -Dlss.soak.summary opt-in), so it
    never requests at all — its disabled SERVER switch is a second, independent
    belt, not the mechanism — and the server side alone cannot see a broken client
    gate; a client that APPLIED anything on a non-opt-in run is the belt for that
    shape.
    Presence-tolerant: pre-summary recordings pass vacuously."""
    if scenario in SUMMARY_OPT_IN_SCENARIOS:
        return []
    snaps = snapshots if isinstance(snapshots, list) else [snapshots]
    moved = {}
    for snap in snaps:
        for k, v in snap.get("summary", {}).items():
            if isinstance(v, (int, float)) and v != 0:
                moved[k] = max(moved.get(k, 0), v)
    for run, rows in sorted((client_runs or {}).items()):
        for snap in rows:
            for k, v in snap.get("summary", {}).items():
                if isinstance(v, (int, float)) and v != 0:
                    moved[f"client-run{run}.{k}"] = max(
                        moved.get(f"client-run{run}.{k}", 0), v)
    if moved:
        return [Violation("summary-inert", "entire run",
                          "summary counters moved on a non-opt-in soak run — the client "
                          "property gate must keep harness clients from requesting", moved)]
    return []


# ----------------------------------------------------------------------- named checks

def named_check(law, required_fields):
    def deco(fn):
        fn.law = law
        fn.required_fields = required_fields
        return fn
    return deco


@dataclass
class Ctx:
    scenario: str
    server_snaps: list
    commands: list
    joins: list
    ends: list
    runs: dict                 # run number -> list of client snapshots
    qpoints: list
    config: dict               # scenario -config.json contents ({} if unavailable)
    platform: str = "fabric"   # SOAK_PLATFORM of the recording (soak.sh --platform)
    quiescent_server: set = field(default_factory=set)   # server snapshot indices
    quiescent_client: set = field(default_factory=set)   # (run, client index) join targets
    run_actions: dict = field(default_factory=dict)      # run number -> list of action rows

    def final_client(self, run):
        snaps = self.runs.get(run)
        return snaps[-1] if snaps else None


@named_check("fresh-backfill", ["server.generation.completed", "client.scan.confirmed"])
def check_fresh_backfill(ctx):
    last = ctx.server_snaps[-1]
    completed = last["generation"]["completed"]
    if completed <= 500:
        yield Violation("fresh-backfill", "final snapshot",
                        "generation.completed must exceed 500 on a fresh world",
                        {"expected": "> 500", "actual": completed})
    if (len(ctx.server_snaps) - 1) not in ctx.quiescent_server:
        yield Violation("fresh-backfill", "final snapshot",
                        "last server snapshot is not verified-quiescent (backfill did not converge)",
                        {"wallMs": last["wallMs"]})
    fc = ctx.final_client(1)
    if fc is None:
        yield Violation("fresh-backfill", "run1", "no client snapshots in run 1", {})
    else:
        confirmed = fc["scan"]["confirmed"]
        if confirmed <= 24:
            yield Violation("fresh-backfill", "run1 final snapshot",
                            "client scan.confirmed must exceed the LOD distance (24)",
                            {"expected": "> 24", "actual": confirmed})


@named_check("hybrid-boundary", ["server.generation.completed", "client.scan.confirmed"])
def check_hybrid_boundary(ctx):
    # The hybrid walk's ONE automated end-to-end gate (hybrid-scan-plan.md §8 item
    # 10): a lod-72 fresh fill crosses the N=64 phase boundary (phase 1 rings +
    # phase 2 residue regions), so convergence here proves the partition/handoff
    # live — everything else in the fleet runs lod <= 24 and degenerates to phase 1.
    # Sizing (impl-review M2, re-derived after the first live run): the annulus is
    # (2*72+1)^2 - 17^2 = 20,736 columns. The lod-24 burst rate (~30 gen/s) does NOT
    # hold at scale: run 1 measured 20.8/s early declining to a ~12/s sustained
    # floor under a WSL2 read-timeout storm (289 x 10 s timeouts wedged >half of the
    # old 5-thread reader pool — the A7 environmental mechanism starving the
    # miss->gen escalations while generation slots idled). The config now carries an
    # 8/8 reader pool (disk contention is not this gate's subject) and the end is
    # 1800 s: converges by ~1,100 s at a healthy ~19/s, by ~1,730 s at the degraded
    # 12/s floor. The SEAM-HOLE discriminator is make_disc_completeness's
    # area floor, NOT scan.confirmed — an unobserved ring-65 hole FALSE-CONVERGES
    # confirmed to lod+1 (it never feeds minUnresolved); the gen floor below is a
    # fill PREMISE (near-full generation), not the discriminator.
    last = ctx.server_snaps[-1]
    completed = last["generation"]["completed"]
    if completed <= 15000:
        yield Violation("hybrid-boundary", "final snapshot",
                        "generation.completed must exceed 15000 on a fresh lod-72 world "
                        "(the ~20.7k-column annulus generated near-fully)",
                        {"expected": "> 15000", "actual": completed})
    if (len(ctx.server_snaps) - 1) not in ctx.quiescent_server:
        yield Violation("hybrid-boundary", "final snapshot",
                        "last server snapshot is not verified-quiescent (the boundary fill did not converge)",
                        {"wallMs": last["wallMs"]})
    fc = ctx.final_client(1)
    if fc is None:
        yield Violation("hybrid-boundary", "run1", "no client snapshots in run 1", {})
    else:
        confirmed = fc["scan"]["confirmed"]
        if confirmed <= 72:
            yield Violation("hybrid-boundary", "run1 final snapshot",
                            "client scan.confirmed must exceed the LOD distance (72) — "
                            "the walk crossed the phase boundary and converged",
                            {"expected": "> 72", "actual": confirmed})


@named_check("warm-rejoin", ["client.responses.up_to_date", "client.responses.columns",
                             "client.requested_total"])
def check_warm_rejoin(ctx):
    r1, r2 = ctx.final_client(1), ctx.final_client(2)
    if r1 is None or r2 is None:
        yield Violation("warm-rejoin", "runs",
                        "warm-rejoin requires client-run1.jsonl and client-run2.jsonl snapshots",
                        {"run1": "present" if r1 else "missing",
                         "run2": "present" if r2 else "missing"})
        return
    utd = r2["responses"]["up_to_date"]
    if utd < 500:
        yield Violation("warm-rejoin", "run2 final snapshot",
                        "run-2 up_to_date responses too low — cache was not warm",
                        {"expected": ">= 500", "actual": utd})
    # run2 columns are NOT near-zero by design: the kick empties the server, the whole
    # disc unload-saves, and disk-served columns have no content-filter baseline (only
    # live serves seed it), so one bounded re-send wave follows the rejoin. The warm
    # signals are a large up_to_date count and run2 staying clearly below a full
    # re-download. (Follow-up: seeding the filter from disk serves needs NBT-vs-live
    # serialization parity verified first.)
    c1, c2 = r1["responses"]["columns"], r2["responses"]["columns"]
    if c2 >= c1:
        yield Violation("warm-rejoin", "run2 final snapshot",
                        "run-2 re-downloaded as much as run-1 (cache avoided nothing)",
                        {"run1_columns": c1, "run2_columns": c2})
    if utd < 0.5 * c1:
        yield Violation("warm-rejoin", "run2 final snapshot",
                        "run-2 up_to_date below half of run-1 columns — cache mostly cold",
                        {"run1_columns": c1, "run2_up_to_date": utd, "limit": 0.5 * c1})
    req = r2["requested_total"]
    if req < 1000:
        yield Violation("warm-rejoin", "run2 final snapshot",
                        "run-2 requested_total too low — full revalidation did not happen",
                        {"expected": ">= 1000", "actual": req})


@named_check("warm-rejoin-summary",
             ["client.summary.columns_validated", "client.summary.tiles_clean",
              "client.summary.tiles_stale", "client.summary.tiles_unknown",
              "client.summary.stamps_applied", "server.summary.stamps_entries",
              "server.summary.stamps_bytes",
              "client.responses.columns",
              "client.requested_total", "client.columns.known",
              "server.summary.requests", "server.summary.frames"])
def check_warm_rejoin_summary(ctx):
    """Region-summary warm rejoin (region-summary-sync-plan.md §8). Geometry: player
    mid-tile at chunk (16,16), distance 24 → the disc spans tiles (-1..1)² and the
    annulus holds 2112 positions; run 1 generates the beyond-base band, a t130
    save-all settles EVERY pending header (Paper's re-serve otherwise regenerates
    unpersisted chunks whose saves postdate the re-stamps — the recorded Paper red),
    then a 160s clearcache re-serves the WHOLE disc, so the cached stamps postdate
    every settled region header (a serve-then-save stamp never clears the margin —
    the scenario-design discovery; the freshness MARGIN itself is deliberately NOT
    soak-gated — no timeline can distinguish margin-15 from margin-0 without racing
    real IO, so its unit pins carry it). Run 2's one summary frame must validate the
    bulk; the player's own tile is the DESIGNED stale residue (its loaded chunks
    kick-save after every stamp). Window geometry (recorded greens): 25 tiles per
    5x5 frame = 16 with region files (the base world spans regions (-2..1)²) + 9
    never-generated — the client counts those 9 as tiles_no_region (no evidence,
    validates nothing; the final honesty review's deleted-region doctrine), so
    tiles_clean can reach at most 16 and the floor of 12 demands the real bulk.
    CALIBRATION honesty (final panel): only ~9 of the 16 region-backed tiles carry
    client STAMPS on this geometry — a tile where the client holds no stamped
    positions counts clean VACUOUSLY (applyTileValidation never falsifies an empty
    tile), so the floor of 12 really demands a stamped-tile majority; the
    columns_validated floor below carries the bulk proof. Re-derive BOTH if the
    geometry changes — do not read 12/16 as "12 of 16 validated".
    The suppression pin is SELF-SCALING (harness review MAJOR-2): requested_total
    must stay below columns.known — a whole-disc re-declare is impossible when
    validation suppresses, even across the frame-vs-cache-load race (one scan is
    capped at WANT_SET_BUDGET=800) and the revocation round a late frame can
    cause."""
    r1, r2 = ctx.final_client(1), ctx.final_client(2)
    if r1 is None or r2 is None:
        yield Violation("warm-rejoin-summary", "runs",
                        "requires client-run1.jsonl and client-run2.jsonl snapshots",
                        {"run1": "present" if r1 else "missing",
                         "run2": "present" if r2 else "missing"})
        return
    if "summary" not in r2:
        yield Violation("warm-rejoin-summary", "run2 final snapshot",
                        "client snapshot has no summary group — pre-summary client jar?",
                        {"keys": sorted(r2)})
        return
    c1 = r1["responses"]["columns"]
    if c1 < 3000:
        yield Violation("warm-rejoin-summary", "run1 final snapshot",
                        "run-1 columns below the serve+clearcache-re-serve floor — the "
                        "re-stamp premise (the 160s clearcache) did not happen",
                        {"expected": ">= 3000", "actual": c1})
    # The re-stamp premise, segment-aware (harness review m1): the POST-action segment
    # alone must have re-served ~the whole annulus — the cumulative run-1 floor above
    # would still pass with a half-completed re-serve, and a partial re-stamp turns
    # run-2 threshold reds into mysteries instead of this premise red.
    snaps1 = ctx.runs.get(1) or []
    segs1 = client_segments(snaps1)
    if len(segs1) < 2:
        yield Violation("warm-rejoin-summary", "run1",
                        "the clearcache action never split the client series — the "
                        "re-stamp premise is gone",
                        {"segments": len(segs1)})
        return
    pre1 = snaps1[segs1[0][3]]
    post1 = snaps1[segs1[-1][3]]
    d_cols = post1["responses"]["columns"] - pre1["responses"]["columns"]
    if d_cols < 1800:
        yield Violation("warm-rejoin-summary", "post-action segment",
                        "the clearcache re-serve did not cover the disc — the re-stamp "
                        "premise is partial (~2112 expected)",
                        {"expected": ">= 1800 post-action", "actual": d_cols})
    # The poison premise (final harness review MAJOR-5): the honesty leg below is only
    # a test if the t195 edit actually executed — a driver that dropped or failed the
    # setblock leaves the player tile genuinely clean and the leg would red as a
    # mystery (or worse, a future timeline edit could delete it and the leg would
    # test nothing while staying green via the kick-save shape).
    kick = next((c for c in ctx.commands if c["cmd"].startswith("kick")), None)
    poison = next((c for c in ctx.commands
                   if "setblock" in c["cmd"]
                   and (kick is None or c["wallMs"] < kick["wallMs"])), None)
    if poison is None or poison.get("ok") is not True:
        yield Violation("warm-rejoin-summary", "commands",
                        "poison premise lost — no successful pre-kick setblock command "
                        "row (the t195 edit that keeps the player tile un-validated)",
                        {"found": poison["cmd"] if poison else None,
                         "ok": poison.get("ok") if poison else None})
    s = r2["summary"]
    if s["columns_validated"] < 800:
        yield Violation("warm-rejoin-summary", "run2 final snapshot",
                        "summary validated too few columns — the exchange did not carry "
                        "the clean bulk",
                        {"expected": ">= 800", "actual": s["columns_validated"]})
    if s["tiles_clean"] < 12:
        yield Violation("warm-rejoin-summary", "run2 final snapshot",
                        "too few clean tiles — of the 16 real tiles in the 5x5 window "
                        "(see docstring geometry) the untouched bulk must validate",
                        {"expected": ">= 12", "actual": s["tiles_clean"]})
    if s.get("tiles_no_region", 0) < 5:
        yield Violation("warm-rejoin-summary", "run2 final snapshot",
                        "the never-generated window tiles did not count no_region — the "
                        "client's no-evidence skip (both sentinels validate nothing) is "
                        "not engaging (~9 expected on this geometry)",
                        {"expected": ">= 5", "actual": s.get("tiles_no_region", 0)})
    # Stamped up_to_date: wrs carries NO stamps floors (Phase B 3-Opus fold,
    # 2026-08-21 — the floor's population here is the player-tile stale residue,
    # which is RACY on every platform: the summary frame itself arms the region
    # latch's clear-side grace, which then refuses the re-ask burst the frame
    # triggers. Same-day yields: fabric 64-712, paper 0-831, folia 64 — a floor
    # of 50 against a 0-831 distribution gates a race, not the lane. The lane's
    # honest live gate is stamp-heal-prime (unmarked-region stale tiles, 100%
    # yield, floors 400/400); Paper's unit gate is the wiring source-scan in
    # PaperRegionFreshnessWiringTest. The stamps_bytes ceiling below stays — an
    # upper bound has no race exposure.)
    if s["tiles_stale"] + s["tiles_unknown"] < 1:
        yield Violation("warm-rejoin-summary", "run2 final snapshot",
                        "the poisoned player tile validated despite the t195 edit — it "
                        "MUST stay un-validated (the honesty leg). Either disposition "
                        "counts: stale (its header stamp postdates the cached stamps) or "
                        "unknown (the mark latch armed a second past the write — the "
                        "recorded Paper shape, where the unload-event mark lands after "
                        "the kick-save's header second and nothing later clears it)",
                        {"expected": "stale+unknown >= 1",
                         "stale": s["tiles_stale"], "unknown": s["tiles_unknown"]})
    req = r2["requested_total"]
    known2 = r2["columns"]["known"]
    if req >= known2:
        yield Violation("warm-rejoin-summary", "run2 final snapshot",
                        "run-2 re-declared at least its whole known disc — validation "
                        "is not suppressing asks (the feature-dead recording measured "
                        "requested == known here)",
                        {"requested_total": req, "columns.known": known2})
    if req > 3200:
        yield Violation("warm-rejoin-summary", "run2 final snapshot",
                        "run-2 requested_total above the absolute runaway ceiling",
                        {"expected": "<= 3200", "actual": req})
    c2 = r2["responses"]["columns"]
    if c2 > 400:
        yield Violation("warm-rejoin-summary", "run2 final snapshot",
                        "run-2 re-downloaded columns — a warm validated rejoin resolves "
                        "via suppression and up_to_date, not re-sends",
                        {"expected": "<= 400", "actual": c2})
    last = ctx.server_snaps[-1]
    if last["summary"]["requests"] < 2 or last["summary"]["frames"] < 2:
        yield Violation("warm-rejoin-summary", "final server snapshot",
                        "server saw fewer summary requests/frames than the two at-entry "
                        "requests the two runs must fire",
                        {"requests": last["summary"]["requests"],
                         "frames": last["summary"]["frames"]})
    # Volume ceilings (final harness review MAJOR-1): the exchange is ONE ~30-byte
    # request and ONE sub-100-byte frame per dimension entry — two runs, two entries.
    # A retry loop, a re-request storm, or an unclamped window would each blow these
    # while every floor above stays green (recorded green: 2/2/178 bytes/0 filtered);
    # slack of 2 covers a benign extra entry (e.g. a reconnect race), never a loop.
    if last["summary"]["requests"] > 4 or last["summary"]["frames"] > 4:
        yield Violation("warm-rejoin-summary", "final server snapshot",
                        "summary request/frame volume above the two-entry exchange — a "
                        "client re-request loop or server re-frame loop",
                        {"expected": "<= 4 each", "requests": last["summary"]["requests"],
                         "frames": last["summary"]["frames"]})
    if last["summary"]["bytes"] > 2048:
        yield Violation("warm-rejoin-summary", "final server snapshot",
                        "summary frame bytes above the whole-exchange ceiling — frames "
                        "are ~100 B at this window; kilobytes means a runaway window or "
                        "frame loop",
                        {"expected": "<= 2048", "actual": last["summary"]["bytes"]})
    if last["summary"]["range_filtered"] != 0:
        yield Violation("warm-rejoin-summary", "final server snapshot",
                        "summary requests were range-clamped — the client asked beyond "
                        "the server's own window (radius/center drift between the two "
                        "halves)",
                        {"expected": "0", "actual": last["summary"]["range_filtered"]})
    # The stamps lane's own ceiling (plan §9.10 — keeps the summary.bytes ceiling
    # sharp): ~10-11 B/entry over at most the run-2 re-ask population.
    if last["summary"].get("stamps_bytes", 0) > 65536:
        yield Violation("warm-rejoin-summary", "final server snapshot",
                        "stamps bytes above the whole-scenario ceiling — a stamp loop "
                        "or an unclamped population",
                        {"expected": "<= 65536", "actual": last["summary"].get("stamps_bytes", 0)})


@named_check("dirty-while-offline-summary",
             ["client.summary.columns_validated", "client.summary.tiles_stale",
              "client.summary.tiles_clean", "client.summary.tiles_unknown",
              "client.responses.up_to_date"])
def check_dirty_while_offline_summary(ctx):
    """The false-clean canary (plan §8): warm-rejoin-summary's shape plus an offline
    edit in chunk (36,-4) — tile (1,-1) — during the kick gap. The edited tile's stamp
    moves past every cached stamp, so it must NOT validate: probe 36:-4 must RISE
    (fresh re-serve) while control -4:36 (tile (-1,1), untouched) validates and stays
    exactly equal. The probe legs don't race the snapshot cadence (an up_to_date
    answer never re-stamps, so the values are stable whenever sampled) — though the
    control's equality does assume nothing else legitimately re-serves its column
    (superflat + the timeline's gamerules pin that; a control drift red means that
    premise broke before it means validation broke). This is the one check that
    would catch a false clean end to end."""
    changed, control = "36:-4", "-4:36"
    r1, r2 = ctx.final_client(1), ctx.final_client(2)
    if r1 is None or r2 is None:
        yield Violation("dirty-while-offline-summary", "runs",
                        "requires client-run1.jsonl and client-run2.jsonl snapshots",
                        {"run1": "present" if r1 else "missing",
                         "run2": "present" if r2 else "missing"})
        return
    for label, row in (("run1", r1), ("run2", r2)):
        for key in (changed, control):
            if key not in row.get("probes", {}):
                yield Violation("dirty-while-offline-summary", f"{label} final snapshot",
                                "probe position missing — -Dlss.soak.probes not staged",
                                {"probe": key, "present": sorted(row.get("probes", {}))})
                return
    p1c, p2c = r1["probes"][changed], r2["probes"][changed]
    p1k, p2k = r1["probes"][control], r2["probes"][control]
    if p1c <= 0 or p1k <= 0:
        yield Violation("dirty-while-offline-summary", "run1 final snapshot",
                        "probe columns never received in run 1 — baseline premise lost",
                        {"changed_ts": p1c, "control_ts": p1k})
        return
    if p2c <= p1c:
        yield Violation("dirty-while-offline-summary", "run2 final snapshot",
                        "edited column's timestamp did not rise — the summary validated "
                        "a tile the offline edit made stale (FALSE CLEAN)",
                        {"probe": changed, "run1_ts": p1c, "run2_ts": p2c})
    if p2k != p1k:
        yield Violation("dirty-while-offline-summary", "run2 final snapshot",
                        "control column's timestamp changed — validation did not carry "
                        "the untouched tile",
                        {"probe": control, "run1_ts": p1k, "run2_ts": p2k})
    if "summary" not in r2:
        yield Violation("dirty-while-offline-summary", "run2 final snapshot",
                        "client snapshot has no summary group — pre-summary client jar?",
                        {"keys": sorted(r2)})
        return
    s = r2["summary"]
    if s["columns_validated"] < 800:
        yield Violation("dirty-while-offline-summary", "run2 final snapshot",
                        "summary validated too few columns",
                        {"expected": ">= 800", "actual": s["columns_validated"]})
    if s["tiles_clean"] < 12:
        yield Violation("dirty-while-offline-summary", "run2 final snapshot",
                        "too few clean tiles — the untouched bulk must still validate "
                        "(same window geometry as warm-rejoin-summary: 16 real tiles, "
                        "9 no_region; two of the 16 are designed residue here)",
                        {"expected": ">= 12", "actual": s["tiles_clean"]})
    if s.get("tiles_no_region", 0) < 5:
        yield Violation("dirty-while-offline-summary", "run2 final snapshot",
                        "the never-generated window tiles did not count no_region — the "
                        "client's no-evidence skip is not engaging (~9 expected)",
                        {"expected": ">= 5", "actual": s.get("tiles_no_region", 0)})
    if s["tiles_stale"] + s["tiles_unknown"] < 2:
        yield Violation("dirty-while-offline-summary", "run2 final snapshot",
                        "expected BOTH the player tile (t195 edit) and the offline-edited "
                        "tile (1,-1) to stay un-validated — stale or latched-unknown "
                        "(see warm-rejoin-summary's honesty leg for the two shapes)",
                        {"expected": "stale+unknown >= 2",
                         "stale": s["tiles_stale"], "unknown": s["tiles_unknown"]})
    kick = next((c for c in ctx.commands if c["cmd"].startswith("kick")), None)
    if kick is None or len(ctx.joins) < 2:
        yield Violation("dirty-while-offline-summary", "commands",
                        "expected a kick command event and a second join",
                        {"kick": kick is not None, "joins": len(ctx.joins)})
        return
    join2_wall = ctx.joins[1]["wallMs"]
    # Premise guard (the dirty-while-offline pattern): the edit must land INSIDE the
    # offline gap or the canary silently degrades to a plain online-dirty test.
    for name in ("setblock", "save-all"):
        cmd = next((c for c in ctx.commands
                    if name in c["cmd"] and c["wallMs"] > kick["wallMs"]), None)
        if cmd is None or not (kick["wallMs"] < cmd["wallMs"] < join2_wall):
            yield Violation("dirty-while-offline-summary", "offline gap",
                            f"offline premise lost — {name} did not execute between the "
                            "kick and the rejoin",
                            {"cmd_wallMs": cmd["wallMs"] if cmd else None,
                             "kick": kick["wallMs"], "join2": join2_wall})
            return
    # The t195 player-tile poison premise (mirrors warm-rejoin-summary's MAJOR-5
    # guard): the stale+unknown >= 2 leg counts this edit's tile as one of its two.
    poison = next((c for c in ctx.commands
                   if "setblock" in c["cmd"] and c["wallMs"] < kick["wallMs"]), None)
    if poison is None or poison.get("ok") is not True:
        yield Violation("dirty-while-offline-summary", "commands",
                        "poison premise lost — no successful pre-kick setblock command "
                        "row (the t195 player-tile edit)",
                        {"found": poison["cmd"] if poison else None,
                         "ok": poison.get("ok") if poison else None})


@named_check("evicted-tscache-rejoin",
             ["server.disk.header_hits", "client.responses.up_to_date",
              "client.responses.columns", "server.tscache.size_per_dimension"])
def check_evicted_tscache_rejoin(ctx):
    """The P1 header rung's live gate (the P1 review's MAJOR-5; phase 2 of
    scripts/summary_evicted.sh): a fresh server boot with the persisted timestamp
    cache DELETED must answer the carried cache's whole-disc ts>0 re-declare through
    the REGION-HEADER rung — disk.header_hits floors the interception, up_to_date
    floors the client-visible outcome, and the columns band pins BOTH failure
    directions: the ceiling proves the rejoin did not degrade to the GB-class full
    re-download the rung exists to kill, and the FLOOR (harness review MAJOR-3) is
    the honesty leg — the phase-1 player tile's halt-save postdates every cached
    stamp, so its residue MUST re-download; a rung that answers up_to_date for it
    is handing out false freshness (the margin/latch broke)."""
    r1 = ctx.final_client(1)
    if r1 is None:
        yield Violation("evicted-tscache-rejoin", "run1", "no client snapshots", {})
        return
    # The eviction premise (final harness review): if the wrapper's
    # `rm world/data/lss-timestamps.bin` silently failed (or the path drifted), the
    # tscache boots WARM (~2100 entries for this disc) and every floor below passes
    # via the ORDINARY tscache rung — the header rung was never exercised and the
    # gate is theater. A cold boot's first snapshot holds only what the first few
    # seconds of serving repopulated.
    first = ctx.server_snaps[0]
    boot_ts = sum((first.get("tscache", {}).get("size_per_dimension") or {}).values())
    if boot_ts >= 1500:
        yield Violation("evicted-tscache-rejoin", "first server snapshot",
                        "tscache booted warm — the persisted timestamp cache was not "
                        "deleted, so header_hits would be vacuous",
                        {"expected": "< 1500 entries at boot", "actual": boot_ts})
    last = ctx.server_snaps[-1]
    hits = last["disk"]["header_hits"]
    if hits < 500:
        yield Violation("evicted-tscache-rejoin", "final server snapshot",
                        "header rung intercepted too few reads — the empty-tscache "
                        "resync fell through to full reads",
                        {"expected": ">= 500", "actual": hits})
    utd = r1["responses"]["up_to_date"]
    if utd < 500:
        yield Violation("evicted-tscache-rejoin", "run1 final snapshot",
                        "up_to_date too low — the rung's answers did not reach the client",
                        {"expected": ">= 500", "actual": utd})
    cols = r1["responses"]["columns"]
    if cols > 1200:
        yield Violation("evicted-tscache-rejoin", "run1 final snapshot",
                        "re-download above the poisoned-tile residue — the rung did not "
                        "bound the rejoin",
                        {"expected": "<= 1200", "actual": cols})
    if cols < 150:
        yield Violation("evicted-tscache-rejoin", "run1 final snapshot",
                        "the poisoned tile's residue did not re-download — the rung is "
                        "answering up_to_date for stamps its headers postdate (a false "
                        "freshness claim, the exact failure this gate exists for)",
                        {"expected": ">= 150", "actual": cols})


@named_check("stamp-heal-prime",
             ["client.summary.tiles_stale", "client.summary.stamps_applied",
              "server.summary.stamps_entries"])
def check_stamp_heal_prime(ctx):
    """Phase 1 of scripts/stamp_heal.sh — the heal gate's BEFORE-pin (3-Opus fold:
    an after-threshold with no pinned before proves nothing; warm-rejoin-summary's
    clearcache re-stamp erased the very inversion the heal must demonstrate). This
    timeline keeps run 1's stamps serve-then-save, so run 2's one frame must find
    the BULK stale — and the whole-disc re-ask that follows is answered up_to_date
    through the compare-backed rungs, whose stamps RATCHET the carried cache. The
    rejoin phase then proves those exact tiles heal."""
    r2 = ctx.final_client(2)
    if r2 is None:
        yield Violation("stamp-heal-prime", "run2", "no run-2 client snapshots", {})
        return
    if "summary" not in r2:
        yield Violation("stamp-heal-prime", "run2 final snapshot",
                        "client snapshot has no summary group — pre-summary client jar?",
                        {"keys": sorted(r2)})
        return
    s = r2["summary"]
    if s["tiles_stale"] + s["tiles_unknown"] < 8:
        yield Violation("stamp-heal-prime", "run2 final snapshot",
                        "the BEFORE-pin failed — the serve-then-save inversion did not "
                        "materialize (did a re-stamp sneak into the timeline? the heal "
                        "gate is vacuous without this)",
                        {"expected": "stale+unknown >= 8",
                         "stale": s["tiles_stale"], "unknown": s["tiles_unknown"]})
    if s.get("stamps_applied", 0) < 400:
        yield Violation("stamp-heal-prime", "run2 final snapshot",
                        "the stale bulk's re-asks did not ratchet — the heal premise "
                        "for phase 2 is gone",
                        {"expected": ">= 400", "actual": s.get("stamps_applied", 0)})
    last = ctx.server_snaps[-1]
    if last["summary"].get("stamps_entries", 0) < 400:
        yield Violation("stamp-heal-prime", "final server snapshot",
                        "the server produced too few stamps for a whole-disc "
                        "up_to_date sweep",
                        {"expected": ">= 400",
                         "actual": last["summary"].get("stamps_entries", 0)})


@named_check("stamp-heal-rejoin",
             ["client.summary.columns_validated", "client.summary.tiles_stale",
              "client.summary.tiles_clean", "client.requested_total"])
def check_stamp_heal_rejoin(ctx):
    """The stamped-up_to_date HEADLINE gate (stamped-up-to-date-plan.md §9.9; phase 2
    of scripts/stamp_heal.sh): stamp-heal-prime PINNED the bulk stale (its run-2
    before-pin: stale+unknown >= 8), its re-asks were answered up_to_date WITH
    verification stamps ratcheted into the carried client cache — so THIS rejoin's
    one summary frame must validate the once-stale bulk. The heal chain
    (stale -> stamped -> clean) is what distinguishes this from a plain warm
    rejoin: without the ratchet, phase 1's stale set (headers written after the
    original serves) would re-flag IDENTICALLY here, forever — and phase 1 proved
    the set existed, so a clean phase 2 is the ratchet's doing (the vacuity the
    3-Opus fold killed). Residue tolerance (re-derived live 2026-08-20, measured
    3+1): phase 1's own shutdown kick-save re-stales the spawn/player corner
    tiles AFTER the session's last stamps — the heal is measured one session
    behind BY DESIGN (one frame per session), so those tiles are the structural
    after-set, not a ratchet failure — plus one persistent no-evidence doubt
    tile (NEVER_CLEAN) at the window edge. Ceiling 5 = the structural 4 + one
    tile of variance, still far below the unhealed shape (phase 1 pinned >= 8
    BEFORE this join's shutdown-save additions); the bulk must read clean.
    The clean floor PAIRS with that ceiling: 16 real tiles - 5 admitted residue
    = 11 (the two legs were briefly inconsistent at 12/5 — a run drawing the
    admitted variance tile has clean = 11 by arithmetic, first fired live on
    the 26.1 v0.12.0 port smoke, 2026-08-21; the unhealed shape reads <= 8,
    so 11 still separates cleanly). Re-derive BOTH legs together. The no_region
    floor (>= 5, matching wrs/dwos) is the pair's PREMISE leg (panel fold
    2026-08-22): the 16-real/9-no-region geometry both legs are derived from
    must be observed, not assumed — if no_region ever shrinks, real tiles grow
    and the ceiling+floor pair silently loses its exactly-16 tightness (the
    same class of drift the 12/5 inconsistency came from), and it doubles as
    the false-clean-against-sentinel belt the sibling checks carry."""
    r1 = ctx.final_client(1)
    if r1 is None:
        yield Violation("stamp-heal-rejoin", "run1", "no client snapshots", {})
        return
    if "summary" not in r1:
        yield Violation("stamp-heal-rejoin", "run1 final snapshot",
                        "client snapshot has no summary group — pre-summary client jar?",
                        {"keys": sorted(r1)})
        return
    known = r1["columns"]["known"]
    if known < 1500:
        yield Violation("stamp-heal-rejoin", "run1 final snapshot",
                        "the carried client cache did not arrive — the heal premise "
                        "(phase 1's ratcheted stamps) is gone and this degraded to a "
                        "cold resync",
                        {"expected": "columns.known >= 1500", "actual": known})
        return
    s = r1["summary"]
    if s["tiles_no_region"] < 5:
        yield Violation("stamp-heal-rejoin", "run1 final snapshot",
                        "the window geometry premise broke — the 25-tile window is "
                        "derived as 9 no-region + 16 real, and the ceiling/floor pair "
                        "below is exactly tight against 16 (see docstring); a shrunken "
                        "no_region count means the pair no longer measures what it "
                        "was derived from (or tiles validated against a sentinel)",
                        {"expected": "tiles_no_region >= 5",
                         "actual": s["tiles_no_region"]})
    if s["tiles_stale"] + s["tiles_unknown"] > 5:
        yield Violation("stamp-heal-rejoin", "run1 final snapshot",
                        "the stale set did not heal — phase 1's stamped up_to_date "
                        "answers should have ratcheted these tiles clean (the ratchet "
                        "-> cache save -> re-declaration chain broke somewhere)",
                        {"expected": "stale+unknown <= 5",
                         "stale": s["tiles_stale"], "unknown": s["tiles_unknown"]})
    if s["tiles_clean"] < 11:
        yield Violation("stamp-heal-rejoin", "run1 final snapshot",
                        "too few clean tiles — the healed bulk must validate "
                        "(16-real/9-no-region window geometry; floor = 16 - the "
                        "stale+unknown ceiling of 5, see docstring)",
                        {"expected": ">= 11", "actual": s["tiles_clean"]})
    if s["columns_validated"] < 800:
        yield Violation("stamp-heal-rejoin", "run1 final snapshot",
                        "summary validated too few columns on the healed rejoin",
                        {"expected": ">= 800", "actual": s["columns_validated"]})
    req = r1["requested_total"]
    if req >= known:
        yield Violation("stamp-heal-rejoin", "run1 final snapshot",
                        "the healed rejoin re-declared its whole known disc — "
                        "validation is not suppressing asks",
                        {"requested_total": req, "columns.known": known})
    if req > 2000:
        yield Violation("stamp-heal-rejoin", "run1 final snapshot",
                        "re-ask volume above the healed ceiling — the residue should "
                        "be ~one player tile plus revalidation churn, not the whole "
                        "~2.1k-column disc phase 1's unhealed run 2 re-asks "
                        "(ceiling re-derived per the 3-Opus fold: 1200 sat inside "
                        "observed healthy variance)",
                        {"expected": "<= 2000", "actual": req})


@named_check("dimension-trip", ["client.dimension", "client.tracker_in_flight", "client.queued"])
def check_dimension_trip(ctx):
    snaps = ctx.runs.get(1)
    if not snaps:
        yield Violation("dimension-trip", "run1", "no client snapshots in run 1", {})
        return
    segs = client_segments(snaps)
    dims = [s[1] for s in segs]
    # The End, not the nether: the trip tests dimension-BOUNDARY mechanics, and the End
    # is the only second dimension whose terrain settles (no fluids — nether lava-ocean
    # gen borders churn content forever, like overworld noise terrain did pre-superflat).
    # Bonus: its void columns exercise the all-air generation path.
    expected = ["minecraft:overworld", "minecraft:the_end", "minecraft:overworld"]
    if dims != expected:
        yield Violation("dimension-trip", "run1",
                        "dimension sequence must be exactly overworld -> the_end -> overworld",
                        {"expected": expected, "actual": dims})
        return
    for seg, dim, lo, hi in segs:
        if (1, hi) not in ctx.quiescent_client:
            yield Violation("dimension-trip",
                            f"run1 dim={dim} wallMs[..{snaps[hi]['wallMs']}]",
                            "dimension segment does not end verified-quiescent",
                            {"segment": seg, "dimension": dim})


@named_check("dirty-broadcast", ["client.columns.dirty", "client.received_columns"])
def check_dirty_broadcast(ctx):
    cmd = next((c for c in ctx.commands if "setblock" in c["cmd"]), None)
    if cmd is None:
        yield Violation("dirty-broadcast", "commands",
                        "no command event containing 'setblock' found", {})
        return
    snaps = ctx.runs.get(1)
    if not snaps:
        yield Violation("dirty-broadcast", "run1", "no client snapshots in run 1", {})
        return
    cmd_wall = cmd["wallMs"]
    # Explicit task-mandated default when the override file omits the key.
    interval = ctx.config.get("dirtyBroadcastIntervalSeconds", DEFAULT_DIRTY_BROADCAST_SECONDS)
    before = [s for s in snaps if s["wallMs"] < cmd_wall]
    if not before:
        yield Violation("dirty-broadcast", f"setblock@{cmd_wall}",
                        "no client snapshot precedes the setblock command — cannot baseline", {})
        return
    base = before[-1]
    baseline_dirty = base["columns"]["dirty"]
    deadline = cmd_wall + 2 * interval * 1000
    # NOTE: no "dirty must visibly rise" assertion — the mark→re-request→clear transient
    # lives ~1-2s (one scan cycle), shorter than the 5s snapshot cadence, so sampling
    # missing it is the NORMAL fast-drain case. The observable outcomes below are what
    # prove the pipeline: the server broadcast something, the client re-fetched data,
    # and no dirty marks accumulated.
    server_before = [s for s in ctx.server_snaps if s["wallMs"] < cmd_wall]
    server_after = [s for s in ctx.server_snaps if cmd_wall < s["wallMs"] <= deadline + 5000]
    if server_before and server_after:
        b0 = server_before[-1]["dirty"]["broadcast_positions"]
        b1 = max(s["dirty"]["broadcast_positions"] for s in server_after)
        if b1 <= b0:
            yield Violation("dirty-broadcast", f"wallMs[{cmd_wall}..{deadline}]",
                            "server broadcast no dirty positions after setblock+save-all",
                            {"before": b0, "after": b1, "interval_s": interval})
    final = snaps[-1]
    final_dirty = final["columns"]["dirty"]
    if final_dirty > baseline_dirty:
        yield Violation("dirty-broadcast", f"final snapshot wallMs={final['wallMs']}",
                        "columns.dirty did not return to baseline (dirty columns never re-served)",
                        {"baseline": baseline_dirty, "final": final_dirty})
    if final["received_columns"] <= base["received_columns"]:
        yield Violation("dirty-broadcast", f"final snapshot wallMs={final['wallMs']}",
                        "received_columns did not increase after setblock (no real re-send)",
                        {"baseline": base["received_columns"], "final": final["received_columns"]})


def make_disc_completeness(scenario, run=1):
    """Independent disc-completeness check: at the end of a run the client must hold an
    entry (known data, a parked not-generated/empty mark, OR a session-satisfied all-air/parked
    mark) for at least every position of the scanned annulus — area accounting that does NOT
    trust the scanner's own confirmed-ring bookkeeping. A silently-orphaned position produces
    zero traffic, so conservation laws can never see it; this floor can. Extra entries
    (pre-teleport spawn-offset discs, cache reloads, in-exclusion fills) only push the count up,
    so >= is exact for the orphan class. NOTE: all-air/parked positions are counted in
    columns.satisfied (they no longer fabricate a >0 stamp into columns.known)."""
    @named_check(scenario, ["client.columns.known", "client.columns.empty",
                            "client.columns.satisfied"])
    def check(ctx):
        lod = ctx.config.get("lodDistanceChunks")
        if not isinstance(lod, int) or isinstance(lod, bool) or lod <= EXCLUSION_RADIUS:
            yield Violation("disc-completeness", f"run{run}",
                            "scenario config must pin lodDistanceChunks above the exclusion "
                            "radius for the disc-completeness check",
                            {"lodDistanceChunks": lod, "exclusion_radius": EXCLUSION_RADIUS})
            return
        fc = ctx.final_client(run)
        if fc is None:
            yield Violation("disc-completeness", f"run{run}",
                            f"no client snapshots in run {run}", {})
            return
        area = (2 * lod + 1) ** 2 - (2 * EXCLUSION_RADIUS + 1) ** 2
        cols = fc["columns"]
        known, empty = cols["known"], cols["empty"]
        satisfied = cols.get("satisfied", 0)  # all-air/parked positions with no server stamp
        total = known + empty + satisfied
        if total < area:
            yield Violation("disc-completeness", f"run{run} final snapshot",
                            "columns.known+empty+satisfied below the scanned annulus area — "
                            "positions were silently orphaned",
                            {"known": known, "empty": empty, "satisfied": satisfied,
                             "total": total, "annulus_area": area, "lodDistanceChunks": lod,
                             "exclusion_radius": EXCLUSION_RADIUS})
    check.__name__ = f"check_disc_completeness_run{run}"
    return check


@named_check("rate-limit-storm", ["server.service.superseded", "server.generation.completed"])
def check_rate_limit_storm(ctx):
    """Small fresh disc (lodDistance 12) declared at the FULL constant want-set budget.

    HISTORICAL NAME, THIRD PREMISE. (1) Pre-v17 this drove the retired rate-limit bounce
    loop. (2) Under early v17 its syncOnLoadConcurrencyLimitPerPlayer:4 shrank the whole
    want-set to 16/scan (the scan budget derived from the cap), so it pinned the
    want-set/gate coupling with a superseded<=50 ceiling — measured 0. (3) Server-owned
    generation DELETED both the knob and the coupling: the client always declares the
    constant WANT_SET_BUDGET (800), the server generates on any disk miss, and a miss that
    cannot take a generation slot is a TRANSIENT silent drop counted superseded (never a
    wire answer). The file name is kept (renaming touches six keyed tables).

    WHAT IT PINS NOW: a small fresh disc (~165 LSS positions behind vanilla's own square)
    converges through default gates with BOUNDED transient-drop churn. Misses beyond the
    generation caps (40/64) drop superseded and heal by re-declaration at 1 Hz, so
    superseded is nonzero but must stay in the low hundreds and STOP at convergence —
    unbounded growth means re-declaration is not converging (positions never satisfy).
    On a gen-ENABLED server NOT_GENERATED must never fire (the permanence guarantee is
    pinned in generation-capacity-stress where the bottleneck makes it interesting).

    MEASURED (Task 10 live run, fixed 1 Hz cadence): superseded == 370, all of it miss-drop
    churn (miss_dropped == 370 exactly; not_found 579 == gen_submitted 209 + 370 — law A5
    exact), fully quiescent tail. RE-MEASURED 2026-08-01 under the client's adaptive scan
    cadence (docs/planning/adaptive-scan-cadence-design.md): superseded == 742 — the
    converging TAIL is exactly where >=95% of a batch is answered, so its re-declarations
    arrive at up to 4 Hz and each replaces a backlog still holding the churning residue
    (the plan-review round predicted this shift; the storm PEAK is unchanged — high
    outstanding holds the cadence at 1 Hz there). Ceiling keeps the ~2x-measured
    convention against the new baseline: convergent churn is bounded by the disc (165
    positions retrying until the 40/64 gen caps drain them), while a broken healing loop
    grows superseded by ~150+/scan for the whole run (thousands)."""
    last = ctx.server_snaps[-1]
    if last["service"]["superseded"] > 1500:
        yield Violation("rate-limit-storm", "final snapshot",
                        "transient-drop churn did not converge: superseded kept growing, so "
                        "re-declared positions are not being satisfied (the disk-miss "
                        "escalation or the silent-drop healing loop is broken)",
                        {"expected": "<= 1500", "actual": last["service"]["superseded"]})
    fc = ctx.final_client(1)
    if fc is None:
        yield Violation("rate-limit-storm", "run1", "no client snapshots in run 1", {})
        return
    # Floor calibrated for lodDistance=12 / exclusion~8: vanilla's own loaded square covers
    # rings 9-10, so only the outer rings (~165 positions) route through LSS generation.
    # Disc-completeness separately proves nothing was orphaned; this floor only asserts
    # generation kept making real progress through the churn.
    if last["generation"]["completed"] <= 120:
        yield Violation("rate-limit-storm", "final snapshot",
                        "fresh-world backfill did not generate through the storm",
                        {"expected": "> 120", "actual": last["generation"]["completed"]})
    if (len(ctx.server_snaps) - 1) not in ctx.quiescent_server:
        yield Violation("rate-limit-storm", "final snapshot",
                        "last server snapshot is not verified-quiescent (storm never reconverged)",
                        {"wallMs": last["wallMs"]})


@named_check("disk-saturation", ["server.disk.saturated", "server.service.superseded"])
def check_disk_saturation(ctx):
    """One reader thread (queue capacity 33) vs a 200-slot sync flood — PREMISE FLIPPED at v17.

    Pre-v17 this asserted saturation MUST occur (disk.saturated >= 1) and surface to the
    client as rate-limited: the router submitted blindly into a full pool and the overflow
    bounced onto the wire. That was issue #32's client-visible failure mode.

    v17 puts an AbstractChunkDiskReader.hasHeadroom() gate in front of every submit: the
    router stops submitting into a full pool and RETAINS the want entry in the backlog
    instead. So the same threads:1-vs-200-slot flood must now produce ZERO saturation —
    the backlog absorbs exactly what used to bounce. This scenario is now the proof that
    the headroom gate holds under the harshest disk contention the harness can create, and
    that absorbing it still converges (the quiescent tail).

    A nonzero disk.saturated here means the gate leaked (a race, or a submit path that
    skipped the check) — the residual saturation drop is silent and counted `superseded`,
    so nothing is lost, but the gate is not doing its job.

    THIS IS ALSO THE HARNESS'S SUPERSESSION PROOF. Contention needs a want-set that outruns
    SERVICE, which is what threads:1 creates: the client declares the constant WANT_SET_BUDGET
    (800) each scan while one reader thread drains it slowly, so the retained backlog deepens
    and every 1 Hz replace drops what is still undrained. The number below is MEASURED, not
    derived (superseded=420, backlog high-water 760, pre-server-owned-generation baseline)."""
    last = ctx.server_snaps[-1]
    if last["disk"]["saturated"] != 0:
        yield Violation("disk-saturation", "final snapshot",
                        "disk.saturated fired despite the v17 headroom gate — the router "
                        "submitted into a full pool (gate leaked)",
                        {"expected": "== 0", "actual": last["disk"]["saturated"]})
    # Measured: superseded=420 with backlog high-water 760 on the first live v17 run.
    # 100 is a conservative floor, not a target: it asserts the retained backlog really did
    # build and get replaced (i.e. the absorption this scenario claims to prove actually
    # happened) rather than the flood being served comfortably.
    if last["service"]["superseded"] < 100:
        yield Violation("disk-saturation", "final snapshot",
                        "want-set supersession never built up — the saturation premise did "
                        "not hold (a 1-thread pool under an 800-entry want-set must leave "
                        "entries undrained at each replace), so the headroom gate's zero "
                        "saturation proves nothing here",
                        {"expected": ">= 100", "actual": last["service"]["superseded"]})
    if ctx.final_client(1) is None:
        yield Violation("disk-saturation", "run1", "no client snapshots in run 1", {})
        return
    if (len(ctx.server_snaps) - 1) not in ctx.quiescent_server:
        yield Violation("disk-saturation", "final snapshot",
                        "last server snapshot is not verified-quiescent (the retained "
                        "backlog never drained)", {"wallMs": last["wallMs"]})


@named_check("disk-read-gate", ["server.disk.gate_stops", "server.disk.saturated",
                               "server.service.superseded"])
def check_disk_read_gate(ctx):
    """The DiskReadGate's own scenario (disk-read-concurrency-gate-plan.md, as amended
    by the stage-B park deviation and Amendment 2's router retention): a prebuilt
    superflat annulus read at diskReaderThreads=2 with maxConcurrentDiskReads=1 —
    every column is a real region read (lodStore off, base world pre-generated).
    Permit-less misses PARK (bounded at threads*32=64) and drain on release; the
    2112-position flood far exceeds the park, so the gate must SATURATE (permits
    exhausted AND the park + permit-less in-flight work at its bound) and the router
    must answer with RETENTION — gate_stops counts one event per stopped player-pass,
    so gate_stops > 0 is the premise — while the v17 headroom gate keeps the POOL
    itself un-saturated (distinct mechanisms: saturated = pool-queue rejection at
    submit; gate_stops = router hold at admission). disk.gated survives as the
    park-overflow race-armor tier and is deliberately UNPINNED here (0 or small are
    both legitimate — it counts submissions already in flight when the park filled).
    Retained entries are replaced wholesale by each fresh declaration, so the churn
    shows as superseded: a static floor (the disk-saturation precedent) replaces the
    old `superseded >= gated` floor, which is vacuous at gated ~0."""
    last = ctx.server_snaps[-1]
    if last["disk"]["gate_stops"] <= 0:
        yield Violation("disk-read-gate", "final snapshot",
                        "disk.gate_stops never fired — the premise did not hold (K=1 "
                        "under a 2-thread pool over a full annulus of real reads must "
                        "saturate the gate and stop at least one router pass), so this "
                        "run proves nothing about retention; check the scenario config "
                        "staged maxConcurrentDiskReads=1",
                        {"expected": "> 0", "actual": last["disk"]["gate_stops"]})
    if last["disk"]["saturated"] != 0:
        yield Violation("disk-read-gate", "final snapshot",
                        "disk.saturated fired — the pool-queue headroom gate leaked "
                        "(the router retention must hold pressure before the pool "
                        "queue can reject a submit in this scenario)",
                        {"expected": "== 0", "actual": last["disk"]["saturated"]})
    # Retained entries are superseded by each fresh declaration during the flood — the
    # retention churn loop's floor. STATIC (the disk-saturation precedent): the old
    # `superseded >= gated` floor is vacuous when gated reads ~0 under retention.
    if last["service"]["superseded"] < 100:
        yield Violation("disk-read-gate", "final snapshot",
                        "superseded < 100 — the retention churn loop never ran "
                        "(retained entries must be superseded by each fresh "
                        "declaration during the flood)",
                        {"expected": ">= 100", "actual": last["service"]["superseded"]})
    if ctx.final_client(1) is None:
        yield Violation("disk-read-gate", "run1", "no client snapshots in run 1", {})
        return
    if (len(ctx.server_snaps) - 1) not in ctx.quiescent_server:
        yield Violation("disk-read-gate", "final snapshot",
                        "last server snapshot is not verified-quiescent (the retained "
                        "entries never converged — K >= 1 must always drain)",
                        {"wallMs": last["wallMs"]})


@named_check("generation-disabled", ["server.generation.submitted", "server.disk.not_found",
                                     "client.responses.not_generated", "client.received_columns"])
def check_generation_disabled(ctx):
    """enableChunkGeneration=false on a fresh world: every unloaded position resolves as a
    permanent not-generated, the client must PARK them (no infinite re-request loop — the
    shipped End-void retry-storm bug class behind an admin toggle) and end quiescent."""
    last = ctx.server_snaps[-1]
    gen = last["generation"]
    if gen["submitted"] != 0 or gen["completed"] != 0 or gen["active"] != 0:
        yield Violation("generation-disabled", "final snapshot",
                        "generation counters moved despite enableChunkGeneration=false",
                        {"submitted": gen["submitted"], "completed": gen["completed"],
                         "active": gen["active"]})
    if last["disk"]["not_found"] < 1000:
        yield Violation("generation-disabled", "final snapshot",
                        "too few disk not-found resolutions — the fresh-world premise did not hold",
                        {"expected": ">= 1000", "actual": last["disk"]["not_found"]})
    fc = ctx.final_client(1)
    if fc is None:
        yield Violation("generation-disabled", "run1", "no client snapshots in run 1", {})
        return
    if fc["responses"]["not_generated"] < 1000:
        yield Violation("generation-disabled", "run1 final snapshot",
                        "client saw too few not-generated responses",
                        {"expected": ">= 1000", "actual": fc["responses"]["not_generated"]})
    if fc["received_columns"] < 1:
        yield Violation("generation-disabled", "run1 final snapshot",
                        "no columns delivered at all — loaded-chunk serves should still work",
                        {"expected": ">= 1", "actual": fc["received_columns"]})
    if (len(ctx.server_snaps) - 1) not in ctx.quiescent_server:
        yield Violation("generation-disabled", "final snapshot",
                        "last server snapshot is not verified-quiescent — the client did not "
                        "park its not-generated positions", {"wallMs": last["wallMs"]})


@named_check("generation-capacity-stress", ["server.generation.completed",
                                            "client.responses.not_generated",
                                            "server.service.superseded"])
def check_generation_capacity_stress(ctx):
    """Global generation cap pinned to 1 while the per-player pending cap admits 8: the
    R7 repeated-miss loop runs hot through the whole backfill — a miss that cannot take
    the single generation slot is a TRANSIENT silent drop (counted superseded), the client
    re-declares at 1 Hz, the disk re-misses, and the cycle repeats until the slot frees.
    It must still converge to a complete disc.

    THE PERMANENCE GUARANTEE, PINNED NEGATIVELY: under server-owned generation a
    NOT_GENERATED answer is session-permanent on the client (only a dirty broadcast
    revives it), so on a generation-ENABLED server it must NEVER fire — the pre-inversion
    checker asserted not_generated >= 50 here (capacity bounces reached the wire); that is
    now exactly the bug class this check exists to catch. One NOT_GENERATED through a
    transient capacity bounce = one column blanked for the whole session.

    MEASURED (Task 10 live run): superseded == miss_dropped == 10276 for 143 completed
    generations at the global-cap-1 bottleneck (not_found 10419 == 143 + 10276 — law A5
    exact), not_generated == 0, fully quiescent. That is R7's worst case quantified:
    ~30 cheap region-miss re-reads/s while the single slot drains the disc. The >= 100
    floor stays far below the measurement on purpose — it only needs to prove the churn
    loop ran at all; the negative-cache revisit trigger is IO pathology, not this count."""
    last = ctx.server_snaps[-1]
    # Calibrated for lodDistance=12 / exclusion~8 at the global=1 bottleneck's ~1 gen/s:
    # vanilla's loaded square covers rings 9-10, leaving ~165 LSS-generated positions.
    if last["generation"]["completed"] < 120:
        yield Violation("generation-capacity-stress", "final snapshot",
                        "backfill did not generate through the capacity bottleneck",
                        {"expected": ">= 120", "actual": last["generation"]["completed"]})
    fc = ctx.final_client(1)
    if fc is None:
        yield Violation("generation-capacity-stress", "run1", "no client snapshots in run 1", {})
        return
    if fc["responses"]["not_generated"] != 0:
        yield Violation("generation-capacity-stress", "run1 final snapshot",
                        "NOT_GENERATED reached the wire on a generation-enabled server — a "
                        "transient outcome (capacity/timeout) leaked as the session-permanent "
                        "answer and blanked columns for the whole session",
                        {"expected": "== 0", "actual": fc["responses"]["not_generated"]})
    if last["service"]["superseded"] < 100:
        yield Violation("generation-capacity-stress", "run1 final snapshot",
                        "too little transient-drop churn — the capacity bottleneck never "
                        "produced the silent-drop/re-declare loop this scenario exists to "
                        "exercise (did the config stop creating contention?)",
                        {"expected": ">= 100", "actual": last["service"]["superseded"]})
    if (len(ctx.server_snaps) - 1) not in ctx.quiescent_server:
        yield Violation("generation-capacity-stress", "final snapshot",
                        "last server snapshot is not verified-quiescent (capacity churn "
                        "never converged)", {"wallMs": last["wallMs"]})


@named_check("bandwidth-throttle", ["server.service.queue_full", "client.received_bytes"])
def check_bandwidth_throttle(ctx):
    """256 KB/s global cap + 64-deep send queue: queue_full must fire (first-ever nonzero),
    the full disc must still stream through, and B2 (armed by the config cap) bounds the
    pacing. Unaffected by v17 (B2 is bandwidth-only and queue_full still fires as a
    send-queue breaker); only the recovery mechanism changed — a want dropped by a
    queue-full break is re-declared by the client's 1 Hz want-set, not rescued by the
    deleted 10 s in-flight timeout sweep."""
    if ("bytesPerSecondLimitGlobal" not in ctx.config
            and "mbPerSecondLimitGlobal" not in ctx.config):
        yield Violation("bandwidth-throttle", "config",
                        "scenario config must set a global bandwidth cap (either spelling)"
                        " or B2 stays unarmed", {})
    last = ctx.server_snaps[-1]
    if last["service"]["queue_full"] < 1:
        yield Violation("bandwidth-throttle", "final snapshot",
                        "service.queue_full never fired — send-queue backpressure premise "
                        "did not hold",
                        {"expected": ">= 1", "actual": last["service"]["queue_full"]})
    fc = ctx.final_client(1)
    if fc is None:
        yield Violation("bandwidth-throttle", "run1", "no client snapshots in run 1", {})
        return
    if fc["received_bytes"] < 5_000_000:
        yield Violation("bandwidth-throttle", "run1 final snapshot",
                        "too few bytes delivered — the disc did not stream through the throttle",
                        {"expected": ">= 5000000", "actual": fc["received_bytes"]})
    if (len(ctx.server_snaps) - 1) not in ctx.quiescent_server:
        yield Violation("bandwidth-throttle", "final snapshot",
                        "last server snapshot is not verified-quiescent (throttled delivery "
                        "never drained)", {"wallMs": last["wallMs"]})


def make_handshake_check(scenario, expect_enabled=True):
    """Every snapshot's server_enabled flag must match the scenario. Presence-gated:
    recordings made before the exporter emitted the field stay judgeable; every new
    recording always carries it, so on fresh data this turns the all-zero failure mode
    (handshake silently said disabled) into a named violation instead of a smear of
    floor/named-check failures."""
    @named_check(scenario, ["client.received_columns"])
    def check(ctx):
        for run, snaps in sorted(ctx.runs.items()):
            for s in snaps:
                if "server_enabled" in s and s["server_enabled"] != expect_enabled:
                    yield Violation("handshake", f"run{run} wallMs={s['wallMs']}",
                                    "client server_enabled flag does not match the scenario",
                                    {"expected": expect_enabled, "actual": s["server_enabled"]})
                    return
    check.__name__ = f"check_handshake_{scenario}"
    return check


# WI-5 (xaero-scatter-remediation-plan.md): the first-observed-save storm pins. BEFORE the
# fix a restart re-marked 449 columns against 0 suppressed (the whole loaded disc, nothing
# changed); with the chunk-load baseline the loaded disc's first saves are suppressed.
COLD_RESTART_MAX_MARKED = 50
COLD_RESTART_MIN_SUPPRESSION_RATIO = 0.9


@named_check("cold-restart-resync", ["server.service.up_to_date", "client.responses.up_to_date",
                                     "client.responses.columns", "client.requested_total",
                                     "server.dirty.marked_total", "server.dirty.suppressed_total"])
def check_cold_restart_resync(ctx):
    """Brand-new server JVM + warm client cache (both restored from the fresh-backfill
    snapshot pair): the resync must be answered from the PERSISTED world/data/
    lss-timestamps.bin loaded at startup — warm-rejoin's run 2 is served by same-process
    RAM and never makes the file load-bearing. If the load regresses, every resync
    becomes a disk read + column re-send: up_to_date collapses toward 0 and columns
    balloons to the full disc, so the warm signal is up_to_date strictly dominating
    columns. This scenario is ALSO the restart-storm gate (xaero-scatter-remediation-plan.md
    WI-5): the dirty content filter's table is per process, and until 2026-09-05 every
    chunk the new JVM merely loaded re-marked dirty at its first save (absent hash ==
    changed — 449 marked / 0 suppressed recorded here with NOTHING changed; this
    docstring used to bless that as "a bounded re-send wave"). With the chunk-load
    baseline the loaded disc's first saves are suppressed: marked stays near zero and
    the suppression ratio near one — both pinned below, absolute and in-phase."""
    last = ctx.server_snaps[-1]
    dirty = last["dirty"]
    marked, suppressed = dirty["marked_total"], dirty["suppressed_total"]
    if marked > COLD_RESTART_MAX_MARKED:
        yield Violation("cold-restart-resync", "final snapshot",
                        "the content filter re-marked the restarted server's loaded disc — "
                        "the first-observed-save storm (chunk-load baseline not seeding)",
                        {"expected": f"marked_total <= {COLD_RESTART_MAX_MARKED}",
                         "marked_total": marked, "suppressed_total": suppressed})
    observed = marked + suppressed
    if observed > 0 and suppressed / observed < COLD_RESTART_MIN_SUPPRESSION_RATIO:
        yield Violation("cold-restart-resync", "final snapshot",
                        "suppression ratio collapsed on a restart with nothing changed",
                        {"expected": f">= {COLD_RESTART_MIN_SUPPRESSION_RATIO}",
                         "actual": round(suppressed / observed, 3),
                         "marked_total": marked, "suppressed_total": suppressed})
    if last["service"]["up_to_date"] < 500:
        yield Violation("cold-restart-resync", "final snapshot",
                        "server resolved too few up_to_date — persisted lss-timestamps.bin "
                        "was not load-bearing in the new JVM",
                        {"expected": ">= 500", "actual": last["service"]["up_to_date"]})
    fc = ctx.final_client(1)
    if fc is None:
        yield Violation("cold-restart-resync", "run1", "no client snapshots in run 1", {})
        return
    utd, cols = fc["responses"]["up_to_date"], fc["responses"]["columns"]
    if utd < 500:
        yield Violation("cold-restart-resync", "run1 final snapshot",
                        "client saw too few up_to_date responses — cache pair was not warm",
                        {"expected": ">= 500", "actual": utd})
    if cols >= utd:
        yield Violation("cold-restart-resync", "run1 final snapshot",
                        "re-download dominated the resync — the cold restart behaved like a cold client",
                        {"columns": cols, "up_to_date": utd})
    if fc["requested_total"] < 1000:
        yield Violation("cold-restart-resync", "run1 final snapshot",
                        "requested_total too low — full revalidation did not happen",
                        {"expected": ">= 1000", "actual": fc["requested_total"]})
    if (len(ctx.server_snaps) - 1) not in ctx.quiescent_server:
        yield Violation("cold-restart-resync", "final snapshot",
                        "last server snapshot is not verified-quiescent (resync never converged)",
                        {"wallMs": last["wallMs"]})


@named_check("enabled-false", ["server.service.requests_received", "server.dirty.pending",
                               "client.server_enabled", "client.received_columns",
                               "client.requested_total"])
def check_enabled_false(ctx):
    """Admin/privacy kill switch: enabled=false must leave BOTH sides verifiably idle.
    The client must report server_enabled=false on every snapshot (zero-filled manager
    fields), the server must move no service/disk/generation/broadcast/bandwidth counter
    and register no player, and dirty.pending must stay 0 — the save hook is gated on
    enabled, otherwise saves would grow the tracker forever with the drain disabled."""
    snaps = ctx.runs.get(1)
    if not snaps:
        yield Violation("enabled-false", "run1", "no client snapshots in run 1", {})
        return
    for s in snaps:
        if s["server_enabled"] is not False:
            yield Violation("enabled-false", f"run1 wallMs={s['wallMs']}",
                            "client reports server_enabled true — the kill switch did not engage",
                            {"server_enabled": s["server_enabled"]})
            break
    last = ctx.server_snaps[-1]
    for path in ("service.requests_received", "service.columns_sent", "service.bytes_sent",
                 "service.up_to_date", "disk.submitted", "generation.submitted",
                 "dirty.broadcast_positions", "bandwidth.total_bytes"):
        v = get_path(last, path)
        if v != 0:
            yield Violation("enabled-false", "final snapshot",
                            f"server counter {path} moved despite enabled=false",
                            {"field": path, "expected": 0, "actual": v})
    if last["dirty"]["pending"] != 0:
        yield Violation("enabled-false", "final snapshot",
                        "dirty tracker accumulated marks with the broadcaster disabled "
                        "(unbounded growth — the save hook must be gated on enabled)",
                        {"expected": 0, "actual": last["dirty"]["pending"]})
    if last["players"]:
        yield Violation("enabled-false", "final snapshot",
                        "players registered for LOD processing despite enabled=false",
                        {"players": [p["name"] for p in last["players"]]})
    fc = snaps[-1]
    if fc["received_columns"] != 0 or fc["requested_total"] != 0:
        yield Violation("enabled-false", "run1 final snapshot",
                        "client traffic happened despite a disabled session",
                        {"received_columns": fc["received_columns"],
                         "requested_total": fc["requested_total"]})


@named_check("teleport-prune", ["server.generation.completed", "client.columns.known",
                                "client.columns.empty", "client.dimension"])
def check_teleport_prune(ctx):
    """2000-block tp = 125 chunks > pruneDistance (lodDistance 24 + 32 buffer), inside
    one dimension, fired only after the origin disc quiesced (so no request can be in
    flight to be silently range-dropped — the documented <=512-block convention is
    deliberately violated because pruning IS the scenario). The old disc must prune
    (bounded column map: the 125-chunk separation leaves zero overlap between prune
    discs), the disc must REBUILD at the new center via generation (the base world has
    no chunks out there), and the run must reconverge. The registered disc-completeness
    check supplies the >= annulus floor; this check supplies the <= bound that a prune
    regression (both discs retained, ~2x annulus) blows through."""
    # The LAST tp is the long prune teleport — every timeline also has the standard
    # origin pin (tp @a 0 150 0) a few seconds after join, which must not be the anchor.
    tps = [c for c in ctx.commands if c["cmd"].startswith("tp ")]
    tp = tps[-1] if tps else None
    if tp is not None and not any(ctx.server_snaps[i]["wallMs"] < tp["wallMs"]
                                  for i in ctx.quiescent_server):
        yield Violation("teleport-prune", "pre-teleport",
                        "premise lost — origin disc never verified-quiescent before the "
                        "long teleport (an in-flight request could be silently "
                        "range-dropped, which the laws would misread)",
                        {"tp_wallMs": tp["wallMs"]})
        return
    snaps = ctx.runs.get(1)
    if not snaps:
        yield Violation("teleport-prune", "run1", "no client snapshots in run 1", {})
        return
    segs = client_segments(snaps)
    if [s[1] for s in segs] != ["minecraft:overworld"]:
        yield Violation("teleport-prune", "run1",
                        "expected a single overworld segment (same-dimension teleport)",
                        {"segments": [s[1] for s in segs]})
        return
    lod = ctx.config.get("lodDistanceChunks")
    if not isinstance(lod, int) or isinstance(lod, bool) or lod <= EXCLUSION_RADIUS:
        yield Violation("teleport-prune", "config",
                        "scenario config must pin lodDistanceChunks above the exclusion radius",
                        {"lodDistanceChunks": lod})
        return
    area = (2 * lod + 1) ** 2 - (2 * EXCLUSION_RADIUS + 1) ** 2
    fc = snaps[-1]
    known, empty = fc["columns"]["known"], fc["columns"]["empty"]
    if known + empty > area + 400:
        yield Violation("teleport-prune", "run1 final snapshot",
                        "columns.known+empty far above one annulus — out-of-range state "
                        "was not pruned after the teleport",
                        {"known": known, "empty": empty, "total": known + empty,
                         "annulus_area": area, "limit": area + 400})
    last = ctx.server_snaps[-1]
    if last["generation"]["completed"] < 500:
        yield Violation("teleport-prune", "final snapshot",
                        "too little generation — the rescan never rebuilt the disc at the "
                        "new (ungenerated) center",
                        {"expected": ">= 500", "actual": last["generation"]["completed"]})
    if (len(ctx.server_snaps) - 1) not in ctx.quiescent_server:
        yield Violation("teleport-prune", "final snapshot",
                        "last server snapshot is not verified-quiescent (post-teleport "
                        "rebuild never converged)", {"wallMs": last["wallMs"]})


@named_check("dirty-range-filter", ["server.dirty.broadcast_positions",
                                    "client.received_columns", "client.requested_total",
                                    "client.columns.dirty"])
def check_dirty_range_filter(ctx):
    """Out-of-push-range edit: the far setblock targets chunk (-16,0) — 36 chunks from
    the post-tp player center (20,0), beyond the broadcaster's lodDistance 24 push filter
    but inside the client's prune distance 56 AND inside the original annulus, so the
    client still KNOWS the column (a regressed filter would push it, the client would
    mark+re-request it, and traffic would rise). Drain is proven by broadcast_positions
    (cumulative DRAINED positions, counted before the per-player range filter) rising;
    suppression by the client's received_columns/requested_total staying exactly flat
    until the in-range follow-up edit, which must then flow end-to-end."""
    setblocks = [c for c in ctx.commands if c["cmd"].startswith("setblock")]
    near_fl = next((c for c in ctx.commands if c["cmd"].startswith("forceload add 560")), None)
    if len(setblocks) != 2 or near_fl is None:
        yield Violation("dirty-range-filter", "commands",
                        "expected two setblock command events and the in-range forceload",
                        {"setblocks": len(setblocks), "near_forceload": near_fl is not None})
        return
    far_cmd, near_cmd = setblocks
    interval = ctx.config.get("dirtyBroadcastIntervalSeconds", DEFAULT_DIRTY_BROADCAST_SECONDS)
    snaps = ctx.runs.get(1)
    if not snaps:
        yield Violation("dirty-range-filter", "run1", "no client snapshots in run 1", {})
        return

    # Drain proof: the far edit's position is drained from the tracker (counter is
    # pre-filter) even though no player is in push range of it.
    server_before = [s for s in ctx.server_snaps if s["wallMs"] < far_cmd["wallMs"]]
    far_deadline = far_cmd["wallMs"] + (2 * interval + 5) * 1000
    server_after = [s for s in ctx.server_snaps
                    if far_cmd["wallMs"] < s["wallMs"] <= far_deadline]
    if not server_before or not server_after:
        yield Violation("dirty-range-filter", f"far setblock@{far_cmd['wallMs']}",
                        "no server snapshots bracket the out-of-range edit", {})
        return
    b0 = server_before[-1]["dirty"]["broadcast_positions"]
    b1 = max(s["dirty"]["broadcast_positions"] for s in server_after)
    if b1 <= b0:
        yield Violation("dirty-range-filter", f"wallMs[{far_cmd['wallMs']}..{far_deadline}]",
                        "out-of-range dirty position was never drained from the tracker",
                        {"before": b0, "after": b1, "interval_s": interval})

    # Suppression proof: zero client traffic between the far edit and the near edit.
    base = [s for s in snaps if s["wallMs"] <= far_cmd["wallMs"]]
    quiet = [s for s in snaps if s["wallMs"] < near_fl["wallMs"]]
    if not base or not quiet or quiet[-1]["wallMs"] <= base[-1]["wallMs"]:
        yield Violation("dirty-range-filter", "suppression window",
                        "no client snapshots span the far-edit suppression window", {})
        return
    base, quiet_end = base[-1], quiet[-1]
    if quiet_end["received_columns"] != base["received_columns"]:
        yield Violation("dirty-range-filter", f"wallMs[{base['wallMs']}..{quiet_end['wallMs']}]",
                        "out-of-range edit re-sent column data to the client",
                        {"baseline": base["received_columns"],
                         "after": quiet_end["received_columns"]})
    if quiet_end["requested_total"] != base["requested_total"]:
        yield Violation("dirty-range-filter", f"wallMs[{base['wallMs']}..{quiet_end['wallMs']}]",
                        "out-of-range edit triggered client re-requests",
                        {"baseline": base["requested_total"],
                         "after": quiet_end["requested_total"]})

    # Live proof: the in-range follow-up edit flows end-to-end (drain did not wedge it).
    final = snaps[-1]
    if final["received_columns"] <= quiet_end["received_columns"]:
        yield Violation("dirty-range-filter", f"final snapshot wallMs={final['wallMs']}",
                        "in-range follow-up edit did not re-send (dirty pipeline dead "
                        "after the out-of-range drain)",
                        {"baseline": quiet_end["received_columns"],
                         "final": final["received_columns"]})
    if final["columns"]["dirty"] > quiet_end["columns"]["dirty"]:
        yield Violation("dirty-range-filter", f"final snapshot wallMs={final['wallMs']}",
                        "columns.dirty did not return to baseline after the in-range edit",
                        {"baseline": quiet_end["columns"]["dirty"],
                         "final": final["columns"]["dirty"]})


@named_check("dirty-during-backfill", ["server.dirty.broadcast_positions",
                                       "server.service.requests_received",
                                       "client.columns.dirty"])
def check_dirty_during_backfill(ctx):
    """Edit + save-all fired while the backfill stream is still in flight (the scenario
    config throttles bytesPerSecondLimitGlobal so the disc takes ~25s to stream instead
    of ~5s): invalidation-mailbox events race live serves for nearby positions. The
    conservation laws judge the interleaving; this check pins the premise — traffic
    genuinely in flight at the edit instant — plus dirty drain and final convergence."""
    cmd = next((c for c in ctx.commands if "setblock" in c["cmd"]), None)
    if cmd is None:
        yield Violation("dirty-during-backfill", "commands",
                        "no command event containing 'setblock' found", {})
        return
    before = [s for s in ctx.server_snaps if s["wallMs"] <= cmd["wallMs"]]
    after = [s for s in ctx.server_snaps if s["wallMs"] > cmd["wallMs"]]
    if not before or not after:
        yield Violation("dirty-during-backfill", f"setblock@{cmd['wallMs']}",
                        "no server snapshots bracket the setblock command", {})
        return
    b, a = before[-1], after[0]
    in_flight = (a["service"]["requests_received"] > b["service"]["requests_received"]
                 or b["disk"]["pending"] > 0
                 or any(p["send_queue"] > 0 for p in b["players"]))
    if not in_flight:
        yield Violation("dirty-during-backfill", f"setblock@{cmd['wallMs']}",
                        "backfill already converged before the edit — the mid-backfill "
                        "premise did not hold (edit fired too late or backfill too fast)",
                        {"requests_before": b["service"]["requests_received"],
                         "requests_after": a["service"]["requests_received"],
                         "disk_pending": b["disk"]["pending"]})
    snaps = ctx.runs.get(1)
    if not snaps:
        yield Violation("dirty-during-backfill", "run1", "no client snapshots in run 1", {})
        return
    client_before = [s for s in snaps if s["wallMs"] <= cmd["wallMs"]]
    baseline_dirty = client_before[-1]["columns"]["dirty"] if client_before else 0
    final = snaps[-1]
    if final["columns"]["dirty"] > baseline_dirty:
        yield Violation("dirty-during-backfill", f"final snapshot wallMs={final['wallMs']}",
                        "columns.dirty did not drain back to its pre-edit level",
                        {"baseline": baseline_dirty, "final": final["columns"]["dirty"]})
    if (len(ctx.server_snaps) - 1) not in ctx.quiescent_server:
        yield Violation("dirty-during-backfill", "final snapshot",
                        "last server snapshot is not verified-quiescent (edit-during-"
                        "backfill never converged)",
                        {"wallMs": ctx.server_snaps[-1]["wallMs"]})


@named_check("dirty-while-offline", ["client.probes", "client.responses.up_to_date",
                                     "server.dirty.broadcast_positions"])
def check_dirty_while_offline(ctx):
    """World edited during the kick gap with ZERO players online. Per-position probes
    (-Dlss.soak.probes=20:0,-20:0) make the outcome exact: the edited column's client
    timestamp must RISE across the rejoin (fresh re-serve — its server timestamp was
    invalidated by the empty-server drain) while the untouched control column's
    timestamp stays EXACTLY equal (warm up_to_date revalidation never re-stamps a >0
    entry). Drain mechanics (settled by two live runs): the broadcaster drains on its
    interval REGARDLESS of who is connected — durability for offline players comes from
    the timestamp-cache invalidation performed at drain time, which forces the rejoin
    resync to re-serve the edited column fresh. The rising probe timestamp asserts that
    durable outcome directly; drain-counter motion during the gap is timing-dependent
    in both directions and deliberately NOT asserted."""
    changed, control = "20:0", "-20:0"
    r1, r2 = ctx.final_client(1), ctx.final_client(2)
    if r1 is None or r2 is None:
        yield Violation("dirty-while-offline", "runs",
                        "requires client-run1.jsonl and client-run2.jsonl snapshots",
                        {"run1": "present" if r1 else "missing",
                         "run2": "present" if r2 else "missing"})
        return
    for label, row in (("run1", r1), ("run2", r2)):
        for key in (changed, control):
            if key not in row["probes"]:
                yield Violation("dirty-while-offline", f"{label} final snapshot",
                                "probe position missing from probes map — "
                                "-Dlss.soak.probes not staged correctly",
                                {"probe": key, "present": sorted(row["probes"])})
                return
    p1c, p2c = r1["probes"][changed], r2["probes"][changed]
    p1k, p2k = r1["probes"][control], r2["probes"][control]
    if p1c <= 0 or p1k <= 0:
        yield Violation("dirty-while-offline", "run1 final snapshot",
                        "probe columns were never received in run 1 — baseline premise lost",
                        {"changed_ts": p1c, "control_ts": p1k})
        return
    if p2c <= p1c:
        yield Violation("dirty-while-offline", "run2 final snapshot",
                        "edited column's timestamp did not rise across the rejoin — the "
                        "offline edit was lost",
                        {"probe": changed, "run1_ts": p1c, "run2_ts": p2c})
    if p2k != p1k:
        yield Violation("dirty-while-offline", "run2 final snapshot",
                        "control column's timestamp changed — rejoin re-downloaded instead "
                        "of warm-revalidating",
                        {"probe": control, "run1_ts": p1k, "run2_ts": p2k})
    if r2["responses"]["up_to_date"] < 500:
        yield Violation("dirty-while-offline", "run2 final snapshot",
                        "run-2 up_to_date responses too low — cache was not warm",
                        {"expected": ">= 500", "actual": r2["responses"]["up_to_date"]})
    kick = next((c for c in ctx.commands if c["cmd"].startswith("kick")), None)
    if kick is None or len(ctx.joins) < 2:
        yield Violation("dirty-while-offline", "commands",
                        "expected a kick command event and a second join",
                        {"kick": kick is not None, "joins": len(ctx.joins)})
        return
    join2_wall = ctx.joins[1]["wallMs"]
    # Premise guard: the edit must actually land INSIDE the offline gap. A fast rejoin
    # (observed: 9.85s) can otherwise overtake a late-scheduled setblock, silently
    # converting this into a plain online-dirty test while staying green.
    for name in ("setblock", "save-all"):
        cmd = next((c for c in ctx.commands
                    if name in c["cmd"] and c["wallMs"] > kick["wallMs"]), None)
        if cmd is None or not (kick["wallMs"] < cmd["wallMs"] < join2_wall):
            yield Violation("dirty-while-offline", "offline gap",
                            f"offline premise lost — {name} did not execute between the "
                            "kick and the rejoin",
                            {"cmd_wallMs": cmd["wallMs"] if cmd else None,
                             "kick": kick["wallMs"], "join2": join2_wall})
            return
    before = [s for s in ctx.server_snaps if s["wallMs"] <= kick["wallMs"]]
    gap = [s for s in ctx.server_snaps if kick["wallMs"] < s["wallMs"] < join2_wall]
    if not before or not gap:
        yield Violation("dirty-while-offline", "offline gap",
                        "no server snapshots inside the kick->rejoin gap", {})
        return


@named_check("clearcache-mid-session", ["client.requested_total", "client.responses.up_to_date",
                                        "client.received_columns"])
def check_clearcache_mid_session(ctx):
    """/lss clearcache mid-session (client action hook): the client wipes its column
    state and cache files, then must cleanly re-request the whole disc. A ts<=0 request
    declares "I have nothing": the server's honest re-resolution (the open-to-LAN hole
    fix) clears its per-session done-bits and RE-SERVES the data instead of answering
    up_to_date for columns the client just declared it lost — so recovery is a full
    re-request wave resolved by a full re-download, then reconvergence to quiescence.
    (Pre-fix the wave was answered up_to_date without re-transfer, which also sealed
    real delivery losses into permanent holes.) The action row splits the client series
    into two segments (metrics are cumulative across the reset; all post-action
    expectations are DELTAS between segment-end snapshots)."""
    acts = [a for a in ctx.run_actions.get(1, []) if a["action"] == "clearcache"]
    if len(acts) != 1:
        yield Violation("clearcache-mid-session", "run1",
                        "expected exactly one clearcache action row — client hook did not fire",
                        {"actions": len(acts)})
        return
    snaps = ctx.runs.get(1)
    if not snaps:
        yield Violation("clearcache-mid-session", "run1", "no client snapshots in run 1", {})
        return
    segs = client_segments(snaps)
    if len(segs) != 2 or [s[1] for s in segs] != ["minecraft:overworld", "minecraft:overworld"]:
        yield Violation("clearcache-mid-session", "run1",
                        "client series must split into exactly two overworld segments at "
                        "the clearcache reset",
                        {"segments": [s[1] for s in segs]})
        return
    pre = snaps[segs[0][3]]
    post = snaps[segs[1][3]]
    # Deltas, not totals: client counters do NOT reset at the action row (flushCache
    # wipes column state, not metrics), so absolute floors would be satisfied by the
    # pre-action backfill alone even if the re-request wave never happened.
    d_req = post["requested_total"] - pre["requested_total"]
    if d_req < 1000:
        yield Violation("clearcache-mid-session", "post-action final snapshot",
                        "full re-request wave did not happen after clearcache",
                        {"expected": ">= 1000 post-action", "actual": d_req})
    delta_recv = post["received_columns"] - pre["received_columns"]
    if delta_recv < 500:
        yield Violation("clearcache-mid-session", "post-action segment",
                        "clearcache must trigger a full re-download (the client declared "
                        "it has nothing; up_to_date answers would mask delivery losses)",
                        {"expected": ">= 500 post-action", "actual": delta_recv})
    d_utd = post["responses"]["up_to_date"] - pre["responses"]["up_to_date"]
    if d_utd > delta_recv:
        yield Violation("clearcache-mid-session", "post-action segment",
                        "post-clearcache re-requests were mostly answered up_to_date — "
                        "the session done-bit is overriding the honest re-resolution",
                        {"up_to_date_delta": d_utd, "received_delta": delta_recv})
    if (len(ctx.server_snaps) - 1) not in ctx.quiescent_server:
        yield Violation("clearcache-mid-session", "final snapshot",
                        "last server snapshot is not verified-quiescent (post-clearcache "
                        "revalidation never converged)",
                        {"wallMs": ctx.server_snaps[-1]["wallMs"]})


def make_store_second_join(scenario):
    """Factory for store-second-join (lodStore=full — the SQLite store alone since the
    Phase 2 delete-the-tier verdict). Kept a factory because the check is written against
    the counters, not the engine: store.hits counts whichever store answers, so the old
    lodStore=memory twin (retired 2026-08-02) shared it verbatim."""
    @named_check(scenario, ["client.requested_total", "client.received_columns",
                                       "server.store.hits", "server.store.deposits",
                                       "server.store.errors", "server.disk.submitted"])
    def check(ctx):
        """The Phase 1 LOD-store gate (lod-store-implementation-plan.md §5): the backfill leg
        populates the memory tier through delivery-path deposits; the mid-session clearcache
        forces the full ts<=0 re-declaration of the disc, and the re-serve wave must come
        from the STORE — store.hits carries it, disk.submitted stays nearly still — with
        byte-identical content (server probe hashes stable across the store-served leg) and
        zero contained store failures. The loaded disc near the player resolves via the probe
        rung on BOTH legs (never deposited, never store-served), so the floors are sized to
        the disk-served annulus, not the whole disc."""
        acts = [a for a in ctx.run_actions.get(1, []) if a["action"] == "clearcache"]
        if len(acts) != 1:
            yield Violation(scenario, "run1",
                            "expected exactly one clearcache action row — client hook did not fire",
                            {"actions": len(acts)})
            return
        snaps = ctx.runs.get(1)
        if not snaps:
            yield Violation(scenario, "run1", "no client snapshots in run 1", {})
            return
        segs = client_segments(snaps)
        if len(segs) != 2:
            yield Violation(scenario, "run1",
                            "client series must split into two segments at the clearcache reset",
                            {"segments": [s[1] for s in segs]})
            return
        pre_cli = snaps[segs[0][3]]
        post_cli = snaps[segs[1][3]]
        d_recv = post_cli["received_columns"] - pre_cli["received_columns"]
        if d_recv < 500:
            yield Violation(scenario, "post-action segment",
                            "the clearcache re-download wave did not happen — nothing for the "
                            "store to serve", {"expected": ">= 500", "actual": d_recv})
            return
        action_wall = acts[0]["wallMs"]
        srv_pre = None
        for s in ctx.server_snaps:
            if s["wallMs"] <= action_wall:
                srv_pre = s
            else:
                break
        srv_final = ctx.server_snaps[-1] if ctx.server_snaps else None
        if srv_pre is None or srv_final is None or srv_pre is srv_final:
            yield Violation(scenario, "server series",
                            "need server snapshots on both sides of the action", {})
            return
        deposits_pre = get_path(srv_pre, "store.deposits")
        if deposits_pre < 800:
            yield Violation(scenario, "populate leg",
                            "the backfill leg deposited too little — the delivery-path "
                            "deposit choke point is not firing",
                            {"expected": ">= 800 before the action", "actual": deposits_pre})
        hits_delta = get_path(srv_final, "store.hits") - get_path(srv_pre, "store.hits")
        if hits_delta < 800:
            yield Violation(scenario, "re-serve leg",
                            "the re-serve wave was not served from the store",
                            {"expected": ">= 800 store hits after the action", "actual": hits_delta})
        disk_delta = get_path(srv_final, "disk.submitted") - get_path(srv_pre, "disk.submitted")
        ceiling = max(200, int(0.25 * d_recv))
        if disk_delta > ceiling:
            yield Violation(scenario, "re-serve leg",
                            "the store-warm re-serve leg re-read region files — the store "
                            "rung is not intercepting the second join",
                            {"disk.submitted delta": disk_delta, "ceiling": ceiling})
        errors = get_path(srv_final, "store.errors")
        if errors:
            yield Violation(scenario, "whole run",
                            "contained store failures fired", {"store.errors": errors})
        # Byte parity: the armed probes were NBT-served on the populate leg and store-served
        # on the re-serve leg; recordServedColumnBytes hashes the exact wire bytes each time,
        # so a stable hash across the action IS byte identity.
        pre_hashes = srv_pre.get("probe_hashes") or {}
        final_hashes = srv_final.get("probe_hashes") or {}
        proven = 0
        for token, final_hash in final_hashes.items():
            pre_hash = pre_hashes.get(token)
            if pre_hash in (None, -1) or final_hash == -1:
                continue  # probe not served on one leg — parity unprovable for it
            proven += 1
            if final_hash != pre_hash:
                yield Violation(scenario, "probe " + token,
                                "store-served bytes differ from the NBT-served bytes — the "
                                "store round trip is not byte-exact",
                                {"pre": pre_hash, "post": final_hash})
        if proven == 0:
            # An armed-but-all(-1) map means the recorder never fired (the vacuous-parity
            # hole the first live run exposed) — the parity leg must never pass silently.
            yield Violation(scenario, "probes",
                            "no probe proved byte parity — server probes unarmed "
                            "(-Psoak.probes / SERVER_EXTRA_ARGS) or the serve-path recorder "
                            "(SoakProbeBridge) is not firing",
                            {"pre": pre_hashes, "final": final_hashes})
        if ctx.server_snaps and (len(ctx.server_snaps) - 1) not in ctx.quiescent_server:
            yield Violation(scenario, "final snapshot",
                            "last server snapshot is not verified-quiescent (the store-warm "
                            "leg never converged)",
                            {"wallMs": ctx.server_snaps[-1]["wallMs"]})
    return check


check_store_second_join = make_store_second_join("store-second-join")


@named_check("store-migration-join", ["client.received_columns", "server.store.hits",
                                      "server.store.errors", "server.disk.submitted"])
def check_store_migration_join(ctx):
    """The C6 store-migration variant (XVER §9): the staged store was DOWNGRADED to the
    released v0.9.x shape at SERVER_STARTING (schema 3, FNV hashes, native bodies), the
    boot ran the REAL lazy 3->4 upgrade, and this cold-cache join must be served warm
    from 19-rows through the inverse translator WHILE the background walk migrates.
    store.hits carries the warm serve (a wrong downgrade or a broken rung FNV-fails or
    errors out -> zero hits); disk stays nearly still; store.errors must be exactly 0
    (every serve validates the rewritten hashes -- one corrupt row = one error).
    Migration COMPLETION is asserted by the wrapper (store_migration_gate.sh) from the
    server log; snapshots carry no walk gauge."""
    snaps = ctx.runs.get(1)
    if not snaps:
        yield Violation("store-migration-join", "run1", "no client snapshots", {})
        return
    final_cli = snaps[-1]
    recv = final_cli.get("received_columns", 0)
    if recv < 500:
        yield Violation("store-migration-join", "run1",
                        "the cold-cache join never downloaded the disc -- nothing for the "
                        "19-row rung to serve", {"expected": ">= 500", "actual": recv})
        return
    srv_final = ctx.server_snaps[-1] if ctx.server_snaps else None
    if srv_final is None:
        yield Violation("store-migration-join", "server series", "no server snapshots", {})
        return
    hits = get_path(srv_final, "store.hits")
    if hits < 800:
        yield Violation("store-migration-join", "warm serve",
                        "the downgraded store did not serve the join -- the 19-row rung "
                        "(or the lazy upgrade that precedes it) is broken",
                        {"expected": ">= 800 store hits", "actual": hits})
    disk = get_path(srv_final, "disk.submitted")
    ceiling = max(200, int(0.25 * recv))
    if disk > ceiling:
        yield Violation("store-migration-join", "warm serve",
                        "the join re-read region files -- 19-rows are not intercepting",
                        {"disk.submitted": disk, "ceiling": ceiling})
    errors = get_path(srv_final, "store.errors")
    if errors:
        yield Violation("store-migration-join", "whole run",
                        "contained store failures fired -- on this scenario every one is "
                        "a downgrade/translation/hash defect, never noise",
                        {"store.errors": errors})
    if ctx.server_snaps and (len(ctx.server_snaps) - 1) not in ctx.quiescent_server:
        yield Violation("store-migration-join", "final snapshot",
                        "last server snapshot is not verified-quiescent",
                        {"wallMs": ctx.server_snaps[-1]["wallMs"]})


@named_check("paper-store-unfired-event", ["server.store.hits", "server.store.errors",
                                           "server.store.deposits", "client.received_columns"])
def check_paper_store_unfired_event(ctx):
    """The Paper staleness-bound gate (lod-store-implementation-plan.md Phase 2): a
    console setblock fires no Bukkit event, so the store's stale row for the edited
    column can only be culled by the periodic resweep (lodStoreResweepSeconds) — within
    ≈ one save + one sweep cycle. The timeline saves at 75 s and clearcaches at 120 s
    (≥ 2 sweep cycles later): the re-declared edited probe MUST come back with DIFFERENT
    bytes (stale would be byte-identical), the control probe byte-identical, the sweep
    must be OBSERVED (store.sweep_drops moved), and the re-serve wave must still be
    store-served with zero contained failures."""
    acts = [a for a in ctx.run_actions.get(1, []) if a["action"] == "clearcache"]
    if len(acts) != 1:
        yield Violation("paper-store-unfired-event", "run1",
                        "expected exactly one clearcache action row — client hook did "
                        "not fire", {"actions": len(acts)})
        return
    snaps = ctx.runs.get(1)
    if not snaps:
        yield Violation("paper-store-unfired-event", "run1", "no client snapshots", {})
        return
    segs = client_segments(snaps)
    if len(segs) != 2:
        yield Violation("paper-store-unfired-event", "run1",
                        "client series must split into two segments at the clearcache "
                        "reset", {"segments": [s[1] for s in segs]})
        return
    d_recv = snaps[segs[1][3]]["received_columns"] - snaps[segs[0][3]]["received_columns"]
    if d_recv < 500:
        yield Violation("paper-store-unfired-event", "post-action segment",
                        "the clearcache re-download wave did not happen",
                        {"expected": ">= 500", "actual": d_recv})
        return
    action_wall = acts[0]["wallMs"]
    srv_pre = None
    for s in ctx.server_snaps:
        if s["wallMs"] <= action_wall:
            srv_pre = s
        else:
            break
    srv_final = ctx.server_snaps[-1] if ctx.server_snaps else None
    if srv_pre is None or srv_final is None or srv_pre is srv_final:
        yield Violation("paper-store-unfired-event", "server series",
                        "need server snapshots on both sides of the action", {})
        return
    # Premise guard (C6-hardened to the EDIT WINDOW): the edit must be INVISIBLE to
    # the event-driven dirty pipeline — that is what makes the resweep the only healer.
    # A neighbor-reaction event firing on the setblock (seen live: glowstone tripping a
    # configured event racily) marks the column dirty, the broadcast re-serves it fresh
    # BEFORE the action, and the pre/final hash comparison then measures the DIRTY
    # pipeline, not the staleness bound. The original bar was whole-run flatness, but a
    # rerolled base world produces AMBIENT settle marks (gravity blocks firing
    # EntityChangeBlockEvent minutes away from the edit — first seen 2026-08-08 on the
    # regenerated base-paper world) that say nothing about whether THIS edit fired.
    # The window is [last snapshot before the setblock .. first snapshot after
    # save-all + 10 s] (one 5 s broadcast interval + grace; an edit-fired mark lands
    # within a second of the command). Position-blind counters cannot rule out an
    # ambient mark landing exactly on the edited chunk inside the window — accepted
    # residual, one improbable chunk out of the disc; outside the window ambient marks
    # are tolerated by design.
    setblocks = [c for c in ctx.commands if "setblock" in c.get("cmd", "")]
    saves = [c for c in ctx.commands if "save-all" in c.get("cmd", "")]
    if len(setblocks) != 1 or not saves:
        yield Violation("paper-store-unfired-event", "premise",
                        "timeline shape drifted — need exactly one setblock and a "
                        "save-all command row to window the premise",
                        {"setblocks": len(setblocks), "saves": len(saves)})
        return
    edit_wall = setblocks[0]["wallMs"]
    window_end = saves[-1]["wallMs"] + 10_000
    pre_marked = 0
    for s in ctx.server_snaps:
        if s["wallMs"] <= edit_wall:
            pre_marked = get_path(s, "dirty.marked_total")
        else:
            break
    post_marked = get_path(srv_final, "dirty.marked_total")
    for s in ctx.server_snaps:
        if s["wallMs"] >= window_end:
            post_marked = get_path(s, "dirty.marked_total")
            break
    if post_marked - pre_marked != 0:
        yield Violation("paper-store-unfired-event", "premise",
                        "the edit fired a configured Bukkit event (dirty.marked_total "
                        "moved inside the edit window) — the scenario measured the "
                        "dirty pipeline, not the unfired-event staleness bound; use an "
                        "inert edit (in-ground bedrock replace)",
                        {"window_delta": post_marked - pre_marked,
                         "pre": pre_marked, "post": post_marked,
                         "whole_run": get_path(srv_final, "dirty.marked_total")})
        return
    if get_path(srv_final, "store.sweep_drops") < 1:
        yield Violation("paper-store-unfired-event", "resweep",
                        "store.sweep_drops never moved — the periodic resweep did not "
                        "cull the un-evented edit (staleness bound unproven)",
                        {"store.sweep_drops": get_path(srv_final, "store.sweep_drops")})
    hits_delta = get_path(srv_final, "store.hits") - get_path(srv_pre, "store.hits")
    if hits_delta < 800:
        yield Violation("paper-store-unfired-event", "re-serve leg",
                        "the re-serve wave was not served from the store",
                        {"expected": ">= 800 store hits after the action",
                         "actual": hits_delta})
    errors = get_path(srv_final, "store.errors")
    if errors:
        yield Violation("paper-store-unfired-event", "whole run",
                        "contained store failures fired", {"store.errors": errors})
    pre_hashes = srv_pre.get("probe_hashes") or {}
    final_hashes = srv_final.get("probe_hashes") or {}
    edited_pre, edited_final = pre_hashes.get("20:0"), final_hashes.get("20:0")
    control_pre, control_final = pre_hashes.get("-20:0"), final_hashes.get("-20:0")
    if None in (edited_pre, edited_final, control_pre, control_final) \
            or -1 in (edited_pre, edited_final, control_pre, control_final):
        yield Violation("paper-store-unfired-event", "probes",
                        "probes unarmed or unserved on a leg — the staleness comparison "
                        "is void", {"pre": pre_hashes, "final": final_hashes})
        return
    if edited_final == edited_pre:
        yield Violation("paper-store-unfired-event", "probe 20:0",
                        "the edited column re-served the PRE-EDIT bytes after the "
                        "resweep bound — the store is serving stale data past its "
                        "documented staleness window",
                        {"pre": edited_pre, "final": edited_final})
    if control_final != control_pre:
        yield Violation("paper-store-unfired-event", "probe -20:0",
                        "the untouched control column's bytes drifted — store re-serve "
                        "is not byte-exact", {"pre": control_pre, "final": control_final})
    if (len(ctx.server_snaps) - 1) not in ctx.quiescent_server:
        yield Violation("paper-store-unfired-event", "final snapshot",
                        "last server snapshot is not verified-quiescent",
                        {"wallMs": srv_final["wallMs"]})


@named_check("store-save-storm", ["server.store.deposits", "server.store.deposit_drops",
                                  "server.store.errors", "server.store.misses",
                                  "client.received_columns"])
def check_store_save_storm(ctx):
    """The Phase 3 gate (lod-store-implementation-plan.md), recalibrated for the
    DELETE-only save hook (4-agent round R2-M2: hook deposits provably never survived
    the fan-out their own mark triggers, so the hook no longer deposits). The filter
    must SUPPRESS the storm's metadata re-saves (deposit_drops == 0 — the queue never
    even pressures), the hook must NOT deposit (deposits <= serve-path misses: every
    surviving deposit is delivery-path, one miss upstream of each — a positive margin
    means the doomed write-through crept back), the hook must still PROCESS the wave
    (marked_total), the mid-storm edit must re-serve fresh through the dirty pipeline,
    and after the clearcache the edited column must serve fresh (probe 20:0 freshness,
    disk re-read ceiling = the first-observation invalidation churn)."""
    acts = [a for a in ctx.run_actions.get(1, []) if a["action"] == "clearcache"]
    if len(acts) != 1:
        yield Violation("store-save-storm", "run1",
                        "expected exactly one clearcache action row", {"actions": len(acts)})
        return
    snaps = ctx.runs.get(1)
    segs = client_segments(snaps) if snaps else []
    if len(segs) != 2:
        yield Violation("store-save-storm", "run1",
                        "client series must split at the clearcache reset",
                        {"segments": [s[1] for s in segs]})
        return
    d_recv = snaps[segs[1][3]]["received_columns"] - snaps[segs[0][3]]["received_columns"]
    if d_recv < 500:
        yield Violation("store-save-storm", "post-action segment",
                        "the clearcache re-download wave did not happen",
                        {"expected": ">= 500", "actual": d_recv})
        return
    action_wall = acts[0]["wallMs"]
    srv_pre = None
    for s in ctx.server_snaps:
        if s["wallMs"] <= action_wall:
            srv_pre = s
        else:
            break
    srv_final = ctx.server_snaps[-1] if ctx.server_snaps else None
    if srv_pre is None or srv_final is None or srv_pre is srv_final:
        yield Violation("store-save-storm", "server series",
                        "need server snapshots on both sides of the action", {})
        return
    drops = get_path(srv_final, "store.deposit_drops")
    if drops:
        yield Violation("store-save-storm", "whole run",
                        "save-hook deposits were shed", {"store.deposit_drops": drops})
    errors = get_path(srv_final, "store.errors")
    if errors:
        yield Violation("store-save-storm", "whole run",
                        "contained store failures fired", {"store.errors": errors})
    # DELETE-ONLY pin (R2-M2 regression guard, inverting the old HOOK-ALIVE margin):
    # with the hook depositing nothing, every applied deposit is delivery-path and has
    # exactly one store miss upstream (store hits never re-deposit, probe serves never
    # deposit, backfill is off in this scenario) — so cumulative deposits <= misses.
    # A positive margin means save-hook write-through deposits crept back in; they are
    # provably dead on arrival (the fan-out tombstones them) and pure queue pressure.
    margin = get_path(srv_final, "store.deposits") - get_path(srv_final, "store.misses")
    if margin > 0:
        yield Violation("store-save-storm", "whole run",
                        "deposits exceed serve-path misses — a non-delivery deposit "
                        "path (the retired save-hook write-through?) is live again",
                        {"deposits - misses": margin, "expected": "<= 0"})
    # HOOK-AT-SCALE pin (recalibrated on live data: vanilla save-all SKIPS unchanged
    # chunks entirely — suppressed_total stayed 0 through a 10x storm because nothing
    # re-saved, so a suppression floor is unforceable from a scripted timeline; filter
    # suppression itself is unit-pinned and live-pinned by check_dirty_resave_quiet).
    # What the first save-all DOES force is the loaded set's first-observation wave
    # through the hook — marked_total >= 200 proves the hook processed it at scale.
    marked = get_path(srv_final, "dirty.marked_total")
    if marked < 200:
        yield Violation("store-save-storm", "storm window",
                        "the first save-all's first-observation wave never went "
                        "through the hook (marked_total too low — hook dead or storm "
                        "never ran)", {"dirty.marked_total": marked, "expected": ">= 200"})
    hits_delta = get_path(srv_final, "store.hits") - get_path(srv_pre, "store.hits")
    if hits_delta < 800:
        yield Violation("store-save-storm", "re-serve leg",
                        "the post-clearcache wave was not served from the store",
                        {"expected": ">= 800", "actual": hits_delta})
    disk_delta = get_path(srv_final, "disk.submitted") - get_path(srv_pre, "disk.submitted")
    if disk_delta > 50:
        # <= 50, not a percentage ceiling (a 25% ceiling let the delete+re-read corner
        # pass wholesale; measured churn is ~25). The allowed reads are the session's
        # first-observation invalidation churn: the first save-all marks the loaded set
        # (first observations), the dirty->store fan-out (kept ON — the Phase 3 engine
        # review showed it is the correctness backstop against stale in-flight reads
        # overwriting hook deposits) tombstones those rows, and the subset that has
        # UNLOADED by clearcache time legitimately re-reads once (measured 25 of ~441;
        # the loaded majority probe-serves). Wholesale re-reads (hundreds) still red.
        yield Violation("store-save-storm", "re-serve leg",
                        "the re-serve leg read region files beyond the session's "
                        "first-observation invalidation churn",
                        {"disk.submitted delta": disk_delta, "expected": "<= 50"})
    # Probe story: earliest hash = pre-edit; srv_pre = after the edit's dirty re-serve;
    # final = the post-clearcache store serve.
    edited_first = None
    for s in ctx.server_snaps:
        h = (s.get("probe_hashes") or {}).get("20:0")
        if h is not None and h != -1:
            edited_first = h
            break
    pre_hashes = srv_pre.get("probe_hashes") or {}
    final_hashes = srv_final.get("probe_hashes") or {}
    edited_pre = pre_hashes.get("20:0")
    edited_final = final_hashes.get("20:0")
    control_first = None
    for s in ctx.server_snaps:
        h = (s.get("probe_hashes") or {}).get("-20:0")
        if h is not None and h != -1:
            control_first = h
            break
    control_final = final_hashes.get("-20:0")
    if None in (edited_first, edited_pre, edited_final, control_first, control_final) \
            or -1 in (edited_pre, edited_final, control_final):
        yield Violation("store-save-storm", "probes",
                        "probes unarmed or unserved on a leg",
                        {"first": edited_first, "pre": pre_hashes, "final": final_hashes})
        return
    if edited_pre == edited_first:
        yield Violation("store-save-storm", "probe 20:0",
                        "the mid-storm edit never re-served fresh before the action "
                        "(dirty pipeline dead?)",
                        {"pre-edit": edited_first, "pre-action": edited_pre})
    if edited_final == edited_first:
        # Freshness, NOT byte-identity (recalibrated on live data): with the fan-out
        # ON, the edited row is tombstoned at the drain and the post-clearcache serve
        # is an NBT re-read of the SAVED region — vanilla re-palettizes containers on
        # save (first-appearance order), so those bytes legitimately differ from the
        # LIVE re-serve while carrying identical content (the documented disk/live
        # parity caveat). The invariant that must hold: the final serve is not the
        # PRE-edit bytes.
        yield Violation("store-save-storm", "probe 20:0",
                        "the post-clearcache serve returned the PRE-edit bytes",
                        {"pre-edit": edited_first, "final": edited_final})
    if control_final != control_first:
        yield Violation("store-save-storm", "probe -20:0",
                        "the untouched control column's bytes drifted",
                        {"first": control_first, "final": control_final})
    if (len(ctx.server_snaps) - 1) not in ctx.quiescent_server:
        yield Violation("store-save-storm", "final snapshot",
                        "last server snapshot is not verified-quiescent",
                        {"wallMs": srv_final["wallMs"]})


@named_check("store-offline-populate", ["server.store.deposits", "server.store.errors"])
def check_store_offline_populate(ctx):
    """Phase 1 of scripts/store_offline_edit.sh: the backfill must actually charge the
    store (deposit floor), with zero contained store failures, and BOTH armed probes
    must have been served — their hashes are the cross-phase baseline the wrapper
    compares the verify phase against, so an unserved probe here voids the whole
    offline-edit experiment."""
    last = ctx.server_snaps[-1] if ctx.server_snaps else None
    if last is None:
        yield Violation("store-offline-populate", "server series", "no server snapshots", {})
        return
    deposits = get_path(last, "store.deposits")
    if deposits < 800:
        yield Violation("store-offline-populate", "whole run",
                        "the populate leg deposited too little — nothing for the verify "
                        "phase to serve", {"expected": ">= 800", "actual": deposits})
    errors = get_path(last, "store.errors")
    if errors:
        yield Violation("store-offline-populate", "whole run",
                        "contained store failures fired", {"store.errors": errors})
    hashes = last.get("probe_hashes") or {}
    unserved = sorted(t for t, h in hashes.items() if h == -1)
    if not hashes or unserved:
        yield Violation("store-offline-populate", "probes",
                        "cross-phase baseline probes missing or unserved — arm "
                        "-Psoak.probes=20:0,-20:0 on the server",
                        {"hashes": hashes, "unserved": unserved})


@named_check("store-offline-mutate", ["server.service.requests_received",
                                      "server.store.deposits"])
def check_store_offline_mutate(ctx):
    """Phase 2: a REAL edit reaches the region file while LSS is disabled end-to-end —
    what makes the edit 'offline' from the store's point of view. The forceload-add,
    setblock, and save-all must all be acknowledged (the forceload is LOAD-BEARING: a
    setblock into an unloaded chunk silently no-ops with a dispatch-level ok — the exact
    false-green the first live run produced), the client must see server_enabled=false
    on every snapshot, and no LSS service/disk/store counter may move."""
    fl = next((c for c in ctx.commands if c["cmd"].startswith("forceload add")), None)
    if fl is None or not fl.get("ok"):
        yield Violation("store-offline-mutate", "timeline",
                        "the forceload-add did not run — the setblock would silently "
                        "no-op into an unloaded chunk", {"row": fl})
    sb = next((c for c in ctx.commands if c["cmd"].startswith("setblock")), None)
    if sb is None or not sb.get("ok"):
        yield Violation("store-offline-mutate", "timeline",
                        "the offline edit's setblock did not run/acknowledge",
                        {"row": sb})
    sa = next((c for c in ctx.commands if c["cmd"].startswith("save-all")), None)
    if sa is None or not sa.get("ok"):
        yield Violation("store-offline-mutate", "timeline",
                        "the save-all flushing the edit did not run/acknowledge",
                        {"row": sa})
    for s in ctx.runs.get(1, []):
        if s["server_enabled"] is not False:
            yield Violation("store-offline-mutate", f"run1 wallMs={s['wallMs']}",
                            "client reports server_enabled true — the phase was not "
                            "invisible to LSS", {"server_enabled": s["server_enabled"]})
            break
    last = ctx.server_snaps[-1] if ctx.server_snaps else None
    if last is None:
        yield Violation("store-offline-mutate", "server series", "no server snapshots", {})
        return
    for path in ("service.requests_received", "service.columns_sent", "disk.submitted",
                 "store.deposits", "store.hits"):
        v = get_path(last, path)
        if v != 0:
            yield Violation("store-offline-mutate", "final snapshot",
                            f"LSS counter {path} moved during the offline-edit phase",
                            {"field": path, "expected": 0, "actual": v})


@named_check("store-offline-verify", ["server.store.hits", "server.store.errors",
                                      "server.disk.submitted", "client.received_columns"])
def check_store_offline_verify(ctx):
    """Phase 3: a cold-cache client joins the carried world + store after the offline
    edit. The startup sweep must have run cleanly against the mutated region (errors 0),
    the unedited annulus must re-serve from the STORE (hits floor + disk-read ceiling —
    the sweep must not have over-dropped), both probes must serve (the WRAPPER compares
    hashes across phases: edited probe differs, control probe byte-identical), and the
    run must end verified-quiescent."""
    last = ctx.server_snaps[-1] if ctx.server_snaps else None
    snaps = ctx.runs.get(1)
    if last is None or not snaps:
        yield Violation("store-offline-verify", "series",
                        "need both server and client run-1 snapshots", {})
        return
    hits = get_path(last, "store.hits")
    if hits < 800:
        yield Violation("store-offline-verify", "whole run",
                        "the carried store did not serve the re-join — the startup "
                        "sweep over-dropped or the store did not survive the restart",
                        {"expected": ">= 800 store hits", "actual": hits})
    errors = get_path(last, "store.errors")
    if errors:
        yield Violation("store-offline-verify", "whole run",
                        "contained store failures fired", {"store.errors": errors})
    recv = snaps[-1]["received_columns"]
    disk = get_path(last, "disk.submitted")
    ceiling = max(250, int(0.25 * recv))
    if disk > ceiling:
        yield Violation("store-offline-verify", "whole run",
                        "the verify leg re-read region files wholesale — the sweep "
                        "dropped far more than the edit justifies",
                        {"disk.submitted": disk, "ceiling": ceiling})
    hashes = last.get("probe_hashes") or {}
    unserved = sorted(t for t, h in hashes.items() if h == -1)
    if not hashes or unserved:
        yield Violation("store-offline-verify", "probes",
                        "cross-phase comparison probes missing or unserved",
                        {"hashes": hashes, "unserved": unserved})
    if (len(ctx.server_snaps) - 1) not in ctx.quiescent_server:
        yield Violation("store-offline-verify", "final snapshot",
                        "last server snapshot is not verified-quiescent",
                        {"wallMs": last["wallMs"]})


@named_check("dimension-rejoin-warm", ["client.dimension", "client.responses.up_to_date"])
def check_dimension_rejoin_warm(ctx):
    """Kick while in the End; the rejoining cold client must land back in the End
    (vanilla playerdata), warm-resync it from minecraft_the_end.bin, then return and
    warm-resync the overworld from minecraft_overworld.bin — the per-dimension cache
    files (End map flushed by the kick-disconnect save) each become load-bearing."""
    r1, r2 = ctx.runs.get(1), ctx.runs.get(2)
    if not r1 or not r2:
        yield Violation("dimension-rejoin-warm", "runs",
                        "requires client-run1.jsonl and client-run2.jsonl snapshots",
                        {"run1": "present" if r1 else "missing",
                         "run2": "present" if r2 else "missing"})
        return
    dims1 = [s[1] for s in client_segments(r1)]
    if dims1 != ["minecraft:overworld", "minecraft:the_end"]:
        yield Violation("dimension-rejoin-warm", "run1",
                        "run-1 dimension sequence must be overworld -> the_end",
                        {"expected": ["minecraft:overworld", "minecraft:the_end"],
                         "actual": dims1})
        return
    segs2 = client_segments(r2)
    dims2 = [s[1] for s in segs2]
    if dims2 != ["minecraft:the_end", "minecraft:overworld"]:
        yield Violation("dimension-rejoin-warm", "run2",
                        "rejoin must land in the End and then return to the overworld",
                        {"expected": ["minecraft:the_end", "minecraft:overworld"],
                         "actual": dims2})
        return
    end_final = r2[segs2[0][3]]
    ow_final = r2[segs2[1][3]]
    if end_final["responses"]["up_to_date"] < 300:
        yield Violation("dimension-rejoin-warm", "run2 End segment",
                        "End resync was not warm — minecraft_the_end.bin not load-bearing",
                        {"expected": ">= 300", "actual": end_final["responses"]["up_to_date"]})
    # Client counters are run-cumulative (dimension boundaries do NOT reset them), so the
    # overworld segment's final up_to_date INCLUDES the End segment's count — only the
    # segment DELTA proves the overworld cache file was load-bearing. The raw cumulative
    # value trivially clears the floor whenever the End leg passed.
    ow_delta = ow_final["responses"]["up_to_date"] - end_final["responses"]["up_to_date"]
    if ow_delta < 300:
        yield Violation("dimension-rejoin-warm", "run2 overworld segment",
                        "overworld resync was not warm — minecraft_overworld.bin not load-bearing",
                        {"expected": ">= 300 up_to_date beyond the End segment's total",
                         "actual": ow_delta})
    for seg, dim, lo, hi in segs2:
        if (2, hi) not in ctx.quiescent_client:
            yield Violation("dimension-rejoin-warm",
                            f"run2 dim={dim} wallMs[..{r2[hi]['wallMs']}]",
                            "run-2 dimension segment does not end verified-quiescent",
                            {"segment": seg, "dimension": dim})


# Server-side tolerance for benign skylight-settle drift in check_dirty_resave_quiet (see there).
# A real reload loop is an order of magnitude larger AND trips the strict client re-download check.
DIRTY_RESAVE_LIGHT_SETTLE_TOLERANCE = 16


@named_check("dirty-broadcast", ["server.dirty.broadcast_positions", "client.received_columns"])
def check_dirty_resave_quiet(ctx):
    """Suppress direction of the dirty economy with the REAL vanilla save machinery:
    once the edited save-all has broadcast (and the first-save wave seeded the content
    filter for every loaded chunk), further no-edit save-alls re-save identical content
    and must broadcast NOTHING new and re-send NOTHING — exactly the metadata-resave
    churn (inhabitedTime) DirtyContentFilter was built to suppress. Conditional on >=2
    recorded save-all command events: recordings made before the timeline gained the
    re-save steps stay judgeable; on a fresh run the driver logs command rows before
    execution, so absence means the run died mid-timeline (run-completion also fails).

    The timeline uses 'save-all flush': plain save-all is THROTTLED (vanilla dribbles the
    save wave over many seconds), so under machine load the seeding wave from the edit
    save can drain past the baseline snapshot and late first-observation marks read as
    violations. flush makes seeding synchronous; the startswith match accepts both.

    The save-all list is anchored AFTER the setblock: the law's subject is "once the
    EDITED save-all has broadcast" (above), so the timeline's warmup save-alls BEFORE the
    edit (added 2026-07-30 so the first-save hash-seeding wave lands before
    check_dirty_broadcast's baseline on platforms whose chunk systems defer autosave —
    Moonrise never saves until asked) must not shift the window onto the edit itself.
    No-setblock recordings keep the legacy index-based window."""
    save_alls = [c for c in ctx.commands if c["cmd"].strip().startswith("save-all")]
    setblock = next((c for c in ctx.commands if "setblock" in c["cmd"]), None)
    if setblock is not None:
        save_alls = [c for c in save_alls if c["wallMs"] > setblock["wallMs"]]
    if len(save_alls) < 2:
        return
    resave_wall = save_alls[1]["wallMs"]
    server_before = [s for s in ctx.server_snaps if s["wallMs"] <= resave_wall]
    server_after = [s for s in ctx.server_snaps if s["wallMs"] > resave_wall]
    if not server_before or not server_after:
        yield Violation("dirty-resave", f"re-save@{resave_wall}",
                        "no server snapshots bracket the first no-edit re-save", {})
        return
    b0 = server_before[-1]["dirty"]["broadcast_positions"]
    b1 = max(s["dirty"]["broadcast_positions"] for s in server_after)
    # Skylight recalculation from the edit (a y=310 block casts a column-long shadow) and from
    # the loaded area still settling trickles a FEW genuine content changes over many save
    # cycles: loaded chunks re-light and re-save with new content, which DirtyContentFilter
    # correctly marks. That benign drift (live runs: 1-6 over the window, bounded by the
    # settling-column count) is NOT the failure mode this guards. A real suppression failure /
    # reload loop re-broadcasts the whole edited region EVERY cycle (cumulative tens-to-hundreds)
    # AND surfaces as client re-download (checked strictly below). So tolerate the light trickle
    # on the cumulative server counter; the client re-download check stays zero-tolerance.
    if b1 - b0 > DIRTY_RESAVE_LIGHT_SETTLE_TOLERANCE:
        yield Violation("dirty-resave", f"wallMs[{resave_wall}..]",
                        "no-edit re-saves broadcast new dirty positions beyond the skylight-"
                        "settle tolerance — the content filter failed to suppress identical re-saves",
                        {"baseline": b0, "after": b1, "tolerance": DIRTY_RESAVE_LIGHT_SETTLE_TOLERANCE,
                         "resaves": len(save_alls) - 1})
    snaps = ctx.runs.get(1)
    if not snaps:
        yield Violation("dirty-resave", "run1", "no client snapshots in run 1", {})
        return
    client_before = [s for s in snaps if s["wallMs"] <= resave_wall]
    if not client_before:
        yield Violation("dirty-resave", f"re-save@{resave_wall}",
                        "no client snapshot precedes the first no-edit re-save", {})
        return
    c0 = client_before[-1]["received_columns"]
    c1 = snaps[-1]["received_columns"]
    if c1 > c0:
        yield Violation("dirty-resave", f"wallMs[{resave_wall}..]",
                        "client re-downloaded columns after no-edit re-saves",
                        {"baseline": c0, "final": c1})


@named_check("paper-dirty-falling-block", ["server.dirty.broadcast_positions",
                                           "client.columns.dirty", "client.received_columns"])
def check_paper_dirty_falling_block(ctx):
    """Paper-native dirty pipeline (SOAK_PLATFORM=paper|folia): /setblock fires no Bukkit event,
    so the edit is a summoned falling block whose landing fires EntityChangeBlockEvent —
    the only E2E proof that PaperWorldHandler's event registration + Bukkit-world-key to
    NMS-dimension-string mapping feeds the broadcaster (a key mismatch is otherwise
    totally silent: marks pile up under a dimension no player matches and nothing errors).
    Same observable outcomes as dirty-broadcast: broadcast_positions rises within 2x the
    interval (the ~1 s fall fits the same +5 s slack), the client's dirty marks return to
    their pre-summon baseline, and received_columns rises (real re-send of the landed
    column). Bracketing snapshots are REQUIRED — this scenario has no legacy recordings
    to stay lenient for."""
    cmd = next((c for c in ctx.commands if "summon" in c["cmd"]), None)
    if cmd is None:
        yield Violation("paper-dirty-falling-block", "commands",
                        "no command event containing 'summon' found", {})
        return
    snaps = ctx.runs.get(1)
    if not snaps:
        yield Violation("paper-dirty-falling-block", "run1", "no client snapshots in run 1", {})
        return
    cmd_wall = cmd["wallMs"]
    interval = ctx.config.get("dirtyBroadcastIntervalSeconds", DEFAULT_DIRTY_BROADCAST_SECONDS)
    before = [s for s in snaps if s["wallMs"] < cmd_wall]
    if not before:
        yield Violation("paper-dirty-falling-block", f"summon@{cmd_wall}",
                        "no client snapshot precedes the summon command — cannot baseline", {})
        return
    base = before[-1]
    baseline_dirty = base["columns"]["dirty"]
    deadline = cmd_wall + 2 * interval * 1000
    server_before = [s for s in ctx.server_snaps if s["wallMs"] < cmd_wall]
    server_after = [s for s in ctx.server_snaps if cmd_wall < s["wallMs"] <= deadline + 5000]
    if not server_before or not server_after:
        yield Violation("paper-dirty-falling-block", f"summon@{cmd_wall}",
                        "no server snapshots bracket the falling-block landing window", {})
        return
    b0 = server_before[-1]["dirty"]["broadcast_positions"]
    b1 = max(s["dirty"]["broadcast_positions"] for s in server_after)
    if b1 <= b0:
        yield Violation("paper-dirty-falling-block", f"wallMs[{cmd_wall}..{deadline}]",
                        "server broadcast no dirty positions after the falling block "
                        "landed (EntityChangeBlockEvent never reached the tracker)",
                        {"before": b0, "after": b1, "interval_s": interval})
    final = snaps[-1]
    final_dirty = final["columns"]["dirty"]
    if final_dirty > baseline_dirty:
        yield Violation("paper-dirty-falling-block", f"final snapshot wallMs={final['wallMs']}",
                        "columns.dirty did not return to baseline (dirty columns never re-served)",
                        {"baseline": baseline_dirty, "final": final_dirty})
    if final["received_columns"] <= base["received_columns"]:
        yield Violation("paper-dirty-falling-block", f"final snapshot wallMs={final['wallMs']}",
                        "received_columns did not increase after the landing (no real re-send)",
                        {"baseline": base["received_columns"],
                         "final": final["received_columns"]})


CHECKS = {
    "fresh-backfill": [check_fresh_backfill,
                       make_handshake_check("fresh-backfill"),
                       make_disc_completeness("fresh-backfill")],
    "hybrid-boundary": [check_hybrid_boundary,
                        make_handshake_check("hybrid-boundary"),
                        make_disc_completeness("hybrid-boundary")],
    "warm-rejoin": [check_warm_rejoin,
                    make_handshake_check("warm-rejoin"),
                    make_disc_completeness("warm-rejoin", run=1),
                    make_disc_completeness("warm-rejoin", run=2)],
    "dimension-trip": [check_dimension_trip,
                       make_handshake_check("dimension-trip"),
                       make_disc_completeness("dimension-trip")],
    "dirty-broadcast": [check_dirty_broadcast,
                        check_dirty_resave_quiet,
                        make_handshake_check("dirty-broadcast"),
                        make_disc_completeness("dirty-broadcast")],
    "rate-limit-storm": [check_rate_limit_storm,
                         make_handshake_check("rate-limit-storm"),
                         make_disc_completeness("rate-limit-storm")],
    "disk-saturation": [check_disk_saturation,
                        make_handshake_check("disk-saturation"),
                        make_disc_completeness("disk-saturation")],
    "disk-read-gate": [check_disk_read_gate,
                       make_handshake_check("disk-read-gate"),
                       make_disc_completeness("disk-read-gate")],
    "generation-disabled": [check_generation_disabled,
                            make_handshake_check("generation-disabled"),
                            make_disc_completeness("generation-disabled")],
    "generation-capacity-stress": [check_generation_capacity_stress,
                                   make_handshake_check("generation-capacity-stress"),
                                   make_disc_completeness("generation-capacity-stress")],
    "bandwidth-throttle": [check_bandwidth_throttle,
                           make_handshake_check("bandwidth-throttle"),
                           make_disc_completeness("bandwidth-throttle")],
    "cold-restart-resync": [check_cold_restart_resync,
                            make_handshake_check("cold-restart-resync"),
                            make_disc_completeness("cold-restart-resync")],
    # No disc-completeness: a disabled session never builds a disc (that is the point).
    "enabled-false": [check_enabled_false],
    "teleport-prune": [check_teleport_prune,
                       make_handshake_check("teleport-prune"),
                       make_disc_completeness("teleport-prune")],
    "dirty-range-filter": [check_dirty_range_filter,
                           make_handshake_check("dirty-range-filter"),
                           make_disc_completeness("dirty-range-filter")],
    "dirty-during-backfill": [check_dirty_during_backfill,
                              make_handshake_check("dirty-during-backfill"),
                              make_disc_completeness("dirty-during-backfill")],
    "dirty-while-offline": [check_dirty_while_offline,
                            make_handshake_check("dirty-while-offline"),
                            make_disc_completeness("dirty-while-offline", run=1),
                            make_disc_completeness("dirty-while-offline", run=2)],
    # Region summaries (plan §8). Disc completeness holds on BOTH runs: summary
    # validation only sets validated bits on already-stamped positions, so run 2's
    # columns.known still covers the disc — a validation that DROPPED state would
    # red here.
    "warm-rejoin-summary": [check_warm_rejoin_summary,
                            make_handshake_check("warm-rejoin-summary"),
                            make_disc_completeness("warm-rejoin-summary", run=1),
                            make_disc_completeness("warm-rejoin-summary", run=2)],
    "dirty-while-offline-summary": [check_dirty_while_offline_summary,
                                    make_handshake_check("dirty-while-offline-summary"),
                                    make_disc_completeness("dirty-while-offline-summary", run=1),
                                    make_disc_completeness("dirty-while-offline-summary", run=2)],
    "evicted-tscache-rejoin": [check_evicted_tscache_rejoin,
                               make_handshake_check("evicted-tscache-rejoin"),
                               make_disc_completeness("evicted-tscache-rejoin")],
    "stamp-heal-prime": [check_stamp_heal_prime,
                         make_handshake_check("stamp-heal-prime"),
                         make_disc_completeness("stamp-heal-prime", run=1),
                         make_disc_completeness("stamp-heal-prime", run=2)],
    "stamp-heal-rejoin": [check_stamp_heal_rejoin,
                          make_handshake_check("stamp-heal-rejoin"),
                          make_disc_completeness("stamp-heal-rejoin")],
    "clearcache-mid-session": [check_clearcache_mid_session,
                               make_handshake_check("clearcache-mid-session"),
                               make_disc_completeness("clearcache-mid-session")],
    "store-second-join": [check_store_second_join,
                          make_handshake_check("store-second-join"),
                          make_disc_completeness("store-second-join")],
    # C6 store-migration variant (XVER §9): downgraded-store warm join while the
    # background walk migrates; migration completion asserted by the wrapper.
    "store-migration-join": [check_store_migration_join,
                             make_handshake_check("store-migration-join"),
                             make_disc_completeness("store-migration-join")],
    # store_offline_edit.sh phases: each individually law-checked; the cross-phase
    # probe-hash comparison (edited differs, control identical) lives in the wrapper.
    "store-offline-populate": [check_store_offline_populate,
                               make_handshake_check("store-offline-populate"),
                               make_disc_completeness("store-offline-populate")],
    # No disc-completeness: a disabled session never builds a disc (like enabled-false).
    "store-offline-mutate": [check_store_offline_mutate,
                             make_handshake_check("store-offline-mutate",
                                                  expect_enabled=False)],
    "store-offline-verify": [check_store_offline_verify,
                             make_handshake_check("store-offline-verify"),
                             make_disc_completeness("store-offline-verify")],
    # Phase 3 gate: the save-hook deposit path under an autosave storm; the -off twin
    # exists solely as store_save_storm.sh's MSPT pairing arm (laws only).
    "store-save-storm": [check_store_save_storm,
                         make_handshake_check("store-save-storm"),
                         make_disc_completeness("store-save-storm")],
    "store-save-storm-off": [make_handshake_check("store-save-storm-off"),
                             make_disc_completeness("store-save-storm-off")],
    "dimension-rejoin-warm": [check_dimension_rejoin_warm,
                              make_handshake_check("dimension-rejoin-warm"),
                              make_disc_completeness("dimension-rejoin-warm", run=1),
                              make_disc_completeness("dimension-rejoin-warm", run=2)],
    # Paper/Folia scenario (scripts/soak.sh SOAK_PLATFORM=paper|folia); the conservation laws
    # themselves are platform-blind — the Paper exporter twin emits the same schema.
    "paper-dirty-falling-block": [check_paper_dirty_falling_block,
                                  make_handshake_check("paper-dirty-falling-block"),
                                  make_disc_completeness("paper-dirty-falling-block")],
    "paper-store-unfired-event": [check_paper_store_unfired_event,
                                  make_handshake_check("paper-store-unfired-event"),
                                  make_disc_completeness("paper-store-unfired-event")],
}


# ---------------------------------------------------------------------- validate mode

def validate_config_overrides(cfg):
    """Reject -config.json keys that are not real lss-server-config.json fields (GSON
    silently ignores unknown keys — a typo would silently revert the scenario to defaults)
    and values whose JSON type does not match the field."""
    errors = []
    for key in sorted(cfg.keys() - SERVER_CONFIG_KEYS):
        errors.append(f"config key '{key}' is not a lss-server-config.json field "
                      f"(GSON would silently ignore it); known keys: {sorted(SERVER_CONFIG_KEYS)}")
    for key, value in sorted(cfg.items()):
        if key in SERVER_CONFIG_BOOL_KEYS and not isinstance(value, bool):
            errors.append(f"config key '{key}' must be a JSON boolean, got {value!r}")
        elif key in SERVER_CONFIG_INT_KEYS and (not isinstance(value, int) or isinstance(value, bool)):
            errors.append(f"config key '{key}' must be a JSON integer, got {value!r}")
        elif key in SERVER_CONFIG_STRING_KEYS and not isinstance(value, str):
            errors.append(f"config key '{key}' must be a JSON string, got {value!r}")
        elif key in SERVER_CONFIG_STRING_LIST_KEYS and (
                not isinstance(value, list) or not all(isinstance(v, str) for v in value)):
            errors.append(f"config key '{key}' must be a JSON list of strings, got {value!r}")
    return errors


def validate_scenario(name):
    errors = []
    scen_path = SCENARIO_DIR / f"{name}.json"
    cfg_path = SCENARIO_DIR / f"{name}-config.json"
    scen, cfg = None, None
    for path, label in ((scen_path, "scenario"), (cfg_path, "config")):
        if not path.is_file():
            errors.append(f"{label} file missing: {path}")
            continue
        try:
            data = json.loads(path.read_text(encoding="utf-8"))
        except (json.JSONDecodeError, OSError) as e:
            errors.append(f"{label} file {path.name} does not parse: {e}")
            continue
        if not isinstance(data, dict):
            errors.append(f"{label} file {path.name} must be a JSON object")
            continue
        if label == "scenario":
            scen = data
        else:
            cfg = data

    if scen is not None:
        errors += validate_timeline(scen)
    if cfg is not None:
        errors += validate_config_overrides(cfg)

    if name not in ANOMALY_OPT_INS:
        errors.append(f"scenario '{name}' has no ANOMALY_OPT_INS entry (must be declared "
                      f"explicitly, even if empty)")
    floors = MIN_CLIENT_WINDOWS.get(name)
    if not floors or not all(isinstance(v, int) and v >= 1 for v in floors.values()):
        errors.append(f"scenario '{name}' must declare MIN_CLIENT_WINDOWS floors >= 1 "
                      f"(got {floors!r}) — without them every client-involving law can pass vacuously")

    if name not in CHECKS:
        errors.append(f"scenario '{name}' has no entry in the CHECKS registry "
                      f"(known: {sorted(CHECKS)})")
    else:
        for fn in CHECKS[name]:
            if not callable(fn):
                errors.append(f"CHECKS['{name}'] entry {fn!r} is not callable")
                continue
            rf = getattr(fn, "required_fields", None)
            if (not isinstance(rf, list) or not rf
                    or not all(isinstance(p, str) and p.split(".", 1)[0] in ("server", "client")
                               and "." in p for p in rf)):
                errors.append(f"check {fn.__name__} must declare required_fields as a non-empty "
                              f"list of 'server.x.y'/'client.x.y' dotted paths (got {rf!r})")
    return errors


SCENARIO_TOP_LEVEL_KEYS = frozenset({
    "snapshotIntervalSeconds", "joinTimeoutSeconds", "steps", "end",
    # C6 store-migration variant: the SERVER_STARTING downgrade flag. Allowlisted
    # (review m10) so a typo'd key reds validation instead of silently skipping the
    # downgrade and measuring an ordinary v20 store — the same hole the config-override
    # allowlist exists to close (the R4 lesson).
    "downgradeStoreToV19",
})


def validate_timeline(scen):
    errors = []
    for key in scen:
        if key not in SCENARIO_TOP_LEVEL_KEYS:
            errors.append(f"unknown scenario key '{key}' (allowed: "
                          f"{sorted(SCENARIO_TOP_LEVEL_KEYS)})")
    for key, lo, hi in (("snapshotIntervalSeconds", 1, 300), ("joinTimeoutSeconds", 1, 3600)):
        v = scen.get(key)
        if not isinstance(v, int) or isinstance(v, bool) or not (lo <= v <= hi):
            errors.append(f"{key} must be an int in [{lo}, {hi}], got {v!r}")
    steps = scen.get("steps")
    if not isinstance(steps, list) or not steps:
        errors.append("steps must be a non-empty list")
        steps = []
    keys = []
    for i, step in enumerate(steps):
        if not isinstance(step, dict):
            errors.append(f"steps[{i}] must be an object")
            continue
        anchor, at, cmd = step.get("anchor"), step.get("at"), step.get("cmd")
        if not isinstance(anchor, int) or isinstance(anchor, bool) or anchor < 1:
            errors.append(f"steps[{i}].anchor must be a positive int, got {anchor!r}")
        if not isinstance(at, int) or isinstance(at, bool) or at < 0:
            errors.append(f"steps[{i}].at must be a non-negative int, got {at!r}")
        if not isinstance(cmd, str) or not cmd.strip():
            errors.append(f"steps[{i}].cmd must be a non-empty string, got {cmd!r}")
        if isinstance(anchor, int) and isinstance(at, int):
            keys.append((anchor, at))
    if keys != sorted(keys):
        errors.append(f"steps must be sorted by (anchor, at); got order {keys}")
    end = scen.get("end")
    if not isinstance(end, dict):
        errors.append(f"end must be an object with anchor/at, got {end!r}")
    else:
        e_anchor, e_at = end.get("anchor"), end.get("at")
        if not isinstance(e_anchor, int) or isinstance(e_anchor, bool) or e_anchor < 1:
            errors.append(f"end.anchor must be a positive int, got {e_anchor!r}")
        if not isinstance(e_at, int) or isinstance(e_at, bool) or e_at < 0:
            errors.append(f"end.at must be a non-negative int, got {e_at!r}")
        if keys and isinstance(e_anchor, int) and isinstance(e_at, int):
            max_anchor = max(a for a, _ in keys)
            if e_anchor < max_anchor:
                errors.append(f"end.anchor ({e_anchor}) must be >= max step anchor ({max_anchor})")
            same = [at for a, at in keys if a == e_anchor]
            if same and e_at <= max(same):
                errors.append(f"end.at ({e_at}) must be after the last step of anchor "
                              f"{e_anchor} (at={max(same)})")
    return errors


# --------------------------------------------------------------------------- run mode

def resolve_required_fields(fn, server_snaps, runs, violations):
    """Verify a named check's declared fields against the FIRST row of each series."""
    ok = True
    first_client = next((snaps[0] for _, snaps in sorted(runs.items()) if snaps), None)
    for dotted in fn.required_fields:
        series, path = dotted.split(".", 1)
        row = server_snaps[0] if series == "server" else first_client
        if row is None:
            violations.append(Violation("schema", f"check {fn.__name__}",
                                        f"no {series} snapshot rows to validate required field",
                                        {"field": dotted}))
            ok = False
            continue
        try:
            get_path(row, path)
        except KeyError:
            violations.append(Violation("schema", f"check {fn.__name__}",
                                        f"required field missing from first {series} snapshot",
                                        {"field": dotted}))
            ok = False
    return ok


def check_global_schema(server_snaps, runs, violations):
    ok = True
    if server_snaps:
        for path in GLOBAL_SERVER_FIELDS:
            try:
                get_path(server_snaps[0], path)
            except KeyError:
                violations.append(Violation("schema", "server.jsonl first snapshot",
                                            "required field missing", {"field": path}))
                ok = False
        first_with_players = next((s for s in server_snaps if s.get("players")), None)
        if first_with_players is not None:
            for k in PLAYER_DRAINS:
                if k not in first_with_players["players"][0]:
                    violations.append(Violation("schema", "server.jsonl players[0]",
                                                "required player field missing", {"field": k}))
                    ok = False
    for run, snaps in sorted(runs.items()):
        if not snaps:
            continue
        for path in GLOBAL_CLIENT_FIELDS:
            try:
                get_path(snaps[0], path)
            except KeyError:
                violations.append(Violation("schema", f"client-run{run}.jsonl first snapshot",
                                            "required field missing", {"field": path}))
                ok = False
    return ok


def run_checker(results_dir, scenario, expect_session_version=None, platform="fabric"):
    violations, warnings = [], []
    unknown_keys, unknown_events = set(), set()

    server_path = results_dir / "server.jsonl"
    if server_path.is_file():
        server = load_server(server_path, warnings, unknown_keys, unknown_events)
    else:
        server = {"snapshots": [], "commands": [], "joins": [], "ends": []}
        violations.append(Violation("input", str(server_path), "server.jsonl missing", {}))
    if server_path.is_file() and not server["snapshots"]:
        violations.append(Violation("input", "server.jsonl", "zero snapshot rows", {}))

    runs, run_actions = {}, {}
    expected_runs = EXPECTED_RUNS.get(scenario, 1)
    for n in range(1, expected_runs + 1):
        run_path = results_dir / f"client-run{n}.jsonl"
        if not run_path.is_file():
            violations.append(Violation("input", run_path.name, "expected client run file missing", {}))
            continue
        snaps, actions = load_client_run(run_path, warnings, unknown_keys, unknown_events)
        if not snaps:
            violations.append(Violation("input", run_path.name, "zero snapshot rows", {}))
        else:
            violations.extend(client_run_completion_violations(run_path.name, snaps))
            violations.extend(session_version_violations(run_path.name, snaps,
                                                         expect_session_version))
        runs[n] = snaps
        run_actions[n] = actions
    for extra in sorted(results_dir.glob("client-run*.jsonl")):
        try:
            n = int(extra.stem.removeprefix("client-run"))
        except ValueError:
            continue
        if n > expected_runs:
            warnings.append(f"unexpected extra client run file ignored: {extra.name}")

    ends = server["ends"]
    if not ends:
        # The driver writes an end row on EVERY controlled exit (timeline-complete or
        # join-timeout), so absence means the server JVM died mid-run — the central
        # failure a soak harness must catch. As a warning this let a post-convergence
        # crash produce a clean PASS.
        violations.append(Violation("run-completion", "server.jsonl",
                                    "no end event — server died mid-run (uncontrolled exit)", {}))
    elif ends[-1]["reason"] != "timeline-complete":
        violations.append(Violation("run-completion", "end event",
                                    "run did not complete its timeline",
                                    {"reason": ends[-1]["reason"]}))

    cfg_path = SCENARIO_DIR / f"{scenario}-config.json"
    config = {}
    if cfg_path.is_file():
        try:
            config = json.loads(cfg_path.read_text(encoding="utf-8"))
        except (json.JSONDecodeError, OSError) as e:
            warnings.append(f"{cfg_path.name} unreadable ({e}); using defaults")
    else:
        warnings.append(f"{cfg_path.name} not found; using defaults")
    for e in validate_config_overrides(config):
        # --validate is the hard gate pre-run; at evaluation time surface it loudly but
        # keep judging the recorded data.
        warnings.append(f"{cfg_path.name}: {e}")

    qpoints, windows, client_windows = [], 0, 0
    schema_ok = check_global_schema(server["snapshots"], runs, violations)
    if server["snapshots"] and schema_ok:
        try:
            qpoints = find_quiescent(server["snapshots"], runs)
        except KeyError as e:
            violations.append(Violation("schema", "quiescence scan",
                                        "field missing while evaluating quiescence",
                                        {"field": str(e.args[0])}))
        if not qpoints:
            violations.append(Violation("quiescence", "entire run",
                                        "no verified-quiescent snapshot pairs found "
                                        "(system never drained or client never joined)", {}))
        ctx = Ctx(scenario=scenario, server_snaps=server["snapshots"],
                  commands=server["commands"], joins=server["joins"], ends=ends,
                  runs=runs, qpoints=qpoints, config=config,
                  quiescent_server={q.si for q in qpoints},
                  quiescent_client={(q.run, q.ci) for q in qpoints},
                  run_actions=run_actions, platform=platform)
        try:
            law_violations, windows, client_windows = evaluate_laws(ctx)
            violations += law_violations
        except KeyError as e:
            violations.append(Violation("schema", "law evaluation",
                                        "field missing while evaluating laws",
                                        {"field": str(e.args[0])}))
        if scenario not in CHECKS:
            violations.append(Violation("registry", scenario,
                                        "scenario has no entry in the CHECKS registry", {}))
        else:
            for fn in CHECKS[scenario]:
                if not resolve_required_fields(fn, server["snapshots"], runs, violations):
                    continue
                try:
                    violations += list(fn(ctx))
                except KeyError as e:
                    violations.append(Violation("schema", f"check {fn.__name__}",
                                                "field missing while running named check",
                                                {"field": str(e.args[0])}))

    if unknown_events:
        warnings.append("ignored unknown event types: " + ", ".join(sorted(unknown_events)))
    if unknown_keys:
        warnings.append("ignored unknown top-level fields: " + ", ".join(sorted(unknown_keys)))

    verdict = {
        "scenario": scenario,
        "passed": not violations,
        "violations": [vars(v) for v in violations],
        "warnings": warnings,
        "windows_evaluated": windows,
        "client_windows_evaluated": client_windows,
        "quiescent_snapshots": len(qpoints),
    }
    try:
        (results_dir / "verdict.json").write_text(json.dumps(verdict, indent=2) + "\n",
                                                  encoding="utf-8")
    except OSError as e:
        print(f"WARNING: could not write verdict.json: {e}", file=sys.stderr)

    status = "PASS" if not violations else "FAIL"
    print(f"{status}: {scenario} — {windows} windows ({client_windows} client-laws), "
          f"{len(qpoints)} quiescent snapshots, "
          f"{len(violations)} violations, {len(warnings)} warnings")
    for v in violations:
        print("  " + v.line())
    for w in warnings:
        print(f"  WARNING: {w}")
    return 0 if not violations else 1


# --------------------------------------------------------------------------- selftest

def _set_path(row, dotted, value):
    parts = dotted.split(".")
    cur = row
    for p in parts[:-1]:
        cur = cur[p]
    cur[parts[-1]] = value


def _srv(wall=1000, seg=0, over=None):
    """Schema-complete server snapshot fixture (all GLOBAL_SERVER_FIELDS present, zeros)."""
    snap = {"event": "snapshot", "wallMs": wall, "tick": wall // 50, "_seg": seg,
            "service": {"requests_received": 0, "columns_sent": 0, "bytes_sent": 0,
                        "wire_bytes": 0, "cols_zstd": 0, "cols_raw": 0,
                        "duplicate_skips": 0, "queue_full": 0, "up_to_date": 0,
                        "in_memory": 0, "disk_resolved": 0, "gen_drained": 0,
                        "superseded": 0, "range_filtered": 0, "re_resolved": 0,
                        "paced_ticks": 0,
                        "grace_skipped": 0, "miss_dropped": 0},
            "disk": {"submitted": 0, "completed": 0, "not_found": 0, "all_air": 0,
                     "errors": 0, "saturated": 0, "successful": 0, "pending": 0,
                     "memo_hits": 0, "header_hits": 0, "gated": 0, "gate_stops": 0},
            "generation": {"submitted": 0, "completed": 0, "timeouts": 0,
                           "removed_in_flight": 0, "active": 0,
                           "order_gated": 0, "inversions": 0},
            "dirty": {"pending": 0, "broadcast_positions": 0, "marked_total": 0,
                      "suppressed_total": 0, "seeded_load": 0, "entries": 0},
            "far_players": {"subscribers": 0, "roster_frames": 0, "update_frames": 0,
                            "entries": 0, "suppressed": 0, "bytes": 0},
            "summary": {"requests": 0, "range_filtered": 0, "frames": 0,
                        "tiles_known": 0, "tiles_never_clean": 0, "tiles_no_region": 0,
                        "bytes": 0, "refresh_ms_hw": 0,
                        "stamps_frames": 0, "stamps_entries": 0, "stamps_bytes": 0},
            "store": {"hits": 0, "misses": 0, "deposits": 0, "deposit_drops": 0,
                      "deposit_skips": 0,
                      "errors": 0, "sweep_drops": 0,
                      "backfill_reads": 0, "backfill_deposits": 0, "backfill_skips": 0,
                      "queue": 0,
                      "db_bytes": 0, "wal_bytes": 0,
                      "checkpoint_ms_max": 0, "read_avg_us": 0, "read_p95_us": 0},
            "bandwidth": {"total_bytes": 0},
            "tscache": {"size_per_dimension": {}, "evictions": 0}, "players": []}
    for k, v in (over or {}).items():
        _set_path(snap, k, v)
    return snap


def _cli(wall=1000, seg=0, over=None):
    """Schema-complete client snapshot fixture (all GLOBAL_CLIENT_FIELDS present, zeros)."""
    snap = {"event": "snapshot", "wallMs": wall, "dimension": "minecraft:overworld",
            "_seg": seg, "received_columns": 0, "received_bytes": 0, "dropped": 0,
            "responses": {"columns": 0, "up_to_date": 0, "not_generated": 0},
            "requested_total": 0, "send_cycles": 0,
            "columns": {"known": 0, "empty": 0, "dirty": 0},
            "scan": {"confirmed": 0, "ring": 0, "missing_vanilla": 0},
            "summary": {"tiles_clean": 0, "tiles_stale": 0, "tiles_unknown": 0,
                        "tiles_no_region": 0, "columns_validated": 0,
                        "stamps_applied": 0, "stamps_ignored": 0},
            "tracker_in_flight": 0, "queued": 0}
    for k, v in (over or {}).items():
        _set_path(snap, k, v)
    return snap


def selftest():
    """Prove every law both PASSES consistent data and CATCHES a minimally-doctored
    inconsistency. A semantically-inverted or disconnected law turns the whole soak suite
    into theater while staying green — this is the guard against that."""
    cases = [0]

    def clean(label, vs):
        cases[0] += 1
        assert vs == [], f"{label}: unexpected violations: {[v.line() for v in vs]}"

    def hits(label, vs, law):
        cases[0] += 1
        assert vs, f"{label}: expected a {law} violation, got none"
        assert all(v.law == law for v in vs), \
            f"{label}: expected only {law}, got {[v.line() for v in vs]}"

    # --- A1 (v17): requests == responses + duplicate_skips + superseded + range_filtered ---
    # queue_full is deliberately NONZERO in the clean fixture: it must NOT be a term (a
    # queue-full break retains its entries, it does not dispose of them). If someone
    # re-adds it to the law, "A1 balanced" fails — that is the pin.
    ps, cs = _srv(1000), _srv(6000, over={"service.duplicate_skips": 2,
                                          "service.superseded": 3,
                                          "service.range_filtered": 1,
                                          "service.queue_full": 5})
    pc = _cli(1000)
    cc = _cli(6000, over={"requested_total": 13, "responses.columns": 4,
                          "responses.up_to_date": 2, "responses.not_generated": 1})
    clean("A1 balanced", law_A1(ps, cs, pc, cc, "selftest"))
    cc_bad = _cli(6000, over={"requested_total": 14, "responses.columns": 4,
                              "responses.up_to_date": 2, "responses.not_generated": 1})
    hits("A1 lost request", law_A1(ps, cs, pc, cc_bad, "selftest"), "A1")

    # A silent server-side drop (mailbox overwrite / backlog replace) is conserved ONLY by
    # service.superseded — with no wire response, an uncounted drop is an invisible hole.
    ps2, pc2 = _srv(1000), _cli(1000)
    cc_drop = _cli(6000, over={"requested_total": 8, "responses.columns": 3})
    hits("A1 uncounted silent drop", law_A1(
        ps2, _srv(6000), pc2, cc_drop, "selftest"), "A1")
    clean("A1 superseded balances a silent drop", law_A1(
        ps2, _srv(6000, over={"service.superseded": 5}), pc2, cc_drop, "selftest"))

    # The ingress Chebyshev guard drops out-of-range declarations (the movement race):
    # counted range_filtered, never answered.
    cc_rf = _cli(6000, over={"requested_total": 6, "responses.columns": 2})
    hits("A1 uncounted range filter", law_A1(
        ps2, _srv(6000), pc2, cc_rf, "selftest"), "A1")
    clean("A1 range_filtered balances the motion race", law_A1(
        ps2, _srv(6000, over={"service.range_filtered": 4}), pc2, cc_rf, "selftest"))

    # --- A2: delivery (columns, bytes, dropped) ---
    ps, cs = _srv(1000), _srv(6000, over={"service.columns_sent": 5, "service.bytes_sent": 100})
    pc, cc = _cli(1000), _cli(6000, over={"received_columns": 5, "received_bytes": 100})
    clean("A2 balanced", law_A2(ps, cs, pc, cc, "selftest"))
    hits("A2 column lost", law_A2(
        ps, _srv(6000, over={"service.columns_sent": 6, "service.bytes_sent": 100}),
        pc, cc, "selftest"), "A2")
    hits("A2 bytes mismatch", law_A2(
        ps, _srv(6000, over={"service.columns_sent": 5, "service.bytes_sent": 99}),
        pc, cc, "selftest"), "A2")
    hits("A2 client dropped", law_A2(ps, cs, pc, _cli(6000, over={
        "received_columns": 5, "received_bytes": 100, "dropped": 1}), "selftest"), "A2")

    # --- A3: columns_sent bounded by sources ---
    cs = _srv(6000, over={"service.columns_sent": 5, "service.in_memory": 2,
                          "disk.successful": 2, "generation.completed": 1})
    clean("A3 within sources", law_A3(_srv(1000), cs, "selftest"))
    cs_bad = _srv(6000, over={"service.columns_sent": 5, "service.in_memory": 1,
                              "disk.successful": 2, "generation.completed": 1})
    hits("A3 conjured column", law_A3(_srv(1000), cs_bad, "selftest"), "A3")
    cs_store = _srv(6000, over={"service.columns_sent": 5, "service.in_memory": 1,
                                "disk.successful": 2, "store.hits": 2})
    clean("A3 store hits are a source", law_A3(_srv(1000), cs_store, "selftest"))

    # --- A4: generation accounting ---
    cs = _srv(6000, over={"generation.submitted": 5, "generation.completed": 4,
                          "generation.timeouts": 1})
    clean("A4 balanced", law_A4(_srv(1000), cs, "selftest"))
    cs_bad = _srv(6000, over={"generation.submitted": 5, "generation.completed": 3,
                              "generation.timeouts": 1})
    hits("A4 lost generation", law_A4(_srv(1000), cs_bad, "selftest"), "A4")

    # --- A5: disk triage (escalation leg + partition leg) ---
    ps, pc = _srv(1000), _cli(1000)
    cs = _srv(6000, over={"disk.not_found": 5, "generation.submitted": 3,
                          "disk.completed": 10, "disk.all_air": 1, "disk.saturated": 1,
                          "disk.successful": 3})
    cc = _cli(6000, over={"responses.not_generated": 2})
    clean("A5 balanced", law_A5(ps, cs, pc, cc, "selftest"))
    # Server-owned generation: transient miss-drops (cap-full / capacity-reject / ghost)
    # balance the escalation leg through the dedicated miss_dropped term.
    cs_md = _srv(6000, over={"disk.not_found": 7, "generation.submitted": 3,
                             "service.miss_dropped": 2,
                             "disk.completed": 12, "disk.all_air": 1, "disk.saturated": 1,
                             "disk.successful": 3})
    clean("A5 balanced with miss-drops", law_A5(ps, cs_md, pc, cc, "selftest"))
    cs_bad_md = _srv(6000, over={"disk.not_found": 7, "generation.submitted": 3,
                                 "service.miss_dropped": 1,
                                 "disk.completed": 12, "disk.all_air": 1, "disk.saturated": 1,
                                 "disk.successful": 3})
    hits("A5 miss-drop leg", law_A5(ps, cs_bad_md, pc, cc, "selftest"), "A5")
    cs_bad = _srv(6000, over={"disk.not_found": 5, "generation.submitted": 2,
                              "disk.completed": 10, "disk.all_air": 1, "disk.saturated": 1,
                              "disk.successful": 3})
    hits("A5 escalation leg", law_A5(ps, cs_bad, pc, cc, "selftest"), "A5")
    cs_bad = _srv(6000, over={"disk.not_found": 5, "generation.submitted": 3,
                              "disk.completed": 10, "disk.all_air": 1, "disk.saturated": 1,
                              "disk.successful": 4})
    hits("A5 partition leg", law_A5(ps, cs_bad, pc, cc, "selftest"), "A5")
    # Miss-memo rung: hits are virtual not-founds. 4 hits -> 3 more gen submits + 1 more
    # miss_dropped, with disk.not_found unchanged — the identity must stay exact.
    cs_memo = _srv(6000, over={"disk.not_found": 5, "disk.memo_hits": 4,
                               "generation.submitted": 6, "service.miss_dropped": 1,
                               "disk.completed": 10, "disk.all_air": 1, "disk.saturated": 1,
                               "disk.successful": 3})
    clean("A5 balanced with memo hits", law_A5(ps, cs_memo, pc, cc, "selftest"))
    # A memo hit that vanished without a disposition (neither submit nor miss_dropped) is
    # exactly the accounting hole the virtual-not-found fold must catch.
    cs_memo_bad = _srv(6000, over={"disk.not_found": 5, "disk.memo_hits": 4,
                                   "generation.submitted": 5, "service.miss_dropped": 1,
                                   "disk.completed": 10, "disk.all_air": 1, "disk.saturated": 1,
                                   "disk.successful": 3})
    hits("A5 memo-hit leg", law_A5(ps, cs_memo_bad, pc, cc, "selftest"), "A5")

    # --- A6 server: monotonic whitelist, including the v17 want-set counters ---
    clean("A6 server monotonic", law_A6_server([
        _srv(1000, over={"service.superseded": 5, "dirty.broadcast_positions": 7}),
        _srv(6000, over={"service.superseded": 6, "dirty.broadcast_positions": 7})]))
    hits("A6 superseded decrement", law_A6_server([
        _srv(1000, over={"service.superseded": 5}),
        _srv(6000, over={"service.superseded": 4})]), "A6")
    hits("A6 range_filtered decrement", law_A6_server([
        _srv(1000, over={"service.range_filtered": 3}),
        _srv(6000, over={"service.range_filtered": 2})]), "A6")
    hits("A6 broadcast_positions decrement", law_A6_server([
        _srv(1000, over={"dirty.broadcast_positions": 7}),
        _srv(6000, over={"dirty.broadcast_positions": 6})]), "A6")
    hits("A6 re_resolved decrement", law_A6_server([
        _srv(1000, over={"service.re_resolved": 9}),
        _srv(6000, over={"service.re_resolved": 8})]), "A6")
    hits("A6 suppressed_total decrement", law_A6_server([
        _srv(1000, over={"dirty.suppressed_total": 5}),
        _srv(6000, over={"dirty.suppressed_total": 4})]), "A6")
    clean("A6 store counters growth", law_A6_server([
        _srv(1000, over={"store.hits": 5, "store.deposits": 2}),
        _srv(6000, over={"store.hits": 9, "store.deposits": 4})]))
    hits("A6 store hits decrement", law_A6_server([
        _srv(1000, over={"store.hits": 5}),
        _srv(6000, over={"store.hits": 4})]), "A6")
    hits("A6 store deposit_drops decrement", law_A6_server([
        _srv(1000, over={"store.deposit_drops": 2}),
        _srv(6000, over={"store.deposit_drops": 1})]), "A6")

    # --- A6 client: monotonic for the whole run (counters are run-cumulative; a
    # dimension/action boundary does NOT excuse a decrement) ---
    hits("A6 client in-segment decrement", law_A6_client(1, [
        _cli(1000, over={"responses.up_to_date": 5}),
        _cli(6000, over={"responses.up_to_date": 4})]), "A6")
    hits("A6 client cross-segment decrement", law_A6_client(1, [
        _cli(1000, seg=0, over={"responses.up_to_date": 5}),
        _cli(6000, seg=1, over={"responses.up_to_date": 0})]), "A6")
    clean("A6 client cross-segment growth", law_A6_client(1, [
        _cli(1000, seg=0, over={"responses.up_to_date": 5}),
        _cli(6000, seg=1, over={"responses.up_to_date": 5})]))

    # --- A7 server: errors/timeouts always fail; saturated honors the opt-in ---
    hits("A7 disk.errors", law_A7_server(
        _srv(1000), _srv(6000, over={"disk.errors": 1}), "selftest",
        frozenset({"saturated"})), "A7")
    hits("A7 generation.timeouts", law_A7_server(
        _srv(1000), _srv(6000, over={"generation.timeouts": 1}), "selftest",
        frozenset({"saturated"})), "A7")
    hits("A7 saturated w/o opt-in", law_A7_server(
        _srv(1000), _srv(6000, over={"disk.saturated": 1}), "selftest", frozenset()), "A7")
    clean("A7 saturated with opt-in", law_A7_server(
        _srv(1000), _srv(6000, over={"disk.saturated": 1}), "selftest",
        frozenset({"saturated"})))
    # The DiskReadGate arm (disk-read-concurrency-gate-plan.md): every no-op-pinned
    # scenario self-verifies its pin here — gated>0 without the opt-in is a permit leak.
    hits("A7 gated w/o opt-in", law_A7_server(
        _srv(1000), _srv(6000, over={"disk.gated": 1}), "selftest",
        frozenset({"saturated"})), "A7")
    clean("A7 gated with opt-in", law_A7_server(
        _srv(1000), _srv(6000, over={"disk.gated": 1}), "selftest",
        frozenset({"gated"})))
    # The retention arm (Amendment 2): gate_stops>0 on a no-op-pinned (K=pool) scenario
    # means the saturation predicate leaked — structurally impossible there (the park
    # stays pigeonhole-empty). Only disk-read-gate opts in.
    hits("A7 gate_stops w/o opt-in", law_A7_server(
        _srv(1000), _srv(6000, over={"disk.gate_stops": 1}), "selftest",
        frozenset({"gated"})), "A7")
    clean("A7 gate_stops with opt-in", law_A7_server(
        _srv(1000), _srv(6000, over={"disk.gate_stops": 1}), "selftest",
        frozenset({"gate_stops"})))

    # --- A7 client: dropped is the only client anomaly at v17, and is never optable
    # (the responses.rate_limited arm left with the wire response; law B1 is deleted) ---
    hits("A7 client dropped", law_A7_client(
        _cli(1000), _cli(6000, over={"dropped": 1}), "selftest",
        frozenset({"saturated"})), "A7")

    # --- B2: bandwidth pacing against the configured cap ---
    cap = 262144
    clean("B2 under cap", law_B2([
        _srv(1000), _srv(6000, over={"bandwidth.total_bytes": 1_000_000})], cap))
    hits("B2 cap burst", law_B2([
        _srv(1000), _srv(6000, over={"bandwidth.total_bytes": 2_000_000})], cap), "B2")
    # Whole-run cumulative leg (arms only past 30 s — the 5 s fixtures above never reach
    # it): a sustained 1.2x-of-cap pace hides inside the 1.3x per-window headroom
    # (1_572_864 per 5 s window < cap*5*1.3 = 1_703_936), so only the cumulative 5%
    # bound can catch it. 12 windows x 5 s = 60 s span.
    clean("B2 sustained under cap across 60s", law_B2(
        [_srv(1000 + 5000 * i, over={"bandwidth.total_bytes": 1_000_000 * i})
         for i in range(13)], cap))
    sustained = law_B2(
        [_srv(1000 + 5000 * i, over={"bandwidth.total_bytes": 1_572_864 * i})
         for i in range(13)], cap)
    hits("B2 sustained 1.2x pacing regression", sustained, "B2")
    cases[0] += 1
    assert all("whole-run" in v.window for v in sustained), \
        f"B2 sustained: the CUMULATIVE leg must fire (per-window stays under headroom): " \
        f"{[v.line() for v in sustained]}"

    # --- Quiescence predicate ---
    quiet_player = {"name": "p", "held_sync": 0, "held_gen": 0, "send_queue": 0,
                    "backlog": 0}
    q1 = _srv(1000)
    q1["players"] = [dict(quiet_player)]
    q2 = _srv(6000)
    q2["players"] = [dict(quiet_player)]
    cases[0] += 1
    assert server_pair_quiescent(q1, q2), "quiescence: stable pair must qualify"
    moving = _srv(6000, over={"service.requests_received": 1})
    moving["players"] = [dict(quiet_player)]
    cases[0] += 1
    assert not server_pair_quiescent(q1, moving), "quiescence: moving counter must disqualify"
    draining = _srv(6000, over={"disk.pending": 1})
    draining["players"] = [dict(quiet_player)]
    cases[0] += 1
    assert not server_pair_quiescent(q1, draining), "quiescence: nonzero drain must disqualify"
    store_draining = _srv(6000, over={"store.queue": 1})
    store_draining["players"] = [dict(quiet_player)]
    cases[0] += 1
    assert not server_pair_quiescent(q1, store_draining), \
        "quiescence: a nonzero store batcher queue (undeposited serves) must disqualify"
    held = _srv(6000)
    held["players"] = [{"name": "p", "held_sync": 0, "held_gen": 1, "send_queue": 0,
                        "backlog": 0}]
    cases[0] += 1
    assert not server_pair_quiescent(q1, held), "quiescence: held player slot must disqualify"
    dirty_ok = _srv(6000, over={"dirty.pending": QUIESCENCE_DIRTY_PENDING_TOLERANCE})
    dirty_ok["players"] = [dict(quiet_player)]
    cases[0] += 1
    assert server_pair_quiescent(q1, dirty_ok), \
        "quiescence: dirty.pending within tolerance (benign light-settle drift) stays quiescent"
    dirty_over = _srv(6000, over={"dirty.pending": QUIESCENCE_DIRTY_PENDING_TOLERANCE + 1})
    dirty_over["players"] = [dict(quiet_player)]
    cases[0] += 1
    assert not server_pair_quiescent(q1, dirty_over), \
        "quiescence: dirty.pending beyond tolerance (storm/backlog) must still disqualify"
    # v17: a retained want entry is real outstanding work no other gauge reports.
    backlogged = _srv(6000)
    backlogged["players"] = [{"name": "p", "held_sync": 0, "held_gen": 0, "send_queue": 0,
                              "backlog": 1}]
    cases[0] += 1
    assert not server_pair_quiescent(q1, backlogged), \
        "quiescence: a nonzero player backlog (want entries the router still owes) must " \
        "disqualify — held_sync/held_gen count ADMITTED work only"

    # --- Quiescence CLIENT MIRROR (find_quiescent) ---
    # Pre-existing coverage gap: server_pair_quiescent was directly tested above, but the
    # client-side half of the predicate (tracker_in_flight / queued, joined on the nearest
    # in-skew client row) had NO selftest at all — an inverted or dropped client mirror
    # would have opened windows mid-traffic while every law stayed green.
    def qpoints_for(cli_over=None, srv_players=None, skew=0):
        players = srv_players if srv_players is not None else [dict(quiet_player)]
        s1, s2 = _srv(1000, seg=1), _srv(6000, seg=1)
        s1["players"], s2["players"] = players, [dict(p) for p in players]
        return find_quiescent([s1, s2],
                              {1: [_cli(6000 + skew, seg=0, over=cli_over or {})]})

    cases[0] += 1
    assert len(qpoints_for()) == 1, \
        "quiescence client mirror: a still server pair joined to an idle client row must " \
        "produce exactly one window"
    cases[0] += 1
    assert qpoints_for({"tracker_in_flight": 1}) == [], \
        "quiescence client mirror: tracker_in_flight != 0 (a declared-and-unanswered " \
        "want) must disqualify the window"
    cases[0] += 1
    assert qpoints_for({"queued": 1}) == [], \
        "quiescence client mirror: queued != 0 (undecoded columns still in the ingest " \
        "queue) must disqualify the window"
    cases[0] += 1
    assert qpoints_for(skew=SKEW_MS + 1) == [], \
        "quiescence client mirror: a client row beyond SKEW_MS proves nothing about the " \
        "server instant and must not open a window"
    cases[0] += 1
    assert qpoints_for(srv_players=[{"name": "p", "held_sync": 0, "held_gen": 0,
                                     "send_queue": 0, "backlog": 1}]) == [], \
        "quiescence: a player backlog must disqualify the whole window even when the " \
        "server pair is otherwise stable and the client looks idle"

    # --- Disc completeness named check ---
    disc = make_disc_completeness("fresh-backfill")
    def disc_ctx(known, empty, config):
        return Ctx(scenario="fresh-backfill", server_snaps=[_srv(1000)], commands=[],
                   joins=[], ends=[], runs={1: [_cli(9000, over={
                       "columns.known": known, "columns.empty": empty})]},
                   qpoints=[], config=config)
    # lod 24 / exclusion 8 -> annulus 49^2 - 17^2 = 2112
    clean("disc complete at boundary", list(disc(disc_ctx(2000, 112, {"lodDistanceChunks": 24}))))
    hits("disc one orphaned position", list(disc(disc_ctx(2000, 111, {"lodDistanceChunks": 24}))),
         "disc-completeness")
    hits("disc config missing lod", list(disc(disc_ctx(99999, 0, {}))), "disc-completeness")

    # --- Far-players baseline neutrality (E1) ---
    clean("far players inert", far_players_inert_violations([_srv(1000)]))
    hits("far players moved", far_players_inert_violations(
        [_srv(1000, over={"far_players.roster_frames": 3})]), "far-players-inert")
    hits("far players gauge moved mid-run only", far_players_inert_violations(
        [_srv(1000, over={"far_players.subscribers": 1}), _srv(2000)]),
        "far-players-inert")

    # --- Region-summary baseline neutrality (plan §6/§8 — the far-players mirror) ---
    clean("summary inert", summary_inert_violations([_srv(1000)]))
    clean("summary inert without the group (pre-summary recording)",
          summary_inert_violations([{"wallMs": 1000}]))
    hits("summary moved on a gated run", summary_inert_violations(
        [_srv(1000, over={"summary.requests": 1})]), "summary-inert")
    hits("summary moved mid-run only", summary_inert_violations(
        [_srv(1000, over={"summary.frames": 2}), _srv(2000)]), "summary-inert")
    clean("summary opt-in scenario exempt", summary_inert_violations(
        [_srv(1000, over={"summary.requests": 5, "summary.frames": 5})],
        scenario="warm-rejoin-summary"))

    # --- Window floors (vacuous-pass guard) ---
    clean("floors met", check_window_floors({(1, 0): 3}, {(1, 0): 3}))
    hits("floors short", check_window_floors({(1, 0): 3}, {(1, 0): 2}), "law-coverage")
    hits("floors no windows", check_window_floors({(1, 0): 3}, {}), "law-coverage")

    # --- Named-check fixtures (synthetic Ctx, like disc completeness above) ---
    def _cmd(wall, cmd, ok=True):
        return {"event": "command", "wallMs": wall, "tick": wall // 50, "cmd": cmd,
                "anchor": 1, "at": wall // 1000, "ok": ok}

    def _join(wall, idx):
        return {"event": "join", "wallMs": wall, "tick": wall // 50, "player": "p",
                "joinIndex": idx}

    def _ctx(**kw):
        base = dict(scenario="selftest", server_snaps=[], commands=[], joins=[], ends=[],
                    runs={}, qpoints=[], config={})
        base.update(kw)
        return Ctx(**base)

    # --- Action rows segment the client series (clearcache counter reset) ---
    import tempfile
    with tempfile.NamedTemporaryFile("w", suffix=".jsonl", delete=False) as tf:
        tf.write(json.dumps({"event": "snapshot", "wallMs": 1000, "dimension": "minecraft:overworld"}) + "\n")
        tf.write(json.dumps({"event": "action", "wallMs": 2000, "action": "clearcache", "atSeconds": 60}) + "\n")
        tf.write(json.dumps({"event": "snapshot", "wallMs": 3000, "dimension": "minecraft:overworld"}) + "\n")
        tmp_path = Path(tf.name)
    try:
        w, uk, ue = [], set(), set()
        snaps, actions = load_client_run(tmp_path, w, uk, ue)
        cases[0] += 1
        assert [s["_seg"] for s in snaps] == [0, 1], \
            f"action segmentation: expected segs [0, 1], got {[s['_seg'] for s in snaps]}"
        cases[0] += 1
        assert len(actions) == 1 and actions[0]["action"] == "clearcache", \
            "action segmentation: action row must be returned separately"
        cases[0] += 1
        assert not uk and not ue, f"action segmentation: unexpected unknowns {uk} {ue}"
    finally:
        tmp_path.unlink()

    # --- Client disconnect-row completion gate: exercise the REAL gate function (not a copy) ---
    # A controlled exit ends with a disconnect row → gate passes; a crashed run lacks it → gate
    # fires the run-completion violation. Both cases run the loaded snaps through the exact
    # client_run_completion_violations() that run_checker calls, so an inverted/moved/dropped
    # gate would flip these assertions.
    with tempfile.NamedTemporaryFile("w", suffix=".jsonl", delete=False) as tf:
        tf.write(json.dumps({"event": "snapshot", "wallMs": 1000, "dimension": "minecraft:overworld"}) + "\n")
        tf.write(json.dumps({"event": "disconnect", "wallMs": 2000, "dimension": "minecraft:overworld",
                             "reason": "kicked"}) + "\n")
        tmp_path = Path(tf.name)
    try:
        w, uk, ue = [], set(), set()
        snaps, _ = load_client_run(tmp_path, w, uk, ue)
        cases[0] += 1
        assert client_run_completion_violations("client-run1.jsonl", snaps) == [], \
            "disconnect gate: a controlled exit (disconnect row present) must not fire a violation"
    finally:
        tmp_path.unlink()
    with tempfile.NamedTemporaryFile("w", suffix=".jsonl", delete=False) as tf:
        tf.write(json.dumps({"event": "snapshot", "wallMs": 1000, "dimension": "minecraft:overworld"}) + "\n")
        tf.write(json.dumps({"event": "snapshot", "wallMs": 2000, "dimension": "minecraft:overworld"}) + "\n")
        tmp_path = Path(tf.name)
    try:
        w, uk, ue = [], set(), set()
        snaps, _ = load_client_run(tmp_path, w, uk, ue)
        cases[0] += 1
        viols = client_run_completion_violations("client-run1.jsonl", snaps)
        assert len(viols) == 1 and viols[0].law == "run-completion", \
            "disconnect gate: a crashed run (no disconnect row) must fire a run-completion violation"
    finally:
        tmp_path.unlink()

    # --- Folia mapped command rows are schema-known (no unknown-key warning) ---
    with tempfile.NamedTemporaryFile("w", suffix=".jsonl", delete=False) as tf:
        tf.write(json.dumps({"event": "command", "wallMs": 1000, "tick": 20,
                             "cmd": "save-all flush", "anchor": 1, "at": 30,
                             "ok": True, "mapped": True}) + "\n")
        tmp_path = Path(tf.name)
    try:
        w, uk, ue = [], set(), set()
        server = load_server(tmp_path, w, uk, ue)
        cases[0] += 1
        assert len(server["commands"]) == 1 and server["commands"][0].get("mapped") is True, \
            "folia mapped command: row must load as a command"
        cases[0] += 1
        assert not uk, f"folia mapped command: 'mapped' must be schema-known, got unknowns {uk}"
    finally:
        tmp_path.unlink()

    # --- Handshake flag check: legacy rows pass, mismatch caught ---
    hs = make_handshake_check("fresh-backfill")
    clean("handshake legacy rows (no flag)", list(hs(_ctx(runs={1: [_cli(1000)]}))))
    clean("handshake enabled", list(hs(_ctx(runs={1: [_cli(1000, over={"server_enabled": True})]}))))
    hits("handshake disabled on enabled scenario",
         list(hs(_ctx(runs={1: [_cli(1000, over={"server_enabled": False})]}))), "handshake")

    # --- enabled-false: both sides idle; any movement caught ---
    ef_cli = _cli(1000, over={"server_enabled": False})
    clean("enabled-false idle", list(check_enabled_false(_ctx(
        server_snaps=[_srv(1000)], runs={1: [ef_cli]}))))
    hits("enabled-false server counter moved", list(check_enabled_false(_ctx(
        server_snaps=[_srv(1000, over={"service.requests_received": 5})],
        runs={1: [ef_cli]}))), "enabled-false")
    hits("enabled-false dirty leak", list(check_enabled_false(_ctx(
        server_snaps=[_srv(1000, over={"dirty.pending": 3})],
        runs={1: [ef_cli]}))), "enabled-false")
    hits("enabled-false flag true", list(check_enabled_false(_ctx(
        server_snaps=[_srv(1000)],
        runs={1: [_cli(1000, over={"server_enabled": True})]}))), "enabled-false")

    # --- fresh-backfill named check (was selftest-dark): gen>500, quiescent tail, confirmed>24 ---
    fb_srv = [_srv(1000), _srv(6000, over={"generation.completed": 600})]
    fb_cli = _cli(6000, over={"scan.confirmed": 30})
    clean("fresh-backfill healthy", list(check_fresh_backfill(_ctx(
        server_snaps=fb_srv, runs={1: [fb_cli]}, quiescent_server={1}))))
    hits("fresh-backfill too little generation", list(check_fresh_backfill(_ctx(
        server_snaps=[_srv(1000), _srv(6000, over={"generation.completed": 400})],
        runs={1: [fb_cli]}, quiescent_server={1}))), "fresh-backfill")
    hits("fresh-backfill non-quiescent tail", list(check_fresh_backfill(_ctx(
        server_snaps=fb_srv, runs={1: [fb_cli]}, quiescent_server=set()))), "fresh-backfill")
    hits("fresh-backfill scan not confirmed", list(check_fresh_backfill(_ctx(
        server_snaps=fb_srv, runs={1: [_cli(6000, over={"scan.confirmed": 20})]},
        quiescent_server={1}))), "fresh-backfill")
    hits("fresh-backfill no client", list(check_fresh_backfill(_ctx(
        server_snaps=fb_srv, runs={}, quiescent_server={1}))), "fresh-backfill")

    # --- hybrid-boundary named check: gen>15000, quiescent tail, confirmed>72 ---
    hb_srv = [_srv(1000), _srv(6000, over={"generation.completed": 19000})]
    hb_cli = _cli(6000, over={"scan.confirmed": 73})
    clean("hybrid-boundary healthy", list(check_hybrid_boundary(_ctx(
        server_snaps=hb_srv, runs={1: [hb_cli]}, quiescent_server={1}))))
    hits("hybrid-boundary too little generation", list(check_hybrid_boundary(_ctx(
        server_snaps=[_srv(1000), _srv(6000, over={"generation.completed": 12000})],
        runs={1: [hb_cli]}, quiescent_server={1}))), "hybrid-boundary")
    hits("hybrid-boundary non-quiescent tail", list(check_hybrid_boundary(_ctx(
        server_snaps=hb_srv, runs={1: [hb_cli]}, quiescent_server=set()))), "hybrid-boundary")
    hits("hybrid-boundary below the phase boundary", list(check_hybrid_boundary(_ctx(
        server_snaps=hb_srv, runs={1: [_cli(6000, over={"scan.confirmed": 64})]},
        quiescent_server={1}))), "hybrid-boundary")
    hits("hybrid-boundary no client", list(check_hybrid_boundary(_ctx(
        server_snaps=hb_srv, runs={}, quiescent_server={1}))), "hybrid-boundary")

    # --- warm-rejoin named check (was selftest-dark): run2 warm-cache signals ---
    wr_r1 = _cli(1000, over={"responses.columns": 1200})
    wr_r2 = _cli(2000, over={"responses.up_to_date": 700, "responses.columns": 200,
                             "requested_total": 1500})
    clean("warm-rejoin healthy", list(check_warm_rejoin(_ctx(runs={1: [wr_r1], 2: [wr_r2]}))))
    hits("warm-rejoin missing run2", list(check_warm_rejoin(_ctx(runs={1: [wr_r1]}))), "warm-rejoin")
    hits("warm-rejoin cold cache low utd", list(check_warm_rejoin(_ctx(
        runs={1: [wr_r1], 2: [_cli(2000, over={"responses.up_to_date": 100,
              "responses.columns": 200, "requested_total": 1500})]}))), "warm-rejoin")
    hits("warm-rejoin full re-download", list(check_warm_rejoin(_ctx(
        runs={1: [wr_r1], 2: [_cli(2000, over={"responses.up_to_date": 700,
              "responses.columns": 1300, "requested_total": 1500})]}))), "warm-rejoin")
    hits("warm-rejoin no revalidation", list(check_warm_rejoin(_ctx(
        runs={1: [wr_r1], 2: [_cli(2000, over={"responses.up_to_date": 700,
              "responses.columns": 200, "requested_total": 500})]}))), "warm-rejoin")

    # --- dirty-broadcast named check (was selftest-dark): broadcast rose, re-fetch, dirty drained ---
    db_cmd = {"event": "command", "cmd": "setblock 0 5 0 minecraft:stone", "wallMs": 5000,
              "anchor": 1, "at": 4}
    db_srv = [_srv(2000), _srv(8000, over={"dirty.broadcast_positions": 5})]
    db_cli = [_cli(2000, over={"received_columns": 100}), _cli(10000, over={"received_columns": 150})]
    clean("dirty-broadcast healthy", list(check_dirty_broadcast(_ctx(
        server_snaps=db_srv, commands=[db_cmd], runs={1: db_cli}))))
    hits("dirty-broadcast no setblock", list(check_dirty_broadcast(_ctx(
        server_snaps=db_srv, commands=[], runs={1: db_cli}))), "dirty-broadcast")
    hits("dirty-broadcast server broadcast nothing", list(check_dirty_broadcast(_ctx(
        server_snaps=[_srv(2000), _srv(8000)], commands=[db_cmd], runs={1: db_cli}))), "dirty-broadcast")
    hits("dirty-broadcast dirty stuck above baseline", list(check_dirty_broadcast(_ctx(
        server_snaps=db_srv, commands=[db_cmd],
        runs={1: [_cli(2000, over={"received_columns": 100}),
                  _cli(10000, over={"received_columns": 150, "columns.dirty": 4})]}))), "dirty-broadcast")
    hits("dirty-broadcast no re-fetch", list(check_dirty_broadcast(_ctx(
        server_snaps=db_srv, commands=[db_cmd],
        runs={1: [_cli(2000, over={"received_columns": 100}),
                  _cli(10000, over={"received_columns": 100})]}))), "dirty-broadcast")

    # --- dimension-trip named check (was selftest-dark): overworld -> end -> overworld, each segment quiescent ---
    def _dt(mid_dim):
        return [_cli(1000, seg=0, over={"dimension": "minecraft:overworld"}),
                _cli(2000, seg=0, over={"dimension": "minecraft:overworld"}),
                _cli(3000, seg=1, over={"dimension": mid_dim}),
                _cli(4000, seg=1, over={"dimension": mid_dim}),
                _cli(5000, seg=2, over={"dimension": "minecraft:overworld"}),
                _cli(6000, seg=2, over={"dimension": "minecraft:overworld"})]
    dt_quiescent = {(1, 1), (1, 3), (1, 5)}  # last index of each of the 3 segments
    clean("dimension-trip healthy", list(check_dimension_trip(_ctx(
        runs={1: _dt("minecraft:the_end")}, quiescent_client=dt_quiescent))))
    hits("dimension-trip wrong dimension sequence", list(check_dimension_trip(_ctx(
        runs={1: _dt("minecraft:the_nether")}, quiescent_client=dt_quiescent))), "dimension-trip")
    hits("dimension-trip segment not quiescent", list(check_dimension_trip(_ctx(
        runs={1: _dt("minecraft:the_end")}, quiescent_client={(1, 1), (1, 5)}))), "dimension-trip")
    hits("dimension-trip no client", list(check_dimension_trip(_ctx(
        runs={}, quiescent_client=dt_quiescent))), "dimension-trip")

    # --- cold-restart-resync: warm dominance; re-download caught ---
    clean("cold-restart warm", list(check_cold_restart_resync(_ctx(
        server_snaps=[_srv(1000, over={"service.up_to_date": 2000})],
        runs={1: [_cli(1000, over={"responses.up_to_date": 2000, "responses.columns": 300,
                                   "requested_total": 2400})]},
        quiescent_server={0}))))
    hits("cold-restart re-download dominated", list(check_cold_restart_resync(_ctx(
        server_snaps=[_srv(1000, over={"service.up_to_date": 600})],
        runs={1: [_cli(1000, over={"responses.up_to_date": 600, "responses.columns": 2100,
                                   "requested_total": 2700})]},
        quiescent_server={0}))), "cold-restart-resync")
    # WI-5 (xaero-scatter-remediation-plan.md): the first-observed-save storm — the recorded
    # BEFORE shape (449 marked / 0 suppressed) reds on BOTH legs; a seeded restart is clean.
    clean("cold-restart seeded loads suppress the first saves", list(check_cold_restart_resync(_ctx(
        server_snaps=[_srv(1000, over={"service.up_to_date": 2000, "dirty.marked_total": 5,
                                       "dirty.suppressed_total": 800})],
        runs={1: [_cli(1000, over={"responses.up_to_date": 2000, "responses.columns": 300,
                                   "requested_total": 2400})]},
        quiescent_server={0}))))
    hits("cold-restart first-observed-save storm", list(check_cold_restart_resync(_ctx(
        server_snaps=[_srv(1000, over={"service.up_to_date": 2000, "dirty.marked_total": 449,
                                       "dirty.suppressed_total": 0})],
        runs={1: [_cli(1000, over={"responses.up_to_date": 2000, "responses.columns": 300,
                                   "requested_total": 2400})]},
        quiescent_server={0}))), "cold-restart-resync")
    hits("cold-restart suppression ratio collapsed", list(check_cold_restart_resync(_ctx(
        server_snaps=[_srv(1000, over={"service.up_to_date": 2000, "dirty.marked_total": 40,
                                       "dirty.suppressed_total": 100})],
        runs={1: [_cli(1000, over={"responses.up_to_date": 2000, "responses.columns": 300,
                                   "requested_total": 2400})]},
        quiescent_server={0}))), "cold-restart-resync")

    # --- teleport-prune: bounded map; double disc caught (lod 24 -> annulus 2112) ---
    clean("teleport-prune pruned", list(check_teleport_prune(_ctx(
        server_snaps=[_srv(1000, over={"generation.completed": 1500})],
        runs={1: [_cli(1000, over={"columns.known": 2000, "columns.empty": 200})]},
        config={"lodDistanceChunks": 24}, quiescent_server={0}))))
    hits("teleport-prune double disc", list(check_teleport_prune(_ctx(
        server_snaps=[_srv(1000, over={"generation.completed": 1500})],
        runs={1: [_cli(1000, over={"columns.known": 4000, "columns.empty": 200})]},
        config={"lodDistanceChunks": 24}, quiescent_server={0}))), "teleport-prune")

    # --- dirty-resave: flat after no-edit re-saves; rise/legacy-skip both proven ---
    resave_cmds = [_cmd(64_000, "save-all"), _cmd(95_000, "save-all")]
    resave_cli = [_cli(90_000, over={"received_columns": 2200}),
                  _cli(140_000, over={"received_columns": 2200})]
    clean("dirty-resave quiet", list(check_dirty_resave_quiet(_ctx(
        server_snaps=[_srv(90_000, over={"dirty.broadcast_positions": 40}),
                      _srv(140_000, over={"dirty.broadcast_positions": 40})],
        commands=resave_cmds, runs={1: resave_cli}))))
    # Benign skylight-settle drift (+12, within tolerance) must NOT read as a violation.
    clean("dirty-resave light-settle drift tolerated", list(check_dirty_resave_quiet(_ctx(
        server_snaps=[_srv(90_000, over={"dirty.broadcast_positions": 40}),
                      _srv(140_000, over={"dirty.broadcast_positions": 52})],
        commands=resave_cmds, runs={1: resave_cli}))))
    # A real suppression failure / reload loop (+30, beyond tolerance) still fails.
    hits("dirty-resave broadcast rise beyond tolerance", list(check_dirty_resave_quiet(_ctx(
        server_snaps=[_srv(90_000, over={"dirty.broadcast_positions": 40}),
                      _srv(140_000, over={"dirty.broadcast_positions": 70})],
        commands=resave_cmds, runs={1: resave_cli}))), "dirty-resave")
    hits("dirty-resave client re-download", list(check_dirty_resave_quiet(_ctx(
        server_snaps=[_srv(90_000, over={"dirty.broadcast_positions": 40}),
                      _srv(140_000, over={"dirty.broadcast_positions": 40})],
        commands=resave_cmds,
        runs={1: [_cli(90_000, over={"received_columns": 2200}),
                  _cli(140_000, over={"received_columns": 2230})]}))), "dirty-resave")
    clean("dirty-resave legacy single save-all", list(check_dirty_resave_quiet(_ctx(
        server_snaps=[_srv(90_000, over={"dirty.broadcast_positions": 40})],
        commands=[_cmd(64_000, "save-all")], runs={1: resave_cli}))))
    clean("dirty-resave flush variant matches", list(check_dirty_resave_quiet(_ctx(
        server_snaps=[_srv(90_000, over={"dirty.broadcast_positions": 40}),
                      _srv(140_000, over={"dirty.broadcast_positions": 40})],
        commands=[_cmd(64_000, "save-all flush"), _cmd(95_000, "save-all flush")],
        runs={1: resave_cli}))))
    # Warmup save-alls BEFORE the setblock (the 2026-07-30 Moonrise-deferred-autosave
    # timeline hardening) must not shift the no-edit window onto the edit itself: the
    # anchor is the save-alls AFTER the setblock, so [1] stays the t+95 re-save.
    clean("dirty-resave warmup save-alls before the edit do not shift the window",
          list(check_dirty_resave_quiet(_ctx(
        server_snaps=[_srv(90_000, over={"dirty.broadcast_positions": 40}),
                      _srv(140_000, over={"dirty.broadcast_positions": 40})],
        commands=[_cmd(30_000, "save-all flush"), _cmd(45_000, "save-all flush"),
                  _cmd(62_000, "setblock 320 310 0 minecraft:stone"),
                  _cmd(64_000, "save-all flush"), _cmd(95_000, "save-all flush")],
        runs={1: resave_cli}))))

    # --- dirty-while-offline probes: rise/equality + drain mechanics (hold while empty,
    # drain after rejoin) all discriminating ---
    def dwo_ctx(changed2, control2, gap_broadcast=10, post_broadcast=13):
        return _ctx(
            server_snaps=[_srv(55_000, over={"dirty.broadcast_positions": 10}),
                          _srv(80_000, over={"dirty.broadcast_positions": gap_broadcast}),
                          _srv(205_000, over={"dirty.broadcast_positions": post_broadcast}),
                          _srv(300_000, over={"dirty.broadcast_positions": post_broadcast})],
            commands=[_cmd(60_000, "kick @a soak-phase-end"),
                      _cmd(63_000, "setblock 320 310 0 minecraft:stone"),
                      _cmd(65_000, "save-all")],
            joins=[_join(1000, 1), _join(200_000, 2)],
            runs={1: [_cli(55_000, over={"probes": {"20:0": 100, "-20:0": 100},
                                         "responses.up_to_date": 0})],
                  2: [_cli(300_000, over={"probes": {"20:0": changed2, "-20:0": control2},
                                          "responses.up_to_date": 800})]})
    clean("dirty-while-offline edit surfaced", list(check_dirty_while_offline(
        dwo_ctx(changed2=200, control2=100))))
    hits("dirty-while-offline edit lost", list(check_dirty_while_offline(
        dwo_ctx(changed2=100, control2=100))), "dirty-while-offline")
    hits("dirty-while-offline control re-downloaded", list(check_dirty_while_offline(
        dwo_ctx(changed2=200, control2=150))), "dirty-while-offline")
    # Drain-counter motion during/after the gap is timing-dependent in both directions
    # (settled by live runs) — no fixtures assert it. The durable outcome is the probe
    # timestamp legs above; the premise guard below pins the edit landing inside the gap.
    hits("dirty-while-offline edit raced past the gap", list(check_dirty_while_offline(
        _ctx(server_snaps=[_srv(55_000, over={"dirty.broadcast_positions": 10}),
                           _srv(300_000, over={"dirty.broadcast_positions": 13})],
             commands=[_cmd(60_000, "kick @a soak-phase-end"),
                       _cmd(210_000, "setblock 320 310 0 minecraft:stone"),
                       _cmd(212_000, "save-all")],
             joins=[_join(1000, 1), _join(200_000, 2)],
             runs={1: [_cli(55_000, over={"probes": {"20:0": 100, "-20:0": 100},
                                          "responses.up_to_date": 0})],
                   2: [_cli(300_000, over={"probes": {"20:0": 200, "-20:0": 100},
                                           "responses.up_to_date": 800})]}))),
        "dirty-while-offline")

    # --- warm-rejoin-summary: bulk validation + the poisoned-tile honesty leg ---
    def wrs_ctx(validated=1400, clean_tiles=15, stale_tiles=1, no_region=9, req2=900,
                cols1=4200, pre_cols=2100, srv_reqs=2, srv_frames=2, srv_bytes=178,
                srv_rf=0, known2=2144, cols2=16, split_run1=True, poison_ok=True,
                with_poison=True, srv_stamps=800, srv_stamps_bytes=8200,
                cli_stamps=800, platform="fabric"):
        run1 = ([_cli(1000, seg=0, over={"responses.columns": pre_cols}),
                 _cli(200_000, seg=1, over={"responses.columns": cols1})]
                if split_run1 else [_cli(1000, over={"responses.columns": cols1})])
        commands = ([_cmd(195_000, "setblock 264 310 264 minecraft:stone", ok=poison_ok)]
                    if with_poison else []) + [_cmd(210_000, "kick @a soak-phase-end")]
        return _ctx(
            platform=platform,
            server_snaps=[_srv(1000), _srv(200_000, over={
                "summary.requests": srv_reqs, "summary.frames": srv_frames,
                "summary.bytes": srv_bytes, "summary.range_filtered": srv_rf,
                "summary.stamps_entries": srv_stamps,
                "summary.stamps_frames": 2 if srv_stamps else 0,
                "summary.stamps_bytes": srv_stamps_bytes})],
            commands=commands,
            runs={1: run1,
                  2: [_cli(300_000, over={
                      "summary.columns_validated": validated,
                      "summary.tiles_clean": clean_tiles,
                      "summary.tiles_stale": stale_tiles,
                      "summary.tiles_no_region": no_region,
                      "summary.stamps_applied": cli_stamps,
                      "requested_total": req2,
                      "columns.known": known2,
                      "responses.columns": cols2})]})
    clean("warm-rejoin-summary healthy", list(check_warm_rejoin_summary(wrs_ctx())))
    # wrs carries NO stamps floors (Phase B fold — the population is racy on every
    # platform; stamp-heal-prime is the lane's floor-bearing gate): zero stamps must
    # NOT red here, on any platform.
    clean("warm-rejoin-summary zero-stamps tolerated (raced latch, any platform)",
          list(check_warm_rejoin_summary(
              wrs_ctx(srv_stamps=0, srv_stamps_bytes=0, cli_stamps=0))))
    clean("warm-rejoin-summary zero-stamps tolerated on paper",
          list(check_warm_rejoin_summary(
              wrs_ctx(srv_stamps=0, srv_stamps_bytes=0, cli_stamps=0, platform="paper"))))
    hits("warm-rejoin-summary validated too little", list(check_warm_rejoin_summary(
        wrs_ctx(validated=100))), "warm-rejoin-summary")
    hits("warm-rejoin-summary too few clean tiles", list(check_warm_rejoin_summary(
        wrs_ctx(clean_tiles=8))), "warm-rejoin-summary")
    hits("warm-rejoin-summary no-region skip dead", list(check_warm_rejoin_summary(
        wrs_ctx(no_region=0, clean_tiles=24))), "warm-rejoin-summary")
    hits("warm-rejoin-summary poison premise lost (no setblock)",
         list(check_warm_rejoin_summary(wrs_ctx(with_poison=False, stale_tiles=1))),
         "warm-rejoin-summary")
    hits("warm-rejoin-summary poison premise lost (setblock failed)",
         list(check_warm_rejoin_summary(wrs_ctx(poison_ok=False))),
         "warm-rejoin-summary")
    hits("warm-rejoin-summary request/frame loop", list(check_warm_rejoin_summary(
        wrs_ctx(srv_reqs=9, srv_frames=9))), "warm-rejoin-summary")
    hits("warm-rejoin-summary frame-bytes runaway", list(check_warm_rejoin_summary(
        wrs_ctx(srv_bytes=5000))), "warm-rejoin-summary")
    hits("warm-rejoin-summary window range-clamped", list(check_warm_rejoin_summary(
        wrs_ctx(srv_rf=3))), "warm-rejoin-summary")
    hits("warm-rejoin-summary stamps bytes runaway", list(check_warm_rejoin_summary(
        wrs_ctx(srv_stamps_bytes=200_000))), "warm-rejoin-summary")
    hits("warm-rejoin-summary poisoned tile validated", list(check_warm_rejoin_summary(
        wrs_ctx(stale_tiles=0))), "warm-rejoin-summary")
    hits("warm-rejoin-summary runaway re-declares", list(check_warm_rejoin_summary(
        wrs_ctx(req2=5000, known2=6000))), "warm-rejoin-summary")
    # The self-scaling suppression pin (harness review MAJOR-2): the feature-dead
    # recording measured requested == columns.known (2144) — that exact shape must red
    # even though it sits far below the absolute runaway ceiling.
    hits("warm-rejoin-summary feature-dead requested==known", list(check_warm_rejoin_summary(
        wrs_ctx(req2=2144, known2=2144))), "warm-rejoin-summary")
    hits("warm-rejoin-summary run2 re-downloaded", list(check_warm_rejoin_summary(
        wrs_ctx(cols2=900))), "warm-rejoin-summary")
    hits("warm-rejoin-summary clearcache premise lost", list(check_warm_rejoin_summary(
        wrs_ctx(cols1=2000, pre_cols=100))), "warm-rejoin-summary")
    hits("warm-rejoin-summary re-serve only partial", list(check_warm_rejoin_summary(
        wrs_ctx(cols1=4200, pre_cols=3500))), "warm-rejoin-summary")
    hits("warm-rejoin-summary clearcache never split the series", list(check_warm_rejoin_summary(
        wrs_ctx(split_run1=False))), "warm-rejoin-summary")
    hits("warm-rejoin-summary server never framed", list(check_warm_rejoin_summary(
        wrs_ctx(srv_frames=1))), "warm-rejoin-summary")

    # --- dirty-while-offline-summary: the false-clean canary's probe legs ---
    def dwos_ctx(changed2=200, control2=100, stale_tiles=2, validated=1400,
                 clean_tiles=14, no_region=9, edit_wall=212_000, with_poison=True):
        commands = ([_cmd(195_000, "setblock 264 310 264 minecraft:stone")]
                    if with_poison else []) + [
            _cmd(210_000, "kick @a soak-phase-end"),
            _cmd(edit_wall, "setblock 580 310 -60 minecraft:stone"),
            _cmd(edit_wall + 1000, "save-all")]
        return _ctx(
            server_snaps=[_srv(1000), _srv(400_000)],
            commands=commands,
            joins=[_join(1000, 1), _join(250_000, 2)],
            runs={1: [_cli(200_000, over={"probes": {"36:-4": 100, "-4:36": 100}})],
                  2: [_cli(400_000, over={
                      "probes": {"36:-4": changed2, "-4:36": control2},
                      "summary.columns_validated": validated,
                      "summary.tiles_clean": clean_tiles,
                      "summary.tiles_stale": stale_tiles,
                      "summary.tiles_no_region": no_region})]})
    clean("dirty-while-offline-summary canary green", list(
        check_dirty_while_offline_summary(dwos_ctx())))
    hits("dirty-while-offline-summary FALSE CLEAN (edit not re-served)", list(
        check_dirty_while_offline_summary(dwos_ctx(changed2=100))),
        "dirty-while-offline-summary")
    hits("dirty-while-offline-summary control re-downloaded", list(
        check_dirty_while_offline_summary(dwos_ctx(control2=150))),
        "dirty-while-offline-summary")
    hits("dirty-while-offline-summary edited tile validated", list(
        check_dirty_while_offline_summary(dwos_ctx(stale_tiles=1))),
        "dirty-while-offline-summary")
    hits("dirty-while-offline-summary validated too little", list(
        check_dirty_while_offline_summary(dwos_ctx(validated=100))),
        "dirty-while-offline-summary")
    hits("dirty-while-offline-summary bulk did not validate", list(
        check_dirty_while_offline_summary(dwos_ctx(clean_tiles=8))),
        "dirty-while-offline-summary")
    hits("dirty-while-offline-summary no-region skip dead", list(
        check_dirty_while_offline_summary(dwos_ctx(no_region=0, clean_tiles=23))),
        "dirty-while-offline-summary")
    hits("dirty-while-offline-summary poison premise lost", list(
        check_dirty_while_offline_summary(dwos_ctx(with_poison=False))),
        "dirty-while-offline-summary")
    hits("dirty-while-offline-summary edit raced past the gap", list(
        check_dirty_while_offline_summary(dwos_ctx(edit_wall=260_000))),
        "dirty-while-offline-summary")

    # --- evicted-tscache-rejoin: the P1 header rung's live-gate floors ---
    def etr_ctx(hits_=1300, utd=1300, cols=750, boot_ts=40):
        return _ctx(
            server_snaps=[_srv(1000, over={
                              "tscache.size_per_dimension": {"minecraft:overworld": boot_ts}}),
                          _srv(150_000, over={"disk.header_hits": hits_})],
            runs={1: [_cli(150_000, over={"responses.up_to_date": utd,
                                          "responses.columns": cols})]})
    clean("evicted-tscache-rejoin rung carried it", list(
        check_evicted_tscache_rejoin(etr_ctx())))
    hits("evicted-tscache-rejoin rung never fired", list(
        check_evicted_tscache_rejoin(etr_ctx(hits_=10))), "evicted-tscache-rejoin")
    hits("evicted-tscache-rejoin full re-download", list(
        check_evicted_tscache_rejoin(etr_ctx(cols=2100))), "evicted-tscache-rejoin")
    # Harness review MAJOR-3: the rung answering up_to_date for the poisoned tile's
    # genuinely-stale residue (a false freshness claim) shows as cols ~0 — must red.
    hits("evicted-tscache-rejoin poisoned residue never re-downloaded", list(
        check_evicted_tscache_rejoin(etr_ctx(hits_=2328, utd=2328, cols=0))),
        "evicted-tscache-rejoin")
    hits("evicted-tscache-rejoin client saw nothing", list(
        check_evicted_tscache_rejoin(etr_ctx(utd=100))), "evicted-tscache-rejoin")
    # The eviction premise belt: a warm-booted tscache (the rm failed / path drifted)
    # makes every floor above vacuous — must red even with healthy-looking counters.
    hits("evicted-tscache-rejoin tscache booted warm", list(
        check_evicted_tscache_rejoin(etr_ctx(boot_ts=2100))), "evicted-tscache-rejoin")

    # --- stamp-heal-rejoin: the stale -> stamped -> clean headline gate ---
    def shr_ctx(stale=1, unknown=0, clean=15, validated=1900, req=420, known=2144,
                no_region=9):
        return _ctx(
            server_snaps=[_srv(1000), _srv(150_000)],
            runs={1: [_cli(150_000, over={
                "summary.tiles_stale": stale,
                "summary.tiles_unknown": unknown,
                "summary.tiles_clean": clean,
                "summary.tiles_no_region": no_region,
                "summary.columns_validated": validated,
                "requested_total": req,
                "columns.known": known})]})
    # --- stamp-heal-prime: the heal gate's before-pin ---
    def shp_ctx(stale=12, unknown=1, applied=1800, entries=1900, req=2300):
        return _ctx(
            server_snaps=[_srv(1000), _srv(200_000, over={
                "summary.stamps_entries": entries, "summary.stamps_frames": 3,
                "summary.stamps_bytes": 21_000, "summary.requests": 2,
                "summary.frames": 2})],
            runs={1: [_cli(1000)],
                  2: [_cli(300_000, over={
                      "summary.tiles_stale": stale,
                      "summary.tiles_unknown": unknown,
                      "summary.tiles_clean": 3,
                      "summary.tiles_no_region": 9,
                      "summary.stamps_applied": applied,
                      "requested_total": req,
                      "columns.known": 2144})]})
    clean("stamp-heal-prime inversion pinned", list(check_stamp_heal_prime(shp_ctx())))
    hits("stamp-heal-prime before-pin lost (no inversion)", list(check_stamp_heal_prime(
        shp_ctx(stale=1, unknown=0))), "stamp-heal-prime")
    hits("stamp-heal-prime ratchet never ran", list(check_stamp_heal_prime(
        shp_ctx(applied=5))), "stamp-heal-prime")
    hits("stamp-heal-prime server stamps dead", list(check_stamp_heal_prime(
        shp_ctx(entries=0))), "stamp-heal-prime")

    clean("stamp-heal-rejoin healed", list(check_stamp_heal_rejoin(shr_ctx())))
    hits("stamp-heal-rejoin stale set did not heal", list(check_stamp_heal_rejoin(
        shr_ctx(stale=499, clean=6, validated=900, req=1100))), "stamp-heal-rejoin")
    hits("stamp-heal-rejoin latched-unknown residue too big", list(check_stamp_heal_rejoin(
        shr_ctx(unknown=8))), "stamp-heal-rejoin")
    hits("stamp-heal-rejoin bulk did not validate", list(check_stamp_heal_rejoin(
        shr_ctx(validated=100))), "stamp-heal-rejoin")
    hits("stamp-heal-rejoin re-ask volume unhealed", list(check_stamp_heal_rejoin(
        shr_ctx(req=2300))), "stamp-heal-rejoin")
    hits("stamp-heal-rejoin cache never carried", list(check_stamp_heal_rejoin(
        shr_ctx(known=300))), "stamp-heal-rejoin")
    # ISOLATING cases (final panel: every leg above was proven only by adjacency —
    # each composite case tripped >= 2 legs, so any single leg could be deleted with
    # the selftest green; hits() cannot distinguish which leg fired). Each case below
    # trips EXACTLY ONE leg, so deleting that leg turns its violation list empty.
    hits("stamp-heal-rejoin carry premise alone", list(check_stamp_heal_rejoin(
        shr_ctx(known=300, req=100))), "stamp-heal-rejoin")
    hits("stamp-heal-rejoin geometry premise lost", list(check_stamp_heal_rejoin(
        shr_ctx(no_region=2))), "stamp-heal-rejoin")
    hits("stamp-heal-rejoin clean floor alone", list(check_stamp_heal_rejoin(
        shr_ctx(clean=6))), "stamp-heal-rejoin")
    # The admitted-variance boundary (26.1 port smoke, 2026-08-21): stale+unknown
    # exactly at the ceiling forces clean = 16 - 5 = 11, which must PASS — the
    # floor pairs with the ceiling, or the ceiling's variance tile is a lie.
    clean("stamp-heal-rejoin variance tile admitted", list(check_stamp_heal_rejoin(
        shr_ctx(stale=4, unknown=1, clean=11))))
    hits("stamp-heal-rejoin req>=known alone", list(check_stamp_heal_rejoin(
        shr_ctx(req=2000, known=1900))), "stamp-heal-rejoin")
    hits("stamp-heal-rejoin req ceiling alone", list(check_stamp_heal_rejoin(
        shr_ctx(req=2100, known=3000))), "stamp-heal-rejoin")

    # --- summary-inert: the client-side belt (harness review m4) ---
    hits("summary moved CLIENT-side only on a gated run", summary_inert_violations(
        [_srv(1000)], scenario="dirty-broadcast",
        client_runs={1: [_cli(1000, over={"summary.columns_validated": 3})]}),
        "summary-inert")

    # --- stress named checks: one clean + one catch case each (review finding) ---
    def stress_ctx(srv_over, cli_over, quiescent_last=True, config=None):
        return _ctx(
            server_snaps=[_srv(50_000), _srv(200_000, over=srv_over)],
            runs={1: [_cli(200_000, over=cli_over)]},
            quiescent_server={1} if quiescent_last else set(),
            config=config or {})

    # Server-owned generation: the storm declares the full constant want-set (800); misses
    # beyond the gen caps drop superseded (transient) and heal by re-declaration, so a small
    # fresh disc converges with bounded churn. Unbounded superseded growth = the healing
    # loop is broken. (Ceiling provisional at 500 — re-baselined at Task 10 to 800, and
    # again 2026-08-01 to 1500 against the adaptive-cadence measurement of 742: the
    # converging tail re-declares at up to 4 Hz.)
    storm_srv = {"service.superseded": 1100, "generation.completed": 165}
    storm_cli = {}
    clean("rate-limit-storm clean", list(check_rate_limit_storm(
        stress_ctx(storm_srv, storm_cli))))
    hits("rate-limit-storm churn never converged", list(check_rate_limit_storm(
        stress_ctx({**storm_srv, "service.superseded": 4200}, storm_cli))),
        "rate-limit-storm")

    # v17: the headroom gate must PREVENT saturation under the threads:1 flood; the backlog
    # absorbs what used to bounce. Premise flipped — saturated > 0 is the failure. This is
    # also where supersession is proven (moved from rate-limit-storm at Task 10): the flood
    # must actually leave entries undrained, or zero saturation proves nothing.
    sat_srv = {"disk.saturated": 0, "disk.submitted": 2000, "disk.completed": 2000,
               "service.superseded": 420}
    sat_cli = {}
    clean("disk-saturation clean", list(check_disk_saturation(
        stress_ctx(sat_srv, sat_cli))))
    hits("disk-saturation headroom gate leaked", list(check_disk_saturation(
        stress_ctx({**sat_srv, "disk.saturated": 12}, sat_cli))), "disk-saturation")
    hits("disk-saturation no supersession (flood premise broke)", list(check_disk_saturation(
        stress_ctx({**sat_srv, "service.superseded": 0}, sat_cli))), "disk-saturation")

    bw_cfg = {"bytesPerSecondLimitGlobal": 262144}
    bw_srv = {"service.queue_full": 30}
    bw_cli = {"received_bytes": 60_000_000}
    clean("bandwidth-throttle clean", list(check_bandwidth_throttle(
        stress_ctx(bw_srv, bw_cli, config=bw_cfg))))
    hits("bandwidth-throttle queue never filled", list(check_bandwidth_throttle(
        stress_ctx({**bw_srv, "service.queue_full": 0}, bw_cli, config=bw_cfg))),
        "bandwidth-throttle")

    gd_srv = {"disk.not_found": 2100, "generation.submitted": 0,
              "generation.completed": 0, "generation.active": 0}
    gd_cli = {"responses.not_generated": 2100, "received_columns": 26}
    clean("generation-disabled clean", list(check_generation_disabled(
        stress_ctx(gd_srv, gd_cli))))
    hits("generation-disabled gen leaked", list(check_generation_disabled(
        stress_ctx({**gd_srv, "generation.completed": 5}, gd_cli))),
        "generation-disabled")

    # Server-owned generation: on a gen-ENABLED server NOT_GENERATED must NEVER reach the
    # wire (it is session-permanent on the client) — transient capacity pressure drops
    # silently as superseded, which IS the scenario's churn subject now (R7).
    gcs_srv = {"generation.completed": 165, "service.superseded": 950}
    gcs_cli = {"responses.not_generated": 0}
    clean("generation-capacity-stress clean", list(check_generation_capacity_stress(
        stress_ctx(gcs_srv, gcs_cli))))
    hits("generation-capacity-stress stalled", list(check_generation_capacity_stress(
        stress_ctx({**gcs_srv, "generation.completed": 40}, gcs_cli, quiescent_last=False))),
        "generation-capacity-stress")
    hits("generation-capacity-stress permanence leak", list(check_generation_capacity_stress(
        stress_ctx(gcs_srv, {"responses.not_generated": 3}))),
        "generation-capacity-stress")
    hits("generation-capacity-stress churn premise broke", list(check_generation_capacity_stress(
        stress_ctx({**gcs_srv, "service.superseded": 2}, gcs_cli))),
        "generation-capacity-stress")

    # --- clearcache-mid-session: honest re-resolution = full re-download ---
    cc_action = [{"event": "action", "wallMs": 60_000, "action": "clearcache", "atSeconds": 60}]
    def cc_ctx(post_received, actions, post_utd=0):
        return _ctx(
            server_snaps=[_srv(120_000)],
            runs={1: [_cli(50_000, seg=0, over={"requested_total": 2200,
                                                "responses.columns": 2100,
                                                "received_columns": 2200}),
                      _cli(120_000, seg=1, over={"requested_total": 4400,
                                                 "responses.up_to_date": post_utd,
                                                 "received_columns": post_received})]},
            run_actions={1: actions}, quiescent_server={0})
    clean("clearcache clean re-download", list(check_clearcache_mid_session(
        cc_ctx(post_received=4250, actions=cc_action, post_utd=40))))
    hits("clearcache re-download missing (legacy up_to_date dedup)", list(check_clearcache_mid_session(
        cc_ctx(post_received=2210, actions=cc_action, post_utd=2100))), "clearcache-mid-session")
    hits("clearcache utd outweighs re-serves (done-bit override)", list(check_clearcache_mid_session(
        cc_ctx(post_received=2900, actions=cc_action, post_utd=900))), "clearcache-mid-session")
    hits("clearcache action never fired", list(check_clearcache_mid_session(
        cc_ctx(post_received=4250, actions=[]))), "clearcache-mid-session")

    # --- store-second-join: the re-serve wave must be store hits with byte parity ---
    ssj_action = [{"event": "action", "wallMs": 60_000, "action": "clearcache", "atSeconds": 60}]
    def ssj_ctx(hits_final=2000, disk_final=2100, deposits_pre=1900, errors=0,
                pre_hash=111, post_hash=111, actions=None):
        srv_pre = _srv(55_000, over={"store.deposits": deposits_pre,
                                     "store.hits": 0, "disk.submitted": 2000})
        srv_pre["probe_hashes"] = {"20:0": pre_hash}
        srv_fin = _srv(120_000, over={"store.deposits": deposits_pre + 50,
                                      "store.hits": hits_final,
                                      "disk.submitted": disk_final,
                                      "store.errors": errors})
        srv_fin["probe_hashes"] = {"20:0": post_hash}
        return _ctx(
            server_snaps=[srv_pre, srv_fin],
            runs={1: [_cli(50_000, seg=0, over={"requested_total": 2200,
                                                "received_columns": 2200}),
                      _cli(120_000, seg=1, over={"requested_total": 4400,
                                                 "received_columns": 4300})]},
            run_actions={1: actions if actions is not None else ssj_action},
            quiescent_server={1})
    clean("store-second-join clean warm re-serve", list(check_store_second_join(ssj_ctx())))
    hits("store-second-join hits missing (rung not intercepting)",
         list(check_store_second_join(ssj_ctx(hits_final=100))), "store-second-join")
    hits("store-second-join disk re-reads (warm leg re-read regions)",
         list(check_store_second_join(ssj_ctx(disk_final=4100))), "store-second-join")
    hits("store-second-join deposits missing (choke point dead)",
         list(check_store_second_join(ssj_ctx(deposits_pre=10))), "store-second-join")
    hits("store-second-join store errors",
         list(check_store_second_join(ssj_ctx(errors=2))), "store-second-join")
    hits("store-second-join byte drift (round trip not exact)",
         list(check_store_second_join(ssj_ctx(post_hash=999))), "store-second-join")
    hits("store-second-join action never fired",
         list(check_store_second_join(ssj_ctx(actions=[]))), "store-second-join")

    # --- store-offline-edit phases (store_offline_edit.sh) ---
    def sop_ctx(deposits=2000, errors=0, hashes=None):
        srv = _srv(150_000, over={"store.deposits": deposits, "store.errors": errors})
        srv["probe_hashes"] = {"20:0": 111, "-20:0": 222} if hashes is None else hashes
        return _ctx(server_snaps=[srv], runs={1: [_cli(150_000)]})
    clean("store-offline-populate clean", list(check_store_offline_populate(sop_ctx())))
    hits("store-offline-populate deposits missing",
         list(check_store_offline_populate(sop_ctx(deposits=10))), "store-offline-populate")
    hits("store-offline-populate store errors",
         list(check_store_offline_populate(sop_ctx(errors=1))), "store-offline-populate")
    hits("store-offline-populate probe unserved (baseline void)",
         list(check_store_offline_populate(sop_ctx(hashes={"20:0": 111, "-20:0": -1}))),
         "store-offline-populate")

    som_cmds = [dict(_cmd(8_000, "forceload add 328 8"), ok=True),
                dict(_cmd(10_000, "setblock 328 -60 8 minecraft:glowstone"), ok=True),
                dict(_cmd(15_000, "save-all"), ok=True)]
    def som_ctx(commands=None, requests=0, enabled=False):
        return _ctx(
            server_snaps=[_srv(45_000, over={"service.requests_received": requests})],
            commands=som_cmds if commands is None else commands,
            runs={1: [_cli(40_000, over={"server_enabled": enabled})]})
    clean("store-offline-mutate clean", list(check_store_offline_mutate(som_ctx())))
    hits("store-offline-mutate forceload missing (setblock would no-op unloaded)",
         list(check_store_offline_mutate(som_ctx(commands=som_cmds[1:]))),
         "store-offline-mutate")
    hits("store-offline-mutate setblock missing",
         list(check_store_offline_mutate(som_ctx(commands=[som_cmds[0], som_cmds[2]]))),
         "store-offline-mutate")
    hits("store-offline-mutate save-all missing",
         list(check_store_offline_mutate(som_ctx(commands=som_cmds[:2]))),
         "store-offline-mutate")
    hits("store-offline-mutate LSS not disabled",
         list(check_store_offline_mutate(som_ctx(enabled=True))), "store-offline-mutate")
    hits("store-offline-mutate LSS counters moved",
         list(check_store_offline_mutate(som_ctx(requests=5))), "store-offline-mutate")

    def sov_ctx(hits_=2000, errors=0, disk=100, hashes=None, quiescent=True):
        srv = _srv(150_000, over={"store.hits": hits_, "store.errors": errors,
                                  "disk.submitted": disk})
        srv["probe_hashes"] = {"20:0": 333, "-20:0": 222} if hashes is None else hashes
        return _ctx(server_snaps=[srv],
                    runs={1: [_cli(150_000, over={"received_columns": 2200})]},
                    quiescent_server={0} if quiescent else set())
    clean("store-offline-verify clean", list(check_store_offline_verify(sov_ctx())))
    hits("store-offline-verify store did not serve",
         list(check_store_offline_verify(sov_ctx(hits_=100))), "store-offline-verify")
    hits("store-offline-verify store errors",
         list(check_store_offline_verify(sov_ctx(errors=3))), "store-offline-verify")
    hits("store-offline-verify sweep over-dropped (disk re-reads)",
         list(check_store_offline_verify(sov_ctx(disk=1500))), "store-offline-verify")
    hits("store-offline-verify probe unserved",
         list(check_store_offline_verify(sov_ctx(hashes={"20:0": -1, "-20:0": 222}))),
         "store-offline-verify")
    hits("store-offline-verify never converged",
         list(check_store_offline_verify(sov_ctx(quiescent=False))), "store-offline-verify")

    # --- paper-store-unfired-event: resweep culls the un-evented edit ---
    def psu_ctx(edited_final=999, control_final=222, sweep_drops=2, hits_final=2000,
                errors=0, actions=None, marked_pre_window=0, marked_in_window=0,
                marked_ambient_late=0):
        # Window geometry: setblock at 70 s, save-all at 75 s → window end 85 s.
        # A 90 s snapshot brackets the window; 115 s / 185 s carry the rest of the
        # run (185 s may add AMBIENT marks the windowed premise must tolerate).
        srv_win = _srv(90_000, over={"store.hits": 0, "store.deposits": 1880,
                                     "dirty.marked_total": marked_pre_window
                                             + marked_in_window})
        srv_pre = _srv(115_000, over={"store.hits": 0, "store.deposits": 1900,
                                      "dirty.marked_total": marked_pre_window
                                              + marked_in_window})
        srv_pre["probe_hashes"] = {"20:0": 111, "-20:0": 222}
        srv_fin = _srv(185_000, over={"store.hits": hits_final,
                                      "store.sweep_drops": sweep_drops,
                                      "store.errors": errors,
                                      "store.deposits": 1950,
                                      "dirty.marked_total": marked_pre_window
                                              + marked_in_window + marked_ambient_late})
        srv_fin["probe_hashes"] = {"20:0": edited_final, "-20:0": control_final}
        srv_first = _srv(60_000, over={"store.deposits": 1800,
                                       "dirty.marked_total": marked_pre_window})
        return _ctx(
            server_snaps=[srv_first, srv_win, srv_pre, srv_fin],
            commands=[{"event": "command", "wallMs": 70_000,
                       "cmd": "setblock 328 -64 8 minecraft:stone", "ok": True},
                      {"event": "command", "wallMs": 75_000,
                       "cmd": "save-all", "ok": True}],
            runs={1: [_cli(110_000, seg=0, over={"received_columns": 2200}),
                      _cli(185_000, seg=1, over={"received_columns": 4300})]},
            run_actions={1: actions if actions is not None else [
                {"event": "action", "wallMs": 120_000, "action": "clearcache",
                 "atSeconds": 120}]},
            quiescent_server={3})
    clean("paper-store-unfired-event clean staleness-bound pass",
          list(check_paper_store_unfired_event(psu_ctx())))
    clean("paper-store-unfired-event AMBIENT marks outside the edit window are tolerated"
          " (the rerolled-base-world settle drip, 2026-08-08)",
          list(check_paper_store_unfired_event(psu_ctx(marked_ambient_late=4))))
    clean("paper-store-unfired-event pre-existing marks before the edit are tolerated",
          list(check_paper_store_unfired_event(psu_ctx(marked_pre_window=2))))
    hits("paper-store-unfired-event a mark INSIDE the edit window is the premise red",
         list(check_paper_store_unfired_event(psu_ctx(marked_in_window=1))),
         "paper-store-unfired-event")
    hits("paper-store-unfired-event stale bytes past the bound",
         list(check_paper_store_unfired_event(psu_ctx(edited_final=111))),
         "paper-store-unfired-event")
    hits("paper-store-unfired-event control drifted",
         list(check_paper_store_unfired_event(psu_ctx(control_final=888))),
         "paper-store-unfired-event")
    hits("paper-store-unfired-event resweep never culled",
         list(check_paper_store_unfired_event(psu_ctx(sweep_drops=0))),
         "paper-store-unfired-event")
    hits("paper-store-unfired-event store not serving the wave",
         list(check_paper_store_unfired_event(psu_ctx(hits_final=100))),
         "paper-store-unfired-event")
    hits("paper-store-unfired-event store errors",
         list(check_paper_store_unfired_event(psu_ctx(errors=1))),
         "paper-store-unfired-event")
    hits("paper-store-unfired-event action never fired",
         list(check_paper_store_unfired_event(psu_ctx(actions=[]))),
         "paper-store-unfired-event")
    # (The old whole-run premise case — final-snapshot-only marks — is now the
    # AMBIENT-tolerated clean case above; the in-window doctored case replaces it.)
    shape_ctx = psu_ctx()
    shape_ctx.commands.append({"event": "command", "wallMs": 76_000,
                               "cmd": "setblock 1 2 3 minecraft:dirt", "ok": True})
    hits("paper-store-unfired-event timeline shape drift (two setblocks) is a premise red",
         list(check_paper_store_unfired_event(shape_ctx)),
         "paper-store-unfired-event")

    # --- store-save-storm: the delete-only save hook under an autosave storm ---
    # (deposits default == misses: the delivery path is the ONLY depositor now;
    #  a positive deposits-misses margin is the R2-M2 write-through regression.)
    def sst_ctx(edited_pre=555, edited_final=777, deposits=2100, misses=2100,
                drops=0, errors=0, hits_final=2100, disk_final=2150,
                control_final=222, marked=441, actions=None):
        srv_first = _srv(20_000, over={"store.deposits": 2000, "store.misses": 2000,
                                       "store.hits": 0, "disk.submitted": 2000})
        srv_first["probe_hashes"] = {"20:0": 111, "-20:0": 222}
        srv_pre = _srv(125_000, over={"store.deposits": deposits - 20,
                                      "store.misses": misses - 20,
                                      "store.hits": 0, "disk.submitted": 2050})
        srv_pre["probe_hashes"] = {"20:0": edited_pre, "-20:0": 222}
        srv_fin = _srv(185_000, over={"store.deposits": deposits, "store.misses": misses,
                                      "store.hits": hits_final,
                                      "store.deposit_drops": drops,
                                      "store.errors": errors,
                                      "dirty.marked_total": marked,
                                      "disk.submitted": disk_final})
        srv_fin["probe_hashes"] = {"20:0": edited_final, "-20:0": control_final}
        return _ctx(
            server_snaps=[srv_first, srv_pre, srv_fin],
            runs={1: [_cli(120_000, seg=0, over={"received_columns": 2200}),
                      _cli(185_000, seg=1, over={"received_columns": 4300})]},
            run_actions={1: actions if actions is not None else [
                {"event": "action", "wallMs": 130_000, "action": "clearcache",
                 "atSeconds": 130}]},
            quiescent_server={2})
    clean("store-save-storm clean save-hook pass (first-observation churn reads)",
          list(check_store_save_storm(sst_ctx(disk_final=2075))))
    hits("store-save-storm deposits shed under storm",
         list(check_store_save_storm(sst_ctx(disk_final=2075, drops=7))),
         "store-save-storm")
    hits("store-save-storm write-through deposits crept back (deposits > misses)",
         list(check_store_save_storm(sst_ctx(disk_final=2075, deposits=2600))),
         "store-save-storm")
    hits("store-save-storm edit never re-served before the action",
         list(check_store_save_storm(sst_ctx(disk_final=2075, edited_pre=111))),
         "store-save-storm")
    hits("store-save-storm final serve returned pre-edit bytes",
         list(check_store_save_storm(sst_ctx(disk_final=2075, edited_final=111))),
         "store-save-storm")
    hits("store-save-storm control drifted",
         list(check_store_save_storm(sst_ctx(disk_final=2075, control_final=888))),
         "store-save-storm")
    hits("store-save-storm re-serve leg re-read regions beyond churn",
         list(check_store_save_storm(sst_ctx(disk_final=2200))), "store-save-storm")
    hits("store-save-storm hook never processed the loaded set",
         list(check_store_save_storm(sst_ctx(disk_final=2075, marked=12))),
         "store-save-storm")
    hits("store-save-storm store did not serve the wave",
         list(check_store_save_storm(sst_ctx(disk_final=2075, hits_final=100))),
         "store-save-storm")
    hits("store-save-storm action never fired",
         list(check_store_save_storm(sst_ctx(disk_final=2075, actions=[]))),
         "store-save-storm")
    hits("store-save-storm store errors",
         list(check_store_save_storm(sst_ctx(disk_final=2075, errors=1))),
         "store-save-storm")

    # --- dirty-range-filter: drain visible, client silent, follow-up live ---
    drf_cmds = [_cmd(122_000, "setblock -250 310 5 minecraft:stone"),
                _cmd(180_000, "forceload add 560 5"),
                _cmd(182_000, "setblock 560 310 5 minecraft:stone")]
    def drf_ctx(after_broadcast, quiet_received, quiet_requested, final_received):
        return _ctx(
            server_snaps=[_srv(120_000, over={"dirty.broadcast_positions": 50}),
                          _srv(135_000, over={"dirty.broadcast_positions": after_broadcast}),
                          _srv(230_000, over={"dirty.broadcast_positions": after_broadcast + 1})],
            commands=drf_cmds,
            config={"dirtyBroadcastIntervalSeconds": 5},
            runs={1: [_cli(120_000, over={"received_columns": 2200, "requested_total": 2300}),
                      _cli(175_000, over={"received_columns": quiet_received,
                                          "requested_total": quiet_requested}),
                      _cli(230_000, over={"received_columns": final_received,
                                          "requested_total": quiet_requested + 2})]})
    clean("dirty-range-filter suppressed", list(check_dirty_range_filter(
        drf_ctx(after_broadcast=53, quiet_received=2200, quiet_requested=2300,
                final_received=2201))))
    hits("dirty-range-filter pushed out-of-range", list(check_dirty_range_filter(
        drf_ctx(after_broadcast=53, quiet_received=2205, quiet_requested=2305,
                final_received=2206))), "dirty-range-filter")
    hits("dirty-range-filter drain wedged", list(check_dirty_range_filter(
        drf_ctx(after_broadcast=50, quiet_received=2200, quiet_requested=2300,
                final_received=2201))), "dirty-range-filter")

    # --- dirty-during-backfill: premise = traffic in flight at the edit ---
    ddb_cmd = [_cmd(12_000, "setblock 320 310 0 minecraft:stone")]
    clean("dirty-during-backfill mid-flight", list(check_dirty_during_backfill(_ctx(
        server_snaps=[_srv(10_000, over={"service.requests_received": 800}),
                      _srv(15_000, over={"service.requests_received": 1400}),
                      _srv(120_000, over={"service.requests_received": 2400})],
        commands=ddb_cmd, runs={1: [_cli(10_000), _cli(120_000)]},
        quiescent_server={2}))))
    hits("dirty-during-backfill premise lost", list(check_dirty_during_backfill(_ctx(
        server_snaps=[_srv(10_000, over={"service.requests_received": 2400}),
                      _srv(15_000, over={"service.requests_received": 2400}),
                      _srv(120_000, over={"service.requests_received": 2400})],
        commands=ddb_cmd, runs={1: [_cli(10_000), _cli(120_000)]},
        quiescent_server={2}))), "dirty-during-backfill")

    # --- paper-dirty-falling-block: landing broadcast + drain + re-send all discriminating ---
    fb_cmds = [_cmd(95_000, 'summon minecraft:falling_block 320.5 -50.0 0.5 '
                            '{BlockState:{Name:"minecraft:stone"},Time:1}')]
    def fb_ctx(after_broadcast, final_dirty, final_received):
        return _ctx(
            server_snaps=[_srv(90_000, over={"dirty.broadcast_positions": 10}),
                          _srv(100_000, over={"dirty.broadcast_positions": after_broadcast}),
                          _srv(140_000, over={"dirty.broadcast_positions": after_broadcast})],
            commands=fb_cmds,
            config={"dirtyBroadcastIntervalSeconds": 5},
            runs={1: [_cli(90_000, over={"received_columns": 2250}),
                      _cli(140_000, over={"received_columns": final_received,
                                          "columns.dirty": final_dirty})]})
    clean("falling-block landed and re-served", list(check_paper_dirty_falling_block(
        fb_ctx(after_broadcast=11, final_dirty=0, final_received=2251))))
    hits("falling-block event lost (no broadcast)", list(check_paper_dirty_falling_block(
        fb_ctx(after_broadcast=10, final_dirty=0, final_received=2251))),
         "paper-dirty-falling-block")
    hits("falling-block dirty never drained", list(check_paper_dirty_falling_block(
        fb_ctx(after_broadcast=11, final_dirty=1, final_received=2251))),
         "paper-dirty-falling-block")
    hits("falling-block no re-send", list(check_paper_dirty_falling_block(
        fb_ctx(after_broadcast=11, final_dirty=0, final_received=2250))),
         "paper-dirty-falling-block")
    hits("falling-block landing window unbracketed", list(check_paper_dirty_falling_block(_ctx(
        server_snaps=[_srv(140_000, over={"dirty.broadcast_positions": 11})],
        commands=fb_cmds, config={"dirtyBroadcastIntervalSeconds": 5},
        runs={1: [_cli(90_000, over={"received_columns": 2250}),
                  _cli(140_000, over={"received_columns": 2251})]}))),
         "paper-dirty-falling-block")

    # --- dimension-rejoin-warm: rejoin lands in the End, both resyncs warm ---
    def drw_ctx(run2_first_dim, end_utd=400, ow_utd=None):
        # Counters are run-cumulative: the overworld segment's final up_to_date INCLUDES
        # the End segment's count. Default overworld delta: +400.
        if ow_utd is None:
            ow_utd = end_utd + 400
        r1 = [_cli(1000, seg=0), _cli(50_000, seg=1, over={"dimension": "minecraft:the_end"})]
        r2 = [_cli(200_000, seg=0, over={"dimension": run2_first_dim,
                                         "responses.up_to_date": end_utd}),
              _cli(280_000, seg=1, over={"dimension": "minecraft:overworld",
                                         "responses.up_to_date": ow_utd})]
        return _ctx(runs={1: r1, 2: r2}, quiescent_client={(2, 0), (2, 1)})
    clean("dimension-rejoin-warm good", list(check_dimension_rejoin_warm(
        drw_ctx("minecraft:the_end"))))
    hits("dimension-rejoin-warm rejoined overworld", list(check_dimension_rejoin_warm(
        drw_ctx("minecraft:overworld"))), "dimension-rejoin-warm")
    hits("dimension-rejoin-warm cold End resync", list(check_dimension_rejoin_warm(
        drw_ctx("minecraft:the_end", end_utd=100))), "dimension-rejoin-warm")
    # The cumulative counter hides a cold overworld leg behind the End total: the raw
    # value (2000) clears any absolute floor while the overworld segment contributed 0.
    hits("dimension-rejoin-warm cold overworld resync behind a warm End total",
         list(check_dimension_rejoin_warm(
             drw_ctx("minecraft:the_end", end_utd=2000, ow_utd=2000))),
         "dimension-rejoin-warm")

    # --- Config override allowlist ---
    cases[0] += 1
    assert validate_config_overrides({"lodDistanceChunks": 24,
                                      "enableChunkGeneration": False}) == [], \
        "config allowlist: legal overrides must validate"
    cases[0] += 1
    assert validate_config_overrides({"diskReaderThread": 1}), \
        "config allowlist: typo key must be rejected"
    cases[0] += 1
    assert validate_config_overrides({"enableChunkGeneration": "false"}), \
        "config allowlist: string-for-bool must be rejected"
    cases[0] += 1
    assert validate_config_overrides({"lodDistanceChunks": True}), \
        "config allowlist: bool-for-int must be rejected"
    cases[0] += 1
    assert validate_config_overrides({"maxConcurrentDiskReads": 5}) == [], \
        "config allowlist: the gate key must validate (the R4 lesson — every no-op pin " \
        "depends on this registration)"
    cases[0] += 1
    assert validate_config_overrides({"maxConcurrentDiskReads": "5"}), \
        "config allowlist: string-for-int gate key must be rejected"
    cases[0] += 1
    assert validate_config_overrides({"xrayObfuscation": "on",
                                      "xrayHiddenBlocks": ["diamond_ore"],
                                      "xrayMaxBlockHeight": 64}) == [], \
        "config allowlist: legal xray overrides must validate"
    cases[0] += 1
    assert validate_config_overrides({"xrayObfuscation": True}), \
        "config allowlist: non-string for the xray tri-state must be rejected"
    cases[0] += 1
    assert validate_config_overrides({"xrayHiddenBlocks": [1, 2]}), \
        "config allowlist: non-string list entries must be rejected"

    # ---- session-version assertion (C6 negotiated-protocol observability) ----
    sv_rows = [{"event": "snapshot", "session_version": 0},
               {"event": "snapshot", "session_version": 19},
               {"event": "disconnect", "session_version": 19}]
    clean("session-version: pre-config 0 rows + established 19 under expect 19",
          session_version_violations("client-run1.jsonl", sv_rows, 19))
    clean("session-version: expectation None is a no-op on any rows",
          session_version_violations("client-run1.jsonl",
                                     [{"event": "snapshot"}], None))
    hits("session-version: a 16 row under expect 19 (the silent-degrade shape)",
         session_version_violations("client-run1.jsonl",
                                    [{"event": "snapshot", "session_version": 16}], 19),
         "session-version")
    hits("session-version: all rows 0 under an expectation is vacuous, must red",
         session_version_violations("client-run1.jsonl",
                                    [{"event": "snapshot", "session_version": 0}], 19),
         "session-version")
    hits("session-version: rows lacking the key under an expectation (pre-C6 jar)",
         session_version_violations("client-run1.jsonl",
                                    [{"event": "snapshot"}], 19),
         "session-version")

    print(f"selftest OK: {cases[0]} cases — every law (A1-A7, B2), the quiescence "
          f"predicate (server pair AND client mirror), disc completeness, window floors, "
          f"the config allowlist, action "
          f"segmentation, and every session/movement/dirty-pipeline named check each "
          f"pass consistent data and catch a doctored inconsistency")
    return 0


# ------------------------------------------------------------------------------- main

def main(argv=None):
    parser = argparse.ArgumentParser(description="Soak harness invariant checker")
    parser.add_argument("--validate", metavar="SCENARIO",
                        help="pre-flight validation of a scenario (no results needed)")
    parser.add_argument("--selftest", action="store_true", help="run in-memory law selftest")
    parser.add_argument("--expect-session-version", type=int, default=None,
                        metavar="N", help="assert every established client session is "
                        "protocol N (soak.sh passes SOAK_DIALECT or the native version)")
    parser.add_argument("--platform", default="fabric",
                        help="SOAK_PLATFORM of the recording (fabric/paper/folia) — "
                        "platform-conditional checks key on this")
    parser.add_argument("args", nargs="*", metavar="RESULTS_DIR SCENARIO",
                        help="results directory and scenario name")
    opts = parser.parse_args(argv)

    if opts.selftest:
        return selftest()
    if opts.validate:
        errors = validate_scenario(opts.validate)
        if errors:
            print(f"VALIDATE FAIL: {opts.validate}")
            for e in errors:
                print(f"  {e}")
            return 1
        print(f"VALIDATE PASS: {opts.validate}")
        return 0
    if len(opts.args) != 2:
        parser.error("expected: check_soak.py <results-dir> <scenario-name> "
                     "(or --validate <scenario-name> / --selftest)")
    results_dir, scenario = Path(opts.args[0]), opts.args[1]
    if not results_dir.is_dir():
        print(f"FAIL: results dir not found: {results_dir}")
        return 1
    return run_checker(results_dir, scenario, opts.expect_session_version, opts.platform)


if __name__ == "__main__":
    sys.exit(main())
