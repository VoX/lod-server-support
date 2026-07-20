# A + B Disk-Read Protection: Adaptive-Throttle Fallback — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Keep Approach A (background-priority reads) as the default protection against LOD reads drowning out gameplay, and — when A is detected incompatible at runtime because a chunk-IO-overhaul mod (C2ME et al.) replaced vanilla's `IOWorker` — fall back *once, quietly* to Approach B (a portable adaptive read throttle) so LOD reads still yield to gameplay via self-restraint instead of losing all protection.

**Architecture:**
- **A stays the default and is unchanged on the compatible path.** Fabric: schedule reads at IOWorker `BACKGROUND`. Paper/Folia: Moonrise `loadDataAsync(Priority.LOW)` — Paper's A path is a direct compile-time call to a class that is *always present* on supported Paper (a missing Moonrise fails loudly at class-load, never silently null), so **B never engages on Paper**.
- **Detection (Fabric only, in practice):** resolving the IOWorker executor/storage handles is wrapped so that a null handle *or any Throwable* (cast failure, missing accessor) latches a one-way, server-wide "background incompatible" flag, enables the throttle, emits **exactly one** warning log, and serves that read (and all later reads) through the vanilla `chunkMap.read` path.
- **B is restored from branch `bed0843` into `common/`** as `AdaptiveReadThrottle` (a pure AIMD control law keyed on LSS's own EWMA read latency — zero `net.minecraft` coupling, verified). Two changes from its standalone prototype: (1) it is **runtime-enabled by A's fallback**, not a config flag; (2) it expresses backpressure by **narrowing `hasHeadroom()`**, not by a separate saturated bounce — so the want-set router simply *retains* a throttled read and the client re-declares it next second. Every B failure mode degrades only LSS's own read throughput and self-heals; none harm gameplay or correctness.

**Tech Stack:** Java 25 (Fabric/Paper), Java 21 (`common/`); Fabric Loom + accessor mixins; paperweight-userdev; MC 26.2. No new dependencies.

**Branch:** the current `feat/want-set-requests` (this extends the read-priority + want-set work already on it; it builds on `f65a447`, the C2ME fail-safe that this plan upgrades from "fall back to foreground" to "fall back to foreground **+ throttle**").

## Global Constraints

- **Exactly ONE warning log, ever, on fallback.** Latched by a single `AtomicBoolean`. No per-read logging, no repeats across dimensions.
- **Detection is maximally fail-safe.** Treat *both* a null handle and *any* `Throwable` during accessor resolution as incompatibility — never let the detection itself throw. The whole point is that a mod we cannot anticipate has changed vanilla's internals.
- **The throttle engages ONLY on A-incompatibility.** When A works (true priority), the throttle stays null — throttling a working-A server would only cost LSS throughput for zero gameplay benefit.
- **`useBackgroundReadPriority = false` remains a true rollback:** plain foreground `chunkMap.read`, **no** throttle. A user who disables A has opted out of all read protection; do not silently re-add it.
- **B integrates through `hasHeadroom()`, not a bounce.** A throttled-out read is *retained* by the want-set router (the `NO_DISK_HEADROOM` path) and healed by the 1 Hz re-declaration. No `RESPONSE_RATE_LIMITED` (retired in v17), no saturated result on the throttle path.
- **No new user-facing config knob is required.** The throttle's target latency is a constant default (`20 ms`, matching the prototype). An optional `adaptiveReadTargetLatencyMs` override MAY be added later; it is out of scope here to keep the config surface minimal (the fallback is automatic).
- **`common/` stays MC-free.** `AdaptiveReadThrottle` has zero `net.minecraft` references (verified against `bed0843`).
- **The one-way latch is server-wide.** A chunk-IO mod replaces IO globally, so once any dimension's read resolves incompatible, all subsequent reads (every dimension) skip the accessor and go straight to foreground + throttle. Never un-latch (no flapping).
- **Tier 1 (`:fabric:test -x runGameTest -x runClientGameTest`) and `:paper:test` are green at every commit.**

## Known rough edges (accepted, stated)

1. **The fallback path cannot be reached by any automated test.** Gametests and soak run plain vanilla chunk IO, where the executor is always non-null. The C2ME branch only exists with a chunk-IO mod loaded, which no dev/CI environment runs. We pin every *reachable* piece — the null/throwable predicate, the throttle control law, the enable→narrow-headroom→recover wiring — as `common/` unit tests, and rely on a **manual C2ME smoke test** (Task 7) for the end-to-end. This is the same test-reachability limitation the Paper Moonrise LOW path already lives with.
2. **B is self-restraint, not priority.** On a C2ME server, LOD reads do not jump behind gameplay reads at the scheduler; LSS just submits fewer of them when its own read latency rises. This is strictly weaker than A's true priority but is the best a portable, coupling-free mechanism can do, and its worst case never harms gameplay.
3. **Latency proxy conflates causes.** The throttle reads high latency as "back off" whether the cause is gameplay contention or LSS's own deep backlog. Backing off on self-inflicted load is harmless (it still reduces total IO pressure); the imprecision only means LSS is occasionally more conservative than strictly necessary.

---

## File Structure

**Create:**
- `common/src/main/java/dev/vox/lss/common/processing/AdaptiveReadThrottle.java` — restored from `bed0843`.
- `common/src/test/…/AdaptiveReadThrottleTest.java` — restored control-law tests (lives under `fabric/src/test` per the repo's test layout for `common/` classes; match where `AbstractChunkDiskReaderTest` lives).

**Modify:**
- `common/…/processing/AbstractChunkDiskReader.java` — runtime-enableable throttle; `hasHeadroom()` consults it; feed `recordLatency` at real completions; expose throttle state for diagnostics.
- `common/…/processing/DiskReaderDiagnostics.java` — `getSubmittedCount()`/`getCompletedCount()` getters if absent (the throttle's in-flight measure); a diag line for the throttle.
- `common/…/LSSConstants.java` — `ADAPTIVE_READ_TARGET_LATENCY_MS = 20`.
- `fabric/…/server/ChunkDiskReader.java` — detection + one-way latch + enable-throttle + one warn; upgrade the existing `f65a447` fail-safe.
- `paper/…/PaperChunkDiskReader.java` — a comment only: Moonrise always compatible, throttle hook unused.
- `common/…/DiagnosticsFormatter.java` (+ platform diag callers) — show the throttle when engaged.
- `README.md`, `CLAUDE.md`, `docs/planning/read-scheduler-design.md` — A+B documentation.
- The `c2me-background-read-incompat` memory + CLAUDE flake note — "now throttles, not just foreground."

**Test files touched:** `AdaptiveReadThrottleTest` (restore), `AbstractChunkDiskReaderTest` (enable→headroom→recover), `ChunkDiskReaderTest` (predicate exists; add the throwable-latch case), `DiagnosticsFormatterTest` (golden line if the diag output is golden-pinned).

---

## Task 1: Restore `AdaptiveReadThrottle` (pure control law, no wiring)

Bring the class and its tests back verbatim from the prototype; they were green there and have no dependency on the wiring.

- [ ] **Step 1:** `git show worktree-agent-a87a5c55fb11ce30e:common/src/main/java/dev/vox/lss/common/processing/AdaptiveReadThrottle.java > common/src/main/java/dev/vox/lss/common/processing/AdaptiveReadThrottle.java`. Confirm its only import is `dev.vox.lss.common.LSSConstants` (no `net.minecraft`).
- [ ] **Step 2:** Restore the control-law tests: `git show worktree-agent-a87a5c55fb11ce30e:fabric/src/test/java/dev/vox/lss/common/processing/AdaptiveReadThrottleTest.java > …` (path per the repo's `common/`-tests-live-in-fabric layout — confirm by where `AbstractChunkDiskReaderTest` sits).
- [ ] **Step 3:** `LSSConstants`: add `public static final int ADAPTIVE_READ_TARGET_LATENCY_MS = 20;` (the prototype's default). Keep it a plain constant — no clamp, no config field.
- [ ] **Step 4:** `./gradlew :fabric:test --tests '*AdaptiveReadThrottleTest' -x runGameTest -x runClientGameTest` → green (the 14 control-law tests).
- [ ] **Step 5:** Commit: `read-throttle: restore AdaptiveReadThrottle control law (A+B groundwork)`.

## Task 2: Runtime-enableable throttle in `AbstractChunkDiskReader`, expressed through `hasHeadroom()`

The throttle is off (null) by default and enabled once, at runtime, by the platform reader's incompatibility detection. It never bounces — it narrows admission.

- [ ] **Step 1:** Fields + enable hook:
```java
    // Adaptive read throttle: null until a platform reader detects that its background-priority
    // path is incompatible (a chunk-IO-overhaul mod replaced vanilla IO) and calls
    // enableAdaptiveThrottleFallback(). Never set on a working-A server — A gives true priority,
    // so throttling would only cost LSS throughput. Volatile: enabled on the submit/processing
    // thread, read by hasHeadroom() (submit thread) and fed by recordLatency() (pool threads).
    private volatile AdaptiveReadThrottle throttle;

    /** Idempotently enable the adaptive-throttle fallback (a background-priority path reported
     *  itself incompatible). Safe to call from any thread; the first call wins. */
    protected final void enableAdaptiveThrottleFallback() {
        if (this.throttle == null) {
            synchronized (this) {
                if (this.throttle == null) {
                    this.throttle = AdaptiveReadThrottle.forPool(
                            this.threadCount, QUEUE_CAPACITY_PER_THREAD,
                            LSSConstants.ADAPTIVE_READ_TARGET_LATENCY_MS);
                }
            }
        }
    }
```
(Add a `threadCount` field if the base doesn't already retain it — the pool was built from it.)

- [ ] **Step 2:** `hasHeadroom()` consults the throttle (this is the whole integration — no bounce path):
```java
    public boolean hasHeadroom() {
        if (this.workQueue.remainingCapacity() <= 0) return false;   // pool queue full (unchanged)
        var t = this.throttle;                                        // null on the working-A path
        if (t != null) {
            int inFlight = (int) Math.max(0, this.diag.getSubmittedCount() - this.diag.getCompletedCount());
            if (!t.canSubmit(inFlight)) return false;   // throttle: retain the read, want-set re-declares
        }
        return true;
    }
```
Add `getSubmittedCount()`/`getCompletedCount()` to `DiskReaderDiagnostics` if absent (they back the existing `recordSubmitted`/`recordCompleted`).

- [ ] **Step 3:** Feed the throttle the **real** read latencies only (not the 0-latency bounce/error-before-IO paths that poison the EWMA). At each real completion in `readAndDeliver` (the error/not-found/all-air/success tails that call `recordCompleted(System.nanoTime() - startNs)`), also feed the throttle. Factor a tiny helper to avoid four edits drifting:
```java
    private void recordRealCompletion(long elapsedNanos) {
        this.diag.recordCompleted(elapsedNanos);
        var t = this.throttle;
        if (t != null) t.recordLatency(elapsedNanos);
    }
```
Replace the four `this.diag.recordCompleted(System.nanoTime() - startNs);` calls with `recordRealCompletion(System.nanoTime() - startNs);`. Leave the two 0-latency `recordCompleted(0)` bounce paths (lines ~101, ~116) as plain `diag.recordCompleted(0)` — they measured no IO.

- [ ] **Step 4:** Expose throttle state for diagnostics (Task 5 uses it):
```java
    /** The adaptive read throttle's current effective concurrency limit, or -1 when the throttle
     *  is not engaged (the normal working-A path). */
    public int adaptiveThrottleLimitOrDisabled() {
        var t = this.throttle;
        return t == null ? -1 : t.currentLimit();
    }
```

- [ ] **Step 5:** Unit-test the wiring in `AbstractChunkDiskReaderTest` (reuse its existing gated-read rig): with the throttle enabled and an artificially low limit (feed high `recordLatency` samples so AIMD collapses it), assert `hasHeadroom()` returns false at/over the limit and true again after low-latency samples recover it; with the throttle *not* enabled, `hasHeadroom()` is exactly `workQueue.remainingCapacity() > 0` (unchanged). Do not weaken the existing `hasHeadroomIsFalseExactlyWhenTheNextSubmitWouldBeRejected` pin — extend around it.

- [ ] **Step 6:** `./gradlew :fabric:test :paper:test -x runGameTest -x runClientGameTest` → green. Commit: `read-throttle: enable adaptive throttle at runtime via hasHeadroom (want-set-integrated)`.

## Task 3: Fabric — robust A-incompatibility detection that enables the fallback

Upgrade the `f65a447` fail-safe from "fall back to foreground" to "fall back to foreground **and** enable the throttle", make detection catch *any* Throwable, and latch it server-wide so the accessor is attempted at most until the first incompatibility.

- [ ] **Step 1:** Add a one-way latch + the existing one-shot warn to `ChunkDiskReader`:
```java
    // Server-wide, one-way: set the first time the background-priority path resolves as
    // incompatible (null handle OR any throwable from accessor resolution — a chunk-IO-overhaul
    // mod replaced vanilla's IOWorker). Once set, every read skips the accessor and uses the
    // foreground path with the adaptive throttle engaged. Never cleared.
    private volatile boolean backgroundIncompatible = false;
    private final AtomicBoolean backgroundUnavailableWarned = new AtomicBoolean();   // already present
```

- [ ] **Step 2:** Rework the reader selection in `submitReadDirect` / `backgroundReader` into a single guarded path:
```java
    NbtSectionSerializer.ChunkNbtRead read;
    if (!this.useBackgroundReadPriority || this.backgroundIncompatible) {
        // config rollback → foreground, no throttle; or already-latched incompatible → foreground
        // + throttle (enabled at latch time). Skip the accessor entirely once latched.
        read = (cx, cz) -> chunkMap.read(new ChunkPos(cx, cz));
    } else {
        read = backgroundReaderOrFallback(chunkMap);
    }
```
```java
    /** Resolve the IOWorker background-priority reader, or latch incompatible + fall back. Any
     *  null handle OR any Throwable from resolution (a chunk-IO-overhaul mod changed vanilla's
     *  internals) latches the fallback: enable the adaptive throttle so LOD reads still yield to
     *  gameplay, warn ONCE, and read foreground. Detection must never itself throw. */
    private NbtSectionSerializer.ChunkNbtRead backgroundReaderOrFallback(ChunkMap chunkMap) {
        PriorityConsecutiveExecutor executor = null;
        RegionFileStorage storage = null;
        try {
            var worker = (AccessorIOWorker) ((AccessorSimpleRegionStorage) chunkMap).lss$getWorker();
            executor = worker.lss$getConsecutiveExecutor();
            storage = worker.lss$getStorage();
        } catch (Throwable t) {
            // fall through to the incompatible branch below with executor/storage still null
        }
        if (backgroundReadUnavailable(executor, storage)) {
            this.backgroundIncompatible = true;         // latch: subsequent reads skip the accessor
            enableAdaptiveThrottleFallback();            // B protects gameplay via self-restraint
            if (this.backgroundUnavailableWarned.compareAndSet(false, true)) {
                LSSLogger.warn("Background-priority disk reads unavailable — vanilla's IOWorker"
                        + " executor is absent, which a chunk-IO-overhaul mod (e.g. C2ME) causes by"
                        + " replacing vanilla chunk IO. LSS switched to adaptive read throttling so"
                        + " LOD reads still yield to gameplay. Set useBackgroundReadPriority=false"
                        + " to disable read protection entirely.");
            }
            return (cx, cz) -> chunkMap.read(new ChunkPos(cx, cz));
        }
        return backgroundRead(executor, storage);
    }
```
`backgroundReadUnavailable(Object, Object)` and `backgroundRead(...)` already exist from `f65a447`. Keep `backgroundReadUnavailable` as the unit-tested predicate.

- [ ] **Step 3:** Tests. The pure predicate test (`ChunkDiskReaderTest.backgroundReadIsUnavailableWhenEitherHandleIsNull`) already exists — keep it. Add a seam-level test that a *throwing* resolver latches + enables the throttle: extract the "resolve handles" step behind a package-private overridable/`@FunctionalInterface` seam so a test can inject a resolver that throws, then assert (a) `backgroundIncompatible` latched, (b) `adaptiveThrottleLimitOrDisabled() >= 0` (throttle engaged), (c) the warn fired exactly once across two triggering reads. If the seam is too invasive, at minimum unit-test the latch+enable+warn-once logic via a small extracted method `onBackgroundIncompatible()` and pin *that*.

- [ ] **Step 4:** `./gradlew :fabric:test -x runGameTest -x runClientGameTest` + `./gradlew :fabric:runGameTest` (the existing `backgroundPriorityReadMatchesForegroundReadForDiskLoadedColumn` still passes — dev has a real executor, so the compatible path is unchanged). Commit: `fabric: fall back to adaptive throttle (not just foreground) when IOWorker is incompatible`.

## Task 4: Paper — confirm no engagement (comment only)

Paper's A path (`MoonriseRegionFileIO.loadDataAsync`) is a direct compile-time reference; a Paper build without Moonrise fails loudly at class-load, never as a silent null. So Paper never latches and the throttle never engages there.

- [ ] **Step 1:** In `PaperChunkDiskReader`, add a one-line comment at the Moonrise reader: the shared base's `enableAdaptiveThrottleFallback()` hook exists but Paper's Moonrise path is always compatible on supported versions, so Paper never calls it (Paper's read protection is Moonrise `Priority.LOW`, not the throttle). No functional change.
- [ ] **Step 2:** `./gradlew :paper:test` → green (unchanged). Commit folded into Task 6's docs commit if trivial, or its own: `paper: note the throttle fallback is Fabric-only (Moonrise always present)`.

## Task 5: Diagnostics — surface the throttle so the fallback is observable

The fallback path has no automated end-to-end test; the operator (and our Task 7 manual smoke test) needs to *see* it engaged.

- [ ] **Step 1:** Add a diag line, shown only when engaged: in the reader's diagnostics (via `adaptiveThrottleLimitOrDisabled()`), when the value is `>= 0` render e.g. `read throttle: ENGAGED (limit N/MAX)`; when `-1`, render nothing (or `read throttle: off (background priority active)`). Wire through `DiagnosticsFormatter` and both platforms' `/lsslod diag` output.
- [ ] **Step 2:** If `DiagnosticsFormatterTest` golden-pins the diag block, update the golden for the off case (the common case) and add an engaged-case line. Keep the off-case output stable so existing goldens barely move.
- [ ] **Step 3:** `./gradlew :fabric:test :paper:test -x runGameTest -x runClientGameTest` → green. Commit: `diag: surface the adaptive read throttle when the background path fell back`.

## Task 6: Docs

- [ ] **Step 1:** `CLAUDE.md` reader entries: A is the default (Fabric IOWorker BACKGROUND / Paper Moonrise LOW); on Fabric, if a chunk-IO-overhaul mod makes the IOWorker executor unavailable, LSS latches once, warns once, and **falls back to the adaptive read throttle** (`AdaptiveReadThrottle`, which yields to gameplay via self-restraint by narrowing `hasHeadroom()` — the want-set router then retains and the client re-declares). `useBackgroundReadPriority=false` disables all read protection. Update the existing `ChunkDiskReader` bullet (currently ends at "falls back to `chunkMap.read`").
- [ ] **Step 2:** `docs/planning/read-scheduler-design.md`: add a §10.4 (or extend §10.1's pick) — **shipped resolution is A + B**: A default, B as the auto-engaged Fabric fallback for chunk-IO-overhaul incompatibility, integrated through `hasHeadroom()` (want-set makes B a headroom modifier, not a bounce). This supersedes both the original "pick B" (§10.1) and the later "A everywhere" decision — record that the C2ME incident on 2026-07-16 drove the synthesis.
- [ ] **Step 3:** `README.md`: the `useBackgroundReadPriority` row already exists; append that on servers with chunk-IO-overhaul mods LSS automatically switches to adaptive throttling.
- [ ] **Step 4:** Update the `c2me-background-read-incompat` memory + the CLAUDE.md Known-issues note: the fallback is now foreground **+ adaptive throttle** (not bare foreground), so gameplay stays protected on C2ME servers.
- [ ] **Step 5:** Commit: `docs: A+B read protection — adaptive-throttle fallback on chunk-IO-mod incompatibility`.

## Task 7: Validation

- [ ] **Step 1: Automated gauntlet** (the compatible path + the control law + the wiring): `./gradlew :fabric:test -x runClientGameTest` (Tier 1+2), `./gradlew :fabric:runClientGameTest` (Tier 3), `./gradlew :paper:test :paper:shadowJar`. All green. The working-A gametest and all soak scenarios exercise the *compatible* path (throttle null), unchanged.
- [ ] **Step 2: Soak** (regression, compatible path): `./scripts/soak.sh fresh-backfill` and `SOAK_PLATFORM=paper ./scripts/soak.sh all` — verdicts PASS (read from `verdict.json`, never a piped exit code). Confirms A+B did not perturb the normal path. Folia not runnable (26.2 unpublished).
- [ ] **Step 3: MANUAL C2ME smoke test — the decisive one, and the only end-to-end for the fallback.** Deploy the built Fabric jar to a server with C2ME installed (the `test-server.sh` Fabric server already installs C2ME). Confirm:
  1. **Exactly ONE** `Background-priority disk reads unavailable …` warning in the log — not a per-read storm, not one-per-dimension.
  2. **No `NullPointerException`** from `ChunkDiskReader` at all.
  3. LOD terrain still streams (Voxy renders distant chunks).
  4. `/lsslod diag` shows the read throttle **ENGAGED** and its limit adapting under load (open the world, fly around generating/loading chunks, watch the limit move).
  Record the outcome in the PR description. This is the validation neither gametests nor soak can provide (they run plain vanilla IO).
- [ ] **Step 4:** Commit any doc/flake-catalog updates surfaced during validation.

---

## Self-Review

- **A default preserved:** Task 3 only changes behavior when detection latches incompatible; the compatible path (`backgroundRead`) is byte-identical to today. ✅
- **B only on incompatibility:** the throttle is null unless `enableAdaptiveThrottleFallback()` is called, which happens only in the incompatible branch. ✅
- **One warn:** `backgroundUnavailableWarned.compareAndSet(false, true)` guards the single log; the latch prevents re-entry into detection after the first incompatibility. ✅
- **Fail-safe detection:** resolution wrapped in `catch (Throwable)`, null-or-throw both route to fallback; detection cannot itself throw. ✅
- **Want-set integration:** the throttle narrows `hasHeadroom()`, so a throttled read is retained by the router (`NO_DISK_HEADROOM`) and re-declared — no new bounce, no `rate_limited` (retired), consistent with v17. ✅
- **Rollback intact:** `useBackgroundReadPriority=false` → foreground, no throttle, unchanged. ✅
- **Test-reachability honesty:** the fallback end-to-end is manual (no C2ME in CI); every reachable unit is pinned. Stated in rough edge #1 and Task 7. ✅
- **Config surface unchanged:** no new user knob; target latency is a constant. ✅
