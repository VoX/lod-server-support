# Remaining primary soak queue monitor — 2026-09-08

All four monitored Fabric MC 1.21.1 scenarios passed their actual checker and exited 0, first attempt in this queue. No launches, reruns, edits to fixtures or runtime state, or process intervention were performed by this monitor. Parent owned the serial queue (session 32200; Python PID 788354). These are scenario acceptance results, not exhaustive race or graphics validation.

## Exit receipts and checker verdicts

Paths below are absolute; times are UTC. Each result directory contains the captured scenario JSON, server/client JSONL, server/client logs, CPU JSONL, anomaly report and checker verdict. Zero violations and zero warnings in all four.

| Scenario | UTC start → finish | Windows / client-law windows / quiescent | Result directory |
|---|---|---|---|
| generation-capacity-stress | 2026-09-08T22:17:13.748025+00:00 → 2026-09-08T22:22:21.997170+00:00 | 18 / 17 / 18 | `/home/vox/projects/lss-lines/1.21.1/soak-results/generation-capacity-stress-20260908T221718Z` |
| generation-disabled | 2026-09-08T22:22:21.997432+00:00 → 2026-09-08T22:25:54.573790+00:00 | 35 / 34 / 35 | `/home/vox/projects/lss-lines/1.21.1/soak-results/generation-disabled-20260908T222226Z` |
| dimension-rejoin-warm | 2026-09-08T22:25:54.574044+00:00 → 2026-09-08T22:33:18.398257+00:00 | 62 / 58 / 61 | `/home/vox/projects/lss-lines/1.21.1/soak-results/dimension-rejoin-warm-20260908T222600Z` |
| dirty-while-offline-summary | 2026-09-08T22:33:18.398530+00:00 → 2026-09-08T22:39:27.828998+00:00 | 52 / 48 / 51 | `/home/vox/projects/lss-lines/1.21.1/soak-results/dirty-while-offline-summary-20260908T223324Z` |

Each queue receipt is `E/runtime/<queue-name>.status.json`, with queue names `1211-generation-stress`, `1211-generation-disabled`, `1211-dimension-rejoin`, `1211-dirty-summary`; E is `/home/vox/.local/state/lss-review/20260908-implementation`. Matching `.log` files show clean client/server exits and the checker PASS. Commands are recorded in the receipts and run via `run21.sh env SOAK_PLATFORM=fabric ./scripts/soak.sh <scenario>`.

## Observed behavior and limits

### Generation capacity stress

The cap-1 scenario exercised real contention and converged: generation submitted/completed 185/185, zero timeouts, final active 0; observed active high-water reached 1 during contention. `superseded == miss_dropped == 17261`; client received 733 columns, NOT_GENERATED 0, in-flight 0, ingest failures 0. Disk completed/submitted 981/981, errors 0, pending 0. The high-water is windowed and its last snapshot is 0, so the observed peak is not claimed from the terminal snapshot alone.

The checker requires at least 120 generation completions, at least 100 superseded requests, no leaked permanent NOT_GENERATED response, disc completion and quiescence. This validates ordinary capacity cancellation/retry convergence. It does not force a registration replacement, generation-handle ownership race, or shutdown interruption.

### Generation disabled

All generation counters remained 0. Disk submitted/completed 2080/2080, successful 84, not-found 1996, errors/pending 0. The client still received 363 terrain columns and exactly 1996 NOT_GENERATED responses, then settled at in-flight 0 and ingest failures 0. This clears the checker's missing-data volume floor (1000), positive existing-terrain control, no-generation contract and quiescence. It validates supported disabled-generation behavior without mistaking capacity rejection for permanent absence.

### Dimension rejoin warm

Run 1 traversed overworld → End and saved 2144 overworld cache entries on the portal transition, then 103 End content entries at disconnect. Run 2 joined in the End, loaded 103 cached End entries, and returned to overworld, loading 2144 entries. Exact collected log citations: `client-run1.log:223` (overworld save), `:226` (End save); `client-run2.log:222` (End load), `:225` (overworld load).

Run 2 End settled with 0 terrain-column bodies and 2144 up-to-date responses; the subsequent overworld segment added another 2144 up-to-date responses, ending at 4288 total with still 0 bodies. Both segments settled at in-flight 0 and ingest failures 0. End's 103 cached entries are content entries, not all 2144 resolved positions. This combines actual cache-load logs, per-dimension response deltas and zero body re-download; cumulative counters alone would be insufficient.

This validates real ordinary disconnect/dimension cache continuity. It does not inject the canceled authoritative-CLEAR race, local receive OFF/ON, or a detached third-party callback. Those claims require their separate focused or Tier 3 evidence.

### Dirty while offline summary

The first client disconnected at 18:37:14 local; the server changed the offline target at 18:37:16 and acknowledged save at 18:37:17 before the second join at 18:37:30. Collected `server.log:130`, `:134`, `:137`, `:141` give that ordering. The player-tile edit/save positive control is at `:123` / `:126`.

Use the FINAL first-client baseline, not its earlier pre-save samples: target `36:-4` and untouched control `-4:36` both ended at 1788906985. Run 2 target rose to 1788907050 (+65); control remained 1788906985. Final run 2 summary: clean 13, stale 3, unknown 0, no-region 9; columns validated 1049, stamps applied 1158, stamps ignored 0. It received 1 terrain column and 1158 up-to-date responses; final in-flight and ingest failures both 0. This clears the exact checker floors: validated >=800, clean >=12, no-region >=5, stale+unknown >=2, changed target and unchanged control.

This is live evidence for honest invalidation after a disconnected edit plus reuse of untouched content. It does not independently exercise provisional summary sentinels before content proof, arbitrary asynchronous frame ordering, or GUI controls. No known 1.21.1 residue-variance allowance was invoked; the checker passed outright.

## Artifact completeness and queue ownership

Every nonblank line in the JSONL files parsed successfully. Server files end with `event=end`; each client file ends with `event=disconnect`. CPU row counts are recorded below. All status receipts report exit 0, all verdicts contain `passed: true`.

| Result | Server rows | Client rows | CPU rows |
|---|---:|---|---:|
| generation-capacity-stress-20260908T221718Z | 68 | client-run1.jsonl: 57 | 296 |
| generation-disabled-20260908T222226Z | 48 | client-run1.jsonl: 37 | 198 |
| dimension-rejoin-warm-20260908T222600Z | 101 | client-run1.jsonl: 47, client-run2.jsonl: 37 | 435 |
| dirty-while-offline-summary-20260908T223324Z | 91 | client-run1.jsonl: 44, client-run2.jsonl: 25 | 357 |

Observed owned server/client PID chains: stress 820227/820699; disabled 828245/828674; dimension 833774/834264/840045; final summary soak 844854 with server 845182 and clients 845620/850987. At final audit, all these PIDs and queue Python 788354 were gone. The unrelated regular server 489815 (parent 489750, `fabric-server-launch.jar nogui`) remained running. No process was stopped by this monitor.

The earlier migration and Paper offline wrapper audits are separate: `store-migration-monitor.md` and `paper-offline-monitor.md`. Preserve their stated limitations, especially the migration all-air canary that startup sweep removed before migration; none of these later scenarios closes that live canary gap.

## Evidence hashes

SHA-256 of definitive receipts and checker verdicts (the detailed logs/JSONL remain in the result directories):

```
c629df1b39e9e1ca39e3b62e6587c0028b3ef545db1fc519b142f97be33f8bfa  /home/vox/.local/state/lss-review/20260908-implementation/runtime/1211-generation-stress.status.json
45407ad1bf1045545fd0cf2174dafdcf23de152d21fb6392ebfbaf7e8227db36  /home/vox/projects/lss-lines/1.21.1/soak-results/generation-capacity-stress-20260908T221718Z/verdict.json
8f77c90dc6c6ba97f25c13e6fab9461509932e4c4a223e51651fbc991616e464  /home/vox/.local/state/lss-review/20260908-implementation/runtime/1211-generation-disabled.status.json
7e010785e5a5adf001a242970d3243b28ed8e777f70a2bc4a7b69291387767c7  /home/vox/projects/lss-lines/1.21.1/soak-results/generation-disabled-20260908T222226Z/verdict.json
93f07ff8c0a85d9aa25b5e457f3507a8bd5b695136c8a68998940a9d7f9e02d7  /home/vox/.local/state/lss-review/20260908-implementation/runtime/1211-dimension-rejoin.status.json
31cb826286006336661987b3c0c358a622388c4fd29f43e8f469050bba8f832f  /home/vox/projects/lss-lines/1.21.1/soak-results/dimension-rejoin-warm-20260908T222600Z/verdict.json
51eb7975e5a620dc2ba0813cb73f92ab4d6ace49f05b68cc264129564e23fc86  /home/vox/.local/state/lss-review/20260908-implementation/runtime/1211-dirty-summary.status.json
b76bdb4b1b57fa980937988f961e6e59821299da711e279d5292f5dfee04f441  /home/vox/projects/lss-lines/1.21.1/soak-results/dirty-while-offline-summary-20260908T223324Z/verdict.json
```
