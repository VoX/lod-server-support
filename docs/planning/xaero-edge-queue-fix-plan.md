# Xaero edge shading and queue accounting fix plan

Date: 2026-09-06. Scope: 26.2, 26.1, 1.21.11, 1.21.10, 1.21.1; Fabric and NeoForge clients. Follow-up to the three reproduced findings in the 2026-09-06 Xaero review. The user's transient map lines are not yet a proven reproduction of the slope defect.

## 1. Edge shading

Mirror the native writer's dependent pixels when a full column is replaced: south neighbor row z=0 (x=0..15), east neighbor column x=0 (z=1..15), southeast neighbor pixel (0,0). Mark those MapBlocks slopeUnknown=true and their existing tile chunks changed=true; coalesce their redraws through pendingUpdates. New incoming blocks already start slopeUnknown=true. Conservatively invalidate on every committed tile rather than introducing a costly or approximate effective-height comparison; at most 32 neighbor pixels and four distinct texture groups per commit.

Only touch loaded, existing neighbor tiles in the SAME Xaero region, matching MapWriter's native region-local behavior. Never create/load another region or take a second region's monitor for slope polish. Missing/unloaded neighbor tiles are skipped and calculate slopes from their own subsequent writes. Add exact reflective handles for MapTile.getBlock(int,int), MapTile.isLoaded(), MapBlock.setSlopeUnknown(boolean); verify signatures on locally installed Xaero jars. Preserve the existing writer-pause, load-state, PBO and resting gates. Never use setToUpdateBuffers (cache-save race).

Before pixel mutation, collect eligible neighbors and count DISTINCT pending-update keys not already present (own group plus neighbor groups). If adding them would exceed the hard cap, defer the column without writing pixels, consuming retries, or dropping work; undo a newly created empty tile chunk. This avoids multiplying the 1024-entry bound. Neighbor changes in the own group coalesce with its redraw. Rebuild, session-identity, dimension and save/cache gates remain identical.

Tests: south/east/diagonal pixel invalidation; unrelated pixels remain known; same-group coalescing; adjacent-group redraw; negative coordinates; region boundary does not create or mutate foreign regions; missing/unloaded neighbors; capacity admission with multiple dependent groups; existing save-race gates. Model the stub slope-known flag faithfully and assert the specific dependency pixels, not just call counts.

## 2. Queue byte bound on replacement

Keep the existing Entry identity for same-dimension replacement so compare-and-remove retains its latest-wins protection. After updating its tile/size, enforce the byte bound by evicting oldest OTHER entries until within budget. Preserve queue ordering and the replacement; insertions keep their current oldest-first semantics. Drain eviction reports outside queueLock through the existing governed/owed classification. Remove the replacement-path early return that would suppress reporting newly evicted entries. Shrinking replacements update occupancy without evicting. A single tile beyond the configured byte budget must be refused and reported under the same policy (normally impossible with production tile shapes, but makes the bound total).

Tests: the reproduced 28,800 -> 43,136 byte case under a 30,000 limit; shrinking; repeated replacement; latest content retained; cap boundary; evicted positions reported/owed outside lock; oversized single tile; existing in-flight replacement and dimension-change tests.

## 3. Owed gauge

Increment owedGauge only when the corresponding positions/busyTiles set actually inserts a new member. Keep the two categories' existing semantics (forget removes both). Tests duplicate insertion, payoff/release returning gauge to zero, and both debt categories.

## 4. Rollout and gates

Create a dedicated fix branch in each current lss-lines worktree, based on its current reviewed issue-fix head. Implement on 26.2 and port literally, preserving each line's world-height expression and all loader/toolchain/release identity facts. Do not merge support code into main. No release tags or publishing.

Before implementation, one subagent reviews this plan against source and the installed Xaero bytecode. Fold material findings and record the verdict here.

Run the focused Xaero suites on the implementation, then Fabric Tier 1 and NeoForge contract tests/builds plus Fabric production jar builds on each line, sequentially with limited Gradle workers and each line's JDK (25 for 26.x, 21 for 1.21.x). Check loader artifact contents and preserve the stock nested sqlite/zstd shape. Run relevant artifact gates with the line's existing full jar set. No protocol or server implementation changes, so do not run unrelated live soaks or change their baselines. Record exact results, not inferred greens. Review the final diff and cross-line parity. Commit each line's changes with plan and regression tests.

After all builds pass, update exactly the nine current lss-test Prism rigs (five Fabric, four NeoForge; historical and personal instances excluded), one dev-named LSS jar each, no disabled LSS duplicates. Verify each installed SHA-256 against its built source. Do not hot-swap a running Minecraft client's jar: if still running, request a normal client exit while preparing deployment independently. Keep recovery copies outside instance mods folders.

Stop the regular MC 1.21.1 Fabric server gracefully, install its matching new jar, and restart the existing world through test-server.sh run-fabric. Verify startup/listening and LSS initialization. Do not launch a dummy. The user has authorized this restart; no second approval needed.

## 5. Review and execution record

Plan reviewed by subagent `review_xaero_plan`: APPROVE with amendments, all adopted before implementation:
- Capacity and busy-loaded-neighbor refusal use cap-exempt DEFERRED, never commitPixels=false/SKIPPED_SETTINGS or expiring DEFERRED_TILE; no pixel mutation and empty-group rollback.
- Existing loaded dependencies with busy load state/PBO defer the source until ready; missing/unloaded tiles are skipped. Test eventual progress without another offer.
- Oversized replacement removes only its own older queued entry (stale-dimension removal reported separately), never unrelated entries; refuses/reports the new bytes, preserves any owed debt, and calls forgetOwed only on acceptance. Tests cover new and replacement refusal.
- Test a position in both debt categories: gauge two, payoff zero.

Signature preflight: all three added members verified by javap on seven installed World Map 1.45.0 jars (five Fabric, NeoForge 26.2 and 1.21.1). Other two NeoForge rigs have no World Map jar installed. Additional compatibility check: downloaded the official Modrinth 1.42.0 Fabric and NeoForge MC 1.21.1 artifacts and verified the same three public signatures with javap. Artifact metadata retained under /tmp/lss-xaero-floor-check.

Implementation-review fold: the same subagent found and we fixed one ordering regression before ports: check per-tile loadNew/update eligibility before neighbor/capacity preflight, so a refused existing update cannot block allowed new sibling tiles behind a busy neighbor. Added its regression test. First targeted run: all existing and first 14 new tests passed; final mixed-settings regression added afterward and covered by final gates.

Final implementation review: ship, no remaining production findings. Its mixed-settings test initially selected a sibling inside the busy 4-by-4 group; corrected to (72,66), a different group in the same region. Final Xaero suites on 26.2: 143 bridge + 20 extractor + 5 wiring tests, zero failures (15 new regressions total).


## 6. Completed validation and deployment

All five lines passed `:fabric:test :fabric:assemble :fabric:vssJar :neoforge:build :paper:shadowJar :paper:vssJar` with `--max-workers=1`, followed by `scripts/release_check.py`. No failures or errors; skipped tests are the existing suite skips.

| MC line | Fabric tests (skipped) | NeoForge tests | Artifact check |
| --- | ---: | ---: | --- |
| 26.2 | 2341 (4) | 23 | PASS |
| 26.1 | 2331 (4) | 23 | PASS |
| 1.21.11 | 2325 (4) | 23 | PASS |
| 1.21.10 | 2321 (5) | 19 | PASS |
| 1.21.1 | 2325 (4) | 23 | PASS |

Each line includes the 143 bridge, 20 extractor and 5 wiring tests. Cross-line normalized production-source parity and `git diff --check` passed. No gametests or soaks were run for these client-only changes. Paper jars were rebuilt for artifact validation; Paper source is unchanged.

Deployment completed 2026-09-06: all nine dedicated current Prism instances have exactly one matching `0.14.0-dev-xaero-edge+mc<line>` LSS jar, verified by SHA-256 against its built source. No Minecraft client was running during replacement. Backups, installed-file manifest and gate results are in `/home/vox/.local/state/lss-deploy/xaero-edge-20260906/`.

The regular MC 1.21.1 Fabric server in `/home/vox/projects/lss-port-1.21.1/test-server/fabric` shut down gracefully with world saves completed, then restarted through `./test-server.sh run-fabric`. Startup reached `Done` and LSS request-processing initialization at 20:55:36 local time. Its installed jar SHA matches the fixed Fabric build. Existing world retained; no dummy client launched. Endpoint: `[::1]:25564`. Restart metadata and log location are in the deployment directory's `server/restart.json`.

The reproduced code defects are covered by regressions; an in-game visual retest of the user's transient map lines remains outstanding.
