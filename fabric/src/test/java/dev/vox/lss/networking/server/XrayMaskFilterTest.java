package dev.vox.lss.networking.server;

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
 * Pins {@link XrayMaskFilter} (docs/planning/antixray-compat-design.md §3): the height
 * gate, the palette fast path, dominant-state replacement with the deterministic
 * registry-id tie-break, the dimension fallback ladder, the never-air invariant, live-copy
 * vs in-place path equivalence, and the {@code xray-masked} golden fixture — the byte
 * artifact the cross-module corpus parity test diffs against the Paper twin
 * ({@code PaperXrayMaskFilterTest} builds the IDENTICAL sections; keep them in sync).
 */
class XrayMaskFilterTest {

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

    // ---- helpers ----

    /** A mask from the production default list with a cutoff high enough to be inert. */
    private static XrayMaskFilter.MaskSet defaultMask() {
        return XrayMaskFilter.MaskSet.resolve(ServerConfigBase.defaultXrayHiddenBlocks(), 2048);
    }

    private static XrayMaskFilter.MaskSet mask(int maxHeight, String... blockIds) {
        return XrayMaskFilter.MaskSet.resolve(List.of(blockIds), maxHeight);
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

    // ---- masking semantics ----

    @Test
    void hiddenStatesReplacedByDominantStateAndOriginalUntouched() {
        var section = newSection();
        fillAll(section, Blocks.STONE.defaultBlockState());
        int ores = sprinkle(section, Blocks.DIAMOND_ORE.defaultBlockState(), 37, 0);

        var masked = XrayMaskFilter.maskCopy(section, 0, defaultMask(), FallbackKind.OVERWORLD);

        assertNotSame(section, masked, "an ore-bearing section must be copied");
        assertEquals(0, countCells(masked, s -> s.is(Blocks.DIAMOND_ORE)), "every ore masked");
        assertEquals(4096, countCells(masked, s -> s.is(Blocks.STONE)), "dominant stone replaces ore");
        assertEquals(ores, countCells(section, s -> s.is(Blocks.DIAMOND_ORE)),
                "the LIVE section must never be mutated");
    }

    @Test
    void allStatesOfAHiddenBlockAreMasked() {
        var section = newSection();
        fillAll(section, Blocks.DEEPSLATE.defaultBlockState());
        section.setBlockState(1, 1, 1, Blocks.REDSTONE_ORE.defaultBlockState().setValue(BlockStateProperties.LIT, true));
        section.setBlockState(2, 2, 2, Blocks.REDSTONE_ORE.defaultBlockState());

        var masked = XrayMaskFilter.maskCopy(section, 0, mask(2048, "redstone_ore"), FallbackKind.OVERWORLD);

        assertEquals(0, countCells(masked, s -> s.is(Blocks.REDSTONE_ORE)),
                "hiding a block must hide ALL its states (lit and unlit)");
        assertEquals(4096, countCells(masked, s -> s.is(Blocks.DEEPSLATE)));
    }

    @Test
    void oreFreeSectionReturnsTheSameInstance() {
        var section = newSection();
        fillAll(section, Blocks.STONE.defaultBlockState());
        assertSame(section, XrayMaskFilter.maskCopy(section, 0, defaultMask(), FallbackKind.OVERWORLD),
                "the palette fast path must not copy ore-free sections");
    }

    @Test
    void sectionAtOrAboveCutoffReturnsTheSameInstance() {
        var section = newSection();
        fillAll(section, Blocks.STONE.defaultBlockState());
        sprinkle(section, Blocks.GOLD_ORE.defaultBlockState(), 50, 0);

        // Cutoff 64: sectionY 4 starts AT world 64 — entirely public in vanilla packets.
        assertSame(section, XrayMaskFilter.maskCopy(section,
                4, mask(64, "gold_ore"), FallbackKind.OVERWORLD));
        // In-place flavor must gate identically.
        XrayMaskFilter.maskInPlace(section, 4, mask(64, "gold_ore"), FallbackKind.OVERWORLD);
        assertTrue(countCells(section, s -> s.is(Blocks.GOLD_ORE)) > 0);
    }

    @Test
    void straddlingSectionMasksOnlyBelowTheCutoff() {
        var section = newSection();
        fillAll(section, Blocks.STONE.defaultBlockState());
        for (int y = 0; y < 16; y++) section.setBlockState(3, y, 3, Blocks.IRON_ORE.defaultBlockState());

        // sectionY 4 spans world 64..79; cutoff 72 masks local y 0..7 only.
        var masked = XrayMaskFilter.maskCopy(section, 4, mask(72, "iron_ore"), FallbackKind.OVERWORLD);

        for (int y = 0; y < 8; y++) {
            assertTrue(masked.getBlockState(3, y, 3).is(Blocks.STONE),
                    "below the cutoff (world " + (64 + y) + ") must be masked");
        }
        for (int y = 8; y < 16; y++) {
            assertTrue(masked.getBlockState(3, y, 3).is(Blocks.IRON_ORE),
                    "at/above the cutoff (world " + (64 + y) + ") must stay real — vanilla packets already reveal it");
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
            var masked = XrayMaskFilter.maskCopy(section, c.sectionY(),
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
        // Exactly equal counts of the two candidates, rest ore.
        fillAll(section, Blocks.DIAMOND_ORE.defaultBlockState());
        for (int x = 0; x < 8; x++) {
            section.setBlockState(x, 0, 0, granite);
            section.setBlockState(x, 0, 1, diorite);
        }
        var expected = Block.BLOCK_STATE_REGISTRY.getId(granite) < Block.BLOCK_STATE_REGISTRY.getId(diorite)
                ? granite : diorite;

        var masked = XrayMaskFilter.maskCopy(section, 0, mask(2048, "diamond_ore"), FallbackKind.OVERWORLD);

        // Live and NBT-parsed palettes order entries differently for the same content, so a
        // palette-order-dependent winner would break live-vs-disk byte parity.
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

        var copied = XrayMaskFilter.maskCopy(a, 0, m, FallbackKind.OVERWORLD);
        XrayMaskFilter.maskInPlace(b, 0, m, FallbackKind.OVERWORLD);

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

        var copied = XrayMaskFilter.maskCopy(a, 0, m, FallbackKind.OVERWORLD);
        XrayMaskFilter.maskInPlace(b, 0, m, FallbackKind.OVERWORLD);

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
    void negativeSectionHeightGateAndStraddle() {
        // sectionY -4 spans world -64..-49; cutoff -60 masks local y 0..3 only.
        var section = newSection();
        fillAll(section, Blocks.DEEPSLATE.defaultBlockState());
        for (int y = 0; y < 16; y++) section.setBlockState(6, y, 6, Blocks.DEEPSLATE_GOLD_ORE.defaultBlockState());

        var masked = XrayMaskFilter.maskCopy(section, -4, mask(-60, "deepslate_gold_ore"), FallbackKind.OVERWORLD);
        for (int y = 0; y < 4; y++) {
            assertTrue(masked.getBlockState(6, y, 6).is(Blocks.DEEPSLATE),
                    "below the negative cutoff (world " + (-64 + y) + ") must be masked");
        }
        for (int y = 4; y < 16; y++) {
            assertTrue(masked.getBlockState(6, y, 6).is(Blocks.DEEPSLATE_GOLD_ORE),
                    "at/above the negative cutoff (world " + (-64 + y) + ") must stay real");
        }

        // Cutoff at the section's own bottom: fully gated, same instance.
        assertSame(section, XrayMaskFilter.maskCopy(section, -4,
                mask(-64, "deepslate_gold_ore"), FallbackKind.OVERWORLD));
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

        var masked = XrayMaskFilter.maskCopy(section, 0, defaultMask(), FallbackKind.OVERWORLD);

        assertFalse(masked.hasOnlyAir());
        for (int y = 0; y < 16; y++)
            for (int z = 0; z < 16; z++)
                for (int x = 0; x < 16; x++)
                    assertEquals(section.getBlockState(x, y, z).isAir(),
                            masked.getBlockState(x, y, z).isAir(),
                            "air-ness must be preserved at " + x + "," + y + "," + z
                                    + " — nonEmptyBlockCount honesty and the all-air sentinel depend on it");
    }

    // ---- MaskSet resolution ----

    @Test
    void unknownIdsAreSkippedAndAnAllUnknownMaskIsInert() {
        var m = mask(2048, "not_a_block", "definitely:missing", "diamond_ore");
        assertFalse(m.isEmpty());
        assertTrue(m.contains(Blocks.DIAMOND_ORE.defaultBlockState()));

        var allUnknown = mask(2048, "not_a_block");
        assertTrue(allUnknown.isEmpty());
        var section = newSection();
        fillAll(section, Blocks.DIAMOND_ORE.defaultBlockState());
        assertSame(section, XrayMaskFilter.maskCopy(section, 0, allUnknown, FallbackKind.OVERWORLD),
                "a mask that resolved nothing must no-op, not serve a false sense of cover");
    }

    @Test
    void nullAndBlankListEntriesAreSkippedWithoutThrowing() {
        // GSON deserializes ["iron_ore", null] verbatim; a throw here would escape into
        // serve choke points (generation's catch turns it session-permanent NOT_GENERATED).
        var entries = new java.util.ArrayList<String>();
        entries.add(null);
        entries.add("  ");
        entries.add("iron_ore");
        var m = XrayMaskFilter.MaskSet.resolve(entries, 2048);
        assertFalse(m.isEmpty());
        assertTrue(m.contains(Blocks.IRON_ORE.defaultBlockState()));
    }

    @Test
    void fromStatesDropsAirAndNulls() {
        // The engine-adoption twin of the id path's air guard — never-air is load-bearing
        // (count headers + the all-air sentinel).
        var states = new java.util.ArrayList<net.minecraft.world.level.block.state.BlockState>();
        states.add(Blocks.AIR.defaultBlockState());
        states.add(null);
        states.add(Blocks.DIAMOND_ORE.defaultBlockState());
        var m = XrayMaskFilter.MaskSet.fromStates(states, 2048);
        assertFalse(m.isEmpty());
        assertTrue(m.contains(Blocks.DIAMOND_ORE.defaultBlockState()));
        assertFalse(m.contains(Blocks.AIR.defaultBlockState()));

        assertTrue(XrayMaskFilter.MaskSet.fromStates(
                        List.of(Blocks.AIR.defaultBlockState()), 2048).isEmpty(),
                "an all-air adoption must be inert, not a false sense of cover");
    }

    @Test
    void bareAndNamespacedIdsBothResolve() {
        assertTrue(mask(2048, "diamond_ore").contains(Blocks.DIAMOND_ORE.defaultBlockState()));
        assertTrue(mask(2048, "minecraft:diamond_ore").contains(Blocks.DIAMOND_ORE.defaultBlockState()));
    }

    @Test
    void defaultListCoversTheExpectedPaperSet() {
        var m = defaultMask();
        for (var block : List.of(Blocks.DIAMOND_ORE, Blocks.DEEPSLATE_DIAMOND_ORE, Blocks.IRON_ORE,
                Blocks.RAW_IRON_BLOCK, Blocks.CHEST, Blocks.ENDER_CHEST, Blocks.OBSIDIAN, Blocks.CLAY)) {
            assertTrue(m.contains(block.defaultBlockState()), block + " must be in the default mask");
        }
        assertFalse(m.contains(Blocks.STONE.defaultBlockState()));
        assertFalse(m.contains(Blocks.ANCIENT_DEBRIS.defaultBlockState()),
                "Paper's engine-mode-1 default does NOT hide nether ores — the list is a verbatim copy");
    }

    // ---- golden fixture (cross-module parity via the corpus diff test) ----

    /**
     * Serializes three deterministic masked sections into the {@code xray-masked} corpus
     * fixture. The Paper twin test builds the IDENTICAL sections through
     * {@code PaperXrayMaskFilter}; the corpus parity test then byte-diffs the two committed
     * fixtures, pinning twin behavior at the byte level (same wire grammar as the
     * serializers: varint count, then per section byte Y + section bytes + two light flags).
     */
    @Test
    void goldenMaskedFixture() throws IOException {
        var m = XrayMaskFilter.MaskSet.resolve(ServerConfigBase.defaultXrayHiddenBlocks(), 64);

        // Deep section (world -64..-49): deepslate with deepslate ores.
        var deep = newSection();
        fillAll(deep, Blocks.DEEPSLATE.defaultBlockState());
        sprinkle(deep, Blocks.DEEPSLATE_DIAMOND_ORE.defaultBlockState(), 41, 0);
        sprinkle(deep, Blocks.DEEPSLATE_IRON_ORE.defaultBlockState(), 29, 3);
        XrayMaskFilter.maskInPlace(deep, -4, m, FallbackKind.OVERWORLD);
        assertEquals(0, countCells(deep, s -> s.is(Blocks.DEEPSLATE_DIAMOND_ORE)));

        // Surface-band section (world 0..15): stone, ores incl. a lit redstone state, air roof.
        var mid = newSection();
        fillAll(mid, Blocks.STONE.defaultBlockState());
        sprinkle(mid, Blocks.IRON_ORE.defaultBlockState(), 23, 1);
        mid.setBlockState(5, 5, 5, Blocks.REDSTONE_ORE.defaultBlockState().setValue(BlockStateProperties.LIT, true));
        for (int x = 0; x < 16; x++)
            for (int z = 0; z < 16; z++)
                mid.setBlockState(x, 15, z, Blocks.AIR.defaultBlockState());
        XrayMaskFilter.maskInPlace(mid, 0, m, FallbackKind.OVERWORLD);
        assertEquals(0, countCells(mid, s -> s.is(Blocks.IRON_ORE) || s.is(Blocks.REDSTONE_ORE)));

        // Above the cutoff (world 64..79): gold ore stays real — the height-gate pin.
        var high = newSection();
        fillAll(high, Blocks.STONE.defaultBlockState());
        int highOres = sprinkle(high, Blocks.GOLD_ORE.defaultBlockState(), 61, 2);
        XrayMaskFilter.maskInPlace(high, 4, m, FallbackKind.OVERWORLD);
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

    // ---- golden helpers (same mechanics as NbtSectionSerializerTest) ----

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
            Path nested = dir.resolve("fabric");
            if (Files.isDirectory(nested.resolve("src/test/java/dev/vox/lss"))) {
                return nested.resolve(GOLDEN_DIR).resolve(name + ".bin");
            }
        }
        throw new IllegalStateException("cannot locate the fabric module source tree from "
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
