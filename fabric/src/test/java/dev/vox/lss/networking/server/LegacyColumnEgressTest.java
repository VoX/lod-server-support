package dev.vox.lss.networking.server;

import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.common.store.StoreCodec;
import dev.vox.lss.common.wire.WireFormatException;
import dev.vox.lss.common.wire.WireSectionCursor;
import dev.vox.lss.networking.payloads.VoxelColumnS2CPayload;
import io.netty.buffer.Unpooled;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The C2 legacy egress body translation (XVER plan §4.2/§9), which runs at the
 * per-recipient ENQUEUE choke point ({@code FabricOffThreadProcessor.buildLegacyColumn}
 * over {@code NbtSectionSerializer.fromV20}) so every queued size — gauges, bandwidth
 * budget, diag books, soak law A2 — derives from the bytes the legacy client decodes.
 * The headline is the corpus-driven translation chain: for every committed fixture
 * pair, {@code fromV20(v20-corpus golden)} must BYTE-EQUAL the frozen {@code
 * nbt-corpus} native golden through the PRODUCTION registry tables — the §9 bijection
 * on one registry, driven by the same fixtures the emit direction is pinned against.
 * Build-level cases pin the codec choice (v19 recompress shrink-gated, forced-RAW
 * otherwise), the re-derived rawSize, splice composition (v18/v16 are "the translated
 * v19 minus header bytes"), and the loud failure shape the enqueue containment
 * catches.
 */
class LegacyColumnEgressTest {

    static {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static RegistryAccess REGISTRY_ACCESS;

    @BeforeAll
    static void setup() {
        REGISTRY_ACCESS = CorpusRegistryAccess.build();
    }

    // ---- fixtures ----

    /** Locate a corpus dir in this module regardless of the test JVM's working directory
     *  (the {@code NbtSectionSerializerTest.goldenPath} walk, parameterized by dir). */
    private static Path corpusDir(String dirName) {
        Path dir = Path.of("").toAbsolutePath();
        for (int depth = 0; depth < 5 && dir != null; depth++, dir = dir.getParent()) {
            if (Files.isDirectory(dir.resolve("src/test/java/dev/vox/lss"))) {
                return dir.resolve("src/test/resources").resolve(dirName);
            }
            Path nested = dir.resolve("fabric");
            if (Files.isDirectory(nested.resolve("src/test/java/dev/vox/lss"))) {
                return nested.resolve("src/test/resources").resolve(dirName);
            }
        }
        throw new IllegalStateException("cannot locate the fabric module source tree from "
                + Path.of("").toAbsolutePath());
    }

    private static byte[] readCorpus(String dirName, String name) {
        try {
            // Line-aware override-resolution (single-branch consolidation): the active line's
            // overlay copy of a golden wins over the shared corpus (e.g. 26.1's xray-masked.bin).
            return Files.readAllBytes(dev.vox.lss.LineGoldens.resolve(dirName, name));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static List<String> corpusNames() throws IOException {
        try (Stream<Path> files = Files.list(corpusDir("nbt-corpus"))) {
            return files.map(p -> p.getFileName().toString())
                    .filter(n -> n.endsWith(".bin")).sorted().toList();
        }
    }

    // ---- the §9 translation chain over the committed corpus ----

    /** The one deliberate byte divergence in the chain: this fixture's native palette
     *  carries DUPLICATE entries, which the dictionary de-duplicates on the way to v20
     *  (identity-keyed) — so the way back emits the translator's collapsed (minimal)
     *  palette, byte-smaller but content-identical. Native decoders tolerate either
     *  shape; {@link #duplicatePaletteFixtureCollapsesToContentIdenticalBytes} pins
     *  the content equality this exclusion leans on. */
    private static final String COLLAPSED_FIXTURE = "duplicate-air.bin";

    @Test
    void everyCorpusGoldenTranslatesBackToItsExactNativeBytes() throws IOException {
        var names = corpusNames();
        assertTrue(names.size() >= 14, "premise: the committed corpus has at least 14 fixtures, found " + names);
        assertTrue(names.contains(COLLAPSED_FIXTURE),
                "premise: the collapse carve-out below still names a committed fixture");
        for (String name : names) {
            if (name.equals(COLLAPSED_FIXTURE)) continue;
            byte[] nativeGolden = readCorpus("nbt-corpus", name);
            byte[] v20Golden = readCorpus("v20-corpus", name);
            assertArrayEquals(nativeGolden,
                    NbtSectionSerializer.fromV20(v20Golden, REGISTRY_ACCESS),
                    "fromV20 must reproduce the frozen native bytes through the "
                            + "production registry tables for " + name);
        }
    }

    @Test
    void duplicatePaletteFixtureCollapsesToContentIdenticalBytes() {
        byte[] nativeGolden = readCorpus("nbt-corpus", COLLAPSED_FIXTURE);
        byte[] translated = NbtSectionSerializer.fromV20(
                readCorpus("v20-corpus", COLLAPSED_FIXTURE), REGISTRY_ACCESS);
        assertFalse(java.util.Arrays.equals(nativeGolden, translated),
                "premise: the collapse actually diverges — if this starts passing "
                        + "byte-equal, fold the fixture back into the chain test");

        var expected = WireSectionCursor.parse(nativeGolden, WireSectionCursor.Layout.NATIVE);
        var actual = WireSectionCursor.parse(translated, WireSectionCursor.Layout.NATIVE);
        assertEquals(expected.sections().size(), actual.sections().size());
        for (int i = 0; i < expected.sections().size(); i++) {
            var e = expected.sections().get(i);
            var a = actual.sections().get(i);
            assertEquals(e.sectionY(), a.sectionY());
            assertEquals(e.nonEmptyBlockCount(), a.nonEmptyBlockCount());
            assertEquals(e.fluidCount(), a.fluidCount());
            assertArrayEquals(resolvedValues(e.blocks(), 4096), resolvedValues(a.blocks(), 4096),
                    "per-entry block ids must survive the palette collapse (section " + i + ")");
            assertArrayEquals(resolvedValues(e.biomes(), 64), resolvedValues(a.biomes(), 64),
                    "per-entry biome ids must survive the palette collapse (section " + i + ")");
            // A collapse only ever SHRINKS: a wider re-emit (e.g. DIRECT where indexed
            // was right) would be a byte-drift this carve-out must not mask (review m8).
            assertTrue(a.blocks().bits() <= e.blocks().bits(),
                    "collapsed block width must not exceed the original (section " + i + ")");
            assertArrayEquals(e.blockLight(), a.blockLight());
            assertArrayEquals(e.skyLight(), a.skyLight());
        }
    }

    /** Per-entry resolved ids of a native container (single-value / indexed / DIRECT). */
    private static int[] resolvedValues(WireSectionCursor.WireContainer c, int entries) {
        if (c.bits() == 0) {
            int[] out = new int[entries];
            java.util.Arrays.fill(out, c.palette()[0]);
            return out;
        }
        int[] values = WireSectionCursor.unpack(c.data(), c.bits(), entries);
        if (c.palette() == null) {
            return values; // DIRECT: the packed values ARE the ids
        }
        int[] out = new int[entries];
        for (int i = 0; i < entries; i++) {
            out[i] = c.palette()[values[i]];
        }
        return out;
    }

    // ---- the per-recipient build (codec choice + re-derived rawSize) ----

    @Test
    void rawBuildTranslatesInPlaceAndRederivesRawSize() {
        byte[] v20 = readCorpus("v20-corpus", "multi-section.bin");
        byte[] nativeGolden = readCorpus("nbt-corpus", "multi-section.bin");

        var build = FabricOffThreadProcessor.buildLegacyColumn(v20, REGISTRY_ACCESS, false, null);

        assertArrayEquals(nativeGolden, build.shipped());
        assertEquals(LSSConstants.COLUMN_CODEC_RAW, build.codecTag());
        assertEquals(nativeGolden.length, build.rawSize(),
                "rawSize must be re-derived from the TRANSLATED body — the legacy client's "
                        + "charge rule (and law A2's server book) read the bytes it receives");
    }

    @Test
    void compressedSessionBuildRecompressesTheNativeBody() {
        StoreCodec zstd = StoreCodec.zstdOrNull();
        assertNotNull(zstd, "the zstd natives ship on the test classpath (the store suite "
                + "requires them) — a null here is an environment regression, not a skip");
        byte[] v20 = readCorpus("v20-corpus", "multi-palette.bin");
        byte[] nativeGolden = readCorpus("nbt-corpus", "multi-palette.bin");

        var build = FabricOffThreadProcessor.buildLegacyColumn(v20, REGISTRY_ACCESS, true, zstd);

        assertEquals(LSSConstants.COLUMN_CODEC_ZSTD, build.codecTag(),
                "a v19 session keeps its compression capability — the recompress must fire");
        assertEquals(nativeGolden.length, build.rawSize());
        assertArrayEquals(nativeGolden, zstd.decompress(build.shipped(), build.rawSize()),
                "the recompressed frame must decompress to the exact native bytes");
    }

    @Test
    void tinyBodyShipsRawUnderTheShrinkGate() {
        StoreCodec zstd = StoreCodec.zstdOrNull();
        assertNotNull(zstd);
        // The v20 clear column {0,0} translates to the native clear {0} — one byte,
        // which no zstd frame can undercut, so the shrink gate must ship raw.
        var build = FabricOffThreadProcessor.buildLegacyColumn(
                new byte[] {0, 0}, REGISTRY_ACCESS, true, zstd);
        assertEquals(LSSConstants.COLUMN_CODEC_RAW, build.codecTag());
        assertArrayEquals(new byte[] {0}, build.shipped(),
                "the ghost-clear column must translate to the native single-byte clear");
        assertEquals(1, build.rawSize());
    }

    @Test
    void missingCodecDegradesToRawNotThrow() {
        // wantsCompressed with a null codec cannot happen in production (the flag
        // requires the live probe), but the build must stay total: raw is legal per
        // frame, so degrade, never throw.
        byte[] v20 = readCorpus("v20-corpus", "multi-palette.bin");
        var build = FabricOffThreadProcessor.buildLegacyColumn(v20, REGISTRY_ACCESS, true, null);
        assertEquals(LSSConstants.COLUMN_CODEC_RAW, build.codecTag());
    }

    // ---- splice composition: v18/v16 are "the translated v19 minus header bytes" ----

    private static VoxelColumnS2CPayload payloadOf(byte[] nativeBody) {
        return new VoxelColumnS2CPayload(7, -3, Level.OVERWORLD, 1234567L,
                LSSConstants.COLUMN_SOURCE_DISK, LSSConstants.COLUMN_CODEC_RAW,
                nativeBody, nativeBody.length);
    }

    private static byte[] encodePayload(VoxelColumnS2CPayload payload) {
        var buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            VoxelColumnS2CPayload.CODEC.encode(buf, payload);
            byte[] out = new byte[buf.readableBytes()];
            buf.readBytes(out);
            return out;
        } finally {
            buf.release();
        }
    }

    @Test
    void v18SpliceComposesOnTheTranslatedBody() {
        byte[] nativeBody = NbtSectionSerializer.fromV20(
                readCorpus("v20-corpus", "waterlogged.bin"), REGISTRY_ACCESS);
        byte[] nativeGolden = readCorpus("nbt-corpus", "waterlogged.bin");

        var buf = new FriendlyByteBuf(Unpooled.wrappedBuffer(
                encodePayload(payloadOf(nativeBody).asV18())));
        try {
            assertEquals(7, buf.readInt());
            assertEquals(-3, buf.readInt());
            assertEquals(Level.OVERWORLD.identifier().toString(),
                    buf.readUtf(LSSConstants.MAX_DIMENSION_STRING_LENGTH));
            assertEquals(1234567L, buf.readLong());
            assertEquals(LSSConstants.COLUMN_SOURCE_DISK, buf.readByte(),
                    "v18 keeps the source byte");
            assertArrayEquals(nativeGolden, buf.readByteArray(LSSConstants.MAX_SECTIONS_SIZE),
                    "the v18 frame's section array must be the TRANSLATED native body");
            assertEquals(0, buf.readableBytes(), "no codec byte anywhere in a v18 frame");
        } finally {
            buf.release();
        }
    }

    @Test
    void v16SpliceComposesOnTheTranslatedBody() {
        byte[] nativeBody = NbtSectionSerializer.fromV20(
                readCorpus("v20-corpus", "negative-y.bin"), REGISTRY_ACCESS);
        byte[] nativeGolden = readCorpus("nbt-corpus", "negative-y.bin");

        var buf = new FriendlyByteBuf(Unpooled.wrappedBuffer(
                encodePayload(payloadOf(nativeBody).asV16())));
        try {
            assertEquals(7, buf.readInt());
            assertEquals(-3, buf.readInt());
            assertEquals(Level.OVERWORLD.identifier().toString(),
                    buf.readUtf(LSSConstants.MAX_DIMENSION_STRING_LENGTH));
            assertEquals(1234567L, buf.readLong());
            assertArrayEquals(nativeGolden, buf.readByteArray(LSSConstants.MAX_SECTIONS_SIZE),
                    "the v16 frame drops source AND codec and carries the translated body");
            assertEquals(0, buf.readableBytes());
        } finally {
            buf.release();
        }
    }

    // ---- review MAJOR-1/2/3 pins ----

    @Test
    void dialectTrackerAttachIsWiredInTheServiceConstructor() throws IOException {
        // Source-regex pin (review MAJOR-1): the enqueue's dialect read is the single
        // decision selecting the translate branch, and an UNATTACHED tracker fails
        // toward shipping v20 dictionary bodies to legacy clients (the C1-1 CRITICAL
        // failure mode) while every tier stays green — the soak lever is the only
        // thing that would notice, and it is not in CI. Pin the attach call site.
        Path src = dev.vox.lss.testutil.SourcePaths.mainSource(
                "dev/vox/lss/networking/server/RequestProcessingService.java");
        String body = Files.readString(src);
        assertTrue(body.contains("offThreadProcessor.attachDialectTracker(this.dialects)"),
                "RequestProcessingService must attach its dialect tracker to the "
                        + "processor — without it every legacy session gets v20 bodies");
    }

    @Test
    void legacyBuildIsMemoizedPerColumnAcrossTheFanOut() {
        // Review MAJOR-2: a dedup fan-out must cost ONE translate per column, never one
        // per recipient — the memo rides the ColumnBytes holder every recipient shares.
        byte[] v20 = readCorpus("v20-corpus", "multi-section.bin");
        var bytes = dev.vox.lss.common.processing.ColumnBytes.ofRaw(null, v20);
        var computeCount = new java.util.concurrent.atomic.AtomicInteger();
        java.util.function.Supplier<dev.vox.lss.common.processing.LegacyColumnBuild> compute =
                () -> {
                    computeCount.incrementAndGet();
                    return FabricOffThreadProcessor.buildLegacyColumn(
                            v20, REGISTRY_ACCESS, false, null);
                };
        var first = bytes.legacyBuild(false, compute);
        var second = bytes.legacyBuild(false, compute);
        assertEquals(1, computeCount.get(), "the second recipient must reuse the memo");
        assertTrue(first == second, "same build instance across the fan-out");
    }

    @Test
    void overCapTranslationThrowsBeforeItCanKillTheLegacyConnection() {
        // Review MAJOR-3: the enqueue admission guard checks the V20 size, but native
        // can be LARGER — wide-palette sections repack from v20's ≤12-bit dictionary
        // indices to native DIRECT at ~15-16 registry bits. A body admitted under the
        // cap as v20 that translates over it would kill the legacy client at
        // readByteArray (RAW) or park it in a permanent ingest-failure loop (ZSTD), so
        // buildLegacyColumn must refuse loudly (→ the up_to_date containment).
        var identities = IdentityTables.blockIdentities();
        int distinct = 300; // > 256 → native goes DIRECT at the registry width
        var dict = new java.util.ArrayList<String>(distinct + 1);
        for (int i = 0; i < distinct; i++) {
            dict.add(identities[i]);
        }
        String biomeIdentity = NbtSectionSerializer.biomeIdentityLookup(REGISTRY_ACCESS).apply(0);
        dict.add(biomeIdentity);

        int[] palette = new int[distinct];
        int[] values = new int[4096];
        for (int i = 0; i < distinct; i++) {
            palette[i] = i;
        }
        for (int i = 0; i < 4096; i++) {
            values[i] = i % distinct;
        }
        int v20Bits = 9; // ceillog2(300); v20's dictionary-index width for this palette
        var blocks = new WireSectionCursor.WireContainer(v20Bits, palette,
                WireSectionCursor.pack(values, v20Bits));
        var biomes = new WireSectionCursor.WireContainer(0, new int[] {distinct}, new long[0]);
        var sections = new java.util.ArrayList<WireSectionCursor.WireSection>();
        int sectionCount = 220;
        for (int y = 0; y < sectionCount; y++) {
            sections.add(new WireSectionCursor.WireSection(y - 100, 4096, 0,
                    blocks, biomes, new byte[2048], null));
        }
        byte[] v20Body = WireSectionCursor.emit(
                new WireSectionCursor.WireColumn(dict, sections), WireSectionCursor.Layout.V20);
        assertTrue(v20Body.length <= LSSConstants.MAX_SEND_SECTIONS_SIZE,
                "premise: the v20 body passes the enqueue admission guard ("
                        + v20Body.length + " bytes) — otherwise this test is vacuous");

        var thrown = assertThrows(IllegalStateException.class,
                () -> FabricOffThreadProcessor.buildLegacyColumn(v20Body, REGISTRY_ACCESS, false, null));
        assertTrue(thrown.getMessage().contains("exceeds send limit"),
                "the refusal must name the cap, got: " + thrown.getMessage());
    }

    @Test
    void corpusDirectoriesStayInLockstep() throws IOException {
        // A v20-corpus fixture added without its native sibling would be silently
        // unchecked by the chain test (it iterates nbt-corpus names) — pin the dirs.
        try (Stream<Path> files = Files.list(corpusDir("v20-corpus"))) {
            var v20Names = files.map(p -> p.getFileName().toString())
                    .filter(n -> n.endsWith(".bin")).sorted().toList();
            assertEquals(corpusNames(), v20Names,
                    "nbt-corpus and v20-corpus must hold the same fixture set");
        }
    }

    // ---- the loud failure shape (contained at the enqueue as an up_to_date answer) ----

    @Test
    void unresolvableIdentityThrowsTheTranslatorsPinnedFailure() {
        byte[] v20 = readCorpus("v20-corpus", "multi-section.bin");
        assertThrows(WireFormatException.class,
                () -> dev.vox.lss.common.wire.V20ToNativeTranslator.translate(v20,
                        identity -> -1,
                        NbtSectionSerializer.biomeIdLookup(REGISTRY_ACCESS),
                        Block.BLOCK_STATE_REGISTRY.size(),
                        NbtSectionSerializer.biomeIdCount(REGISTRY_ACCESS)),
                "an identity missing from the server's own registry is a table bug and "
                        + "must fail loudly (a WireFormatException the enqueue containment "
                        + "resolves as up_to_date), never serve wrong blocks");
    }
}
