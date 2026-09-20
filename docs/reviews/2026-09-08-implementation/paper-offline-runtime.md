# Full Paper offline-edit wrapper: independent read-only monitor

Receipt `1211-paper-offline-edit.status.json`: exit 0, 2026-09-08 22:04:46.145281 → 22:17:13.747645 UTC (12m27.602s, including the missing-base prerequisite). Command: run21.sh → SOAK_PLATFORM=paper ./scripts/store_offline_edit.sh. The original wrapper remained the owner throughout; no phase was manually launched or skipped.

| Phase | Result directory under 1.21.1/soak-results | Checker windows / client-law windows / quiescent | Violations / warnings |
| --- | --- | --- | --- |
| Automatic base-world prerequisite | fresh-backfill-paper-20260908T220457Z | 41 / 40 / 41 | 0 / 0 |
| Populate | store-offline-populate-paper-20260908T220947Z | 30 / 29 / 30 | 0 / 0 |
| Mutate | store-offline-mutate-paper-20260908T221253Z | 9 / 8 / 9 | 0 / 0 |
| Verify | store-offline-verify-paper-20260908T221417Z | 30 / 29 / 30 | 0 / 0 |

The prerequisite ran because base-paper/world was absent. It served 2,161 columns, completed 2,069 generations with no timeout, acknowledged save-all flush, then saved its world for the actual chain. Store-off during that prerequisite was intentional. Its pass was not counted as the populate pass.

Populate final: 2,144 deposits, 0 store errors/drops; both baseline probes served. Edited20:0 and control-20:0 both hashed -8582001278133590983.

Mutation proof: every client snapshot reports server_enabled=false; final service requests/columns, disk submissions, store hits/deposits are all zero. Collected mutation server.log:90 acknowledges forceload-add step; :92–93 executes/acknowledges setblock328,-60,8→glowstone; :94–96 executes/acknowledges save-all. Exported command rows have ok=true, including forceload removal. This is actual saved editing while invisible to LSS, not an in-service dirty-event test.

Verification proof: collected verify server.log:81 startup sweep drops297 stale and0 vanished-region rows before client join:90/registration:94. Final store hits1,847, misses/deposits297, successful disk reads297, errors0, queue0. Client receives2,144 columns with0 ingest failures and0 in-flight requests. Wrapper comparison passes exactly:

- Edited20:0: -8582001278133590983 → -7005409826721539322 (fresh content).
- Control-20:0: -8582001278133590983 → -8582001278133590983 (byte-stable round trip).

All four phases exported valid complete JSONL: prerequisite server63/client52/CPU268 rows; populate42/31/165; mutate22/10/61; verify42/31/165. Every server export ends with end and every client export with disconnect. Each verdict.json agrees with the wrapper's phase checker PASS. After the Paper server exited and WAL was absent/zero, immutable read-only SQLite inspection returned quick_check=ok, schema4/wire20, and lods_1 containing2,144 rows all wirefmt20. No DB writes were performed.

Ownership: queue788354 → xvfb799454 → wrapper799467 → populate owner799477/soak799478. Nested auto-prime was owner799488/soak799489 with server800053/client800441; it exited before populate resumed with server807236/client807597. Mutation used soak811604/server812233; verify used soak814417/server814990/client815415. All tracked Paper-wrapper/phase/client/server owner PIDs exited after the final receipt; regular server489815 remained running. Parent queue advanced automatically to its next assigned soak.

Scope: this establishes normal Paper restart invalidation after an offline region edit, retention of useful unedited store rows, and end-to-end edited/control byte behavior. It does not replace the exact WI1 interrupted-invalidation tests, and it does not inject corrupt migration rows for WI7. No source/runtime mutation, build, process launch, or process stop was performed by this monitor; only this report was written.
