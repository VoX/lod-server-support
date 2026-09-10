#!/usr/bin/env bash
# Serialize verification with disposable rigs and supervise only our descendants.
set -euo pipefail
verify_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$verify_root"
source scripts/lib/harness-lock.sh
harness_acquire
harness_gradle "$@"
