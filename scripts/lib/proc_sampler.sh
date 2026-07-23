#!/usr/bin/env bash
# 1 Hz CPU/RSS/wire sampler for benchmark_compare.sh — external measurement, no
# harness modification (works identically against any LSS ref).
#
# Usage: proc_sampler.sh <out.jsonl> <max-seconds>
#
# Discovers the benchmark server JVM (-Dlss.benchmark.duration on its command line)
# and client JVM (--quickPlayMultiplayer), then appends one JSON row per second:
#   t          epoch seconds
#   srv_cpu    server utime+stime, jiffies (CLK_TCK=100), all threads
#   srv_rss    server resident bytes
#   srv_thr    server thread count
#   cli_cpu    client utime+stime, jiffies
#   cli_rss    client resident bytes
#   sys_busy   whole-box busy jiffies (total - idle - iowait), all CPUs
#   sys_total  whole-box total jiffies
#   wire_bytes cumulative bytes_acked on the server-side :25565 socket (vanilla +
#              LOD traffic — the delivery curve that defines the active window)
#
# Exits when the server JVM disappears after having been seen, or at max-seconds.
set -u

OUT="$1"
MAX_SECONDS="${2:-900}"

SRV_PID=""
CLI_PID=""
SEEN_SERVER=0
ELAPSED=0

# /proc/<pid>/stat with comm stripped: fields after ')' start at overall field 3
# (state), so utime(14)->idx 11, stime(15)->idx 12, num_threads(20)->idx 17.
read_proc() { # pid -> "cpu rss_bytes threads" or ""
    local pid="$1" stat rest rss
    stat=$(cat "/proc/$pid/stat" 2>/dev/null) || return 1
    rest="${stat##*) }"
    # shellcheck disable=SC2206
    local f=($rest)
    rss=$(awk '{print $2 * 4096}' "/proc/$pid/statm" 2>/dev/null) || return 1
    echo "$(( f[11] + f[12] )) $rss ${f[17]}"
}

find_pid() { # pattern -> newest matching pid ("" if none)
    pgrep -f "$1" 2>/dev/null | tail -1
}

: > "$OUT"
while [ "$ELAPSED" -lt "$MAX_SECONDS" ]; do
    NOW=$(date +%s)

    if [ -z "$SRV_PID" ] || [ ! -d "/proc/$SRV_PID" ]; then
        SRV_PID=$(find_pid 'Dlss\.benchmark\.duration')
    fi
    if [ -z "$CLI_PID" ] || [ ! -d "/proc/$CLI_PID" ]; then
        CLI_PID=$(find_pid 'quickPlayMultiplayer')
    fi

    SRV="null null null"
    if [ -n "$SRV_PID" ]; then
        if SRVDATA=$(read_proc "$SRV_PID"); then
            SRV="$SRVDATA"
            SEEN_SERVER=1
        else
            SRV_PID=""
        fi
    fi
    CLI="null null null"
    if [ -n "$CLI_PID" ]; then
        CLI=$(read_proc "$CLI_PID") || { CLI="null null null"; CLI_PID=""; }
    fi

    # cpu  user nice system idle iowait irq softirq steal ...
    read -r _ u n s idle iow irq sirq steal _ < /proc/stat
    SYS_TOTAL=$(( u + n + s + idle + iow + irq + sirq + steal ))
    SYS_BUSY=$(( SYS_TOTAL - idle - iow ))

    WIRE=$(ss -tinH state established '( sport = :25565 )' 2>/dev/null \
        | grep -o 'bytes_acked:[0-9]*' | cut -d: -f2 | awk '{s+=$1} END {print s+0}')

    set -- $SRV; SRV_CPU=$1; SRV_RSS=$2; SRV_THR=$3
    set -- $CLI; CLI_CPU=$1; CLI_RSS=$2
    printf '{"t":%s,"srv_cpu":%s,"srv_rss":%s,"srv_thr":%s,"cli_cpu":%s,"cli_rss":%s,"sys_busy":%s,"sys_total":%s,"wire_bytes":%s}\n' \
        "$NOW" "$SRV_CPU" "$SRV_RSS" "$SRV_THR" "$CLI_CPU" "$CLI_RSS" \
        "$SYS_BUSY" "$SYS_TOTAL" "${WIRE:-0}" >> "$OUT"

    # Server was up and is now gone: run over.
    if [ "$SEEN_SERVER" -eq 1 ] && [ -z "$SRV_PID" ]; then
        exit 0
    fi

    sleep 1
    ELAPSED=$(( ELAPSED + 1 ))
done
exit 0
