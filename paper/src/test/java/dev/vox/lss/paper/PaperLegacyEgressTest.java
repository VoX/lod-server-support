package dev.vox.lss.paper;

import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.common.store.StoreCodec;
import dev.vox.lss.common.wire.WireFormatException;
import dev.vox.lss.common.wire.WireSectionCursor;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.server.Bootstrap;
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
 * Paper twin of the Fabric {@code LegacyColumnEgressTest}: the C2 legacy egress body
 * translation ({@code PaperOffThreadProcessor.buildLegacyColumn} over
 * {@code PaperNbtSectionSerializer.fromV20} — XVER plan §4.2/§9) at the per-recipient
 * ENQUEUE choke point. The corpus-driven translation chain (fromV20(v20 golden) must
 * byte-equal the frozen native golden through the production registry tables, one
 * deliberate palette-collapse carve-out), the codec choice (v19 recompress
 * shrink-gated), splice composition over translated frames, and the loud failure
 * shape the enqueue containment catches.
 */
class PaperLegacyEgressTest {

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

    static Path corpusDir(String dirName) {
        Path dir = Path.of("").toAbsolutePath();
        for (int depth = 0; depth < 5 && dir != null; depth++, dir = dir.getParent()) {
            if (Files.isDirectory(dir.resolve("src/test/java/dev/vox/lss"))) {
                return dir.resolve("src/test/resources").resolve(dirName);
            }
            Path nested = dir.resolve("paper");
            if (Files.isDirectory(nested.resolve("src/test/java/dev/vox/lss"))) {
                return nested.resolve("src/test/resources").resolve(dirName);
            }
        }
        throw new IllegalStateException("cannot locate the paper module source tree from "
                + Path.of("").toAbsolutePath());
    }

    static byte[] readCorpus(String dirName, String name) {
        try {
            // Line-aware override-resolution (single-branch consolidation): the active line's
            // overlay golden wins (e.g. 26.1's xray-masked.bin); others fall back to shared.
            return Files.readAllBytes(dev.vox.lss.paper.LineGoldens.resolve(dirName, name));
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

    /** Same deliberate divergence as the Fabric twin: the fixture's duplicate palette
     *  entries collapse through the identity dictionary; content pinned below. */
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
                    PaperNbtSectionSerializer.fromV20(v20Golden, REGISTRY_ACCESS),
                    "fromV20 must reproduce the frozen native bytes through the "
                            + "production registry tables for " + name);
        }
    }

    @Test
    void duplicatePaletteFixtureCollapsesToContentIdenticalBytes() {
        byte[] nativeGolden = readCorpus("nbt-corpus", COLLAPSED_FIXTURE);
        byte[] translated = PaperNbtSectionSerializer.fromV20(
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

    private static int[] resolvedValues(WireSectionCursor.WireContainer c, int entries) {
        if (c.bits() == 0) {
            int[] out = new int[entries];
            java.util.Arrays.fill(out, c.palette()[0]);
            return out;
        }
        int[] values = WireSectionCursor.unpack(c.data(), c.bits(), entries);
        if (c.palette() == null) {
            return values;
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

        var build = PaperOffThreadProcessor.buildLegacyColumn(v20, REGISTRY_ACCESS, false, null);

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

        var build = PaperOffThreadProcessor.buildLegacyColumn(v20, REGISTRY_ACCESS, true, zstd);

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
        var build = PaperOffThreadProcessor.buildLegacyColumn(
                new byte[] {0, 0}, REGISTRY_ACCESS, true, zstd);
        assertEquals(LSSConstants.COLUMN_CODEC_RAW, build.codecTag());
        assertArrayEquals(new byte[] {0}, build.shipped(),
                "the ghost-clear column must translate to the native single-byte clear");
        assertEquals(1, build.rawSize());
    }

    // ---- splice composition over translated frames ----

    @Test
    void v18AndV16SplicesOverTheTranslatedFrameEqualTheNativeBuiltRewrites() {
        // Field-by-field decomposition mirroring the Fabric twin (pre-D3 review L3-3:
        // the old form compared rewrite(nativeFrame) to rewrite(translatedFrame), but
        // the two input frames are byte-identical by the chain pin above — f(x)==f(x),
        // which any deterministic rewrite satisfies, wrong-offset splices included).
        byte[] nativeBody = PaperNbtSectionSerializer.fromV20(
                readCorpus("v20-corpus", "waterlogged.bin"), REGISTRY_ACCESS);
        byte[] nativeGolden = readCorpus("nbt-corpus", "waterlogged.bin");
        byte[] translatedFrame = PaperPayloadHandler.encodeVoxelColumnPreEncoded(7, -3,
                "minecraft:overworld", 1234567L, LSSConstants.COLUMN_SOURCE_DISK,
                LSSConstants.COLUMN_CODEC_RAW, nativeBody);

        var v18 = new FriendlyByteBuf(Unpooled.wrappedBuffer(
                PaperPayloadHandler.rewriteColumnToV18(translatedFrame)));
        try {
            assertEquals(7, v18.readInt());
            assertEquals(-3, v18.readInt());
            assertEquals("minecraft:overworld",
                    v18.readUtf(LSSConstants.MAX_DIMENSION_STRING_LENGTH));
            assertEquals(1234567L, v18.readLong());
            assertEquals(LSSConstants.COLUMN_SOURCE_DISK, v18.readByte(),
                    "v18 keeps the source byte");
            assertArrayEquals(nativeGolden, v18.readByteArray(LSSConstants.MAX_SECTIONS_SIZE),
                    "the v18 frame's section array must be the TRANSLATED native body");
            assertEquals(0, v18.readableBytes(), "no codec byte anywhere in a v18 frame");
        } finally {
            v18.release();
        }

        var v16 = new FriendlyByteBuf(Unpooled.wrappedBuffer(
                PaperPayloadHandler.rewriteColumnToV16(translatedFrame)));
        try {
            assertEquals(7, v16.readInt());
            assertEquals(-3, v16.readInt());
            assertEquals("minecraft:overworld",
                    v16.readUtf(LSSConstants.MAX_DIMENSION_STRING_LENGTH));
            assertEquals(1234567L, v16.readLong());
            assertArrayEquals(nativeGolden, v16.readByteArray(LSSConstants.MAX_SECTIONS_SIZE),
                    "the v16 frame drops source AND codec and carries the translated body");
            assertEquals(0, v16.readableBytes());
        } finally {
            v16.release();
        }
    }

    // ---- review MAJOR-1/2/3 pins (Fabric twins) ----

    @Test
    void dialectTrackerAttachIsWiredInTheServiceConstructor() throws IOException {
        // Source-regex pin (review MAJOR-1): an UNATTACHED tracker fails toward
        // shipping v20 dictionary bodies to legacy clients while every tier stays
        // green. The REAL enqueue path is additionally driven end-to-end by
        // PaperRequestProcessingServiceTest.enqueueTranslatesLegacySessionBodies...
        Path src = corpusDir("..").normalize().getParent()
                .resolve("main/java/dev/vox/lss/paper/PaperRequestProcessingService.java");
        String body = Files.readString(src);
        assertTrue(body.contains("offThreadProcessor.attachDialectTracker(this.dialects)"),
                "PaperRequestProcessingService must attach its dialect tracker to the "
                        + "processor — without it every legacy session gets v20 bodies");
    }

    @Test
    void overCapTranslationThrowsBeforeItCanKillTheLegacyConnection() {
        // Review MAJOR-3 (Fabric twin): a v20 body under the admission cap can
        // translate LARGER (wide palettes repack from <=12-bit dictionary indices to
        // native DIRECT at the ~15-16 bit registry width); buildLegacyColumn must
        // refuse loudly instead of shipping a connection-killing frame.
        var identities = PaperIdentityTables.blockIdentities();
        int distinct = 300;
        var dict = new java.util.ArrayList<String>(distinct + 1);
        for (int i = 0; i < distinct; i++) {
            dict.add(identities[i]);
        }
        dict.add(PaperNbtSectionSerializer.biomeIdentityLookup(REGISTRY_ACCESS).apply(0));

        int[] palette = new int[distinct];
        int[] values = new int[4096];
        for (int i = 0; i < distinct; i++) {
            palette[i] = i;
        }
        for (int i = 0; i < 4096; i++) {
            values[i] = i % distinct;
        }
        int v20Bits = 9;
        var blocks = new WireSectionCursor.WireContainer(v20Bits, palette,
                WireSectionCursor.pack(values, v20Bits));
        var biomes = new WireSectionCursor.WireContainer(0, new int[] {distinct}, new long[0]);
        var sections = new java.util.ArrayList<WireSectionCursor.WireSection>();
        for (int y = 0; y < 220; y++) {
            sections.add(new WireSectionCursor.WireSection(y - 100, 4096, 0,
                    blocks, biomes, new byte[2048], null));
        }
        byte[] v20Body = WireSectionCursor.emit(
                new WireSectionCursor.WireColumn(dict, sections), WireSectionCursor.Layout.V20);
        assertTrue(v20Body.length <= LSSConstants.MAX_SEND_SECTIONS_SIZE,
                "premise: the v20 body passes the enqueue admission guard ("
                        + v20Body.length + " bytes) — otherwise this test is vacuous");

        var thrown = assertThrows(IllegalStateException.class,
                () -> PaperOffThreadProcessor.buildLegacyColumn(v20Body, REGISTRY_ACCESS, false, null));
        assertTrue(thrown.getMessage().contains("exceeds send limit"),
                "the refusal must name the cap, got: " + thrown.getMessage());
    }

    @Test
    void corpusDirectoriesStayInLockstep() throws IOException {
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
                        PaperNbtSectionSerializer.biomeIdLookup(REGISTRY_ACCESS),
                        Block.BLOCK_STATE_REGISTRY.size(),
                        PaperNbtSectionSerializer.biomeIdCount(REGISTRY_ACCESS)),
                "an identity missing from the server's own registry is a table bug and "
                        + "must fail loudly (a WireFormatException the enqueue containment "
                        + "resolves as up_to_date), never serve wrong blocks");
    }
}
