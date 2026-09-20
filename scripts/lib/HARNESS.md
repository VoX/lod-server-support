The soak and benchmark entrypoints and their wrappers share a per-user Linux
`flock` at `/tmp/lss-harness-$UID/port-25565.lock`. Every current harness uses
port 25565, so this single coarse lock also serializes the overlapping client
scratch/cache, worlds, results, and wrapper staging paths across worktrees.
It is acquired before builds or staging and is never unlinked on exit. An
unrelated listener is checked before mutation and again at server startup;
cleanup never targets a process discovered through its port.

Nested `all`, auto-prime, and multi-phase calls pass a verified inherited lock
file descriptor. Builds and game launches run with `--no-daemon` under a Linux
child-subreaper supervisor. Only harness scripts and supervisors retain the
lock. Gradle/game children and samplers close it. The supervisor waits for the
launcher and its descendants, allows five seconds for a single-use daemon's
natural shutdown tail, then cleans up lingering owned processes (TERM, KILL
after three seconds, and reap). It retains ownership until the descendants
are gone. Controller TERM and even controller SIGKILL trigger owned-process
cleanup; the latter uses Linux parent-death notification. These helpers depend
on Bash, Python 3, util-linux flock, and Linux /proc/prctl.

A benchmark invocation writes `benchmark-results/runs/<unique-id>/manifest.json`
plus separate `populate/` and `measure/` directories. It clears producer exports
before each cycle. Required server/client JSON and their core numeric fields,
actual process completion statuses, and deadlines gate completion. Optional
JFR absence is recorded separately. Failed/incomplete runs retain their logs
and exports but cannot publish current aliases or a fresh reusable base world.
Soak fresh-backfill likewise publishes its base only after the checker passes.

The familiar `benchmark-results/server.json`, `client.json`, recordings, and
populate aliases are published after all required cycles validate. Publication
ends with `current.json`; consumers require that identity to match the completed
run manifest. Store/compression acceptance wrappers archive this verified
manifest as `benchmark-manifest.json`. Reusing a wrapper stamp/arm/rep archives
the previous destination under `.previous/` before starting the new attempt. Historical comparison/profile refs still
run their own checked-in harness, but evidence from a ref without the new
contract is labeled `legacy-unverified`, with `verified=false`. Those historical
results are useful for exploratory A/B comparisons and are excluded from
current acceptance gates. They do not gain certification from wrapper exit 0.

Run `python3 scripts/test_harness.py` for isolated actual-script fixtures.
They copy the scripts to temporary worktrees, redirect only the lock namespace,
and replace external launchers, clocks, port inspection, sampling and product
checkers with controlled fakes. They never start Gradle/Minecraft, bind a socket,
or read the running server's files. CI also syntax-checks every changed shell
entrypoint and runs the existing checker selftests. These fixtures establish
orchestrator behavior; genuine benchmark/soak smoke remains a separate live gate.
