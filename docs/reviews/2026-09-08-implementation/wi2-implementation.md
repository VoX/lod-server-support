# WI-2 implementation: asynchronous server registration ownership

Implemented and committed on all five lines:

| MC line | Commit |
| --- | --- |
| 1.21.1 | a6562743 |
| 1.21.10 | 4c388643 |
| 1.21.11 | 7dd7baea |
| 26.1 | fa76056e |
| 26.2 | 3759894a |

Every player state owns a unique, one-way retired `RequestRegistration`. Disk admission, queued tasks, store/header hits, gate-parked reads and all terminal result flavors retain that registration's sink. Production completion paths never look up the replacement UUID sink. Reader binding and removal compare registration identity. Old work may complete and release its accounting/permit normally but cannot enter a replacement's queue.

Generation ticket requests, callbacks, buffered success/failure outcomes, active-count accounting, processing-thread in-flight/dirty maps, and loaded-probe skip positions carry the same originating identity. Both platform service drains reject retired/replaced tickets before generation submission or failure publication. Old generation outcomes cannot remove fresh pending slots, consume fresh taint, deliver bytes/answers, mark done, or seed the store. Repeated registration of an existing state retains its token; lifecycle replacement receives a new one.

Deferred removals and dedup references are identity-scoped. Departing-primary behavior deliberately remains release-and-redeclare for followers; valid followers retain their own registrations. Shared generation callbacks preserve remaining participants. World-level miss-memo clearing deliberately remains before the owner guard, including departed owners; same-session ghost deliveries and normal generation/fanout semantics remain supported.

## Validation completed before/at commit

Parent coordinated all Gradle invocations; this agent ran none.

- Production/common, Fabric and Paper compilation succeeded on 1.21.1.
- Broad focused Fabric run: 669 tests, 3 failures. All new WI-2 ownership regressions passed. One failure belonged to root WI-6 and was fixed by root; two older generation fixtures required accurate reconnect identity and deterministic outcome-drain ordering.
- Subsequent lifecycle-focused run: **384 tests, zero failures/skips** (parent report), including both corrected fixtures and root's farplayer controls; `lifecycle-first.log` records build success.
- Paper full run: 501 tests; all WI-2 generation/service tests passed. Three unrelated root disguise-fixture failures were corrected separately.
- `git diff --check` clean on all five lines.
- After port, all seven common production processing files are byte-identical across all five lines.

Permanent regressions include real blocked reads across replacement, dirty-taint preservation, terminal success/all-air/notfound/error/timeout isolation, gate-parked sink/accounting release, delayed old removal against a fresh same-position admission, old generation success/all-air/permanent/transient outcomes against fresh pending/taint/store state, abandoned generation-map reclamation, queued generation admission rejection, old ready-list preservation, probe-suppression isolation and shared generation callback retirement.

The two repaired fixtures now model production lifecycle ordering: reconnect creates a new state; miss-memo assertions post B's declaration only after the previous routing and generation-outcome phases have completed. They use completed router-cycle publication, not wall-clock sleeps. The removal fixture directly checks that both old registration maps are reclaimed.

## Port review

Conflict resolution retained only line-specific APIs while applying registration arguments:

- 1.21.10 keeps `Gt.assertTrue(helper, ...)` gametest wrappers.
- 1.21.10/1.21.11/26.1 keep their gametest imports; no 1.21.1 TestPositions seam imports were introduced.
- Modern Paper lines keep `MoonriseRegionFileIO`, flat `Priority`, and `getMinSectionY/getMaxSectionY`.
- 26.1/26.2 keep `ChunkPos.x()/z()` generation accessors; 1.21.x retains fields.
- Existing Paper generation operation-token/async scheduling and loader-specific service/world APIs remain in place.

Port compilation, full unit suites, and engine gametests are being coordinated by root after these commits. No runtime deployment or mod changes were performed by this agent.
