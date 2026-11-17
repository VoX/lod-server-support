package dev.vox.lss.common.config;

import dev.vox.lss.common.LSSConstants;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Read-only server settings view. Runtime facades override snapshot(); a view retains one revision. */
public class ServerConfigBase {
    private final ServerSettings initial;
    protected final boolean paperPlatform;
    private static final double MB = 1024.0 * 1024.0;
    private static final double DEFAULT_MB_PER_PLAYER = 25.0;
    private static final double DEFAULT_MB_GLOBAL = 75.0;

    public ServerConfigBase() { this(SettingsSchema.server(false).defaults(), false); }
    public ServerConfigBase(ServerSettings settings, boolean paper) {
        this.initial = java.util.Objects.requireNonNull(settings);
        this.paperPlatform = paper;
    }
    public ServerSettings snapshot() { return initial; }
    public ServerSettings configuredSnapshot() { return snapshot(); }
    /** Capture this once for an operation needing several related values. */
    public final ServerConfigBase snapshotView() { return new ServerConfigBase(snapshot(), paperPlatform); }
    public record GenerationLimits(int global, int perPlayer) {}
    public final GenerationLimits generationLimits() {
        var limits = snapshot().generation().concurrency();
        return new GenerationLimits(limits.global(), limits.perPlayer());
    }
    /** Legacy diagnostic accessor name: the accepted configured generation flag, not restart timing. */
    public boolean generationConfiguredForRestart() { return snapshot().generation().enabled(); }
    public final boolean enabled() { return snapshot().service().enabled(); }
    public final boolean requireServicePermission() { return snapshot().service().requirePermission(); }
    /** Whether a player the permission gate leaves out may be told so (0.23.0 plan §3). */
    public final boolean showPermissionRefusal() { return snapshot().service().showPermissionRefusal(); }
    public final int lodDistanceChunks() { return snapshot().lod().distance().defaultChunks(); }
    public final double mbPerSecondLimitPerPlayer() { return snapshot().network().bandwidth().perPlayerMibPerSecond(); }
    public final double mbPerSecondLimitGlobal() { return snapshot().network().bandwidth().globalMibPerSecond(); }
    public final int diskReaderThreads() { return snapshot().storage().disk().readerThreads(); }
    public final int maxConcurrentDiskReads() { return snapshot().storage().disk().maxConcurrentReads(); }
    public final int sendQueueLimitPerPlayer() { return snapshot().network().sendQueueLimitPerPlayer(); }
    public final boolean enablePingBackstop() { return snapshot().network().pingBackstop(); }
    public final boolean enableSendPacing() { return snapshot().network().sendPacing(); }
    public final boolean enableRegionSummaries() { return snapshot().updates().regionSummaries(); }
    public final boolean lodYieldsToVanillaTransport() { return snapshot().network().yieldToVanilla(); }
    public final boolean enableChunkGeneration() { return snapshot().generation().enabled(); }
    public final int generationConcurrencyLimitGlobal() { return snapshot().generation().concurrency().global(); }
    public final int generationConcurrencyLimitPerPlayer() { return snapshot().generation().concurrency().perPlayer(); }
    public final int generationTimeoutTicks() { return snapshot().generation().timeoutTicks(); }
    public final int dirtyBroadcastIntervalTicks() { return snapshot().updates().dirtyBroadcastIntervalTicks(); }
    public final int perDimensionTimestampCacheSizeMB() { return snapshot().storage().timestampCacheMibPerDimension(); }
    public final int missMemoTtlSeconds() { return snapshot().storage().missMemoTtlSeconds(); }
    public final boolean useBackgroundReadPriority() { return snapshot().storage().disk().backgroundPriority(); }
    public final boolean useBackgroundReadSplit() { return snapshot().storage().disk().splitBackgroundReads(); }
    public final boolean useSelectiveNbtParse() { return snapshot().serialization().selectiveNbtParse(); }
    public final boolean useNbtTranscode() { return snapshot().serialization().nbtTranscode(); }
    public final boolean useCompressedColumns() { return snapshot().serialization().compressedColumns(); }
    public final boolean enableV16Compat() { return snapshot().compatibility().protocols().v16(); }
    public final boolean enableV18Compat() { return snapshot().compatibility().protocols().v18(); }
    public final boolean enableV19Compat() { return snapshot().compatibility().protocols().v19(); }
    public final boolean enableViaMismatchGuard() { return snapshot().compatibility().viaMismatchGuard(); }
    public final int lodStoreResweepSeconds() { return snapshot().storage().lodStore().resweepIntervalSeconds(); }
    public final boolean lodStoreBackfill() { return snapshot().storage().lodStore().backfill().enabled(); }
    public final int lodStoreBackfillColumnsPerSecond() { return snapshot().storage().lodStore().backfill().columnsPerSecond(); }
    public final int lodStoreMaxMB() { return snapshot().storage().lodStore().maxSizeMib(); }
    public final String xrayObfuscation() { return snapshot().privacy().xray().mode(); }
    public final List<String> xrayHiddenBlocks() { return snapshot().privacy().xray().hiddenBlocks(); }
    public final int xrayMaxBlockHeight() { return snapshot().privacy().xray().maxYBlocks(); }
    /** {@code privacy.request_no_map_ingestion} (map-policy plan A3.1): ask clients not to
     *  write LOD terrain into map mods; only CURRENT sessions declaring the capability are
     *  served while it is on. */
    public final boolean requestNoMapIngestion() { return snapshot().privacy().requestNoMapIngestion(); }
    public final int farPlayersUpdateIntervalTicks() { return snapshot().farPlayers().updateIntervalTicks(); }
    public final int farPlayersMaxDistanceBlocks() { return snapshot().farPlayers().distance().maxBlocks(); }
    public final int farPlayersMinDistanceBlocks() { return snapshot().farPlayers().distance().minBlocks(); }
    public final boolean farPlayersSendSpectators() { return snapshot().farPlayers().sendSpectators(); }
    public final List<String> farPlayersExclude() { return snapshot().farPlayers().excludedPlayers(); }
    /** {@code far_players.hide_permission_holders} (hide-permission plan §2): while on, every
     *  holder of the far-player hide permission is withheld as a far player (the behaviour
     *  before 0.21.0); off, the permission hides nobody by itself. */
    public final boolean farPlayersHidePermissionHolders() { return snapshot().farPlayers().hidePermissionHolders(); }
    /** Whether the loaded settings file itself sets {@code path}, as opposed to the value
     *  coming from the schema default. A view with no file behind it (a detached snapshot,
     *  a test fixture, a failed startup) answers true: nothing is known to be unset. */
    public boolean settingDeclared(String path) { return true; }
    /** The operator has not chosen yet: the loaded file predates 0.21.0 and never set
     *  {@code far_players.hide_permission_holders} (every file 0.21.0 creates carries the
     *  key). Only then are holders of the hide permission told in game that it no longer
     *  hides them; writing the key with either value and reloading ends that. */
    public final boolean farPlayersHidePermissionHoldersUndecided() {
        return !settingDeclared("far_players.hide_permission_holders");
    }
    public final List<String> updateEvents() { return snapshot().paper().updateEvents(); }
    public final String lodStore() { return snapshot().storage().lodStore().enabled() ? "on" : "off"; }
    /** {@code far_players.mode} as the broadcast core reads it: {@code off},
     *  {@code opt-in} (the YAML spelling is {@code opt_in}), {@code on} or
     *  {@code required} (0.21.0: everyone is shown and only holders of the hide
     *  permission may opt out). */
    public final String farPlayers() {
        String mode = snapshot().farPlayers().mode();
        return mode.equals("opt_in") ? "opt-in" : mode;
    }
    public final int bytesPerSecondPerPlayer() {
        return (int) Math.round(snapshot().network().bandwidth().perPlayerMibPerSecond() * MB);
    }
    public final int bytesPerSecondGlobal() {
        return (int) Math.round(snapshot().network().bandwidth().globalMibPerSecond() * MB);
    }
    /** Paper supplies exact world name then dimension; mod loaders supply only the dimension ID. */
    public final int lodDistanceForWorld(String... keys) {
        var distance = snapshot().lod().distance();
        if (paperPlatform && keys.length > 0 && keys[0] != null) {
            var named = distance.byWorld().get(keys[0]);
            if (named != null) return named;
        }
        String dimension = keys.length == 0 ? null : keys[keys.length - 1];
        return dimension == null ? distance.defaultChunks()
                : distance.byDimension().getOrDefault(dimension, distance.defaultChunks());
    }
    /** A world's LOD distance of 0 means no LOD service there (per-dimension / per-world off,
     *  or {@code default_chunks: 0} with served worlds listed). */
    public static boolean servesWorld(int worldDistance) {
        return worldDistance > 0;
    }

    /** The Chebyshev radius the server accepts requests in for a world's distance: the
     *  distance plus {@link LSSConstants#LOD_DISTANCE_BUFFER}, or -1 (accept nothing) when the
     *  world is off — every range check is {@code distance > radius}. */
    public static int requestRadius(int worldDistance) {
        return worldDistance > 0 ? worldDistance + LSSConstants.LOD_DISTANCE_BUFFER : -1;
    }

    /** The distance a legacy-dialect session is told at its handshake. Legacy sessions get no
     *  mid-session SessionConfig, so one handshaking in an off world is told the server's
     *  largest distance (it stays enabled for the worlds that are served) and the request
     *  filter enforces the off world. The largest counts {@code default_chunks} even when every
     *  real dimension overrides it, so it can exceed every served world's distance — harmless,
     *  the request filter still bounds each world. */
    public final int legacyAdvertisedDistance(int worldDistance) {
        return worldDistance > 0 ? worldDistance : Math.max(1, maxConfiguredLodDistanceChunks());
    }

    /** The configured overrides that turn LODs off, for diagnostics: {@code unlisted dimensions} when
     *  {@code default_chunks} is 0, then dimension IDs, then (Paper) world names. */
    public final List<String> unservedWorlds() {
        var distance = snapshot().lod().distance();
        var off = new java.util.ArrayList<String>();
        if (distance.defaultChunks() <= 0) off.add("unlisted dimensions (default_chunks)");
        distance.byDimension().forEach((k, v) -> { if (v <= 0) off.add(k); });
        if (paperPlatform) distance.byWorld().forEach((k, v) -> { if (v <= 0) off.add("world " + k); });
        return List.copyOf(off);
    }

    /** The diag line naming the worlds with no LOD service, or null when every world is served. */
    public final String unservedWorldsDiagLineOrNull() {
        var off = unservedWorlds();
        return off.isEmpty() ? null : "LODs off (distance 0): " + String.join(", ", off);
    }

    public final int maxConfiguredLodDistanceChunks() {
        var distance = snapshot().lod().distance();
        int max = distance.defaultChunks();
        for (int value : distance.byDimension().values()) max = Math.max(max, value);
        if (paperPlatform) for (int value : distance.byWorld().values()) max = Math.max(max, value);
        return max;
    }

    public int effectiveDiskReaderThreads(boolean prioritizedReadPath) {
        if (diskReaderThreads() > 0) return diskReaderThreads();
        if (!prioritizedReadPath) return LSSConstants.AUTO_DISK_READER_THREADS_SHARED_WORKER;
        return Math.clamp(Runtime.getRuntime().availableProcessors() / 2,
                LSSConstants.AUTO_DISK_READER_THREADS_SHARED_WORKER,
                LSSConstants.AUTO_DISK_READER_THREADS_PRIORITIZED_MAX);
    }

    public int effectiveMaxConcurrentDiskReads(int resolvedReaderThreads, boolean storeAttached) {
        if (maxConcurrentDiskReads() > 0) {
            return Math.clamp(maxConcurrentDiskReads(), LSSConstants.MIN_MAX_CONCURRENT_DISK_READS,
                    resolvedReaderThreads);
        }
        if (!storeAttached) return resolvedReaderThreads; // AUTO, no store: no-op gate
        return Math.clamp(
                (resolvedReaderThreads + LSSConstants.AUTO_DISK_READ_GATE_DIVISOR - 1)
                        / LSSConstants.AUTO_DISK_READ_GATE_DIVISOR, // ceil(pool/2)
                LSSConstants.MIN_MAX_CONCURRENT_DISK_READS, resolvedReaderThreads);
    }

    public int effectiveTimestampCacheMB() {
        if (perDimensionTimestampCacheSizeMB() > 0) return perDimensionTimestampCacheSizeMB();
        long side = 2L * (maxConfiguredLodDistanceChunks() + LSSConstants.LOD_DISTANCE_BUFFER) + 1L;
        long columns = (long) (side * side
                * LSSConstants.TIMESTAMP_CACHE_AUTO_COVERAGE_FACTOR);
        long mb = columns * LSSConstants.TIMESTAMP_CACHE_HEAP_BYTES_PER_COLUMN
                / (1024L * 1024L);
        return (int) Math.clamp(mb, LSSConstants.MIN_TIMESTAMP_CACHE_SIZE_MB,
                LSSConstants.MAX_TIMESTAMP_CACHE_SIZE_MB);
    }

    public long lodStoreMaxBytes() {
        return lodStoreMaxMB() <= 0 ? Long.MAX_VALUE : lodStoreMaxMB() * 1024L * 1024L;
    }

    public String effectiveConfigEcho(int effectiveReaderThreads,
                                      boolean effectiveCompressedColumns,
                                      int effectiveMaxConcurrentDiskReads) {
        return "Effective config: useNbtTranscode=" + useNbtTranscode()
                + ", diskReaderThreads=" + effectiveReaderThreads
                + ", useCompressedColumns=" + effectiveCompressedColumns
                + ", useBackgroundReadSplit=" + useBackgroundReadSplit()
                + ", useSelectiveNbtParse=" + useSelectiveNbtParse()
                + ", maxConcurrentDiskReads=" + effectiveMaxConcurrentDiskReads;
    }

    public static List<String> defaultXrayHiddenBlocks() {
        return List.of(
                "copper_ore", "deepslate_copper_ore", "raw_copper_block",
                "gold_ore", "deepslate_gold_ore",
                "iron_ore", "deepslate_iron_ore", "raw_iron_block",
                "coal_ore", "deepslate_coal_ore",
                "lapis_ore", "deepslate_lapis_ore",
                "mossy_cobblestone", "obsidian", "chest",
                "diamond_ore", "deepslate_diamond_ore",
                "redstone_ore", "deepslate_redstone_ore",
                "clay",
                "emerald_ore", "deepslate_emerald_ore",
                "ender_chest");
    }

    public static int clampLodDistance(int v) {
        return Math.clamp(v, LSSConstants.MIN_LOD_DISTANCE, LSSConstants.MAX_LOD_DISTANCE);
    }

    public static Map<String, Integer> clampLodDistanceByWorld(Map<?, ?> raw) {
        if (raw == null || raw.isEmpty()) return new LinkedHashMap<>();
        Map<String, Integer> cleaned = new LinkedHashMap<>();
        for (var entry : raw.entrySet()) {
            Object keyObj = entry.getKey();
            if (!(keyObj instanceof String key)) continue;
            key = key.trim();
            if (key.isEmpty() || key.length() > LSSConstants.MAX_DIMENSION_STRING_LENGTH) continue;
            Object value = entry.getValue();
            if (!(value instanceof Number n)) continue;
            cleaned.put(key, clampLodDistance(n.intValue()));
        }
        return cleaned;
    }

    public static double clampMbPerPlayer(double v) {
        if (v < 0) return DEFAULT_MB_PER_PLAYER;
        return Math.clamp(v, LSSConstants.MIN_BYTES_PER_SECOND / MB,
                LSSConstants.MAX_BYTES_PER_SECOND_PER_PLAYER / MB);
    }

    public static double clampMbGlobal(double v) {
        if (v < 0) return DEFAULT_MB_GLOBAL;
        return Math.clamp(v, LSSConstants.MIN_BYTES_PER_SECOND / MB,
                LSSConstants.MAX_BYTES_PER_SECOND_GLOBAL_LIMIT / MB);
    }

    public static int clampGenGlobal(int v) {
        return Math.clamp(v, LSSConstants.MIN_CONCURRENT_GENERATIONS, LSSConstants.MAX_CONCURRENT_GENERATIONS);
    }

    public static int clampGenPerPlayer(int v, int configuredGlobal) {
        return Math.clamp(v, LSSConstants.MIN_CONCURRENCY_LIMIT, configuredGlobal);
    }

    public static int clampDirtyBroadcastInterval(int v) {
        return v <= 0 ? 0 : Math.clamp(v,
                LSSConstants.MIN_DIRTY_BROADCAST_INTERVAL, LSSConstants.MAX_DIRTY_BROADCAST_INTERVAL);
    }

    public static int clampMaxConcurrentDiskReads(int v) {
        return v <= 0 ? 0 : Math.clamp(v,
                LSSConstants.MIN_MAX_CONCURRENT_DISK_READS, LSSConstants.MAX_DISK_READER_THREADS);
    }

    public static String clampFarPlayersMode(String v) {
        if (v == null) return "off";
        return switch (v.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "on" -> "on";
            case "opt-in", "optin", "opt_in" -> "opt-in";
            case "required" -> "required";
            default -> "off";
        };
    }

    public static int clampFarPlayersUpdateInterval(int v) {
        return Math.clamp(v, 2, 100);
    }

    public static int clampFarPlayersMaxDistance(int v) {
        return Math.clamp(v, 128, 16384);
    }

    public static int clampFarPlayersMinDistance(int v) {
        return Math.clamp(v, 0, 16384);
    }
}
