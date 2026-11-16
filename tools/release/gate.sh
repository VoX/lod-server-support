#!/usr/bin/env bash
# The local release gate for ONE line worktree. Every step's log and exit code is kept.
#
# Usage: gate.sh <worktree> [<output-root>]
#   Results go to <output-root>/<line>/ (default ~/.local/state/lss-release-prep-v<version>/gates).
#   The version is the worktree's mod_version. The line and what it ships come from its own
#   config/compatibility/line.json and .github/line.env.
# Java: set LSS_JAVA_HOME_21 / LSS_JAVA_HOME_25 when the JDKs are not in the usual places.
# Run it through gate-locked.sh whenever anything else on the machine may be building.
set -uo pipefail
root="$(cd "${1:?worktree}" && pwd)"; cd "$root" || exit 2
version="$(sed -n 's/^mod_version=//p' gradle.properties | tr -d '\r')"
fact() { python3 -c 'import json,sys; print(json.load(open("config/compatibility/line.json"))["facts"][sys.argv[1]])' "$1"; }
flag() { sed -n "s/^$1=//p" .github/line.env | tr -d '\r'; }
line="$(fact line)"
out="${2:-$HOME/.local/state/lss-release-prep-v$version/gates}/$line"
mkdir -p "$out"; : > "$out/steps.txt"

java_major="$(flag LINE_JAVA_VERSION)"
java_home_var="LSS_JAVA_HOME_$java_major"
if [ -n "${!java_home_var:-}" ]; then export JAVA_HOME="${!java_home_var}"
elif [ -d "/usr/lib/jvm/java-$java_major-openjdk-amd64" ]; then export JAVA_HOME="/usr/lib/jvm/java-$java_major-openjdk-amd64"
else
  # Directories only (a downloaded jdk-25.tar.gz sorts after jdk-25.0.2+10), newest first by
  # version order (25.0.10 after 25.0.9).
  for candidate in $(ls -d "$HOME"/.local/jdk/jdk-"$java_major"*/ 2>/dev/null | sort -rV); do
    if [ -x "${candidate}bin/java" ]; then export JAVA_HOME="${candidate%/}"; break; fi
  done
fi
[ -x "${JAVA_HOME:-}/bin/java" ] || { echo "no Java $java_major found; set $java_home_var" >&2; exit 2; }
export PATH="$JAVA_HOME/bin:$PATH"

step() {  # step <name> <command...>
  local name="$1"; shift
  local start=$SECONDS
  "$@" > "$out/$name.log" 2>&1; local rc=$?
  echo "$name rc=$rc secs=$((SECONDS-start))" | tee -a "$out/steps.txt"
  return $rc
}
git rev-parse HEAD > "$out/head.txt"
git status --porcelain > "$out/status.txt"
step catalog-validate python3 tools/compat/catalog.py validate

tasks=(:common:test :fabric:build :paper:build :paper:test :paper:shadowJar :neoforge:build :neoforge:runGameTestServer :sponge:build vssJars)
[ "$(flag LINE_SHIP_SPONGE)" = "true" ] && tasks+=(:sponge:bootSmoke)
extra=()
if [ "$(fact fabric_client_gametests)" = "True" ]; then
  tasks+=(:fabric:runClientGameTest)
  extra=(--init-script "$root/tools/verify/private-client-tests.init.gradle")
fi
gradle_private_display() {
  env -u WAYLAND_DISPLAY -u XAUTHORITY DISPLAY= XDG_SESSION_TYPE=x11 ALSOFT_DRIVERS=null CI=true \
    bash tools/verify/run-gradle.sh -Pmod_version="$version" "${tasks[@]}" "${extra[@]}"
}
step gradle-clean bash tools/verify/run-gradle.sh clean
step gradle gradle_private_display
# The NeoForge gametest server and the Fabric one must each have run tests: a server that fails
# to boot can exit 0, and Gradle is then green over nothing.
step gametests-ran python3 -c 'import pathlib, sys
sys.path.insert(0, "tools/release")
import release_common as rc
problem = rc.gametests_problem(pathlib.Path(sys.argv[1]).read_text(errors="replace"), 2)
print(problem or "gametest servers ran: %s" % rc.gametest_counts(pathlib.Path(sys.argv[1]).read_text(errors="replace"))[0])
sys.exit(1 if problem else 0)' "$out/gradle.log"
step release-check python3 scripts/release_check.py --version "$version"
step release-check-selftest python3 scripts/release_check.py --selftest
step check-artifacts python3 tools/tests/check_artifacts.py
step compat-ci python3 tools/compat/ci.py
rm -f compatibility-candidate-report.json

# The Python suites take the shared harness lock themselves, in subprocesses that cannot inherit
# our descriptor. Give the lock back first (gate-locked.sh held it for the Gradle steps) and
# retry a suite only when it lost the race for the lock.
if [ -n "${LSS_HARNESS_LOCK_FD:-}" ]; then
  flock -u "$LSS_HARNESS_LOCK_FD" || true
  eval "exec ${LSS_HARNESS_LOCK_FD}>&-" || true
  unset LSS_HARNESS_LOCK_FD
fi
for suite in tools/compat tools/lines tools/release tools/verify tools/tests tools/rig tools/packaging test-fixtures/client-preset-tools; do
  [ -d "$suite" ] || continue
  name="unittest-${suite//\//_}"
  for attempt in 1 2 3 4 5 6 7 8 9 10; do
    step "$name" python3 -m unittest discover -s "$suite" && break
    if grep -q "owns the shared resources\|Port 25565 is occupied" "$out/$name.log"; then
      cp "$out/$name.log" "$out/$name.lock-busy-$attempt.log"; sleep 60; continue
    fi
    break
  done
done

# JUnit totals per module, and the jars this gate built (the publish tools compare against them).
python3 - "$root" "$out" <<'PY'
import hashlib, json, pathlib, shutil, sys, xml.etree.ElementTree as ET
root, out = map(pathlib.Path, sys.argv[1:3])
totals = {}
for module in ("common", "fabric", "paper", "neoforge", "sponge"):
    tests = failures = errors = skipped = 0
    for report in (root / module / "build/test-results").rglob("*.xml"):
        try:
            tree = ET.parse(report).getroot()
        except Exception:
            continue
        for suite in ([tree] if tree.tag == "testsuite" else tree.iter("testsuite")):
            tests += int(suite.get("tests", 0)); failures += int(suite.get("failures", 0))
            errors += int(suite.get("errors", 0)); skipped += int(suite.get("skipped", 0))
    totals[module] = dict(tests=tests, failures=failures, errors=errors, skipped=skipped)
jars = {}
(out / "jars").mkdir(exist_ok=True)
for module in ("fabric", "paper", "neoforge", "sponge"):
    for jar in sorted((root / module / "build/libs").glob("*.jar")):
        if any(marker in jar.name for marker in ("-sources", "-dev", "-slim")):
            continue
        jars[jar.name] = hashlib.sha256(jar.read_bytes()).hexdigest()
        shutil.copy2(jar, out / "jars" / jar.name)
sys.path.insert(0, str(root / "tools/release"))
import release_common
gametests = release_common.gametest_counts((out / "gradle.log").read_text(errors="replace"))[0]
(out / "summary.json").write_text(json.dumps({"junit": totals, "gametests_passed": gametests, "jars": jars}, indent=2))
print(json.dumps({"junit": totals, "gametests_passed": gametests, "jar_count": len(jars)}))
PY
# A retried step counts by its last result.
failed="$(awk '{split($2,r,"="); last[$1]=r[2]} END{n=0; for (k in last) if (last[k]!="0") n++; print n}' "$out/steps.txt")"
echo "GATE-DONE $line failed_steps=$failed" | tee -a "$out/steps.txt"
[ "$failed" = "0" ]
