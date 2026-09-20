# MC 1.21.1 freshness / region-summary review

Review target: `/home/vox/projects/lss-lines/1.21.1`, merged `1b544494`. Read-only production review; no repository edits, Gradle invocations, servers, or child agents. External regression source supplied to parent for coordinated execution. This is the independent freshness lens requested after the storage lens stopped; SQLite internals are excluded here.

## Finding FRESH-1 — P2: a newer doubt summary does not retract an older summary's clean proof

**Primary location:** `xplat/src/main/java/dev/vox/lss/networking/client/LodRequestManager.java:886` (also the `STAMP_NO_REGION` branch at line 872; both continue before the revocation path at 890).

**Defect:** an earlier numeric summary can set a cached column's `validated` and `summaryValidated` bits. A subsequent summary reporting `STAMP_NEVER_CLEAN` only increments a diagnostic and skips the tile. The older proof remains and `classify()` continues returning `SATISFIED`. The equivalent numeric-newer frame correctly revokes precisely those bits through `ColumnStateMap.applyTileValidation`. Therefore the documented stale-then-fresh recovery guarantee fails when the newer information is a sentinel, which is the server's normal output during a pending-write latch and its first-clear grace.

**Real trigger:** a rapid A → B → A dimension trip overlaps a retained or slow old A summary. The ready queue deliberately survives dimension changes and can retain a frame for 10 seconds (`RegionSummaryService:95`, `removePlayer` is network-disconnect only). While the player is in B, another player edits/saves A and its dirty broadcast drains; `DirtyColumnBroadcaster` sends only to players currently in that dimension, so this client receives no A dirty mark. Back in A, after its cache load, the old A frame arrives and validates its cached pre-edit column. The current A summary arrives later during the region's pending-write latch or five-second first-clear grace (`RegionStampTable:364-367`) and reports NEVER_CLEAN. It fails to revoke the old frame's proof. The client never re-asks that column until another dirty event, a state-resetting move, or a later dimension/session entry. This does not require fresh-before-stale reordering; the existing FIFO guarantee is fully respected.

The exact frame-state defect is deterministically testable. The portal/RETRY chronology is a source-derived reachable schedule, not a live reproduction; its timing needs an integration pin as described below. A fresh NO_REGION frame has the same state-machine defect, although NEVER_CLEAN is the ordinary marked-edit trigger.

**Evidence:** external `probes/dev/vox/lss/networking/client/ReviewSummarySentinelTest.java` seeds one cached positive stamp (7000), applies an older numeric summary (1000), proves SATISFIED, then applies each sentinel and expects the original 7000 claim to become requestable. Current branches retain SATISFIED. Parent executed both cases: **2 RED**, line 39, expected 7000 but actual `ColumnStateMap.SATISFIED` (`Long.MIN_VALUE`). Evidence saved by parent as `summary-sentinel-probe.*` (log/XML). This confirms the incorrect state transition; the portal chronology remains source-derived.

**Existing pins checked:** `LodRequestManagerSummaryTest.neverCleanTilesCountUnknownAndValidateNothing` and `noRegionTilesAreNoEvidenceAndValidateNothing` begin with an unvalidated cache; they do not cover retraction. `aRevokedPositionReopensItsRingAndRedeclares` and `ColumnStateMapTest`'s newer-summary cases use real numeric stamps. `RegionSummaryServiceTest.retainedFramesSendInAssemblyOrderPerPlayer` explicitly protects stale-before-fresh order. The proposed fix preserves all those intended contracts. This is distinct from the accepted single-deleted-chunk granularity limitation and from accepted clock rewind.

**Proposed fix:** add a tile operation that removes only `summaryValidated`-owned proofs on a newer doubt sentinel, recomputes leaf `needs`, and reports every revoked position through the existing scanner reopen callback. Keep all per-column `onReceived`/`onUpToDate` proofs, timestamps, dirty/retry/session-satisfied flags, and current sentinel diagnostics unchanged. Do not validate positions against zero or clear every proof indiscriminately.

**Tests:** retain the two external frame-level regressions; add controls proving that a sentinel neither creates claims/leaves nor revokes per-column proofs. Exercise a fully confirmed legacy-scanner ring so retraction also proves redeclaration, plus the default hybrid scanner's needs-mask path. Add a composed A/B/A service-manager case with the old A ready frame held RETRY, edit/drain during B, and a fresh A NEVER_CLEAN frame after admission; assert both ordered delivery and an actual new column declaration. No timing sleeps are needed if service's clock/source seams are used.

## Coverage and checks

Read the full region stamp oracle, summary admission/assembly/retention service, timestamp-cache representation/persistence, and dirty tracker. Traced:

- live mark → pending invalidation count → applied callback, including repeated batches;
- save hook / load seeding / generation exception and dirty broadcast dimension/range selection;
- chunk-versus-tile header layout, unknown/absent/degenerate states, header cap accounting, directory/stat horizons, latch/grace, and monotonic bounds;
- summary admission window/cooldown, retained FIFO, expiry, disconnect cleanup, and client buffering around cache adoption;
- `ColumnStateMap` classify/needs equivalence, proof provenance, dirty/stale crossing, retry and clear failure flavors, pruning, load adoption, and stamp ratchet;
- hybrid scanner near rings, residue rectangles, bounded emit scratch, budget truncation, audit, and cadence pricing; legacy scanner confirmation, reopen, movement retention and shrink/view transitions;
- relevant `RegionStampTableTest`, `RegionSummaryServiceTest`, `LodRequestManagerSummaryTest`, `ColumnStateMapTest`, `DirtyContentFilterTest`, and scanner test cases. Tests were read; parent owns execution.

No additional concrete unaccepted finding was established in this lens. This is code review, not a proof of completeness, and no live portal/network stress run was performed.

## Deliberate tradeoffs / dismissed candidates

1. **Partial chunk deletion inside a surviving region:** tile max cannot detect it when siblings remain; explicitly accepted in `docs/planning/region-summary-sync-plan.md:374-378`. Per-chunk lookup correctly reports NEVER_CLEAN for location zero. Do not report it as a new regression or poison every sparse tile.
2. **Clock rewind / backup-restore currency:** explicitly accepted shared limitation, including the <=1-hour future-stamp latch gap and deferred heal (`region-summary-sync-plan.md:117-125`, `:345-351`). The test asserting a monotonic max alone does not prove currency, but the residual is already documented; no new bug claim here.
3. **Header publication's two-volatile-read straddle:** documented five-second-horizon residual; live marked changes consult separate atomic evidence. Not escalated absent a trigger outside the accepted envelope.
4. **No per-column proof revocation by a coarse real tile stamp:** deliberate provenance rule. Revoking those proofs wholesale would undo warm-rejoin confirmations; FRESH-1's fix must remain summary-provenance-only.
5. **Summary frame loss / malformed frame / sender DROP:** deliberately falls back to ordinary per-column validation. RETRY retention and per-player FIFO already handle transient unwritability and order.
6. **Region header array cap versus permanent small region records, and holding one entry monitor during header IO:** explicit memory/concurrency tradeoffs in source and the stamped-up-to-date final-panel record.
7. **Timestamp cache pre-epoch clamping, additive load merge, complete-prefix recovery on truncated files, tile-granular eviction, and nonpersisted miss memo:** deliberate test-pinned behavior. No alternate clamp or whole-file discard proposed.
8. **Hybrid region audit/no-op reopen and observed-frontier confirmedRing:** deliberate stateless scan semantics; every state mutation examined recomputes needs or changes only a non-classify input. Near/far residue shares a single budget and has explicit boundary differential pins.
9. **Unbounded trusted-server summary apply cost:** already accepted in `stamped-up-to-date-plan.md:419-423`; not introduced as a security finding.

## Unproved hypotheses kept separate

- The five-second latch-clear grace is a time heuristic, not per-column acknowledgement of disk completion. A sibling write delayed longer can outlive it, but the mechanism and grace were deliberately reviewed and I did not establish a new supported-platform execution outside the accepted assumptions here.
- Dirty load-seeding can overlap unusual chunk-system activity. Existing newly-generated exclusions and load/first-save pins were inspected; no concrete missed dirty update beyond known earlier startup/clock limitations was established in this lens.
