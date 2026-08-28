package dev.vox.lss.compat;

import dev.vox.lss.common.LSSLogger;
import dev.vox.lss.api.LSSApi;
import dev.vox.lss.api.VoxelColumnConsumer;
import dev.vox.lss.api.VoxelColumnData;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Pure reflection bridge to Voxy — zero compile-time dependency.
 * Uses Voxy's {@code rawIngest} API to pass MC-native {@link net.minecraft.world.level.chunk.LevelChunkSection}
 * and {@link net.minecraft.world.level.chunk.DataLayer} objects directly, eliminating all
 * intermediate ID translation.
 */
class VoxyCompat {

    // Log-sweep hygiene (2026-08-13): per-column/per-frame failure sites aggregate to
    // one line/min — a persistent condition must not flood the client log.
    private static final dev.vox.lss.common.LogThrottle INGEST_FAIL_WARN =
            new dev.vox.lss.common.LogThrottle(60_000);
    // WorldIdentifier.of(Level) → Object
    private static MethodHandle worldIdentifierOf;

    // VoxelIngestService.rawIngest(Object worldId, LevelChunkSection, int cx, int sy, int cz, DataLayer blockLight, DataLayer skyLight)
    private static MethodHandle rawIngest;

    // VoxyConfig — lazily initialized to avoid premature class loading
    private static volatile MethodHandle getVoxyConfig;
    private static volatile MethodHandle getSectionRenderDist;

    // Ingest-backlog probe (issue #71): VoxyCommon.getInstance() → VoxyInstance
    // .getIngestService() → VoxelIngestService.getTaskCount(). Resolved in init() but in a
    // SEPARATE try/catch from the ingest handles — a Voxy that renames this surface must
    // not cost the ingest bridge; the probe then permanently reports -1 (no signal).
    private static MethodHandle voxyGetInstance;
    private static MethodHandle getIngestService;
    private static MethodHandle getTaskCount;

    /** Resolves the reflected Voxy class names — test seam, see {@link #resetSeams()}. */
    @FunctionalInterface
    interface ClassResolver {
        Class<?> resolve(String name) throws ClassNotFoundException;
    }

    /** Receives the bridge's ingest-failure reports — test seam, see {@link #resetSeams()}. */
    @FunctionalInterface
    interface ReportSink {
        void report(ResourceKey<Level> dimension, int chunkX, int chunkZ);
    }

    // Test seams: default-wired to production by resetSeams() (zero behavior change);
    // VoxyCompatTest swaps them to drive the bridge's failure ladder against stub classes.
    static ClassResolver classResolver;
    static ReportSink reportSink;
    static Consumer<VoxelColumnConsumer> consumerRegistrar;

    static {
        resetSeams();
    }

    /** Restores production wiring for the test seams. */
    static void resetSeams() {
        classResolver = Class::forName;
        reportSink = LSSApi::reportIngestFailure;
        consumerRegistrar = LSSApi::registerColumnConsumer;
    }

    static boolean init() {
        try {
            var lookup = MethodHandles.lookup();

            Class<?> worldIdClass = classResolver.resolve("me.cortex.voxy.commonImpl.WorldIdentifier");
            worldIdentifierOf = lookup.findStatic(worldIdClass, "of",
                    MethodType.methodType(worldIdClass, Level.class))
                    .asType(MethodType.methodType(Object.class, Level.class));

            Class<?> ingestClass = classResolver.resolve("me.cortex.voxy.common.world.service.VoxelIngestService");
            rawIngest = lookup.findStatic(ingestClass, "rawIngest",
                    MethodType.methodType(boolean.class,
                            worldIdClass, LevelChunkSection.class,
                            int.class, int.class, int.class,
                            DataLayer.class, DataLayer.class));

            initBacklogProbe(lookup, ingestClass);

            // Register column consumer — an anonymous class, not a lambda, so it can
            // override pendingIngestBacklog() with the live Voxy queue depth.
            var bridgeDead = new AtomicBoolean();
            VoxelColumnConsumer consumer = new VoxelColumnConsumer() {
                @Override
                public int pendingIngestBacklog() {
                    return probeIngestBacklog();
                }

                @Override
                public void onVoxelColumnReceived(ClientLevel level,
                                                  ResourceKey<Level> dimension,
                                                  int chunkX, int chunkZ,
                                                  VoxelColumnData columnData) {
                    ingestColumn(bridgeDead, level, dimension, chunkX, chunkZ, columnData);
                }
            };
            consumerRegistrar.accept(consumer);

            LSSLogger.info("Voxy detected — registered raw ingest bridge");
            return true;
        } catch (ClassNotFoundException e) {
            LSSLogger.warn("Voxy compat: class not found — " + e.getMessage());
            return false;
        } catch (NoSuchMethodException e) {
            LSSLogger.warn("Voxy compat: method not found — " + e.getMessage());
            return false;
        } catch (Throwable e) {
            LSSLogger.error("Failed to initialize Voxy compat", e);
            return false;
        }
    }

    /** The ingest body — the pre-#71 consumer lambda, unchanged, hoisted so the anonymous
     *  consumer class stays a thin dispatch shell. */
    private static void ingestColumn(AtomicBoolean bridgeDead,
                                     ClientLevel level,
                                     ResourceKey<Level> dimension, int chunkX, int chunkZ,
                                     VoxelColumnData columnData) {
        if (bridgeDead.get()) {
            // The latch stops the churn, but the receive handler already stamped this
            // position received (ts>0): returning SILENTLY would keep — and persist —
            // a stamp for data Voxy never stored, poisoning the cache across sessions
            // (after a Voxy upgrade every such position resyncs up_to_date and stays
            // a hole). Report instead: each position parks honestly (ts=-1 +
            // session-satisfied) at the bounded failure cap.
            reportSink.report(dimension, chunkX, chunkZ);
            return;
        }
        try {
            Object worldId = worldIdentifierOf.invoke(level);
            if (worldId == null) {
                // Voxy has no storage for this world yet (e.g. mid open-to-LAN
                // transition) — without the report the column is silently lost
                // and never re-requested.
                reportSink.report(dimension, chunkX, chunkZ);
                return;
            }
            boolean allAccepted = true;
            for (var s : columnData.sections()) {
                // H-12 guard: LSS ships "absent light layer = null" (the universal
                // "absent means all-zero" wire default). Voxy renders a null sky-light
                // layer as full daylight, so a no-sky dimension (nether/end,
                // hasSkyLight()==false) — where MC never stores sky light, so every
                // section arrives null — would light its topmost surfaces as if skylit
                // until vanilla loaded the chunk. Hand Voxy an explicit all-zero
                // (present, non-empty) DataLayer so those surfaces render dark. Overworld
                // skylit surfaces ship a real non-null layer and are unaffected.
                DataLayer skyLight = s.skyLight() != null ? s.skyLight() : new DataLayer(new byte[2048]);
                allAccepted &= (boolean) rawIngest.invoke(worldId, s.section(),
                        chunkX, s.sectionY(), chunkZ,
                        s.blockLight(), skyLight);
            }
            if (!allAccepted) {
                reportSink.report(dimension, chunkX, chunkZ);
            }
        } catch (LinkageError e) {
            // Incompatible Voxy: this will never succeed for any column. Latch to
            // stop re-serve churn, but keep the stamps honest — this column (and every
            // later one, via the latch branch above) still reports so it parks at
            // ts=-1 instead of retaining a fabricated received-stamp.
            bridgeDead.set(true);
            LSSLogger.error("Voxy raw ingest is incompatible with this Voxy version — "
                    + "disabling the bridge for this session", e);
            reportSink.report(dimension, chunkX, chunkZ);
        } catch (Throwable e) {
            if (e instanceof Error && !(e instanceof AssertionError)) throw (Error) e;
            long n = INGEST_FAIL_WARN.recordAndTryAcquire(System.nanoTime() / 1_000_000);
            if (n > 0) {
                LSSLogger.error("Voxy raw ingest failed (" + n
                        + " failed ingest(s) since the last report; each is re-served)", e);
            }
            reportSink.report(dimension, chunkX, chunkZ);
        }
    }

    /**
     * Resolve the ingest-backlog probe chain. SEPARATE failure domain from the ingest
     * handles: a Voxy that renames this surface costs only the pacing signal (probe
     * permanently -1), never the bridge. All three handles are (re)assigned atomically-
     * enough for the single-threaded init/poll contract: nulled first, so a failed
     * re-resolve can never leave a stale mixed set.
     */
    private static void initBacklogProbe(MethodHandles.Lookup lookup, Class<?> ingestClass) {
        voxyGetInstance = null;
        getIngestService = null;
        getTaskCount = null;
        try {
            Class<?> voxyCommonClass = classResolver.resolve("me.cortex.voxy.commonImpl.VoxyCommon");
            Class<?> voxyInstanceClass = classResolver.resolve("me.cortex.voxy.commonImpl.VoxyInstance");
            var instanceHandle = lookup.findStatic(voxyCommonClass, "getInstance",
                    MethodType.methodType(voxyInstanceClass))
                    .asType(MethodType.methodType(Object.class));
            var serviceHandle = lookup.findVirtual(voxyInstanceClass, "getIngestService",
                    MethodType.methodType(ingestClass))
                    .asType(MethodType.methodType(Object.class, Object.class));
            var countHandle = lookup.findVirtual(ingestClass, "getTaskCount",
                    MethodType.methodType(int.class))
                    .asType(MethodType.methodType(int.class, Object.class));
            // Assign only once ALL three resolved — a partial chain must read as absent.
            voxyGetInstance = instanceHandle;
            getIngestService = serviceHandle;
            getTaskCount = countHandle;
        } catch (Throwable e) {
            LSSLogger.warn("Voxy compat: ingest-backlog probe unavailable — request pacing "
                    + "will not see Voxy's ingest queue (" + e + ")");
        }
    }

    /**
     * Live Voxy ingest backlog in sections, or -1 when unavailable: probe unresolved, no
     * VoxyInstance yet (pre-join / post-shutdown — routine, never logged), or any throw
     * (contained; true Errors propagate). Main client thread, up to 20 Hz — the terminal
     * call reads a semaphore permit count, safe at this cadence.
     */
    static int probeIngestBacklog() {
        var instanceProbe = voxyGetInstance;
        if (instanceProbe == null) return -1;
        try {
            Object instance = (Object) instanceProbe.invokeExact();
            if (instance == null) return -1;
            Object service = (Object) getIngestService.invokeExact(instance);
            if (service == null) return -1;
            return (int) getTaskCount.invokeExact(service);
        } catch (Throwable e) {
            if (e instanceof Error err && !(e instanceof AssertionError)) throw err;
            return -1;
        }
    }

    // ---- alias-corroboration storage probe (cache-alias-keying-and- ----
    // ---- reset-override-plan.md §2.2)                                ----

    // OWN two-handle failure domain (VoxyCommon.getInstance +
    // VoxyClientInstance.getStorageBasePath), deliberately NOT initResetDomain: the
    // reset domain's renderer-holder rung is the known-unstable name, and its
    // all-or-nothing resolution would fail the corroboration probe on exactly the
    // Voxy versions where only the reset COMMAND should degrade. Same lazy
    // 0/1/-1 pattern as the reset domain.
    private static volatile int storageProbeState;
    private static MethodHandle storageProbeGetInstance;
    private static MethodHandle storageProbeGetBasePath;

    /** Test seam: forget the resolved storage-probe domain. */
    static void resetStorageProbeForTest() {
        storageProbeState = 0;
    }

    private static boolean initStorageProbe() {
        int state = storageProbeState;
        if (state != 0) return state > 0;
        try {
            var lookup = MethodHandles.lookup();
            Class<?> voxyCommonClass = classResolver.resolve("me.cortex.voxy.commonImpl.VoxyCommon");
            Class<?> voxyInstanceClass = classResolver.resolve("me.cortex.voxy.commonImpl.VoxyInstance");
            Class<?> clientInstanceClass = classResolver.resolve("me.cortex.voxy.client.VoxyClientInstance");
            var getInstanceH = lookup.findStatic(voxyCommonClass, "getInstance",
                    MethodType.methodType(voxyInstanceClass))
                    .asType(MethodType.methodType(Object.class));
            var basePathH = lookup.findVirtual(clientInstanceClass, "getStorageBasePath",
                    MethodType.methodType(java.nio.file.Path.class))
                    .asType(MethodType.methodType(java.nio.file.Path.class, Object.class));
            // Assign only once BOTH resolved — a partial chain must read as absent.
            storageProbeGetInstance = getInstanceH;
            storageProbeGetBasePath = basePathH;
            storageProbeState = 1;
            return true;
        } catch (Throwable e) {
            if (e instanceof VirtualMachineError vme) throw vme;
            storageProbeState = -1;
            LSSLogger.warn("Voxy storage probe unavailable on this Voxy version — "
                    + "cacheAddressAliases will fall back per connection (" + e + ")");
            return false;
        }
    }

    /**
     * The live Voxy storage root's directory NAME, or null on ANY failure — probe
     * unresolvable, no live instance, a null/rootless path, or a throw (contained;
     * VirtualMachineErrors propagate). The alias corroboration compares this against
     * the group's canonical (fail closed on null — plan §2.2). One log line of its
     * own per call site expectation: the observation itself is the record.
     */
    static String observeStorageDirName() {
        if (!initStorageProbe()) return null;
        try {
            Object instance = (Object) storageProbeGetInstance.invokeExact();
            if (instance == null) {
                LSSLogger.info("Voxy storage probe: no live Voxy instance to observe");
                return null;
            }
            var path = (java.nio.file.Path) storageProbeGetBasePath.invokeExact(instance);
            if (path == null || path.getFileName() == null) {
                LSSLogger.info("Voxy storage probe: the live instance reports no storage root");
                return null;
            }
            String name = path.getFileName().toString();
            LSSLogger.info("Voxy storage probe: live root directory is '" + name + "'");
            return name;
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError vme) throw vme;
            LSSLogger.warn("Voxy storage probe failed (" + t + ") — treating as unobservable");
            return null;
        }
    }

    // ---- /lss reset: Voxy teardown + wipe + rebuild (v0.11.0 stage D — ----
    // ---- client-reset-command-and-cache-relocation-plan.md Part 1)      ----

    /** A lifecycle step that may throw (reflective invokes surface Throwable). */
    @FunctionalInterface
    interface ThrowingRunnable {
        void run() throws Throwable;
    }

    /**
     * Seam bundle for {@link #resetVoxy}: every side effect of the reset ladder,
     * injectable so JUnit drives the full ordering without MC (the
     * {@code AntiXrayCompat.buildEngineProbe} injectable shape).
     *
     * <p>{@code rendererShutdown} resolves the renderer teardown AT CALL TIME:
     * {@code null} = the holder is unresolvable on this Voxy version — the ladder must
     * ABORT before {@code shutdownInstance} (skipping renderer-first teardown risks
     * {@code VoxyInstance.shutdown}'s {@code isWorldUsed} busy-wait freezing the main
     * thread — an unbounded freeze, not a throw); a no-op Runnable = levelRenderer is
     * null, nothing holds sections, teardown may proceed (Voxy's own reload skips the
     * renderer the same way).
     */
    record ResetHooks(java.util.function.Supplier<Object> instance,
                      java.util.function.Function<Object, java.nio.file.Path> storagePath,
                      java.util.function.Supplier<java.nio.file.Path> fallbackPath,
                      java.util.function.Supplier<Runnable> rendererShutdown,
                      ThrowingRunnable shutdownInstance,
                      ThrowingRunnable createInstance,
                      java.util.function.Consumer<java.nio.file.Path> wipe,
                      Runnable gc,
                      Runnable allChanged,
                      java.util.function.Predicate<java.nio.file.Path> wipeContained) {
}

    /**
     * The reset ladder — mirrors {@code /voxy reload}'s sequence
     * (research/voxy VoxyCommands.java:80-98) with the disk wipe inserted into the
     * instance-down window. Order is load-bearing and review-pinned:
     * <ol>
     *   <li>wipe root read from the LIVE instance's {@code getStorageBasePath()} BEFORE
     *       shutdown, then CROSS-CHECKED against this connection's own derived path
     *       (the stage-D review MAJOR): a Flashback replay's storage override is the
     *       ORIGIN server's real store path recorded into the replay meta — it PASSES
     *       directory containment, so containment alone cannot protect it. The two
     *       paths are equal in every non-override session (the live path is computed by
     *       the same derivation); a mismatch means an override is active and the wipe
     *       is SKIPPED with the {@code RESET_WIPE_SKIPPED} outcome;</li>
     *   <li>renderer down BEFORE the instance (the {@code isWorldUsed} busy-wait trap);
     *       unresolvable holder = abort the Voxy half entirely (fail-safe);</li>
     *   <li>{@code shutdownInstance()} — a throw means the instance is already nulled
     *       and storage may hold open handles: the wipe is SKIPPED (deleting over open
     *       handles is the Windows partial-wipe trap) but {@code createInstance()} is
     *       still attempted (Voxy is instanceless at that point — recovery);</li>
     *   <li>wipe (containment inside {@link #wipeVoxyStore}), gc (native storage handle
     *       parity with Voxy's own reload), create, {@code allChanged()}.</li>
     * </ol>
     * A null live instance skips shutdown AND create (never create an instance Voxy
     * itself didn't have — config-disabled/GPU cases) but still wipes via the fallback
     * derivation (no live storage to race). Every non-{@link VirtualMachineError} throw
     * from a hook is contained to a feedback-driving outcome — the coordinator's LSS
     * flush must always run (a skipped flush after a wipe persists false stamps).
     *
     * @param forceWipe issue #4's {@code /lss reset voxy-force confirm}: the user has
     *                  been shown both roots and accepted the risk, so step 1's equality
     *                  comparison is waived — and NOTHING else is. The null guards stand,
     *                  the containment fence inside {@link #wipeVoxyStore} stands, and
     *                  {@code false} reproduces the pre-#4 behaviour exactly.
     */
    static ModCompat.VoxyResetReport resetVoxy(ResetHooks h, boolean forceWipe,
                                               java.nio.file.Path grantedLiveRoot) {
        Object instance;
        try {
            instance = h.instance().get();
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError vme) throw vme;
            // "Can't tell" must never route into the destructive fallback-wipe branch.
            LSSLogger.warn("Voxy reset: instance probe failed — aborting the Voxy half (" + t + ")");
            return ModCompat.VoxyResetReport.of(ModCompat.VoxyResetOutcome.UNAVAILABLE);
        }
        if (instance == null) {
            java.nio.file.Path derived = h.fallbackPath().get();
            if (forceWipe) {
                // The grant was armed for a LIVE root that no longer exists — under
                // force this branch wipes NOTHING (panel fix: the no-instance fallback
                // wipe targets the DERIVED root, which on the OVERRIDDEN shape is a
                // different directory than the one the user was shown; shown==wiped
                // must hold at the wipe itself).
                LSSLogger.warn("Voxy reset (forced): the live instance vanished before "
                        + "the wipe — nothing was deleted");
                return new ModCompat.VoxyResetReport(
                        ModCompat.VoxyResetOutcome.UNAVAILABLE,
                        VoxyStorageOverride.Verdict.NO_INSTANCE, null, derived, false, true);
            }
            if (derived != null) h.wipe().accept(derived);
            // No cross-check happens on this branch and the log says nothing about one,
            // so the feedback must not either — the flag mirrors the log exactly.
            return new ModCompat.VoxyResetReport(
                    ModCompat.VoxyResetOutcome.WIPED_NO_INSTANCE,
                    VoxyStorageOverride.Verdict.NO_INSTANCE, null, derived, false, false);
        }
        java.nio.file.Path liveRoot;
        try {
            liveRoot = h.storagePath().apply(instance);
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError vme) throw vme;
            LSSLogger.warn("Voxy reset: getStorageBasePath failed — wipe will be skipped (" + t + ")");
            liveRoot = null;
        }
        // With a live root this is the cross-check's own read, in the hook order the
        // ladder tests pin. WITHOUT one it is a report-only read (issue #4 follow-up):
        // the branch that most needs a path to show the user was the one branch that
        // never read one, so it is fetched here — contained, because a message must
        // never be the thing that breaks the reset.
        java.nio.file.Path expected =
                liveRoot != null ? h.fallbackPath().get() : fallbackPathForReport(h);
        // Force applies ONLY to the exact root the consumed grant was armed for — a
        // live root that moved since the stage-2 probe falls back to the ordinary
        // comparison (declined, nothing wiped) rather than deleting an unshown path.
        boolean forceApplies = forceWipe && grantedLiveRoot != null
                && VoxyStorageOverride.samePath(liveRoot, grantedLiveRoot);
        java.nio.file.Path root =
                VoxyStorageOverride.shouldWipeLiveRoot(liveRoot, expected, forceApplies)
                        ? liveRoot : null;
        var verdict = VoxyStorageOverride.verdict(true, true, liveRoot, expected);
        boolean liveContained = liveRootContained(h, liveRoot);
        boolean wipeDeclined = root == null;
        if (wipeDeclined) {
            // Step 1 above declined the wipe. The report is the user-facing half of #4:
            // both roots, the likely cause, and (unless they already forced) the override.
            LSSLogger.warn(String.join(System.lineSeparator(),
                    VoxyStorageOverride.wipeSkippedLines(verdict, liveRoot, expected,
                            !forceWipe && liveContained)));
        } else if (forceApplies && verdict != VoxyStorageOverride.Verdict.MATCHES) {
            LSSLogger.warn("Voxy reset: FORCED wipe of " + liveRoot + " — it does NOT match this "
                    + "connection's derived root " + VoxyStorageOverride.render(expected)
                    + "; the storage-override cross-check was waived on explicit user "
                    + "confirmation (containment still applies)");
        }
        Runnable rendererShutdown = h.rendererShutdown().get();
        if (rendererShutdown == null) {
            return new ModCompat.VoxyResetReport(
                    ModCompat.VoxyResetOutcome.UNAVAILABLE, verdict, liveRoot, expected,
                    liveContained, wipeDeclined);
        }
        try {
            rendererShutdown.run();
        } catch (Throwable t) {
            // A partial renderer shutdown may leave acquired sections — proceeding to
            // shutdownInstance risks the isWorldUsed busy-wait freeze. Abort.
            if (t instanceof VirtualMachineError vme) throw vme;
            LSSLogger.warn("Voxy reset: renderer shutdown failed — aborting the Voxy half", t);
            return new ModCompat.VoxyResetReport(
                    ModCompat.VoxyResetOutcome.UNAVAILABLE, verdict, liveRoot, expected,
                    liveContained, wipeDeclined);
        }
        try {
            h.shutdownInstance().run();
        } catch (Throwable t) {
            // Contained (VirtualMachineError excepted): e.g. the world-cleaner join
            // interrupt. The instance is already nulled; storage may hold open handles.
            if (t instanceof VirtualMachineError vme) throw vme;
            LSSLogger.warn("Voxy reset: shutdownInstance failed — skipping the disk wipe", t);
            try {
                h.createInstance().run();
            } catch (Throwable t2) {
                if (t2 instanceof VirtualMachineError vme) throw vme;
                LSSLogger.error("Voxy reset: createInstance failed after a failed shutdown", t2);
                return new ModCompat.VoxyResetReport(
                        ModCompat.VoxyResetOutcome.RESTART_FAILED, verdict, liveRoot, expected,
                        liveContained, wipeDeclined);
            }
            if (!runAllChangedContained(h)) {
                return new ModCompat.VoxyResetReport(
                        ModCompat.VoxyResetOutcome.RESTART_FAILED, verdict, liveRoot, expected,
                        liveContained, wipeDeclined);
            }
            return new ModCompat.VoxyResetReport(
                    ModCompat.VoxyResetOutcome.SHUTDOWN_FAILED, verdict, liveRoot, expected,
                    liveContained, wipeDeclined);
        }
        if (root != null) h.wipe().accept(root);
        h.gc().run();
        try {
            h.createInstance().run();
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError vme) throw vme;
            // The renderer stays down; every re-served column will fail ingest, bounded
            // by the ingest-failure parking caps. The feedback branch says "rejoin".
            LSSLogger.error("Voxy reset: createInstance failed — Voxy is down until rejoin", t);
            return new ModCompat.VoxyResetReport(
                    ModCompat.VoxyResetOutcome.RESTART_FAILED, verdict, liveRoot, expected,
                    liveContained, wipeDeclined);
        }
        if (!runAllChangedContained(h)) {
            return new ModCompat.VoxyResetReport(
                    ModCompat.VoxyResetOutcome.RESTART_FAILED, verdict, liveRoot, expected,
                    liveContained, wipeDeclined);
        }
        return new ModCompat.VoxyResetReport(
                wipeDeclined ? ModCompat.VoxyResetOutcome.RESET_WIPE_SKIPPED
                        : ModCompat.VoxyResetOutcome.RESET,
                verdict, liveRoot, expected, liveContained, wipeDeclined);
    }

    /** Containment of the live root for REPORTING (the wipe itself re-checks inside
     *  {@link #wipeVoxyStore}). Throw-contained; doubt reads as not-contained, which
     *  only suppresses the voxy-force offer — the safe direction. */
    private static boolean liveRootContained(ResetHooks h, java.nio.file.Path liveRoot) {
        if (liveRoot == null) return false;
        try {
            return h.wipeContained().test(liveRoot);
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError vme) throw vme;
            return false;
        }
    }

    /** The derived root read purely to enrich a message (issue #4 follow-up). Contained:
     *  a throw here must not turn a survivable reset into a failed one — the report just
     *  degrades to "&lt;unresolved&gt;". */
    private static java.nio.file.Path fallbackPathForReport(ResetHooks h) {
        try {
            return h.fallbackPath().get();
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError vme) throw vme;
            LSSLogger.warn("Voxy reset: derived root unavailable for the report (" + t + ")");
            return null;
        }
    }

    /**
     * Stage 1 of {@code /lss reset voxy-force}: read both storage roots and report
     * whether the live one would survive containment — WITHOUT touching the renderer,
     * the instance lifecycle, or the disk. The two-stage promise ("you see the path
     * before anything is deleted") is only worth as much as this method's restraint:
     * it may call {@code instance}, {@code fallbackPath} and {@code storagePath},
     * and nothing else in {@link ResetHooks}.
     */
    static ModCompat.VoxyStorageProbe probeStorage(ResetHooks h, java.nio.file.Path gameDir) {
        Object instance;
        try {
            instance = h.instance().get();
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError vme) throw vme;
            LSSLogger.warn("Voxy storage probe: instance probe failed (" + t + ")");
            return new ModCompat.VoxyStorageProbe(true,
                    VoxyStorageOverride.Verdict.UNAVAILABLE, null, null, false);
        }
        java.nio.file.Path expected;
        try {
            expected = h.fallbackPath().get();
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError vme) throw vme;
            LSSLogger.warn("Voxy storage probe: derived root unavailable (" + t + ")");
            expected = null;
        }
        if (instance == null) {
            // No live instance: the ordinary reset already wipes the derived root, so
            // there is no live root to force — the prompt says exactly that.
            return new ModCompat.VoxyStorageProbe(true,
                    VoxyStorageOverride.Verdict.NO_INSTANCE, null, expected, false);
        }
        java.nio.file.Path live;
        try {
            live = h.storagePath().apply(instance);
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError vme) throw vme;
            LSSLogger.warn("Voxy storage probe: getStorageBasePath failed (" + t + ")");
            live = null;
        }
        return new ModCompat.VoxyStorageProbe(true,
                VoxyStorageOverride.verdict(true, true, live, expected), live, expected,
                live != null && isWipeContained(live, gameDir));
    }

    /** allChanged containment: a throw means the renderer rebuild never triggered —
     *  the RESTART_FAILED "rejoin to recover" guidance is the honest one. */
    private static boolean runAllChangedContained(ResetHooks h) {
        try {
            h.allChanged().run();
            return true;
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError vme) throw vme;
            LSSLogger.error("Voxy reset: renderer rebuild (allChanged) failed", t);
            return false;
        }
    }

    /**
     * Wipe containment: the target must be strictly inside {@code <gameDir>/.voxy/saves}
     * (multiplayer/realms/UNKNOWN shapes) or be a {@code voxy} directory under
     * {@code <gameDir>/saves} (the singleplayer {@code <world>/voxy} shape — worlds
     * always live under {@code saves/}, review m3). NOTE containment is the SECOND
     * fence: a Flashback replay's override path is the origin's REAL store path and
     * passes these checks — the override cross-check in {@link #resetVoxy} is what
     * protects it (stage-D review MAJOR). Pure and package-visible for the tests.
     */
    static boolean isWipeContained(java.nio.file.Path target, java.nio.file.Path gameDir) {
        java.nio.file.Path t = target.toAbsolutePath().normalize();
        java.nio.file.Path g = gameDir.toAbsolutePath().normalize();
        java.nio.file.Path saves = g.resolve(".voxy").resolve("saves");
        if (t.startsWith(saves) && !t.equals(saves)) return true;
        return t.startsWith(g.resolve("saves"))
                && t.getFileName() != null && t.getFileName().toString().equals("voxy");
    }

    /**
     * Recursive delete of the resolved Voxy store dir, containment-gated and IO-contained
     * (any failure is logged, never thrown — a partial wipe self-heals on the next reset
     * or rebuilds through normal ingest).
     */
    static void wipeVoxyStore(java.nio.file.Path target, java.nio.file.Path gameDir) {
        if (target == null) return;
        if (!isWipeContained(target, gameDir)) {
            LSSLogger.warn("Voxy reset: wipe target " + target + " is outside the expected "
                    + "Voxy storage roots — skipping the wipe (fail-safe)");
            return;
        }
        java.nio.file.Path t = target.toAbsolutePath().normalize();
        if (!java.nio.file.Files.exists(t)) return;
        // NOTE Files.walk deliberately does NOT follow links: a symlink inside the
        // store is deleted as a LINK, its foreign target tree survives (empirically
        // verified + test-pinned). Adding FileVisitOption.FOLLOW_LINKS here would turn
        // this into a foreign-tree deleter — never do that.
        var failures = new java.util.concurrent.atomic.AtomicInteger();
        try (var walk = java.nio.file.Files.walk(t)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    java.nio.file.Files.deleteIfExists(p);
                } catch (java.io.IOException e) {
                    failures.incrementAndGet();
                    LSSLogger.warn("Voxy reset: could not delete " + p + " (" + e + ")");
                }
            });
            if (failures.get() == 0) {
                LSSLogger.info("Voxy reset: wiped " + t);
            } else {
                LSSLogger.warn("Voxy reset: partial wipe of " + t + " (" + failures.get()
                        + " paths could not be deleted)");
            }
        } catch (java.io.IOException e) {
            LSSLogger.warn("Voxy reset: wipe of " + t + " failed", e);
        }
    }

    // Reset-domain handles — resolved lazily in their OWN all-or-nothing failure domain
    // (the backlog-probe pattern): a Voxy that renames any of this surface costs only
    // the reset command ("Voxy reset unavailable"), never the ingest bridge.
    private static volatile int resetDomainState; // 0 = unresolved, 1 = ok, -1 = failed
    private static MethodHandle resetGetInstance;
    private static MethodHandle resetShutdownInstance;
    private static MethodHandle resetCreateInstance;
    private static MethodHandle resetGetStorageBasePath;
    private static Class<?> rendererHolderInterface;
    private static MethodHandle rendererHolderShutdown;
    // Rung 1 only: IVoxyRenderSystemHolder.getNullableHolder() — the 26.2 Voxy build's
    // own reload obtains the holder through this static (bytecode-verified 2026-08-13),
    // which abstracts away WHICH vanilla class carries the mixin. Null on rung 2.
    private static MethodHandle rendererHolderStaticGet;

    /** Test seam: forget the resolved reset domain (lazy-resolve reruns). */
    static void resetResetDomainForTest() {
        resetDomainState = 0;
    }

    static boolean initResetDomain() {
        int state = resetDomainState;
        if (state != 0) return state > 0;
        try {
            var lookup = MethodHandles.lookup();
            Class<?> voxyCommonClass = classResolver.resolve("me.cortex.voxy.commonImpl.VoxyCommon");
            Class<?> voxyInstanceClass = classResolver.resolve("me.cortex.voxy.commonImpl.VoxyInstance");
            // The CLIENT instance subclass carries getStorageBasePath (public in 0.2.11,
            // 0.2.18-beta and dev — javap-verified for all three at plan review).
            Class<?> clientInstanceClass = classResolver.resolve("me.cortex.voxy.client.VoxyClientInstance");
            var getInstanceH = lookup.findStatic(voxyCommonClass, "getInstance",
                    MethodType.methodType(voxyInstanceClass))
                    .asType(MethodType.methodType(Object.class));
            var shutdownH = lookup.findStatic(voxyCommonClass, "shutdownInstance",
                    MethodType.methodType(void.class));
            var createH = lookup.findStatic(voxyCommonClass, "createInstance",
                    MethodType.methodType(void.class));
            var storagePathH = lookup.findVirtual(clientInstanceClass, "getStorageBasePath",
                    MethodType.methodType(java.nio.file.Path.class))
                    .asType(MethodType.methodType(java.nio.file.Path.class, Object.class));
            // The renderer holder is the ONE unstable name (a mixin interface on a
            // vanilla render class): 0.2.18-beta's IVoxyRenderSystemHolder is the
            // primary rung (fabric.mod.json suggests >=0.2.17) — obtained via its
            // STATIC getNullableHolder(), exactly as the 26.2 Voxy build's own reload
            // does (bytecode-verified; on 26.2 the render-extract rework means the
            // carrier class is not knowable from here, and the static abstracts it
            // away). 0.2.11/dev's IGetVoxyRenderSystem.shutdownRenderer is the
            // fallback rung, obtained instanceof-style off Minecraft.levelRenderer
            // (those Voxy versions mix into LevelRenderer). Class.forName-safe —
            // Voxy classes, not MC classes.
            Class<?> holderClass;
            MethodHandle holderShutdownH;
            MethodHandle holderStaticGetH = null;
            try {
                holderClass = classResolver.resolve("me.cortex.voxy.client.core.IVoxyRenderSystemHolder");
                holderShutdownH = lookup.findVirtual(holderClass, "voxy$shutdownRenderer",
                        MethodType.methodType(void.class))
                        .asType(MethodType.methodType(void.class, Object.class));
                holderStaticGetH = lookup.findStatic(holderClass, "getNullableHolder",
                        MethodType.methodType(holderClass))
                        .asType(MethodType.methodType(Object.class));
            } catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException e) {
                holderClass = classResolver.resolve("me.cortex.voxy.client.core.IGetVoxyRenderSystem");
                holderShutdownH = lookup.findVirtual(holderClass, "shutdownRenderer",
                        MethodType.methodType(void.class))
                        .asType(MethodType.methodType(void.class, Object.class));
            }
            // Assign only once ALL resolved — a partial chain must read as absent.
            resetGetInstance = getInstanceH;
            resetShutdownInstance = shutdownH;
            resetCreateInstance = createH;
            resetGetStorageBasePath = storagePathH;
            rendererHolderInterface = holderClass;
            rendererHolderShutdown = holderShutdownH;
            rendererHolderStaticGet = holderStaticGetH;
            resetDomainState = 1;
            return true;
        } catch (Throwable e) {
            if (e instanceof VirtualMachineError vme) throw vme;
            resetDomainState = -1;
            LSSLogger.warn("Voxy reset unavailable on this Voxy version — /"
                    + dev.vox.lss.common.Brand.clientCommand()
                    + " reset will clear only the " + dev.vox.lss.common.Brand.shortName()
                    + " half (" + e + ")");
            return false;
        }
    }

    /** Production entry for {@link ModCompat#resetVoxyLods}: resolve the domain,
     *  build the production hooks, run the ladder. Main client thread only. */
    static ModCompat.VoxyResetReport resetVoxyProduction(boolean forceWipe,
                                                        java.nio.file.Path grantedLiveRoot) {
        if (!initResetDomain()) {
            return ModCompat.VoxyResetReport.of(ModCompat.VoxyResetOutcome.UNAVAILABLE);
        }
        var gameDir = dev.vox.lss.platform.LoaderServices.get().gameDir();
        return resetVoxy(productionHandleHooks(gameDir,
                VoxyCompat::fallbackVoxyBasePath,
                () -> net.minecraft.client.Minecraft.getInstance().levelRenderer,
                () -> {
                    // 26.1 line flavor: the render-extract rework is 26.2-only, so the
                    // rebuild call is the classic LevelRenderer.allChanged() here
                    // (javap-verified against the 26.1.2 artifact; 26.2 uses
                    // Minecraft.levelExtractor instead).
                    var renderer = net.minecraft.client.Minecraft.getInstance().levelRenderer;
                    if (renderer != null) renderer.allChanged();
                }), forceWipe, grantedLiveRoot);
    }

    /** Production entry for {@link ModCompat#probeVoxyStorage()} — the same resolved
     *  handles the ladder uses, so the prompt cannot show a path the wipe would not
     *  target. The renderer/allChanged hooks are inert here: {@link #probeStorage}
     *  never calls them. */
    static ModCompat.VoxyStorageProbe probeStorageProduction() {
        if (!initResetDomain()) {
            return new ModCompat.VoxyStorageProbe(true,
                    VoxyStorageOverride.Verdict.UNAVAILABLE, null, null, false);
        }
        var gameDir = dev.vox.lss.platform.LoaderServices.get().gameDir();
        return probeStorage(productionHandleHooks(gameDir,
                VoxyCompat::fallbackVoxyBasePath,
                () -> null,
                () -> {}), gameDir);
    }

    /**
     * The handle-invoking half of the production wiring, with only the
     * Minecraft-touching pieces injected — so the test can drive the RESOLVED
     * MethodHandles end-to-end against the stub classes (the plan's call-recording
     * requirement; an asType/cast shape bug here would otherwise pass every test and
     * surface only at the live smoke — review m3). Call after a successful
     * {@link #initResetDomain}.
     */
    static ResetHooks productionHandleHooks(java.nio.file.Path gameDir,
                                            java.util.function.Supplier<java.nio.file.Path> fallbackPath,
                                            java.util.function.Supplier<Object> levelRendererSupplier,
                                            Runnable allChanged) {
        return new ResetHooks(
                VoxyCompat::resetDomainInstance,
                inst -> {
                    try {
                        return (java.nio.file.Path) resetGetStorageBasePath.invokeExact(inst);
                    } catch (Throwable t) {
                        throw new IllegalStateException(t);
                    }
                },
                fallbackPath,
                () -> resolveRendererShutdown(levelRendererSupplier),
                // Block-bodied ON PURPOSE: an EXPRESSION lambda (`() -> h.invokeExact()`)
                // makes javac emit a ()Object call-site signature against the ()void
                // handle — WrongMethodTypeException at runtime. Caught by the stub-drive
                // test; do not "simplify" these back.
                () -> { resetShutdownInstance.invokeExact(); },
                () -> { resetCreateInstance.invokeExact(); },
                target -> wipeVoxyStore(target, gameDir),
                System::gc,
                allChanged,
                target -> isWipeContained(target, gameDir));
    }

    /** The instance probe THROWS on a failed handle invoke (never null — "can't tell"
     *  must abort in the ladder, not route into the destructive fallback-wipe branch). */
    private static Object resetDomainInstance() {
        try {
            return (Object) resetGetInstance.invokeExact();
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError vme) throw vme;
            throw new IllegalStateException("Voxy instance probe failed", t);
        }
    }

    // Once-latch for the runtime holder-resolution warn (plan: "abort ... with a
    // once-warn" — the smoke greps latest.log for exactly one warn on a failed rung).
    private static final AtomicBoolean HOLDER_RESOLVE_WARNED = new AtomicBoolean();

    /** Null = holder unresolvable at runtime (abort, once-warned); no-op = no live
     *  renderer (nothing holds sections — Voxy's own reload skips the same way).
     *  Rung 1 asks the interface's static getNullableHolder(); rung 2 (0.2.11/dev)
     *  instanceof-checks the supplied levelRenderer, the class those versions mix into. */
    private static Runnable resolveRendererShutdown(java.util.function.Supplier<Object> levelRendererSupplier) {
        Object holder;
        if (rendererHolderStaticGet != null) {
            try {
                holder = (Object) rendererHolderStaticGet.invokeExact();
            } catch (Throwable t) {
                if (t instanceof VirtualMachineError vme) throw vme;
                if (HOLDER_RESOLVE_WARNED.compareAndSet(false, true)) {
                    LSSLogger.warn("Voxy reset: renderer holder lookup failed — the Voxy half "
                            + "is unavailable this session (" + t + ")");
                }
                return null; // live resolution failed — abort the Voxy half, fail-safe
            }
            if (holder == null) return () -> {};
        } else {
            var lr = levelRendererSupplier.get();
            if (lr == null) return () -> {};
            if (!rendererHolderInterface.isInstance(lr)) {
                if (HOLDER_RESOLVE_WARNED.compareAndSet(false, true)) {
                    LSSLogger.warn("Voxy reset: levelRenderer does not carry the Voxy holder "
                            + "interface — the Voxy half is unavailable this session");
                }
                return null;
            }
            holder = lr;
        }
        Object boundHolder = holder;
        return () -> {
            try {
                rendererHolderShutdown.invoke(boundHolder);
            } catch (Throwable t) {
                if (t instanceof VirtualMachineError vme) throw vme;
                throw new IllegalStateException("Voxy renderer shutdown failed", t);
            }
        };
    }

    /**
     * The instance-null wipe-root fallback — a hand-duplicated mirror of Voxy's
     * {@code VoxyClientInstance.getBasePath} (research/voxy .../client/
     * VoxyClientInstance.java:94-119; multiplayer {@code .voxy/saves/<ip ':'→'_'>},
     * singleplayer {@code <world>/voxy}, realms {@code saves/realms}, null server info
     * {@code saves/UNKNOWN}). Survives ONLY for the no-live-instance case; drift here
     * degrades to "wipes nothing / a wrong-but-contained subdir of .voxy", never data
     * loss outside the containment roots.
     */
    private static java.nio.file.Path fallbackVoxyBasePath() {
        var mc = net.minecraft.client.Minecraft.getInstance();
        java.nio.file.Path base = mc.gameDirectory.toPath().resolve(".voxy").resolve("saves");
        var iserver = mc.getSingleplayerServer();
        if (iserver != null) {
            return iserver.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
                    .resolve("voxy").toAbsolutePath();
        }
        var info = mc.getCurrentServer();
        if (info == null) return base.resolve("UNKNOWN").toAbsolutePath();
        if (info.isRealm()) return base.resolve("realms").toAbsolutePath();
        return base.resolve(info.ip.replace(":", "_")).toAbsolutePath();
    }

    private static void initConfigHandles() throws Throwable {
        if (getVoxyConfig != null) return;
        var lookup = MethodHandles.lookup();
        Class<?> voxyConfigClass = classResolver.resolve("me.cortex.voxy.client.config.VoxyConfig");
        var configField = voxyConfigClass.getField("CONFIG");
        // Type-agnostic on purpose (review 2026-07-27): shipped Voxy 0.2.16 declares this
        // field as float, but the in-development Voxy source declares it int — an
        // exact-typed findGetter would throw on the next Voxy release and silently kill
        // slider adoption (the catch below returns empty). unreflectGetter + asType widens
        // int/float/double alike.
        var sectionRenderDistField = voxyConfigClass.getField("sectionRenderDistance");
        getSectionRenderDist = lookup.unreflectGetter(sectionRenderDistField)
                .asType(MethodType.methodType(double.class, Object.class));
        // Assign getVoxyConfig last — it's the guard checked by callers
        getVoxyConfig = lookup.unreflectGetter(configField)
                .asType(MethodType.methodType(Object.class));
    }

    static OptionalInt getViewDistanceChunks() {
        try {
            initConfigHandles();
            Object config = (Object) getVoxyConfig.invokeExact();
            double sectionDist = (double) getSectionRenderDist.invokeExact(config);
            return OptionalInt.of((int) Math.round(sectionDist * 32));
        } catch (Throwable e) {
            return OptionalInt.empty();
        }
    }
}
