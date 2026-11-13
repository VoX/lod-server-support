#!/usr/bin/env bash
# Push a release's tags one at a time, oldest line first and the Latest line last. Each push is
# watched to the end of its release run and verified before the next tag goes out. Stops on the
# first problem.
#
# Usage: tag_chain.sh <version> [first-line]
#   first-line resumes after a stop (for example "26.2"); verified lines are not pushed again.
#   LSS_RELEASE_LINES=1.21.1,26.3 restricts the set (a beta ships two lines).
# Tags must already exist locally: annotated, --cleanup=verbatim, on the merge commits.
# A red release run is never re-run: recover from its attached artifacts (recover_modrinth.py).
set -u
version=${1:?version}; first=${2:-}
here="$(cd "$(dirname "$0")" && pwd)"; repo="$(cd "$here/../.." && pwd)"
state="${LSS_RELEASE_STATE:-$HOME/.local/state/lss-publish-v$version}"; logs="$state/logs"; mkdir -p "$logs"
log() { echo "$*" >> "$logs/chain.log"; }
slug="${LSS_REPO_SLUG:-VoX/lod-server-support}"
lines_arg=(); [ -n "${LSS_RELEASE_LINES:-}" ] && lines_arg=(--lines "$LSS_RELEASE_LINES")
mapfile -t rows < <(python3 "$here/release_common.py" lines --version "$version" --repo "$repo" "${lines_arg[@]}")
[ "${#rows[@]}" -gt 0 ] || { log "no local tags for $version"; exit 1; }
started=0; [ -z "$first" ] && started=1
for row in "${rows[@]}"; do
  set -- $row; line=$1; tag=$2
  [ "$line" = "$first" ] && started=1
  [ $started = 1 ] || continue
  log "=== $line $tag $(date -u +%T)"
  git -C "$repo" push origin "refs/tags/$tag" >> "$logs/chain.log" 2>&1 || { log "PUSH FAILED $tag"; exit 1; }
  id=""
  for _ in $(seq 1 40); do
    id=$(gh run list -R "$slug" --workflow release.yml --limit 10 --json headBranch,databaseId \
          -q ".[] | select(.headBranch==\"$tag\") | .databaseId" | head -1)
    [ -n "$id" ] && break; sleep 5
  done
  [ -z "$id" ] && { log "NO RUN for $tag"; exit 1; }
  log "run $id"
  timeout 2700 gh run watch -R "$slug" "$id" --exit-status --interval 30 > /dev/null 2>&1; rc=$?
  log "watch rc=$rc $(gh run view -R "$slug" "$id" --json conclusion -q .conclusion)"
  [ $rc -ne 0 ] && { log "RUN FAILED $tag (do not re-run; recover from the attached artifacts)"; exit 2; }
  sleep 20
  ok=0
  for _ in 1 2 3 4 5; do
    if timeout 900 python3 "$here/verify_lss.py" --version "$version" --repo "$repo" --state "$state" "$line" \
         > "$logs/verify-$line.json" 2>&1; then ok=1; break; fi
    sleep 60
  done
  [ $ok -ne 1 ] && { log "VERIFY FAILED $line: $(grep -A5 problems "$logs/verify-$line.json" | tr '\n' ' ')"; exit 3; }
  log "VERIFIED $line"
done
[ $started = 1 ] || { log "unknown first line $first"; exit 1; }
log CHAIN-DONE
