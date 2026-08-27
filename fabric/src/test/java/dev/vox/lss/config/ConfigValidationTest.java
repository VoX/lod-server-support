package dev.vox.lss.config;

import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.config.menu.RateSliderStops;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ConfigValidationTest {

    // --- LSSServerConfig ---

    private LSSServerConfig serverConfig() {
        return new LSSServerConfig();
    }

    /** Fabric's resweep default is 0 by DESIGN (the save hook owns within-session
     *  freshness there) while Paper overrides to 300 (its unfired-event staleness
     *  bound, pinned in PaperConfigValidationTest) — this half keeps the pair from
     *  drifting together silently. */
    /** The service gate ships OFF on every platform (the shared-key move —
     *  service-permission-gate-plan.md §2.1): an upgrading server keeps serving every
     *  player with no node to grant first; validate() must not move it. */
    @Test
    void requireServicePermissionShipsOffOnTheSharedBase() {
        var c = new dev.vox.lss.config.LSSServerConfig();
        org.junit.jupiter.api.Assertions.assertFalse(c.requireServicePermission);
        c.validate();
        org.junit.jupiter.api.Assertions.assertFalse(c.requireServicePermission);
    }

    @Test
    void fabricDefaultsTheLodStoreResweepToZero() {
        assertEquals(0, serverConfig().lodStoreResweepSeconds);
    }

    /** All compat rungs ship ON: a fleet of released v0.6.x (protocol 16),
     *  v0.7.x–v0.8.x (protocol 18), and v0.9.x–v0.10.x-pre (protocol 19) clients must
     *  keep working across a server upgrade without any operator action. Flipping any
     *  is a player-facing compat break and must be a deliberate release decision, not
     *  drift. */
    @Test
    void compatRungsDefaultOn() {
        assertTrue(serverConfig().enableV16Compat);
        assertTrue(serverConfig().enableV18Compat);
        assertTrue(serverConfig().enableV19Compat);
    }

    /** C6 review C-2: a null/blank ENTRY in the curated table NPEs Map.copyOf at
     *  resolver construction — inside the decode drain, every tick, no ingest-failure
     *  report: LOD dead for the session off one hand-edited entry. validate() must
     *  drop such entries fail-open. */
    @Test
    void curatedTableNullOrBlankEntriesAreDroppedFailOpen() {
        var c = clientConfig();
        c.crossVersionBlockFallbacks = new java.util.HashMap<>();
        c.crossVersionBlockFallbacks.put("ancient:sulfur", null);
        c.crossVersionBlockFallbacks.put("", "minecraft:stone");
        c.crossVersionBlockFallbacks.put("ok:kept", "minecraft:dirt");
        c.validate();
        assertEquals(java.util.Map.of("ok:kept", "minecraft:dirt"),
                c.crossVersionBlockFallbacks,
                "null/blank entries dropped, the valid one kept");
        assertDoesNotThrow(() -> java.util.Map.copyOf(c.crossVersionBlockFallbacks),
                "the resolver's Map.copyOf must be safe after validate()");
    }

    /** The Via cross-MC guard ships ON (XVER §7): without it a legacy client behind
     *  Via silently receives columns it cannot decode. Fail-open by construction (no
     *  Via / no signal = unchanged), so the on-default is safe without Via installed. */
    @Test
    void viaMismatchGuardDefaultsOn() {
        assertTrue(serverConfig().enableViaMismatchGuard);
    }

    /** Both adaptive-transfer-rate mechanisms ship ON (adaptive-transfer-rate-plan.md):
     *  the server ping backstop protects ANY client on a congested link, and the
     *  client governor paces itself against every released v17+ server. Each has its
     *  own kill switch. */
    @Test
    void regionSummariesDefaultOn() {
        assertTrue(serverConfig().enableRegionSummaries,
                "region summaries must ship enabled (region-summary-sync-plan.md §9 — "
                + "the HANDLER-checked kill switch is the off ramp)");
    }

    @Test
    void adaptiveTransferRateMechanismsDefaultOn() {
        assertTrue(serverConfig().enablePingBackstop,
                "the ping backstop must ship enabled");
        assertTrue(serverConfig().enableSendPacing,
                "send pacing must ship enabled (a burst shaper, never a rate governor)");
        assertTrue(clientConfig().enableAdaptiveTransferRate,
                "the client transfer governor must ship enabled");
        assertTrue(clientConfig().enableJoinSlowStart,
                "join slow start must ship enabled (join-slow-start-plan.md — join "
                        + "latency beats LOD fill speed, user decision 2026-08-14)");
    }

    @Test
    void lodDistanceChunksClamped() {
        var c = serverConfig();
        c.lodDistanceChunks = 0;
        c.validate();
        assertEquals(1, c.lodDistanceChunks);

        c.lodDistanceChunks = 99999;
        c.validate();
        assertEquals(2048, c.lodDistanceChunks);
    }

    private static final double MB = 1024.0 * 1024.0;

    @Test
    void mbPerSecondLimitPerPlayerClamped() {
        // Since the 2026-08-08 rework the visible knob is decimal MiB/s; the clamp band is
        // the old byte band re-denominated, so nothing an admin could type before is newly
        // rejected. A fresh (unvalidated) field is the -1 sentinel, so every arm here sets
        // the mb field DIRECTLY — the sentinel/legacy resolution has its own tests below.
        var c = serverConfig();
        c.mbPerSecondLimitPerPlayer = 0.0000001; // below the 1 KiB/s byte floor
        c.validate();
        assertEquals(LSSConstants.MIN_BYTES_PER_SECOND / MB, c.mbPerSecondLimitPerPlayer);
        assertEquals(LSSConstants.MIN_BYTES_PER_SECOND, c.bytesPerSecondPerPlayer());

        // Ceiling raised 100 MB -> 1 GiB 2026-08-02 (config review §5): the live server hit
        // the old one exactly. The DEFAULT is untouched — this bounds only what an admin types.
        c.mbPerSecondLimitPerPlayer = 200.0;
        c.validate();
        assertEquals(200.0, c.mbPerSecondLimitPerPlayer, "200 MiB/s is inside the band");
        assertEquals(209_715_200, c.bytesPerSecondPerPlayer());
        c.mbPerSecondLimitPerPlayer = 999_999.0;
        c.validate();
        assertEquals(LSSConstants.MAX_BYTES_PER_SECOND_PER_PLAYER / MB, c.mbPerSecondLimitPerPlayer);
        assertEquals(LSSConstants.MAX_BYTES_PER_SECOND_PER_PLAYER, c.bytesPerSecondPerPlayer());
    }

    /** The legacy byte-denominated key must ride the SAME clamp band after conversion —
     *  an old file's out-of-range value lands exactly where it always did. */
    @Test
    void legacyBytesPerSecondKeyRidesTheSameClampAfterConversion() {
        var c = serverConfig();
        c.bytesPerSecondLimitPerPlayer = 100; // below the 1 KiB/s floor, legacy spelling
        c.validate();
        assertEquals(LSSConstants.MIN_BYTES_PER_SECOND, c.bytesPerSecondPerPlayer());
        assertEquals(-1, c.bytesPerSecondLimitPerPlayer,
                "the legacy field is re-sentineled after resolution (it never persists)");

        c = serverConfig();
        c.bytesPerSecondLimitPerPlayer = 200_000_000;
        c.validate();
        assertEquals(200_000_000, c.bytesPerSecondPerPlayer(),
                "an in-band legacy value must be honored EXACTLY (int/2^20 is exact in double)");
    }

    @Test
    void diskReaderThreadsClamped() {
        var c = serverConfig();
        c.diskReaderThreads = 1;
        c.validate();
        assertEquals(1, c.diskReaderThreads);

        c.diskReaderThreads = 100;
        c.validate();
        assertEquals(64, c.diskReaderThreads);
    }

    /** 0 = AUTO survives validate() (it must not be clamped up to the MIN of 1, which would
     *  silently destroy the derive-it semantics), and the derived value depends on whether the
     *  resolved read path carries real priority. */
    @Test
    void diskReaderThreadsZeroMeansAutoAndSurvivesValidation() {
        var c = serverConfig();
        c.diskReaderThreads = 0;
        c.validate();
        assertEquals(0, c.diskReaderThreads, "0 = AUTO must survive validate()");
        c.diskReaderThreads = -5;
        c.validate();
        assertEquals(0, c.diskReaderThreads, "negative nonsense normalizes to AUTO, not to 1");

        assertEquals(LSSConstants.AUTO_DISK_READER_THREADS_SHARED_WORKER,
                c.effectiveDiskReaderThreads(false),
                "vanilla's single-threaded IOWorker: concurrency IS the vanilla-delay tradeoff");
        int prioritized = c.effectiveDiskReaderThreads(true);
        assertTrue(prioritized >= LSSConstants.AUTO_DISK_READER_THREADS_SHARED_WORKER
                        && prioritized <= LSSConstants.AUTO_DISK_READER_THREADS_PRIORITIZED_MAX,
                "the Moonrise Priority.LOW tier scales with cores inside its band, got " + prioritized);

        c.diskReaderThreads = 12;
        c.validate();
        assertEquals(12, c.effectiveDiskReaderThreads(true), "an explicit value always wins");
        assertEquals(12, c.effectiveDiskReaderThreads(false));
    }

    /** The config review proposed retiring this ("unreachable at its default"); implementation
     *  reversed the call — lowering it is the only lever that exercises service.queue_full, the
     *  send-queue breaker, and the bandwidth-throttle soak scenario gates on it firing. */
    @Test
    void sendQueueLimitPerPlayerClamped() {
        var c = serverConfig();
        assertEquals(LSSConstants.MAX_BATCH_CHUNK_REQUESTS, c.sendQueueLimitPerPlayer,
                "the default must stay AT the wire batch cap");
        c.sendQueueLimitPerPlayer = 0;
        c.validate();
        assertEquals(1, c.sendQueueLimitPerPlayer);

        c.sendQueueLimitPerPlayer = 999999;
        c.validate();
        assertEquals(100_000, c.sendQueueLimitPerPlayer);
    }

    @Test
    void mbPerSecondLimitGlobalClamped() {
        var c = serverConfig();
        c.mbPerSecondLimitGlobal = 0.0000001;
        c.validate();
        assertEquals(LSSConstants.MIN_BYTES_PER_SECOND / MB, c.mbPerSecondLimitGlobal);
        assertEquals(LSSConstants.MIN_BYTES_PER_SECOND, c.bytesPerSecondGlobal());

        c.mbPerSecondLimitGlobal = 2_000_000.0;
        c.validate();
        assertEquals(LSSConstants.MAX_BYTES_PER_SECOND_GLOBAL_LIMIT / MB, c.mbPerSecondLimitGlobal);
        assertEquals(1_073_741_824, c.bytesPerSecondGlobal());

        // Legacy spelling, same band after conversion.
        c = serverConfig();
        c.bytesPerSecondLimitGlobal = 2_000_000_000;
        c.validate();
        assertEquals(1_073_741_824, c.bytesPerSecondGlobal());
        assertEquals(-1, c.bytesPerSecondLimitGlobal, "re-sentineled after resolution");
    }

    @Test
    void generationConcurrencyLimitGlobalClamped() {
        var c = serverConfig();
        c.generationConcurrencyLimitGlobal = 0;
        c.validate();
        assertEquals(1, c.generationConcurrencyLimitGlobal);

        // Ceiling raised 256 -> 512 2026-08-02: WantSetBudgetInvariantTest permits up to 536
        // (slot cap 200 + reserve 64 against the 800 budget), so the headroom was unused.
        c.generationConcurrencyLimitGlobal = 999;
        c.validate();
        assertEquals(LSSConstants.MAX_CONCURRENT_GENERATIONS, c.generationConcurrencyLimitGlobal);
    }

    @Test
    void generationTimeoutSecondsClamped() {
        var c = serverConfig();
        c.generationTimeoutSeconds = 0;
        c.validate();
        assertEquals(1, c.generationTimeoutSeconds);

        c.generationTimeoutSeconds = 9999;
        c.validate();
        assertEquals(600, c.generationTimeoutSeconds);
    }

    /** v0.11.0 (dirty-broadcast-interval-zero-plan.md): 0 = dirty pushes disabled is a
     *  first-class value — the lodStoreMaxMB idiom. 0 stays 0 (was: clamped to 1, the
     *  FASTEST cadence — the opposite of what an operator writing 0 means), negatives
     *  normalize to 0, and 1 stays the floor for a NONZERO (sending) interval. */
    @Test
    void dirtyBroadcastIntervalSecondsZeroDisablesSendsAndNonzeroFloorsAt1() {
        var c = serverConfig();
        c.dirtyBroadcastIntervalSeconds = 0;
        c.validate();
        assertEquals(0, c.dirtyBroadcastIntervalSeconds, "0 = sends off, must survive validate");

        c.dirtyBroadcastIntervalSeconds = -5;
        c.validate();
        assertEquals(0, c.dirtyBroadcastIntervalSeconds,
                "negative nonsense must mean sends off, not a 1 s cadence");

        c.dirtyBroadcastIntervalSeconds = 1;
        c.validate();
        assertEquals(1, c.dirtyBroadcastIntervalSeconds, "1 is the nonzero floor, kept exactly");

        c.dirtyBroadcastIntervalSeconds = 9999;
        c.validate();
        assertEquals(300, c.dirtyBroadcastIntervalSeconds);
    }

    @Test
    void generationConcurrencyLimitPerPlayerClamped() {
        var c = serverConfig();
        c.generationConcurrencyLimitPerPlayer = 0;
        c.validate();
        assertEquals(1, c.generationConcurrencyLimitPerPlayer);

        // Plain per-field bound: the #28 cross-clamp is gone (no client budget derives
        // from this cap anymore; the successor invariant lives in WantSetBudgetInvariantTest).
        // Config review §9.1: the per-player ceiling is now the CONFIGURED global, not the
        // deleted MAX_CONCURRENCY_LIMIT — a per-player value above the fleet-wide one
        // is unreachable by construction, so it used to validate to silent nonsense.
        c.generationConcurrencyLimitGlobal = 64;
        c.generationConcurrencyLimitPerPlayer = 9999;
        c.validate();
        assertEquals(64, c.generationConcurrencyLimitPerPlayer,
                "per-player must clamp to the configured global, not above it");
    }

    @Test
    void perDimensionTimestampCacheSizeMBClamped() {
        var c = serverConfig();
        // 0 = AUTO now (derived from lodDistanceChunks) and must survive validate().
        c.perDimensionTimestampCacheSizeMB = 0;
        c.validate();
        assertEquals(0, c.perDimensionTimestampCacheSizeMB);

        c.perDimensionTimestampCacheSizeMB = 9999;
        c.validate();
        assertEquals(LSSConstants.MAX_TIMESTAMP_CACHE_SIZE_MB, c.perDimensionTimestampCacheSizeMB);
    }

    /** The AUTO sizing must track lodDistanceChunks — its whole reason for existing is that a
     *  fixed value silently under-provisions exactly when an admin raises the distance —
     *  which is the whole point, since the default went 256 -> 512 -> 256 in one day. AUTO must
     *  reproduce the historic hand-tuned 32 MB at the 256 default, and a 512 disc (~4x the
     *  area) must buy materially more. */
    @Test
    void timestampCacheAutoSizeTracksLodDistance() {
        var c = serverConfig();
        c.perDimensionTimestampCacheSizeMB = 0;

        c.lodDistanceChunks = 256;
        c.validate();
        int at256 = c.effectiveTimestampCacheMB();
        // The D0 tile model: 8x the scanned disc area at ~5 B/column. At the 256 default
        // that is ~12 MB — a third of the pre-tile 32 MB while covering 5.3x the columns
        // (timestamp-cache-tile-redesign.md §6); pin the exact derivation, not a band.
        long side = 2L * (256 + LSSConstants.LOD_DISTANCE_BUFFER) + 1;
        // Same cast shape as production (double area product, then long bytes): a
        // fractional coverage factor must not silently diverge the pin.
        long want = (long) (side * side * LSSConstants.TIMESTAMP_CACHE_AUTO_COVERAGE_FACTOR)
                * LSSConstants.TIMESTAMP_CACHE_HEAP_BYTES_PER_COLUMN / (1024 * 1024);
        assertEquals(want, at256, "AUTO at the 256 default follows the tile-cost derivation");
        assertTrue(at256 >= 10 && at256 <= 16,
                "sanity band: the derivation itself must stay in the ~12 MB regime, got " + at256);

        c.lodDistanceChunks = 512;
        c.validate();
        int at512 = c.effectiveTimestampCacheMB();
        assertTrue(at512 > at256 * 2,
                "4x the disc area must buy materially more cache: " + at256 + " -> " + at512);
        assertTrue(at512 <= LSSConstants.MAX_TIMESTAMP_CACHE_SIZE_MB, "AUTO must respect the ceiling");

        c.perDimensionTimestampCacheSizeMB = 77;
        assertEquals(77, c.effectiveTimestampCacheMB(), "an explicit value always wins");
    }

    /** The lodStore SPLIT default (user decision, 2026-08-08 second round): the
     *  COMPILED default is "off", which is what a key ABSENT from an existing config
     *  file binds to — an upgrade must never silently double an operator's world
     *  folder. Fresh installs generate "on" instead via onFreshCreate (pinned through
     *  the real load path in JsonConfigLoadTest). "on" is the canonical spelling,
     *  "full" a permanent read alias.
     *
     *  <p>lodStoreBackfill stays ON so an ARMED store is the whole feature — warm
     *  serves from the first join — and turning the store off still disables both
     *  with the one key. */
    @Test
    void lodStoreCompiledDefaultIsOffForAbsentKeysWithBackfillArmed() {
        assertEquals("off", serverConfig().lodStore,
                "compiled default off: an absent key must never arm the store on upgrade");
        assertTrue(serverConfig().lodStoreBackfill,
                "backfill stays on so an armed store is the whole feature");
    }

    /** "on" is canonical on disk; "full" (the pre-rework spelling) must stay accepted
     *  forever and normalize to "on" — an existing file must not change behavior. An
     *  unknown word still lands on "off": a typo now silently DISABLES a default
     *  feature rather than silently arming a storage engine. */
    @Test
    void lodStoreOnIsCanonicalAndFullIsAPermanentAlias() {
        var c = serverConfig();
        c.lodStore = "full";
        c.validate();
        assertEquals("on", c.lodStore, "'full' must normalize to the canonical 'on'");

        c.lodStore = "ON";
        c.validate();
        assertEquals("on", c.lodStore);

        c.lodStore = "garbage";
        c.validate();
        assertEquals("off", c.lodStore, "unknown values disable, never arm");
    }

    /** The cap-behavior user decision (store-cap-behavior-plan.md §1): the store ships
     *  UNCAPPED — 0 means no size cap, and it is the default. A silent revert to a
     *  nonzero default would re-enter the backfill<->eviction treadmill. */
    @Test
    void lodStoreMaxMBDefaultsToUncappedZero() {
        assertEquals(0, serverConfig().lodStoreMaxMB);
    }

    /** The 0-or-64..1048576 clamp: 0 (and negative nonsense) stays uncapped; a nonzero
     *  opt-in cap keeps the 64 floor (a tiny accidental cap would evict constantly). */
    @Test
    void lodStoreMaxMBZeroStaysUncappedAndNonzeroFloorsAt64() {
        var c = serverConfig();
        c.lodStoreMaxMB = 0;
        c.validate();
        assertEquals(0, c.lodStoreMaxMB);

        c.lodStoreMaxMB = -7;
        c.validate();
        assertEquals(0, c.lodStoreMaxMB, "negative nonsense must mean uncapped, not a 64 MB cap");

        c.lodStoreMaxMB = 1;
        c.validate();
        assertEquals(LSSConstants.MIN_LOD_STORE_MAX_MB, c.lodStoreMaxMB);

        // Ceiling raised 32 GB -> 1 TB 2026-08-02: this bounds the ADMIN'S OWN disk, and a
        // Chunky-pregenerated world's store can exceed 32 GB, where an artificial ceiling turns
        // an intentional cap into a silent eviction treadmill.
        c.lodStoreMaxMB = 999_999;
        c.validate();
        assertEquals(999_999, c.lodStoreMaxMB, "999999 MB is now inside the band");
        c.lodStoreMaxMB = Integer.MAX_VALUE;
        c.validate();
        assertEquals(LSSConstants.MAX_LOD_STORE_MAX_MB, c.lodStoreMaxMB);

        c.lodStoreMaxMB = 63;
        c.validate();
        assertEquals(LSSConstants.MIN_LOD_STORE_MAX_MB, c.lodStoreMaxMB);
    }

    /** lodStoreMaxBytes(): the 0-semantics both platforms wire into the store env.
     *  Negatives map to uncapped too — the helper must be robust even on a config
     *  validate() never touched. */
    @Test
    void lodStoreMaxBytesMapsZeroAndNegativesToUncappedAndMBToBytes() {
        var c = serverConfig();
        c.lodStoreMaxMB = 0;
        assertEquals(Long.MAX_VALUE, c.lodStoreMaxBytes());
        c.lodStoreMaxMB = -3;
        assertEquals(Long.MAX_VALUE, c.lodStoreMaxBytes());
        c.lodStoreMaxMB = 100;
        assertEquals(100L * 1024 * 1024, c.lodStoreMaxBytes());
    }

    /** Drift guards for the backfill-tuning defaults. The pace was raised 100 -> 500 on
     *  2026-08-02 (config review §2.3) — it is what makes a default-ON backfill tolerable, by
     *  turning a ~2 h background walk into a ~23 min one. The MSPT gate is a constant now. */
    @Test
    void backfillTuningDefaultsAre500ColumnsPerSecondAnd45MsCeiling() {
        assertEquals(500, serverConfig().lodStoreBackfillColumnsPerSecond);
        assertEquals(45, LSSConstants.LOD_STORE_BACKFILL_TICK_CEILING_MS);
    }

    @Test
    void lodStoreBackfillColumnsPerSecondClamped() {
        var c = serverConfig();
        c.lodStoreBackfillColumnsPerSecond = 1;
        c.validate();
        assertEquals(LSSConstants.MIN_LOD_STORE_BACKFILL_CPS, c.lodStoreBackfillColumnsPerSecond);

        c.lodStoreBackfillColumnsPerSecond = 99999;
        c.validate();
        assertEquals(LSSConstants.MAX_LOD_STORE_BACKFILL_CPS, c.lodStoreBackfillColumnsPerSecond);
    }

    // --- X-ray masking keys (docs/planning/antixray-compat-design.md §3) ---

    @Test
    void xrayObfuscationNormalizedToCanonicalTriState() {
        var c = serverConfig();
        c.xrayObfuscation = "ON";
        c.validate();
        assertEquals("on", c.xrayObfuscation);

        c.xrayObfuscation = " Off ";
        c.validate();
        assertEquals("off", c.xrayObfuscation);

        c.xrayObfuscation = "garbage";
        c.validate();
        assertEquals("auto", c.xrayObfuscation, "unknown values must normalize to auto");

        c.xrayObfuscation = null;
        c.validate();
        assertEquals("auto", c.xrayObfuscation);
    }

    @Test
    void xrayMaxBlockHeightClamped() {
        var c = serverConfig();
        c.xrayMaxBlockHeight = -99999;
        c.validate();
        assertEquals(LSSConstants.MIN_XRAY_MAX_BLOCK_HEIGHT, c.xrayMaxBlockHeight);

        c.xrayMaxBlockHeight = 99999;
        c.validate();
        assertEquals(LSSConstants.MAX_XRAY_MAX_BLOCK_HEIGHT, c.xrayMaxBlockHeight);
    }

    @Test
    void xrayHiddenBlocksNullRestoresDefaultButEmptyIsRespected() {
        var c = serverConfig();
        c.xrayHiddenBlocks = null;
        c.validate();
        assertEquals(dev.vox.lss.common.config.ServerConfigBase.defaultXrayHiddenBlocks(),
                c.xrayHiddenBlocks, "malformed null must fail safe to the default list");

        c.xrayHiddenBlocks = List.of();
        c.validate();
        assertEquals(List.of(), c.xrayHiddenBlocks,
                "an explicit empty list means 'hide nothing' and must be respected");
    }

    // --- Reflective clamp sweep ---

    private static List<Field> numericServerConfigFields() {
        List<Field> fields = Arrays.stream(LSSServerConfig.class.getFields())
                .filter(f -> !Modifier.isStatic(f.getModifiers()))
                .filter(f -> f.getType().isPrimitive() && f.getType() != boolean.class)
                .toList();
        // Guard against the sweep going vacuous if fields get refactored to non-public.
        // (10 since the syncOnLoadConcurrencyLimitPerPlayer knob became a constant.)
        assertTrue(fields.size() >= 10, "clamp sweep lost fields, found only: " + fields);
        assertTrue(fields.stream().anyMatch(f -> f.getName().equals("perDimensionTimestampCacheSizeMB")),
                "clamp sweep no longer sees perDimensionTimestampCacheSizeMB");
        return fields;
    }

    /**
     * Every numeric server-config field must be pulled back to a sane range by validate(),
     * even from int extremes. Auto-catches future fields added without a clamp — the named
     * tests above pin the exact bounds, this pins that bounds exist at all.
     */
    // The bandwidth pair's SENTINEL fields (config rework 2026-08-08): the legacy int
    // spellings always re-sentinel to -1 in validate(), and the mb doubles treat any
    // negative as "not in the file". The sweep handles each specially below.
    private static final List<String> LEGACY_BANDWIDTH_FIELDS =
            List.of("bytesPerSecondLimitPerPlayer", "bytesPerSecondLimitGlobal");

    @Test
    void everyNumericServerFieldClampedAtIntExtremes() throws Exception {
        for (Field f : numericServerConfigFields()) {
            if (f.getType() == double.class) {
                // The mb bandwidth fields: a negative extreme is read as the sentinel and
                // resolves to the default; a positive extreme must clamp into the byte band.
                var c = serverConfig();
                f.setDouble(c, -Double.MAX_VALUE);
                c.validate();
                assertTrue(f.getDouble(c) >= LSSConstants.MIN_BYTES_PER_SECOND / MB,
                        f.getName() + " not resolved/clamped up from a negative extreme");
                f.setDouble(c, Double.MAX_VALUE);
                c.validate();
                assertTrue(f.getDouble(c) <= LSSConstants.MAX_BYTES_PER_SECOND_GLOBAL_LIMIT / MB,
                        f.getName() + " not clamped down from Double.MAX_VALUE");
                continue;
            }
            assertEquals(int.class, f.getType(),
                    f.getName() + ": extend the clamp sweep for non-int numeric fields");

            if (LEGACY_BANDWIDTH_FIELDS.contains(f.getName())) {
                // Hidden legacy spellings: the post-validate contract is "folded into the
                // mb key and re-sentineled", not a clamp on the field itself.
                var c = serverConfig();
                f.setInt(c, Integer.MAX_VALUE);
                c.validate();
                assertEquals(-1, f.getInt(c), f.getName() + " must re-sentinel after resolution");
                continue;
            }

            var c = serverConfig();
            f.setInt(c, Integer.MIN_VALUE);
            c.validate();
            // missMemoTtlSeconds and lodStoreResweepSeconds have a legal floor of 0
            // (each 0 is that feature's kill switch), as does lodStoreMaxMB (0 =
            // uncapped, the default), and dirtyBroadcastIntervalSeconds (0 = dirty
            // pushes off since v0.11.0; the drain keeps its fallback cadence);
            // xrayMaxBlockHeight's floor is a world Y and
            // deliberately negative — every other numeric floor is >= 1.
            int floor = switch (f.getName()) {
                case "missMemoTtlSeconds", "lodStoreResweepSeconds", "lodStoreMaxMB",
                        "dirtyBroadcastIntervalSeconds",
                        // 0 = AUTO (derived), the default for both since 2026-08-02;
                        // maxConcurrentDiskReads is the same shape (store-conditional
                        // AUTO — disk-read-concurrency-gate-plan.md).
                        "diskReaderThreads", "perDimensionTimestampCacheSizeMB",
                        "maxConcurrentDiskReads",
                        // 0 = no inner ring (the default) — E1 far players.
                        "farPlayersMinDistanceBlocks" -> 0;
                case "xrayMaxBlockHeight" -> LSSConstants.MIN_XRAY_MAX_BLOCK_HEIGHT;
                default -> 1;
            };
            assertTrue(f.getInt(c) >= floor,
                    f.getName() + " not clamped up from Integer.MIN_VALUE, still " + f.getInt(c));

            f.setInt(c, Integer.MAX_VALUE);
            c.validate();
            assertTrue(f.getInt(c) < Integer.MAX_VALUE,
                    f.getName() + " not clamped down from Integer.MAX_VALUE");
        }
    }

    /** Compiled defaults must already sit inside their clamp ranges: validate() may not
     *  move them — EXCEPT the bandwidth sentinels, whose whole design is that validate()
     *  resolves -1 into the real defaults (asserted exactly). A second validate() must
     *  then be a fixed point, so the resolution never churns the file. */
    @Test
    void defaultsSurviveValidateUnchanged() throws Exception {
        var validated = serverConfig();
        validated.validate();
        var pristine = serverConfig();
        for (Field f : LSSServerConfig.class.getFields()) {
            if (Modifier.isStatic(f.getModifiers())) continue;
            if (f.getName().startsWith("mbPerSecondLimit")) continue; // sentinel -> resolved, below
            assertEquals(f.get(pristine), f.get(validated),
                    "default for " + f.getName() + " is outside its clamp range");
        }
        assertEquals(25.0, validated.mbPerSecondLimitPerPlayer, "sentinel resolves to the 25 MiB/s default");
        assertEquals(75.0, validated.mbPerSecondLimitGlobal, "sentinel resolves to the 75 MiB/s default");

        var again = serverConfig();
        again.validate();
        again.validate();
        for (Field f : LSSServerConfig.class.getFields()) {
            if (Modifier.isStatic(f.getModifiers())) continue;
            assertEquals(f.get(validated), f.get(again),
                    "validate() must be idempotent for " + f.getName());
        }
    }

    /** LOD reads yield to gameplay out of the box; false is the documented rollback and the
     *  arm selector for benchmark_compare.sh's foreground-vs-background comparison. */
    @Test
    void backgroundReadPriorityDefaultsOn() {
        assertTrue(serverConfig().useBackgroundReadPriority,
                "background read priority must default on");
    }

    @Test
    void autoDiskReaderThreadConstantsAreCoherent() {
        // The retired key no longer exists as a field, so there is nothing to default-check;
        // what must hold is that the constants the auto-sizing leans on are still coherent
        // (both tiers inside the band, floor <= ceiling). JsonConfigLoadTest covers the
        // "an old file still carrying the key parses fine" half.
        assertTrue(LSSConstants.AUTO_DISK_READER_THREADS_SHARED_WORKER
                        <= LSSConstants.AUTO_DISK_READER_THREADS_PRIORITIZED_MAX,
                "the AUTO floor must not exceed the prioritized ceiling");
        assertTrue(LSSConstants.AUTO_DISK_READER_THREADS_SHARED_WORKER
                        >= LSSConstants.MIN_DISK_READER_THREADS,
                "the AUTO value must itself be a legal explicit value");
        assertTrue(LSSConstants.AUTO_DISK_READER_THREADS_PRIORITIZED_MAX
                        <= LSSConstants.MAX_DISK_READER_THREADS);
    }

    /** Default TRUE since v0.11.0 (user decision 2026-08-13, superseding the yield
     *  plan's ships-unarmed stance; the CI-inertness pin keeps soaks provably
     *  unaffected — loopback channels never go unwritable). */
    @Test
    void transportYieldDefaultsOn() {
        assertTrue(serverConfig().lodYieldsToVanillaTransport,
                "lodYieldsToVanillaTransport defaults TRUE since v0.11.0");
    }

    /** Disk serves transcode NBT straight to wire bytes out of the box; false is the
     *  documented rollback to the per-section object path (round 2, 2026-07-29). */
    @Test
    void nbtTranscodeDefaultsOn() {
        assertTrue(serverConfig().useNbtTranscode,
                "NBT transcode must default on");
    }

    /** Phase 3 (R1): the background-read split ships ON; false is the narrow rollback
     *  that keeps read protection (unlike useBackgroundReadPriority=false). */
    @Test
    void backgroundReadSplitDefaultsOn() {
        assertTrue(serverConfig().useBackgroundReadSplit,
                "useBackgroundReadSplit must default on");
    }

    /** Phase 4 (R2): the selective root-whitelist parse ships ON; false restores the
     *  full root parse as the rollback. */
    @Test
    void selectiveNbtParseDefaultsOn() {
        assertTrue(serverConfig().useSelectiveNbtParse,
                "useSelectiveNbtParse must default on");
    }

    // --- LSSClientConfig ---

    private LSSClientConfig clientConfig() {
        return new LSSClientConfig();
    }

    @Test
    void clientLodDistanceChunksClamped() {
        var c = clientConfig();
        c.lodDistanceChunks = -1;
        c.validate();
        assertEquals(0, c.lodDistanceChunks);

        c.lodDistanceChunks = 99999;
        c.validate();
        assertEquals(2048, c.lodDistanceChunks);
    }

    @Test
    void v16CompatFlagsDefaultOn() {
        // The whole point of the branch: a v0.7.0 client talks to a pre-v0.7.0 server out of the
        // box (compat), and drives generation on it (Tier B). A silent revert of either default
        // to false would otherwise pass CI green — this pins the shipped behavior.
        var c = clientConfig();
        assertTrue(c.enableV16ServerCompat, "v16 server backward-compat must default ON");
        assertTrue(c.enableV16Generation, "Tier B generation-drive must default ON");
        assertTrue(c.enableV19ServerCompat,
                "the C3 ladder's 19 rung must default ON (a v0.10.0 client on a v0.9.x "
                        + "server gets a native 19 session, not the v16 degrade)");
    }

    @Test
    void ingestBackpressureDefaultsOn() {
        // Issue #71: the ingest-pressure pacing is the shipped protection for weak clients —
        // a silent default-off revert would pass CI green (no consumer reports in any tier).
        var c = clientConfig();
        assertTrue(c.enableIngestBackpressure, "ingest-pressure request pacing must default ON");
        c.validate();
        assertTrue(c.enableIngestBackpressure, "validate() must not touch the boolean");
    }

    @Test
    void adaptiveScanCadenceDefaultsOn() {
        // The adaptive cadence (docs/planning/adaptive-scan-cadence-design.md) ships ON —
        // a silent default-off revert would pass CI green (unit rigs set the seam
        // explicitly) while quietly restoring the 1 Hz spurt fill on every client.
        var c = clientConfig();
        assertTrue(c.enableAdaptiveScanCadence, "adaptive scan cadence must default ON");
        c.validate();
        assertTrue(c.enableAdaptiveScanCadence, "validate() must not touch the boolean");
    }

    @Test
    void regionSummarySyncDefaultsOnAndRoundTripsThroughJson() {
        // The client half of region summaries (region-summary-sync-plan.md §9) ships ON,
        // and the GSON leg pins the exact key + a saved false binding back as false —
        // the enableScanPrefixRetention precedent (a silent default-off revert would
        // pass CI green while quietly killing the warm-rejoin suppression in the field).
        var c = clientConfig();
        assertTrue(c.enableRegionSummarySync, "region summary sync must default ON");
        c.validate();
        assertTrue(c.enableRegionSummarySync, "validate() must not touch the boolean");
        var gson = new com.google.gson.Gson();
        String saved = gson.toJson(clientConfig());
        assertTrue(saved.contains("\"enableRegionSummarySync\":true"),
                "a fresh config must persist the default under the exact key: " + saved);
        var loaded = gson.fromJson(saved.replace(
                "\"enableRegionSummarySync\":true", "\"enableRegionSummarySync\":false"),
                dev.vox.lss.config.LSSClientConfig.class);
        assertFalse(loaded.enableRegionSummarySync, "a saved false must bind back as false");
    }

    @Test
    void quadtreeScanDefaultsOnAndRoundTripsThroughJson() {
        // The third sibling (final panel: the one new client kill switch with no
        // config pin) — same hazard as its siblings: a silent default-off revert
        // would pass CI green while quietly reverting reset walks to the 30-90 ms
        // per-position shape in the field.
        var c = clientConfig();
        assertTrue(c.enableQuadtreeScan, "quadtree scan must default ON");
        c.validate();
        assertTrue(c.enableQuadtreeScan, "validate() must not touch the boolean");
        var gson = new com.google.gson.Gson();
        String saved = gson.toJson(clientConfig());
        assertTrue(saved.contains("\"enableQuadtreeScan\":true"),
                "a fresh config must persist the default under the exact key: " + saved);
        var loaded = gson.fromJson(saved.replace(
                "\"enableQuadtreeScan\":true", "\"enableQuadtreeScan\":false"),
                dev.vox.lss.config.LSSClientConfig.class);
        assertFalse(loaded.enableQuadtreeScan, "a saved false must bind back as false");
    }

    @Test
    void regionScanDefaultsOnAndRoundTripsThroughJson() {
        // The region-major want-set walk's arm selector (region-scan-plan.md §2.4;
        // selection happens at LodRequestManager CONSTRUCTION, so this key decides
        // the arm for the next join/config re-push). Same hazard as its scan-family
        // siblings: a silent default-off revert would pass CI green while quietly
        // restoring the ~95-region working set the walk exists to collapse.
        var c = clientConfig();
        assertTrue(c.enableRegionScan, "region scan must default ON");
        c.validate();
        assertTrue(c.enableRegionScan, "validate() must not touch the boolean");
        var gson = new com.google.gson.Gson();
        String saved = gson.toJson(clientConfig());
        assertTrue(saved.contains("\"enableRegionScan\":true"),
                "a fresh config must persist the default under the exact key: " + saved);
        var loaded = gson.fromJson(saved.replace(
                "\"enableRegionScan\":true", "\"enableRegionScan\":false"),
                dev.vox.lss.config.LSSClientConfig.class);
        assertFalse(loaded.enableRegionScan, "a saved false must bind back as false");
    }

    @Test
    void xaeroMapBridgeDefaultsOffAndRoundTripsThroughJson() {
        // The Xaero bridge toggle (xaero-map-bridge-plan.md §2.9) ships OFF for
        // v0.12.0 (user decision 2026-08-23: opt-in while the feature is new — map
        // writes are persistent saved data). Same sibling hazard as ever: a silent
        // default flip would pass CI green while the Sodium option's
        // setDefaultValue quietly disagreed with the field.
        var c = clientConfig();
        assertFalse(c.enableXaeroMapBridge, "the Xaero map bridge must default OFF");
        c.validate();
        assertFalse(c.enableXaeroMapBridge, "validate() must not touch the boolean");
        var gson = new com.google.gson.Gson();
        String saved = gson.toJson(clientConfig());
        assertTrue(saved.contains("\"enableXaeroMapBridge\":false"),
                "a fresh config must persist the default under the exact key: " + saved);
        var loaded = gson.fromJson(saved.replace(
                "\"enableXaeroMapBridge\":false", "\"enableXaeroMapBridge\":true"),
                dev.vox.lss.config.LSSClientConfig.class);
        assertTrue(loaded.enableXaeroMapBridge, "a saved true must bind back as true");
    }

    @Test
    void xaeroMapBackpressureDefaultsOnAndRoundTripsThroughJson() {
        // §12 (hybrid-scan-plan.md): the bridge's ingest backpressure ships ON (the
        // bridge itself stays opt-in — the taper is only consulted while the bridge
        // runs, so the cautious default is the governed one, not a second opt-in;
        // false = the pre-§12 shed-at-the-cap behavior). Replaced the §18 heal key
        // (enableXaeroMapBridgeHeal), deleted with the ledger heal.
        var c = clientConfig();
        assertTrue(c.enableXaeroMapBackpressure);
        c.validate();
        assertTrue(c.enableXaeroMapBackpressure, "validate() must not touch the boolean");
        var gson = new com.google.gson.Gson();
        String saved = gson.toJson(clientConfig());
        assertTrue(saved.contains("\"enableXaeroMapBackpressure\":true"), saved);
        assertFalse(saved.contains("enableXaeroMapBridgeHeal"),
                "the deleted heal key must not resurface");
        var loaded = gson.fromJson(saved.replace(
                "\"enableXaeroMapBackpressure\":true", "\"enableXaeroMapBackpressure\":false"),
                dev.vox.lss.config.LSSClientConfig.class);
        assertFalse(loaded.enableXaeroMapBackpressure, "a saved false must bind back as false");
    }

    @Test
    void scanPrefixRetentionDefaultsOnAndRoundTripsThroughJson() {
        // Scan prefix retention (docs/planning/scanner-reopened-rings-plan.md) ships ON —
        // a silent default-off revert would pass CI green (unit rigs set the seam
        // explicitly) while quietly restoring the per-crossing full-disc walk hitch. Plus
        // the GSON leg: exact key + a saved false binds back as false.
        var c = clientConfig();
        assertTrue(c.enableScanPrefixRetention, "scan prefix retention must default ON");
        c.validate();
        assertTrue(c.enableScanPrefixRetention, "validate() must not touch the boolean");
        var gson = new com.google.gson.Gson();
        String saved = gson.toJson(clientConfig());
        assertTrue(saved.contains("\"enableScanPrefixRetention\":true"),
                "a fresh config must persist the default under the exact key: " + saved);
        var loaded = gson.fromJson(saved.replace(
                "\"enableScanPrefixRetention\":true", "\"enableScanPrefixRetention\":false"),
                dev.vox.lss.config.LSSClientConfig.class);
        assertFalse(loaded.enableScanPrefixRetention, "a saved false must bind back as false");
    }

    @Test
    void adaptiveScanCadenceRoundTripsThroughJson() {
        // The GSON leg: the field serializes under its exact key (a rename would silently
        // orphan every saved kill-switch choice) and a saved false binds back as false.
        var gson = new com.google.gson.Gson();
        String saved = gson.toJson(clientConfig());
        assertTrue(saved.contains("\"enableAdaptiveScanCadence\":true"),
                "a fresh config must persist the default under the exact key: " + saved);
        var loaded = gson.fromJson(saved.replace(
                "\"enableAdaptiveScanCadence\":true", "\"enableAdaptiveScanCadence\":false"),
                LSSClientConfig.class);
        assertFalse(loaded.enableAdaptiveScanCadence, "a saved false must bind back as false");
    }

    @Test
    void ingestBackpressureRoundTripsThroughJson() {
        // The GSON leg of the save/load contract: the field serializes under its exact key
        // (a rename would silently orphan every saved kill-switch choice) and a saved false
        // binds back as false.
        var gson = new com.google.gson.Gson();
        String saved = gson.toJson(clientConfig());
        assertTrue(saved.contains("\"enableIngestBackpressure\":true"),
                "a fresh config must persist the default under the exact key: " + saved);
        var loaded = gson.fromJson(saved.replace(
                "\"enableIngestBackpressure\":true", "\"enableIngestBackpressure\":false"),
                LSSClientConfig.class);
        assertFalse(loaded.enableIngestBackpressure, "a saved false must bind back as false");
    }

    @Test
    void lodColumnsPerSecondLimitDefaultsOffAndClamps() {
        // The manual column-rate cap (docs/planning/client-column-rate-cap-design.md):
        // 0 = off is the shipped default and must survive validate() bit-identically.
        var c = clientConfig();
        assertEquals(0, c.lodColumnsPerSecondLimit, "the cap must ship OFF");
        c.validate();
        assertEquals(0, c.lodColumnsPerSecondLimit, "0 must stay 0 (never clamped up to the floor)");

        // A negative hand-edit means "off" — clamping it to the floor would silently ARM a
        // cap the user meant to disable.
        c.lodColumnsPerSecondLimit = -5;
        c.validate();
        assertEquals(0, c.lodColumnsPerSecondLimit, "negatives normalize to off, never to the floor");

        c.lodColumnsPerSecondLimit = 5;
        c.validate();
        assertEquals(10, c.lodColumnsPerSecondLimit, "positive values clamp to the 10 floor");

        // The 2026-08-14 granularity request: a low-but-plausible manual rate like 20
        // must survive validate() unchanged (the old floor of 50 silently rewrote it).
        c.lodColumnsPerSecondLimit = 20;
        c.validate();
        assertEquals(20, c.lodColumnsPerSecondLimit, "20 col/s is a legal manual cap");

        c.lodColumnsPerSecondLimit = 1_000_000;
        c.validate();
        assertEquals(100_000, c.lodColumnsPerSecondLimit, "...and to the 100k ceiling");

        // Every nonzero Sodium slider stop must round-trip unchanged — the slider is
        // curved (RateSliderStops: 10s, then 25s/50s/100s up to 3200), and its
        // lowest nonzero stop equals the validate() floor by construction.
        c.lodColumnsPerSecondLimit = 3200;
        c.validate();
        assertEquals(3200, c.lodColumnsPerSecondLimit);
    }

    @Test
    void rateSliderCurveRoundTripsTheClampAndCoversTheLowEnd() {
        // The curved "Max LOD Download Rate" slider (2026-08-14 granularity request).
        // Invariant 1: the UI must never lie — every nonzero stop survives validate()
        // unchanged, so what the slider shows is what the config keeps.
        var stops = RateSliderStops.STOPS;
        var c = clientConfig();
        for (int i = 1; i < stops.length; i++) {
            c.lodColumnsPerSecondLimit = stops[i];
            c.validate();
            assertEquals(stops[i], c.lodColumnsPerSecondLimit,
                    "slider stop " + stops[i] + " must round-trip the clamp unchanged");
            assertTrue(stops[i] > stops[i - 1], "stops must be strictly ascending");
        }
        // Invariant 2: the shape the request asked for — stop 0 is off, the lowest
        // nonzero stop IS the clamp floor (10), 20 is selectable, top stays 3200.
        assertEquals(0, stops[0], "stop 0 must be off");
        c.lodColumnsPerSecondLimit = stops[1] - 1;
        c.validate();
        assertEquals(stops[1], c.lodColumnsPerSecondLimit,
                "the lowest nonzero stop must equal the validate() floor");
        assertTrue(java.util.Arrays.stream(stops).anyMatch(s -> s == 20),
                "20 col/s must be a selectable stop (the granularity request)");
        assertEquals(3200, stops[stops.length - 1], "the top stop is the no-op bound");

        // Invariant 3: nearestIndex — off maps only to stop 0 and stop 0 only to off
        // (a tiny nonzero rate must never display as Unlimited, nor off as a throttle);
        // exact stops map to themselves; legal-but-inert hand-edits snap to the top.
        assertEquals(0, RateSliderStops.nearestIndex(0));
        assertEquals(0, RateSliderStops.nearestIndex(-7));
        assertEquals(1, RateSliderStops.nearestIndex(1), "1 col/s must not read as Unlimited");
        for (int i = 1; i < stops.length; i++) {
            assertEquals(i, RateSliderStops.nearestIndex(stops[i]),
                    "exact stop " + stops[i] + " must map to its own index");
        }
        assertEquals(stops.length - 1, RateSliderStops.nearestIndex(100_000),
                "above-top values display snapped to the top stop");
    }

    @Test
    void lodColumnsPerSecondLimitRoundTripsThroughJson() {
        // Same GSON contract as the toggles: exact key, and a saved nonzero binds back.
        var gson = new com.google.gson.Gson();
        String saved = gson.toJson(clientConfig());
        assertTrue(saved.contains("\"lodColumnsPerSecondLimit\":0"),
                "a fresh config must persist the off default under the exact key: " + saved);
        var loaded = gson.fromJson(saved.replace(
                "\"lodColumnsPerSecondLimit\":0", "\"lodColumnsPerSecondLimit\":400"),
                LSSClientConfig.class);
        assertEquals(400, loaded.lodColumnsPerSecondLimit, "a saved cap must bind back");
    }

    /**
     * The §3 fallback ladder's TERMINAL config carrier (XVER §9 client-config
     * validation): GSON can null or blank this from a malformed/hand-edited file, and
     * a null reaching {@code ClientIdentityResolver.resolveTerminalBlock} is survivable
     * (it coerces to stone) but a BLANK would resolve as a malformed identity every
     * session with no heal on disk — validate() owns restoring the default. The
     * resolver validates the value's RESOLUTION itself; config only carries it, so
     * an arbitrary non-blank string must survive validate() untouched.
     */
    @Test
    void unknownBlockFallbackHealsNullAndBlankToTheDefault() {
        var c = clientConfig();
        c.unknownBlockFallback = null;
        c.validate();
        assertEquals("minecraft:stone", c.unknownBlockFallback, "null must heal to the default");

        c.unknownBlockFallback = "   ";
        c.validate();
        assertEquals("minecraft:stone", c.unknownBlockFallback, "blank must heal to the default");

        c.unknownBlockFallback = "minecraft:sandstone";
        c.validate();
        assertEquals("minecraft:sandstone", c.unknownBlockFallback,
                "a configured value is carried verbatim — resolution is the resolver's job");
    }

    /**
     * The ladder's CURATED rung table: the resolver's constructor does
     * {@code Map.copyOf(CONFIG.crossVersionBlockFallbacks)}, which throws on null —
     * so a GSON-nulled table would crash resolver construction on the FIRST v20
     * column of every session unless validate() heals it to the empty map. A
     * populated table must pass through untouched (it is user-extended as real
     * cross-version gaps are reported).
     */
    @Test
    void crossVersionBlockFallbacksHealNullToTheEmptyMap() {
        var c = clientConfig();
        c.crossVersionBlockFallbacks = null;
        c.validate();
        assertNotNull(c.crossVersionBlockFallbacks, "null must heal to an empty map");
        assertTrue(c.crossVersionBlockFallbacks.isEmpty());

        c.crossVersionBlockFallbacks = new java.util.HashMap<>(
                java.util.Map.of("ancient:sulfur", "minecraft:sandstone"));
        c.validate();
        assertEquals(java.util.Map.of("ancient:sulfur", "minecraft:sandstone"),
                c.crossVersionBlockFallbacks, "user entries must survive validate() untouched");
    }

    /**
     * The effective-config echo is a SCRIPT-CONSUMED CONTRACT (PERF Phase 0 item 1):
     * profile_disk_read.sh / compress_gate.sh / backfill_profile.sh grep
     * "Effective config: " from server.log and match per-key substrings into each
     * run's arm_valid. Exact-string pin — a format drift here silently invalidates
     * every measurement arm, so this must red before any script does.
     */
    @Test
    void effectiveConfigEchoIsAScriptConsumedContract() {
        var c = serverConfig();
        c.useNbtTranscode = false;
        // The compression value echoed is the EFFECTIVE post-probe state the caller
        // passes, NOT this field — set the field to the opposite to pin that (a
        // probe-failed server must echo false so compress_gate reds the arm, B0 M1).
        c.useCompressedColumns = false;
        assertEquals("Effective config: useNbtTranscode=false, diskReaderThreads=7,"
                        + " useCompressedColumns=true, useBackgroundReadSplit=true,"
                        + " useSelectiveNbtParse=true, maxConcurrentDiskReads=4",
                c.effectiveConfigEcho(7, true, 4),
                "key order and key=value spelling are what the harnesses grep");
        // The thread count echoed is the RESOLVED one the caller passes (0=AUTO already
        // applied) — the scripts assert the staged explicit value appears verbatim.
        // Same rule for the gate's K (the store-conditional resolution, post-degrade).
        c.useNbtTranscode = true;
        c.useCompressedColumns = true;
        c.useBackgroundReadSplit = false;
        c.useSelectiveNbtParse = false;
        assertEquals("Effective config: useNbtTranscode=true, diskReaderThreads=5,"
                        + " useCompressedColumns=false, useBackgroundReadSplit=false,"
                        + " useSelectiveNbtParse=false, maxConcurrentDiskReads=5",
                c.effectiveConfigEcho(5, false, 5));
    }

    /** The disk-read gate's K resolver (disk-read-concurrency-gate-plan.md): 0 = AUTO is
     *  STORE-CONDITIONAL — no store attached resolves to the pool (a no-op gate; the
     *  store-off population must never pay the half-pool tax — both gate reviews'
     *  convergent MAJOR), store attached resolves to ceil(pool/2); an explicit override
     *  clamps to the pool (a K above it cannot bind — the documented OFF idiom). */
    @Test
    void effectiveMaxConcurrentDiskReadsIsStoreConditionalWithPoolClampedOverride() {
        var c = serverConfig();

        c.maxConcurrentDiskReads = 0; // AUTO
        assertEquals(8, c.effectiveMaxConcurrentDiskReads(8, false),
                "AUTO with no store attached must be the pool — a structural no-op");
        assertEquals(3, c.effectiveMaxConcurrentDiskReads(3, false));
        assertEquals(4, c.effectiveMaxConcurrentDiskReads(8, true),
                "AUTO with a store: half the pool, reserving the rest for store lookups");
        assertEquals(2, c.effectiveMaxConcurrentDiskReads(3, true), "ceil(3/2) = 2");
        assertEquals(1, c.effectiveMaxConcurrentDiskReads(1, true),
                "pool 1: K=1 — exactly today's behavior, nothing regresses");

        c.maxConcurrentDiskReads = 2;
        assertEquals(2, c.effectiveMaxConcurrentDiskReads(8, true), "explicit override wins");
        assertEquals(2, c.effectiveMaxConcurrentDiskReads(8, false),
                "an explicit override binds regardless of store attachment");
        c.maxConcurrentDiskReads = 64;
        assertEquals(8, c.effectiveMaxConcurrentDiskReads(8, true),
                "override >= pool clamps to the pool — the disable idiom");

        // The diskReaderThreads negative-normalizes-to-AUTO mirror: -1 must mean AUTO
        // (store-conditional), never clamp up to the tightest possible gate (1).
        c.maxConcurrentDiskReads = -1;
        c.validate();
        assertEquals(0, c.maxConcurrentDiskReads, "negative normalizes to AUTO");
        assertEquals(8, c.effectiveMaxConcurrentDiskReads(8, false));
        c.maxConcurrentDiskReads = 3;
        c.validate();
        assertEquals(3, c.maxConcurrentDiskReads, "in-band explicit value survives validate");
        c.maxConcurrentDiskReads = 9999;
        c.validate();
        assertEquals(LSSConstants.MAX_DISK_READER_THREADS, c.maxConcurrentDiskReads,
                "validate clamps nonzero to the shared ceiling; the pool clamp is at derivation");
    }


    /** Far players (E1): the mode normalizes through the shared helper (unknown -> off,
     *  the inert compiled default), and the min ring drags under the CONFIGURED max —
     *  the cross-field rule the table sweeps cannot express (an inverted ring would
     *  hide everyone). */
    @Test
    void farPlayersModeNormalizesAndMinRingDragsUnderTheConfiguredMax() {
        var c = serverConfig();
        assertEquals("on", c.farPlayers,
                "E2 flips the compiled default ON for fresh AND upgrading installs"
                        + " (user decision 2026-08-12)");
        c.farPlayers = "ON";
        c.validate();
        assertEquals("on", c.farPlayers, "case-insensitive canonical spelling");
        c.farPlayers = "optin";
        c.validate();
        assertEquals("opt-in", c.farPlayers, "alias spellings normalize");
        c.farPlayers = "banana";
        c.validate();
        assertEquals("off", c.farPlayers, "unknown modes fail SAFE to off");
        c.farPlayers = null;
        c.validate();
        assertEquals("off", c.farPlayers);

        c.farPlayersMaxDistanceBlocks = 1024;
        c.farPlayersMinDistanceBlocks = 9000;
        c.validate();
        assertEquals(1024, c.farPlayersMinDistanceBlocks,
                "min drags under the configured max — never an inverted ring");
        c.farPlayersExclude = null;
        c.validate();
        assertEquals(java.util.List.of(), c.farPlayersExclude,
                "a malformed null exclude list restores the empty default");
    }
}
