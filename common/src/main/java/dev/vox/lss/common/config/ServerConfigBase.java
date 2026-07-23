package dev.vox.lss.common.config;

import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.common.XrayMaskPolicy;

import java.util.List;

/**
 * The server config shared verbatim by Fabric and Paper: same fields, same defaults,
 * same clamps, same file name. Platform subclasses add only platform-specific options
 * (Paper's updateEvents) and the config-directory resolution.
 */
public abstract class ServerConfigBase extends JsonConfig {
    protected static final String FILE_NAME = "lss-server-config.json";

    public boolean enabled = true;
    public int lodDistanceChunks = 256;
    public int bytesPerSecondLimitPerPlayer = 20_971_520;
    public int diskReaderThreads = 5;
    public int sendQueueLimitPerPlayer = 4000;
    public int bytesPerSecondLimitGlobal = 104_857_600;
    public boolean enableChunkGeneration = true;
    public int generationConcurrencyLimitGlobal = 32;
    public int generationTimeoutSeconds = 60;
    public int dirtyBroadcastIntervalSeconds = 10;
    // The per-player SYNC (disk-read) slot cap is NOT config anymore — see
    // LSSConstants.SYNC_ON_LOAD_SLOT_CAP (shadowed by the disk-pool headroom gate at the
    // default pool size; a fixed fairness ceiling above it). The generation caps stay
    // config: they are the real worldgen limiters, but they are server-internal (off the
    // wire since the server-owned-generation fold into v17).
    public int generationConcurrencyLimitPerPlayer = 16;
    public int perDimensionTimestampCacheSizeMB = 32;
    /**
     * Miss-memo TTL (docs/planning/miss-memo-design.md): an authoritative disk miss is
     * remembered for this many seconds, so a position waiting for a generation slot skips
     * the redundant ~1 Hz not-found re-reads and falls through to the generation decision
     * directly. 0 disables the memo (the kill switch — restores the pre-memo re-read
     * churn, which remains fully correct behavior).
     */
    public int missMemoTtlSeconds = 30;
    /**
     * When true (default), LSS disk reads yield to vanilla/gameplay chunk loading: Fabric
     * schedules them at IOWorker BACKGROUND priority; Paper/Folia route them through Moonrise
     * at Priority.LOW. Set false to restore FOREGROUND reads (the pre-0.7 behavior) as a
     * rollback. No clamp: a boolean has no out-of-range value.
     */
    public boolean useBackgroundReadPriority = true;
    /**
     * When true (default), clients running the legacy protocol-16 mod (v0.6.x) get a
     * translated LOD session through the v16 compat shim (docs/planning/v16-compat-design.md)
     * instead of the silent version-mismatch no-session. Inert for current-protocol clients;
     * set false as the kill switch to restore the strict version gate. No clamp: boolean.
     */
    public boolean enableV16Compat = true;
    /**
     * LOD x-ray masking (docs/planning/antixray-compat-design.md §3). "auto" (default)
     * masks iff an anti-xray engine is detected — Paper's built-in anti-xray config, or the
     * AntiXray mod on Fabric — adopting its per-world hidden list + max-block-height
     * ("mask exactly what the packet engine masks"). "on" forces masking everywhere; "off"
     * is the explicit kill switch (re-opens the LOD ore leak knowingly — the AntiXray crash
     * shim stays active regardless). Unknown values normalize to "auto".
     */
    public String xrayObfuscation = "auto";
    /**
     * FALLBACK hidden-block list — used when no engine values are adoptable (mode "on"
     * without a detected engine, or a detection/reflection failure). Verbatim copy of
     * Paper's default engine-mode-1 {@code hidden-blocks}. All states of each block are
     * hidden; unknown ids warn and are skipped at resolve time. An explicit empty list
     * means "hide nothing"; a malformed null restores this default.
     */
    public List<String> xrayHiddenBlocks = defaultXrayHiddenBlocks();
    /**
     * FALLBACK mask cutoff: only blocks below this world Y are masked (Paper's default 64).
     * At/above it the data already ships unobfuscated in vanilla chunk packets, so masking
     * there would over-hide while protecting nothing.
     */
    public int xrayMaxBlockHeight = 64;

    /** Paper's default engine-mode-1 hidden-blocks, copied verbatim (2026-07-23 build). */
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

    @Override
    protected String getFileName() {
        // Deliberately brand-INVARIANT: both LSS and VSS servers use lss-server-config.json.
        // Unlike the client config (LSSClientConfig, which is brand-driven via
        // brandedConfigCandidates), the server config was never brand-specific, so keeping one
        // shared name is what makes an LSS<->VSS server jar swap trivially keep its config. Do
        // NOT route this through brandedConfigCandidates("server") without a migration story.
        return FILE_NAME;
    }

    @Override
    public void validate() {
        lodDistanceChunks = Math.clamp(lodDistanceChunks, LSSConstants.MIN_LOD_DISTANCE, LSSConstants.MAX_LOD_DISTANCE);
        bytesPerSecondLimitPerPlayer = Math.clamp(bytesPerSecondLimitPerPlayer, LSSConstants.MIN_BYTES_PER_SECOND, LSSConstants.MAX_BYTES_PER_SECOND_PER_PLAYER);
        diskReaderThreads = Math.clamp(diskReaderThreads, LSSConstants.MIN_DISK_READER_THREADS, LSSConstants.MAX_DISK_READER_THREADS);
        sendQueueLimitPerPlayer = Math.clamp(sendQueueLimitPerPlayer, LSSConstants.MIN_SEND_QUEUE_SIZE, LSSConstants.MAX_SEND_QUEUE_SIZE);
        bytesPerSecondLimitGlobal = (int) Math.clamp((long) bytesPerSecondLimitGlobal, LSSConstants.MIN_BYTES_PER_SECOND, LSSConstants.MAX_BYTES_PER_SECOND_GLOBAL_LIMIT);
        generationConcurrencyLimitGlobal = Math.clamp(generationConcurrencyLimitGlobal, LSSConstants.MIN_CONCURRENT_GENERATIONS, LSSConstants.MAX_CONCURRENT_GENERATIONS);
        generationTimeoutSeconds = Math.clamp(generationTimeoutSeconds, LSSConstants.MIN_GENERATION_TIMEOUT, LSSConstants.MAX_GENERATION_TIMEOUT);
        dirtyBroadcastIntervalSeconds = Math.clamp(dirtyBroadcastIntervalSeconds, LSSConstants.MIN_DIRTY_BROADCAST_INTERVAL, LSSConstants.MAX_DIRTY_BROADCAST_INTERVAL);
        generationConcurrencyLimitPerPlayer = Math.clamp(generationConcurrencyLimitPerPlayer, LSSConstants.MIN_CONCURRENCY_LIMIT, LSSConstants.MAX_CONCURRENCY_LIMIT);
        perDimensionTimestampCacheSizeMB = Math.clamp(perDimensionTimestampCacheSizeMB, LSSConstants.MIN_TIMESTAMP_CACHE_SIZE_MB, LSSConstants.MAX_TIMESTAMP_CACHE_SIZE_MB);
        missMemoTtlSeconds = Math.clamp(missMemoTtlSeconds, LSSConstants.MIN_MISS_MEMO_TTL_SECONDS, LSSConstants.MAX_MISS_MEMO_TTL_SECONDS);
        xrayObfuscation = XrayMaskPolicy.normalizeMode(xrayObfuscation);
        if (xrayHiddenBlocks == null) xrayHiddenBlocks = defaultXrayHiddenBlocks();
        xrayMaxBlockHeight = Math.clamp(xrayMaxBlockHeight, LSSConstants.MIN_XRAY_MAX_BLOCK_HEIGHT, LSSConstants.MAX_XRAY_MAX_BLOCK_HEIGHT);

        // Global Constraint #28 is GONE: no client budget derives from any server cap under
        // server-owned generation, so there is nothing to cross-clamp against the wire batch.
        // Its successor is a static inequality between constants, pinned by
        // WantSetBudgetInvariantTest: SYNC_ON_LOAD_SLOT_CAP + MAX_CONCURRENT_GENERATIONS
        //   + WANT_SET_FRONTIER_RESERVE <= WANT_SET_BUDGET <= MAX_BATCH_CHUNK_REQUESTS.
    }
}
