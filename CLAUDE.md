# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

LOD Server Support (LSS) — distributes LOD chunk data from servers to clients over a custom networking protocol. Supports Fabric (client + server) and Paper (server only). The Folia code paths (regionized probing, lifecycle mailbox) exist in the one plugin jar, but **`folia-supported` is deliberately ABSENT on the 26.2 line** (since v0.7.0): no Folia build exists for MC 26.2, so declaring it would auto-load release jars on a future Folia with live-unvalidated paths. Re-add the flag together with the `SOAK_PLATFORM=folia` validation once Folia ships 26.2 (`PluginYmlContractTest` + `release_check.py` pin the absence; the older support lines — 1.21.8/1.21.11/26.1.x — still declare it and remain Folia-experimental: single-player soak validated, concurrent multi-region ingress untested). Clients request distant chunks individually; the server reads them from disk or memory and streams serialized sections back, enabling LOD rendering mods to display terrain beyond vanilla render distance.

## Project Structure

Multi-project Gradle build with three subprojects:

```
lod-server-support/
├── common/   Pure Java utilities (no MC deps) — shared by fabric/ and paper/
├── fabric/   Fabric mod (client + server), Minecraft 26.2
└── paper/    Paper/Folia plugin (server only), Minecraft 26.2
```

## Build Commands

```bash
./gradlew :fabric:build -x runClientGameTest  # Build Fabric mod + Tier 1 & 2 tests
./gradlew :paper:shadowJar                    # Build Paper plugin JAR
./gradlew clean                               # Clean build artifacts
```

Output JARs:
- `fabric/build/libs/lod-server-support-fabric.jar` — Fabric mod (client + server)
- `paper/build/libs/lod-server-support-paper.jar` — Paper plugin (server only, shadow JAR; serves Paper and Purpur — `folia-supported` is absent on the 26.2 line, so Folia refuses this jar until the flag returns with the Folia 26.2 validation)
- `fabric/build/libs/voxy-server-side-fabric*.jar` / `paper/build/libs/voxy-server-side-paper*.jar` — the **Voxy Server Side** branded pair (`vssJar` tasks: byte-copies of the LSS jars with only fabric.mod.json branding / plugin.yml description rewritten; mod id `lss` and plugin name `LodServerSupport` preserved — the LSS/VSS interchangeability contract, pinned by `release_check.py`'s pair checks). Published to Modrinth project `84zcagOb` (voxy-server-side) by releases since v0.7.0.

CI builds (env `CI=true`) name the jars `lod-server-support-<platform>-<mod_version>+<minecraft_version>.jar` (e.g. `lod-server-support-fabric-0.6.0+26.2.jar`); the release workflow feeds `mod_version` from the tag. Local dev builds keep the stable unversioned names.

## Test Commands

```bash
./gradlew :fabric:test -x runGameTest -x runClientGameTest  # Tier 1: JUnit unit tests only (fast, ~7s)
./gradlew :fabric:runGameTest                                # Tier 2: server gametests (starts dedicated server, ~13s)
# NOTE: `:fabric:test` alone runs Tier 1 ONLY — runGameTest hangs off check/build, not test.
# For Tier 1 + 2 run both commands above, or `:fabric:build -x runClientGameTest`.
./gradlew :fabric:runClientGameTest                          # Tier 3: client gametests (starts integrated server + client)
./gradlew :fabric:build -x runClientGameTest                 # Full build + Tier 1 + 2 tests
./gradlew :paper:test                                       # Paper JUnit tests (wire parity, NBT serialization, config)
```

Most tests live in the Fabric module. Paper has Tier 1 JUnit tests as well (`./gradlew :paper:test`, ~259 tests; also run by CI) — Fabric/Paper wire parity, payload edge frames, NBT section serialization (incl. a golden cross-module byte corpus + live-vs-NBT parity), the shared handshake gate, config validation + malformed-file tolerance, world-handler event reflection, the request-service / broadcaster / generation-service / disk-reader twins driven through injection seams, plugin enable-plan/dispatch/handshake glue + plugin.yml contract (incl. `folia-supported` ABSENCE on the 26.2 line), the Folia seams (lifecycle mailbox incl. foreign-thread visibility, the `FoliaSupport` platform probe, the soak driver's save-all mapping, the generation completion-thread extraction ladder, the experimental regionized loaded-chunk probe scheduling — `RegionProbeSchedulingTest`: EntityScheduler hand-off, region-ownership gating, one-tick batch hold-release alignment + the `republishHeldBatch` CAS pin — a held batch must lose to a newer arrival and be counted superseded, never resurrected — dimension-change discard, departed-player sweep), `FoliaWiringContractTest` (constant-pool scan of every production class, nested classes included: no legacy-scheduler references, lifecycle routed through the mailbox), exporter schema parity, and command output. Paper runtime behavior is validated live by the soak harness on both Bukkit platforms (`SOAK_PLATFORM=paper` / `SOAK_PLATFORM=folia ./scripts/soak.sh all` — fresh-backfill, warm-rejoin, dimension-trip, paper-dirty-falling-block against a real Paper / Folia server; the Folia runs are pending upstream — no Folia build is published for MC 26.2 yet, so `runFolia` has nothing to download on this line, and the 26.2 plugin.yml omits `folia-supported`, so restoring that flag is step one of the future Folia validation).

### Test Architecture

- **Tier 1** (`fabric/src/test/java/dev/vox/lss/`, ~689 tests): JUnit 5 tests via `fabric-loader-junit`. Tests position packing, bandwidth limiter, config validation + GSON whole-file-fallback semantics, column cache store (pre-v4 discard / path-sanitization / oversize / corruption guards), column state map (incl. answer-time mark consumption), the awaiting-answer tracker, the per-player mailbox/backlog replace path (`BacklogReplaceTest`: latest-wins overwrite, superseded accounting, the published want-set surviving a take, the empty clear batch), column timestamp cache (per-dimension isolation / eviction age / save atomicity / the miss memo: TTL boundary, put()-clears, invalidate-vs-invalidateStamps split, never-persisted, kill-switch ttl 0, wrap-safe deadline compare), dirty tracking and content filtering (fail-open + zero-hash sentinel), slot admission and the off-thread processor mailbox (incl. same-UUID re-registration survival), payload codecs + protocol-constant envelope, Fabric/Paper wire parity, NBT section serialization + a golden cross-module byte corpus + section-light defaults, negative/oversized payload edge cases, the LodRequestManager request-loop contract (untracked-response gates, dimension guard, ingest-failure unstamping, tick decomposition, RTT distribution, the want-set pins: convergence sends nothing at all, entering backpressure halt sends exactly one empty clear batch, awaited positions are re-declared and block ring confirmation, and the retired byte 0 stays as inert as an unknown type), the extracted client `ClientSessionGate` (handshake/session-config/disconnect ladder), `ClientColumnProcessor` decode drain (silent-clear paths report through ingest-failure), the reflective Voxy bridge (full report/latch/rethrow ladder, stubbed), spiral-scanner budget scaling and retry reset, IncomingRequestRouter conservation + clientTimestamp routing + the honest re-resolution ladder + clock-skew, cross-player dedup fan-out, the disk-reader submit envelope + late-delivery drop + bounded shutdown + the `hasHeadroom` gate agreeing with a real pool rejection, OffThreadProcessor disk-result delivery and lifecycle + the invalidation-overtake stale guard (the disk twin, the per-player generation twin swept on player removal, the error-retry replay that must not un-taint a consumed outcome, and the multi-holder taint invariant), the miss-memo processor pins (authoritative-only seeding — error-triage, ghost, edit-overtaken, and gen-disabled deliveries never seed; every generation-outcome flavor clears incl. delivered-data and departed-player; memo-hit escalation skips the re-read; the pacing rules on BOTH admission paths — nearer-read hold incl. the delivery-path/movement case, cohort span, straggler-tightens-never-blocks), send-action batching, the shared HandshakeGate ladder, client column decode + LSSApi dispatch, the data-capture exporter schema contracts (both platforms), DiagnosticsFormatter golden lines, the column cache pre-v4 discard-not-migrate gate, the VoxelColumn receiver glue (held-before ordering + authoritative 0-section clears), the gametest-entrypoint listing contract, and malformed-config-file tolerance.
- **Tier 2** (`fabric/src/gametest/java/dev/vox/lss/test/`): 56 Fabric server gametests across 7 classes (seeded through the shared `GameTestSeeding` helper — the mailbox is latest-wins, so multi-position seeds must be ONE batch, not back-to-back declarations) (all listed in the `fabric-gametest` entrypoint of `fabric/src/gametest/resources/fabric.mod.json` — a new class MUST be added there or it compiles but never runs; `GameTestEntrypointContractTest` in Tier 1 enforces the listing) — `LSSGameTests` (service activation, diagnostics, command registration, config loading), `ServiceLifecycleGameTests` (player registration/cleanup, in-memory probe without dirty-filter seeding, batch-request distance guards incl. extreme-coord overflow safety, idempotent shutdown), `SerializerParityGameTests` (disk-read vs live serializer byte parity, DirtyContentFilter re-save suppression, End-void all-air sentinel), `GenerationLifecycleGameTests` (generation caps/piggybacking/timeouts, dimension-change re-registration), `CommandGameTests` (`/lsslod` permission gating + stats/diag dispatch rendering + exporter/formatter agreement), `TwoPlayerGameTests` (two-player dedup convergence, bandwidth fairness + idle dilution, vanilla-player-without-handshake invisibility, edited-column broadcast fan-out to two holders), `RegionFaultGameTests` (corrupt-region containment: garbage-zlib resolves not-found, the read pool survives). The in-game runner additionally counts vanilla's built-in `minecraft:always_pass` test instance.
- **Tier 3** (`fabric/src/gametest/java/dev/vox/lss/test/LSSClientGameTests.java`): End-to-end client-server flow test. Validates handshake, session config, spiral scanning, chunk section receipt, decoded section content asserted at a registered `LSSApi` consumer (flat-world block layers, off-render-thread dispatch), and the ingest-failure recovery loop (a consumer rejecting a column — by `LSSApi.reportIngestFailure` or by throwing — gets it re-served end-to-end). Fabric Loom handles headless rendering automatically.

The system property `-Dlss.test.integratedServer=true` (set in fabric/build.gradle for gametest JVMs) allows `RequestProcessingService` to activate on integrated servers during testing.

### Known Test Flakes & Environmental Failures

**This section is the canonical home for known CI test flakes and environment-specific test failures — keep it up to date, and record all similar findings here.** When a test failure is diagnosed as a flake or an environment quirk (not a code regression), add an entry here so the next contributor/session **re-runs rather than chases a phantom regression**. Each entry gives the test, the symptom, why it's benign, and the action. (These are also mirrored in the assistant's project memory, but this file is the in-repo source of truth.)

Since 2026-07 `build.yml` contains the flakes automatically: docs-only changes (`**.md`, `docs/**`) skip the workflow entirely, the Tier 2 and Tier 3 gametest steps **retry once** on failure (each retry emits a `::warning::` annotation in the run summary — a retried-then-green run means a flake fired, not that nothing happened), and the first attempt's logs/crash-reports are uploaded as `gametest-flake-evidence-*` artifacts (failures upload `gametest-failure-evidence-*`, retention 30 days — GitHub's raw job logs for this repo expire within days, the artifacts are the durable record). A job that is red even after its retry is very unlikely to be one of the entries below — diagnose it from the artifacts before re-running.

- **`RegionFaultGameTests` (Tier 2) — environmental on some dev boxes.** `region_fault_..._corrupt_region_chunk_resolves_as_contained_error_and_reader_survives` can fail (`errors=1 ... not_found=0 on tick 1202`) on constrained / WSL2 machines **regardless of load** (reproduces with all changes stashed) yet passes in clean CI. The corrupt-region triage is version-robust (a broad `catch (Exception)` resolves to not-found). Action: trust clean CI — a local red here is not a regression.
- **`LSSClientGameTests` superflat section count (Tier 3) — hardened in v0.6.1 (was a serialization-window flake).** `assertDecodedFlatWorldContent` used to fail with *"superflat columns must decode to exactly the one non-air section, got 24"* when a column was serialized during unsettled spawn-prep (its air sections not yet culled by `SectionSerializer`'s `hasOnlyAir` / block-light filter) and that stale delivery lingered in `recorder.snapshot()`. It flaked on the v0.6.0 and v0.6.1 PR CI (twice in a row on the latter — re-running did not clear it). The assertion now checks the content section (sectionY -4) and requires any extra sections to be **air-only** (sampled block states — the extras' blocks are genuinely air, only cull metadata/light differed), tolerating the transient un-culled sections while still catching real decode bugs. If a *new* "expected exactly one content section" or "must be air-only" variant appears, treat it as a real decode regression, not a flake. (`release.yml` runs `-x runClientGameTest`, so Tier 3 never gated the publish regardless.)
- **`TwoPlayerGameTests` fan-out (Tier 2) — hardened 2026-07 (was a one-shot re-ask race, twice).** `editedColumnPropagatesToBothHoldersThroughBroadcastFanout` failed as "A=3 B=1 on tick 1202" when a step-2 re-ask routed before the broadcaster's dirty-clear mailbox event applied on the processing thread and terminally resolved up_to_date off the stale done-bit. Step 1's version of the same bug was fixed in PR #19; step 2 flaked again 2026-07-10 and now retries its ts=1L re-asks under the same exactly-once guards (mirroring the real client's rescan retry, without weakening the pin — an undelivered clear still freezes the counts and fails at maxTicks). If this test fails again, treat it as a real fan-out regression, not a re-run.
- **`GenerationLifecycleGameTests` (Tier 2) — cold-gen timing, budgets raised 2026-07.** The four tests that wait on real cold chunk generation (piggybacked, same-tick re-request, End-void sentinel, generation-serve seed) ran at 600 maxTicks, which first-run generation can exceed on starved runners; all now run at 1200 (FP-033 needed the same bump earlier). A timeout beyond 1200 ticks is worth investigating, not re-running.
- **Vanilla `TicketStorage.purgeStaleTickets` NPE (Tier 3) — upstream MC 26.2 crash under ticket churn.** The integrated server can die with `NullPointerException: ... "this.wrapped" is null` in `Long2ObjectOpenHashMap$MapIterator.nextEntry` via `TicketStorage.removeTicketIf` ← `purgeStaleTickets` ← `ServerChunkCache.tick` (first seen 2026-07-10, run 29101338616). Investigated: every LSS ticket call is main-thread-only (`ChunkGenerationService` — submit/timeout/extract/removePlayer/shutdown all inside `tick()`), and LSS's generation ticket has no timeout so the purge never touches it; 26.2's `removeTicketIf` bytecode fires the `ChunkUpdated.update` callback *mid-iteration* of the fastutil ticket map, so any synchronous downstream ticket mutation corrupts the iterator — a vanilla fragility that Tier 3's generation-driven ticket churn makes likely to trip. Not fixable in LSS; the CI retry contains it. Action: if it recurs, pull the crash report from the `gametest-flake-evidence-client` artifact and confirm the stack is still vanilla-only before suspecting LSS.
- **Soak checker (`check_soak.py`) — benign timing / light-settle drift on constrained boxes.** Occasional dirty-resave / quiescence drift is tolerated by design (the checker carries settle tolerances); treat as a re-run, not a rewrite.
  - **`A7` "disk.errors increased" on a memo-enabled fresh-backfill — the miss memo's generation acceleration starving background reads (diagnosed by A/B 2026-07-19).** With `missMemoTtlSeconds` > 0 (default 30), fresh-backfill on constrained boxes (WSL2, concurrent servers) can red A7 with 10-15 timeout-triaged `disk.errors`: the memo removes the churn reads that accidentally paced generation admission, generation runs at full caps, and its save traffic monopolizes the single-threaded vanilla IOWorker that `useBackgroundReadPriority` reads deliberately queue BELOW — a few real reads exceed the 10 s timeout. Decisive signature (counter-based — raw `TimeoutException` stacks no longer print; timeouts log as a throttled `"Disk read timed out"` WARN whose `+N more` aggregation and 60 s window make line counts under-count): `disk.errors` increased, `grep -c "Failed to read chunk" server.log` == 0 (every error was a timeout — non-timeout read errors still log that line with stacks), and **A5 exact** (the `disk.memo_hits` term closes it). Every timeout self-heals via the not-found ladder. Action: it is the documented memo tradeoff (`docs/planning/miss-memo-design.md` A/B finding), not a regression — verify with the kill-switch A/B (`"missMemoTtlSeconds": 0` in the scenario config) if in doubt. If A5 ALSO fires, it is a different problem. Since frontier outward damping (2026-07-19) the same box measures `disk.errors = 0` on fresh-backfill (the damper's admission cadence removed the IOWorker monopolization) — this entry stays for constrained-box variance, but a red here on a post-damping build deserves a second look before being written off.
  - **`A7` "disk.errors increased" + `law-coverage` "fewer client-laws windows" together — environmental read-timeout storm on a loaded box (diagnosed 2026-07-17).** When the machine is under heavy IO load (e.g. concurrent Gradle builds/soaks/gametests, or WSL2), LOD disk reads can exceed `DISK_READ_TIMEOUT_SECONDS` (10 s) and throw `TimeoutException`, which increments `disk.errors` (A7 flags any increase) and disrupts convergence enough that fewer quiescent windows form (tripping the client-law coverage floor). **Decisive check** (counter-based since 2026-07-19 — timeouts log as a throttled `"Disk read timed out"` WARN, not a `TimeoutException` stack, and the `+N more` aggregation means line counts under-count): `disk.errors` increased while `grep -c "Failed to read chunk" <run>/server.log` == 0 — every error was a timeout (non-timeout read errors still log that line with a stack trace). A clean run has `disk.errors=0`. Critically, if `A5` also fires it is a DIFFERENT problem: A5's fold `d_nf += disk.errors` assumes a timed-out read lands on the not-found ladder (gen submit / miss_dropped), so an A5 imbalance == the timeout count means the timeout-triage path changed (see the v0.7.1 disk-timeout item in `docs/planning/v16-compat-design.md`). A7+coverage WITHOUT A5 = environmental, re-run on an idle box; the fresh-backfill history is overwhelmingly green.
  - **`dimension-trip` "dimension segment does not end verified-quiescent" — snapshot phase-drift artifact (diagnosed 2026-07-15).** The segment-end law (`check_soak.py:990`) requires the segment's LAST client row to be in `quiescent_client`, but `find_quiescent` only ever files a QPoint at the client row *nearest in wallMs* to a server snapshot. When the client and server 5 s cadences drift out of phase (routine at TPS ~14-19 on a loaded box), the segment's final row can sit in a gap where a neighbour is always closer — so it is **structurally unable** to be marked quiescent no matter how idle the system is. Seen on a Paper run where the boundary was provably converged (client `tracker_in_flight=0`, `queued=0`, `received_columns == requested_total` flat for 15 s; server counters unmoved across 4 snapshots); it passed clean on re-run. **Decisive check before blaming code:** for the failing row index `hi`, ask whether `hi` is ever `min(|client.wallMs - server.wallMs|)` for any server snapshot — if never, it's this artifact, not a convergence failure. Action: re-run.
  - **Law A5 — latent false positives on PERMANENT generation failures and boundary ticket drops (documented 2026-07-16, direction corrected 2026-07-18, never yet seen live).** A5 balances `disk.not_found` against `generation.submitted + not_generated + service.miss_dropped`. A permanent generation failure (extraction error / Moonrise null chunk) lands on BOTH right-hand terms for one miss — `generation.submitted` at submit AND the client's `responses.not_generated` when the answer arrives — so while the client is connected the RHS OVER-counts by one per failure; a disconnect before the answer rebalances it. Same family, opposite direction: the two generation-ticket drop paths in `drainGenerationTicketRequests` (state gone / dimension mismatch, both platforms) skip `miss_dropped`, so a near-boundary drop on `dimension-trip` UNDER-counts the RHS by one. No current scenario produces permanent gen failures (they require real extraction errors, not config), so an A5 red today is a code regression first; check whether the imbalance equals a small count of server-side generation errors or near-boundary ticket drops before chasing conservation. Also noted in `law_A5`'s comment in `check_soak.py`.

## Local Test Servers (manual play)

```bash
./test-server.sh              # set up + run all three: Fabric :25564, Paper :25566, Folia :25567
                              # (:25565 stays free for the soak/benchmark harness — a test server
                              #  there is indistinguishable in the server list and an accidental
                              #  join contaminates a running soak)
./test-server.sh run-folia    # one platform only (also run-fabric, run-paper)
./test-server.sh run-fabric-no-c2me  # Fabric with c2me*.jar parked as .disabled (A/B vs C2ME; run-fabric re-enables)
./test-server.sh update       # rebuild + reinstall the LSS jars into all three (restart to apply)
./test-server.sh clean        # delete test-server/
```

Downloads real server jars (Fabric launcher; Paper/Folia latest stable via fill.papermc.io),
installs the built LSS jars (Folia gets the same Paper plugin jar), and stages
offline-mode config under `test-server/<platform>/`. For joining with a real client and
eyeballing LOD behavior — the automated gates are the test tiers and the soak harness above.

## Architecture

### Entry Points

- **Fabric:** `LSSMod` — server initializer: registers payloads, starts `RequestProcessingService` on dedicated servers
- **Fabric:** `LSSClient` — client initializer: sets up client networking, commands, mod compatibility
- **Paper:** `LSSPaperPlugin` — plugin entry point: registers Plugin Messaging channels, starts `PaperRequestProcessingService`

### Networking Protocol (v17 — declarative want-set, server-owned generation)

Batch model with 6 payload types. Fabric uses `LSSNetworking` with Fabric `StreamCodec`;
Paper uses `PaperPayloadHandler` with raw `FriendlyByteBuf` encoding. Both produce identical wire format.

**C2S:** `HandshakeC2SPayload` (capabilities bitmask) → `BatchChunkRequestC2SPayload` (batch of packed position + clientTimestamp pairs — the client's **complete want-set**, not an increment; ts `>0` = resync "send if newer", `<=0` = "I have nothing" — there is no generate-request shape, the client never classifies)

**S2C:** `SessionConfigS2CPayload` (4 fields: protocolVersion/enabled/LOD distance/generation toggle — the concurrency caps are server-internal and left the wire) → `VoxelColumnS2CPayload` (coords + dimension + columnTimestamp + one-byte serve-source tag `COLUMN_SOURCE_*` in_memory/disk/generation — diagnostic attribution for the client trace; unknown values pass through verbatim; added in protocol 18, still the v17 design line — + MC-native sections) → `BatchResponseS2CPayload` (batch of responseType + packed position pairs — up-to-date and not-generated) → `DirtyColumnsS2CPayload` (server-pushed change notifications)

**The want-set model.** Each scan (1 Hz / 20 ticks) the client declares *every position it still wants*, closest-first, in one batch; the server **replaces** that player's backlog with it. A position drops out of the want-set only when it is SATISFIED. Consequences that are load-bearing, not incidental:
- **Re-declaration is the single self-healing mechanism.** There is no client drip-feed, no in-flight timeout sweep, and no rate-limit backoff — all deleted. The server may silently drop any not-yet-admitted ask (backlog replace, mailbox overwrite, Folia hold CAS loss, residual disk saturation); the next 1 Hz declaration heals it. Every such drop is counted (`service.superseded` / `service.range_filtered`).
- **The scanner must NOT suppress awaited positions at send.** An awaited position is an ordinary unsatisfied want-set member and blocks its own ring's confirmation until data lands. Re-introducing send-time suppression re-opens a 10s-class stall against the server's silent drops.
- **`RESPONSE_UP_TO_DATE` / `RESPONSE_NOT_GENERATED` are the only things that empty the want-set.** They are what makes `ColumnStateMap.classify` return SATISFIED. Never delete them. `NOT_GENERATED` is **permanent for the session**: the client adds the position to `sessionSatisfied` (no 0-stamp — legacy cache 0-stamps re-declare as -1) and never re-asks, even on movement; the ONLY revival is a dirty broadcast. It fires ONLY on permanent unservability (generation disabled, or a hard generation failure) — **never for transient pressure**: a full gen slot, gen-service capacity, a generation timeout, a submit no-op, or a ghost delivery all drop silently (counted `superseded`, disk-miss flavors also `miss_dropped`) and heal by re-declaration. On a gen-enabled server it never fires at all (pinned by the `generation-capacity-stress` soak: `not_generated == 0`).
- **At convergence the client sends NOTHING** — not an empty heartbeat. Soak quiescence depends on `service.requests_received` going still. The one exception is a single **edge-triggered empty batch** when the client's decode queue crosses into backpressure halt: it clears the server backlog so no work is done for a client that cannot ingest it.
- **Byte 0 is retired and reserved.** It was `RESPONSE_RATE_LIMITED` through v16; UP_TO_DATE(1)/NOT_GENERATED(2) were deliberately *not* renumbered, and the codec ships unknown types unaltered (pinned) so byte 0 stays forward-safe.
- **v17 is a breaking protocol bump — with a server-side legacy shim.** A v17+ client against a v16 server gets no LOD session (silent version rung). A v16 client against a v17+ SERVER gets a translated session through the **v16 compat shim** (`enableV16Compat`, default true — `common/compat/V16CompatManager`): the handshake gate's `WireDialect.V16` rung replies with the legacy 6-field SessionConfig echoing version 16, ingress merges the old drip-feed into a per-player synthetic want-set (1 Hz sole declarer, sorted closest-first, 75 s TTL, overflow bounced with the revived byte 0), and the column egress strips the source byte. The pipeline never learns v16 exists; with no legacy clients every shim path is a no-op. Design + both review rounds: `docs/planning/v16-compat-design.md`.
- **The server owns generation.** The disk miss IS the generation trigger: a cold column always reads disk first (one route, one SYNC slot), and on not-found the server generates if generation is enabled and a GENERATION slot is free — no request shape can force or forbid it. `ts == 0` (the retired v16 generate-request) routes identically to `-1`, as inert as the retired wire byte 0.
- **No client budget derives from any server cap.** The scanner declares the constant `WANT_SET_BUDGET` (800) per scan; the old Global Constraint #28 cross-clamp is gone. Its successor is a static inequality pinned by `WantSetBudgetInvariantTest`: `SYNC_ON_LOAD_SLOT_CAP (200) + MAX_CONCURRENT_GENERATIONS (256) + WANT_SET_FRONTIER_RESERVE (64) <= WANT_SET_BUDGET <= MAX_BATCH_CHUNK_REQUESTS (1024)` — the want-set dominates the worst-case in-flight set with frontier headroom, re-derived at build time if any constant drifts.

Backpressure lives in three places now, none of them on the wire: the bounded want-set (the constant `WANT_SET_BUDGET` = 800, inside the `MAX_BATCH_CHUNK_REQUESTS` = 1024 wire cap), the server's per-player dequeue gates (a full slot cap or disk pool *retains the entry in the backlog* — it never bounces), and the client's edge-triggered clear above.

Requests are keyed by packed chunk position end-to-end (no request ids): every response is a statement about a position, so late or duplicate responses are idempotent, and a column the client no longer tracks is still ingested. Delivery honesty: the server's per-player served-set (diskReadDone) answers re-declarations with up_to_date only when the client claims data (ts>0); a ts<=0 re-declaration declares "I have nothing" and re-resolves honestly (skipped silently while the payload is still in the send pipeline, deliberately without clearing the done-bit — see `IncomingRequestRouter.resolvedAsDuplicate`), so any post-resolution loss — send-queue drop, decode failure, consumer rejection — self-heals instead of becoming a permanent hole. Consumers report rejected columns via `LSSApi.reportIngestFailure`, which forgets the client's received-stamp so the next scan re-declares it.

Flow: client handshakes with capabilities bitmask (CAPABILITY_VOXEL_COLUMNS set if LSSApi has consumers) → server sends the 4-field session config → client scans in an expanding spiral every second (20 ticks) and ships its whole want-set with clientTimestamps (<=0 for "I have nothing", >0 for resync) → the batch lands in the player's latest-wins mailbox and, on the processing cycle, **replaces** the backlog (superseded entries counted, out-of-range ones range-filtered) → the router drains the backlog in declaration order (proximity-ordered by the client): up-to-date checks, loaded-chunk probe, then for a cold column `tryAdmitAndSubmit` — every entry takes a SYNC slot and reads disk; if the slot cap is full the entry is **retained and the drain continues**, and if the disk pool has no headroom it is retained and the pass **stops** → a disk miss escalates server-side (`handleDiskNotFound`): gen enabled + a GENERATION slot free swaps the entry into a generation ticket; gen slot full drops silently (`superseded` + `miss_dropped`, re-declared next scan); gen disabled answers `NOT_GENERATED` (session-permanent) → server batches lightweight responses (up-to-date, not-generated) per tick; column data payloads are sent individually. After the initial scan, the server periodically pushes `DirtyColumnsS2CPayload` listing changed columns; a dirty position re-enters the client's want-set on the next scan — for a `NOT_GENERATED`-parked position this is the ONE revival path (on Fabric a first-time vanilla chunk save fires it; on Paper walk-in generation does NOT mark dirty by default — `ChunkPopulateEvent` is an opt-in `updateEvents` entry — so a parked position there heals on reconnect). One accepted corner rides this: with generation disabled, a LOADED but never-saved chunk whose probe was deferred by the global probe cap disk-reads as not-found and parks `NOT_GENERATED` despite being live in memory — healed by its first save's dirty broadcast or reconnect (see the `MAX_PROBES_PER_TICK_GLOBAL` comments in both services).

### Backfill visual ordering — settled model, do NOT re-investigate (2026-07-16/17)

"LOD chunks load far-before-near / parts of rings missing" was root-caused across a full day of
live traces (`/lss trace`, source-tagged columns) and is CLOSED. The observable is the sum of
three layers — two irreducible, one platform-owned. Re-deriving this costs a day; read this first:

1. **Arc-fill geometry (irreducible, all platforms, scales with confirmed ring).** Ring r has 8r
   positions but only `generationConcurrencyLimitPerPlayer` tickets fly at once, so rings beyond
   ~cap/8 fill as sequential arcs; missing-arc time ≈ ring_size/gen_rate. Platform completion
   scramble (C2ME ≈ 8% stationary, measured via the `inversions` counter; Moonrise mild; vanilla
   ≈ FIFO) makes the arcs non-contiguous. Bounded by the order-spread gate to ~2 rings.
2. **Trailing view-edge crescents (irreducible, movement only).** Chunks inside the vanilla view
   circle are LOD-excluded; the circle moves with the player, so positions exiting it become
   LOD-needing for the first time and pop in NEAR (ring ≈ view distance, `src:0` probe serves)
   while the eye watches the far frontier — reads as inversion, is correct first-time service.
3. **The platform generating ahead of LSS (C2ME; the only tunable layer).** On a fresh world
   ~10% of the C2ME stream arrives `src:1` (disk) — terrain C2ME completed and saved BEYOND
   LSS's tickets (dependency pyramid / batch completion, notickvd where enabled), landing a
   median 5 (max ~18) rings ahead of the frontier. LSS finds it on the next 1 Hz re-declaration
   and serves it immediately — CORRECT (refusing to serve wanted, existing terrain is strictly
   worse). The lever is C2ME's config, not LSS code.

What WAS LSS's fault is already fixed and pinned — do not un-fix under performance pressure:
the movement cadence debounce (starved all declarations during flight), the spread-gate anchor
ladder (batch[0]-origin wedge → min-outstanding straggler leak → the shipped router-stamped
LIVE frontier), the disconnect bulk ticket release (60 s C2ME distance-graph freeze; staggered
via `DeferredTicketReleases`), and the vanilla-load scan budget scale (retired — server-side
priority owns that protection). Diagnosis instruments if something NEW appears: `/lss trace`
(client JSONL; `col` events carry `src` 0=probe/1=disk/2=generation) and `/lsslod diag`'s
`order_gated`/`inversions`. A far `src:2` beyond frontier+2 on a fresh world is an LSS gate bug;
far `src:1`/`src:0` is the platform — go tune C2ME, not the router.

Chunk coordinates are packed as `((long)chunkX << 32) | (chunkZ & 0xFFFFFFFFL)`.

### Common Module (`common/`)

- `LSSConstants` — protocol version, channel IDs, wire format limits, status codes
- `LSSLogger` — SLF4J logging wrapper
- `SharedBandwidthLimiter` — fair per-tick bandwidth allocation across active players (global + per-player caps)
- `PositionUtil` — chunk coordinate packing/unpacking
- `DiagnosticsFormatter` — formats diagnostic data for `/lsslod diag` output
- `ColumnTimestampCache` — in-memory per-dimension `(packedXZ → epochSeconds)` timestamp cache for up-to-date checks without disk IO. Also hosts the **miss memo** (docs/planning/miss-memo-design.md): a sibling per-dimension map of TTL-bounded authoritative disk misses (`missMemoTtlSeconds`, default 30, 0=off) so gen-waiting positions skip the ~1 Hz redundant not-found re-reads. `put()` (any serve) and `invalidate()` (edits) clear entries; `invalidateStamps()` is the disk-drain's stamps-only variant (a not-found delivery is the memo's own evidence — full invalidation there would erase every memo write); misses never persist across save/load
- `OffThreadProcessor` — abstract base for main-thread → processing-thread handoff (prepares pre-serialized column data off-thread, routes requests via `IncomingRequestRouter`). `handleDiskNotFound` carries the **generation order-spread gate** (`MAX_GENERATION_RING_SPREAD` = 2): a new generation ticket may enter at most 2 Chebyshev rings beyond the **client-declared frontier** — the applied want-set's first entry (nearest unsatisfied by closest-first construction), measured from the player's real chunk, stamped main-thread each lifecycle tick via `updatePlayerChunk`. Two wrong anchors preceded this (all 2026-07-16): the batch's first entry as the RING ORIGIN wedged the gate to ~97% refusals (the scan excludes the vanilla view distance, so that entry sits ON a ring perimeter), and the oldest OUTSTANDING ticket as the reference leaked — when the near band momentarily completed, a far corridor straggler re-anchored the window and legally admitted positions many rings past the frontier (fresh-server trace evidence). The frontier cannot be dragged outward by in-flight work. Pinned by `spreadGateAnchorsOnTheDeclaredFrontierNotOutstandingTickets` (the frontier anchor) and `generationRefillMustNotRaceBeyondTheOldestOutstandingTicket` (re-calibrated 2026-07-19 to the tighter unified-pacing invariant: refill holds at nearest-outstanding+1, so the cohort rule fires before the spread gate in that ladder). The reference is additionally **outward-damped** (`FRONTIER_OUTWARD_DAMP_MILLIS_PER_RING` = 333, ~3 rings/s; inward instant — movement-inversion fix, admission-trace diagnosed 2026-07-19): under movement the honest frontier is NON-MONOTONIC — at instants when the near field is momentarily all-satisfied it observes the far edge, and an undamped window admits far waves that complete AFTER the near work movement mints a moment later (the far-then-near "line pop" on stopping — every such admission was verified IN-window by `[lss-adm]` logs; the gate was honest, the observation was about to be falsified). Stationary backfill advances ring-by-ring inside the +2 window, which covers the damper's ≤333 ms reference lag (soak-verified: identical throughput — but "never waits" would over-round; each ring transition's reference advance can lag up to one interval); a long-idle stamp gap accumulates outward budget, so a teleport FROM IDLE into explored terrain self-heals immediately (a teleport during active backfill has no banked budget — far generation resumes after a few seconds of climb; reads/memoization proceed ungated meanwhile). Rigs disable damping (`setFrontierDampingForTest(0, …)`) — existing gate pins calibrate instant-outward semantics; the damping tests re-enable it with an injected clock, and `productionDefaultEnablesOutwardDamping` pins the wired nonzero default. Admission-order diagnosis instrument: `-Dlss.admissionTrace=true` (test-server.sh enables it on dev Fabric servers by default; `LSS_ADMISSION_TRACE=0 ./test-server.sh run-fabric` silences it — it is verbose during backfill) logs one `[lss-adm]` line per escalation decision (candidate ring, damped frontier, nearest in-flight rings, via, verdict). Without the gate, per-slot refill starves the nearest chunk while the frontier races outward on non-FIFO schedulers (C2ME). A gated miss is the standard transient drop (`superseded` + `miss_dropped`); `/lsslod diag` shows `order_gated` (gate refusals) and `inversions` (completions that finished while a nearer ticket was outstanding — the far-before-near evidence counter). Holds the invalidation-overtake stale guard: an edit that overtakes an in-flight disk read (tracked via its dedup group) or generation (tracked per-player in `generationInFlight`, swept on player removal) marks the buffered result stale so its delivery skips the timestamp stamp + `diskReadDone` and the client re-resolves. On a failed cycle, `requeueLosslessEvents` re-queues only the events whose phase did not complete (phase-completion flags) so a consumed generation outcome is never re-delivered un-tainted. Owns the server side of server-owned generation: `handleDiskNotFound` escalates any miss into a GENERATION slot + ticket when gen is available (gen slot full = silent drop), and `processGenerationReady` routes transient outcomes (timeout/capacity — `transientFailure` on `GenerationReadyData`) to silent drops while only permanent failures answer `NOT_GENERATED`. The **miss memo** rides this path: an AUTHORITATIVE miss (never an error/timeout triage — `ChunkReadResult.authoritativeMiss`, never a ghost) is memoized before the gen ladder, the router's memo rung (gated on generationAvailable, placed after the duplicate/timestamp/probe rungs) skips the redundant re-read via the shared `escalateMissToGeneration` helper (GENERATION slot only, counted `disk.memo_hits` — law A5's virtual not-founds), and EVERY drained generation outcome clears the entry before the per-player continues (plus the `put()` serve choke point). ALL generation admission — memo rung AND delivery path — runs the **pacing rules** ("generation never overtakes nearer in-flight work": nearer-pending-SYNC hold + `MAX_GENERATION_COHORT_SPAN` (1) beyond the NEAREST outstanding ticket, refusals counted `gen_order_gated`); the delivery path's old "emergent pacing" only held for stationary players with fast reads — unpaced stale deliveries under movement were the far-ahead isolated-chunk bug. The outstanding set is a RESTRICTION, never the window's anchor (straggler tightens, never widens — pinned). Every disk-miss silent drop counts `superseded` + `miss_dropped` (the dedicated soak-law-A5 term; timeouts count only `superseded` — their submission already balanced the miss). Residual disk saturation is a silent drop counted `superseded` and healed by the next want-set declaration — it no longer reaches the wire.
- `IncomingBatch` — immutable `record IncomingBatch(IncomingRequest[])`; the unit the client declares, the mailbox holds, and the backlog is replaced with
- `AbstractPlayerRequestState` — per-player state. Under v17 it owns the want-set path: a latest-wins `pendingBatch` mailbox (`offerIncomingBatch`/`takeIncomingBatch`, an `AtomicReference` — network/pump/processing safe; an overwritten batch is counted superseded), the processing-thread-only `ArrayDeque` **backlog** (`replaceBacklogWith`/`pollBacklog`/`restoreBacklog`, with a volatile `getBacklogSize` mirror for diagnostics), and `publishedWantSet`/`peekWantSet` — the probe's fallback source, which survives `takeIncomingBatch` and clears only when the backlog drains (`restoreBacklog` republishes; without that the steady state is ~zero probe coverage)
- `IncomingRequestRouter` — replaces each player's backlog with the newest declared want-set, then drains it in declaration (proximity) order: checks timestamps for up-to-date, probes loaded chunks, dispatches disk reads (every entry — the client no longer classifies; generation is decided at the disk miss), with cross-player dedup via `DedupTracker`. Nothing is bounced: a full slot cap **retains the entry and keeps scanning** (the cap means "dequeue at most N concurrently", not "reject above N" — entries behind a full slot can still resolve via the ladder/probe), no disk headroom **retains and stops the pass** (saturation never reaches the wire — issue #32 at its source), and a full send queue retains and stops. A submit no-op (level not yet registered) drops silently — transient conditions never answer the session-permanent `NOT_GENERATED`. Retained entries are re-prepended in order by `restoreBacklog`, which also republishes the want-set
- `PendingRequest` / `SlotType` — derived admission: a pending-map entry IS the held slot (no separate limiter object). Every entry holds `SYNC_ON_LOAD` until a disk miss escalates it to `GENERATION`
- `DirtyColumnTracker` — thread-safe tracker of per-dimension dirty chunk positions, drained by broadcasters
- `SendActionBatcher` — reusable per-tick accumulator for batching `SendAction` responses by player
- `HandshakeGate` — pure decision ladder for the C2S handshake (version mismatch → no reply; no consumer → reply without registering; disabled → advertise disabled; else register), shared by `LSSServerNetworking` and `LSSPaperPlugin` so reply/registration policy cannot drift between platforms
- `JsonConfig` / `ServerConfigBase` — GSON config base classes; `ServerConfigBase` holds the server config fields, defaults, and clamps shared verbatim by Fabric and Paper

### Fabric Server-Side (`fabric/`)

- `RequestProcessingService` — orchestrates per-player processing, ticks on server thread, broadcasts dirty columns
- `PlayerRequestState` — per-player request state, pending-request slot admission, send queue, metrics
- `ChunkDiskReader` — async region file reader with configurable thread pool. With `useBackgroundReadPriority` (default true) it schedules reads on the vanilla `IOWorker`'s own single-threaded `consecutiveExecutor` at BACKGROUND priority (below vanilla's FOREGROUND chunk loads) via `AccessorSimpleRegionStorage`/`AccessorIOWorker`, reading straight from `RegionFileStorage`; the ordinal is pinned (`IOWorker$Priority` is package-private, and Fabric remapping rules out resolving it by name — a `SerializerParityGameTests` byte-parity test pins the path). That skips IOWorker's `pendingWrites`, so Fabric gives up read-your-writes here; a just-saved column self-heals via dirty-broadcast. Flag false restores `chunkMap.read` (a true rollback — no throttle either). **Chunk-IO-overhaul mods (C2ME's chunkio rewrite, and structurally similar) replace vanilla's single-threaded IOWorker with their own concurrent IO, leaving `consecutiveExecutor`/`storage` null on the vanilla shell — `backgroundReaderOrFallback` catches a null handle OR any `Throwable` from accessor resolution, latches a server-wide one-way `backgroundIncompatible` flag, warns exactly once, and falls back to `chunkMap.read` + engages the adaptive read throttle (Approach B — `AdaptiveReadThrottle`, which yields to gameplay via self-restraint by narrowing `hasHeadroom()`, so the want-set router retains the read and the client re-declares it). So LSS keeps gameplay protection on C2ME servers, not just correctness. This branch is unreachable from gametests/soak (both run plain vanilla IO, executor always non-null); only a live C2ME server surfaces it, so `ChunkDiskReaderTest` pins the fall-back CONDITION as a pure predicate AND the throwable-latch→enable-throttle→warn-once wiring via an injected throwing resolver; the throttle control law + the enable→narrow-`hasHeadroom`→recover wiring are pinned in `common/` unit tests. A live C2ME smoke should also verify the DIRTY path (a `/setblock` reaching a connected client as a dirty broadcast): dirty detection hangs off the `ChunkMap.save` mixin hook, which a chunk-IO overhaul could bypass or move off-thread — no automated tier covers that either.** See `docs/planning/read-scheduler-design.md` §10.4.
- `FabricOffThreadProcessor` — extends `OffThreadProcessor`; main thread probes loaded chunks via `SectionSerializer`, processing thread prepares payloads and schedules sends
- `SectionSerializer` — serializes loaded MC `LevelChunkSection` + light data into wire-format bytes using MC's native `section.write(buf)`
- `NbtSectionSerializer` — parses region file NBT into `LevelChunkSection` objects, then serializes them (used by `ChunkDiskReader`)
- `ChunkGenerationService` — ticket-based chunk generation, triggered by the server on any disk-read "not found" (gen enabled + slot free). Timeouts are TRANSIENT outcomes (silent drop, retried via re-declaration); only extraction errors are permanent. Fabric generation has no priority hand-off (vanilla pins worldgen priority to ticket level 33) and no latency throttle (worldgen's variable baseline makes latency a poor congestion signal) — the concurrency caps are its honest limiter; a future adaptive backoff should key on tick health (MSPT), not latency
- `DirtyColumnBroadcaster` — periodically pushes dirty column notifications to connected players
- `DirtyContentFilter` — FNV-1a hash of served column bytes per position; chunk saves mark a column dirty only when LOD-visible content actually changed (filters vanilla's ~10s metadata-only re-saves, e.g. inhabitedTime). Fabric only — Paper's dirty detection is event-driven.
- `LSSNetworking` — `PayloadTypeRegistry` registration for all C2S and S2C payload types
- `LSSServerNetworking` — server-side event listeners, `RequestProcessingService` lifecycle (started via `ServerLifecycleEvents.SERVER_STARTED`)
- `AccessorServerChunkCache` (mixin) — exposes `ChunkMap` for disk reads
- `AccessorChunkMap` / `ChunkMapSaveHook` (mixins) — hooks chunk saves to feed `DirtyColumnTracker`
- `IntegratedServerLanHook` (client mixin) — starts `RequestProcessingService` when an integrated server is published to LAN

### Paper Server-Side (`paper/`)

- `PaperRequestProcessingService` — same orchestration as Fabric, sends via Plugin Messaging instead of Fabric networking; ticks on the GlobalRegionScheduler pump (main thread on Paper, the global-region thread on Folia) and drains a cross-thread lifecycle mailbox (`enqueueRegister`/`enqueueRemove` — handshakes and quits arrive on region threads on Folia) at the top of `tick()`. On Folia only (`regionizedProbing = FoliaSupport.IS_FOLIA`, experimental) the loaded-chunk probe runs on the chunk's OWNING region thread via the player's `EntityScheduler` behind `Bukkit.isOwnedByCurrentRegion`, instead of on the pump — with a one-tick hold-release at **batch** granularity (`heldForProbe`, a `Map<UUID, IncomingBatch>`) so a batch and its region-published probe results meet in the same snapshot. The pump releases last tick's held batch back into the mailbox via `republishHeldBatch` (a `compareAndSet(null, held)`) **before** taking anything new, and a successful release ends the tick: taking first would empty the mailbox, make the CAS succeed unconditionally, and resurrect a batch the client has already superseded. A lost CAS means a newer declaration arrived — the held batch is dropped and counted superseded, never resurrected. `RegionTaskScheduler`/`RegionOwnershipCheck` are injection seams for the JUnit tests
- `PaperPlayerRequestState` — per-player state adapted for `byte[]` payloads instead of `CustomPacketPayload`
- `PaperChunkDiskReader` — async disk reader using direct NMS `ChunkMap` access (no mixin needed). With `useBackgroundReadPriority` (default true) reads route through `MoonriseRegionFileIO.loadDataAsync(..., intendingToBlock=false, Priority.LOW)` instead: Paper/Folia gameplay loads run on Moonrise's own prioritised IO pool rather than the vanilla `IOWorker`, so IOWorker priority would be a no-op here and LOW is what actually defers to gameplay's NORMAL. Unlike Fabric this keeps read-your-writes (`loadDataAsync` serves in-progress writes). Flag false restores `chunkMap.read`. The live LOW path has no cheap unit test — the soak harness on real Paper/Folia is its gate
- `PaperOffThreadProcessor` — extends `OffThreadProcessor`; same main-thread/off-thread split as Fabric
- `PaperSectionSerializer` — Paper-side serializer using NMS `LevelChunkSection.write(buf)` + light data
- `PaperNbtSectionSerializer` — parses region file NBT into `LevelChunkSection` objects, then serializes them (used by `PaperChunkDiskReader`)
- `PaperChunkGenerationService` — async chunk generation via Moonrise `PlatformHooks.get().scheduleChunkLoad(..., ChunkStatus.FULL, addTicket=true, Priority.LOW, consumer)`, triggered by the server on any disk-read "not found": LOD generation DEFERS to player-driven NORMAL generation (the generation analogue of the `Priority.LOW` read path). The consumer delivers an NMS `ChunkAccess` with no throwable channel — null is the (permanent) failure outcome; timeouts stay transient. Serializes the generated chunk inside the completion (`completeAsyncLoad` — on Folia that is the owning region thread and the chunk can unload before a hop to the global tick) and hands only the immutable `LoadedColumnData` to the pump. The live LOW path has no cheap unit test — the soak harness on real Paper/Folia is its gate (the `Priority.LOW` argument is unit-pinned via the `launchAsyncLoad` seam)
- `FoliaSupport` — platform probe (`Class.forName` on Folia's regionizer class); used only where Folia removes functionality outright (e.g. the soak driver's save-all mapping)
- `PaperPayloadHandler` — encodes S2C / decodes C2S payloads using same wire format as Fabric
- `PaperCommands` — Bukkit `CommandExecutor` for `/lsslod stats` and `/lsslod diag`
- `PaperConfig` — GSON JSON config (same defaults/validation as Fabric)
- `PaperDirtyColumnBroadcaster` — broadcasts dirty column notifications to connected players (same as Fabric's `DirtyColumnBroadcaster`)
- `PaperWorldHandler` — registers configurable Bukkit event listeners for dirty chunk detection via reflection-based position extraction
- `PaperSoakBridge` — reflective gate from `LSSPaperPlugin` into the dev-only `dev.vox.lss.paper.soak` package (soak scenario driver + metrics exporter twins; excluded from the release shadowJar, retained by `soakShadowJar` for `SOAK_PLATFORM=paper` and `SOAK_PLATFORM=folia` runs)

### Client-Side

- `LodRequestManager` — orchestrates the client request loop: each scan the scanner writes the whole want-set into its send buffers and it ships in the same tick; handles dirty column re-requests, pruning on movement, and the one edge-triggered empty batch that clears the server backlog on decode-queue backpressure. No drip-feed, no timeout sweep, no rate-limit retry — re-declaration replaces all three
- `SpiralScanner` — expanding Chebyshev ring scan (20-tick cadence) that produces the want-set closest-first; owns scan policy: the constant `WANT_SET_BUDGET` (800 — no client budget derives from any server cap) with queue-pressure scaling (the vanilla-load scale is retired — server-side read/generation priority owns that protection). Does NOT suppress awaited positions — an awaited position blocks its own ring's confirmation
- `ColumnStateMap` — single owner of per-column client state (timestamps + dirty/retry/validated marks); its `classify` ladder decides whether a position is still wanted, and marks are consumed at the terminal answer (up-to-date / not-generated), not at send. `onNotGenerated` is a PERMANENT session-satisfy (no 0-stamp; only `markDirtyIfKnown` — a dirty broadcast — revives it); legacy cache 0-stamps from released clients classify as "no data" and re-declare -1
- `InFlightTracker` — the awaiting-answer set (positions of the last want-set minus answers since), replaced wholesale each scan. NOT a send-suppression structure: it exists only to gate status responses (BatchResponse carries no dimension) and to detect a dirty broadcast crossing an in-flight answer
- `ClientColumnProcessor` — off-render-thread decode of received column payloads before dispatch to consumers
- `ColumnCacheStore` — per-server per-dimension binary cache of column positions and timestamps (enables resync across sessions)
- `LSSClientNetworking` — packet handlers, dispatches sections to `LSSApi` consumers

### Public API (`LSSApi`)

LOD rendering mods register a `VoxelColumnConsumer` via `LSSApi.registerColumnConsumer()` to receive `VoxelColumnData` (MC-native `LevelChunkSection` + `DataLayer` light data per section, with coordinates). Consumer list is `CopyOnWriteArrayList` for thread safety. The client sets `CAPABILITY_VOXEL_COLUMNS` in the handshake only if consumers are registered.

### Compatibility

`VoxyCompat` uses `MethodHandle` reflection to call Voxy's `rawIngest` API at runtime — zero compile-time dependency, only 2 core MethodHandles (`WorldIdentifier.of` + `VoxelIngestService.rawIngest`). MC types (`LevelChunkSection`, `DataLayer`) must be referenced via direct class literals (not `Class.forName()`) due to Fabric's runtime remapping. `ModCompat` checks `FabricLoader.isModLoaded()` before attempting.

### Configuration

**Fabric:** GSON configs built on the shared `common/` base classes (`JsonConfig` loader + `ServerConfigBase`, which holds the server fields/defaults/clamps verbatim for both platforms). Two configs auto-created in `config/`:
- `lss-server-config.json` — bandwidth caps, LOD distance, disk reader threads, read priority, send queue limits, generation toggle, dirty broadcast interval, generation concurrency caps (server-internal; the old `syncOnLoadConcurrencyLimitPerPlayer` knob is retired — a constant now, and the key is dropped from the file on the next save), legacy-client shim toggle (`enableV16Compat`, default true), miss-memo TTL (`missMemoTtlSeconds`, default 30, 0 = off)
- `lss-client-config.json` — receive toggle (`receiveServerLods`), distance override (`lodDistanceChunks`)

**Paper:** `PaperConfig` extends the same `ServerConfigBase` — identical fields, defaults, and clamps as Fabric. Config at `plugins/LodServerSupport/lss-server-config.json`, plus an `updateEvents` list of Bukkit event class names for dirty chunk detection.

All config values are clamped to safe ranges (min and max) on load.

## Benchmarking

Automated benchmark system for measuring server pipeline performance. Gated behind `-Dlss.benchmark=true` — never activates in production.

### Quick Start

```bash
./scripts/benchmark.sh fresh 60       # Fresh world, 60s — tests generation + serialization
./scripts/benchmark.sh no-cache 60    # Pre-built world — tests disk-read + serialization
```

Run `fresh` first to generate a base world, then `no-cache` reuses it.

### Optimization Workflow

1. Run baseline: `./scripts/benchmark.sh fresh 60`
2. Make optimization changes
3. Run again: `./scripts/benchmark.sh fresh 60`
4. Compare `benchmark-results/server.json` — key metrics: `throughput.sections_per_second`, `throughput.bytes_per_second`, `disk_reader.avg_read_time_ms`, `jvm.gc_time_ms`
5. Client metrics in `benchmark-results/client.json` — key: `avg_rtt_ms`, `columns_received`, `total_not_generated`

### Output Files

- `benchmark-results/server.json` — server throughput, sources, disk reader, generation, backpressure, bandwidth, JVM stats
- `benchmark-results/client.json` — columns received, bytes, send cycles, RTT, bandwidth rate
- `benchmark-results/*.jfr` — Java Flight Recorder profiles for both server and client

### How It Works

The shell script builds the mod, starts a dedicated server (`runBenchmarkServer`) and client (`runBenchmarkClient`) as separate Gradle tasks. The client auto-joins via `--quickPlayMultiplayer`. After the configured duration, the server exports metrics and halts; the client exports on disconnect. Duration is configurable via `-Pbenchmark.duration=N` on the server Gradle task.

### Key Files

- `fabric/src/main/java/dev/vox/lss/BenchmarkBridge.java` — reflective gate from the entrypoints into the dev-only harness (the `dev.vox.lss.benchmark` package is excluded from release jars; production no-ops)
- `fabric/src/main/java/dev/vox/lss/benchmark/BenchmarkHook.java` — tick counter, auto-shutdown, metric dump
- `fabric/src/main/java/dev/vox/lss/benchmark/BenchmarkMetricsExporter.java` — JSON serialization of all diagnostic + JVM data
- `scripts/benchmark.sh` — orchestrator (build → start server → start client → wait → collect); sources `scripts/lib/mc-run.sh` for the shared server/client lifecycle helpers

### Scenarios Explained

| Scenario | World State | Tests |
|----------|------------|-------|
| `fresh` | Empty world, chunks generated on demand | Generation + serialization pipeline |
| `no-cache` | Pre-built world | Disk-read + serialization pipeline |

### Interpreting Results

**Server `server.json`:**
- `throughput.sections_per_second` — primary throughput metric
- `sources` — where data came from: `in_memory` (loaded chunks), `disk_read`, `generation`
- `disk_reader.avg_read_time_ms` — disk I/O latency
- `backpressure.superseded` / `range_filtered` — asks silently dropped by a newer want-set declaration, transient generation pressure (gen slot full / capacity / timeout), a generation-ordering refusal (pacing/spread — these dominate the volume during backfill; see `gen_order_gated`), or the distance filter; healed by re-declaration. Nonzero is normal under v17 — `superseded` rises with any backlog the 1 Hz declare outruns. The disk-miss flavors also count `service.miss_dropped` (soak law A5's term)
- `backpressure.queue_full` — send-queue overflow drops (a real loss signal, unlike the two above)
- `jvm.gc_time_ms` — GC overhead during benchmark

**Client `client.json`:**
- `avg_rtt_ms` — round-trip time for chunk requests
- `total_not_generated` — chunks the server permanently couldn't serve; ~0 on a gen-enabled server (transient pressure never answers NOT_GENERATED anymore)

## Soak Testing

Scenario-driven correctness harness: a real dedicated server + headless client execute a scripted timeline, both sides append JSONL diagnostic snapshots, and a Python checker enforces conservation invariants (request/delivery/generation/disk accounting) plus per-scenario expectations. Gated behind `-Dlss.soak.scenario=<path>` (server) and `-Dlss.soak=true` (client) — never activates in production. Design spec: `docs/planning/soak-test-design.md`.

### Quick Start

```bash
./scripts/soak.sh fresh-backfill    # Fresh superflat world — generation backfill; saves soak-worlds/base
./scripts/soak.sh warm-rejoin       # Two client runs — warm cache resync after a kick
./scripts/soak.sh dimension-trip    # Overworld → End → Overworld dimension boundaries
./scripts/soak.sh dirty-broadcast   # setblock → dirty broadcast → client re-request
./scripts/soak.sh all               # All 17 Fabric scenarios in order; stops at first failure
SOAK_PLATFORM=paper ./scripts/soak.sh all   # Paper port: fresh-backfill, warm-rejoin, dimension-trip, paper-dirty-falling-block
SOAK_PLATFORM=folia ./scripts/soak.sh all   # Folia: same four scenarios against a real Folia server (:paper:runFolia)
```

Further scenarios (same invocation): rate-limit-storm, disk-saturation, generation-disabled, generation-capacity-stress, bandwidth-throttle, cold-restart-resync, enabled-false, teleport-prune, dirty-range-filter, dirty-during-backfill, dirty-while-offline, clearcache-mid-session, dimension-rejoin-warm.

`rate-limit-storm` and `disk-saturation` keep their **historical names** but no longer test rate limiting — v17 has none:

- **`disk-saturation` is the harness's supersession proof.** `disk.saturated == 0` (the `hasHeadroom` gate must *prevent* what used to bounce — issue #32's root cause; **measured: 0** under threads:1) **plus** `service.superseded >= 100` (**measured: 420**, backlog high-water 760) — without the second, zero saturation would prove nothing, because a flood that never built a backlog cannot demonstrate absorption.
- **`rate-limit-storm` is on its THIRD premise** (the file name is kept — renaming touches six keyed tables). Under server-owned generation the client always declares the constant `WANT_SET_BUDGET` (its old `syncCap: 4` lever is a deleted knob), so it now pins **bounded transient-drop churn**: a small fresh disc converges through default gates with `service.superseded` stopping at convergence (ceiling 800 in `check_soak.py`) plus a generation-progress floor.
- **`generation-capacity-stress` pins the permanence guarantee negatively**: on a gen-ENABLED server `NOT_GENERATED` must NEVER reach the wire (`not_generated == 0` — a transient capacity/timeout leak would blank columns for the whole session), while `superseded >= 100` proves the repeated-miss churn loop (a miss that can't take the single gen slot drops silently, is re-declared, re-misses) actually ran. Law A5 carries the dedicated `service.miss_dropped` term for exactly these drops.

**The general rule: under this model, supersession is produced by slow *service* relative to the want-set** — the want-set is a constant, so a scenario that means to create contention must slow the service (disk threads / generation caps). A failure in these scenarios is a premise question first (did the config stop creating contention? did the headroom gate leak?) and a threshold question only after.

Scenarios needing a base world auto-run `fresh-backfill` first. warm-rejoin, dirty-while-offline, and dimension-rejoin-warm use two client runs. Exit code = checker exit code.

### Output Files

- `soak-results/<scenario>-<timestamp>/server.jsonl` — server snapshot/command/join/end event rows. Snapshots carry the data-capture fields: `service.{superseded,range_filtered,re_resolved,miss_dropped}`, `disk.memo_hits` (miss-memo rung hits — law A5's virtual not-founds), `players[].backlog`, `*_hw` high-water gauges (per-tick sampled), `dedup.groups`, `jvm.*`, `tscache.*`, `dirty.{marked,suppressed}_total`, `mspt_avg_window`; command rows carry `ok`.
- `soak-results/<scenario>-<timestamp>/client-run<N>.jsonl` — client snapshot/disconnect rows per run (incl. `ingest_failures`, `effective_lod`, `rtt.{p50,p95}_ms`)
- `soak-results/<scenario>-<timestamp>/verdict.json` — checker verdict (conservation laws + named checks)
- `soak-results/<scenario>-<timestamp>/report.md` — `soak_report.py` anomaly digest (auto-written; a lens, not a gate)

### How It Works

`soak.sh` stages world/cache/config per scenario, pre-validates the timeline (`check_soak.py --validate`), then starts `runSoakServer` and `runSoakClient` (no JFR). `SoakScenarioDriver` fires timeline commands at join-anchored offsets, snapshots every 5 s, and halts the server at scenario end; the soak client synchronously flushes its column cache before exiting. The checker evaluates deltas between verified-quiescent snapshots and writes `verdict.json`; any violation exits nonzero. `SOAK_PLATFORM=paper` runs the identical scenario timelines against a real Paper server (`:paper:runSoakServer`, a run-paper task fed by the dev-only `soakShadowJar` that retains the soak package) with the unchanged Fabric soak client and checker; Paper keeps its own base world at `soak-worlds/base-paper`. `SOAK_PLATFORM=folia` does the same against a real Folia server (`:paper:runFolia`, run-paper's Folia download task with `pluginsMode = INHERIT_NONE` — the default plugin-jar detection would add the soak-stripped release jar alongside the soak jar; base world `soak-worlds/base-folia`): the driver maps `save-all` to an acknowledged no-op (`"mapped": true` command-row key — Folia unregisters the command) and the staging writes a `bukkit.yml` with a 100-tick autosave so chunks still flush mid-run. Per-scenario client instrumentation: `-Psoak.probes=x:z,...` adds per-position timestamp probes to client snapshots; `-Psoak.clientActionAt=SECONDS:ACTION` fires one scripted client action (e.g. `60:clearcache`). warm-rejoin, dirty-while-offline, and dimension-rejoin-warm use two client runs.

### Key Files

- `scripts/soak.sh` — orchestrator (stage → validate → run → collect → check)
- `scripts/soak-scenarios/<name>.json` + `<name>-config.json` — driver timeline + sparse server-config overrides
- `scripts/check_soak.py` — stdlib Python invariant checker (`--validate` pre-flight, post-run laws, per-run completion gates — a missing server `end` row OR a missing client `disconnect` row means that JVM died mid-run, `client_run_completion_violations` — `--selftest` 140 in-memory pass/catch cases incl. all four oldest named checks, the disconnect gate, and the quiescence client mirror). **The harness is v17-only:** `players[].backlog` is a required schema field and `service.superseded`/`range_filtered` are required + monotonic, so it will correctly reject any pre-v17 recording — re-record rather than debug.
- `scripts/soak_report.py` — stdlib post-run anomaly digest (spikes/stalls, concerning-vs-mechanism counters, high-water marks, cadence/TPS, law margins, cross-identity audits); a lens, never a gate (`--strict` to exit nonzero on any anomaly; `--compare`, `--selftest`)
- `scripts/release_check.py` — release-jar safety gate (no dev-only benchmark/soak packages ship, incl. inside nested Jar-in-Jar entries and dev/vox/lss/common namespaces; stale-jar ambiguity guard + `--version` pinning; version expansion; mappings-namespace manifest; glob hygiene). Wired into `.github/workflows/build.yml` alongside the three `--selftest` runs.
- `scripts/lib/mc-run.sh` — shared server/client lifecycle helpers (sourced by soak.sh and benchmark.sh)
- `fabric/src/main/java/dev/vox/lss/benchmark/SoakScenarioDriver.java` — server-side timeline executor + JSONL snapshots
- `paper/src/main/java/dev/vox/lss/paper/soak/PaperSoakScenarioDriver.java` — Paper twin of the Fabric driver (Bukkit join anchors + 1-tick scheduler, same JSONL contract)
- `paper/src/main/java/dev/vox/lss/paper/soak/PaperSoakMetricsExporter.java` — Paper twin of the server-side exporter (same snapshot schema)
- `paper/src/main/java/dev/vox/lss/paper/PaperSoakBridge.java` — reflective gate; the soak package is excluded from the release shadowJar
- `fabric/src/main/java/dev/vox/lss/BenchmarkBridge.java` — reflective gate to the dev-only harness package (excluded from release jars)

## Key Patterns

- **Java 25** target for Fabric and Paper (`options.release = 25`), **Java 21** for common. Uses `var`, records, sealed-pattern `instanceof`, switch expressions.
- **Payloads:** Fabric uses `StreamCodec` with lambda encode/decode registered in `LSSNetworking`; Paper uses raw `FriendlyByteBuf` in `PaperPayloadHandler`. Both produce identical wire bytes.
- **Raw chunk shipping:** Server serializes MC-native `LevelChunkSection` data (block states + biomes via `section.write(buf)`) plus light `DataLayer` nibbles and sends. MC's built-in zlib network compression handles packet compression. No server-side voxelization or caching — client receives MC objects directly.
- **Off-thread processing:** Main server thread serializes loaded chunks into `LoadedColumnData` (pre-serialized bytes); a dedicated processing thread prepares payloads and schedules sends via `OffThreadProcessor`. `ColumnTimestampCache` provides in-memory up-to-date checks.
- **Folia (Paper module, experimental):** the service tick runs on the `GlobalRegionScheduler` (main thread on Paper, the single global-region thread on Folia — same pump semantics both ways); handshake registration and PlayerQuit arrive on region threads and go through a lifecycle mailbox drained at the top of `tick()`; generated chunks are serialized inside the Moonrise `scheduleChunkLoad` completion (the owning region thread — the chunk can unload before a hop to the global tick). Loaded-chunk probing on Folia runs on the chunk's owning region thread (the player's `EntityScheduler`, gated by `Bukkit.isOwnedByCurrentRegion`) rather than the global pump, so palettes are read on their owner; a one-tick hold-release at batch granularity aligns each declared batch with its region-published probe results in the same snapshot (release-then-take, never take-then-release — see `PaperRequestProcessingService`). `FoliaWiringContractTest` scans every production class's constant pool (nested classes included) so no `BukkitRunnable`/`BukkitScheduler` reference creeps back in (they throw on Folia). `/reload` is unsupported on Folia. LSS disk reads go through Moonrise (`loadDataAsync` at `Priority.LOW`, see `PaperChunkDiskReader`) — called from the LSS reader pool, off any region thread, which is safe because region files are not regionised. Release notes for Folia-affecting changes should mention the experimental status.
- **Thread safety** via `ConcurrentHashMap`, `AtomicLong`/`AtomicInteger`, `volatile`, `CopyOnWriteArrayList`
- **No compile-time optional deps** — all mod compat uses `MethodHandle` bridges (MC types must use direct class literals, not `Class.forName()`, due to Fabric remapping)
- **Mappings:** Mojang official (not Yarn). Paper uses Mojang mappings natively via paperweight-userdev.
- **Package:** `dev.vox.lss` (Fabric), `dev.vox.lss.paper` (Paper), `dev.vox.lss.common` (shared)

## Releasing

Releases are triggered by pushing an **annotated tag** (`git tag -a`). The tag annotation message becomes the release notes on both GitHub and Modrinth. The CI workflow extracts it automatically.

### Tagging a Release

`main` is branch-protected (changes must land via PR; tags are **not** protected). Full flow:

1. Review commits since the last tag: `git log $(git describe --tags --abbrev=0)..HEAD --oneline`
2. **Pre-flight the exact release build locally** before tagging — the tag triggers an irreversible GitHub + Modrinth publish, so it must be green first:
   `CI=true ./gradlew :fabric:build -x runClientGameTest :paper:test :paper:shadowJar -Pmod_version=<version> && python3 scripts/release_check.py --version <version>`
   (`release_check.py` must print `OK`; `--version` pins the check to the jars just built — stale jars in build/libs otherwise fail the run; `:fabric:build` runs Tier 1 + Tier 2, `:paper:test` gates the Paper jar. CI runs Tier 3 (`:fabric:runClientGameTest`) as a separate build.yml job — check it is green on the release commit before tagging.)
3. Get the release commit onto `main` via PR (protected branch): push the release branch, `gh pr create --base main`, then `gh pr merge --merge`. Use **`--merge`** (a merge commit) — `--squash`/`--rebase` rewrite SHAs and orphan the tag.
4. Write release notes to a file (format below) and create the annotated tag with **`--cleanup=verbatim`** so the `###` headers survive:
   `git tag -a v<version> -F <notes-file> --cleanup=verbatim`
   Verify before pushing: `git for-each-ref --format='%(contents)' refs/tags/v<version>` (are the headers present?).
5. Push the tag — this triggers `release.yml`: `git push origin v<version>`
6. Watch CI (`gh run watch <id> --exit-status`); after it publishes, **verify the rendered notes** on GitHub (`gh release view v<version>`) and Modrinth — see Pitfalls.

### Release Pitfalls (all hit v0.4.0 — see also memory `release-tag-notes-gotchas`)

- **`###` headers stripped from the tag message.** `git tag` defaults to `--cleanup=strip`, which deletes `#`-leading lines as comments. Always pass `--cleanup=verbatim`.
- **CI can publish a commit-dump instead of the annotation.** `actions/checkout` leaves a *lightweight* tag on tag-triggered runs, so the extract step's `objecttype` reads `commit` and falls back to `git log` commit subjects. Fixed in `release.yml` by force-fetching the annotated tag (`git fetch --force origin "refs/tags/$TAG:refs/tags/$TAG"`); if the notes still render wrong, fix GitHub instantly with `gh release edit v<version> --notes-file <file>`.
- **Modrinth changelog can't be fixed locally.** `MODRINTH_TOKEN` is a CI-only secret, so a wrong Modrinth changelog must be edited by hand on the web — on BOTH projects since v0.7.0 (`lKiXKLvv` and `84zcagOb`/voxy-server-side). Keep a short, player-focused variant of the notes ready for this.
- **Never `gh run rerun` a partially-published release.** A re-run rebuilds byte-different jars, silently replaces the GitHub release assets, and creates DUPLICATE Modrinth versions (nothing rejects a second `v<X>+fabric+mc<Y>`). Red BEFORE the gh-release step = nothing published, full re-run safe. Red AFTER any publish step (the likely case: the voxy step 403s on missing membership) = recover by hand-uploading the GitHub-attached jars to the channel that missed.
- **Verify voxy-server-side publish access BEFORE tagging** — authenticated, with the real token: `curl -H "Authorization: <MODRINTH_TOKEN>" https://api.modrinth.com/v2/project/84zcagOb` must return 200 (proves project id AND membership in one call; unauthenticated calls 404 while the project is delisted/draft). Do NOT smoke-test with a throwaway pre-release tag — `release.yml` triggers on every `v*` tag and would irreversibly publish the LSS jars to the real project.
- **A gametest can flake on CI** — see **Known Test Flakes & Environmental Failures** (in the Test Commands section) for the full catalog. Since 2026-07 `build.yml` retries the gametest tiers once automatically (retries surface as `::warning::` annotations; first-attempt evidence lands in the `gametest-flake-evidence-*` artifacts), so a red job already survived two attempts — diagnose from the artifacts before `gh run rerun <id> --failed`.

### Release Notes Format

Write for **Minecraft server admins and mod users**, not developers. Use markdown with category headers — omit empty categories:

```
### Bug Fixes

- **Short summary** — What changed and why it matters to users.

### New Features

- **Short summary** — What it does and how users benefit.

### Configuration

- **Short summary** — New/changed config options with defaults.

### Performance

- **Short summary** — What improved and the user-visible impact.
```

Rules:
- Each item: bold summary + dash + 1-2 sentence explanation
- Mention Fabric or Paper when a change is platform-specific; omit qualifier when it affects both
- Folia-affecting items must mention Folia support's **experimental** status (the label stays
  until concurrent multi-region soak validation — see the spec's exit criterion)
- Skip internal-only changes (CI, README, refactoring) unless they have user-visible impact
- Use present tense ("Fixes X" not "Fixed X" — GitHub/Modrinth display these as current release notes)
- No version heading — the tag name is displayed as the title automatically

### Example

```bash
git tag -a v0.2.4 -m "$(cat <<'EOF'
### Bug Fixes

- **Fixes infinite generation loop in The End** — All-air chunks were treated as missing, causing endless re-generation in void dimensions.

### New Features

- **Purpur server support** — Paper builds now work on Purpur servers.

### Configuration

- **Configurable timestamp cache size** — New `perDimensionTimestampCacheSizeMB` option (default 32 MB) controls memory used for chunk freshness tracking.
EOF
)"
```
