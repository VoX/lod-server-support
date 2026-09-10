package dev.vox.lss.common.wire;

import dev.vox.lss.common.wire.WireSectionCursor.Layout;
import dev.vox.lss.common.wire.WireSectionCursor.WireColumn;
import dev.vox.lss.common.wire.WireSectionCursor.WireContainer;
import dev.vox.lss.common.wire.WireSectionCursor.WireSection;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntFunction;
import java.util.function.ToIntFunction;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The cross-MC-version proof without two MC versions in one JVM (XVER plan §9,
 * "Cross-registry simulation"): protocol 20's entire claim is that a column encoded
 * against one registry decodes correctly against a DIFFERENT registry, because the
 * wire keys on canonical identities and only the injected lookup seams know ids.
 * No CI environment can load two Minecraft versions, so the proof runs on SYNTHETIC
 * registry pairs: encode a native body under registry A ({@link NativeToV20Translator}
 * with A's id→identity lookups), decode under registry B ({@link V20ToNativeTranslator}
 * with B's identity→id inverses), and compare voxel content BY IDENTITY.
 *
 * <p>The three registry-drift shapes a real version bump produces are each pinned:
 * <ul>
 * <li><b>Permutation</b> — same identities, shuffled ids. The A3-style
 *     permutation-blindness pin ({@code RegistryFingerprintTest}'s lesson applied to
 *     the wire): id-keyed reasoning passes a same-size permutation by accident, so the
 *     assert maps every decoded id back through B's table and demands the identity at
 *     every voxel, never comparing ids.</li>
 * <li><b>Missing entries</b> — B lacks identities A served. The translator's pinned
 *     contract is that it has NO fallback policy of its own: the injected resolver
 *     must always return a valid id (the client injects the §3 ladder), and a negative
 *     return is a loud {@link WireFormatException}. So the fallback pin drives a
 *     recording ladder-style resolver and demands it fired EXACTLY on the missing set
 *     — an extra firing means known identities leak into the fallback path, a missed
 *     one means silent wrong-block substitution inside the translator.</li>
 * <li><b>Added entries</b> — B gained identities (ids shift wholesale). Decode must
 *     be byte-DIFFERENT (the ids really moved) yet identity-identical.</li>
 * </ul>
 * plus <b>duplicate-resolve tolerance</b>: two dictionary identities resolving to ONE
 * local id (the §4.2 palette-collapse case — impossible same-version, routine through
 * curated fallbacks) must decode without error, with both identities' voxels carrying
 * that one id and the emitted palette collapsed to distinct entries.
 */
class CrossRegistrySimulationTest {

    // ---- synthetic registries -------------------------------------------------

    /** Registry A: position IS the id. Property-carrying identities included so the
     *  identity round trip is exercised on the full canonical grammar, not just bare
     *  names. */
    private static final String[] BLOCKS_A = {
            "xver:air",                                       // 0
            "xver:stone",                                     // 1
            "xver:oak_stairs[facing=north,half=top]",         // 2
            "xver:deepslate",                                 // 3
            "xver:copper_grate[waterlogged=true]",            // 4
            "xver:tuff",                                      // 5
    };
    private static final String[] BIOMES_A = {
            "xver:plains", "xver:desert", "xver:river", "xver:grove",
    };

    /** Reversing a table is a full derangement of even-length arrays: every identity
     *  changes id, so nothing can pass by id-coincidence. */
    private static String[] reversed(String[] table) {
        String[] out = new String[table.length];
        for (int i = 0; i < table.length; i++) {
            out[i] = table[table.length - 1 - i];
        }
        return out;
    }

    private static IntFunction<String> lookup(String[] idToIdentity) {
        return id -> idToIdentity[id];
    }

    private static Map<String, Integer> inverse(String[] idToIdentity) {
        var map = new HashMap<String, Integer>();
        for (int id = 0; id < idToIdentity.length; id++) {
            map.put(idToIdentity[id], id);
        }
        return map;
    }

    /** Strict inverse: an unknown identity fails the TEST loudly — used where the
     *  registry pair is constructed to contain every served identity, so any miss is
     *  a translation bug, never a legitimate fallback. */
    private static ToIntFunction<String> strictInverse(String[] idToIdentity) {
        var map = inverse(idToIdentity);
        return identity -> {
            Integer id = map.get(identity);
            if (id == null) {
                throw new AssertionError("identity '" + identity
                        + "' unexpectedly missing from the receiving registry");
            }
            return id;
        };
    }

    // ---- body construction / voxel extraction ----------------------------------

    private static byte[] nativeBody(WireSection... sections) {
        return WireSectionCursor.emit(new WireColumn(List.of(), List.of(sections)), Layout.NATIVE);
    }

    /** The multi-shape source column every drift case reuses: an indexed 4-bit block
     *  palette + indexed 2-bit biomes, plus a single-value section — the two container
     *  shapes a small cross-version column actually ships. */
    private static byte[] sourceBodyUnderA() {
        var blockValues = new int[4096];
        var biomeValues = new int[64];
        for (int i = 0; i < 4096; i++) {
            blockValues[i] = i % 4;
        }
        for (int i = 0; i < 64; i++) {
            biomeValues[i] = i % 4;
        }
        var blocks = new WireContainer(4, new int[] { 1, 2, 3, 4 },
                WireSectionCursor.pack(blockValues, 4));
        var biomes = new WireContainer(2, new int[] { 0, 1, 2, 3 },
                WireSectionCursor.pack(biomeValues, 2));
        var single = new WireSection(3, 1, 0,
                new WireContainer(0, new int[] { 5 }, new long[0]),
                new WireContainer(0, new int[] { 1 }, new long[0]), null, null);
        return nativeBody(new WireSection(-4, 100, 5, blocks, biomes, null, null), single);
    }

    /** Per-entry resolved ids of a native container (single-value / indexed / DIRECT)
     *  — the {@code LegacyColumnEgressTest} helper, needed here because collapse and
     *  width re-emits make raw palette comparison meaningless. */
    private static int[] resolvedValues(WireContainer c, int entries) {
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

    private static String[] voxelIdentities(WireContainer c, int entries, String[] idToIdentity) {
        int[] ids = resolvedValues(c, entries);
        String[] out = new String[entries];
        for (int i = 0; i < entries; i++) {
            out[i] = idToIdentity[ids[i]];
        }
        return out;
    }

    /** The by-IDENTITY equivalence at every voxel of every section — ids may differ
     *  arbitrarily between the two registries; the identity may not. */
    private static void assertIdentityEquivalent(byte[] bodyUnderA, byte[] bodyUnderB,
                                                 String[] blocksA, String[] biomesA,
                                                 String[] blocksB, String[] biomesB) {
        var a = WireSectionCursor.parse(bodyUnderA, Layout.NATIVE);
        var b = WireSectionCursor.parse(bodyUnderB, Layout.NATIVE);
        assertEquals(a.sections().size(), b.sections().size());
        for (int i = 0; i < a.sections().size(); i++) {
            var sa = a.sections().get(i);
            var sb = b.sections().get(i);
            String at = "section " + i;
            assertEquals(sa.sectionY(), sb.sectionY(), at);
            assertEquals(sa.nonEmptyBlockCount(), sb.nonEmptyBlockCount(), at);
            assertEquals(sa.fluidCount(), sb.fluidCount(), at);
            assertArrayEquals(voxelIdentities(sa.blocks(), 4096, blocksA),
                    voxelIdentities(sb.blocks(), 4096, blocksB), at + " blocks");
            assertArrayEquals(voxelIdentities(sa.biomes(), 64, biomesA),
                    voxelIdentities(sb.biomes(), 64, biomesB), at + " biomes");
        }
    }

    private static byte[] encodeUnderA(byte[] nativeUnderA) {
        return NativeToV20Translator.translate(nativeUnderA, lookup(BLOCKS_A), lookup(BIOMES_A));
    }

    // ---- (a) permutation: the A3-style blindness pin ----------------------------

    @Test
    void permutedIdsDecodeToIdenticalContentByIdentity() {
        String[] blocksB = reversed(BLOCKS_A);
        String[] biomesB = reversed(BIOMES_A);
        byte[] source = sourceBodyUnderA();

        byte[] decoded = V20ToNativeTranslator.translate(encodeUnderA(source),
                strictInverse(blocksB), strictInverse(biomesB),
                blocksB.length, biomesB.length);

        // The ids REALLY moved (a byte-equal result would mean the test degenerated
        // into the same-registry bijection and proves nothing about permutation).
        assertFalse(java.util.Arrays.equals(source, decoded),
                "premise: a full derangement must change the emitted ids");
        assertIdentityEquivalent(source, decoded, BLOCKS_A, BIOMES_A, blocksB, biomesB);
    }

    // ---- (b) missing entries: fallback fires exactly on the missing set ---------

    @Test
    void missingEntriesFireTheFallbackExactlyOnTheMissingSetAndNowhereElse() {
        // B lost deepslate (a block A serves) and grove (a biome A serves).
        String[] blocksB = {
                "xver:air", "xver:stone", "xver:oak_stairs[facing=north,half=top]",
                "xver:copper_grate[waterlogged=true]", "xver:tuff",
        };
        String[] biomesB = { "xver:plains", "xver:desert", "xver:river" };
        var blockInverse = inverse(blocksB);
        var biomeInverse = inverse(biomesB);
        int blockFallbackId = blockInverse.get("xver:stone");
        int biomeFallbackId = biomeInverse.get("xver:plains");

        var blockFellBack = new HashSet<String>();
        var biomeFellBack = new HashSet<String>();
        ToIntFunction<String> blockResolver = identity -> {
            Integer id = blockInverse.get(identity);
            if (id != null) {
                return id;
            }
            blockFellBack.add(identity);
            return blockFallbackId;
        };
        ToIntFunction<String> biomeResolver = identity -> {
            Integer id = biomeInverse.get(identity);
            if (id != null) {
                return id;
            }
            biomeFellBack.add(identity);
            return biomeFallbackId;
        };

        byte[] source = sourceBodyUnderA();
        byte[] decoded = V20ToNativeTranslator.translate(encodeUnderA(source),
                blockResolver, biomeResolver, blocksB.length, biomesB.length);

        // Exactly the missing set — set equality catches BOTH failure directions
        // (a known identity leaking into fallback, and a second missing one hiding).
        // The dictionary is SHARED between kinds, so this also pins that the biome
        // resolver never sees block identities and vice versa: resolution is lazy,
        // driven by palette references, not an eager dictionary sweep.
        assertEquals(Set.of("xver:deepslate"), blockFellBack);
        assertEquals(Set.of("xver:grove"), biomeFellBack);

        // Voxel-level: deepslate positions carry the fallback identity, everything
        // else survives by identity untouched.
        var a = WireSectionCursor.parse(source, Layout.NATIVE);
        var b = WireSectionCursor.parse(decoded, Layout.NATIVE);
        String[] srcBlocks = voxelIdentities(a.sections().get(0).blocks(), 4096, BLOCKS_A);
        String[] gotBlocks = voxelIdentities(b.sections().get(0).blocks(), 4096, blocksB);
        String[] srcBiomes = voxelIdentities(a.sections().get(0).biomes(), 64, BIOMES_A);
        String[] gotBiomes = voxelIdentities(b.sections().get(0).biomes(), 64, biomesB);
        int substituted = 0;
        for (int i = 0; i < 4096; i++) {
            if (srcBlocks[i].equals("xver:deepslate")) {
                assertEquals("xver:stone", gotBlocks[i], "missing identity must land on the fallback at voxel " + i);
                substituted++;
            } else {
                assertEquals(srcBlocks[i], gotBlocks[i], "present identity must survive at voxel " + i);
            }
        }
        assertEquals(1024, substituted, "premise: the source actually served the missing block");
        for (int i = 0; i < 64; i++) {
            assertEquals(srcBiomes[i].equals("xver:grove") ? "xver:plains" : srcBiomes[i],
                    gotBiomes[i], "biome voxel " + i);
        }
    }

    /** The other half of the missing-entry contract: fallback policy lives in the
     *  RESOLVER, never in the translator — a resolver that answers an unknown identity
     *  with a negative id gets the pinned loud failure, not a silent substitution.
     *  (This is what forces every real consumer to inject an explicit policy: the
     *  client its §3 ladder, the server its fail-loud exact inverse.) */
    @Test
    void resolverReturningNegativeIsALoudWireFormatException() {
        byte[] v20 = encodeUnderA(sourceBodyUnderA());
        var thrown = assertThrows(WireFormatException.class,
                () -> V20ToNativeTranslator.translate(v20,
                        identity -> -1, strictInverse(BIOMES_A),
                        BLOCKS_A.length, BIOMES_A.length));
        assertTrue(thrown.getMessage().contains("resolver returned invalid id"),
                "the failure must name the resolver contract, got: " + thrown.getMessage());
    }

    // ---- (c) added entries: ids shift, identities don't -------------------------

    @Test
    void addedEntriesShiftEveryIdWithoutDisturbingDecodeByIdentity() {
        // B gained entries BEFORE A's (shifting every inherited id) and after.
        String[] blocksB = {
                "xver:new_block_a", "xver:new_block_b",
                "xver:air", "xver:stone", "xver:oak_stairs[facing=north,half=top]",
                "xver:deepslate", "xver:copper_grate[waterlogged=true]", "xver:tuff",
                "xver:new_block_c",
        };
        String[] biomesB = {
                "xver:new_biome",
                "xver:plains", "xver:desert", "xver:river", "xver:grove",
        };
        byte[] source = sourceBodyUnderA();

        byte[] decoded = V20ToNativeTranslator.translate(encodeUnderA(source),
                strictInverse(blocksB), strictInverse(biomesB),
                blocksB.length, biomesB.length);

        assertFalse(java.util.Arrays.equals(source, decoded),
                "premise: prepended entries must shift the emitted ids");
        assertIdentityEquivalent(source, decoded, BLOCKS_A, BIOMES_A, blocksB, biomesB);
    }

    // ---- (d) duplicate-resolve tolerance: the palette-collapse case -------------

    @Test
    void twoIdentitiesResolvingToOneLocalIdCollapseWithoutErrorAndBothMapToIt() {
        // A curated-style resolver on B's side sends BOTH stone and deepslate to B's
        // stone id — the §4.2 collapse case. Cannot happen same-version; CAN through
        // fallbacks, and native palettes tolerate duplicates, but the translator
        // collapses and the packed-index REMAP is the correctness-critical half: an
        // unremapped index would silently point at the wrong collapsed entry.
        String[] blocksB = reversed(BLOCKS_A);
        String[] biomesB = reversed(BIOMES_A);
        var blockInverse = inverse(blocksB);
        int stoneB = blockInverse.get("xver:stone");
        ToIntFunction<String> collapsing = identity ->
                identity.equals("xver:deepslate") ? stoneB : blockInverse.get(identity);

        byte[] source = sourceBodyUnderA();
        byte[] decoded = V20ToNativeTranslator.translate(encodeUnderA(source),
                collapsing, strictInverse(biomesB), blocksB.length, biomesB.length);

        var section = WireSectionCursor.parse(decoded, Layout.NATIVE).sections().get(0);
        var sourceSection = WireSectionCursor.parse(source, Layout.NATIVE).sections().get(0);
        String[] srcBlocks = voxelIdentities(sourceSection.blocks(), 4096, BLOCKS_A);
        int[] gotIds = resolvedValues(section.blocks(), 4096);
        for (int i = 0; i < 4096; i++) {
            String src = srcBlocks[i];
            if (src.equals("xver:stone") || src.equals("xver:deepslate")) {
                assertEquals(stoneB, gotIds[i], "both collapsed identities must map to the ONE id at voxel " + i);
            } else {
                assertEquals(blockInverse.get(src).intValue(), gotIds[i], "uncollapsed voxel " + i);
            }
        }

        // The emitted palette is the MINIMAL collapsed one: distinct ids only. A
        // duplicate here would mean the collapse half ran without the remap half.
        int[] palette = section.blocks().palette();
        assertEquals(3, palette.length, "4 source entries, one pair collapsed -> 3");
        assertEquals(palette.length,
                java.util.Arrays.stream(palette).distinct().count(),
                "collapsed palette must not carry duplicates");
    }
}
