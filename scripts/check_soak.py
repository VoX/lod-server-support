#!/usr/bin/env python3
"""Soak-harness invariant checker (stdlib only). Authoritative contract:
docs/superpowers/specs/2026-06-10-soak-test-design.md

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

Quiescence predicate: across >=2 consecutive server snapshots in the same join segment,
service.requests_received, service.columns_sent, disk.submitted, generation.submitted and
generation.completed are unchanged AND (at both endpoints) every players[] row has
held_sync == held_gen == send_queue == 0, disk.pending == 0, generation.active == 0,
dirty.pending == 0; joined to the nearest-in-wallMs client snapshot of the matching run
(<= 3 s skew) which must have tracker_in_flight == 0 and queued == 0.

Modes:
  check_soak.py --validate <scenario-name>      pre-flight scenario/config/registry validation
  check_soak.py <results-dir> <scenario-name>   evaluate laws + named checks, write verdict.json
  check_soak.py --selftest                      in-memory law sanity check (A4)
"""

import argparse
import json
import sys
from dataclasses import dataclass, field
from pathlib import Path

SCENARIO_DIR = Path(__file__).resolve().parent / "soak-scenarios"
SKEW_MS = 3000  # max |server.wallMs - client.wallMs| for a quiescence join
DEFAULT_DIRTY_BROADCAST_SECONDS = 5  # used when the scenario -config.json omits the key

EXPECTED_RUNS = {"warm-rejoin": 2}  # every other scenario has exactly 1 client run

# A7 opt-ins. The spec treats saturated/rate_limited as one opt-in pair (disk saturation
# surfaces to the client as rate_limited BY DESIGN), so opting in suppresses both flags.
# errors / timeouts / dropped can never be opted out.
ANOMALY_OPT_INS = {
    "fresh-backfill": frozenset({"rate_limited", "saturated"}),
    "warm-rejoin": frozenset({"rate_limited", "saturated"}),
    "dimension-trip": frozenset({"rate_limited", "saturated"}),
    "dirty-broadcast": frozenset(),
}

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

# A6 whitelist — ONLY these are required to be monotonic. Gauges (disk.pending,
# generation.active, dirty.pending, players[].*) are deliberately absent.
SERVER_MONOTONIC = (
    "service.requests_received", "service.columns_sent", "service.bytes_sent",
    "service.duplicate_skips", "service.queue_full", "service.up_to_date",
    "service.in_memory", "service.disk_resolved", "service.gen_drained",
    "disk.submitted", "disk.completed", "disk.not_found", "disk.all_air",
    "disk.errors", "disk.saturated", "disk.successful",
    "generation.submitted", "generation.completed", "generation.timeouts",
    "generation.removed_in_flight",
    "bandwidth.total_bytes",
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
    "snapshot": {"event", "wallMs", "tick", "service", "disk", "generation", "dirty",
                 "bandwidth", "players"},
    "command": {"event", "wallMs", "tick", "cmd", "anchor", "at"},
    "join": {"event", "wallMs", "tick", "player", "joinIndex"},
    "end": {"event", "wallMs", "tick", "reason"},
}
KNOWN_CLIENT_KEYS = {
    "snapshot": {"event", "wallMs", "dimension", "received_columns", "received_bytes",
                 "dropped", "responses", "requested_total", "send_cycles", "columns",
                 "scan", "tracker_in_flight", "queued"},
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
    """Returns snapshot-bearing rows (event snapshot or disconnect — the disconnect row
    carries the final snapshot inline), each tagged _seg by dimension segmentation."""
    rows = parse_jsonl(path, warnings, path.name)
    snaps = []
    for row in rows:
        ev = row["event"]
        known = KNOWN_CLIENT_KEYS.get(ev)
        if known is None:
            unknown_events.add(f"client:{ev}")
            continue
        for k in row.keys() - known:
            unknown_keys.add(f"client.{ev}:{k}")
        snaps.append(row)
    seg, prev_dim = 0, None
    for row in snaps:
        dim = row["dimension"] if "dimension" in row else None
        if prev_dim is not None and dim != prev_dim:
            seg += 1
        prev_dim = dim
        row["_seg"] = seg
    return snaps


def client_segments(snaps):
    """[(seg_index, dimension, first_idx, last_idx)] in order."""
    segs = []
    for i, row in enumerate(snaps):
        if not segs or segs[-1][0] != row["_seg"]:
            segs.append([row["_seg"], row.get("dimension"), i, i])
        else:
            segs[-1][3] = i
    return [tuple(s) for s in segs]


# ------------------------------------------------------------------------- quiescence

@dataclass
class QPoint:
    si: int        # server snapshot index (the LATER element of the stable pair)
    sseg: int      # server join segment (== client run number; 0 = before first join)
    run: int       # client run number
    ci: int        # index into that run's snapshot list
    cseg: int      # client dimension segment of the joined snapshot
    wall: int      # server snapshot wallMs


def server_pair_quiescent(prev, cur):
    if prev["_seg"] != cur["_seg"]:
        return False
    for path in SERVER_MOVING:
        if get_path(prev, path) != get_path(cur, path):
            return False
    for snap in (prev, cur):
        for path in SERVER_DRAINS:
            if get_path(snap, path) != 0:
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
        qpoints.append(QPoint(si=i, sseg=cur["_seg"], run=run, ci=ci,
                              cseg=crow["_seg"], wall=cur["wallMs"]))
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
    """Client counters are monotonic only within one run AND one dimension segment."""
    out = []
    for i in range(1, len(snaps)):
        prev, cur = snaps[i - 1], snaps[i]
        if prev["_seg"] != cur["_seg"]:
            continue  # reset at dimension change is legitimate
        for path in CLIENT_MONOTONIC:
            pv, cv = get_path(prev, path), get_path(cur, path)
            if cv < pv:
                out.append(Violation("A6", window_label(prev, cur, run=run, dim=cur.get("dimension")),
                                     f"client counter {path} decreased inside one dimension segment",
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
    """prev may be None: head window from run/segment start (client counters reset there)."""
    out = []
    _anomaly(prev, cur, "dropped", window, "client dropped", opt_ins, None, out)
    _anomaly(prev, cur, "responses.rate_limited", window, "responses.rate_limited",
             opt_ins, "rate_limited", out)
    return out


# ------------------------------------------------------------------ law orchestration

def evaluate_laws(ctx):
    """Returns (violations, windows_evaluated). Window kinds:
    - in-window quiescent pairs (same run + same dimension segment): A1-A5 + A7
    - boundary-spanning quiescent pairs (join/dimension boundary between them): A7 only
      (the spec: boundaries get drain+anomaly checks only)
    - head/tail anomaly coverage: process-start -> first qpoint and last qpoint -> final
      snapshot (server); segment-start -> first qpoint and last qpoint -> segment end
      per client (run, dimension segment). These do not count as evaluated windows.
    """
    violations = []
    windows = 0
    snaps = ctx.server_snaps
    opt_ins = ANOMALY_OPT_INS.get(ctx.scenario, frozenset())

    # A6 over raw series.
    violations += law_A6_server(snaps)
    for run, csnaps in sorted(ctx.runs.items()):
        violations += law_A6_client(run, csnaps)

    # Quiescent-pair windows.
    for k in range(1, len(ctx.qpoints)):
        q1, q2 = ctx.qpoints[k - 1], ctx.qpoints[k]
        ps, cs = snaps[q1.si], snaps[q2.si]
        same_window = (q1.run == q2.run and q1.cseg == q2.cseg and q1.sseg == q2.sseg)
        # Client-involving laws need more than instant-stillness at the endpoints: the
        # paired client row can lag the server instant by up to SKEW_MS, so the endpoint
        # must sit inside a TWO-SIDED still plateau (the pair behind it proves stillness
        # before; the row after it must be still too, covering the client row's skew).
        # A one-sided endpoint let a send burst land between the server instant and the
        # client row — server delta 52, client delta 0, false A1/A2. Also require
        # distinct ordered client rows (same-row joins make client deltas vacuously 0).
        def two_sided(q):
            return q.si + 1 >= len(snaps) or server_pair_quiescent(snaps[q.si], snaps[q.si + 1])
        client_laws_ok = same_window and q1.ci < q2.ci and two_sided(q1) and two_sided(q2)
        if same_window:
            pc, cc = ctx.runs[q1.run][q1.ci], ctx.runs[q2.run][q2.ci]
            win = window_label(ps, cs, run=q1.run, dim=cc.get("dimension"))
            if client_laws_ok:
                violations += law_A1(ps, cs, pc, cc, win)
                violations += law_A2(ps, cs, pc, cc, win)
                violations += law_A5(ps, cs, pc, cc, win)
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
            chain = [None] + [csnaps[ci] for ci in qcis]
            if not qcis or qcis[-1] < hi:
                chain.append(csnaps[hi])
            for j in range(1, len(chain)):
                prev, cur = chain[j - 1], chain[j]
                start = csnaps[lo]["wallMs"] if prev is None else prev["wallMs"]
                win = f"wallMs[{start}..{cur['wallMs']}] run{run} dim={dim}"
                violations += law_A7_client(prev, cur, win, opt_ins)
    return violations, windows


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


CHECKS = {
    "fresh-backfill": [check_fresh_backfill],
    "warm-rejoin": [check_warm_rejoin],
    "dimension-trip": [check_dimension_trip],
    "dirty-broadcast": [check_dirty_broadcast],
}


# ---------------------------------------------------------------------- validate mode

def validate_scenario(name):
    errors = []
    scen_path = SCENARIO_DIR / f"{name}.json"
    cfg_path = SCENARIO_DIR / f"{name}-config.json"
    scen = None
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

    if scen is not None:
        errors += validate_timeline(scen)

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

    runs = {}
    expected_runs = EXPECTED_RUNS.get(scenario, 1)
    for n in range(1, expected_runs + 1):
        run_path = results_dir / f"client-run{n}.jsonl"
        if not run_path.is_file():
            violations.append(Violation("input", run_path.name, "expected client run file missing", {}))
            continue
        snaps = load_client_run(run_path, warnings, unknown_keys, unknown_events)
        if not snaps:
            violations.append(Violation("input", run_path.name, "zero snapshot rows", {}))
        runs[n] = snaps
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

    qpoints, windows = [], 0
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
                  quiescent_client={(q.run, q.ci) for q in qpoints})
        try:
            law_violations, windows = evaluate_laws(ctx)
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
        "quiescent_snapshots": len(qpoints),
    }
    try:
        (results_dir / "verdict.json").write_text(json.dumps(verdict, indent=2) + "\n",
                                                  encoding="utf-8")
    except OSError as e:
        print(f"WARNING: could not write verdict.json: {e}", file=sys.stderr)

    status = "PASS" if not violations else "FAIL"
    print(f"{status}: {scenario} — {windows} windows, {len(qpoints)} quiescent snapshots, "
          f"{len(violations)} violations, {len(warnings)} warnings")
    for v in violations:
        print("  " + v.line())
    for w in warnings:
        print(f"  WARNING: {w}")
    return 0 if not violations else 1


# --------------------------------------------------------------------------- selftest

def selftest():
    def snap(gen_completed):
        return {"event": "snapshot", "wallMs": 1000, "tick": 0,
                "service": {"requests_received": 5, "columns_sent": 5, "bytes_sent": 100,
                            "duplicate_skips": 0, "queue_full": 0, "up_to_date": 0,
                            "in_memory": 0, "disk_resolved": 0, "gen_drained": 0},
                "disk": {"submitted": 5, "completed": 5, "not_found": 5, "all_air": 0,
                         "errors": 0, "saturated": 0, "successful": 0, "pending": 0},
                "generation": {"submitted": 5, "completed": gen_completed, "timeouts": 1,
                               "removed_in_flight": 0, "active": 0},
                "dirty": {"pending": 0}, "bandwidth": {"total_bytes": 100}, "players": []}

    passing_prev, passing_cur = snap(4), snap(4)          # deltas all zero -> A4 holds
    failing_prev, failing_cur = snap(4), snap(3)          # completed decremented -> A4 broken
    failing_cur["wallMs"] = passing_cur["wallMs"] = 6000

    assert law_A4(passing_prev, passing_cur, "selftest") == [], "A4 false positive"
    hits = law_A4(failing_prev, failing_cur, "selftest")
    assert hits and all(v.law == "A4" for v in hits), "A4 failed to catch decremented generation.completed"
    print("selftest OK: A4 passes a stable pair and catches a decremented generation.completed")
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
