package dev.vox.lss.common.wire;

import dev.vox.lss.common.wire.WireSectionCursor.Layout;
import dev.vox.lss.common.wire.WireSectionCursor.WireColumn;
import dev.vox.lss.common.wire.WireSectionCursor.WireContainer;
import dev.vox.lss.common.wire.WireSectionCursor.WireSection;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;
import java.util.function.IntFunction;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The native → v20 byte-domain translation (§4.2 encode direction): indexed
 * containers keep bits + packed longs VERBATIM with palettes rewritten to
 * dictionary indices; DIRECT containers re-palettize (first-seen scan order) and
 * repack at the §2.1 v20 widths; count shorts and light pass through; the
 * dictionary is shared, first-seen, and validated.
 */
class NativeToV20TranslatorTest {

    private static final IntFunction<String> BLOCKS = id -> switch (id) {
        case 0 -> "minecraft:air";
        case 9 -> "minecraft:stone";
        case 130 -> "minecraft:oak_stairs[facing=north,half=top,waterlogged=false]";
        default -> "minecraft:generated_" + id;
    };
    private static final IntFunction<String> BIOMES = id -> switch (id) {
        case 2 -> "minecraft:plains";
        default -> "minecraft:biome_" + id;
    };

    private static byte[] nativeBody(WireSection... sections) {
        return WireSectionCursor.emit(new WireColumn(List.of(), List.of(sections)), Layout.NATIVE);
    }

    private static long[] packed(int[] values, int bits) {
        return WireSectionCursor.pack(values, bits);
    }

    @Test
    void indexedSectionsShipLongsVerbatimWithDictIndexPalettes() {
        var rng = new Random(7);
        var blockValues = new int[4096];
        for (int i = 0; i < 4096; i++) {
            blockValues[i] = rng.nextInt(3);
        }
        var blocks = new WireContainer(4, new int[] { 0, 9, 130 }, packed(blockValues, 4));
        var biomes = new WireContainer(0, new int[] { 2 }, new long[0]);
        byte[] v20 = NativeToV20Translator.translate(
                nativeBody(new WireSection(-4, 100, 5, blocks, biomes, null, null)),
                BLOCKS, BIOMES);

        var column = WireSectionCursor.parse(v20, Layout.V20);
        assertEquals(List.of("minecraft:air", "minecraft:stone",
                "minecraft:oak_stairs[facing=north,half=top,waterlogged=false]",
                "minecraft:plains"), column.dictionary());
        var s = column.sections().get(0);
        assertEquals(-4, s.sectionY());
        // Derived from the S1 descriptor (fold, not drop — review MAJOR-1): the native
        // SOURCE bytes carry the line fold in their single short on a 1-short line, so
        // the translation reads (fold, 0) there.
        assertEquals(NativeSectionShape.NATIVE_COUNT_SHORTS == 2
                        ? 100 : NativeSectionShape.foldedCountForNativeHeader(100, 5),
                s.nonEmptyBlockCount());
        assertEquals(NativeSectionShape.NATIVE_COUNT_SHORTS == 2 ? 5 : 0, s.fluidCount());
        assertEquals(4, s.blocks().bits());
        assertArrayEquals(new int[] { 0, 1, 2 }, s.blocks().palette());
        assertArrayEquals(blocks.data(), s.blocks().data(), "packed longs must ship verbatim");
        assertEquals(0, s.biomes().bits());
        assertArrayEquals(new int[] { 3 }, s.biomes().palette());
    }

    @Test
    void directBlocksArePalettizedInFirstSeenOrderAndRepacked() {
        // A DIRECT container at width 15 holding three distinct ids.
        var ids = new int[4096];
        for (int i = 0; i < 4096; i++) {
            ids[i] = (i % 3 == 0) ? 9 : (i % 3 == 1 ? 4242 : 130);
        }
        var direct = new WireContainer(15, null, packed(ids, 15));
        var biomes = new WireContainer(0, new int[] { 2 }, new long[0]);
        byte[] v20 = NativeToV20Translator.translate(
                nativeBody(new WireSection(2, 4096, 0, direct, biomes, null, null)),
                BLOCKS, BIOMES);

        var s = WireSectionCursor.parse(v20, Layout.V20).sections().get(0);
        // First-seen scan order: 9, 4242, 130 -> dict 0,1,2; blocks floor width 4.
        assertEquals(4, s.blocks().bits());
        assertArrayEquals(new int[] { 0, 1, 2 }, s.blocks().palette());
        int[] repacked = WireSectionCursor.unpack(s.blocks().data(), 4, 4096);
        for (int i = 0; i < 4096; i++) {
            int expected = (i % 3 == 0) ? 0 : (i % 3 == 1 ? 1 : 2);
            assertEquals(expected, repacked[i], "voxel " + i + " must be preserved");
        }
        assertEquals(List.of("minecraft:stone", "minecraft:generated_4242",
                "minecraft:oak_stairs[facing=north,half=top,waterlogged=false]",
                "minecraft:plains"),
                WireSectionCursor.parse(v20, Layout.V20).dictionary());
    }

    @Test
    void directBiomesRepackAtCeillog2IncludingTheSingleValueCollapse() {
        var biomeIds = new int[64];
        for (int i = 0; i < 64; i++) {
            biomeIds[i] = (i < 32) ? 2 : 13;  // both fit the 4-bit DIRECT width
        }
        var directBiomes = new WireContainer(4, null, packed(biomeIds, 4));
        var single = new WireContainer(0, new int[] { 9 }, new long[0]);
        byte[] v20 = NativeToV20Translator.translate(
                nativeBody(new WireSection(0, 1, 0, single, directBiomes, null, null)),
                BLOCKS, BIOMES);
        var s = WireSectionCursor.parse(v20, Layout.V20).sections().get(0);
        assertEquals(1, s.biomes().bits(), "two entries -> ceillog2(2) = 1 (no block floor)");
        int[] repacked = WireSectionCursor.unpack(s.biomes().data(), 1, 64);
        for (int i = 0; i < 64; i++) {
            assertEquals(i < 32 ? 0 : 1, repacked[i]);
        }

        // All-one-id DIRECT collapses to a single-value container (bits 0, no data).
        var uniform = new WireContainer(4, null, packed(new int[64], 4)); // all id 0
        byte[] v20b = NativeToV20Translator.translate(
                nativeBody(new WireSection(0, 1, 0, single, uniform, null, null)),
                BLOCKS, id -> "minecraft:the_void");
        var sb = WireSectionCursor.parse(v20b, Layout.V20).sections().get(0);
        assertEquals(0, sb.biomes().bits());
        assertEquals(0, sb.biomes().data().length);
    }

    @Test
    void dictionaryIsSharedAcrossSectionsAndKindsFirstSeen() {
        var stoneSingle = new WireContainer(0, new int[] { 9 }, new long[0]);
        var plainsSingle = new WireContainer(0, new int[] { 2 }, new long[0]);
        var s1 = new WireSection(-4, 1, 0, stoneSingle, plainsSingle, null, null);
        var s2 = new WireSection(-3, 1, 0, stoneSingle, plainsSingle, null, null);
        byte[] v20 = NativeToV20Translator.translate(nativeBody(s1, s2), BLOCKS, BIOMES);
        var column = WireSectionCursor.parse(v20, Layout.V20);
        // stone first (section 1 blocks), plains second (section 1 biomes); section 2
        // adds nothing — repeats resolve to the same indices.
        assertEquals(List.of("minecraft:stone", "minecraft:plains"), column.dictionary());
        assertArrayEquals(new int[] { 0 }, column.sections().get(1).blocks().palette());
        assertArrayEquals(new int[] { 1 }, column.sections().get(1).biomes().palette());
    }

    @Test
    void clearColumnTranslatesToTheEmptyDictionaryClearFrame() {
        byte[] v20 = NativeToV20Translator.translate(nativeBody(), BLOCKS, BIOMES);
        assertArrayEquals(new byte[] { 0, 0 }, v20);
    }

    @Test
    void lightLayersAndCountShortsPassThroughVerbatim() {
        var blLight = new byte[2048];
        var skyLight = new byte[2048];
        new Random(3).nextBytes(blLight);
        new Random(4).nextBytes(skyLight);
        var single = new WireContainer(0, new int[] { 9 }, new long[0]);
        var biome = new WireContainer(0, new int[] { 2 }, new long[0]);
        byte[] v20 = NativeToV20Translator.translate(
                nativeBody(new WireSection(5, 1234, 77, single, biome, blLight, skyLight)),
                BLOCKS, BIOMES);
        var s = WireSectionCursor.parse(v20, Layout.V20).sections().get(0);
        assertEquals(NativeSectionShape.NATIVE_COUNT_SHORTS == 2
                        ? 1234 : NativeSectionShape.foldedCountForNativeHeader(1234, 77),
                s.nonEmptyBlockCount());
        assertEquals(NativeSectionShape.NATIVE_COUNT_SHORTS == 2 ? 77 : 0, s.fluidCount());
        assertArrayEquals(blLight, s.blockLight());
        assertArrayEquals(skyLight, s.skyLight());
    }

    @Test
    void missingOrInvalidIdentitiesFailLoudly() {
        var single = new WireContainer(0, new int[] { 9 }, new long[0]);
        var biome = new WireContainer(0, new int[] { 2 }, new long[0]);
        byte[] body = nativeBody(new WireSection(0, 1, 0, single, biome, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> NativeToV20Translator.translate(body, id -> null, BIOMES));
        assertThrows(IllegalArgumentException.class,
                () -> NativeToV20Translator.translate(body, id -> "Not Canonical", BIOMES));
    }

    @Test
    void v20BitsMatchesTheSpecTable() {
        assertEquals(0, NativeToV20Translator.v20Bits(1, true));
        assertEquals(4, NativeToV20Translator.v20Bits(2, true));
        assertEquals(4, NativeToV20Translator.v20Bits(16, true));
        assertEquals(5, NativeToV20Translator.v20Bits(17, true));
        assertEquals(8, NativeToV20Translator.v20Bits(256, true));
        assertEquals(9, NativeToV20Translator.v20Bits(257, true));
        assertEquals(12, NativeToV20Translator.v20Bits(4096, true));
        assertEquals(0, NativeToV20Translator.v20Bits(1, false));
        assertEquals(1, NativeToV20Translator.v20Bits(2, false));
        assertEquals(3, NativeToV20Translator.v20Bits(8, false));
        assertEquals(6, NativeToV20Translator.v20Bits(64, false));
    }

    // ---- §9 bijection fuzz: toNative(toV20(x)) == x byte-exact on one registry ----
    //
    // The C2 corpus walk (LegacyColumnEgressTest / WireSectionCursorCorpusTest) proves
    // the chain on 14 FROZEN goldens; this is the breadth complement — seeded-random
    // synthetic bodies across every packing mode, incl. the DIRECT tiers the real
    // emitter only produces on exotic terrain. One synthetic registry plays both
    // sides (encode lookup and decode inverse), so any byte drift is the translators'
    // own, never a registry artifact.

    /** Encode-side identity tables for the fuzz registry (id -> unique identity). */
    private static final String FUZZ_BLOCK_PREFIX = "syntheticblocks:state_";
    private static final String FUZZ_BIOME_PREFIX = "syntheticbiomes:biome_";
    /** ceillog2(39999) = 16: the DIRECT width vanilla would derive for a ~40k-state
     *  block registry — the decode side must re-derive the same width or the DIRECT
     *  round trip can never be byte-exact. */
    private static final int FUZZ_BLOCK_REGISTRY_SIZE = 40_000;
    /** ceillog2(95) = 7: a biome registry big enough that DIRECT biomes exist. */
    private static final int FUZZ_BIOME_REGISTRY_SIZE = 96;

    private static final IntFunction<String> FUZZ_BLOCKS = id -> FUZZ_BLOCK_PREFIX + id;
    private static final IntFunction<String> FUZZ_BIOMES = id -> FUZZ_BIOME_PREFIX + id;

    private static int fuzzInverse(String identity, String prefix, int registrySize) {
        if (!identity.startsWith(prefix)) {
            throw new AssertionError("foreign identity in fuzz dictionary: " + identity);
        }
        int id = Integer.parseInt(identity.substring(prefix.length()));
        if (id < 0 || id >= registrySize) {
            throw new AssertionError("fuzz id " + id + " outside the synthetic registry");
        }
        return id;
    }

    private static byte[] fuzzRoundTrip(byte[] nativeIn) {
        byte[] v20 = NativeToV20Translator.translate(nativeIn, FUZZ_BLOCKS, FUZZ_BIOMES);
        return V20ToNativeTranslator.translate(v20,
                identity -> fuzzInverse(identity, FUZZ_BLOCK_PREFIX, FUZZ_BLOCK_REGISTRY_SIZE),
                identity -> fuzzInverse(identity, FUZZ_BIOME_PREFIX, FUZZ_BIOME_REGISTRY_SIZE),
                FUZZ_BLOCK_REGISTRY_SIZE, FUZZ_BIOME_REGISTRY_SIZE);
    }

    /** {@code n} DISTINCT ids sampled from {@code [0, bound)} — duplicate palette
     *  entries are the canonicalizing case and get their own carve-out fuzz below. */
    private static int[] distinctIds(Random rng, int n, int bound) {
        var seen = new java.util.LinkedHashSet<Integer>();
        while (seen.size() < n) {
            seen.add(rng.nextInt(bound));
        }
        int[] out = new int[n];
        int i = 0;
        for (int id : seen) {
            out[i++] = id;
        }
        return out;
    }

    /**
     * The §9 bijection fuzz: ~200 seeded-random native columns spanning every packing
     * mode of both container types — single-value, indexed at every legal width
     * (blocks 4-8, biomes 1-3), and DIRECT synthesis (blocks &gt;256 distinct ids at
     * the 16-bit registry width, biomes &gt;8 at 7 bits) — must survive
     * {@code toNative(toV20(x)) == x} BYTE-exact. Byte-exactness (not content
     * equality) is the load-bearing strength: the store migration walk pins that a
     * migrated row byte-equals a fresh encode, and the C2 egress charges bandwidth on
     * the translated bytes, so ANY canonicalization drift on well-formed vanilla-shaped
     * input is a real-world byte mismatch, not a cosmetic one. Duplicate palette
     * entries are deliberately EXCLUDED here — the collapse makes them canonicalize
     * by design (the {@code duplicate-air.bin} carve-out), pinned content-identical
     * in the sibling fuzz below.
     */
    @Test
    void roundTripIsByteExactAcrossAllPackingModesUnderSeededFuzz() {
        var rng = new Random(0x20260808L);
        int blockSingles = 0, blockIndexed = 0, blockDirect = 0;
        int biomeSingles = 0, biomeIndexed = 0, biomeDirect = 0;
        for (int round = 0; round < 200; round++) {
            int sectionCount = 1 + rng.nextInt(3);
            var sections = new java.util.ArrayList<WireSection>(sectionCount);
            for (int s = 0; s < sectionCount; s++) {
                WireContainer blocks;
                switch (rng.nextInt(3)) {
                    case 0 -> {
                        blocks = new WireContainer(0,
                                new int[] { rng.nextInt(FUZZ_BLOCK_REGISTRY_SIZE) }, new long[0]);
                        blockSingles++;
                    }
                    case 1 -> {
                        // Indexed at vanilla's exact width for the palette size — the
                        // only shape the real emitter produces, and the only one the
                        // decode side ships verbatim (an overwide palette re-emits at
                        // the derived width and legitimately canonicalizes).
                        int n = 2 + rng.nextInt(255); // [2, 256]
                        int[] palette = distinctIds(rng, n, FUZZ_BLOCK_REGISTRY_SIZE);
                        int bits = Math.max(4, 32 - Integer.numberOfLeadingZeros(n - 1));
                        int[] values = new int[4096];
                        for (int i = 0; i < 4096; i++) {
                            values[i] = rng.nextInt(n);
                        }
                        blocks = new WireContainer(bits, palette, packed(values, bits));
                        blockIndexed++;
                    }
                    default -> {
                        // DIRECT synthesis: >256 distinct ids, every one present in the
                        // data (the v20 palette is derived FROM the longs, so an absent
                        // id simply would not exist to round-trip).
                        int n = 257 + rng.nextInt(300);
                        int[] ids = distinctIds(rng, n, FUZZ_BLOCK_REGISTRY_SIZE);
                        int[] data = new int[4096];
                        for (int i = 0; i < 4096; i++) {
                            data[i] = i < n ? ids[i] : ids[rng.nextInt(n)];
                        }
                        blocks = new WireContainer(16, null, packed(data, 16));
                        blockDirect++;
                    }
                }
                WireContainer biomes;
                switch (rng.nextInt(3)) {
                    case 0 -> {
                        biomes = new WireContainer(0,
                                new int[] { rng.nextInt(FUZZ_BIOME_REGISTRY_SIZE) }, new long[0]);
                        biomeSingles++;
                    }
                    case 1 -> {
                        int n = 2 + rng.nextInt(7); // [2, 8] -> bits 1..3
                        int[] palette = distinctIds(rng, n, FUZZ_BIOME_REGISTRY_SIZE);
                        int bits = 32 - Integer.numberOfLeadingZeros(n - 1);
                        int[] values = new int[64];
                        for (int i = 0; i < 64; i++) {
                            values[i] = rng.nextInt(n);
                        }
                        biomes = new WireContainer(bits, palette, packed(values, bits));
                        biomeIndexed++;
                    }
                    default -> {
                        int n = 9 + rng.nextInt(56); // [9, 64] distinct -> above the 3-bit threshold
                        int[] ids = distinctIds(rng, n, FUZZ_BIOME_REGISTRY_SIZE);
                        int[] data = new int[64];
                        for (int i = 0; i < 64; i++) {
                            data[i] = i < n ? ids[i] : ids[rng.nextInt(n)];
                        }
                        biomes = new WireContainer(7, null, packed(data, 7));
                        biomeDirect++;
                    }
                }
                byte[] blockLight = rng.nextBoolean() ? new byte[2048] : null;
                byte[] skyLight = rng.nextBoolean() ? new byte[2048] : null;
                if (blockLight != null) {
                    rng.nextBytes(blockLight);
                }
                if (skyLight != null) {
                    rng.nextBytes(skyLight);
                }
                sections.add(new WireSection(rng.nextInt(128) - 64, rng.nextInt(4097),
                        rng.nextInt(4097), blocks, biomes, blockLight, skyLight));
            }
            byte[] nativeIn = WireSectionCursor.emit(
                    new WireColumn(List.of(), sections), Layout.NATIVE);
            assertArrayEquals(nativeIn, fuzzRoundTrip(nativeIn),
                    "round " + round + " must round-trip byte-exact");
        }
        // The fuzz only has teeth while it visits every mode — a generator regression
        // that stopped producing a tier would pass vacuously without these.
        assertTrue(blockSingles > 0 && blockIndexed > 0 && blockDirect > 0,
                "block coverage: " + blockSingles + "/" + blockIndexed + "/" + blockDirect);
        assertTrue(biomeSingles > 0 && biomeIndexed > 0 && biomeDirect > 0,
                "biome coverage: " + biomeSingles + "/" + biomeIndexed + "/" + biomeDirect);
    }

    /**
     * The collapse carve-out, fuzzed: palettes WITH duplicate entries canonicalize
     * (the dictionary is identity-keyed, so duplicates merge on the way to v20 and
     * the way back emits the collapsed minimal palette) — byte-exactness is
     * structurally impossible and content identity is the contract, exactly as
     * {@code LegacyColumnEgressTest} pins for the frozen {@code duplicate-air.bin}
     * fixture. Every round asserts the divergence PREMISE too: if a duplicate-palette
     * shape ever starts round-tripping byte-equal, the carve-out is stale and the
     * shape belongs in the byte-exact fuzz above.
     */
    @Test
    void duplicatePaletteEntriesCanonicalizeToContentIdenticalBytesUnderSeededFuzz() {
        var rng = new Random(0xD0BB1E5L);
        for (int round = 0; round < 40; round++) {
            int distinct = 2 + rng.nextInt(6);
            int paletteLen = distinct + 1 + rng.nextInt(8); // always at least one duplicate
            int[] ids = distinctIds(rng, distinct, FUZZ_BLOCK_REGISTRY_SIZE);
            int[] palette = new int[paletteLen];
            for (int i = 0; i < paletteLen; i++) {
                palette[i] = i < distinct ? ids[i] : ids[rng.nextInt(distinct)];
            }
            int bits = Math.max(4, 32 - Integer.numberOfLeadingZeros(paletteLen - 1));
            int[] values = new int[4096];
            for (int i = 0; i < 4096; i++) {
                values[i] = rng.nextInt(paletteLen);
            }
            var blocks = new WireContainer(bits, palette, packed(values, bits));
            var biomes = new WireContainer(0, new int[] { rng.nextInt(FUZZ_BIOME_REGISTRY_SIZE) },
                    new long[0]);
            byte[] nativeIn = nativeBody(new WireSection(0, 1, 0, blocks, biomes, null, null));

            byte[] back = fuzzRoundTrip(nativeIn);
            assertFalse(java.util.Arrays.equals(nativeIn, back),
                    "round " + round + ": the collapse must actually diverge — byte-equal "
                            + "means the carve-out is stale");

            var in = WireSectionCursor.parse(nativeIn, Layout.NATIVE).sections().get(0);
            var out = WireSectionCursor.parse(back, Layout.NATIVE).sections().get(0);
            assertArrayEquals(resolvedIds(in.blocks()), resolvedIds(out.blocks()),
                    "round " + round + ": per-voxel ids must survive the collapse");
            // A collapse only ever SHRINKS (the LegacyColumnEgressTest m8 guard): a
            // wider re-emit would be byte drift this carve-out must not mask.
            assertTrue(out.blocks().bits() <= in.blocks().bits(),
                    "round " + round + ": collapsed width must not exceed the original");
        }
    }

    /** Per-voxel resolved ids of a non-DIRECT block container (the collapse fuzz never
     *  emits DIRECT: its distinct counts stay far below the 256 threshold). */
    private static int[] resolvedIds(WireContainer c) {
        if (c.bits() == 0) {
            int[] out = new int[4096];
            java.util.Arrays.fill(out, c.palette()[0]);
            return out;
        }
        int[] values = WireSectionCursor.unpack(c.data(), c.bits(), 4096);
        int[] out = new int[4096];
        for (int i = 0; i < 4096; i++) {
            out[i] = c.palette()[values[i]];
        }
        return out;
    }

    /** Fuzz: random native columns translate to parseable v20 whose voxel content matches. */
    @Test
    void translationPreservesEveryVoxelUnderFuzz() {
        var rng = new Random(20260807);
        for (int round = 0; round < 40; round++) {
            int paletteSize = 1 + rng.nextInt(20);
            var palette = new int[paletteSize];
            for (int i = 0; i < paletteSize; i++) {
                palette[i] = rng.nextInt(30000);
            }
            var values = new int[4096];
            for (int i = 0; i < 4096; i++) {
                values[i] = rng.nextInt(paletteSize);
            }
            WireContainer blocks;
            boolean direct = rng.nextBoolean();
            if (direct) {
                var ids = new int[4096];
                for (int i = 0; i < 4096; i++) {
                    ids[i] = palette[values[i]];
                }
                blocks = new WireContainer(15, null, packed(ids, 15));
            } else {
                int bits = Math.max(4, 32 - Integer.numberOfLeadingZeros(paletteSize - 1));
                blocks = paletteSize == 1
                        ? new WireContainer(0, palette, new long[0])
                        : new WireContainer(bits, palette, packed(values, bits));
            }
            var biomes = new WireContainer(0, new int[] { rng.nextInt(60) }, new long[0]);
            byte[] v20 = NativeToV20Translator.translate(
                    nativeBody(new WireSection(0, 1, 0, blocks, biomes, null, null)),
                    BLOCKS, BIOMES);
            var s = WireSectionCursor.parse(v20, Layout.V20).sections().get(0);
            var dict = WireSectionCursor.parse(v20, Layout.V20).dictionary();
            assertNotNull(s.blocks().palette());
            int[] decodedIdx = s.blocks().bits() == 0
                    ? null
                    : WireSectionCursor.unpack(s.blocks().data(), s.blocks().bits(), 4096);
            for (int i = 0; i < 4096; i++) {
                String expected = BLOCKS.apply(palette[values[i]]);
                int dictIndex = decodedIdx == null
                        ? s.blocks().palette()[0]
                        : s.blocks().palette()[decodedIdx[i]];
                assertEquals(expected, dict.get(dictIndex), "round " + round + " voxel " + i);
            }
            assertTrue(s.blocks().bits() == 0 || s.blocks().bits() >= 4);
        }
    }

    @Test
    void convertIndexedByteMatchesTranslateAcrossIndexedShapes() {
        // The direct-emit rung's identity pin (review finding 4): for every indexed
        // shape the NBT transcoder can produce — single (bits 0), 4-bit linear with a
        // DUPLICATE palette entry (the air-substitution shape), the 8-bit block /
        // 3-bit biome boundary one short of the fallback — building the column via
        // convertIndexed must byte-equal translate() on the equivalent native body.
        var rng = new Random(11);
        int[] fourBit = new int[4096];
        for (int i = 0; i < 4096; i++) fourBit[i] = rng.nextInt(4);
        int[] eightBit = new int[4096];
        for (int i = 0; i < 4096; i++) eightBit[i] = rng.nextInt(256);
        int[] biome2 = new int[64];
        for (int i = 0; i < 64; i++) biome2[i] = rng.nextInt(3);
        int[] biome3 = new int[64];
        for (int i = 0; i < 64; i++) biome3[i] = rng.nextInt(8);
        int[] pal256 = new int[256];
        for (int i = 0; i < 256; i++) pal256[i] = i;
        int[] biomePal8 = new int[8];
        for (int i = 0; i < 8; i++) biomePal8[i] = i;
        byte[] blockLight = new byte[2048];
        blockLight[7] = 0x5A;

        var shapes = List.of(
                new WireSection(-4, 100, 5,
                        new WireContainer(0, new int[]{9}, new long[0]),
                        new WireContainer(0, new int[]{2}, new long[0]), null, null),
                new WireSection(0, 42, 0,
                        new WireContainer(4, new int[]{0, 9, 0, 130}, packed(fourBit, 4)),
                        new WireContainer(2, new int[]{2, 5, 7}, packed(biome2, 2)),
                        blockLight, null),
                new WireSection(7, 4096, 12,
                        new WireContainer(8, pal256, packed(eightBit, 8)),
                        new WireContainer(3, biomePal8, packed(biome3, 3)), null, null));

        byte[] viaTranslate = NativeToV20Translator.translate(
                nativeBody(shapes.toArray(new WireSection[0])), BLOCKS, BIOMES);

        var dict = new IdentityDictionary();
        var sections = new java.util.ArrayList<WireSection>();
        for (var s : shapes) {
            sections.add(new WireSection(s.sectionY(), s.nonEmptyBlockCount(), s.fluidCount(),
                    NativeToV20Translator.convertIndexed(s.blocks().bits(), s.blocks().palette(),
                            s.blocks().data(), true, dict, BLOCKS),
                    NativeToV20Translator.convertIndexed(s.biomes().bits(), s.biomes().palette(),
                            s.biomes().data(), false, dict, BIOMES),
                    s.blockLight(), s.skyLight()));
        }
        byte[] direct = WireSectionCursor.emit(
                new WireColumn(dict.entries(), sections), Layout.V20);
        assertArrayEquals(viaTranslate, direct,
                "convertIndexed must be translate()'s indexed branch, byte for byte");
    }

    @Test
    void convertIndexedRejectsWidthsBeyondTheNativeIndexedThresholds() {
        // Beyond the native indexed thresholds, parse would classify the same bytes
        // DIRECT and the two routes diverge — the width guard makes the transcoder's
        // fallback-gate coupling enforced instead of assumed (review finding 3).
        var dict = new IdentityDictionary();
        assertThrows(IllegalArgumentException.class, () ->
                NativeToV20Translator.convertIndexed(9, new int[512], new long[576], true,
                        dict, BLOCKS));
        assertThrows(IllegalArgumentException.class, () ->
                NativeToV20Translator.convertIndexed(4, new int[16], new long[32], false,
                        dict, BIOMES));
    }
}
