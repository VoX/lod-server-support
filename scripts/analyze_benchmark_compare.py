#!/usr/bin/env python3
"""Analysis for the v16-vs-v17 CPU-efficiency benchmark comparison.

Design: docs/planning/benchmark-cpu-compare-design.md

Subcommands:
  coverage <world-dir>       Report base-world chunk coverage: spawn anchor, best center,
                             and the largest fully-present Chebyshev square radius
                             (pick the compare runs' lodDistanceChunks a few rings inside it).
  analyze <results-dir>      Per-run gates + metrics and cross-arm aggregation for one
                             benchmark_compare.sh matrix directory; writes report.md there.

Stdlib only (repo convention — same as check_soak.py / soak_report.py).
"""

import glob
import gzip
import json
import math
import os
import statistics
import struct
import sys

CLK_TCK = 100  # jiffies per second (getconf CLK_TCK — pinned; WSL2/x86_64 default)

# ---------------------------------------------------------------- coverage --

def read_spawn(world_dir):
    """Best-effort SpawnX/SpawnZ from level.dat via a byte scan for the NBT int tags
    (TAG_Int 0x03 + name). Returns (x, z) block coords or None."""
    try:
        raw = gzip.open(os.path.join(world_dir, "level.dat"), "rb").read()
    except OSError:
        return None
    out = {}
    for name in (b"SpawnX", b"SpawnZ"):
        pat = b"\x03" + struct.pack(">H", len(name)) + name
        i = raw.find(pat)
        if i < 0:
            return None
        out[name] = struct.unpack(">i", raw[i + len(pat):i + len(pat) + 4])[0]
    return out[b"SpawnX"], out[b"SpawnZ"]


def overworld_region_dir(world_dir):
    """MC 26.x dedicated servers keep the overworld at dimensions/minecraft/overworld/region;
    older layouts used world/region."""
    for candidate in (os.path.join(world_dir, "dimensions", "minecraft", "overworld", "region"),
                      os.path.join(world_dir, "region")):
        if os.path.isdir(candidate):
            return candidate
    return os.path.join(world_dir, "region")


def present_chunks(world_dir):
    """Set of (cx, cz) chunks that are FULLY generated on disk. Region-header presence is
    not enough: MC saves proto-chunks (pre-full generation stages) at the generation
    frontier, and LSS's disk read resolves those as not-found. Each present chunk's NBT is
    decompressed and checked for Status == minecraft:full."""
    import zlib
    present = set()
    for path in glob.glob(os.path.join(overworld_region_dir(world_dir), "r.*.mca")):
        parts = os.path.basename(path).split(".")
        rx, rz = int(parts[1]), int(parts[2])
        with open(path, "rb") as f:
            data = f.read()
        if len(data) < 4096:
            continue
        for i in range(1024):
            entry = struct.unpack(">I", data[i * 4:i * 4 + 4])[0]
            if entry == 0:
                continue
            off = (entry >> 8) * 4096
            if off + 5 > len(data):
                continue
            length, comp = struct.unpack(">IB", data[off:off + 5])
            payload = data[off + 5:off + 4 + length]
            try:
                if comp == 2:
                    nbt = zlib.decompress(payload)
                elif comp == 1:
                    nbt = gzip.decompress(payload)
                elif comp == 3:
                    nbt = payload
                else:
                    continue  # external/oversize or unknown — treat as not servable
            except Exception:
                continue
            if b"minecraft:full" in nbt:
                present.add((rx * 32 + (i % 32), rz * 32 + (i // 32)))
    return present


def max_full_square(present, cx, cz, cap=256):
    """Largest K such that every chunk with Chebyshev distance <= K of (cx,cz) is present."""
    k = 0
    while k < cap:
        n = k + 1
        ring = [(cx + dx, cz + dz) for dx in range(-n, n + 1) for dz in (-n, n)]
        ring += [(cx + dx, cz + dz) for dz in range(-n + 1, n) for dx in (-n, n)]
        if any(p not in present for p in ring):
            return k
        k = n
    return k


def cmd_coverage(world_dir):
    present = present_chunks(world_dir)
    if not present:
        print(f"no chunks found under {world_dir}/region")
        return 1
    spawn = read_spawn(world_dir)
    if spawn:
        ax, az = spawn[0] // 16, spawn[1] // 16
        anchor_src = f"level.dat spawn ({spawn[0]}, {spawn[1]})"
    else:
        ax = round(statistics.mean(c[0] for c in present))
        az = round(statistics.mean(c[1] for c in present))
        anchor_src = "centroid of present chunks (level.dat spawn not found)"
    best = (-1, ax, az)
    for dx in range(-4, 5):
        for dz in range(-4, 5):
            k = max_full_square(present, ax + dx, az + dz)
            if k > best[0]:
                best = (k, ax + dx, az + dz)
    k, bx, bz = best
    print(f"world:            {world_dir}")
    print(f"present chunks:   {len(present)}")
    print(f"anchor:           chunk ({ax}, {az})  [{anchor_src}]")
    print(f"best center:      chunk ({bx}, {bz})")
    print(f"max full square:  R = {k}  (all chunks within Chebyshev {k} present)")
    print(f"suggested lodDistanceChunks: {max(k - 3, 0)}  (3-ring margin for player-spawn offset)")
    return 0


# ----------------------------------------------------------------- analyze --

def load_jsonl(path):
    rows = []
    with open(path) as f:
        for line in f:
            line = line.strip()
            if line:
                rows.append(json.loads(line))
    return rows


def smoothed_slopes(ts, vals, window=3):
    """Per-sample slope of vals over time, smoothed by a trailing mean of `window`."""
    raw = [0.0]
    for i in range(1, len(ts)):
        dt = ts[i] - ts[i - 1]
        raw.append((vals[i] - vals[i - 1]) / dt if dt > 0 else 0.0)
    return [statistics.mean(raw[max(0, i - window + 1):i + 1]) for i in range(len(raw))]


def cpu_delta(ts, cpu, t0, t1):
    """CPU jiffies accumulated in [t0, t1] using the nearest samples."""
    def at(t):
        best = min(range(len(ts)), key=lambda i: abs(ts[i] - t))
        return cpu[best]
    return at(t1) - at(t0)


def analyze_run(run_dir):
    meta = json.load(open(os.path.join(run_dir, "meta.json")))
    server = json.load(open(os.path.join(run_dir, "server.json")))
    client = json.load(open(os.path.join(run_dir, "client.json")))
    samples = load_jsonl(os.path.join(run_dir, "cpu.jsonl"))

    # Rows where the server JVM existed
    srv_rows = [r for r in samples if isinstance(r.get("srv_cpu"), (int, float))]
    if len(srv_rows) < 20:
        return {"run": os.path.basename(run_dir), "error": "too few server samples"}
    ts = [r["t"] for r in srv_rows]
    srv = [r["srv_cpu"] for r in srv_rows]
    wire = [r["wire_bytes"] or 0 for r in srv_rows]
    # wire_bytes drops to 0 when the socket closes; hold the last nonzero value
    for i in range(1, len(wire)):
        if wire[i] < wire[i - 1]:
            wire[i] = wire[i - 1]

    slopes = smoothed_slopes(ts, wire)
    peak = max(slopes) if slopes else 0.0
    thresh = max(50_000.0, peak * 0.01)
    active_idx = [i for i, s in enumerate(slopes) if s > thresh]
    if not active_idx:
        return {"run": os.path.basename(run_dir), "error": "no active window detected"}
    t_first, t_last = ts[active_idx[0]], ts[active_idx[-1]]
    active_s = max(t_last - t_first, 1)
    tail_s = ts[-1] - t_last

    # Idle slope from the post-convergence tail (jiffies/s)
    tail = [(t, c) for t, c in zip(ts, srv) if t >= t_last + 5]
    idle_slope = ((tail[-1][1] - tail[0][1]) / (tail[-1][0] - tail[0][0])
                  if len(tail) >= 5 and tail[-1][0] > tail[0][0] else None)

    d_cpu = cpu_delta(ts, srv, t_first, t_last)  # jiffies
    cpu_active_s = d_cpu / CLK_TCK
    corrected_s = (cpu_active_s - idle_slope / CLK_TCK * active_s
                   if idle_slope is not None else None)

    cols = client.get("columns_received", 0)
    mb = client.get("bytes_received", 0) / (1024 * 1024)

    # Client CPU over the same window (secondary — includes rendering)
    cli_rows = [r for r in srv_rows if isinstance(r.get("cli_cpu"), (int, float))]
    cli_active_s = cli_corrected_s = None
    if len(cli_rows) >= 20:
        cts = [r["t"] for r in cli_rows]
        cli = [r["cli_cpu"] for r in cli_rows]
        if cts[0] <= t_first and cts[-1] >= t_last:
            cli_active_s = cpu_delta(cts, cli, t_first, t_last) / CLK_TCK
            ctail = [(t, c) for t, c in zip(cts, cli) if t >= t_last + 5]
            if len(ctail) >= 5 and ctail[-1][0] > ctail[0][0]:
                cslope = (ctail[-1][1] - ctail[0][1]) / (ctail[-1][0] - ctail[0][0])
                cli_corrected_s = cli_active_s - cslope / CLK_TCK * active_s

    # Foreign load during the active window: whole-box busy minus our two JVMs, in cores
    win = [r for r in srv_rows if t_first <= r["t"] <= t_last]
    noise_cores = None
    if len(win) >= 2:
        d_sys = win[-1]["sys_busy"] - win[0]["sys_busy"]
        d_us = (win[-1]["srv_cpu"] - win[0]["srv_cpu"]) + (
            (win[-1].get("cli_cpu") or 0) - (win[0].get("cli_cpu") or 0))
        dt = win[-1]["t"] - win[0]["t"]
        if dt > 0:
            noise_cores = (d_sys - d_us) / CLK_TCK / dt

    dr = server.get("disk_reader", {})
    res = {
        "run": os.path.basename(run_dir),
        "arm": meta["arm"],
        "rep": meta["rep"],
        "ref": meta.get("ref"),
        "columns": cols,
        "mb_received": round(mb, 1),
        "active_s": round(active_s, 1),
        "tail_s": round(tail_s, 1),
        "cols_per_s": round(cols / active_s, 1),
        "srv_cpu_active_s": round(cpu_active_s, 2),
        "srv_idle_cores": round(idle_slope / CLK_TCK, 3) if idle_slope is not None else None,
        "srv_cpu_corrected_s": round(corrected_s, 2) if corrected_s is not None else None,
        "srv_cpus_per_1k_cols": round(corrected_s / cols * 1000, 3) if corrected_s and cols else None,
        "srv_cpus_per_1k_cols_raw": round(cpu_active_s / cols * 1000, 3) if cols else None,
        "srv_cpus_per_mb": round(corrected_s / mb, 3) if corrected_s and mb else None,
        "cli_cpu_active_s": round(cli_active_s, 2) if cli_active_s is not None else None,
        "cli_cpus_per_1k_cols": (round(cli_corrected_s / cols * 1000, 3)
                                 if cli_corrected_s is not None and cols else None),
        "peak_rss_mb": round(max(r["srv_rss"] for r in srv_rows) / (1024 * 1024), 0),
        "gc_time_ms": server.get("jvm", {}).get("gc_time_ms"),
        "gc_count": server.get("jvm", {}).get("gc_count"),
        "noise_cores": round(noise_cores, 3) if noise_cores is not None else None,
        "disk_not_found": dr.get("not_found"),
        "disk_errors": dr.get("errors"),
        "disk_avg_read_ms": round(dr.get("avg_read_time_ms", 0), 2),
        "positions_requested": client.get("positions_requested"),
        "send_cycles": client.get("send_cycles"),
        "rate_limited": client.get("total_rate_limited"),  # v16 only
        "up_to_date": client.get("total_up_to_date"),
        "not_generated": client.get("total_not_generated"),
    }

    gates = []
    if dr.get("not_found", 0) != 0:
        gates.append(f"not_found={dr['not_found']} (coverage hole / memo armed)")
    if dr.get("errors", 0) != 0:
        gates.append(f"disk errors={dr['errors']}")
    if tail_s < 15:
        gates.append(f"tail only {tail_s:.0f}s — may not have converged")
    if noise_cores is not None and noise_cores > 1.5:
        gates.append(f"foreign load {noise_cores:.2f} cores during active window")
    res["gate_failures"] = gates
    return res


def cmd_analyze(results_dir):
    runs = []
    for run_dir in sorted(glob.glob(os.path.join(results_dir, "*-rep*"))):
        if not os.path.isdir(run_dir):
            continue
        try:
            runs.append(analyze_run(run_dir))
        except (OSError, json.JSONDecodeError, KeyError) as e:
            runs.append({"run": os.path.basename(run_dir), "error": repr(e)})

    ok = [r for r in runs if "error" not in r]
    arms = {}
    for r in ok:
        arms.setdefault(r["arm"], []).append(r)

    lines = ["# v16 vs v17 CPU-efficiency comparison", ""]
    lines.append("Headline: idle-corrected server CPU-seconds per 1000 columns delivered "
                 "(lower = more efficient). Rate reported alongside — a faster arm doing the "
                 "same total work in less time is not a regression.")
    lines.append("")
    lines.append("| arm | runs | med CPU-s/1k cols | min..max | med cols/s | med raw CPU-s/1k "
                 "| med CPU-s/MB | med idle cores | med GC ms | med peak RSS MB |")
    lines.append("|-----|------|-------------------|----------|------------|------------------"
                 "|--------------|----------------|-----------|-----------------|")

    def med(rs, key):
        vals = [r[key] for r in rs if r.get(key) is not None]
        return statistics.median(vals) if vals else None

    def fmt(v, nd=3):
        return f"{v:.{nd}f}" if isinstance(v, float) else (str(v) if v is not None else "—")

    for arm in ("v16", "v17", "v17-fg"):
        rs = arms.get(arm, [])
        if not rs:
            continue
        eff = [r["srv_cpus_per_1k_cols"] for r in rs if r.get("srv_cpus_per_1k_cols")]
        rng = f"{min(eff):.2f}..{max(eff):.2f}" if eff else "—"
        lines.append("| {} | {} | {} | {} | {} | {} | {} | {} | {} | {} |".format(
            arm, len(rs), fmt(med(rs, "srv_cpus_per_1k_cols")), rng,
            fmt(med(rs, "cols_per_s"), 1), fmt(med(rs, "srv_cpus_per_1k_cols_raw")),
            fmt(med(rs, "srv_cpus_per_mb")), fmt(med(rs, "srv_idle_cores")),
            fmt(med(rs, "gc_time_ms"), 0), fmt(med(rs, "peak_rss_mb"), 0)))
    lines.append("")

    # Cross-arm equal-work check
    col_counts = {arm: [r["columns"] for r in rs] for arm, rs in arms.items()}
    if len(col_counts) > 1:
        allc = [c for cs in col_counts.values() for c in cs]
        if allc and (max(allc) - min(allc)) / max(allc) > 0.01:
            lines.append(f"**WARNING: column counts differ across runs by >1%: "
                         f"{col_counts} — equal-work assumption violated.**")
            lines.append("")

    lines.append("## Per-run detail")
    lines.append("")
    for r in runs:
        if "error" in r:
            lines.append(f"- **{r['run']}**: ERROR {r['error']}")
            continue
        gate = f"  GATES: {'; '.join(r['gate_failures'])}" if r["gate_failures"] else ""
        lines.append(
            f"- **{r['run']}** ({r['ref']}): {r['columns']} cols, {r['mb_received']} MB in "
            f"{r['active_s']}s ({r['cols_per_s']} col/s); srv CPU active {r['srv_cpu_active_s']}s"
            f" (corrected {r['srv_cpu_corrected_s']}s, {r['srv_cpus_per_1k_cols']}/1k cols); "
            f"idle {r['srv_idle_cores']} cores; cli/1k {r['cli_cpus_per_1k_cols']}; "
            f"GC {r['gc_time_ms']}ms/{r['gc_count']}; RSS {r['peak_rss_mb']}MB; "
            f"noise {r['noise_cores']} cores; reqs {r['positions_requested']} in "
            f"{r['send_cycles']} cycles"
            + (f"; rate_limited {r['rate_limited']}" if r.get("rate_limited") else "")
            + f"; up_to_date {r['up_to_date']}; not_gen {r['not_generated']};"
            f" disk nf={r['disk_not_found']} err={r['disk_errors']}"
            f" avg_read {r['disk_avg_read_ms']}ms; tail {r['tail_s']}s{gate}")

    report = "\n".join(lines) + "\n"
    out = os.path.join(results_dir, "report.md")
    with open(out, "w") as f:
        f.write(report)
    with open(os.path.join(results_dir, "runs.json"), "w") as f:
        json.dump(runs, f, indent=2)
    print(report)
    print(f"[analyze] wrote {out}")
    return 0


def main():
    if len(sys.argv) < 3:
        print(__doc__)
        return 1
    cmd, arg = sys.argv[1], sys.argv[2]
    if cmd == "coverage":
        return cmd_coverage(arg)
    if cmd == "analyze":
        return cmd_analyze(arg)
    print(__doc__)
    return 1


if __name__ == "__main__":
    sys.exit(main())
