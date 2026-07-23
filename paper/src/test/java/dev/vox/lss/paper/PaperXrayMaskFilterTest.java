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
import net.minecraft.world.level.chunk.PalettedContainerFactory;
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
    private static PalettedContainerFactory FACTORY;

    @BeforeAll
    static void setup() {
        REGISTRY_ACCESS = buildRegistryAccess();
        FACTORY = PalettedContainerFactory.create(REGISTRY_ACCESS);
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

        var masked = PaperXrayMaskFilter.maskCopy(section, 0, defaultMask(), FallbackKind.OVERWORLD);

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
        assertSame(section, PaperXrayMaskFilter.maskCopy(section,
                4, mask(64, "gold_ore"), FallbackKind.OVERWORLD));
    }

    @Test
    void straddlingSectionMasksOnlyBelowTheCutoff() {
        var section = newSection();
        fillAll(section, Blocks.STONE.defaultBlockState());
        for (int y = 0; y < 16; y++) section.setBlockState(3, y, 3, Blocks.IRON_ORE.defaultBlockState());

        var masked = PaperXrayMaskFilter.maskCopy(section, 4, mask(72, "iron_ore"), FallbackKind.OVERWORLD);

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
            var masked = PaperXrayMaskFilter.maskCopy(section, c.sectionY(),
                    mask(2048, "diamond_ore"), c.kind());
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

        var masked = PaperXrayMaskFilter.maskCopy(section, 0, mask(2048, "diamond_ore"), FallbackKind.OVERWORLD);

        assertEquals(4096 - 8, countCells(masked, s -> s == expected));
    }

    @Test
    void maskInPlaceMatchesMaskCopyCellForCell() {
        var a = newSection();
        var b = newSection();
        for (var s : List.of(a, b)) {
            fillAll(s, Blocks.STONE.defaultBlockState());
            sprinkle(s, Blocks.DIAMOND_ORE.defaultBlockState(), 31, 0);
            sprinkle(s, Blocks.GRANITE.defaultBlockState(), 53, 1);
            for (int x = 0; x < 16; x++) s.setBlockState(x, 15, 15, Blocks.AIR.defaultBlockState());
        }
        var m = defaultMask();

        var copied = PaperXrayMaskFilter.maskCopy(a, 0, m, FallbackKind.OVERWORLD);
        PaperXrayMaskFilter.maskInPlace(b, 0, m, FallbackKind.OVERWORLD);

        for (int y = 0; y < 16; y++)
            for (int z = 0; z < 16; z++)
                for (int x = 0; x < 16; x++)
                    assertSame(copied.getBlockState(x, y, z), b.getBlockState(x, y, z),
                            "the two paths must produce identical content at " + x + "," + y + "," + z);
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

        var copied = PaperXrayMaskFilter.maskCopy(a, 0, m, FallbackKind.OVERWORLD);
        PaperXrayMaskFilter.maskInPlace(b, 0, m, FallbackKind.OVERWORLD);

        assertArrayEquals(sectionBytes(copied), sectionBytes(b),
                "live-copy and in-place masking must serialize byte-identically — "
                        + "DirtyContentFilter hashes and live-vs-disk parity depend on it");
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

        var masked = PaperXrayMaskFilter.maskCopy(section, 0, defaultMask(), FallbackKind.OVERWORLD);

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
        assertSame(section, PaperXrayMaskFilter.maskCopy(section, 0, allUnknown, FallbackKind.OVERWORLD));
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
        PaperXrayMaskFilter.maskInPlace(deep, -4, m, FallbackKind.OVERWORLD);
        assertEquals(0, countCells(deep, s -> s.is(Blocks.DEEPSLATE_DIAMOND_ORE)));

        var mid = newSection();
        fillAll(mid, Blocks.STONE.defaultBlockState());
        sprinkle(mid, Blocks.IRON_ORE.defaultBlockState(), 23, 1);
        mid.setBlockState(5, 5, 5, Blocks.REDSTONE_ORE.defaultBlockState().setValue(BlockStateProperties.LIT, true));
        for (int x = 0; x < 16; x++)
            for (int z = 0; z < 16; z++)
                mid.setBlockState(x, 15, z, Blocks.AIR.defaultBlockState());
        PaperXrayMaskFilter.maskInPlace(mid, 0, m, FallbackKind.OVERWORLD);
        assertEquals(0, countCells(mid, s -> s.is(Blocks.IRON_ORE) || s.is(Blocks.REDSTONE_ORE)));

        var high = newSection();
        fillAll(high, Blocks.STONE.defaultBlockState());
        int highOres = sprinkle(high, Blocks.GOLD_ORE.defaultBlockState(), 61, 2);
        PaperXrayMaskFilter.maskInPlace(high, 4, m, FallbackKind.OVERWORLD);
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
