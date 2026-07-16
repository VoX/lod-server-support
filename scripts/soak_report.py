#!/usr/bin/env python3
"""soak_report.py — post-run anomaly digest for a soak results directory.

A LENS over a soak run, never a gate. The checker (check_soak.py) decides pass/fail
against conservation laws; this tool surfaces the things a law can't name — rate spikes,
counter stalls, unexpected-but-not-illegal nonzero counters, high-water marks, snapshot
cadence gaps, law margins (how close a passing run came to failing), and cross-identity
drift — so a human (or Claude) reviewing the run can spot UNEXPECTED problems.

Exit code is 0 regardless of what it finds, UNLESS --strict is given (then nonzero if any
anomaly was flagged). Stdlib only; imports check_soak for parsing + thresholds.

Usage:
  scripts/soak_report.py <results-dir> [--scenario NAME] [--json] [--strict] [--compare DIR2]
  scripts/soak_report.py --selftest

Sections:
  1. Run header + cadence health        5. High-water marks
  2. Timeline trace (command effects)   6. Law margins
  3. Rate series + spike/stall          7. Cross-identity audits
  4. Unexpected nonzero counters        8. Verdict echo   (9. --compare diff)
"""

import argparse
import importlib.util
import json
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))


def _load_check_soak():
    """Import check_soak.py by path (its __main__ guard keeps the CLI from running)."""
    spec = importlib.util.spec_from_file_location("check_soak", os.path.join(HERE, "check_soak.py"))
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


CS = _load_check_soak()

# CONCERNING counters: nonzero is a genuine "look at this" in a healthy run (errors, lost work,
# capacity exhaustion). These increment the anomaly count unless the scenario opts in via
# ANOMALY_OPT_INS. MECHANISM counters: nonzero is normal operation (the honesty/dedup/filter
# machinery doing its job) — shown for context, never counted as an anomaly. Friendly name -> path.
SERVER_CONCERNING = {
    "disk read errors": "disk.errors",
    "disk pool saturated": "disk.saturated",
    "generation timeouts": "generation.timeouts",
    "gen removed in-flight": "generation.removed_in_flight",
    "send-queue full drops": "service.queue_full",
}
SERVER_MECHANISM = {
    "duplicate skips": "service.duplicate_skips",
    # v17: work RE-PLANNED, not lost. A superseded want (mailbox overwrite / backlog
    # replace / residual saturation drop) is dropped without a wire response and healed by
    # the client's 1 Hz re-declaration — under a want-set protocol that is the normal
    # steady state, not an anomaly. Law A1 conserves it.
    "want-set superseded": "service.superseded",
    # Server-owned generation: a disk miss that could not take a generation slot right now
    # (cap full / capacity reject / ghost delivery) — a transient silent drop, re-declared
    # at 1 Hz until a slot frees. The dedicated law-A5 term (subset of superseded events).
    "gen-miss drops": "service.miss_dropped",
    "honest re-resolutions": "service.re_resolved",
    "dirty re-saves suppressed": "dirty.suppressed_total",
}
CLIENT_CONCERNING = {
    "columns dropped": "dropped",
    "ingest failures": "ingest_failures",
}
# v17 left this dict EMPTY: its only member was "client rate-limited"
# (responses.rate_limited), and that response no longer exists on the wire — a slot bounce
# is now a silently retained server-side backlog entry (see SERVER_MECHANISM's
# "want-set superseded"). The dict and its plumbing are kept deliberately: the client has
# no load-shaping mechanism counter today, and a future one belongs here rather than in
# CLIENT_CONCERNING. Note `ingest_failures` is NOT a mechanism — see the note below.
CLIENT_MECHANISM = {}
# Which CONCERNING names are the declared purpose of a scenario (then not counted as anomalies).
# NOTE: client "ingest failures" is deliberately NOT mapped here. It is genuine lost work (a consumer
# rejected a column) that the honest-re-resolution machinery exists to recover — never ordinary
# load-shaping. Mapping it to "rate_limited" (which ~15/17 scenarios opted into) silently labeled
# every nonzero ingest_failures "expected" and dropped it from the anomaly count. It is now always
# CONCERNING unless a scenario explicitly lists "ingest failures" in SCENARIO_CONCERNING_OPT_IN below.
# (v17 retired the "rate_limited" opt-in entirely; "saturated" — which the same ~15/17 scenarios opt
# into — would inherit the identical mislabel, so the regression guard below still has real teeth.)
OPT_IN_NAMES = {
    "disk pool saturated": "saturated",
}
# Per-scenario opt-ins for CONCERNING counters that are the expected mechanism of THAT scenario
# (the checker's conservation laws still account for them, so they are not lost work):
# queue_full backs up under a deliberate bandwidth throttle / saturation; removed_in_flight fires
# on the kicks and dimension changes those scenarios script. Confirmed benign in the round-2 sweep.
SCENARIO_CONCERNING_OPT_IN = {
    "bandwidth-throttle": {"send-queue full drops"},
    "disk-saturation": {"send-queue full drops"},
    "rate-limit-storm": {"send-queue full drops"},
    "warm-rejoin": {"gen removed in-flight"},
    "dimension-trip": {"gen removed in-flight"},
    "dimension-rejoin-warm": {"gen removed in-flight"},
    "dirty-while-offline": {"gen removed in-flight"},
    "cold-restart-resync": {"gen removed in-flight"},
}
HIGH_WATER = {
    "disk pending": "disk.pending_hw",
    "generation active": "generation.active_hw",
    "mailbox depth": "mailbox_depth_hw",
}
DRAIN_GAUGES = ("disk.pending", "generation.active", "dirty.pending")
DEFAULT_INTERVAL_MS = 5000
CADENCE_GAP_FACTOR = 1.5
MIN_TPS = 19.5


# --------------------------------------------------------------------------- pure detectors

def _get(row, dotted, default=None):
    try:
        return CS.get_path(row, dotted)
    except KeyError:
        return default


def median(xs):
    s = sorted(xs)
    n = len(s)
    if n == 0:
        return 0.0
    mid = n // 2
    return s[mid] if n % 2 else (s[mid - 1] + s[mid]) / 2.0


def mad(xs, med):
    return median([abs(x - med) for x in xs]) if xs else 0.0


def detect_spikes(deltas):
    """Indices i where deltas[i] is a positive spike: above max(5*median, mean+3*MAD).
    Needs >=4 samples; flat/tiny series never spike (a constant series has MAD 0 and a
    nonzero median only if every value equals it, so no value exceeds 5*median)."""
    pos = [d for d in deltas if d > 0]
    if len(deltas) < 4 or len(pos) < 2:
        return []
    med = median(pos)
    mean = sum(deltas) / len(deltas)
    m = mad(deltas, median(deltas))
    threshold = max(5 * med, mean + 3 * m)
    return [i for i, d in enumerate(deltas) if d > threshold and d > 0]


def detect_stalls(windows):
    """windows: list of {"delta": int, "active": bool}. A stall is a run of >=3 consecutive
    zero-delta windows during which a drain gauge / in-flight count is nonzero (work is
    pending but nothing is progressing). Returns list of (start, end) inclusive index pairs."""
    stalls = []
    run_start = None
    for i, w in enumerate(windows):
        if w["delta"] == 0 and w["active"]:
            run_start = i if run_start is None else run_start
        else:
            if run_start is not None and i - run_start >= 3:
                stalls.append((run_start, i - 1))
            run_start = None
    if run_start is not None and len(windows) - run_start >= 3:
        stalls.append((run_start, len(windows) - 1))
    return stalls


# --------------------------------------------------------------------------- loading

def load_dir(results_dir):
    """Parse a results dir into {scenario, server, client_runs, verdict, warnings}."""
    warnings, uk, ue = [], set(), set()
    server_path = os.path.join(results_dir, "server.jsonl")
    server = CS.load_server(_P(server_path), warnings, uk, ue) if os.path.exists(server_path) else None
    client_runs = []
    for name in sorted(os.listdir(results_dir)):
        if name.startswith("client-run") and name.endswith(".jsonl"):
            snaps, actions = CS.load_client_run(_P(os.path.join(results_dir, name)), warnings, uk, ue)
            client_runs.append({"snapshots": snaps, "actions": actions})
    verdict = None
    vp = os.path.join(results_dir, "verdict.json")
    if os.path.exists(vp):
        with open(vp) as f:
            verdict = json.load(f)
    scenario = None
    if verdict and isinstance(verdict, dict):
        scenario = verdict.get("scenario")
    if not scenario:
        base = os.path.basename(os.path.normpath(results_dir))
        scenario = _scenario_from_dirname(base)
    return {"scenario": scenario, "server": server, "client_runs": client_runs,
            "verdict": verdict, "warnings": warnings, "unknown_keys": uk}


def _scenario_from_dirname(base):
    """Dir is "<scenario>-[paper-|folia-]<YYYYMMDDTHHMMSSZ>"; strip the optional platform tag
    and the timestamp. A plain rsplit("-") would mangle hyphenated names (fresh-backfill)."""
    return re.sub(r"-(?:paper-|folia-)?\d{8}T\d{6}Z$", "", base) or base


class _P:
    """Minimal Path-like wrapper so check_soak's load_*(path.name) usage works on a str."""
    def __init__(self, s):
        self._s = s
        self.name = os.path.basename(s)

    def __fspath__(self):
        return self._s

    def __str__(self):
        return self._s


# --------------------------------------------------------------------------- sections

def section_header(rep, d):
    snaps = d["server"]["snapshots"] if d["server"] else []
    lines = [f"scenario: {d['scenario']}",
             f"server snapshots: {len(snaps)}   client runs: {len(d['client_runs'])}"]
    gaps = []
    for a, b in zip(snaps, snaps[1:]):
        gaps.append(b["wallMs"] - a["wallMs"])
    interval = median(gaps) if gaps else DEFAULT_INTERVAL_MS
    lines.append(f"snapshot interval (median): {interval/1000:.1f}s   run length: "
                 f"{(snaps[-1]['wallMs']-snaps[0]['wallMs'])/1000:.0f}s" if snaps else "no snapshots")
    flags = []
    for i, g in enumerate(gaps):
        if g > interval * CADENCE_GAP_FACTOR:
            flags.append(f"  ! cadence gap {g/1000:.1f}s at snapshot {i+1} "
                         f"(>{CADENCE_GAP_FACTOR}x interval — a stall can masquerade as quiescence)")
            rep["anomalies"] += 1
    # Derived TPS per window from the 'tick' field if present.
    for a, b in zip(snaps, snaps[1:]):
        ta, tb = a.get("tick"), b.get("tick")
        dt = (b["wallMs"] - a["wallMs"]) / 1000.0
        if ta is not None and tb is not None and dt > 0:
            tps = (tb - ta) / dt
            if 0 < tps < MIN_TPS:
                flags.append(f"  ! low TPS {tps:.1f} between snapshots at {b['wallMs']}ms")
                rep["anomalies"] += 1
    return lines + (flags or ["  cadence: steady"])


def section_timeline(rep, d):
    if not d["server"]:
        return ["no server log"]
    snaps = d["server"]["snapshots"]
    cmds = d["server"]["commands"]
    if not cmds:
        return ["no command rows"]
    lines = []
    for c in cmds:
        wall = c["wallMs"]
        ok = c.get("ok", True)
        before = [s for s in snaps if s["wallMs"] <= wall][-1:]
        after = [s for s in snaps if s["wallMs"] > wall][:1]
        tag = ""
        if before and after:
            moved = any(_get(after[0], p, 0) != _get(before[0], p, 0)
                        for p in CS.SERVER_MOVING)
            # Informational only (not an anomaly): setup steps (gamerule/tp/forceload/save) are
            # expected to be counter-inert; the tag just tells the reviewer which steps moved
            # the pipeline, so a content edit that was supposed to and didn't is spottable.
            tag = "" if moved else "  [no observable effect on server counters]"
        okmark = "" if ok else "  [STEP THREW]"
        if not ok:
            rep["anomalies"] += 1
        lines.append(f"  +{c.get('at','?')}s @{wall}ms  {c.get('cmd','?')}{okmark}{tag}")
    return lines


def section_rates(rep, d):
    if not d["server"]:
        return ["no server log"]
    snaps = d["server"]["snapshots"]
    out = []
    for label, path in (("requests", "service.requests_received"),
                        ("columns_sent", "service.columns_sent"),
                        ("bytes_sent", "service.bytes_sent")):
        vals = [(_get(s, path)) for s in snaps if _get(s, path) is not None]
        if len(vals) < 4:
            continue
        deltas = [b - a for a, b in zip(vals, vals[1:])]
        spikes = detect_spikes(deltas)
        windows = []
        for i, dlt in enumerate(deltas):
            nxt = snaps[i + 1]
            active = any((_get(nxt, g, 0) or 0) > 0 for g in DRAIN_GAUGES)
            windows.append({"delta": dlt, "active": active})
        stalls = detect_stalls(windows)
        total = vals[-1] - vals[0]
        line = f"  {label}: total={total}  windows={len(deltas)}"
        if spikes:
            line += f"  SPIKES@{[s+1 for s in spikes]}"
            rep["anomalies"] += 1
        if stalls:
            line += f"  STALLS(zero-delta while work pending)@{stalls}"
            rep["anomalies"] += 1
        out.append(line)
    return out or ["insufficient samples for rate analysis"]


def _first_nonzero_wall(snaps, path):
    for s in snaps:
        if (_get(s, path, 0) or 0) != 0:
            return s["wallMs"]
    return None


def section_unexpected(rep, d):
    out = []
    opt = CS.ANOMALY_OPT_INS.get(d["scenario"], frozenset())
    scen_opt = SCENARIO_CONCERNING_OPT_IN.get(d["scenario"], frozenset())

    def scan(label, snaps, watch, concerning):
        final = snaps[-1] if snaps else {}
        for name, path in watch.items():
            v = _get(final, path, 0) or 0
            if v == 0:
                continue
            when = _first_nonzero_wall(snaps, path)
            if concerning:
                opted = OPT_IN_NAMES.get(name) in opt or name in scen_opt
                note = "  (expected for this scenario)" if opted else "  <-- CONCERNING"
                out.append(f"  {label} {name}: {v} (first @{when}ms){note}")
                if not opted:
                    rep["anomalies"] += 1
            else:
                out.append(f"  {label} {name}: {v} (first @{when}ms)  [mechanism]")

    if d["server"]:
        snaps = d["server"]["snapshots"]
        scan("server", snaps, SERVER_CONCERNING, True)
        scan("server", snaps, SERVER_MECHANISM, False)
    for ri, run in enumerate(d["client_runs"]):
        scan(f"client-run{ri+1}", run["snapshots"], CLIENT_CONCERNING, True)
        scan(f"client-run{ri+1}", run["snapshots"], CLIENT_MECHANISM, False)
    return out or ["  none — all watched counters zero"]


def section_high_water(rep, d):
    if not d["server"]:
        return ["no server log"]
    snaps = d["server"]["snapshots"]
    out = []
    for name, path in HIGH_WATER.items():
        best, when = 0, None
        for s in snaps:
            v = _get(s, path, 0) or 0
            if v > best:
                best, when = v, s["wallMs"]
        out.append(f"  {name}: max={best}" + (f" @{when}ms" if when else ""))
    # per-player send-queue high-water
    pbest, pwhen = 0, None
    for s in snaps:
        for p in s.get("players", []):
            v = (p.get("send_queue_hw") or 0)
            if v > pbest:
                pbest, pwhen = v, s["wallMs"]
    out.append(f"  player send-queue: max={pbest}" + (f" @{pwhen}ms" if pwhen else ""))
    return out


def section_identities(rep, d):
    if not d["server"] or not d["server"]["snapshots"]:
        return ["no server log"]
    final = d["server"]["snapshots"][-1]
    out = []

    def audit(label, a_path, b_path, tol=0):
        a, b = _get(final, a_path), _get(final, b_path)
        if a is None or b is None:
            out.append(f"  {label}: n/a (missing field)")
            return
        ok = abs(a - b) <= tol
        if not ok:
            rep["anomalies"] += 1
        out.append(f"  {label}: {a} vs {b}  {'ok' if ok else 'MISMATCH'}")

    audit("disk.successful == service.disk_resolved", "disk.successful", "service.disk_resolved")
    audit("bandwidth.total_bytes ~ service.bytes_sent", "bandwidth.total_bytes",
          "service.bytes_sent", tol=0)
    return out


def section_margins(rep, d):
    """Best-effort law headroom. B2 needs the per-tick byte cap from the scenario config; if a
    <scenario>-config.json copy is in the dir we use it, else we report the raw byte rate."""
    if not d["server"]:
        return ["no server log"]
    snaps = d["server"]["snapshots"]
    if len(snaps) < 2:
        return ["insufficient samples"]
    out = []
    span_s = (snaps[-1]["wallMs"] - snaps[0]["wallMs"]) / 1000.0
    bytes_total = (_get(snaps[-1], "service.bytes_sent", 0) or 0) - (_get(snaps[0], "service.bytes_sent", 0) or 0)
    if span_s > 0:
        out.append(f"  mean send rate: {bytes_total/span_s/1024:.1f} KB/s over {span_s:.0f}s")
    # quiescence: how many consecutive-snapshot pairs were fully still
    still = 0
    for a, b in zip(snaps, snaps[1:]):
        if all((_get(b, g, 0) or 0) == 0 for g in DRAIN_GAUGES) and \
           all((_get(b, m) == _get(a, m)) for m in CS.SERVER_MOVING):
            still += 1
    out.append(f"  fully-quiescent snapshot pairs: {still}/{len(snaps)-1}")
    return out


def section_verdict(rep, d):
    v = d["verdict"]
    if not v:
        return ["  no verdict.json (checker not run yet)"]
    if isinstance(v, dict):
        passed = v.get("passed", v.get("ok"))
        nv = v.get("violations", v.get("num_violations"))
        return [f"  checker verdict: {'PASS' if passed else 'FAIL'}"
                + (f"   violations={nv}" if nv is not None else ""),
                f"  {json.dumps(v)[:300]}"]
    return [f"  {json.dumps(v)[:300]}"]


SECTIONS = [
    ("1. Run header + cadence", section_header),
    ("2. Timeline (command effects)", section_timeline),
    ("3. Rate series + spike/stall", section_rates),
    ("4. Unexpected nonzero counters", section_unexpected),
    ("5. High-water marks", section_high_water),
    ("6. Law margins", section_margins),
    ("7. Cross-identity audits", section_identities),
    ("8. Verdict echo", section_verdict),
]


def build_report(results_dir):
    d = load_dir(results_dir)
    rep = {"scenario": d["scenario"], "dir": results_dir, "anomalies": 0, "sections": {}}
    blocks = []
    for title, fn in SECTIONS:
        lines = fn(rep, d)
        rep["sections"][title] = lines
        blocks.append((title, lines))
    rep["_blocks"] = blocks
    if d["warnings"]:
        rep["warnings"] = d["warnings"]
    if d["unknown_keys"]:
        rep["unknown_keys"] = sorted(d["unknown_keys"])
    return rep


def render(rep):
    out = [f"=== soak report: {rep['scenario']}  ({rep['anomalies']} anomalies flagged) ===",
           f"    {rep['dir']}", ""]
    for title, lines in rep["_blocks"]:
        out.append(title)
        out.extend(lines)
        out.append("")
    if rep.get("unknown_keys"):
        out.append("unknown snapshot keys (schema drift?): " + ", ".join(rep["unknown_keys"]))
    if rep.get("warnings"):
        out.append(f"parse warnings: {len(rep['warnings'])}")
    return "\n".join(out)


def compare(rep_a, rep_b):
    out = [f"=== compare: {rep_a['scenario']} vs {rep_b['scenario']} ===",
           f"  anomalies: {rep_a['anomalies']} -> {rep_b['anomalies']}"]
    return "\n".join(out)


# --------------------------------------------------------------------------- selftest

def _selftest():
    n = 0

    def check(cond, msg):
        nonlocal n
        assert cond, "selftest FAIL: " + msg
        n += 1

    # spike detector: a clean flat series never spikes; one big jump does.
    check(detect_spikes([10, 10, 10, 10, 10]) == [], "flat series spikes")
    check(detect_spikes([10, 10, 1000, 10, 10]) == [2], "obvious spike missed")
    check(detect_spikes([5, 5]) == [], "too-short series spikes")
    # gentle ramp must not register as spikes
    check(detect_spikes([10, 11, 12, 13, 14, 15]) == [], "gentle ramp spikes")

    # stall detector: needs both zero-delta AND pending work.
    quiet = [{"delta": 0, "active": False}] * 5
    check(detect_stalls(quiet) == [], "idle quiet flagged as stall")
    stalled = [{"delta": 0, "active": True}] * 4
    check(detect_stalls(stalled) == [(0, 3)], "real stall missed")
    short = [{"delta": 0, "active": True}] * 2
    check(detect_stalls(short) == [], "2-window zero flagged (need >=3)")
    progressing = [{"delta": 5, "active": True}] * 5
    check(detect_stalls(progressing) == [], "progressing work flagged as stall")

    # median/mad sanity
    check(median([3, 1, 2]) == 2, "median odd")
    check(median([4, 1, 2, 3]) == 2.5, "median even")

    # results-dir scenario parsing: every platform tag strips cleanly
    check(_scenario_from_dirname("fresh-backfill-20260702T010203Z") == "fresh-backfill",
          "fabric dir name mis-parsed")
    check(_scenario_from_dirname("fresh-backfill-paper-20260702T010203Z") == "fresh-backfill",
          "paper dir name mis-parsed")
    check(_scenario_from_dirname("fresh-backfill-folia-20260702T010203Z") == "fresh-backfill",
          "folia dir name mis-parsed")

    # high-water + identity over a synthetic dir-like report
    snaps = [
        CS._srv(1000, over={"disk.pending_hw": 3, "service.disk_resolved": 1, "disk.successful": 1}),
        CS._srv(6000, over={"disk.pending_hw": 9, "service.disk_resolved": 5, "disk.successful": 5}),
    ]
    d = {"scenario": "x", "server": {"snapshots": snaps, "commands": [], "joins": [], "ends": []},
         "client_runs": [], "verdict": None, "warnings": [], "unknown_keys": set()}
    rep = {"anomalies": 0}
    hw = section_high_water(rep, d)
    check(any("max=9 @6000ms" in l for l in hw), "high-water extraction wrong")
    rep2 = {"anomalies": 0}
    ident = section_identities(rep2, d)
    check(rep2["anomalies"] == 0 and any("ok" in l for l in ident), "identity false-positive")
    # doctor a mismatch
    snaps[-1]["disk"]["successful"] = 99
    rep3 = {"anomalies": 0}
    section_identities(rep3, d)
    check(rep3["anomalies"] == 1, "identity mismatch not caught")

    # CONCERNING nonzero (disk.errors) outside an opt-in flags an anomaly.
    snaps_err = [CS._srv(1000, over={"disk.errors": 0}), CS._srv(6000, over={"disk.errors": 3})]
    d_err = {"scenario": "fresh-backfill", "server": {"snapshots": snaps_err, "commands": [], "joins": [], "ends": []},
             "client_runs": [], "verdict": None, "warnings": [], "unknown_keys": set()}
    rep_err = {"anomalies": 0}
    un_err = section_unexpected(rep_err, d_err)
    check(rep_err["anomalies"] == 1 and any("CONCERNING" in l for l in un_err), "disk.errors not flagged concerning")

    # MECHANISM nonzero (re_resolved) is SHOWN but NOT counted as an anomaly.
    snaps_mech = [CS._srv(1000, over={"service.re_resolved": 0}),
                  CS._srv(6000, over={"service.re_resolved": 4})]
    d_mech = {"scenario": "fresh-backfill", "server": {"snapshots": snaps_mech, "commands": [], "joins": [], "ends": []},
              "client_runs": [], "verdict": None, "warnings": [], "unknown_keys": set()}
    rep_mech = {"anomalies": 0}
    un_mech = section_unexpected(rep_mech, d_mech)
    check(rep_mech["anomalies"] == 0 and any("re-resolution" in l and "mechanism" in l for l in un_mech),
          "re_resolved should be shown as mechanism, not an anomaly")

    # disk.saturated is CONCERNING but opted-in for disk-saturation -> shown, not counted.
    snaps_sat = [CS._srv(1000, over={"disk.saturated": 0}), CS._srv(6000, over={"disk.saturated": 9})]
    d_sat = {"scenario": "disk-saturation", "server": {"snapshots": snaps_sat, "commands": [], "joins": [], "ends": []},
             "client_runs": [], "verdict": None, "warnings": [], "unknown_keys": set()}
    rep_sat = {"anomalies": 0}
    section_unexpected(rep_sat, d_sat)
    check(rep_sat["anomalies"] == 0, "opted-in saturated should not count as an anomaly")

    # Client ingest_failures is genuine lost work: CONCERNING even on a scenario whose SERVER opts
    # into a load-shaping flag (rate-limit-storm opts into "saturated"). Regression guard for the old
    # OPT_IN_NAMES["ingest failures"]="rate_limited" mislabel that inherited another counter's opt-in
    # on ~15/17 scenarios and dropped the anomaly. v17 retired "rate_limited"; "saturated" carries
    # the identical inheritance risk, so this guard is unchanged in force.
    cli_ing = {"snapshots": [{"wallMs": 1000, "ingest_failures": 0},
                             {"wallMs": 6000, "ingest_failures": 5}]}
    d_ing = {"scenario": "rate-limit-storm", "server": None,
             "client_runs": [cli_ing], "verdict": None, "warnings": [], "unknown_keys": set()}
    rep_ing = {"anomalies": 0}
    un_ing = section_unexpected(rep_ing, d_ing)
    check(rep_ing["anomalies"] == 1 and any("ingest failures" in l and "CONCERNING" in l for l in un_ing),
          "client ingest_failures must stay concerning on a scenario that opts into "
          "another load-shaping counter")

    print(f"soak_report selftest OK: {n} cases")
    return 0


# --------------------------------------------------------------------------- main

def main(argv):
    ap = argparse.ArgumentParser(description="Post-run soak anomaly digest (a lens, not a gate).")
    ap.add_argument("results_dir", nargs="?", help="soak-results/<scenario>-<ts> directory")
    ap.add_argument("--scenario", help="override scenario name")
    ap.add_argument("--json", action="store_true", help="emit the structured report as JSON")
    ap.add_argument("--strict", action="store_true", help="exit nonzero if any anomaly is flagged")
    ap.add_argument("--compare", help="a second results dir to diff against")
    ap.add_argument("--selftest", action="store_true", help="run the detector selftests and exit")
    args = ap.parse_args(argv)

    if args.selftest:
        return _selftest()
    if not args.results_dir:
        ap.error("results_dir is required (or use --selftest)")

    rep = build_report(args.results_dir)
    if args.scenario:
        rep["scenario"] = args.scenario

    if args.compare:
        rep_b = build_report(args.compare)
        if args.json:
            print(json.dumps({"a": _jsonable(rep), "b": _jsonable(rep_b)}, indent=2))
        else:
            print(render(rep))
            print()
            print(render(rep_b))
            print()
            print(compare(rep, rep_b))
    elif args.json:
        print(json.dumps(_jsonable(rep), indent=2))
    else:
        print(render(rep))

    if args.strict and rep["anomalies"] > 0:
        return 1
    return 0


def _jsonable(rep):
    return {k: v for k, v in rep.items() if not k.startswith("_")}


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
