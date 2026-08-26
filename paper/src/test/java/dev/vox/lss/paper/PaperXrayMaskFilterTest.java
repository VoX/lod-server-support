package dev.vox.lss.paper;

import com.mojang.serialization.Lifecycle;
import dev.vox.lss.common.XrayMaskPolicy.FallbackKind;
import dev.vox.lss.common.config.ServerConfigBase;
import io.netty.buffer.Unpooled;
import net.minecraft.SharedConstants;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Twin of the Fabric {@code XrayMaskFilterTest}: pins {@link PaperXrayMaskFilter}'s
 * masking semantics and writes THIS module's {@code xray-masked} corpus fixture from the
 * IDENTICAL deterministic sections — the corpus parity test then byte-diffs the two
 * committed fixtures, pinning twin behavior at the byte level. Keep the section builders
 * in sync with the Fabric test.
 */
@SuppressWarnings("deprecation") // LevelChunkSection.write(buf): the vanilla 1-arg form is the wire contract
class PaperXrayMaskFilterTest {

    static {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static RegistryAccess REGISTRY_ACCESS;
    private static net.minecraft.core.Registry<net.minecraft.world.level.biome.Biome> FACTORY; // 1.21.1 line: the seam handle is the biome registry

    @BeforeAll
    static void setup() {
        REGISTRY_ACCESS = buildRegistryAccess();
        FACTORY = REGISTRY_ACCESS.registryOrThrow(Registries.BIOME);
    }

    /** Same fixed 4-biome registry as the NbtSectionSerializerTest corpus — golden bytes
     *  embed biome palette ids. Never reorder; regenerate all goldens on BOTH modules if
     *  this changes. */
    private static RegistryAccess buildRegistryAccess() {
        HolderLookup.Provider provider = VanillaRegistries.createLookup();
        HolderLookup.RegistryLookup<Biome> src = provider.lookupOrThrow(Registries.BIOME);
        MappedRegistry<Biome> biomes = new MappedRegistry<>(Registries.BIOME, Lifecycle.stable());
        for (var key : List.of(Biomes.PLAINS, Biomes.DESERT, Biomes.JUNGLE, Biomes.SNOWY_TAIGA)) {
            biomes.register(key, src.getOrThrow(key).value(), RegistrationInfo.BUILT_IN);
        }
        biomes.freeze();
        return new RegistryAccess.ImmutableRegistryAccess(List.of(biomes));
    }

    /** Paper twin of the v0.13.1 fingerprint wiring pin — see
     *  {@code XrayMaskFilterTest.fingerprintIsTheContentHashOfTheHiddenIdentities}:
     *  a one-sided backport that drops the identity collection here would flatten
     *  every mask fingerprint with all fabric suites green. */
    @Test
    void fingerprintIsTheContentHashOfTheHiddenIdentities() {
        assertEquals(dev.vox.lss.common.XrayMaskPolicy.maskContentFingerprint(
                        net.minecraft.world.level.block.Blocks.DIAMOND_ORE
                                .getStateDefinition().getPossibleStates().stream()
                                .map(String::valueOf).toList(), 64),
                mask(64, "diamond_ore").fingerprint(),
                "the fingerprint must hash the hidden-state identity strings through"
                        + " the shared seam");
        assertEquals(mask(64, "diamond_ore", "gold_ore").fingerprint(),
                mask(64, "gold_ore", "diamond_ore").fingerprint(),
                "resolve order must not matter");
        assertNotEquals(mask(64, "diamond_ore").fingerprint(),
                mask(64, "gold_ore").fingerprint(),
                "different hidden blocks must fingerprint apart");
    }

    // ---- helpers (twin-identical to the Fabric test) ----

    private static PaperXrayMaskFilter.MaskSet defaultMask() {
        return PaperXrayMaskFilter.MaskSet.resolve(ServerConfigBase.defaultXrayHiddenBlocks(), 2048);
    }

    private static PaperXrayMaskFilter.MaskSet mask(int maxHeight, String... blockIds) {
        return PaperXrayMaskFilter.MaskSet.resolve(List.of(blockIds), maxHeight);
    }

    private static LevelChunkSection newSection() {
        return new LevelChunkSection(FACTORY);
    }

    private static void fillAll(LevelChunkSection s, BlockState state) {
        for (int y = 0; y < 16; y++)
            for (int z = 0; z < 16; z++)
                for (int x = 0; x < 16; x++)
                    s.setBlockState(x, y, z, state);
    }

    /** Deterministic sprinkle — every {@code step}-th cell in x-z-y scan order. */
    private static int sprinkle(LevelChunkSection s, BlockState state, int step, int offset) {
        int n = 0;
        for (int y = 0; y < 16; y++)
            for (int z = 0; z < 16; z++)
                for (int x = 0; x < 16; x++)
                    if ((x + 16 * z + 256 * y) % step == offset) {
                        s.setBlockState(x, y, z, state);
                        n++;
                    }
        return n;
    }

    private static int countCells(LevelChunkSection s, Predicate<BlockState> p) {
        int n = 0;
        for (int y = 0; y < 16; y++)
            for (int z = 0; z < 16; z++)
                for (int x = 0; x < 16; x++)
                    if (p.test(s.getBlockState(x, y, z))) n++;
        return n;
    }

    // ---- masking semantics (the load-bearing subset; full sweep lives in the Fabric twin) ----

    @Test
    void hiddenStatesReplacedByDominantStateAndOriginalUntouched() {
        var section = newSection();
        fillAll(section, Blocks.STONE.defaultBlockState());
        int ores = sprinkle(section, Blocks.DIAMOND_ORE.defaultBlockState(), 37, 0);

        var masked = PaperXrayMaskFilter.mask(section, 0, defaultMask(), FallbackKind.OVERWORLD, FACTORY);

        assertNotSame(section, masked, "an ore-bearing section must be copied");
        assertEquals(0, countCells(masked, s -> s.is(Blocks.DIAMOND_ORE)), "every ore masked");
        assertEquals(4096, countCells(masked, s -> s.is(Blocks.STONE)), "dominant stone replaces ore");
        assertEquals(ores, countCells(section, s -> s.is(Blocks.DIAMOND_ORE)),
                "the LIVE section must never be mutated");
    }

    @Test
    void sectionAtOrAboveCutoffReturnsTheSameInstance() {
        var section = newSection();
        fillAll(section, Blocks.STONE.defaultBlockState());
        sprinkle(section, Blocks.GOLD_ORE.defaultBlockState(), 50, 0);
        assertSame(section, PaperXrayMaskFilter.mask(section,
                4, mask(64, "gold_ore"), FallbackKind.OVERWORLD, FACTORY));
    }

    @Test
    void stalePaletteRebuildsForThePruneButReportsZeroReplacedCells() {
        // See the Fabric twin (R2-6): a mined-out section's stale palette entry still
        // triggers the rebuild (the prune is the point) but reports zero replaced cells
        // so masked_sections doesn't count a no-op.
        var section = newSection();
        fillAll(section, Blocks.STONE.defaultBlockState());
        section.setBlockState(3, 3, 3, Blocks.DIAMOND_ORE.defaultBlockState());
        section.setBlockState(3, 3, 3, Blocks.STONE.defaultBlockState()); // mined out

        int[] replaced = new int[1];
        var masked = PaperXrayMaskFilter.mask(section, 0, defaultMask(), FallbackKind.OVERWORLD,
                FACTORY, replaced);

        assertNotSame(section, masked, "the stale-palette section still rebuilds (palette prune)");
        assertEquals(0, replaced[0], "zero cells were hidden — masked_sections must not count this");
        assertEquals(4096, countCells(masked, s -> s.is(Blocks.STONE)), "content is untouched");

        var withOre = newSection();
        fillAll(withOre, Blocks.STONE.defaultBlockState());
        int ores = sprinkle(withOre, Blocks.DIAMOND_ORE.defaultBlockState(), 11, 0);
        PaperXrayMaskFilter.mask(withOre, 0, defaultMask(), FallbackKind.OVERWORLD, FACTORY, replaced);
        assertEquals(ores, replaced[0], "a genuinely ore-bearing section reports its replaced cells");
    }

    @Test
    void straddlingSectionMasksOnlyBelowTheCutoff() {
        var section = newSection();
        fillAll(section, Blocks.STONE.defaultBlockState());
        for (int y = 0; y < 16; y++) section.setBlockState(3, y, 3, Blocks.IRON_ORE.defaultBlockState());

        var masked = PaperXrayMaskFilter.mask(section, 4, mask(72, "iron_ore"), FallbackKind.OVERWORLD, FACTORY);

        for (int y = 0; y < 8; y++) {
            assertTrue(masked.getBlockState(3, y, 3).is(Blocks.STONE),
                    "below the cutoff (world " + (64 + y) + ") must be masked");
        }
        for (int y = 8; y < 16; y++) {
            assertTrue(masked.getBlockState(3, y, 3).is(Blocks.IRON_ORE),
                    "at/above the cutoff (world " + (64 + y) + ") must stay real");
        }
    }

    @Test
    void fallbackLadderWhenNoDominantCandidateExists() {
        record Case(FallbackKind kind, int sectionY, Block expected) {}
        for (var c : List.of(
                new Case(FallbackKind.OVERWORLD, 0, Blocks.STONE),
                new Case(FallbackKind.OVERWORLD, -1, Blocks.DEEPSLATE),
                new Case(FallbackKind.NETHER, 0, Blocks.NETHERRACK),
                new Case(FallbackKind.END, 0, Blocks.END_STONE))) {
            var section = newSection();
            fillAll(section, Blocks.DIAMOND_ORE.defaultBlockState());
            var masked = PaperXrayMaskFilter.mask(section, c.sectionY(),
                    mask(2048, "diamond_ore"), c.kind(), FACTORY);
            assertEquals(4096, countCells(masked, s -> s.is(c.expected())),
                    c.kind() + "/sectionY=" + c.sectionY() + " must fall back to " + c.expected());
        }
    }

    @Test
    void dominantTieBreaksOnLowestGlobalStateId() {
        var granite = Blocks.GRANITE.defaultBlockState();
        var diorite = Blocks.DIORITE.defaultBlockState();
        var section = newSection();
        fillAll(section, Blocks.DIAMOND_ORE.defaultBlockState());
        for (int x = 0; x < 8; x++) {
            section.setBlockState(x, 0, 0, granite);
            section.setBlockState(x, 0, 1, diorite);
        }
        var expected = Block.BLOCK_STATE_REGISTRY.getId(granite) < Block.BLOCK_STATE_REGISTRY.getId(diorite)
                ? granite : diorite;

        var masked = PaperXrayMaskFilter.mask(section, 0, mask(2048, "diamond_ore"), FallbackKind.OVERWORLD, FACTORY);

        assertEquals(4096 - 8, countCells(masked, s -> s == expected));
    }

    @Test
    void maskIsDeterministicAcrossSectionInstances() {
        var a = newSection();
        var b = newSection();
        for (var s : List.of(a, b)) {
            fillAll(s, Blocks.STONE.defaultBlockState());
            sprinkle(s, Blocks.DIAMOND_ORE.defaultBlockState(), 31, 0);
            sprinkle(s, Blocks.GRANITE.defaultBlockState(), 53, 1);
            for (int x = 0; x < 16; x++) s.setBlockState(x, 15, 15, Blocks.AIR.defaultBlockState());
        }
        var m = defaultMask();

        var maskedA = PaperXrayMaskFilter.mask(a, 0, m, FallbackKind.OVERWORLD, FACTORY);
        var maskedB = PaperXrayMaskFilter.mask(b, 0, m, FallbackKind.OVERWORLD, FACTORY);

        for (int y = 0; y < 16; y++)
            for (int z = 0; z < 16; z++)
                for (int x = 0; x < 16; x++)
                    assertSame(maskedA.getBlockState(x, y, z), maskedB.getBlockState(x, y, z),
                            "two builds over identical content must match at " + x + "," + y + "," + z);
    }

    @Test
    void maskedBytesAreIdenticalAcrossPathsIncludingCountHeaders() {
        var a = newSection();
        var b = newSection();
        for (var s : List.of(a, b)) {
            fillAll(s, Blocks.STONE.defaultBlockState());
            sprinkle(s, Blocks.DIAMOND_ORE.defaultBlockState(), 31, 0);
            // The regression shape (Phase-2 review finding 1): a waterlogged hidden block
            // below the cutoff changes the section's FLUID census when masked to stone —
            // write() leads with nonEmptyBlockCount + fluidCount, so both paths must
            // recalculate or live-vs-disk bytes diverge at the header.
            s.setBlockState(2, 3, 4, Blocks.CHEST.defaultBlockState()
                    .setValue(BlockStateProperties.WATERLOGGED, true));
            for (int x = 0; x < 16; x++) s.setBlockState(x, 15, 15, Blocks.AIR.defaultBlockState());
        }
        var m = defaultMask();

        var maskedA = PaperXrayMaskFilter.mask(a, 0, m, FallbackKind.OVERWORLD, FACTORY);
        var maskedB = PaperXrayMaskFilter.mask(b, 0, m, FallbackKind.OVERWORLD, FACTORY);

        assertArrayEquals(sectionBytes(maskedA), sectionBytes(maskedB),
                "live-copy and in-place masking must serialize byte-identically — "
                        + "DirtyContentFilter hashes and live-vs-disk parity depend on it");
    }

    @Test
    void maskedSectionPaletteCarriesNoHiddenStates() {
        // The palette-residue fix (review 2026-07-27): set() never prunes, so before the
        // rebuild a masked section still LISTED the hidden ores in its serialized palette —
        // a section-resolution ore-presence oracle across the LOD radius. maybeHas answers
        // from palette ENTRIES (which makes it the right assertion HERE and the wrong
        // ground truth for cell counts): the rebuilt container must carry no hidden state
        // at all, referenced or not.
        var section = newSection();
        fillAll(section, Blocks.STONE.defaultBlockState());
        sprinkle(section, Blocks.DIAMOND_ORE.defaultBlockState(), 31, 0);
        sprinkle(section, Blocks.IRON_ORE.defaultBlockState(), 17, 2);
        var m = defaultMask();

        var masked = PaperXrayMaskFilter.mask(section, 0, m, FallbackKind.OVERWORLD, FACTORY);

        assertFalse(masked.getStates().maybeHas(m::contains),
                "the masked palette must not name any hidden state");
    }


    @Test
    void maskedPaletteDropsTheRecreateSeedResidue() {
        // The recreate() trap (final review 2026-07-27): recreate() seeds the fresh
        // container with the SOURCE palette's entry 0 — for any disk-order palette that is
        // the block at local (0,0,0) — and the seed survives in the palette even with zero
        // referencing cells. A section whose corner block is itself hidden therefore still
        // shipped that one ore id. The replacement-seeded rebuild must not: this source
        // container is seeded on the ore (palette entry 0 == DIAMOND_ORE), exactly the
        // disk shape that leaked.
        // 1.21.1 line: the (IdMap, T, Strategy) ctor (no 2-arg registry-carrying form).
        var oreSeeded = new PalettedContainer<>(
                net.minecraft.world.level.block.Block.BLOCK_STATE_REGISTRY,
                Blocks.DIAMOND_ORE.defaultBlockState(),
                PalettedContainer.Strategy.SECTION_STATES);
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    if (x + y + z > 0) oreSeeded.set(x, y, z, Blocks.STONE.defaultBlockState());
                }
            }
        }
        var section = new LevelChunkSection(oreSeeded, dev.vox.lss.paper.testutil.TestPalettedContainers.createForBiomes(FACTORY));
        var m = defaultMask();
        var masked = PaperXrayMaskFilter.mask(section, 0, m, FallbackKind.OVERWORLD, FACTORY);

        assertNotSame(section, masked, "the corner ore must trigger masking");
        assertFalse(masked.getStates().maybeHas(m::contains),
                "the ex-palette-entry-0 ore must not survive as an unreferenced palette seed");
        assertEquals(Blocks.STONE.defaultBlockState(), masked.getStates().get(0, 0, 0),
                "the corner cell masks to the section's dominant state");
    }

    private static byte[] sectionBytes(LevelChunkSection s) {
        var buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            s.write(buf);
            byte[] out = new byte[buf.readableBytes()];
            buf.readBytes(out);
            return out;
        } finally {
            buf.release();
        }
    }

    @Test
    void airBlocksCannotBeHidden() {
        assertTrue(mask(2048, "air", "cave_air").isEmpty(),
                "air blocks must not resolve — hiding air would fill caves");
        var m = mask(2048, "air", "diamond_ore");
        assertFalse(m.isEmpty());
        assertTrue(m.contains(Blocks.DIAMOND_ORE.defaultBlockState()));
        assertFalse(m.contains(Blocks.AIR.defaultBlockState()));
    }

    @Test
    void maskingNeverIntroducesOrRemovesAir() {
        var section = newSection();
        fillAll(section, Blocks.STONE.defaultBlockState());
        sprinkle(section, Blocks.DIAMOND_ORE.defaultBlockState(), 19, 0);
        for (int x = 0; x < 16; x++)
            for (int z = 0; z < 16; z++)
                section.setBlockState(x, 15, z, Blocks.AIR.defaultBlockState());

        var masked = PaperXrayMaskFilter.mask(section, 0, defaultMask(), FallbackKind.OVERWORLD, FACTORY);

        assertFalse(masked.hasOnlyAir());
        for (int y = 0; y < 16; y++)
            for (int z = 0; z < 16; z++)
                for (int x = 0; x < 16; x++)
                    assertEquals(section.getBlockState(x, y, z).isAir(),
                            masked.getBlockState(x, y, z).isAir(),
                            "air-ness must be preserved at " + x + "," + y + "," + z);
    }

    @Test
    void nullAndBlankListEntriesAreSkippedWithoutThrowing() {
        var entries = new java.util.ArrayList<String>();
        entries.add(null);
        entries.add("  ");
        entries.add("iron_ore");
        var m = PaperXrayMaskFilter.MaskSet.resolve(entries, 2048);
        assertFalse(m.isEmpty());
        assertTrue(m.contains(Blocks.IRON_ORE.defaultBlockState()));
    }

    @Test
    void fromStatesDropsAirAndNulls() {
        var states = new java.util.ArrayList<net.minecraft.world.level.block.state.BlockState>();
        states.add(Blocks.AIR.defaultBlockState());
        states.add(null);
        states.add(Blocks.DIAMOND_ORE.defaultBlockState());
        var m = PaperXrayMaskFilter.MaskSet.fromStates(states, 2048);
        assertFalse(m.isEmpty());
        assertTrue(m.contains(Blocks.DIAMOND_ORE.defaultBlockState()));
        assertFalse(m.contains(Blocks.AIR.defaultBlockState()));
    }

    @Test
    void unknownIdsAreSkippedAndAnAllUnknownMaskIsInert() {
        var m = mask(2048, "not_a_block", "definitely:missing", "diamond_ore");
        assertFalse(m.isEmpty());
        assertTrue(m.contains(Blocks.DIAMOND_ORE.defaultBlockState()));

        var allUnknown = mask(2048, "not_a_block");
        assertTrue(allUnknown.isEmpty());
        var section = newSection();
        fillAll(section, Blocks.DIAMOND_ORE.defaultBlockState());
        assertSame(section, PaperXrayMaskFilter.mask(section, 0, allUnknown, FallbackKind.OVERWORLD, FACTORY));
    }

    // ---- golden fixture (cross-module parity via the corpus diff test) ----

    /** Twin of the Fabric goldenMaskedFixture — IDENTICAL sections, THIS module's fixture. */
    @Test
    void goldenMaskedFixture() throws IOException {
        var m = PaperXrayMaskFilter.MaskSet.resolve(ServerConfigBase.defaultXrayHiddenBlocks(), 64);

        var deep = newSection();
        fillAll(deep, Blocks.DEEPSLATE.defaultBlockState());
        sprinkle(deep, Blocks.DEEPSLATE_DIAMOND_ORE.defaultBlockState(), 41, 0);
        sprinkle(deep, Blocks.DEEPSLATE_IRON_ORE.defaultBlockState(), 29, 3);
        deep = PaperXrayMaskFilter.mask(deep, -4, m, FallbackKind.OVERWORLD, FACTORY);
        assertEquals(0, countCells(deep, s -> s.is(Blocks.DEEPSLATE_DIAMOND_ORE)));

        var mid = newSection();
        fillAll(mid, Blocks.STONE.defaultBlockState());
        sprinkle(mid, Blocks.IRON_ORE.defaultBlockState(), 23, 1);
        mid.setBlockState(5, 5, 5, Blocks.REDSTONE_ORE.defaultBlockState().setValue(BlockStateProperties.LIT, true));
        for (int x = 0; x < 16; x++)
            for (int z = 0; z < 16; z++)
                mid.setBlockState(x, 15, z, Blocks.AIR.defaultBlockState());
        mid = PaperXrayMaskFilter.mask(mid, 0, m, FallbackKind.OVERWORLD, FACTORY);
        assertEquals(0, countCells(mid, s -> s.is(Blocks.IRON_ORE) || s.is(Blocks.REDSTONE_ORE)));

        var high = newSection();
        fillAll(high, Blocks.STONE.defaultBlockState());
        int highOres = sprinkle(high, Blocks.GOLD_ORE.defaultBlockState(), 61, 2);
        high = PaperXrayMaskFilter.mask(high, 4, m, FallbackKind.OVERWORLD, FACTORY);
        assertEquals(highOres, countCells(high, s -> s.is(Blocks.GOLD_ORE)));

        var buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buf.writeVarInt(3);
            for (var entry : List.of(new int[]{-4}, new int[]{0}, new int[]{4})) {
                var section = switch (entry[0]) {
                    case -4 -> deep;
                    case 0 -> mid;
                    default -> high;
                };
                buf.writeByte(entry[0]);
                section.write(buf);
                buf.writeBoolean(false);
                buf.writeBoolean(false);
            }
            byte[] wire = new byte[buf.readableBytes()];
            buf.readBytes(wire);
            // The v20 sibling FIRST (review C1-12): the SAME masked bytes through the
            // produce-path hook, pinned in v20-corpus — masked serves ship v20 like
            // everything else. Runs before the native golden because that one's regen
            // branch fail()s (this one writes without failing under regen).
            assertMatchesV20Golden("xray-masked",
                    PaperNbtSectionSerializer.toV20(wire, REGISTRY_ACCESS));
            assertMatchesGolden("xray-masked", wire);
        } finally {
            buf.release();
        }
    }

    // ---- golden helpers (same mechanics as this module's NbtSectionSerializerTest) ----

    private static final String GOLDEN_DIR = "src/test/resources/nbt-corpus";

    private static boolean regenGoldens() {
        return Boolean.getBoolean("lss.regenGoldens")
                || "true".equalsIgnoreCase(System.getenv("LSS_REGEN_GOLDENS"));
    }

    private static Path goldenPath(String name) {
        Path dir = Path.of("").toAbsolutePath();
        for (int depth = 0; depth < 5 && dir != null; depth++, dir = dir.getParent()) {
            if (Files.isDirectory(dir.resolve("src/test/java/dev/vox/lss"))) {
                return dir.resolve(GOLDEN_DIR).resolve(name + ".bin");
            }
            Path nested = dir.resolve("paper");
            if (Files.isDirectory(nested.resolve("src/test/java/dev/vox/lss"))) {
                return nested.resolve(GOLDEN_DIR).resolve(name + ".bin");
            }
        }
        throw new IllegalStateException("cannot locate the paper module source tree from "
                + Path.of("").toAbsolutePath() + " — the golden corpus reads/writes src/test/resources");
    }

    private static void assertMatchesV20Golden(String name, byte[] wire) throws IOException {
        Path golden = goldenPath(name).getParent().getParent()
                .resolve("v20-corpus").resolve(name + ".bin");
        if (regenGoldens()) {
            Files.createDirectories(golden.getParent());
            Files.write(golden, wire);
            return;  // the NATIVE golden's regen fail() below carries the re-run notice
        }
        if (!Files.exists(golden)) {
            fail("missing v20 golden " + golden + " — regenerate with the goldens flag");
        }
        byte[] expected = Files.readAllBytes(golden);
        if (java.util.Arrays.mismatch(expected, wire) != -1) {
            fail("v20 masked golden drifted at byte "
                    + java.util.Arrays.mismatch(expected, wire));
        }
    }

    private static void assertMatchesGolden(String name, byte[] wire) throws IOException {
        Path golden = goldenPath(name);
        if (regenGoldens()) {
            Files.createDirectories(golden.getParent());
            Files.write(golden, wire);
            fail("goldens regenerated (" + golden + "), re-run without -Dlss.regenGoldens=true and commit the fixture");
        }
        if (!Files.exists(golden)) {
            fail("missing golden fixture " + golden + " — goldens are never authored by hand: run this test"
                    + " with -Dlss.regenGoldens=true on the test JVM (or env LSS_REGEN_GOLDENS=true with"
                    + " --no-daemon), then re-run without the flag and commit the written file");
        }
        byte[] expected = Files.readAllBytes(golden);
        int mismatch = Arrays.mismatch(expected, wire);
        if (mismatch != -1) {
            fail(name + ": wire bytes diverge from the committed golden at index " + mismatch
                    + " (golden " + expected.length + " B, actual " + wire.length + " B). If intentional,"
                    + " regenerate with -Dlss.regenGoldens=true on BOTH the fabric and paper modules and"
                    + " verify the two fixture copies still byte-match.");
        }
    }
}
