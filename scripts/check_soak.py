#!/usr/bin/env python3
"""Soak-harness invariant checker (stdlib only). Authoritative contract:
docs/planning/soak-test-design.md

Laws (evaluated as deltas between consecutive VERIFIED-QUIESCENT snapshots within
same-client-run, same-dimension windows; dimension/join boundaries get anomaly checks only):
  A1 requests:    d(client.requested_total) == d(responses.columns+up_to_date+not_generated+rate_limited) + d(server.service.duplicate_skips) + d(server.service.queue_full)
  A2 delivery:    d(server.service.columns_sent) == d(client.received_columns); d(server.service.bytes_sent) == d(client.received_bytes); d(client.dropped) == 0
  A3 sources:     d(service.columns_sent) <= d(service.in_memory + disk.successful + generation.completed)  [sanity bound, not exact]
  A4 generation:  d(generation.submitted) == d(generation.completed + generation.timeouts + generation.removed_in_flight)
  A5 disk triage: d(disk.not_found) == d(generation.submitted) + d(client.responses.not_generated)  [only when d(generation.timeouts)==0];
                  d(disk.successful) == d(disk.completed - not_found - all_air - errors - saturated)
  A6 monotonic:   server cumulative whitelist (disk totals, generation totals, service.*, bandwidth.total_bytes) over process lifetime;
                  client cumulative counters within one run AND one dimension segment only; server per-player rows are never checked
  A7 anomalies:   disk.errors / generation.timeouts / client.dropped > 0 always fail; disk.saturated / responses.rate_limited fail unless the scenario opts in
  B1 rate-limit:  d(client.responses.rate_limited) == d(service.sync_rate_limited + service.gen_rate_limited + disk.saturated)
                  [single-client; disk saturation surfaces to the client as rate-limited BY DESIGN]
  B2 pacing:      d(bandwidth.total_bytes) <= bytesPerSecondLimitGlobal * dt * 1.3 over every consecutive
                  server snapshot pair — armed only when the scenario config sets the global cap

Vacuous-pass guards: every scenario declares per-(run, dimension-segment) floors on the number
of client-laws windows actually evaluated (MIN_CLIENT_WINDOWS) — a run where A1/A2/A5/B1 never
fired fails loudly instead of passing on zero evidence. --validate additionally rejects scenario
config overrides whose keys are not real lss-server-config.json fields (GSON silently ignores
typos, which would de-fang a scenario's whole premise).

Quiescence predicate: across >=2 consecutive server snapshots in the same join segment,
service.requests_received, service.columns_sent, disk.submitted, generation.submitted and
generation.completed are unchanged AND (at both endpoints) every players[] row has
held_sync == held_gen == send_queue == 0, disk.pending == 0, generation.active == 0,
dirty.pending == 0; joined to the nearest-in-wallMs client snapshot of the matching run
(<= 3 s skew) which must have tracker_in_flight == 0 and queued == 0.

Modes:
  check_soak.py --validate <scenario-name>      pre-flight scenario/config/registry validation
  check_soak.py <results-dir> <scenario-name>   evaluate laws + named checks, write verdict.json
  check_soak.py --selftest                      in-memory pass/catch self-test of every law
                                                (A1-A7, B1, B2), quiescence, disc completeness,
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
EXPECTED_RUNS = {"warm-rejoin": 2, "dirty-while-offline": 2, "dimension-rejoin-warm": 2}

# A7 opt-ins. The spec treats saturated/rate_limited as one opt-in pair (disk saturation
# surfaces to the client as rate_limited BY DESIGN), so opting in suppresses both flags.
# errors / timeouts / dropped can never be opted out.
ANOMALY_OPT_INS = {
    "fresh-backfill": frozenset({"rate_limited", "saturated"}),
    "warm-rejoin": frozenset({"rate_limited", "saturated"}),
    "dimension-trip": frozenset({"rate_limited", "saturated"}),
    "dirty-broadcast": frozenset(),
    "rate-limit-storm": frozenset({"rate_limited", "saturated"}),
    "disk-saturation": frozenset({"rate_limited", "saturated"}),
    "generation-disabled": frozenset({"rate_limited", "saturated"}),
    "generation-capacity-stress": frozenset({"rate_limited", "saturated"}),
    "bandwidth-throttle": frozenset({"rate_limited", "saturated"}),
    "cold-restart-resync": frozenset({"rate_limited", "saturated"}),
    "enabled-false": frozenset(),
    "teleport-prune": frozenset({"rate_limited", "saturated"}),
    "dirty-range-filter": frozenset({"rate_limited", "saturated"}),
    "dirty-during-backfill": frozenset({"rate_limited", "saturated"}),
    "dirty-while-offline": frozenset({"rate_limited", "saturated"}),
    "clearcache-mid-session": frozenset({"rate_limited", "saturated"}),
    "dimension-rejoin-warm": frozenset({"rate_limited", "saturated"}),
    # Paper/Folia (SOAK_PLATFORM=paper|folia): cold-cache disc resync from the base world, like
    # warm-rejoin run 1, so the same load-shaped opt-ins apply.
    "paper-dirty-falling-block": frozenset({"rate_limited", "saturated"}),
}

# Vacuous-pass floors: minimum number of client-laws windows (the quiescent pairs where
# A1/A2/A5/B1 actually evaluated) per (client run, dimension segment). Calibrated against
# recorded green runs at roughly one third of observed counts: fresh-backfill 31, warm-rejoin
# 27/17, dimension-trip 6/22/16, dirty-broadcast 16. New scenarios get a conservative 3
# (their converged tails run 40+ s at 5 s snapshot cadence).
# Scenarios allowed to finish with zero nonzero-delta client-law windows (no LSS traffic).
TRAFFIC_FLOOR_EXEMPT = frozenset({"enabled-false"})

MIN_CLIENT_WINDOWS = {
    "fresh-backfill": {(1, 0): 10},
    "warm-rejoin": {(1, 0): 8, (2, 0): 5},
    "dimension-trip": {(1, 0): 2, (1, 1): 6, (1, 2): 4},
    "dirty-broadcast": {(1, 0): 5},
    "rate-limit-storm": {(1, 0): 3},
    "disk-saturation": {(1, 0): 3},
    "generation-disabled": {(1, 0): 3},
    "generation-capacity-stress": {(1, 0): 3},
    "bandwidth-throttle": {(1, 0): 3},
    "cold-restart-resync": {(1, 0): 5},
    "enabled-false": {(1, 0): 3},
    "teleport-prune": {(1, 0): 8},
    "dirty-range-filter": {(1, 0): 8},
    "dirty-during-backfill": {(1, 0): 5},
    "dirty-while-offline": {(1, 0): 3, (2, 0): 3},
    # clearcache splits run 1 into pre/post-action segments (the flushCache counter reset
    # is a segment boundary, like a dimension change)
    "clearcache-mid-session": {(1, 0): 4, (1, 1): 4},
    "dimension-rejoin-warm": {(1, 0): 2, (1, 1): 5, (2, 0): 3, (2, 1): 3},
    "paper-dirty-falling-block": {(1, 0): 3},
}

# The exclusion circle the client scanner never requests inside: min(client render distance,
# server view-distance), both pinned to 8 by soak.sh (server.properties view-distance=8,
# options.txt renderDistance:8). The scanned annulus is Chebyshev rings 9..lodDistanceChunks.
EXCLUSION_RADIUS = 8

# Every legal key of lss-server-config.json (ServerConfigBase fields — the Fabric soak server
# reads exactly these). GSON silently ignores unknown keys, so a typo in a scenario's
# -config.json would silently fall back to defaults and de-fang the scenario; --validate
# rejects unknown keys and wrong JSON types instead.
SERVER_CONFIG_BOOL_KEYS = frozenset({"enabled", "enableChunkGeneration"})
SERVER_CONFIG_INT_KEYS = frozenset({
    "lodDistanceChunks", "bytesPerSecondLimitPerPlayer", "diskReaderThreads",
    "sendQueueLimitPerPlayer", "bytesPerSecondLimitGlobal",
    "generationConcurrencyLimitGlobal", "generationTimeoutSeconds",
    "dirtyBroadcastIntervalSeconds", "syncOnLoadConcurrencyLimitPerPlayer",
    "generationConcurrencyLimitPerPlayer", "perDimensionTimestampCacheSizeMB",
})
SERVER_CONFIG_KEYS = SERVER_CONFIG_BOOL_KEYS | SERVER_CONFIG_INT_KEYS

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
SERVER_DRAINS = ("disk.pending", "generation.active", "dirty.pending")
PLAYER_DRAINS = ("held_sync", "held_gen", "send_queue")
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
    "service.sync_rate_limited", "service.gen_rate_limited",
    "disk.submitted", "disk.completed", "disk.not_found", "disk.all_air",
    "disk.errors", "disk.saturated", "disk.successful",
    "generation.submitted", "generation.completed", "generation.timeouts",
    "generation.removed_in_flight",
    "dirty.broadcast_positions", "dirty.suppressed_total",
    "bandwidth.total_bytes",
    # The flagship a9bee8d honest-re-resolution counter — emitted every snapshot, cumulative.
    # Was assertable by nothing before round 2; now A6-monotonic and surfaced in the soak_report
    # mechanism digest. No scenario floor-checks it yet (no ingest-failure soak scenario exists),
    # so A6 on it is correct-but-quiescent until such a scenario is authored.
    "service.re_resolved",
)
CLIENT_MONOTONIC = (
    "received_columns", "received_bytes", "dropped",
    "responses.columns", "responses.up_to_date", "responses.not_generated",
    "responses.rate_limited",
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
                 "bandwidth", "players", "dedup", "jvm", "tscache",
                 "mailbox_depth_hw", "mspt_avg_window", "probe_hashes"},
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
    # effective_lod/request_queue/rtt/ingest_failures are the round-2 client data-capture
    # additions; like probes they are presence-optional (older recordings predate them).
    "snapshot": {"event", "wallMs", "dimension", "received_columns", "received_bytes",
                 "dropped", "responses", "requested_total", "send_cycles", "columns",
                 "scan", "tracker_in_flight", "queued", "server_enabled", "probes",
                 "effective_lod", "request_queue", "rtt", "ingest_failures"},
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
    d_req = delta(pc, cc, "requested_total")
    d_resp = sum(delta(pc, cc, f"responses.{k}")
                 for k in ("columns", "up_to_date", "not_generated", "rate_limited"))
    d_dup = delta(ps, cs, "service.duplicate_skips")
    d_qf = delta(ps, cs, "service.queue_full")
    if d_req != d_resp + d_dup + d_qf:
        return [Violation("A1", window,
                          "requested_total delta != responses + duplicate_skips + queue_full",
                          {"d_requested_total": d_req, "d_responses": d_resp,
                           "d_duplicate_skips": d_dup, "d_queue_full": d_qf,
                           "expected": d_resp + d_dup + d_qf, "actual": d_req})]
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
    d_src = (delta(ps, cs, "service.in_memory")
             + delta(ps, cs, "disk.successful")
             + delta(ps, cs, "generation.completed"))
    if d_sent > d_src:
        return [Violation("A3", window,
                          "columns_sent exceeds in_memory + disk.successful + generation.completed",
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
        d_gen_sub = delta(ps, cs, "generation.submitted")
        d_ng = delta(pc, cc, "responses.not_generated")
        if d_nf != d_gen_sub + d_ng:
            out.append(Violation("A5", window,
                                 "disk.not_found != generation.submitted + not_generated responses",
                                 {"d_not_found": d_nf, "d_gen_submitted": d_gen_sub,
                                  "d_not_generated": d_ng, "expected": d_gen_sub + d_ng,
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
    return out


def law_A7_client(prev, cur, window, opt_ins):
    """prev may be None: the run-head window (counters are exactly 0 at process start).
    Later segment heads anchor at the last pre-boundary row instead — counters are
    run-cumulative, so pv=0 there would re-bill earlier segments' anomalies."""
    out = []
    _anomaly(prev, cur, "dropped", window, "client dropped", opt_ins, None, out)
    _anomaly(prev, cur, "responses.rate_limited", window, "responses.rate_limited",
             opt_ins, "rate_limited", out)
    return out


def law_B1(ps, cs, pc, cc, window):
    """Rate-limit conservation (single client): every client rate-limited response has
    exactly one server-side cause — a sync slot bounce, a gen slot bounce, or a saturated
    disk read (which surfaces as rate-limited BY DESIGN). Vacuous (0 == 0) in scenarios
    without contention; live in the storm scenarios."""
    d_rl = delta(pc, cc, "responses.rate_limited")
    d_src = (delta(ps, cs, "service.sync_rate_limited")
             + delta(ps, cs, "service.gen_rate_limited")
             + delta(ps, cs, "disk.saturated"))
    if d_rl != d_src:
        return [Violation("B1", window,
                          "client rate_limited != sync_rate_limited + gen_rate_limited + saturated",
                          {"d_client_rate_limited": d_rl,
                           "d_sync_rate_limited": delta(ps, cs, "service.sync_rate_limited"),
                           "d_gen_rate_limited": delta(ps, cs, "service.gen_rate_limited"),
                           "d_saturated": delta(ps, cs, "disk.saturated"),
                           "expected": d_src, "actual": d_rl})]
    return []


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
    - in-window quiescent pairs (same run + same dimension segment): A1-A5 + B1 + A7
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

    # B2 over raw series, armed by the scenario's bandwidth cap override.
    cap = ctx.config.get("bytesPerSecondLimitGlobal")
    if isinstance(cap, int) and not isinstance(cap, bool) and cap > 0:
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
        v += law_B1(ps, cs, pc, cc, win)
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
              "responses": {"columns": 0, "up_to_date": 0, "not_generated": 0,
                            "rate_limited": 0}}
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
    return violations, windows, sum(client_windows.values())


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


@named_check("rate-limit-storm", ["server.service.gen_rate_limited", "server.generation.completed",
                                  "client.responses.rate_limited"])
def check_rate_limit_storm(ctx):
    """Tiny sync slot cap vs the client's generation-request flood: the whole bounce loop
    (router rateLimit -> wire rate-limited -> onRateLimited -> retry mark -> scanner backoff)
    must actually fire, and the system must still reconverge to a complete disc."""
    last = ctx.server_snaps[-1]
    if last["service"]["gen_rate_limited"] < 1:
        yield Violation("rate-limit-storm", "final snapshot",
                        "router slot bounce (gen_rate_limited) never fired — the storm "
                        "premise did not hold",
                        {"expected": ">= 1", "actual": last["service"]["gen_rate_limited"]})
    fc = ctx.final_client(1)
    if fc is None:
        yield Violation("rate-limit-storm", "run1", "no client snapshots in run 1", {})
        return
    rl = fc["responses"]["rate_limited"]
    if rl < 50:
        yield Violation("rate-limit-storm", "run1 final snapshot",
                        "client saw too few rate-limited responses for a storm",
                        {"expected": ">= 50", "actual": rl})
    # Floor calibrated for lodDistance=12 / exclusion~8: vanilla's own loaded square covers
    # rings 9-10, so only the outer rings (~165 positions) route through LSS generation.
    # Disc-completeness separately proves nothing was orphaned; this floor only asserts
    # generation kept making real progress through the bounce storm.
    if last["generation"]["completed"] <= 120:
        yield Violation("rate-limit-storm", "final snapshot",
                        "fresh-world backfill did not generate through the storm",
                        {"expected": "> 120", "actual": last["generation"]["completed"]})
    if (len(ctx.server_snaps) - 1) not in ctx.quiescent_server:
        yield Violation("rate-limit-storm", "final snapshot",
                        "last server snapshot is not verified-quiescent (storm never reconverged)",
                        {"wallMs": last["wallMs"]})


@named_check("disk-saturation", ["server.disk.saturated", "client.responses.rate_limited"])
def check_disk_saturation(ctx):
    """One reader thread (queue capacity 33) vs a 200-slot sync flood: the saturated leg of
    A5/B1 must go nonzero, surface to the client as rate-limited, and still converge."""
    last = ctx.server_snaps[-1]
    if last["disk"]["saturated"] < 1:
        yield Violation("disk-saturation", "final snapshot",
                        "disk.saturated never fired — the flood did not overrun the reader queue",
                        {"expected": ">= 1", "actual": last["disk"]["saturated"]})
    fc = ctx.final_client(1)
    if fc is None:
        yield Violation("disk-saturation", "run1", "no client snapshots in run 1", {})
        return
    if fc["responses"]["rate_limited"] < 1:
        yield Violation("disk-saturation", "run1 final snapshot",
                        "saturation never surfaced to the client as rate-limited",
                        {"expected": ">= 1", "actual": fc["responses"]["rate_limited"]})
    if (len(ctx.server_snaps) - 1) not in ctx.quiescent_server:
        yield Violation("disk-saturation", "final snapshot",
                        "last server snapshot is not verified-quiescent (saturation retry "
                        "loop never converged)", {"wallMs": last["wallMs"]})


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
                                            "client.responses.not_generated"])
def check_generation_capacity_stress(ctx):
    """Global generation cap pinned to 1 while the per-player pending cap admits 8: the
    drainGenerationTicketRequests -> feedGenerationFailure -> slot-release bounce path runs
    hot through the whole backfill, and it must still converge to a complete disc."""
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
    if fc["responses"]["not_generated"] < 50:
        yield Violation("generation-capacity-stress", "run1 final snapshot",
                        "too few not-generated responses — capacity bouncing never happened",
                        {"expected": ">= 50", "actual": fc["responses"]["not_generated"]})
    if (len(ctx.server_snaps) - 1) not in ctx.quiescent_server:
        yield Violation("generation-capacity-stress", "final snapshot",
                        "last server snapshot is not verified-quiescent (capacity bouncing "
                        "never converged)", {"wallMs": last["wallMs"]})


@named_check("bandwidth-throttle", ["server.service.queue_full", "client.received_bytes"])
def check_bandwidth_throttle(ctx):
    """256 KB/s global cap + 64-deep send queue: queue_full must fire (first-ever nonzero),
    the full disc must still stream through (client 10 s in-flight timeout sweep + late-column
    self-heal), and B2 (armed by the config cap) bounds the pacing."""
    if "bytesPerSecondLimitGlobal" not in ctx.config:
        yield Violation("bandwidth-throttle", "config",
                        "scenario config must set bytesPerSecondLimitGlobal or B2 stays unarmed", {})
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


@named_check("cold-restart-resync", ["server.service.up_to_date", "client.responses.up_to_date",
                                     "client.responses.columns", "client.requested_total"])
def check_cold_restart_resync(ctx):
    """Brand-new server JVM + warm client cache (both restored from the fresh-backfill
    snapshot pair): the resync must be answered from the PERSISTED world/data/
    lss-timestamps.bin loaded at startup — warm-rejoin's run 2 is served by same-process
    RAM and never makes the file load-bearing. If the load regresses, every resync
    becomes a disk read + column re-send: up_to_date collapses toward 0 and columns
    balloons to the full disc. A bounded re-send wave is expected (first-save content-
    filter seeding in the new process), so the warm signal is up_to_date strictly
    dominating columns, not columns == 0."""
    last = ctx.server_snaps[-1]
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
    violations. flush makes seeding synchronous; the startswith match accepts both."""
    save_alls = [c for c in ctx.commands if c["cmd"].strip().startswith("save-all")]
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
    "clearcache-mid-session": [check_clearcache_mid_session,
                               make_handshake_check("clearcache-mid-session"),
                               make_disc_completeness("clearcache-mid-session")],
    "dimension-rejoin-warm": [check_dimension_rejoin_warm,
                              make_handshake_check("dimension-rejoin-warm"),
                              make_disc_completeness("dimension-rejoin-warm", run=1),
                              make_disc_completeness("dimension-rejoin-warm", run=2)],
    # Paper/Folia scenario (scripts/soak.sh SOAK_PLATFORM=paper|folia); the conservation laws
    # themselves are platform-blind — the Paper exporter twin emits the same schema.
    "paper-dirty-falling-block": [check_paper_dirty_falling_block,
                                  make_handshake_check("paper-dirty-falling-block"),
                                  make_disc_completeness("paper-dirty-falling-block")],
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


def validate_timeline(scen):
    errors = []
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


def run_checker(results_dir, scenario):
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
                  run_actions=run_actions)
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
                        "duplicate_skips": 0, "queue_full": 0, "up_to_date": 0,
                        "in_memory": 0, "disk_resolved": 0, "gen_drained": 0,
                        "sync_rate_limited": 0, "gen_rate_limited": 0, "re_resolved": 0},
            "disk": {"submitted": 0, "completed": 0, "not_found": 0, "all_air": 0,
                     "errors": 0, "saturated": 0, "successful": 0, "pending": 0},
            "generation": {"submitted": 0, "completed": 0, "timeouts": 0,
                           "removed_in_flight": 0, "active": 0},
            "dirty": {"pending": 0, "broadcast_positions": 0, "suppressed_total": 0},
            "bandwidth": {"total_bytes": 0}, "players": []}
    for k, v in (over or {}).items():
        _set_path(snap, k, v)
    return snap


def _cli(wall=1000, seg=0, over=None):
    """Schema-complete client snapshot fixture (all GLOBAL_CLIENT_FIELDS present, zeros)."""
    snap = {"event": "snapshot", "wallMs": wall, "dimension": "minecraft:overworld",
            "_seg": seg, "received_columns": 0, "received_bytes": 0, "dropped": 0,
            "responses": {"columns": 0, "up_to_date": 0, "not_generated": 0,
                          "rate_limited": 0},
            "requested_total": 0, "send_cycles": 0,
            "columns": {"known": 0, "empty": 0, "dirty": 0},
            "scan": {"confirmed": 0, "ring": 0, "missing_vanilla": 0},
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

    # --- A1: requests == responses + duplicate_skips + queue_full ---
    ps, cs = _srv(1000), _srv(6000, over={"service.duplicate_skips": 1, "service.queue_full": 1})
    pc = _cli(1000)
    cc = _cli(6000, over={"requested_total": 10, "responses.columns": 4,
                          "responses.up_to_date": 2, "responses.not_generated": 1,
                          "responses.rate_limited": 1})
    clean("A1 balanced", law_A1(ps, cs, pc, cc, "selftest"))
    cc_bad = _cli(6000, over={"requested_total": 11, "responses.columns": 4,
                              "responses.up_to_date": 2, "responses.not_generated": 1,
                              "responses.rate_limited": 1})
    hits("A1 lost request", law_A1(ps, cs, pc, cc_bad, "selftest"), "A1")

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
    cs_bad = _srv(6000, over={"disk.not_found": 5, "generation.submitted": 2,
                              "disk.completed": 10, "disk.all_air": 1, "disk.saturated": 1,
                              "disk.successful": 3})
    hits("A5 escalation leg", law_A5(ps, cs_bad, pc, cc, "selftest"), "A5")
    cs_bad = _srv(6000, over={"disk.not_found": 5, "generation.submitted": 3,
                              "disk.completed": 10, "disk.all_air": 1, "disk.saturated": 1,
                              "disk.successful": 4})
    hits("A5 partition leg", law_A5(ps, cs_bad, pc, cc, "selftest"), "A5")

    # --- A6 server: monotonic whitelist, including the storm counters ---
    clean("A6 server monotonic", law_A6_server([
        _srv(1000, over={"service.sync_rate_limited": 5, "dirty.broadcast_positions": 7}),
        _srv(6000, over={"service.sync_rate_limited": 6, "dirty.broadcast_positions": 7})]))
    hits("A6 sync_rate_limited decrement", law_A6_server([
        _srv(1000, over={"service.sync_rate_limited": 5}),
        _srv(6000, over={"service.sync_rate_limited": 4})]), "A6")
    hits("A6 gen_rate_limited decrement", law_A6_server([
        _srv(1000, over={"service.gen_rate_limited": 3}),
        _srv(6000, over={"service.gen_rate_limited": 2})]), "A6")
    hits("A6 broadcast_positions decrement", law_A6_server([
        _srv(1000, over={"dirty.broadcast_positions": 7}),
        _srv(6000, over={"dirty.broadcast_positions": 6})]), "A6")
    hits("A6 re_resolved decrement", law_A6_server([
        _srv(1000, over={"service.re_resolved": 9}),
        _srv(6000, over={"service.re_resolved": 8})]), "A6")
    hits("A6 suppressed_total decrement", law_A6_server([
        _srv(1000, over={"dirty.suppressed_total": 5}),
        _srv(6000, over={"dirty.suppressed_total": 4})]), "A6")

    # --- A6 client: monotonic for the whole run (counters are run-cumulative; a
    # dimension/action boundary does NOT excuse a decrement) ---
    hits("A6 client in-segment decrement", law_A6_client(1, [
        _cli(1000, over={"responses.rate_limited": 5}),
        _cli(6000, over={"responses.rate_limited": 4})]), "A6")
    hits("A6 client cross-segment decrement", law_A6_client(1, [
        _cli(1000, seg=0, over={"responses.rate_limited": 5}),
        _cli(6000, seg=1, over={"responses.rate_limited": 0})]), "A6")
    clean("A6 client cross-segment growth", law_A6_client(1, [
        _cli(1000, seg=0, over={"responses.rate_limited": 5}),
        _cli(6000, seg=1, over={"responses.rate_limited": 5})]))

    # --- A7 server: errors/timeouts always fail; saturated honors the opt-in ---
    hits("A7 disk.errors", law_A7_server(
        _srv(1000), _srv(6000, over={"disk.errors": 1}), "selftest",
        frozenset({"rate_limited", "saturated"})), "A7")
    hits("A7 generation.timeouts", law_A7_server(
        _srv(1000), _srv(6000, over={"generation.timeouts": 1}), "selftest",
        frozenset({"rate_limited", "saturated"})), "A7")
    hits("A7 saturated w/o opt-in", law_A7_server(
        _srv(1000), _srv(6000, over={"disk.saturated": 1}), "selftest", frozenset()), "A7")
    clean("A7 saturated with opt-in", law_A7_server(
        _srv(1000), _srv(6000, over={"disk.saturated": 1}), "selftest",
        frozenset({"saturated"})))

    # --- A7 client: dropped never optable; rate_limited honors the opt-in ---
    hits("A7 client dropped", law_A7_client(
        _cli(1000), _cli(6000, over={"dropped": 1}), "selftest",
        frozenset({"rate_limited", "saturated"})), "A7")
    hits("A7 rate_limited w/o opt-in", law_A7_client(
        _cli(1000), _cli(6000, over={"responses.rate_limited": 1}), "selftest",
        frozenset()), "A7")
    clean("A7 rate_limited with opt-in", law_A7_client(
        _cli(1000), _cli(6000, over={"responses.rate_limited": 1}), "selftest",
        frozenset({"rate_limited"})))

    # --- B1: client rate_limited == sync + gen + saturated ---
    ps, pc = _srv(1000), _cli(1000)
    cs = _srv(6000, over={"service.sync_rate_limited": 2, "service.gen_rate_limited": 1,
                          "disk.saturated": 2})
    cc = _cli(6000, over={"responses.rate_limited": 5})
    clean("B1 balanced", law_B1(ps, cs, pc, cc, "selftest"))
    hits("B1 lost bounce", law_B1(ps, cs, pc,
                                  _cli(6000, over={"responses.rate_limited": 4}),
                                  "selftest"), "B1")

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
    quiet_player = {"name": "p", "held_sync": 0, "held_gen": 0, "send_queue": 0}
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
    held = _srv(6000)
    held["players"] = [{"name": "p", "held_sync": 0, "held_gen": 1, "send_queue": 0}]
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

    # --- Window floors (vacuous-pass guard) ---
    clean("floors met", check_window_floors({(1, 0): 3}, {(1, 0): 3}))
    hits("floors short", check_window_floors({(1, 0): 3}, {(1, 0): 2}), "law-coverage")
    hits("floors no windows", check_window_floors({(1, 0): 3}, {}), "law-coverage")

    # --- Named-check fixtures (synthetic Ctx, like disc completeness above) ---
    def _cmd(wall, cmd):
        return {"event": "command", "wallMs": wall, "tick": wall // 50, "cmd": cmd,
                "anchor": 1, "at": wall // 1000}

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

    # --- stress named checks: one clean + one catch case each (review finding) ---
    def stress_ctx(srv_over, cli_over, quiescent_last=True, config=None):
        return _ctx(
            server_snaps=[_srv(50_000), _srv(200_000, over=srv_over)],
            runs={1: [_cli(200_000, over=cli_over)]},
            quiescent_server={1} if quiescent_last else set(),
            config=config or {})

    storm_srv = {"service.gen_rate_limited": 40, "service.sync_rate_limited": 200,
                 "generation.completed": 165}
    storm_cli = {"responses.rate_limited": 240}
    clean("rate-limit-storm clean", list(check_rate_limit_storm(
        stress_ctx(storm_srv, storm_cli))))
    hits("rate-limit-storm no bounces", list(check_rate_limit_storm(
        stress_ctx({**storm_srv, "service.gen_rate_limited": 0},
                   {**storm_cli, "responses.rate_limited": 0}))), "rate-limit-storm")

    sat_srv = {"disk.saturated": 12, "disk.submitted": 2000, "disk.completed": 2000}
    sat_cli = {"responses.rate_limited": 12}
    clean("disk-saturation clean", list(check_disk_saturation(
        stress_ctx(sat_srv, sat_cli))))
    hits("disk-saturation never saturated", list(check_disk_saturation(
        stress_ctx({**sat_srv, "disk.saturated": 0}, sat_cli))), "disk-saturation")

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

    gcs_srv = {"generation.completed": 165}
    gcs_cli = {"responses.not_generated": 800}
    clean("generation-capacity-stress clean", list(check_generation_capacity_stress(
        stress_ctx(gcs_srv, gcs_cli))))
    hits("generation-capacity-stress stalled", list(check_generation_capacity_stress(
        stress_ctx({**gcs_srv, "generation.completed": 40}, gcs_cli, quiescent_last=False))),
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

    print(f"selftest OK: {cases[0]} cases — every law (A1-A7, B1, B2), the quiescence "
          f"predicate, disc completeness, window floors, the config allowlist, action "
          f"segmentation, and every session/movement/dirty-pipeline named check each "
          f"pass consistent data and catch a doctored inconsistency")
    return 0


# ------------------------------------------------------------------------------- main

def main(argv=None):
    parser = argparse.ArgumentParser(description="Soak harness invariant checker")
    parser.add_argument("--validate", metavar="SCENARIO",
                        help="pre-flight validation of a scenario (no results needed)")
    parser.add_argument("--selftest", action="store_true", help="run in-memory law selftest")
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
    return run_checker(results_dir, scenario)


if __name__ == "__main__":
    sys.exit(main())
