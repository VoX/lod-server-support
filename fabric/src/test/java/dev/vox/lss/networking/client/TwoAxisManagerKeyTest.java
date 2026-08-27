package dev.vox.lss.networking.client;

import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.networking.payloads.SessionConfigS2CPayload;
import dev.vox.lss.seed.WorldSubKey;
import dev.vox.lss.testutil.SourcePaths;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The manager's two-axis differentials (plan §4.4): the kill-switch/default shapes are
 * byte-identical to the pre-plan bucket, the world sub-key derives fresh per
 * dimension/cache-phase entry (carrying forward ONLY across an unreadable read), the
 * alias latch is per-session and reset at JOIN, and the save-under-the-load-time-key
 * ordering inside {@code onDimensionChange} is pinned.
 */
class TwoAxisManagerKeyTest {

    @BeforeAll
    static void setup() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static ResourceKey<Level> dim(String name) {
        return ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse("lss_test:" + name));
    }

    private static LodRequestManager manager(String addr) {
        var m = new LodRequestManager();
        m.onSessionConfig(
                new SessionConfigS2CPayload(LSSConstants.PROTOCOL_VERSION, true, 64, true), addr);
        return m;
    }

    private static WorldSubKey.Context liveSeed(long seed) {
        return new WorldSubKey.Context(true, true, false, false, true, seed);
    }

    private static WorldSubKey.Context unreadable() {
        return new WorldSubKey.Context(true, true, false, false, false, 0L);
    }

    // ---- byte-identical defaults ----

    @Test
    void anUnconfiguredManagerKeepsThePassedKeyVerbatim() {
        var m = manager("play.example.com");
        assertEquals("play.example.com", m.cacheBucketForTest(),
                "direct-drive rigs (every pre-plan test and harness) keep their exact bucket");
    }

    @Test
    void theKillSwitchOffKeepsTheBareLegacyBucket() {
        // Byte-identical for every ORDINARY shape; the one documented exception is a
        // raw address ending in the reserved .world-<16hex> tail, whose escape applies
        // switch-independently (the sweep-safety invariant outranks byte-identity).
        var m = manager("play.example.com");
        m.configureCacheKeying("play.example.com", List.of("play.example.com"), "address",
                () -> new WorldSubKey.Context(false, true, false, false, true, 42L));
        assertEquals("play.example.com", m.cacheBucketForTest(),
                "useWorldSubBuckets=false = bare legacy naming, byte for byte");
        assertTrue(m.describeCacheKey().contains("world=none — off"), m.describeCacheKey());
    }

    // ---- the world axis ----

    @Test
    void aQualifyingSeedComposesTheSubBucket() {
        var m = manager("play.example.com");
        m.configureCacheKeying("play.example.com", List.of("play.example.com"), "address",
                () -> liveSeed(0x2aL));
        assertEquals("play.example.com.world-000000000000002a", m.cacheBucketForTest());
        assertTrue(m.describeCacheKey().contains("world=000000000000002a — live"),
                m.describeCacheKey());
    }

    @Test
    void eachDimensionEntryReadsTheLiveSeed() {
        var ctx = new AtomicReference<>(liveSeed(1L));
        var m = manager("addr.example.com");
        m.configureCacheKeying("addr.example.com", List.of("addr.example.com"), "address",
                ctx::get);
        assertEquals("addr.example.com." + WorldSubKey.format(1L), m.cacheBucketForTest());

        // First cache-phase entry: same seed, same bucket.
        m.tickDimensionAndCachePhase(dim("overworld"), 0, 0);
        assertEquals("addr.example.com." + WorldSubKey.format(1L), m.cacheBucketForTest());

        // The server rotates worlds under one address: the NEXT dimension entry re-keys.
        ctx.set(liveSeed(2L));
        m.tickDimensionAndCachePhase(dim("world_two"), 0, 0);
        assertEquals("addr.example.com." + WorldSubKey.format(2L), m.cacheBucketForTest(),
                "a latched sub-key would be COARSER than Voxy here (§9 M-A1/M-B1)");
    }

    @Test
    void anUnreadableReadCarriesTheSubKeyForwardButAReadableAnswerReplacesIt() {
        var ctx = new AtomicReference<>(liveSeed(7L));
        var m = manager("carry.example.com");
        m.configureCacheKeying("carry.example.com", List.of("carry.example.com"), "address",
                ctx::get);
        m.tickDimensionAndCachePhase(dim("overworld"), 0, 0);
        assertEquals("carry.example.com." + WorldSubKey.format(7L), m.cacheBucketForTest());

        ctx.set(unreadable());
        m.tickDimensionAndCachePhase(dim("the_nether"), 0, 0);
        assertEquals("carry.example.com." + WorldSubKey.format(7L), m.cacheBucketForTest(),
                "unreadable = carry forward, never a silent drop to the bare bucket");
        assertTrue(m.describeCacheKey().contains("— carried"), m.describeCacheKey());

        ctx.set(liveSeed(0L)); // seed 0 is a DEFINITIVE no-sub-key answer
        m.tickDimensionAndCachePhase(dim("lobby"), 0, 0);
        assertEquals("carry.example.com", m.cacheBucketForTest(),
                "a readable definitive answer replaces the carried sub-key");
        assertTrue(m.describeCacheKey().contains("world=none — seed-0"), m.describeCacheKey());
    }

    @Test
    void seedlessSessionsNeverQueueBucketPreparation() throws Exception {
        String addr = "seedless-" + System.nanoTime() + ".example.com";
        var m = manager(addr);
        m.configureCacheKeying(addr, List.of(addr), "address",
                () -> new WorldSubKey.Context(true, true, false, false, true, 0L));
        m.tickDimensionAndCachePhase(dim("overworld"), 0, 0);
        ColumnCacheStore.flushPendingIo();
        assertEquals(addr, m.cacheBucketForTest());
        try (var entries = Files.newDirectoryStream(ColumnCacheStore.cacheRoot())) {
            for (var e : entries) {
                assertTrue(!e.getFileName().toString().startsWith(addr + ".world-"),
                        "no world bucket may exist for a seedless session: " + e);
            }
        } catch (java.nio.file.NoSuchFileException benign) {
            // no cache root at all — equally proves nothing was prepared
        }
    }

    @Test
    void aLateCrossDimensionUnstampTargetsTheDepartedDimensionsOwnBucket() throws Exception {
        // Panel MAJOR: after a dimension change re-keys the world axis, a late consumer
        // rejection for the OLD dimension must unstamp in the bucket that dimension
        // saved under — aiming at the current bucket would no-op and leave the false
        // ts>0 stamp forever (the #36 hole, reopened on per-world-seed servers).
        String comp = "xdim-" + System.nanoTime() + ".example.com";
        var ctx = new AtomicReference<>(liveSeed(0xAL));
        var m = manager(comp);
        m.configureCacheKeying(comp, List.of(comp), "address", ctx::get);
        var arena = dim("arena");
        m.tickDimensionAndCachePhase(arena, 0, 0);
        String arenaBucket = m.cacheBucketForTest();

        // Plant the false stamp exactly where that dimension's cache lives.
        var stamps = new it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap();
        stamps.defaultReturnValue(-1L);
        long packed = dev.vox.lss.common.PositionUtil.packPosition(3, 4);
        stamps.put(packed, 9999L);
        ColumnCacheStore.save(arenaBucket, arena, stamps);

        // The world rotates: the next dimension re-keys the bucket.
        ctx.set(liveSeed(0xBL));
        m.tickDimensionAndCachePhase(dim("hub"), 0, 0);
        org.junit.jupiter.api.Assertions.assertNotEquals(arenaBucket, m.cacheBucketForTest());

        // The late rejection for the departed dimension.
        m.onIngestFailure(arena, packed);
        ColumnCacheStore.flushPendingIo();

        assertEquals(-1L, ColumnCacheStore.load(arenaBucket, arena).get(packed),
                "the unstamp landed in the DEPARTED dimension's bucket");
        m.flushCache();
    }

    @Test
    void aSeedlessLegBlocksAdoptionForTheRestOfTheSession() throws Exception {
        // Panel fix: the lobby's own residue must not become the game world's stamps.
        String comp = "lobbyfirst-" + System.nanoTime() + ".example.com";
        var bare = ColumnCacheStore.cacheRoot().resolve(comp);
        Files.createDirectories(bare);
        Files.writeString(bare.resolve("minecraft_overworld.bin"), "lobby residue");

        var ctx = new AtomicReference<>(liveSeed(0L)); // the lobby: seed 0
        var m = manager(comp);
        m.configureCacheKeying(comp, List.of(comp), "address", ctx::get);
        m.tickDimensionAndCachePhase(dim("lobby"), 0, 0);
        assertEquals(comp, m.cacheBucketForTest());

        ctx.set(liveSeed(0x77L)); // transferred into the game world
        m.tickDimensionAndCachePhase(dim("game"), 0, 0);
        ColumnCacheStore.flushPendingIo();

        assertTrue(Files.exists(bare), "the bare residue stays where it is");
        assertTrue(!Files.exists(ColumnCacheStore.cacheRoot()
                        .resolve(comp + "." + WorldSubKey.format(0x77L))),
                "the game world's bucket starts empty — never seeded from lobby residue");
        m.flushCache();
    }

    @Test
    void aRebuildCarriesTheSubKeyOnlyAcrossAnUnreadableRead() {
        var m = manager("rebuild.example.com");
        m.configureCacheKeying("rebuild.example.com", List.of("rebuild.example.com"),
                "address", TwoAxisManagerKeyTest::unreadable);
        assertEquals("rebuild.example.com", m.cacheBucketForTest(),
                "an unreadable FIRST read has nothing to carry — bare");
        m.adoptCarriedSubKey(java.util.Optional.of(WorldSubKey.format(5L)));
        assertEquals("rebuild.example.com." + WorldSubKey.format(5L), m.cacheBucketForTest(),
                "the previous manager's sub-key survives the rebuild across an "
                        + "unreadable read (the governor's adoptFrom shape)");
        assertTrue(m.describeCacheKey().contains("— carried"), m.describeCacheKey());

        var readable = manager("rebuild2.example.com");
        readable.configureCacheKeying("rebuild2.example.com",
                List.of("rebuild2.example.com"), "address", () -> liveSeed(9L));
        readable.adoptCarriedSubKey(java.util.Optional.of(WorldSubKey.format(5L)));
        assertEquals("rebuild2.example.com." + WorldSubKey.format(9L),
                readable.cacheBucketForTest(), "a READABLE fresh answer always wins");
    }

    @Test
    void postAdoptionSwitchOffReadsAnEmptyBareBucket() throws Exception {
        // The documented non-rollback semantics (plan §2.3): after adoption the bare
        // bucket is GONE — a switched-off session starts cold rather than resurrecting
        // pre-adoption stamps.
        String comp = "postadopt-" + System.nanoTime() + ".example.com";
        var bare = ColumnCacheStore.cacheRoot().resolve(comp);
        Files.createDirectories(bare);
        Files.writeString(bare.resolve("minecraft_overworld.bin"), "warm stamps");

        var m = manager(comp);
        m.configureCacheKeying(comp, List.of(comp), "address", () -> liveSeed(0x33L));
        ColumnCacheStore.flushPendingIo(); // the adoption move runs

        var off = manager(comp);
        off.configureCacheKeying(comp, List.of(comp), "address",
                () -> new WorldSubKey.Context(false, true, false, false, true, 0x33L));
        assertEquals(comp, off.cacheBucketForTest(), "switch off = the bare name");
        assertTrue(!Files.exists(bare),
                "…which is now EMPTY: the switch is not a rollback lever after adoption");
        m.flushCache();
    }

    // ---- the reset sweep wiring ----

    @Test
    void flushCacheSweepsTheConfiguredComponents() throws Exception {
        String comp = "sweepwire-" + System.nanoTime();
        var bare = ColumnCacheStore.cacheRoot().resolve(comp);
        Files.createDirectories(bare);
        var sibling = ColumnCacheStore.cacheRoot().resolve(comp + "." + WorldSubKey.format(5L));
        Files.createDirectories(sibling);

        var m = manager(comp);
        m.configureCacheKeying(comp, List.of(comp), "address", WorldSubKey.Context::disabled);
        m.flushCache();

        assertTrue(!Files.exists(bare), "the bare bucket is swept");
        assertTrue(!Files.exists(sibling), "…and its world siblings with it (plan §2.4)");
    }

    // ---- the alias latch ----

    @Test
    void theLatchComputesOnceAndIsResetByJoin() {
        AliasLatch.resetForJoin();
        var first = AliasLatch.forConnection("play.example.com",
                () -> new AliasLatch.Decision("play.example.com", "canonical.example.com",
                        List.of("canonical.example.com"), true, "voxy-corroborated"));
        var second = AliasLatch.forConnection("play.example.com",
                () -> { throw new AssertionError("a rebuild must reuse the latched decision"); });
        assertSame(first, second, "re-sent session configs rebuild the manager but keep "
                + "the session's alias decision");

        // JOIN brackets the play session — through the GATE, the production caller.
        var gate = new ClientSessionGate(new ClientColumnProcessor(), v -> {},
                config -> { throw new AssertionError("no manager needed here"); });
        gate.onJoin(false, false, false, true);
        assertNull(AliasLatch.peekForTest(), "onJoin resets the latch before its early returns");
    }

    @Test
    void aDifferentConnectAddressRecomputesDefensively() {
        AliasLatch.resetForJoin();
        AliasLatch.forConnection("a.example.com",
                () -> new AliasLatch.Decision("a.example.com", "a.example.com",
                        List.of("a.example.com"), false, "no-group"));
        var other = AliasLatch.forConnection("b.example.com",
                () -> new AliasLatch.Decision("b.example.com", "b.example.com",
                        List.of("b.example.com"), false, "no-group"));
        assertEquals("b.example.com", other.addressComponent());
        AliasLatch.resetForJoin();
    }

    // ---- source-order pin: the old dimension saves under its load-time key ----

    @Test
    void onDimensionChangeSavesBeforeRederivingTheBucket() throws Exception {
        String source = Files.readString(SourcePaths.mainSource(
                "dev/vox/lss/networking/client/LodRequestManager.java"));
        int body = source.indexOf("private void onDimensionChange");
        assertTrue(body >= 0, "onDimensionChange not found");
        int save = source.indexOf("saveCache();", body);
        int rederive = source.indexOf("rederiveCacheBucket();", body);
        int load = source.indexOf("startAsyncCacheLoad(", body);
        assertTrue(save >= 0 && rederive >= 0 && load >= 0,
                "the three phases must all appear in onDimensionChange");
        assertTrue(save < rederive && rederive < load,
                "save (old key) → rederive (fresh sub-key) → load (new key): reordering "
                        + "this files the old dimension's stamps under the NEW world's bucket");
    }

    // ---- observability ----

    @Test
    void describeCacheKeyNamesBothAxes() {
        var m = manager("diag.example.com");
        m.configureCacheKeying("diag.example.com", List.of("diag.example.com"), "alias group",
                () -> liveSeed(0xffL));
        String line = m.describeCacheKey();
        assertNotNull(line);
        assertEquals("key=diag.example.com.world-00000000000000ff "
                + "(alias group; world=00000000000000ff — live)", line);
    }
}
