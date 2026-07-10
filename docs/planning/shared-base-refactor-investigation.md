# Investigation: Fabric/Paper shared-base refactor (review item #15)

**Question:** what would it take to collapse the ~mirrored Fabric/Paper server classes into a
shared base?

**Short answer:** the orchestration is *already* shared in `common/`. The residual duplication
is real but uneven (serializers ~94% identical, generation ~57%, networking ~36%), and the only
thing keeping the near-identical leaf classes apart is the **build/module boundary**, not the
logic. A full "shared base" refactor is feasible but high-effort/high-risk for a modest,
serializer-concentrated payoff. **Recommended:** ship parity tests first (catches the actual
risk — silent drift), then optionally hoist the highest-dup leaf classes if an
architectury-style build is adopted. Do **not** unify the generation service, commands, or
networking — they are genuinely platform-specific.

---

## 1. What is already shared

The hard part is done. `common/` (pure Java, no Minecraft on its classpath) already holds the
entire orchestration/algorithm layer, used by both platforms:

- `OffThreadProcessor<PlayerState, ReadResult>` — the main→processing-thread engine, generic on
  the platform's player-state and disk-result types. Platform subclasses implement only the
  MC-touching hooks: `pollDiskResult`, `pollGenerationResult`, `submitDiskRead`,
  `enqueueResultPayloads`, `buildAndEnqueueColumnPayload`.
- `AbstractPlayerRequestState<Q>` — per-player state, generic on the queued-payload type `Q`
  (`CustomPacketPayload` on Fabric, `byte[]` on Paper). All queue/rate/pending logic lives here.
- `IncomingRequestRouter`, `DedupTracker`, `RateLimiterSet`, `ConcurrencyLimiter`,
  `SharedBandwidthLimiter`, `ColumnTimestampCache`, `DirtyColumnTracker`, `SendActionBatcher`,
  `ProcessingDiagnostics`, `PositionUtil`.

So the duplication that remains is in the **leaf classes that call Minecraft/NMS directly**.

## 2. Measured duplication (this branch)

Normalized diff (package/import/comment/blank lines stripped, whitespace-insensitive),
fabric vs paper:

| Twin pair | fabric LOC | paper LOC | diff lines | similarity | shareable? |
|---|---:|---:|---:|---:|---|
| SectionSerializer | 65 | 66 | 7 | **94%** | yes — near-identical |
| NbtSectionSerializer | 122 | 114 | 17 | **93%** | yes |
| PlayerRequestState | 46 | 42 | 10 | **89%** | already shares via `AbstractPlayerRequestState<Q>` |
| DirtyColumnBroadcaster | 73 | 72 | 23 | **84%** | mostly |
| ChunkDiskReader | 95 | 97 | 48 | 75% | partial |
| FabricOffThreadProcessor | 78 | 75 | 40 | 74% | partial (these *are* the platform seam) |
| RequestProcessingService | 365 | 377 | 230 | 69% | partial — lots of MC/lifecycle wiring |
| LSSServerCommands / PaperCommands | 100 | 105 | 75 | 64% | **no** — Brigadier vs Bukkit CommandExecutor |
| ChunkGenerationService | 173 | 188 | 158 | 57% | **no** — ticket-based vs `getChunkAtAsync` |
| LSSNetworking / PaperPayloadHandler | 46 | 165 | 209 | 36% | **no** — `StreamCodec` registration vs raw `byte[]` |

Twin totals: **fabric 1163 / paper 1301** code lines. Realistically shareable (the ≥84% rows,
minus what already shares): **~500–700 LOC**, concentrated in the serializers + broadcaster.

The serializer diff is the tell — `SectionSerializer` vs `PaperSectionSerializer` differ in
**exactly three places**: the class name, the method visibility, and a
`@SuppressWarnings("deprecation")` (Paper's `LevelChunkSection.write` is deprecated, Fabric's
isn't). The method bodies are character-for-character identical because **both modules use
Mojang mappings**, so `ServerLevel`/`LevelChunk`/`LevelChunkSection`/`DataLayer` are the same
type names on both sides.

## 3. Why the identical code still can't be a single file

The blocker is the build, in three layers:

1. **`common/` has no Minecraft.** It is a pure-Java module (compileOnly fastutil, api slf4j).
   MC-touching code cannot move there without putting MC on its classpath — which would defeat
   its purpose and pull MC into a module both platforms share.
2. **Fabric ships remapped; Paper does not.** Loom compiles the `fabric/` module against
   Mojang-mapped MC, then **remaps the output to intermediary** for the jar (Fabric Loader
   remaps back at runtime). Paper (paperweight) compiles against Mojang-mapped NMS and runs it
   **as-is**. A plain shared subproject containing `net.minecraft.*` references would be left
   Mojmap in the Fabric jar and fail at Fabric runtime (its names were never remapped to
   intermediary). This is the same hazard documented for `VoxyCompat` ("MC types must use
   direct class literals … due to Fabric's runtime remapping").
3. **Two different MC artifacts.** Loom's remapped `minecraft` dependency and paperweight's
   dev-bundle server jar are different artifacts. A shared module needs one MC API to compile
   against that both builds can then consume/remap.

These are solvable (multi-loader mods do it daily) but only by changing the build, not by moving
a file.

## 4. Options

### A. Shared MC-aware module (architectury-style) — full unification
Add a `:server-common` module compiled against Mojang-mapped MC (compileOnly), holding the
shared leaf classes. Each platform consumes it; Loom remaps its classes into the Fabric jar,
Paper uses them directly.
- **Pros:** real dedup of the serializers/broadcaster; single source of truth for the wire path.
- **Cons:** requires an architectury-loom-style build (or hand-rolled Loom remap of a non-loom
  module) — the riskiest part. The `@SuppressWarnings("deprecation")` mismatch means the shared
  serializer is "unnecessary-suppression" on Fabric (a jdtls hint, not a build error). Payoff is
  ~500–700 LOC for a build overhaul.
- **Effort:** L (build spike + migration). **Risk:** high (touches the release-critical build +
  Fabric remapping correctness).

### B. Interface-seam extraction into `common/` — no MC in the shared module
Push more orchestration into `common/` behind interfaces; keep the thin MC calls in platform
impls (the pattern `OffThreadProcessor` already uses).
- **Pros:** keeps `common/` MC-free; no build change; incremental.
- **Cons:** the serializers are *mostly* MC calls (`section.write`, light layers) — only the
  loop/header skeleton is hoistable, so payoff for the highest-dup classes is small. Adds
  indirection.
- **Effort:** M. **Risk:** low-medium. Best for the processing-service orchestration, weak for
  serializers.

### C. Parity tests instead of unification — address the real risk
Don't merge the twins; add cross-impl tests so divergence is caught mechanically (this is
review items #6 + #16).
- **Pros:** directly kills the actual danger (silent drift between two wire paths) at a fraction
  of the cost; no build change; no remapping risk.
- **Cons:** code stays duplicated (two places to edit), just *safely* duplicated.
- **Effort:** S–M. **Risk:** low.

### D. Hybrid (recommended)
C now; B opportunistically for the processing-service orchestration; A only if/when a
multi-loader build is adopted for other reasons, and then only for the serializers + broadcaster.
Leave generation/commands/networking permanently per-platform (they aren't twins).

## 5. Recommended phased plan

1. **Phase 0 — parity tests (do first; ≈ item #6 + #16).** Extract `PaperPayloadHandler`'s
   pure encode/decode into a Bukkit-free `PaperWireCodec`; add a JUnit test asserting
   byte-equality vs the Fabric `StreamCodec`s for all 8 payloads, and an NBT-serialization
   equivalence test. This makes any future drift a red build — and de-risks every later phase.
2. **Phase 1 — shrink the cheap seams (Option B).** The residual `PlayerRequestState` dup is a
   constructor + one method; fold what's left into `AbstractPlayerRequestState`. Hoist any
   pure-logic still living in `RequestProcessingService` into `common/` helpers.
3. **Phase 2 — decision gate.** Only if the serializer/broadcaster dup is actually causing pain:
   spike an architectury-loom `:server-common` (Option A) in a throwaway branch, prove a Fabric
   jar built from a shared MC module loads in a real server, *then* migrate
   `SectionSerializer` + `NbtSectionSerializer` + `DirtyColumnBroadcaster`. Keep Phase-0 tests
   green throughout.
4. **Never:** unify `ChunkGenerationService` (ticket vs async are different algorithms),
   commands (Brigadier vs Bukkit), or networking (`StreamCodec` vs raw `byte[]`). Forcing these
   into a shared shape adds abstraction without removing real duplication.

## 6. Effort / risk summary

| Option | Dedup payoff | Effort | Risk | When |
|---|---|---|---|---|
| C parity tests | 0 LOC (but kills drift risk) | S–M | low | now |
| B interface seams | ~100–200 LOC | M | low-med | incremental |
| A shared MC module | ~500–700 LOC | L | **high** (build) | only if justified |

**Bottom line:** the "95% duplicated" framing is half-right — a few leaf classes are nearly
identical, but the codebase already shares its hard part, and the remaining twins are split by a
build boundary that costs more to dissolve than the duplicated lines are worth today. Spend the
effort on parity tests (Phase 0); treat the shared MC module as a later, opt-in step gated on a
real multi-loader need.

## Appendix — per-pair disposition

- **Share-worthy (≥84%):** `SectionSerializer`, `NbtSectionSerializer`, `DirtyColumnBroadcaster`
  → Phase 2 (Option A) or just Phase-0 parity tests.
- **Already shared / trivial residual:** `PlayerRequestState` (via `AbstractPlayerRequestState<Q>`),
  `*OffThreadProcessor` (these are the intended platform seam, not accidental dup).
- **Keep per-platform:** `ChunkGenerationService` (ticket vs `getChunkAtAsync`),
  `LSSServerCommands`/`PaperCommands` (Brigadier vs Bukkit), `LSSNetworking`/`PaperPayloadHandler`
  (`StreamCodec` vs raw `FriendlyByteBuf`), and the MC/lifecycle-wiring half of
  `RequestProcessingService`.
