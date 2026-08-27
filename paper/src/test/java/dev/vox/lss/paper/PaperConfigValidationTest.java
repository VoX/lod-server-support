package dev.vox.lss.paper;

import dev.vox.lss.common.LSSConstants;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins PaperConfig.validate(): the shared ServerConfigBase clamps must run through the Paper
 * subclass (i.e. the super.validate() call survives) and the Paper-only updateEvents null guard
 * must replace null with an empty list. The Fabric ConfigValidationTest exercises the same
 * clamps only via LSSServerConfig, so without this test the Paper override is unpinned.
 */
class PaperConfigValidationTest {

    /** Pins the Paper-only resweep default (4-agent round R3: nothing pinned it — the
     *  instance initializer is Paper's ONLY bound on unfired-event staleness, and
     *  deleting it would go green everywhere). GSON keeps the initialized value on a
     *  missing key, so a fresh Paper config writes 300 while Fabric stays 0 (the
     *  save-hook owns Fabric's within-session freshness). */
    @Test
    void paperDefaultsTheLodStoreResweepTo300() {
        assertEquals(300, new PaperConfig().lodStoreResweepSeconds,
                "Paper's periodic resweep default is its unfired-event staleness bound");
    }

    /** Twin of the Fabric pin: all compat rungs ship ON so released v0.6.x (protocol 16),
     *  v0.7.x–v0.8.x (protocol 18), and v0.9.x–v0.10.x-pre (protocol 19) clients keep
     *  working across a server upgrade. */
    @Test
    void compatRungsDefaultOn() {
        assertTrue(new PaperConfig().enableV16Compat);
        assertTrue(new PaperConfig().enableV18Compat);
        assertTrue(new PaperConfig().enableV19Compat);
        assertTrue(new PaperConfig().enableViaMismatchGuard);
        // The ping backstop ships ON (adaptive-transfer-rate-plan.md Mechanism B —
        // it protects ANY client on a congested link, including pre-governor ones).
        assertTrue(new PaperConfig().enablePingBackstop);
        assertTrue(new PaperConfig().enableSendPacing);
    }

    /** The service gate ships OFF (upstream kill-switch rule): a server that upgrades into
     *  this build must keep serving every player exactly as before, with no permission node
     *  to grant first. Flipping this default would black out every non-op on every install. */
    @Test
    void requireServicePermissionShipsOff() {
        assertFalse(new PaperConfig().requireServicePermission,
                "the per-player service gate is opt-in; on by default would deny every non-op");
        var c = new PaperConfig();
        c.validate();
        assertFalse(c.requireServicePermission, "validate() must not move the default");
    }

    @Test
    void validateClampsInheritedFieldsAndGuardsUpdateEvents() {
        PaperConfig c = new PaperConfig();
        c.lodDistanceChunks = 99999;
        c.generationConcurrencyLimitPerPlayer = 0;
        c.updateEvents = null;
        c.validate();
        assertEquals(2048, c.lodDistanceChunks);                 // LSSConstants.MAX_LOD_DISTANCE via super.validate()
        assertEquals(1, c.generationConcurrencyLimitPerPlayer);  // LSSConstants.MIN_CONCURRENCY_LIMIT via super.validate()
        assertEquals(List.of(), c.updateEvents);                 // Paper-only null guard
    }

    /** Paper twin of the cap-behavior pins (store-cap-behavior-plan.md §1): the
     *  inherited default is 0 = uncapped, and the 64 floor binds only nonzero caps. */
    @Test
    void lodStoreMaxMBInheritsUncappedZeroDefaultAndNonzeroFloor() {
        assertEquals(0, new PaperConfig().lodStoreMaxMB,
                "Paper must inherit the uncapped-by-default store");
        PaperConfig c = new PaperConfig();
        c.lodStoreMaxMB = 1;
        c.validate();
        assertEquals(LSSConstants.MIN_LOD_STORE_MAX_MB, c.lodStoreMaxMB,
                "a nonzero opt-in cap must keep the 64 MB floor through the Paper subclass");
        c.lodStoreMaxMB = 63; // the boundary just under the floor (plan §4: 1..63 -> 64)
        c.validate();
        assertEquals(LSSConstants.MIN_LOD_STORE_MAX_MB, c.lodStoreMaxMB);
    }

    /** Paper twin of the Fabric named test (v0.11.0, dirty-broadcast-interval-zero-plan.md):
     *  0 = dirty pushes disabled survives validate() through the Paper subclass, negatives
     *  normalize to 0, and 1 stays the floor for a nonzero (sending) interval. */
    @Test
    void dirtyBroadcastIntervalZeroDisablesSendsAndNonzeroFloorsAt1() {
        PaperConfig c = new PaperConfig();
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
    }

    /** The lodStore SPLIT default (user decision, 2026-08-08 second round), through the
     *  Paper subclass: compiled default "off" — a key absent from an existing file must
     *  never arm the store on upgrade — while fresh installs generate "on" via
     *  onFreshCreate (pinned through the real load path in PaperConfigLoadTest). Paper
     *  adds no Folia-specific override — on Folia validate() WARNS whenever the store
     *  is armed, since Folia support is experimental wholesale.
     *
     *  <p>lodStoreBackfill stays ON, and that pairing is the point: an armed store is
     *  the whole feature, and lodStore=off still disables both. */
    @Test
    void lodStoreCompiledDefaultIsOffForAbsentKeysWithBackfillArmed() {
        var c = new PaperConfig();
        assertEquals("off", c.lodStore,
                "compiled default off: an absent key must never arm the store on upgrade");
        assertTrue(c.lodStoreBackfill,
                "backfill stays on so an armed store is the whole feature");
    }

    /** Paper inherits the shared yield default: unarmed until the live E3 A/B (§4). */
    @Test
    void transportYieldDefaultsOn() {
        assertTrue(new PaperConfig().lodYieldsToVanillaTransport,
                "lodYieldsToVanillaTransport defaults TRUE since v0.11.0 (Fabric parity)");
    }

    /** Paper inherits the shared transcode default: disk serves transcode NBT straight to
     *  wire bytes; false is the documented rollback to the object path (round 2, 2026-07-29). */
    @Test
    void nbtTranscodeDefaultsOn() {
        assertTrue(new PaperConfig().useNbtTranscode,
                "NBT transcode must default on (Paper)");
    }

    // ---- full both-ends clamp sweep: Paper twin of ConfigValidationTest's reflective sweep ----

    private record Bounds(int min, int max) {}

    /** Expected clamp bounds per shared field — the same LSSConstants ServerConfigBase clamps
     *  with. Both per-player slot caps clamp to plain per-field bounds now: the #28 cross-clamp
     *  is gone (no client budget derives from any cap; the successor invariant is
     *  WantSetBudgetInvariantTest), and the sync cap is a constant, not a field. */
    private static final Map<String, Bounds> SHARED_BOUNDS = Map.ofEntries(
            Map.entry("lodDistanceChunks",
                    new Bounds(LSSConstants.MIN_LOD_DISTANCE, LSSConstants.MAX_LOD_DISTANCE)),
            // The bandwidth pair left the int table with the 2026-08-08 rename: the visible
            // knobs are the mb DOUBLES (their exact-bounds sweep is the dedicated arm below)
            // and the legacy byte ints are re-sentineling readers, not clamped fields.
            Map.entry("generationConcurrencyLimitGlobal",
                    new Bounds(LSSConstants.MIN_CONCURRENT_GENERATIONS, LSSConstants.MAX_CONCURRENT_GENERATIONS)),
            Map.entry("generationTimeoutSeconds",
                    new Bounds(LSSConstants.MIN_GENERATION_TIMEOUT, LSSConstants.MAX_GENERATION_TIMEOUT)),
            Map.entry("missMemoTtlSeconds",
                    new Bounds(LSSConstants.MIN_MISS_MEMO_TTL_SECONDS, LSSConstants.MAX_MISS_MEMO_TTL_SECONDS)),
            // dirtyBroadcastIntervalSeconds' legal floor is 0 (= dirty pushes off, v0.11.0);
            // the real 1 s floor applies to nonzero (sending) intervals only, pinned by the
            // named test below — same shape as the lodStoreMaxMB/outboundBufferCeilingKB rows.
            Map.entry("dirtyBroadcastIntervalSeconds",
                    new Bounds(0, LSSConstants.MAX_DIRTY_BROADCAST_INTERVAL)),
            Map.entry("sendQueueLimitPerPlayer",
                    new Bounds(LSSConstants.MIN_SEND_QUEUE_SIZE, LSSConstants.MAX_SEND_QUEUE_SIZE)),
            // maxConcurrentDiskReads' legal floor is 0 (= AUTO, store-conditional —
            // disk-read-concurrency-gate-plan.md); the 1 floor applies to nonzero
            // explicit values only, and the pool clamp lives at derivation (named
            // resolver tests in both twins).
            Map.entry("maxConcurrentDiskReads",
                    new Bounds(0, LSSConstants.MAX_DISK_READER_THREADS)),
            // Far players (E1): interval 2..100, max ring 128..16384.
            // farPlayersMinDistanceBlocks is NOT here: its effective ceiling is the
            // CONFIGURED max (validate() drags it under — an inverted ring hides
            // everyone), so like generationConcurrencyLimitPerPlayer it has no fixed
            // both-ends bounds; the named cross-field test lives in the Fabric twin.
            Map.entry("farPlayersUpdateIntervalTicks", new Bounds(2, 100)),
            Map.entry("farPlayersMaxDistanceBlocks", new Bounds(128, 16384)),
            // generationConcurrencyLimitPerPlayer and perDimensionTimestampCacheSizeMB left the
            // table-driven sweep 2026-08-02: the first clamps to the CONFIGURED global (§9.1) and
            // the second treats 0 as AUTO, so neither has fixed both-ends bounds. Named tests in
            // the Fabric twin cover them.
            // lodStoreMaxMB's legal floor is 0 (= uncapped, the default); the 64 floor
            // applies only to nonzero opt-in caps, pinned by the named test below.
            Map.entry("lodStoreMaxMB",
                    new Bounds(0, LSSConstants.MAX_LOD_STORE_MAX_MB)),
            Map.entry("lodStoreResweepSeconds",
                    new Bounds(LSSConstants.MIN_LOD_STORE_RESWEEP_SECONDS,
                            LSSConstants.MAX_LOD_STORE_RESWEEP_SECONDS)),
            Map.entry("lodStoreBackfillColumnsPerSecond",
                    new Bounds(LSSConstants.MIN_LOD_STORE_BACKFILL_CPS,
                            LSSConstants.MAX_LOD_STORE_BACKFILL_CPS)),
            Map.entry("xrayMaxBlockHeight",
                    new Bounds(LSSConstants.MIN_XRAY_MAX_BLOCK_HEIGHT, LSSConstants.MAX_XRAY_MAX_BLOCK_HEIGHT)));

    /**
     * Every shared numeric field must clamp to the exact shared bounds THROUGH the Paper subclass
     * at both ends, and keep exact-boundary values. A PaperConfig.validate() override that loses
     * its super.validate() call (or a new ServerConfigBase field added without a clamp) fails here.
     */
    @Test
    void everyNumericFieldClampsToExactSharedBoundsAtBothEnds() throws Exception {
        // Excluded 2026-08-02: diskReaderThreads and perDimensionTimestampCacheSizeMB treat 0 as
        // AUTO, and generationConcurrencyLimitPerPlayer clamps to the CONFIGURED global (§9.1) —
        // none has fixed both-ends bounds, so a table-driven sweep cannot express them. Named
        // tests in the Fabric twin cover all three.
        var derived = java.util.Set.of("diskReaderThreads", "perDimensionTimestampCacheSizeMB",
                "generationConcurrencyLimitPerPlayer",
                // Far players (E1): the min ring's effective ceiling is the CONFIGURED
                // max (validate() drags it under — an inverted ring hides everyone), so
                // no fixed both-ends bounds; named cross-field test in the Fabric twin.
                "farPlayersMinDistanceBlocks",
                // The 2026-08-08 bandwidth rename: the mb doubles have their own exact-bounds
                // arm below; the legacy byte ints re-sentinel instead of clamping (also below).
                "mbPerSecondLimitPerPlayer", "mbPerSecondLimitGlobal",
                "bytesPerSecondLimitPerPlayer", "bytesPerSecondLimitGlobal");
        List<Field> fields = Arrays.stream(PaperConfig.class.getFields())
                .filter(f -> !Modifier.isStatic(f.getModifiers()))
                .filter(f -> f.getType().isPrimitive() && f.getType() != boolean.class)
                .filter(f -> !derived.contains(f.getName()))
                .toList();
        // Anti-vacuity twin of the Fabric sweep guards: the sweep must keep seeing every field.
        assertEquals(SHARED_BOUNDS.keySet(),
                fields.stream().map(Field::getName).collect(Collectors.toSet()),
                "numeric field set drifted from SHARED_BOUNDS — update the sweep table");

        for (Field f : fields) {
            assertEquals(int.class, f.getType(), f.getName() + ": extend the sweep for non-int numeric fields");
            Bounds bounds = SHARED_BOUNDS.get(f.getName());

            PaperConfig c = new PaperConfig();
            f.setInt(c, Integer.MIN_VALUE);
            c.validate();
            assertEquals(bounds.min(), f.getInt(c), f.getName() + " must clamp up to the shared minimum");

            f.setInt(c, Integer.MAX_VALUE);
            c.validate();
            assertEquals(bounds.max(), f.getInt(c), f.getName() + " must clamp down to the shared maximum");

            f.setInt(c, bounds.min());
            c.validate();
            assertEquals(bounds.min(), f.getInt(c), f.getName() + " at the exact minimum must be kept");

            f.setInt(c, bounds.max());
            c.validate();
            assertEquals(bounds.max(), f.getInt(c), f.getName() + " at the exact maximum must be kept");
        }
    }

    /** The mb bandwidth doubles' exact-bounds arm (the 2026-08-08 rename twin of the old
     *  bytesPerSecond table rows): the byte bands re-denominated, THROUGH the Paper subclass. */
    @Test
    void mbBandwidthFieldsClampToExactSharedBoundsAtBothEnds() {
        double mb = 1024.0 * 1024.0;

        PaperConfig c = new PaperConfig();
        c.mbPerSecondLimitPerPlayer = 0.0000001;
        c.validate();
        assertEquals(LSSConstants.MIN_BYTES_PER_SECOND / mb, c.mbPerSecondLimitPerPlayer);
        c.mbPerSecondLimitPerPlayer = Double.MAX_VALUE;
        c.validate();
        assertEquals(LSSConstants.MAX_BYTES_PER_SECOND_PER_PLAYER / mb, c.mbPerSecondLimitPerPlayer);

        c.mbPerSecondLimitGlobal = 0.0000001;
        c.validate();
        assertEquals(LSSConstants.MIN_BYTES_PER_SECOND / mb, c.mbPerSecondLimitGlobal);
        c.mbPerSecondLimitGlobal = Double.MAX_VALUE;
        c.validate();
        assertEquals(LSSConstants.MAX_BYTES_PER_SECOND_GLOBAL_LIMIT / mb, c.mbPerSecondLimitGlobal);

        // The legacy byte spellings resolve into the mb keys and re-sentinel — never clamp in place.
        PaperConfig legacy = new PaperConfig();
        legacy.bytesPerSecondLimitPerPlayer = 10_485_760;
        legacy.bytesPerSecondLimitGlobal = Integer.MAX_VALUE;
        legacy.validate();
        assertEquals(10_485_760, legacy.bytesPerSecondPerPlayer(), "legacy value honored exactly");
        assertEquals(1_073_741_824, legacy.bytesPerSecondGlobal(), "legacy value rides the same clamp");
        assertEquals(-1, legacy.bytesPerSecondLimitPerPlayer, "re-sentineled after resolution");
        assertEquals(-1, legacy.bytesPerSecondLimitGlobal);
    }

    /** Compiled Paper defaults must already sit inside the clamp ranges: validate() may not
     *  move them — except the bandwidth sentinels, which resolve to the real defaults by
     *  design (the Fabric twin pins the same exception). */
    @Test
    void defaultsSurviveValidateUnchangedIncludingUpdateEvents() throws Exception {
        PaperConfig validated = new PaperConfig();
        validated.validate();
        PaperConfig pristine = new PaperConfig();
        for (Field f : PaperConfig.class.getFields()) {
            if (Modifier.isStatic(f.getModifiers())) continue;
            if (f.getName().startsWith("mbPerSecondLimit")) continue; // sentinel -> resolved
            assertEquals(f.get(pristine), f.get(validated),
                    "default for " + f.getName() + " is outside its clamp range");
        }
        assertEquals(25.0, validated.mbPerSecondLimitPerPlayer);
        assertEquals(75.0, validated.mbPerSecondLimitGlobal);
    }

    /**
     * Twin of the Fabric pin (ConfigValidationTest): the effective-config echo is a
     * script-consumed contract (PERF Phase 0 item 1) and must render identically on
     * Paper — the harnesses grep the same "Effective config: " line on both platforms.
     */
    @Test
    void effectiveConfigEchoIsAScriptConsumedContract() {
        PaperConfig c = new PaperConfig();
        c.useNbtTranscode = false;
        // Field deliberately opposite the passed effective value — the echo must use
        // the post-probe argument, never the config request (B0 review M1).
        c.useCompressedColumns = false;
        assertEquals("Effective config: useNbtTranscode=false, diskReaderThreads=7,"
                        + " useCompressedColumns=true, useBackgroundReadSplit=true,"
                        + " useSelectiveNbtParse=true, maxConcurrentDiskReads=4",
                c.effectiveConfigEcho(7, true, 4));
    }

    /** Twin of the Fabric store-conditional K resolver pins (the shared resolver runs
     *  through the Paper subclass; the SHARED_BOUNDS row covers the validate clamp). */
    @Test
    void effectiveMaxConcurrentDiskReadsResolvesThroughThePaperSubclass() {
        PaperConfig c = new PaperConfig();
        c.maxConcurrentDiskReads = 0;
        assertEquals(8, c.effectiveMaxConcurrentDiskReads(8, false),
                "AUTO, no store: the pool (no-op gate)");
        assertEquals(4, c.effectiveMaxConcurrentDiskReads(8, true),
                "AUTO, store armed: half the pool");
        c.maxConcurrentDiskReads = 64;
        assertEquals(8, c.effectiveMaxConcurrentDiskReads(8, true),
                "override >= pool clamps to the pool — the disable idiom");
    }

    /** Phase 4 twin of the Fabric default pin (shared key, Fabric-only in effect). */
    @Test
    void selectiveNbtParseDefaultsOn() {
        assertEquals(true, new PaperConfig().useSelectiveNbtParse);
    }
}
