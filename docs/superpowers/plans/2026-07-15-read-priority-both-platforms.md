# Server Disk-Read Priority (yield LOD reads to gameplay) — Both Platforms — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make LSS disk reads yield to vanilla/gameplay chunk loading at the read-scheduler level, on both platforms, so streaming LOD terrain never delays the reads players are waiting on.

**Architecture:** Both platforms keep LSS's own reader pool (it hosts the blocking wait + the section serialization + the per-player result/triage envelope the rest of LSS depends on). Only the *read source* changes, behind one config flag `useBackgroundReadPriority` (default **true**):
- **Fabric (vanilla IOWorker):** LSS and gameplay share the single per-dimension `IOWorker.consecutiveExecutor`. Schedule LSS reads there at `BACKGROUND` priority (below vanilla's `FOREGROUND`) via accessor mixins, reading straight from `RegionFileStorage`.
- **Paper/Folia (Moonrise):** gameplay loads run on Moonrise's own prioritised IO pool, *not* the vanilla IOWorker — so IOWorker priority is a no-op there. Instead route LSS reads through `MoonriseRegionFileIO.loadDataAsync(..., Priority.LOW)` so they defer to gameplay's `NORMAL` reads on Moonrise's shared pool. Moonrise's read serves from in-progress writes, so Paper gets read-your-writes for free.

The design + verification behind this lives in `docs/planning/read-scheduler-design.md` §0, §10.1, §10.2.

**Tech Stack:** Java 25 (Fabric/Paper), Java 21 (common); Fabric Loom + accessor mixins (`org.spongepowered.asm.mixin.gen.Accessor`); paperweight-userdev (`26.2.build.48-alpha`) with native Mojang mappings + direct Moonrise references; MC 26.2.

## Global Constraints

- **Config flag:** `useBackgroundReadPriority` (boolean, **default `true`**) on the shared `ServerConfigBase`; both platforms read the same field. Flag `false` restores the exact current behavior (`chunkMap.read` / FOREGROUND) on both platforms — the rollback path.
- **Paper priority level:** `ca.spottedleaf.concurrentutil.util.Priority.LOW` (chosen: defer to gameplay's `NORMAL` but keep steady LSS progress). Hardcoded, not configurable.
- **`intendingToBlock` = `false`** on `loadDataAsync` — LSS blocks on the returned future from a throwaway daemon pool thread; `false` guarantees no blocking-priority escalation that could override `LOW`.
- **No compile-time MC coupling beyond the reader:** the Moonrise/IOWorker references are confined to the platform reader classes + Fabric mixins. `common/` stays MC-free.
- **Fabric mappings gotcha:** `Class.forName("net.minecraft…")` fails at Fabric runtime (intermediary remapping). Fabric mixin accessors use direct class literals; the `IOWorker$Priority` ordinal cannot be resolved by name at runtime and stays pinned (see Task 3 risk note).
- **Folia stays experimental:** any release note for this change must say so (label holds until concurrent multi-region soak — see the spec's exit criterion). `/reload` unsupported on Folia.
- **Drop Approach B:** the adaptive-throttle prototype (worktree `agent-a87a5c55fb11ce30e`) is superseded by this plan and is not merged. Nothing to remove from `main` (B was never landed).

---

## File Structure

**Create:**
- `fabric/src/main/java/dev/vox/lss/mixin/AccessorSimpleRegionStorage.java` — `@Accessor("worker")` on `SimpleRegionStorage`.
- `fabric/src/main/java/dev/vox/lss/mixin/AccessorIOWorker.java` — `@Accessor("consecutiveExecutor")` + `@Accessor("storage")` on `IOWorker`.

**Modify:**
- `common/src/main/java/dev/vox/lss/common/config/ServerConfigBase.java` — add `useBackgroundReadPriority` (default true).
- `fabric/src/main/resources/lss.mixins.json` — register the two accessors.
- `fabric/src/main/java/dev/vox/lss/networking/server/NbtSectionSerializer.java` — add `ChunkNbtRead` seam; `readAndSerializeSections` takes it.
- `fabric/src/main/java/dev/vox/lss/networking/server/ChunkDiskReader.java` — `(threadCount, useBackgroundReadPriority)` ctor + `backgroundReader`.
- `fabric/src/main/java/dev/vox/lss/networking/server/RequestProcessingService.java` — pass `config.useBackgroundReadPriority`.
- `paper/src/main/java/dev/vox/lss/paper/PaperChunkDiskReader.java` — `(threadCount, useBackgroundReadPriority)` ctor + `moonriseReader`.
- `paper/src/main/java/dev/vox/lss/paper/PaperRequestProcessingService.java` — pass `config.useBackgroundReadPriority`.
- `paper/build.gradle` — (only if Task 0 requires it) `compileOnly 'ca.spottedleaf:concurrentutil:0.0.10'`.
- `README.md`, `CLAUDE.md` — config row + architecture note.

**Test:**
- `fabric/src/gametest/java/dev/vox/lss/test/SerializerParityGameTests.java` — background-vs-foreground byte-parity gametest + update `new ChunkDiskReader(1)` call sites to `(1, false)`.
- `fabric/src/test/java/dev/vox/lss/config/ConfigValidationTest.java` — default/round-trip of the new flag.
- `paper/src/test/java/dev/vox/lss/paper/PaperConfigValidationTest.java` — default of the new flag.
- `paper/src/test/java/dev/vox/lss/paper/PaperChunkDiskReaderPriorityTest.java` (new) — flag plumbing + envelope-unaffected via the existing read-override seam.

**Branch:** `feat/read-priority` off `main`. (Fabric parts can be cherry-picked from the built prototype worktree `agent-a6ba10c0ed783aa3c`, but this plan reproduces them so it is self-contained.)

---

## Task 0: Spike — confirm Moonrise + `Priority` compile access on Paper

De-risks the whole Paper path before any real code. `MoonriseRegionFileIO` is in the paperweight output jar, but `ca.spottedleaf.concurrentutil.util.Priority` is **not** bundled there — it may or may not be on the plugin compile classpath transitively.

**Files:**
- Temp: `paper/src/main/java/dev/vox/lss/paper/_Spike.java` (deleted at end of task)
- Maybe modify: `paper/build.gradle`

- [ ] **Step 1: Add a throwaway reference**

```java
package dev.vox.lss.paper;

import ca.spottedleaf.moonrise.patches.chunk_system.io.MoonriseRegionFileIO;
import ca.spottedleaf.concurrentutil.util.Priority;

final class _Spike {
    static final Object A = MoonriseRegionFileIO.RegionFileType.CHUNK_DATA;
    static final Priority B = Priority.LOW;
}
```

- [ ] **Step 2: Compile the paper module**

Run: `./gradlew :paper:compileJava`
- **If it compiles:** both symbols are on the classpath — no dep change needed. Record "concurrentutil resolves transitively" in the task notes.
- **If it fails** with `package ca.spottedleaf.concurrentutil.util does not exist`: add to `paper/build.gradle` `dependencies {}`:
  ```groovy
  compileOnly 'ca.spottedleaf:concurrentutil:0.0.10'
  ```
  (version present in `~/.gradle/caches/modules-2/…/ca.spottedleaf/concurrentutil/0.0.10`), then re-run `./gradlew :paper:compileJava` until green.

- [ ] **Step 3: Delete the spike file, leave any `build.gradle` change**

Run: `rm paper/src/main/java/dev/vox/lss/paper/_Spike.java && ./gradlew :paper:compileJava`
Expected: PASS (clean, with the dep addition if it was needed).

- [ ] **Step 4: Commit (only if `build.gradle` changed)**

```bash
git add paper/build.gradle
git commit -m "build: ensure concurrentutil (Moonrise Priority) on paper compile classpath"
```

---

## Task 1: Shared config flag `useBackgroundReadPriority` (default true)

**Files:**
- Modify: `common/src/main/java/dev/vox/lss/common/config/ServerConfigBase.java`
- Test: `fabric/src/test/java/dev/vox/lss/config/ConfigValidationTest.java`, `paper/src/test/java/dev/vox/lss/paper/PaperConfigValidationTest.java`

**Interfaces:**
- Produces: `ServerConfigBase.useBackgroundReadPriority` (public boolean field, default `true`) — read by both `RequestProcessingService` and `PaperRequestProcessingService`.

- [ ] **Step 1: Write the failing test (Fabric)**

Add to `ConfigValidationTest.java`:
```java
@Test
void backgroundReadPriorityDefaultsOn() {
    var config = new LSSServerConfig(); // or the module's ServerConfigBase subclass under test
    assertTrue(config.useBackgroundReadPriority,
            "background read priority must default on");
}
```

- [ ] **Step 2: Run it — verify it fails to compile (field missing)**

Run: `./gradlew :fabric:test --tests '*ConfigValidationTest' -x runGameTest -x runClientGameTest`
Expected: FAIL — `cannot find symbol: useBackgroundReadPriority`.

- [ ] **Step 3: Add the field**

In `ServerConfigBase.java`, alongside the other server fields (near `diskReaderThreads` / concurrency fields):
```java
/** When true (default), LSS disk reads yield to vanilla/gameplay chunk loading:
 *  Fabric schedules them at IOWorker BACKGROUND priority; Paper/Folia route them
 *  through Moonrise at Priority.LOW. Set false to restore FOREGROUND reads
 *  (the pre-0.7 behavior) as a rollback. */
public boolean useBackgroundReadPriority = true;
```
No clamp needed (boolean). If the config has a field-copy/merge routine, add `useBackgroundReadPriority` there too (grep the class for how `generationEnabled` is copied and mirror it).

- [ ] **Step 4: Run the Fabric test to green**

Run: `./gradlew :fabric:test --tests '*ConfigValidationTest' -x runGameTest -x runClientGameTest`
Expected: PASS.

- [ ] **Step 5: Mirror the assertion for Paper**

Add to `PaperConfigValidationTest.java`:
```java
@Test
void backgroundReadPriorityDefaultsOn() {
    assertTrue(new PaperConfig().useBackgroundReadPriority,
            "background read priority must default on (Paper)");
}
```
Run: `./gradlew :paper:test --tests '*PaperConfigValidationTest'`
Expected: PASS (field is inherited from `ServerConfigBase`).

- [ ] **Step 6: Commit**

```bash
git add common/src/main/java/dev/vox/lss/common/config/ServerConfigBase.java \
        fabric/src/test/java/dev/vox/lss/config/ConfigValidationTest.java \
        paper/src/test/java/dev/vox/lss/paper/PaperConfigValidationTest.java
git commit -m "config: add useBackgroundReadPriority (default true) to ServerConfigBase"
```

---

## Task 2: Fabric — accessor mixins for the IOWorker

**Files:**
- Create: `fabric/src/main/java/dev/vox/lss/mixin/AccessorSimpleRegionStorage.java`
- Create: `fabric/src/main/java/dev/vox/lss/mixin/AccessorIOWorker.java`
- Modify: `fabric/src/main/resources/lss.mixins.json`

**Interfaces:**
- Produces: `AccessorSimpleRegionStorage.lss$getWorker() : IOWorker`; `AccessorIOWorker.lss$getConsecutiveExecutor() : PriorityConsecutiveExecutor`, `lss$getStorage() : RegionFileStorage`. Consumed by `ChunkDiskReader.backgroundReader` (Task 3).

- [ ] **Step 1: Create `AccessorSimpleRegionStorage`**

```java
package dev.vox.lss.mixin;

import net.minecraft.world.level.chunk.storage.IOWorker;
import net.minecraft.world.level.chunk.storage.SimpleRegionStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Exposes {@code SimpleRegionStorage.worker} so LSS can reach the IOWorker that
 *  {@code ChunkMap} (which extends SimpleRegionStorage) reads through, to schedule
 *  BACKGROUND-priority reads on the same executor vanilla uses. */
@Mixin(SimpleRegionStorage.class)
public interface AccessorSimpleRegionStorage {
    @Accessor("worker")
    IOWorker lss$getWorker();
}
```

- [ ] **Step 2: Create `AccessorIOWorker`**

```java
package dev.vox.lss.mixin;

import net.minecraft.util.thread.PriorityConsecutiveExecutor;
import net.minecraft.world.level.chunk.storage.IOWorker;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Exposes {@code IOWorker}'s private {@code consecutiveExecutor} and {@code storage} so
 *  the background-priority read path can schedule a BACKGROUND read on the same
 *  single-threaded executor vanilla uses, reading straight from {@code RegionFileStorage}. */
@Mixin(IOWorker.class)
public interface AccessorIOWorker {
    @Accessor("consecutiveExecutor")
    PriorityConsecutiveExecutor lss$getConsecutiveExecutor();

    @Accessor("storage")
    RegionFileStorage lss$getStorage();
}
```

- [ ] **Step 3: Register both in `lss.mixins.json`**

In the `"mixins"` array (server-side), add after `"AccessorChunkMap"`:
```json
    "AccessorSimpleRegionStorage",
    "AccessorIOWorker",
```

- [ ] **Step 4: Compile — confirm accessors resolve (fields exist under Mojang mappings)**

Run: `./gradlew :fabric:compileJava`
Expected: PASS. (`defaultRequire: 1` means a field rename would fail *loudly* at mod load — verified live by Task 3's gametest booting a server.)

- [ ] **Step 5: Commit**

```bash
git add fabric/src/main/java/dev/vox/lss/mixin/AccessorSimpleRegionStorage.java \
        fabric/src/main/java/dev/vox/lss/mixin/AccessorIOWorker.java \
        fabric/src/main/resources/lss.mixins.json
git commit -m "fabric: accessor mixins for IOWorker consecutiveExecutor + storage"
```

---

## Task 3: Fabric — BACKGROUND-priority reader + byte-parity gametest

**Files:**
- Modify: `fabric/src/main/java/dev/vox/lss/networking/server/NbtSectionSerializer.java`
- Modify: `fabric/src/main/java/dev/vox/lss/networking/server/ChunkDiskReader.java`
- Modify: `fabric/src/main/java/dev/vox/lss/networking/server/RequestProcessingService.java`
- Test: `fabric/src/gametest/java/dev/vox/lss/test/SerializerParityGameTests.java`

**Interfaces:**
- Consumes: `AccessorSimpleRegionStorage`, `AccessorIOWorker` (Task 2); `ServerConfigBase.useBackgroundReadPriority` (Task 1).
- Produces: `new ChunkDiskReader(int threadCount, boolean useBackgroundReadPriority)`.

- [ ] **Step 1: Add the `ChunkNbtRead` seam to `NbtSectionSerializer`**

Replace the `readAndSerializeSections(ChunkMap chunkMap, …)` signature with a functional seam (mirrors Paper's). Remove the now-unused `ChunkMap`/`ChunkPos` imports; add `Optional` + `CompletableFuture`:
```java
/** Seam for the region-file NBT read: foreground ({@code chunkMap.read}) or
 *  BACKGROUND-priority (per config). Mirrors Paper's {@code ChunkNbtRead}. */
@FunctionalInterface
interface ChunkNbtRead {
    CompletableFuture<Optional<CompoundTag>> read(int cx, int cz);
}

static byte[] readAndSerializeSections(ChunkNbtRead read, RegistryAccess registryAccess,
                                        int cx, int cz) throws Exception {
    var future = read.read(cx, cz);
    var optionalTag = future.get(LSSConstants.DISK_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    if (optionalTag.isEmpty()) return null;
    return serializeChunkNbt(optionalTag.get(), registryAccess);
}
```

- [ ] **Step 2: Rework `ChunkDiskReader` to pick the read path**

```java
package dev.vox.lss.networking.server;

import dev.vox.lss.common.processing.AbstractChunkDiskReader;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

import java.io.IOException;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class ChunkDiskReader extends AbstractChunkDiskReader {

    // IOWorker$Priority is package-private and cannot be referenced; this literal mirrors
    // loadAsync's own use of FOREGROUND(0). Scheduling LOD reads at BACKGROUND(1) makes them
    // yield to vanilla's foreground chunk loads on the shared IOWorker executor.
    private static final int IOWORKER_PRIORITY_BACKGROUND = 1;

    private final boolean useBackgroundReadPriority;

    public ChunkDiskReader(int threadCount, boolean useBackgroundReadPriority) {
        super(threadCount);
        this.useBackgroundReadPriority = useBackgroundReadPriority;
    }

    public void submitReadDirect(UUID playerUuid, String dimension, ServerLevel level,
                                  int chunkX, int chunkZ, long submissionOrder) {
        var registryAccess = level.registryAccess();
        var chunkMap = ((dev.vox.lss.mixin.AccessorServerChunkCache) level.getChunkSource()).getChunkMap();
        var reader = this.useBackgroundReadPriority
                ? backgroundReader(chunkMap)
                : (NbtSectionSerializer.ChunkNbtRead) (cx, cz) -> chunkMap.read(new ChunkPos(cx, cz));
        submitRead(playerUuid, chunkX, chunkZ, dimension, submissionOrder,
                () -> NbtSectionSerializer.readAndSerializeSections(reader, registryAccess, chunkX, chunkZ));
    }

    /** BACKGROUND-priority reader: schedules on the IOWorker's own single-threaded executor
     *  below vanilla's FOREGROUND, reading straight from RegionFileStorage. Reads skip
     *  IOWorker's pendingWrites read-your-writes — acceptable for LOD: a just-written chunk
     *  is covered by dirty-broadcast re-requests, so a momentarily stale read self-heals. */
    private NbtSectionSerializer.ChunkNbtRead backgroundReader(ChunkMap chunkMap) {
        var worker = (dev.vox.lss.mixin.AccessorIOWorker)
                ((dev.vox.lss.mixin.AccessorSimpleRegionStorage) chunkMap).lss$getWorker();
        var executor = worker.lss$getConsecutiveExecutor();
        var storage = worker.lss$getStorage();
        return (cx, cz) -> {
            var pos = new ChunkPos(cx, cz);
            return executor.scheduleWithResult(IOWORKER_PRIORITY_BACKGROUND,
                    (CompletableFuture<Optional<CompoundTag>> f) -> {
                        try {
                            f.complete(Optional.ofNullable(storage.read(pos)));
                        } catch (IOException e) {
                            f.completeExceptionally(e);
                        }
                    });
        };
    }
}
```

- [ ] **Step 3: Pass the flag from `RequestProcessingService`**

Find where `new ChunkDiskReader(config.diskReaderThreads)` is constructed and change to:
```java
new ChunkDiskReader(config.diskReaderThreads, config.useBackgroundReadPriority)
```

- [ ] **Step 4: Update existing `ChunkDiskReader(1)` call sites in the gametest**

In `SerializerParityGameTests.java`, change the three existing `new ChunkDiskReader(1)` to `new ChunkDiskReader(1, false)` (unchanged foreground behavior for those tests).

- [ ] **Step 5: Add the byte-parity gametest**

Add a constant `private static final int BACKGROUND_READ_CHUNK_OFFSET = 144;` and this test (also add its class is already listed in the gametest entrypoint — `SerializerParityGameTests` is already registered, so no `fabric.mod.json` change):
```java
/** BACKGROUND-priority read (schedules on the IOWorker executor at priority 1, reads straight
 *  from RegionFileStorage) must return byte-identical section bytes to the default FOREGROUND
 *  read of the same on-disk chunk. Exercises the accessor-mixin path end to end. */
@GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 1200)
public void backgroundPriorityReadMatchesForegroundReadForDiskLoadedColumn(GameTestHelper helper) {
    ServerLevel level = helper.getLevel();
    var origin = ChunkPos.containing(helper.absolutePos(BlockPos.ZERO));
    int cx = origin.x() + BACKGROUND_READ_CHUNK_OFFSET;
    int cz = origin.z() + 5;
    var chunkPos = new ChunkPos(cx, cz);
    var chunkSource = level.getChunkSource();

    chunkSource.addTicketWithRadius(TicketType.PLAYER_LOADING, chunkPos, 0);
    level.getChunk(cx, cz);
    helper.runAfterDelay(4, () -> chunkSource.removeTicketWithRadius(TicketType.PLAYER_LOADING, chunkPos, 0));

    var foreground = new ChunkDiskReader(1, false);
    var background = new ChunkDiskReader(1, true);
    var fgId = UUID.randomUUID();
    var bgId = UUID.randomUUID();
    foreground.registerPlayer(fgId);
    background.registerPlayer(bgId);
    var step = new AtomicInteger();
    var fgBytes = new AtomicReference<byte[]>();

    helper.succeedWhen(() -> {
        helper.assertTrue(helper.getTick() >= 6, "waiting for the ticket release");
        switch (step.get()) {
            case 0 -> {
                helper.assertTrue(chunkSource.getChunkNow(cx, cz) == null, "waiting for the chunk to unload");
                level.save(null, true, false);
                foreground.submitReadDirect(fgId, LSSConstants.DIM_STR_OVERWORLD, level, cx, cz, 0);
                background.submitReadDirect(bgId, LSSConstants.DIM_STR_OVERWORLD, level, cx, cz, 0);
                step.set(1);
                helper.assertTrue(false, "foreground + background reads submitted");
            }
            case 1 -> {
                var fg = foreground.getPlayerQueue(fgId).poll();
                helper.assertTrue(fg != null, "waiting for the foreground read result");
                foreground.shutdown();
                helper.assertTrue(!fg.notFound() && !fg.saturated() && fg.sectionBytes() != null,
                        "foreground read of the saved superflat chunk must return content");
                fgBytes.set(fg.sectionBytes());
                step.set(2);
                helper.assertTrue(false, "foreground bytes captured, awaiting background result");
            }
            case 2 -> {
                var bg = background.getPlayerQueue(bgId).poll();
                helper.assertTrue(bg != null, "waiting for the background read result");
                background.shutdown();
                helper.assertTrue(!bg.notFound() && !bg.saturated() && bg.sectionBytes() != null,
                        "background-priority read must return content, not not-found/saturated");
                helper.assertTrue(Arrays.equals(fgBytes.get(), bg.sectionBytes()),
                        describeMismatch(fgBytes.get(), bg.sectionBytes()));
            }
            default -> helper.fail("unexpected background-read step " + step.get());
        }
    });
}
```

- [ ] **Step 6: Run Tier 1 (compile + unit) then Tier 2 (gametest)**

Run: `./gradlew :fabric:test -x runGameTest -x runClientGameTest && ./gradlew :fabric:runGameTest --tests '*SerializerParityGameTests*'`
Expected: PASS, including `backgroundPriorityReadMatchesForegroundReadForDiskLoadedColumn`.

> **Risk note (record in task):** the BACKGROUND ordinal `1` is pinned (Fabric runtime remapping blocks resolving `IOWorker$Priority.BACKGROUND` by name — the `Class.forName` gotcha). Mitigations: the byte-parity gametest fails if the read is wrong; a field rename fails loudly via `defaultRequire: 1`; a *reorder* of the 3-constant enum is the only silent risk and is re-validated at each MC version bump. The straight-`storage.read` skips read-your-writes; the tiny write window self-heals via dirty-broadcast (Fabric-only asymmetry — Paper gets read-your-writes free in Task 4).

- [ ] **Step 7: Commit**

```bash
git add fabric/src/main/java/dev/vox/lss/networking/server/NbtSectionSerializer.java \
        fabric/src/main/java/dev/vox/lss/networking/server/ChunkDiskReader.java \
        fabric/src/main/java/dev/vox/lss/networking/server/RequestProcessingService.java \
        fabric/src/gametest/java/dev/vox/lss/test/SerializerParityGameTests.java
git commit -m "fabric: schedule LOD disk reads at IOWorker BACKGROUND priority (flag-gated)"
```

---

## Task 4: Paper/Folia — Moonrise `loadDataAsync` at `Priority.LOW`

**Files:**
- Modify: `paper/src/main/java/dev/vox/lss/paper/PaperChunkDiskReader.java`
- Modify: `paper/src/main/java/dev/vox/lss/paper/PaperRequestProcessingService.java`
- Test: `paper/src/test/java/dev/vox/lss/paper/PaperChunkDiskReaderPriorityTest.java` (new)

**Interfaces:**
- Consumes: `ServerConfigBase.useBackgroundReadPriority` (Task 1); Moonrise `MoonriseRegionFileIO` + `Priority` (Task 0).
- Produces: `new PaperChunkDiskReader(int threadCount, boolean useBackgroundReadPriority)`.

- [ ] **Step 1: Write the failing test (flag plumbing + envelope unaffected via the read-override seam)**

Create `PaperChunkDiskReaderPriorityTest.java`:
```java
package dev.vox.lss.paper;

import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

/** The priority flag must not disturb the read envelope: with the test read-override seam set
 *  (bypassing the real Moonrise call), a flag-on reader still delivers a normal result. The
 *  live Moonrise LOW-priority path itself is validated by the soak harness on real Paper/Folia. */
class PaperChunkDiskReaderPriorityTest {

    @Test
    void flagOnReaderStillDeliversThroughEnvelope() throws Exception {
        var reader = new PaperChunkDiskReader(1, /* useBackgroundReadPriority = */ true);
        var id = UUID.randomUUID();
        reader.registerPlayer(id);
        // Override bypasses NMS: return an empty tag => "not found" result, exercising the envelope.
        reader.setReadOverride((cx, cz) -> CompletableFuture.completedFuture(Optional.<CompoundTag>empty()));

        // submitReadWith(...) is the seam the existing Paper reader tests already use to drive
        // submitRead without a live ServerLevel; reuse whatever that harness exposes.
        reader.submitReadForTest(id, "minecraft:overworld", 10, 10, 0L);

        var result = pollUntilNonNull(reader.getPlayerQueue(id), 2000);
        assertNotNull(result, "flag-on reader must still deliver a result through the envelope");
        assertTrue(result.notFound(), "empty override tag resolves as not-found");
        reader.shutdown();
    }

    private static <T> T pollUntilNonNull(java.util.Queue<T> q, long timeoutMs) throws InterruptedException {
        long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
        T v;
        while ((v = q.poll()) == null && System.nanoTime() < deadline) Thread.sleep(5);
        return v;
    }
}
```
> If the Paper reader tests use a different existing seam than `submitReadForTest`/`setReadOverride`, match that harness (grep `paper/src/test` for how `PaperChunkDiskReader` is currently driven). `setReadOverride` already exists in production code; only the submit-without-level entry may need a small `@VisibleForTesting` helper mirroring the Fabric gametest's `submitReadDirect`. Add that helper if absent.

- [ ] **Step 2: Run — verify it fails (constructor arity)**

Run: `./gradlew :paper:test --tests '*PaperChunkDiskReaderPriorityTest'`
Expected: FAIL — `constructor PaperChunkDiskReader(int, boolean)` not found.

- [ ] **Step 3: Add the flag + Moonrise reader to `PaperChunkDiskReader`**

```java
package dev.vox.lss.paper;

import ca.spottedleaf.concurrentutil.util.Priority;
import ca.spottedleaf.moonrise.patches.chunk_system.io.MoonriseRegionFileIO;
import dev.vox.lss.common.processing.AbstractChunkDiskReader;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class PaperChunkDiskReader extends AbstractChunkDiskReader {

    private volatile PaperNbtSectionSerializer.ChunkNbtRead readOverride;
    private final boolean useBackgroundReadPriority;

    public PaperChunkDiskReader(int threadCount, boolean useBackgroundReadPriority) {
        super(threadCount);
        this.useBackgroundReadPriority = useBackgroundReadPriority;
    }

    void setReadOverride(PaperNbtSectionSerializer.ChunkNbtRead read) {
        this.readOverride = read;
    }

    public void submitReadDirect(UUID playerUuid, String dimension, ServerLevel level,
                                  int chunkX, int chunkZ, long submissionOrder) {
        var registryAccess = level.registryAccess();
        var override = this.readOverride;
        PaperNbtSectionSerializer.ChunkNbtRead read;
        if (override != null) {
            read = override;
        } else if (this.useBackgroundReadPriority) {
            read = moonriseReader(level);
        } else {
            var chunkMap = level.getChunkSource().chunkMap;
            read = (cx, cz) -> chunkMap.read(new ChunkPos(cx, cz));
        }
        submitRead(playerUuid, chunkX, chunkZ, dimension, submissionOrder,
                () -> PaperNbtSectionSerializer.readAndSerializeSections(read, registryAccess, chunkX, chunkZ));
    }

    /** Route reads through Moonrise's prioritised IO pool at LOW priority so they defer to
     *  gameplay's NORMAL reads. loadDataAsync serves from in-progress writes, so this preserves
     *  read-your-writes. The callback only completes our future (no chunk-system reentrancy) —
     *  the blocking wait + serialization then run on the LSS reader pool thread, off Moonrise's
     *  IO threads. intendingToBlock=false: we block on a throwaway daemon thread and want no
     *  blocking-priority escalation overriding LOW. */
    private PaperNbtSectionSerializer.ChunkNbtRead moonriseReader(ServerLevel level) {
        return (cx, cz) -> {
            var future = new CompletableFuture<Optional<CompoundTag>>();
            MoonriseRegionFileIO.loadDataAsync(level, cx, cz,
                    MoonriseRegionFileIO.RegionFileType.CHUNK_DATA,
                    (tag, err) -> {
                        if (err != null) future.completeExceptionally(err);
                        else future.complete(Optional.ofNullable(tag));
                    },
                    /* intendingToBlock = */ false, Priority.LOW);
            return future;
        };
    }

    // @VisibleForTesting — drive submitRead without a live ServerLevel (used by
    // PaperChunkDiskReaderPriorityTest). Requires a read-override to be set.
    void submitReadForTest(UUID playerUuid, String dimension, int chunkX, int chunkZ, long order) {
        var read = this.readOverride;
        if (read == null) throw new IllegalStateException("submitReadForTest requires a read override");
        submitRead(playerUuid, chunkX, chunkZ, dimension, order,
                () -> PaperNbtSectionSerializer.readAndSerializeSections(read, null, chunkX, chunkZ));
    }
}
```
> Note: `readAndSerializeSections` with a null registryAccess is only reached when the override returns an empty Optional (not-found short-circuits before `serializeChunkNbt`). If the Paper serializer dereferences registryAccess before the empty check, have the test override return a minimal valid tag instead, or pass the real `registryAccess` from a stubbed level — match the existing Paper reader test harness.

- [ ] **Step 4: Pass the flag from `PaperRequestProcessingService`**

Find `new PaperChunkDiskReader(config.diskReaderThreads)` and change to:
```java
new PaperChunkDiskReader(config.diskReaderThreads, config.useBackgroundReadPriority)
```

- [ ] **Step 5: Run the Paper test to green + full Paper Tier 1**

Run: `./gradlew :paper:test`
Expected: PASS (new test green; all ~243 existing Paper tests unaffected).

- [ ] **Step 6: Commit**

```bash
git add paper/src/main/java/dev/vox/lss/paper/PaperChunkDiskReader.java \
        paper/src/main/java/dev/vox/lss/paper/PaperRequestProcessingService.java \
        paper/src/test/java/dev/vox/lss/paper/PaperChunkDiskReaderPriorityTest.java
git commit -m "paper: route LOD disk reads through Moonrise loadDataAsync at Priority.LOW (flag-gated)"
```

---

## Task 5: Docs — README + CLAUDE.md

**Files:**
- Modify: `README.md`, `CLAUDE.md`

- [ ] **Step 1: README config row**

In the server-config table(s), add a row (both platforms share the field):
```
| `useBackgroundReadPriority` | `true` | LOD disk reads yield to vanilla/gameplay chunk loading (Fabric: IOWorker BACKGROUND; Paper/Folia: Moonrise LOW priority). Set `false` to restore foreground reads. |
```

- [ ] **Step 2: CLAUDE.md architecture note**

Under the reader entries (`ChunkDiskReader` / `PaperChunkDiskReader`), note the flag and the per-platform mechanism, and add the Moonrise coupling to the Paper section and the Folia key-patterns bullet (experimental). One or two sentences each, matching the file's style. Reference `docs/planning/read-scheduler-design.md` §10.2 for the verified rationale.

- [ ] **Step 3: Commit**

```bash
git add README.md CLAUDE.md
git commit -m "docs: document useBackgroundReadPriority + per-platform read-priority mechanism"
```

---

## Task 6: Validation gauntlet + real-server soak (the true gate)

No new code — this is the gate. The Moonrise LOW-priority path has no cheap unit/gametest; the soak harness on real Paper/Folia is its validation (per CLAUDE.md's Paper validation strategy).

- [ ] **Step 1: Fabric Tier 1 + 2 + 3**

Run:
```bash
./gradlew :fabric:test -x runClientGameTest   # Tier 1 + 2
./gradlew :fabric:runClientGameTest           # Tier 3
```
Expected: PASS. (Known local flake: `RegionFaultGameTests` on WSL2 — ignore per CLAUDE.md.)

- [ ] **Step 2: Paper Tier 1 + jar**

Run: `./gradlew :paper:test :paper:shadowJar`
Expected: PASS; jar builds.

- [ ] **Step 3: Soak — Fabric (default-on now)**

Run: `./scripts/soak.sh fresh-backfill`
Expected: verdict PASS. Confirms priority-on reads still conserve (request/delivery/disk accounting) and backfill completes.

- [ ] **Step 4: Soak — Paper (Moonrise LOW path, live)**

Run: `SOAK_PLATFORM=paper ./scripts/soak.sh all`
Expected: all four Paper scenarios PASS. This is the primary evidence the Moonrise `loadDataAsync` path reads correct bytes and completes under the driver timeline.

- [ ] **Step 5: Soak — Folia (Moonrise LOW path, regionised)**

Run: `SOAK_PLATFORM=folia ./scripts/soak.sh all`
Expected: PASS. Confirms `loadDataAsync` from the LSS pool thread is safe under Folia's regionised scheduler (calling it off a region thread; region files are not regionised).

- [ ] **Step 6: Spot-check byte parity Paper-vs-Fabric (optional but recommended)**

The Paper NBT golden-corpus + live-vs-NBT parity tests (`./gradlew :paper:test`) already pin serialization; the soak conservation checks pin delivery. If a dedicated Moonrise-vs-chunkMap.read byte comparison is wanted, add a temporary logging assert in a soak run comparing a known position served with the flag on vs off — not a committed test. Record the result in the PR description.

- [ ] **Step 7: Commit any doc/flake-catalog updates surfaced during validation**

If a new flake or Moonrise-specific quirk appears, add it to CLAUDE.md's **Known Test Flakes & Environmental Failures** and mirror to memory. Commit.

---

## Task 7: Ship prep + cleanup

- [ ] **Step 1: Open the PR to `main`**

```bash
git push -u origin feat/read-priority
gh pr create --base main --title "Yield LOD disk reads to gameplay (IOWorker BACKGROUND / Moonrise LOW)" \
  --body "$(cat <<'EOF'
Routes LSS disk reads below vanilla/gameplay chunk loading at the scheduler level, default-on.

- Fabric: IOWorker BACKGROUND priority via accessor mixins (byte-parity gametest).
- Paper/Folia: Moonrise `loadDataAsync` at `Priority.LOW` (read-your-writes preserved; soak-validated on real Paper + Folia).
- Config: `useBackgroundReadPriority` (default true) — set false to restore foreground reads.

Verified design: docs/planning/read-scheduler-design.md §10.2. Supersedes the adaptive-throttle prototype (Approach B).

Folia support remains experimental.

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

- [ ] **Step 2: Confirm CI green** (Fabric Tier 1/2/3 jobs, Paper test, release_check). Diagnose reds from artifacts before re-running (gametest tiers auto-retry once).

- [ ] **Step 3: Remove the superseded prototype worktrees**

```bash
git worktree remove .claude/worktrees/agent-a6ba10c0ed783aa3c --force
git worktree remove .claude/worktrees/agent-a87a5c55fb11ce30e --force
```

- [ ] **Step 4: Release notes (when tagging — not in this PR)**

Draft a **Performance** item for the next release: "LOD disk reads now yield to vanilla/gameplay chunk loading, so streaming distant terrain no longer delays the chunks players are actively loading (Fabric and Paper; Paper/Folia via Moonrise — Folia support remains experimental)."

---

## Self-Review (completed)

- **Spec coverage:** Fabric mechanism (Tasks 2–3), Paper/Folia mechanism (Task 4), shared default-on flag (Task 1), the `Priority.LOW` + `intendingToBlock=false` + read-your-writes decisions (Global Constraints + Task 4), docs (Task 5), validation incl. real Paper/Folia soak (Task 6), B dropped + worktree cleanup (Task 7). Covered.
- **Type consistency:** `ChunkNbtRead` returns `CompletableFuture<Optional<CompoundTag>>` on both platforms; `ChunkDiskReader(int,boolean)` / `PaperChunkDiskReader(int,boolean)` match their `RequestProcessingService` call sites; `useBackgroundReadPriority` is one `ServerConfigBase` field read by both services.
- **Placeholders:** none — every code step carries full code; the two soft spots (Paper test harness seam names, serializer null-registry edge) are flagged inline with a concrete "match the existing harness / return a minimal tag" instruction rather than left as TODO.
- **Open risk carried, not hidden:** Fabric read-your-writes self-heal asymmetry (documented, dirty-broadcast covers it); pinned IOWorker ordinal (gametest + per-version revalidation); Moonrise internal-API brittleness (breaks loudly at compile). All in the relevant task's risk note.
