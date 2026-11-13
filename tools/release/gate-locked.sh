#!/usr/bin/env bash
# Run gate.sh for one line while holding the shared harness lock for the whole gate. Waits up to
# two hours for the lock; the nested run-gradle.sh calls reuse the inherited descriptor.
# Usage: gate-locked.sh <worktree> [<output-root>]
set -uo pipefail
dir="/tmp/lss-harness-$UID"; mkdir -p -m 700 "$dir"
exec {fd}>"$dir/port-25565.lock"
flock -w 7200 "$fd" || { echo "lock wait timed out" >&2; exit 75; }
export LSS_HARNESS_LOCK_FD="$fd"
bash "$(dirname "$0")/gate.sh" "$@"
