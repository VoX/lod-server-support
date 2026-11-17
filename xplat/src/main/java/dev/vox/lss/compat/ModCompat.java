package dev.vox.lss.compat;


import java.util.OptionalInt;

/**
 * Handles optional mod integrations. Checks for supported LOD mods at startup
 * and registers consumers to bridge received chunk data into their pipelines.
 * <p>
 * Each integration is isolated in its own class to avoid classloading issues
 * when the target mod is not present.
 */
public final class ModCompat {
    private static boolean voxyLoaded;
    /** Voxy is installed, whether or not the bridge to it started. */
    private static volatile boolean voxyInstalled;
    /** JourneyMap is installed: its facade may be class-loaded (never otherwise). */
    private static volatile boolean journeyMapLoaded;
    private static volatile boolean discoveryComplete;

    public static void init() {
        if (dev.vox.lss.platform.LoaderServices.get().isModLoaded("voxy")) {
            voxyInstalled = true;
            voxyLoaded = VoxyCompat.init();
        }
        if (dev.vox.lss.platform.LoaderServices.get().isModLoaded("xaeroworldmap")) {
            XaeroMapCompat.init();
        }
        if (dev.vox.lss.platform.LoaderServices.get().isModLoaded("journeymap")) {
            journeyMapLoaded = true;
            JourneyMapCompat.init();
        }
        discoveryComplete = true;
    }

    /** Main-thread pumps for Voxy's local vanilla border and the map backends. */
    public static void clientTick() {
        if(voxyLoaded)VoxyCompat.vanillaBorderTick();
        XaeroMapCompat.clientTick();
        if (journeyMapLoaded) JourneyMapCompat.clientTick();
    }

    public static void vanillaChunkLoaded(net.minecraft.client.multiplayer.ClientLevel level,net.minecraft.world.level.chunk.LevelChunk chunk) {
        if(voxyLoaded)VoxyCompat.vanillaChunkLoaded(level,chunk);
    }

    /** Per-frame forwarder (render thread) — the Xaero bridge's texture-rebuild
     *  slice: one recolor per frame (xaero-map-bridge-plan.md §17). */
    public static void renderFrame() {
        if(voxyLoaded)VoxyCompat.vanillaBorderRendered();
        XaeroMapCompat.renderFrame();
    }

    /** Disconnect forwarder — a session's queued map tiles never outlive it. */
    public static void retireClientAcquisition() {
        VoxyCompat.retireVanillaBorder();
        XaeroMapCompat.retireClientAcquisition();
        if (journeyMapLoaded) JourneyMapCompat.retireClientAcquisition();
    }

    public static void onDisconnect() {
        VoxyCompat.retireVanillaBorder();
        XaeroMapCompat.onDisconnect();
        if (journeyMapLoaded) JourneyMapCompat.onDisconnect();
    }

    /** Whether the Voxy ingest bridge registered a consumer this launch — the alias
     *  corroboration's "is there a Voxy partition to be coarser than" input
     *  (cache-alias-keying-and-reset-override-plan.md §2.2). */
    public static boolean isVoxyBridgeActive() {
        return voxyLoaded;
    }

    /** What Voxy says about its own drawing; UNKNOWN without Voxy. It does not need the ingest
     *  bridge: a Voxy this mod could not connect to can still say it is switched off. Ask in
     *  a world only (see {@code VoxyCompat.drawState}). */
    public static dev.vox.lss.common.diagnostics.ClientStatusSnapshot.Renderer voxyDrawState() {
        return voxyInstalled ? VoxyCompat.drawState()
                : dev.vox.lss.common.diagnostics.ClientStatusSnapshot.Renderer.UNKNOWN;
    }

    /** The map backends' column consumers this launch, registered or not. */
    private static java.util.List<dev.vox.lss.api.VoxelColumnConsumer> mapConsumers() {
        var out = new java.util.ArrayList<dev.vox.lss.api.VoxelColumnConsumer>(2);
        var xaero = XaeroMapCompat.mapConsumer();
        if (xaero != null) out.add(xaero);
        var journeyMap = journeyMapLoaded ? JourneyMapCompat.mapConsumer() : null;
        if (journeyMap != null) out.add(journeyMap);
        return out;
    }

    /** Whether a column consumer other than the map backends is registered — Voxy or another
     *  mod's LSSApi reader. Map ingestion alone is multiplayer-only: it never makes a LAN host
     *  fetch LODs from its own integrated server (that generates terrain around the host).
     *  Any thread. */
    public static boolean hasNonMapConsumers() {
        return dev.vox.lss.api.LSSApi.hasVoxelConsumersOtherThan(mapConsumers());
    }

    /** Whether the map backends are this session's ONLY column consumers: what such a session
     *  receives never reached Voxy, so its column cache must not answer for a later Voxy
     *  session (the cache's map-only mark). Any thread. */
    public static boolean isMapOnlyIngestion() {
        return dev.vox.lss.api.LSSApi.hasVoxelConsumers() && !hasNonMapConsumers();
    }

    /** The live Voxy storage root's directory name, or null when unobservable —
     *  see {@code VoxyCompat.observeStorageDirName} (its own two-handle domain). */
    public static String observeVoxyStorageDirName() {
        if (!voxyLoaded) return null;
        return VoxyCompat.observeStorageDirName();
    }

    /** Whether the Xaero map bridge is ARMED (flag ∧ installed ∧ resolved) — the alias
     *  corroboration's hard fallback gate (Xaero's map store is per-address). */
    public static boolean isXaeroBridgeArmed() {
        return XaeroMapCompat.isArmed();
    }

    /** Whether LOD map ingestion can write ANY map store this session (Xaero's World Map
     *  or JourneyMap armed) — the alias corroboration's hard fallback gate: both stores
     *  are per-server with no alias support. */
    public static boolean isMapStoreArmed() {
        return XaeroMapCompat.isArmed() || (journeyMapLoaded && JourneyMapCompat.isArmed());
    }

    /** Whether an installed JourneyMap resolved its internals, so map ingestion can write
     *  to it (the menu's "supported map mod" fact; the 1.21.10 beta never resolves). */
    public static boolean isJourneyMapSupported() {
        return journeyMapLoaded && JourneyMapCompat.isSupported();
    }

    /** The harness client snapshot's map groups (map-ingestion plan §4.4/§3.8): present
     *  only for an installed map mod, so ordinary soaks keep their schema. */
    public static java.util.Map<String, java.util.Map<String, Object>> mapIngestionMetrics() {
        var out = new java.util.LinkedHashMap<String, java.util.Map<String, Object>>();
        if (dev.vox.lss.platform.LoaderServices.get().isModLoaded("xaeroworldmap")) {
            out.put("xaero", XaeroMapCompat.metrics());
        }
        if (journeyMapLoaded) out.put("journeymap", JourneyMapCompat.metrics());
        return out;
    }

    /** The conditional {@code /lss diag} JourneyMap line, or null when JourneyMap is not
     *  installed (an unsupported or unrecognized JourneyMap renders its state). */
    public static String journeyMapDiagLine() {
        return journeyMapLoaded ? JourneyMapCompat.diagLine() : null;
    }

    /** The conditional {@code /lss diag} Xaero line, or null when Xaero is not
     *  installed (a detected-but-unresolvable Xaero renders {@code state=unavailable}
     *  — the drift case must be visible, not mistaken for "not installed"). */
    public static String xaeroDiagLine() {
        return XaeroMapCompat.diagLine();
    }

    public static OptionalInt getVoxyViewDistanceChunks() {
        if (!voxyLoaded) return OptionalInt.empty();
        return VoxyCompat.getViewDistanceChunks();
    }

    /** Outcome of the {@code /lss reset} Voxy half — drives the command's feedback
     *  branch (v0.11.0 stage D, client-reset-command-and-cache-relocation-plan.md). */
    public enum VoxyResetOutcome {
        /** Full teardown + disk wipe + rebuild — "LODs visibly disappear". */
        RESET,
        /** Teardown + rebuild succeeded but the DISK wipe was skipped: the storage root
         *  was unresolvable, or a storage override (Flashback replay redirect — the
         *  origin's REAL store path, which passes directory containment) was detected
         *  by the derived-root cross-check. The feedback must not claim disk was
         *  cleared (stage-D review). */
        RESET_WIPE_SKIPPED,
        /** No live instance (config-disabled / GPU-unsupported): disk wiped via the
         *  fallback derivation, no shutdown/create (never create an instance Voxy
         *  itself didn't have). */
        WIPED_NO_INSTANCE,
        /** Voxy is not installed — the command runs its LSS half only. */
        NOT_PRESENT,
        /** The reflective surface (or the renderer holder) is unresolvable on this
         *  Voxy version — aborted BEFORE any teardown, fail-safe. */
        UNAVAILABLE,
        /** shutdownInstance threw: wipe skipped (open-handle trap), createInstance
         *  recovered — "rejoin to fully clear". */
        SHUTDOWN_FAILED,
        /** createInstance threw: Voxy is down until rejoin. */
        RESTART_FAILED
    }

    /**
     * The Voxy half's outcome PLUS the two storage roots the override cross-check
     * compared (issue #4). Both roots are nullable and are populated only where the
     * ladder actually read them — they exist so the feedback can name the directory the
     * user may want to delete by hand, not as a contract that they are always known.
     *
     * @param verdict      the cross-check's five-way finding (carried, not re-derived:
     *                     the roots alone cannot distinguish NO_INSTANCE from
     *                     UNAVAILABLE)
     * @param liveRoot     the root the running Voxy instance reported, or null
     * @param expectedRoot the root LSS derived for this connection, or null
     * @param liveRootContained whether {@code liveRoot} sits inside the containment
     *                     fence — the declined-wipe report offers {@code voxy-force}
     *                     only when stage 1 could actually arm it (panel fix: the
     *                     chat/log report must not send the user down the dead end
     *                     the force prompt itself refuses)
     * @param wipeDeclined true when the ladder reached the cross-check and REFUSED the
     *                     disk wipe. This is deliberately not derivable from
     *                     {@code outcome}: a wipe declined by the cross-check can still
     *                     end UNAVAILABLE, SHUTDOWN_FAILED or RESTART_FAILED when a
     *                     later rung fails, and those users need the "your LODs are
     *                     still at &lt;path&gt;" report just as much (issue #4 follow-up).
     *                     It mirrors exactly when the client log emits that report, so
     *                     log and in-game feedback stay in step.
     */
    public record VoxyResetReport(VoxyResetOutcome outcome,
                                  VoxyStorageOverride.Verdict verdict,
                                  java.nio.file.Path liveRoot,
                                  java.nio.file.Path expectedRoot,
                                  boolean liveRootContained,
                                  boolean wipeDeclined) {
        /** An outcome reached before (or without) reading either root. */
        public static VoxyResetReport of(VoxyResetOutcome outcome) {
            return new VoxyResetReport(outcome, VoxyStorageOverride.Verdict.UNAVAILABLE,
                    null, null, false, false);
        }
    }

    /**
     * A NON-DESTRUCTIVE read of the same two roots — stage 1 of
     * {@code /lss reset voxy-force}, which must show the user the path BEFORE anything
     * is deleted (issue #4).
     *
     * @param voxyPresent      false = Voxy is not installed at all
     * @param verdict          the five-way finding — computed where the probe knows the
     *                         shape (domain vs instance vs read), so the prompt can
     *                         never disagree with what the wipe would do
     * @param liveRoot         the running instance's root, or null if unreadable/absent
     * @param expectedRoot     the root LSS derived for this connection, or null
     * @param containedForWipe whether {@code liveRoot} would survive the containment
     *                         fence — force does not lift it, so stage 1 refuses to arm
     */
    public record VoxyStorageProbe(boolean voxyPresent,
                                   VoxyStorageOverride.Verdict verdict,
                                   java.nio.file.Path liveRoot,
                                   java.nio.file.Path expectedRoot,
                                   boolean containedForWipe) {
    }

    /**
     * The {@code /lss reset} Voxy half: renderer-first teardown, per-server disk wipe,
     * instance rebuild (the {@code /voxy reload} sequence with the wipe inserted into
     * the down-window). Main client thread only. Every failure shape is contained to a
     * feedback-driving outcome — it must never cost the ingest bridge.
     *
     * @param forceWipe        the user-confirmed {@code voxy-force} override: waives the
     *                         derived-root cross-check ONLY. Containment still applies,
     *                         and the default ({@code false}) path is unchanged.
     * @param grantedLiveRoot  the exact root the consumed ForceGrant was armed for
     *                         (null on every unforced path): the forced wipe applies
     *                         ONLY to a live root {@code samePath}-equal to it — the
     *                         shown==wiped invariant enforced at the wipe itself, not
     *                         merely at the coordinator's re-probe (panel fix)
     */
    public static VoxyResetReport resetVoxyLods(boolean forceWipe,
                                                java.nio.file.Path grantedLiveRoot) {
        if (!dev.vox.lss.platform.LoaderServices.get().isModLoaded("voxy")) {
            return VoxyResetReport.of(VoxyResetOutcome.NOT_PRESENT);
        }
        return VoxyCompat.resetVoxyProduction(forceWipe, grantedLiveRoot);
    }

    /** Reads the storage roots without touching Voxy's lifecycle or the disk — what
     *  {@code /lss reset voxy-force} shows before asking for confirmation. */
    public static VoxyStorageProbe probeVoxyStorage() {
        // Presence means the MOD is installed, not that the ingest bridge resolved
        // (panel fix): a Voxy that renamed the ingest surface but kept the reset/probe
        // domain must not be reported "not installed" while its store sits on disk —
        // the domains resolve independently.
        if (!dev.vox.lss.platform.LoaderServices.get().isModLoaded("voxy")) {
            return new VoxyStorageProbe(false, VoxyStorageOverride.Verdict.UNAVAILABLE,
                    null, null, false);
        }
        return VoxyCompat.probeStorageProduction();
    }
    /** Immutable cached integration facts; reading does not load optional classes or take native locks. */
    public record XaeroStatus(String resolution, boolean failed, boolean retiring,
                              int queuedColumns, int pendingRebuilds) {}

    public static XaeroStatus cachedXaeroStatus() {
        return XaeroMapCompat.cachedStatus(discoveryComplete);
    }

    /** Immutable cached JourneyMap facts; {@code resolution} is unknown|absent|unavailable|available. */
    public record JourneyMapStatus(String resolution, boolean failed, int pendingColumns) {}

    public static JourneyMapStatus cachedJourneyMapStatus() {
        return journeyMapLoaded ? JourneyMapCompat.cachedStatus(discoveryComplete)
                : new JourneyMapStatus(discoveryComplete ? "absent" : "unknown", false, 0);
    }
}
