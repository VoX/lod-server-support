# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

LOD Server Support (LSS) — distributes LOD chunk data from servers to clients over a custom networking protocol. Supports Fabric (client + server) and Paper/Folia (server only — one plugin jar, `folia-supported: true`; Folia is experimental: single-player soak validated, concurrent multi-region ingress untested — the exit criterion for dropping the label). Clients request distant chunks individually; the server reads them from disk or memory and streams serialized sections back, enabling LOD rendering mods to display terrain beyond vanilla render distance.

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
- `paper/build/libs/lod-server-support-paper.jar` — Paper/Folia plugin (server only, shadow JAR; one artifact serves Paper, Purpur, and Folia via `folia-supported: true`)

CI builds (env `CI=true`) name the jars `lod-server-support-<platform>-<mod_version>+<minecraft_version>.jar` (e.g. `lod-server-support-fabric-0.6.0+26.2.jar`); the release workflow feeds `mod_version` from the tag. Local dev builds keep the stable unversioned names.

## Test Commands

```bash
./gradlew :fabric:test -x runGameTest -x runClientGameTest  # Tier 1: JUnit unit tests only (fast, ~7s)
./gradlew :fabric:runGameTest                                # Tier 2: server gametests (starts dedicated server, ~13s)
./gradlew :fabric:test -x runClientGameTest                  # Tier 1 + 2 combined
./gradlew :fabric:runClientGameTest                          # Tier 3: client gametests (starts integrated server + client)
./gradlew :fabric:build -x runClientGameTest                 # Full build + Tier 1 + 2 tests
./gradlew :paper:test                                       # Paper JUnit tests (wire parity, NBT serialization, config)
```

Most tests live in the Fabric module. Paper has Tier 1 JUnit tests as well (`./gradlew :paper:test`, ~243 tests; also run by CI) — Fabric/Paper wire parity, payload edge frames, NBT section serialization (incl. a golden cross-module byte corpus + live-vs-NBT parity), the shared handshake gate, config validation + malformed-file tolerance, world-handler event reflection, the request-service / broadcaster / generation-service / disk-reader twins driven through injection seams, plugin enable-plan/dispatch/handshake glue + plugin.yml contract (incl. `folia-supported: true`), the Folia seams (lifecycle mailbox incl. foreign-thread visibility, the `FoliaSupport` platform probe, the soak driver's save-all mapping, the generation completion-thread extraction ladder, the experimental regionized loaded-chunk probe scheduling — `RegionProbeSchedulingTest`: EntityScheduler hand-off, region-ownership gating, one-tick hold-release alignment, dimension-change discard, departed-player sweep), `FoliaWiringContractTest` (constant-pool scan of every production class, nested classes included: no legacy-scheduler references, lifecycle routed through the mailbox), exporter schema parity, and command output. Paper runtime behavior is validated live by the soak harness on both Bukkit platforms (`SOAK_PLATFORM=paper` / `SOAK_PLATFORM=folia ./scripts/soak.sh all` — fresh-backfill, warm-rejoin, dimension-trip, paper-dirty-falling-block against a real Paper / Folia server).

### Test Architecture

- **Tier 1** (`fabric/src/test/java/dev/vox/lss/`, ~592 tests): JUnit 5 tests via `fabric-loader-junit`. Tests position packing, bandwidth limiter, config validation + GSON whole-file-fallback semantics, column cache store (pre-v4 discard / path-sanitization / oversize / corruption guards), column state map, in-flight tracker, request queue, column timestamp cache (per-dimension isolation / eviction age / save atomicity), dirty tracking and content filtering (fail-open + zero-hash sentinel), slot admission and the off-thread processor mailbox (incl. same-UUID re-registration survival), payload codecs + protocol-constant envelope, Fabric/Paper wire parity, NBT section serialization + a golden cross-module byte corpus + section-light defaults, negative/oversized payload edge cases, the LodRequestManager request-loop contract (untracked-response gates, dimension guard, drain non-starvation, ingest-failure unstamping, send-failure mark-restoration, tick decomposition, RTT distribution), the extracted client `ClientSessionGate` (handshake/session-config/disconnect ladder), `ClientColumnProcessor` decode drain (silent-clear paths report through ingest-failure), the reflective Voxy bridge (full report/latch/rethrow ladder, stubbed), spiral-scanner budget scaling and retry reset, IncomingRequestRouter conservation + clientTimestamp routing + the honest re-resolution ladder + clock-skew, cross-player dedup fan-out, the disk-reader submit envelope + late-delivery drop + bounded shutdown, OffThreadProcessor disk-result delivery and lifecycle + the invalidation-overtake stale guard (the disk twin, the per-player generation twin swept on player removal, the error-retry replay that must not un-taint a consumed outcome, and the multi-holder taint invariant), send-action batching, the shared HandshakeGate ladder, client column decode + LSSApi dispatch, the data-capture exporter schema contracts (both platforms), DiagnosticsFormatter golden lines, the column cache pre-v4 discard-not-migrate gate, the VoxelColumn receiver glue (held-before ordering + authoritative 0-section clears), the gametest-entrypoint listing contract, and malformed-config-file tolerance.
- **Tier 2** (`fabric/src/gametest/java/dev/vox/lss/test/`): 55 Fabric server gametests across 7 classes (all listed in the `fabric-gametest` entrypoint of `fabric/src/gametest/resources/fabric.mod.json` — a new class MUST be added there or it compiles but never runs; `GameTestEntrypointContractTest` in Tier 1 enforces the listing) — `LSSGameTests` (service activation, diagnostics, command registration, config loading), `ServiceLifecycleGameTests` (player registration/cleanup, in-memory probe without dirty-filter seeding, batch-request distance guards incl. extreme-coord overflow safety, idempotent shutdown), `SerializerParityGameTests` (disk-read vs live serializer byte parity, DirtyContentFilter re-save suppression, End-void all-air sentinel), `GenerationLifecycleGameTests` (generation caps/piggybacking/timeouts, dimension-change re-registration), `CommandGameTests` (`/lsslod` permission gating + stats/diag dispatch rendering + exporter/formatter agreement), `TwoPlayerGameTests` (two-player dedup convergence, bandwidth fairness + idle dilution, vanilla-player-without-handshake invisibility, edited-column broadcast fan-out to two holders), `RegionFaultGameTests` (corrupt-region containment: garbage-zlib resolves not-found, the read pool survives). The in-game runner additionally counts vanilla's built-in `minecraft:always_pass` test instance.
- **Tier 3** (`fabric/src/gametest/java/dev/vox/lss/test/LSSClientGameTests.java`): End-to-end client-server flow test. Validates handshake, session config, spiral scanning, chunk section receipt, decoded section content asserted at a registered `LSSApi` consumer (flat-world block layers, off-render-thread dispatch), and the ingest-failure recovery loop (a consumer rejecting a column — by `LSSApi.reportIngestFailure` or by throwing — gets it re-served end-to-end). Fabric Loom handles headless rendering automatically.

The system property `-Dlss.test.integratedServer=true` (set in fabric/build.gradle for gametest JVMs) allows `RequestProcessingService` to activate on integrated servers during testing.

### Known Test Flakes & Environmental Failures

**This section is the canonical home for known CI test flakes and environment-specific test failures — keep it up to date, and record all similar findings here.** When a test failure is diagnosed as a flake or an environment quirk (not a code regression), add an entry here so the next contributor/session **re-runs rather than chases a phantom regression**. Each entry gives the test, the symptom, why it's benign, and the action. (These are also mirrored in the assistant's project memory, but this file is the in-repo source of truth.)

- **`RegionFaultGameTests` (Tier 2) — environmental on some dev boxes.** `region_fault_..._corrupt_region_chunk_resolves_as_contained_error_and_reader_survives` can fail (`errors=1 ... not_found=0 on tick 1202`) on constrained / WSL2 machines **regardless of load** (reproduces with all changes stashed) yet passes in clean CI. The corrupt-region triage is version-robust (a broad `catch (Exception)` resolves to not-found). Action: trust clean CI — a local red here is not a regression.
- **`LSSClientGameTests` superflat section count (Tier 3) — hardened in v0.6.1 (was a serialization-window flake).** `assertDecodedFlatWorldContent` used to fail with *"superflat columns must decode to exactly the one non-air section, got 24"* when a column was serialized during unsettled spawn-prep (its air sections not yet culled by `SectionSerializer`'s `hasOnlyAir` / block-light filter) and that stale delivery lingered in `recorder.snapshot()`. It flaked on the v0.6.0 and v0.6.1 PR CI (twice in a row on the latter — re-running did not clear it). The assertion now checks the content section (sectionY -4) and requires any extra sections to be **air-only** (sampled block states — the extras' blocks are genuinely air, only cull metadata/light differed), tolerating the transient un-culled sections while still catching real decode bugs. If a *new* "expected exactly one content section" or "must be air-only" variant appears, treat it as a real decode regression, not a flake. (`release.yml` runs `-x runClientGameTest`, so Tier 3 never gated the publish regardless.)
- **`TwoPlayerGameTests` fan-out (Tier 2) — probe-race flake on 2-core CI.** `editedColumnPropagatesToBothHoldersThroughBroadcastFanout` step 1 is a high-rate probe-race that can miss on starved 2-core runners. Action: re-run the failed job; it does not block a release.
- **`GenerationLifecycleGameTests` (Tier 2) — cold-gen / serialization timing flake.** Can fail on CI on identical, already-green code. Action: re-run before assuming a regression.
- **Soak checker (`check_soak.py`) — benign timing / light-settle drift on constrained boxes.** Occasional dirty-resave / quiescence drift is tolerated by design (the checker carries settle tolerances); treat as a re-run, not a rewrite.

## Local Test Servers (manual play)

```bash
./test-server.sh              # set up + run all three: Fabric :25565, Paper :25566, Folia :25567
./test-server.sh run-folia    # one platform only (also run-fabric, run-paper)
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

### Networking Protocol (v16)

Batch model with 6 payload types. Fabric uses `LSSNetworking` with Fabric `StreamCodec`;
Paper uses `PaperPayloadHandler` with raw `FriendlyByteBuf` encoding. Both produce identical wire format.

**C2S:** `HandshakeC2SPayload` (capabilities bitmask) → `BatchChunkRequestC2SPayload` (batch of packed position + clientTimestamp pairs)

**S2C:** `SessionConfigS2CPayload` (enabled/LOD distance/concurrency limits/generation toggle) → `VoxelColumnS2CPayload` (coords + dimension + MC-native sections + columnTimestamp) → `BatchResponseS2CPayload` (batch of responseType + packed position pairs — covers rate-limited, up-to-date, and not-generated) → `DirtyColumnsS2CPayload` (server-pushed change notifications)

Requests are keyed by packed chunk position end-to-end (no request ids): every response is a statement about a position, so late or duplicate responses are idempotent and a timed-out client request self-heals when the answer eventually arrives. Delivery honesty: the server's per-player served-set (diskReadDone) answers re-requests with up_to_date only when the client claims data (ts>0); a ts<=0 re-request declares "I have nothing" and re-resolves honestly (bounced rate-limited while the payload is still in the send pipeline), so any post-resolution loss — send-queue drop, decode failure, consumer rejection — self-heals instead of becoming a permanent hole. Consumers report rejected columns via `LSSApi.reportIngestFailure`, which forgets the client's received-stamp and schedules a re-request.

Flow: client handshakes with capabilities bitmask (CAPABILITY_VOXEL_COLUMNS set if LSSApi has consumers) → server sends session config with concurrency limits → client scans in expanding spiral every second (20 ticks) and sends batched requests with clientTimestamp (-1 for unknown/first request, 0 for generation request, >0 for resync) → server routes by timestamp: sync-on-load path (clientTimestamp > 0 or -1) acquires a concurrency slot then reads disk, generation path (clientTimestamp == 0) acquires a slot then generates; a full limiter bounces the request with rate-limited and the client retries on a later scan → server batches lightweight responses (rate-limited, up-to-date, not-generated) per tick; column data payloads are sent individually. After initial scan, server periodically pushes `DirtyColumnsS2CPayload` listing changed columns, client re-requests only those.

Chunk coordinates are packed as `((long)chunkX << 32) | (chunkZ & 0xFFFFFFFFL)`.

### Common Module (`common/`)

- `LSSConstants` — protocol version, channel IDs, wire format limits, status codes
- `LSSLogger` — SLF4J logging wrapper
- `SharedBandwidthLimiter` — fair per-tick bandwidth allocation across active players (global + per-player caps)
- `PositionUtil` — chunk coordinate packing/unpacking
- `DiagnosticsFormatter` — formats diagnostic data for `/lsslod diag` output
- `ColumnTimestampCache` — in-memory per-dimension `(packedXZ → epochSeconds)` timestamp cache for up-to-date checks without disk IO
- `OffThreadProcessor` — abstract base for main-thread → processing-thread handoff (prepares pre-serialized column data off-thread, routes requests via `IncomingRequestRouter`). Holds the invalidation-overtake stale guard: an edit that overtakes an in-flight disk read (tracked via its dedup group) or generation (tracked per-player in `generationInFlight`, swept on player removal) marks the buffered result stale so its delivery skips the timestamp stamp + `diskReadDone` and the client re-resolves. On a failed cycle, `requeueLosslessEvents` re-queues only the events whose phase did not complete (phase-completion flags) so a consumed generation outcome is never re-delivered un-tainted.
- `IncomingRequestRouter` — routes incoming chunk requests: checks timestamps for up-to-date, probes loaded chunks, dispatches disk reads (registering the generation in-flight guard on the direct-generation path), with cross-player dedup via `DedupTracker`
- `PendingRequest` / `SlotType` — derived admission: a pending-map entry IS the held sync/generation slot (no separate limiter object)
- `DirtyColumnTracker` — thread-safe tracker of per-dimension dirty chunk positions, drained by broadcasters
- `SendActionBatcher` — reusable per-tick accumulator for batching `SendAction` responses by player
- `HandshakeGate` — pure decision ladder for the C2S handshake (version mismatch → no reply; no consumer → reply without registering; disabled → advertise disabled; else register), shared by `LSSServerNetworking` and `LSSPaperPlugin` so reply/registration policy cannot drift between platforms
- `JsonConfig` / `ServerConfigBase` — GSON config base classes; `ServerConfigBase` holds the server config fields, defaults, and clamps shared verbatim by Fabric and Paper

### Fabric Server-Side (`fabric/`)

- `RequestProcessingService` — orchestrates per-player processing, ticks on server thread, broadcasts dirty columns
- `PlayerRequestState` — per-player request state, pending-request slot admission, send queue, metrics
- `ChunkDiskReader` — async region file reader with configurable thread pool
- `FabricOffThreadProcessor` — extends `OffThreadProcessor`; main thread probes loaded chunks via `SectionSerializer`, processing thread prepares payloads and schedules sends
- `SectionSerializer` — serializes loaded MC `LevelChunkSection` + light data into wire-format bytes using MC's native `section.write(buf)`
- `NbtSectionSerializer` — parses region file NBT into `LevelChunkSection` objects, then serializes them (used by `ChunkDiskReader`)
- `ChunkGenerationService` — ticket-based chunk generation, auto-triggered on disk-read "not found"
- `DirtyColumnBroadcaster` — periodically pushes dirty column notifications to connected players
- `DirtyContentFilter` — FNV-1a hash of served column bytes per position; chunk saves mark a column dirty only when LOD-visible content actually changed (filters vanilla's ~10s metadata-only re-saves, e.g. inhabitedTime). Fabric only — Paper's dirty detection is event-driven.
- `LSSNetworking` — `PayloadTypeRegistry` registration for all C2S and S2C payload types
- `LSSServerNetworking` — server-side event listeners, `RequestProcessingService` lifecycle (started via `ServerLifecycleEvents.SERVER_STARTED`)
- `AccessorServerChunkCache` (mixin) — exposes `ChunkMap` for disk reads
- `AccessorChunkMap` / `ChunkMapSaveHook` (mixins) — hooks chunk saves to feed `DirtyColumnTracker`
- `IntegratedServerLanHook` (client mixin) — starts `RequestProcessingService` when an integrated server is published to LAN

### Paper Server-Side (`paper/`)

- `PaperRequestProcessingService` — same orchestration as Fabric, sends via Plugin Messaging instead of Fabric networking; ticks on the GlobalRegionScheduler pump (main thread on Paper, the global-region thread on Folia) and drains a cross-thread lifecycle mailbox (`enqueueRegister`/`enqueueRemove` — handshakes and quits arrive on region threads on Folia) at the top of `tick()`. On Folia only (`regionizedProbing = FoliaSupport.IS_FOLIA`, experimental) the loaded-chunk probe runs on the chunk's OWNING region thread via the player's `EntityScheduler` behind `Bukkit.isOwnedByCurrentRegion`, instead of on the pump — with a one-tick hold-release of fresh requests (`heldForProbe` + `reinjectIncomingRequest`) so a request and its region-published probe result meet in the same snapshot. `RegionTaskScheduler`/`RegionOwnershipCheck` are injection seams for the JUnit tests
- `PaperPlayerRequestState` — per-player state adapted for `byte[]` payloads instead of `CustomPacketPayload`
- `PaperChunkDiskReader` — async disk reader using direct NMS `ChunkMap` access (no mixin needed)
- `PaperOffThreadProcessor` — extends `OffThreadProcessor`; same main-thread/off-thread split as Fabric
- `PaperSectionSerializer` — Paper-side serializer using NMS `LevelChunkSection.write(buf)` + light data
- `PaperNbtSectionSerializer` — parses region file NBT into `LevelChunkSection` objects, then serializes them (used by `PaperChunkDiskReader`)
- `PaperChunkGenerationService` — async chunk generation via Paper's `World.getChunkAtAsync()` API, auto-triggered on disk-read "not found"; serializes the generated chunk inside the completion callback (`completeAsyncLoad` — on Folia that is the owning region thread and the chunk can unload before a hop to the global tick) and hands only the immutable `LoadedColumnData` to the pump
- `FoliaSupport` — platform probe (`Class.forName` on Folia's regionizer class); used only where Folia removes functionality outright (e.g. the soak driver's save-all mapping)
- `PaperPayloadHandler` — encodes S2C / decodes C2S payloads using same wire format as Fabric
- `PaperCommands` — Bukkit `CommandExecutor` for `/lsslod stats` and `/lsslod diag`
- `PaperConfig` — GSON JSON config (same defaults/validation as Fabric)
- `PaperDirtyColumnBroadcaster` — broadcasts dirty column notifications to connected players (same as Fabric's `DirtyColumnBroadcaster`)
- `PaperWorldHandler` — registers configurable Bukkit event listeners for dirty chunk detection via reflection-based position extraction
- `PaperSoakBridge` — reflective gate from `LSSPaperPlugin` into the dev-only `dev.vox.lss.paper.soak` package (soak scenario driver + metrics exporter twins; excluded from the release shadowJar, retained by `soakShadowJar` for `SOAK_PLATFORM=paper` and `SOAK_PLATFORM=folia` runs)

### Client-Side

- `LodRequestManager` — orchestrates the client request loop: drip-feeds queued requests each tick, applies in-flight timeouts and rate-limit retries, handles dirty column re-requests and pruning on movement
- `SpiralScanner` — expanding Chebyshev ring scan (20-tick cadence); owns scan policy: request budget, queue-pressure scaling, rate-limit backoff
- `ColumnStateMap` — single owner of per-column client state (timestamps + dirty/retry/validated marks); its `classify` ladder decides whether a position needs a request
- `InFlightTracker` / `RequestQueue` — pending requests keyed by packed position; accepted-position queue between scanner and sender
- `ClientColumnProcessor` — off-render-thread decode of received column payloads before dispatch to consumers
- `ColumnCacheStore` — per-server per-dimension binary cache of column positions and timestamps (enables resync across sessions)
- `LSSClientNetworking` — packet handlers, dispatches sections to `LSSApi` consumers

### Public API (`LSSApi`)

LOD rendering mods register a `VoxelColumnConsumer` via `LSSApi.registerColumnConsumer()` to receive `VoxelColumnData` (MC-native `LevelChunkSection` + `DataLayer` light data per section, with coordinates). Consumer list is `CopyOnWriteArrayList` for thread safety. The client sets `CAPABILITY_VOXEL_COLUMNS` in the handshake only if consumers are registered.

### Compatibility

`VoxyCompat` uses `MethodHandle` reflection to call Voxy's `rawIngest` API at runtime — zero compile-time dependency, only 2 core MethodHandles (`WorldIdentifier.of` + `VoxelIngestService.rawIngest`). MC types (`LevelChunkSection`, `DataLayer`) must be referenced via direct class literals (not `Class.forName()`) due to Fabric's runtime remapping. `ModCompat` checks `FabricLoader.isModLoaded()` before attempting.

### Configuration

**Fabric:** GSON configs built on the shared `common/` base classes (`JsonConfig` loader + `ServerConfigBase`, which holds the server fields/defaults/clamps verbatim for both platforms). Two configs auto-created in `config/`:
- `lss-server-config.json` — bandwidth caps, LOD distance, disk reader threads, send queue limits, generation toggle, dirty broadcast interval, concurrency limits
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
5. Client metrics in `benchmark-results/client.json` — key: `avg_rtt_ms`, `columns_received`, `total_rate_limited`

### Output Files

- `benchmark-results/server.json` — server throughput, sources, disk reader, generation, rate limiting, bandwidth, JVM stats
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
- `rate_limiting.sync_rate_limited` / `gen_rate_limited` — requests rejected by rate limiter
- `jvm.gc_time_ms` — GC overhead during benchmark

**Client `client.json`:**
- `avg_rtt_ms` — round-trip time for chunk requests
- `total_not_generated` — chunks the server couldn't serve (fresh world only)
- `total_rate_limited` — client-observed rate limiting

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

Scenarios needing a base world auto-run `fresh-backfill` first. warm-rejoin, dirty-while-offline, and dimension-rejoin-warm use two client runs. Exit code = checker exit code.

### Output Files

- `soak-results/<scenario>-<timestamp>/server.jsonl` — server snapshot/command/join/end event rows. Snapshots carry the round-2 data-capture fields: `service.{sync_rate_limited,gen_rate_limited,re_resolved}`, `*_hw` high-water gauges (per-tick sampled), `dedup.groups`, `jvm.*`, `tscache.*`, `dirty.{marked,suppressed}_total`, `mspt_avg_window`; command rows carry `ok`.
- `soak-results/<scenario>-<timestamp>/client-run<N>.jsonl` — client snapshot/disconnect rows per run (incl. `ingest_failures`, `effective_lod`, `request_queue`, `rtt.{p50,p95}_ms`)
- `soak-results/<scenario>-<timestamp>/verdict.json` — checker verdict (conservation laws + named checks)
- `soak-results/<scenario>-<timestamp>/report.md` — `soak_report.py` anomaly digest (auto-written; a lens, not a gate)

### How It Works

`soak.sh` stages world/cache/config per scenario, pre-validates the timeline (`check_soak.py --validate`), then starts `runSoakServer` and `runSoakClient` (no JFR). `SoakScenarioDriver` fires timeline commands at join-anchored offsets, snapshots every 5 s, and halts the server at scenario end; the soak client synchronously flushes its column cache before exiting. The checker evaluates deltas between verified-quiescent snapshots and writes `verdict.json`; any violation exits nonzero. `SOAK_PLATFORM=paper` runs the identical scenario timelines against a real Paper server (`:paper:runSoakServer`, a run-paper task fed by the dev-only `soakShadowJar` that retains the soak package) with the unchanged Fabric soak client and checker; Paper keeps its own base world at `soak-worlds/base-paper`. `SOAK_PLATFORM=folia` does the same against a real Folia server (`:paper:runFolia`, run-paper's Folia download task with `pluginsMode = INHERIT_NONE` — the default plugin-jar detection would add the soak-stripped release jar alongside the soak jar; base world `soak-worlds/base-folia`): the driver maps `save-all` to an acknowledged no-op (`"mapped": true` command-row key — Folia unregisters the command) and the staging writes a `bukkit.yml` with a 100-tick autosave so chunks still flush mid-run. Per-scenario client instrumentation: `-Psoak.probes=x:z,...` adds per-position timestamp probes to client snapshots; `-Psoak.clientActionAt=SECONDS:ACTION` fires one scripted client action (e.g. `60:clearcache`). warm-rejoin, dirty-while-offline, and dimension-rejoin-warm use two client runs.

### Key Files

- `scripts/soak.sh` — orchestrator (stage → validate → run → collect → check)
- `scripts/soak-scenarios/<name>.json` + `<name>-config.json` — driver timeline + sparse server-config overrides
- `scripts/check_soak.py` — stdlib Python invariant checker (`--validate` pre-flight, post-run laws, per-run completion gates — a missing server `end` row OR a missing client `disconnect` row means that JVM died mid-run, `client_run_completion_violations` — `--selftest` 127 in-memory pass/catch cases incl. all four oldest named checks and the disconnect gate)
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
- **Folia (Paper module, experimental):** the service tick runs on the `GlobalRegionScheduler` (main thread on Paper, the single global-region thread on Folia — same pump semantics both ways); handshake registration and PlayerQuit arrive on region threads and go through a lifecycle mailbox drained at the top of `tick()`; generated chunks are serialized inside the `getChunkAtAsync` completion (the owning region thread — the chunk can unload before a hop to the global tick). Loaded-chunk probing on Folia runs on the chunk's owning region thread (the player's `EntityScheduler`, gated by `Bukkit.isOwnedByCurrentRegion`) rather than the global pump, so palettes are read on their owner; a one-tick hold-release of fresh requests aligns each request with its region-published probe result in the same snapshot. `FoliaWiringContractTest` scans every production class's constant pool (nested classes included) so no `BukkitRunnable`/`BukkitScheduler` reference creeps back in (they throw on Folia). `/reload` is unsupported on Folia. Release notes for Folia-affecting changes should mention the experimental status.
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
- **Modrinth changelog can't be fixed locally.** `MODRINTH_TOKEN` is a CI-only secret, so a wrong Modrinth changelog must be edited by hand on the web (project `lKiXKLvv`). Keep a short, player-focused variant of the notes ready for this.
- **A gametest can flake on CI** — see **Known Test Flakes & Environmental Failures** (in the Test Commands section) for the full catalog (`GenerationLifecycleGameTests` cold-gen timing, `TwoPlayerGameTests` fan-out, `LSSClientGameTests` superflat section count, `RegionFaultGameTests`). If a post-merge `main` build fails on identical, already-green code, re-run it (`gh run rerun <id> --failed`) before assuming a regression.

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
