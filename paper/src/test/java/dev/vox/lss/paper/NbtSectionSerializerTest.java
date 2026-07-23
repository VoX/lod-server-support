package dev.vox.lss.paper;

import com.mojang.serialization.Lifecycle;
import io.netty.buffer.Unpooled;
import net.minecraft.SharedConstants;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Paper mirror of the Fabric {@code dev.vox.lss.networking.server.NbtSectionSerializerTest}.
 * Covers {@link PaperNbtSectionSerializer#serializeChunkNbt} (the region-NBT -> wire-bytes path).
 * Uses the same reference grammar, so it also pins Paper's serializer to the Fabric output.
 */
// new LevelChunkSection(PalettedContainerFactory) is @Deprecated on Paper (anti-xray overload),
// but is the canonical decode ctor used by the client; the 1-arg form is vanilla-identical.
@SuppressWarnings("deprecation")
class NbtSectionSerializerTest {

    // CORPUS_PALETTE below touches Blocks during <clinit>, which runs before @BeforeAll —
    // bootstrap must happen in a static block or the class explodes when it loads first.
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

    /**
     * Twin of the Fabric builder: the golden corpus bytes embed biome palette ids, so ids must
     * be platform- and version-independent — register exactly the corpus biomes in this fixed
     * order (full-vanilla listElements() order differs between the Fabric and Paper test
     * runtimes and skewed the fixtures). Never reorder; if this list changes, regenerate all
     * goldens on BOTH modules.
     */
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

    private CompoundTag chunkNbt(String status, CompoundTag... sections) {
        var c = new CompoundTag();
        if (status != null) c.putString("Status", status);
        var list = new ListTag();
        for (var s : sections) list.add(s);
        c.put("sections", list);
        return c;
    }

    private CompoundTag sectionNbt(int y, boolean stone, boolean includeBiomes, byte[] blockLight, byte[] skyLight) {
        var sec = new LevelChunkSection(FACTORY);
        if (stone) sec.setBlockState(0, 0, 0, Blocks.STONE.defaultBlockState());
        var s = new CompoundTag();
        s.putInt("Y", y);
        s.put("block_states", FACTORY.blockStatesContainerCodec().encodeStart(NbtOps.INSTANCE, sec.getStates()).getOrThrow());
        if (includeBiomes) {
            s.put("biomes", FACTORY.biomeContainerCodec().encodeStart(NbtOps.INSTANCE, sec.getBiomes()).getOrThrow());
        }
        if (blockLight != null) s.putByteArray("BlockLight", blockLight);
        if (skyLight != null) s.putByteArray("SkyLight", skyLight);
        return s;
    }

    private static byte[] light(int index, byte value) {
        byte[] b = new byte[2048];
        b[index] = value;
        return b;
    }

    private record DecodedSection(int y, LevelChunkSection section,
                                  boolean hasBlockLight, byte[] blockLight,
                                  boolean hasSkyLight, byte[] skyLight) {}

    private List<DecodedSection> decode(byte[] wire) {
        var buf = new FriendlyByteBuf(Unpooled.wrappedBuffer(wire));
        try {
            int count = buf.readVarInt();
            var out = new ArrayList<DecodedSection>(count);
            for (int i = 0; i < count; i++) {
                int y = buf.readByte();
                var section = new LevelChunkSection(FACTORY);
                section.read(buf);
                boolean hasBl = buf.readBoolean();
                byte[] bl = null;
                if (hasBl) {
                    bl = new byte[2048];
                    buf.readBytes(bl);
                }
                boolean hasSl = buf.readBoolean();
                byte[] sl = null;
                if (hasSl) {
                    sl = new byte[2048];
                    buf.readBytes(sl);
                }
                out.add(new DecodedSection(y, section, hasBl, bl, hasSl, sl));
            }
            assertEquals(0, buf.readableBytes(), "wire buffer fully drained");
            return out;
        } finally {
            buf.release();
        }
    }

    @Test
    void happyPath_stoneSection_roundTrips() {
        byte[] wire = PaperNbtSectionSerializer.serializeChunkNbt(
                chunkNbt("minecraft:full", sectionNbt(0, true, true, null, null)), REGISTRY_ACCESS);
        var sections = decode(wire);
        assertEquals(1, sections.size());
        assertEquals(0, sections.get(0).y());
        assertFalse(sections.get(0).section().hasOnlyAir());
        assertEquals(Blocks.STONE.defaultBlockState(), sections.get(0).section().getBlockState(0, 0, 0));
    }

    /**
     * The masked disk-path wiring pin (the Paper twin of the Fabric masked parity
     * gametest's disk leg — Paper has no gametest tier): a non-null MaskEntry through the
     * 3-arg {@code serializeChunkNbt} must actually mask (hidden states replaced on the
     * wire, bytes differing from the unmasked 2-arg call). Deleting the maskInPlace block
     * or null-ing the entry silently leaks real ores on Paper disk serves — this is the
     * failing-test path for that mutation.
     */
    @Test
    void maskEntryMasksHiddenStatesOnTheDiskPath() {
        var sec = new LevelChunkSection(FACTORY);
        for (int x = 0; x < 16; x++)
            for (int z = 0; z < 16; z++)
                sec.setBlockState(x, 0, z, Blocks.STONE.defaultBlockState());
        sec.setBlockState(3, 0, 3, Blocks.DIAMOND_ORE.defaultBlockState());
        var s = new CompoundTag();
        s.putInt("Y", 0);
        s.put("block_states", FACTORY.blockStatesContainerCodec()
                .encodeStart(NbtOps.INSTANCE, sec.getStates()).getOrThrow());
        s.put("biomes", FACTORY.biomeContainerCodec()
                .encodeStart(NbtOps.INSTANCE, sec.getBiomes()).getOrThrow());
        var nbt = chunkNbt("minecraft:full", s);

        byte[] unmasked = PaperNbtSectionSerializer.serializeChunkNbt(nbt, REGISTRY_ACCESS);
        var entry = new PaperXrayMaskManager.MaskEntry(
                PaperXrayMaskFilter.MaskSet.resolve(java.util.List.of("diamond_ore"), 2048),
                dev.vox.lss.common.XrayMaskPolicy.FallbackKind.OVERWORLD, "config");
        byte[] masked = PaperNbtSectionSerializer.serializeChunkNbt(nbt, REGISTRY_ACCESS, entry);

        assertFalse(java.util.Arrays.equals(unmasked, masked),
                "a non-null mask entry must change the ore chunk's wire bytes");
        var sections = decode(masked);
        assertEquals(1, sections.size());
        assertEquals(Blocks.STONE.defaultBlockState(), sections.get(0).section().getBlockState(3, 0, 3),
                "the hidden state must be replaced by the dominant state on the wire");
    }

    @Test
    void lightBytesPreserved() {
        byte[] bl = light(0, (byte) 0x10);
        byte[] sl = light(5, (byte) 0x0F);
        byte[] wire = PaperNbtSectionSerializer.serializeChunkNbt(
                chunkNbt("minecraft:full", sectionNbt(0, true, true, bl, sl)), REGISTRY_ACCESS);
        var d = decode(wire).get(0);
        assertTrue(d.hasBlockLight());
        assertArrayEquals(bl, d.blockLight());
        assertTrue(d.hasSkyLight());
        assertArrayEquals(sl, d.skyLight());
    }

    @Test
    void airOnly_withBlockLight_kept() {
        byte[] bl = light(100, (byte) 0x01);
        byte[] wire = PaperNbtSectionSerializer.serializeChunkNbt(
                chunkNbt("minecraft:full", sectionNbt(3, false, true, bl, null)), REGISTRY_ACCESS);
        var sections = decode(wire);
        assertEquals(1, sections.size());
        assertTrue(sections.get(0).section().hasOnlyAir());
        assertTrue(sections.get(0).hasBlockLight());
        assertArrayEquals(bl, sections.get(0).blockLight());
    }

    @Test
    void airOnly_zeroBlockLight_dropped() {
        byte[] wire = PaperNbtSectionSerializer.serializeChunkNbt(
                chunkNbt("minecraft:full", sectionNbt(0, false, true, new byte[2048], new byte[2048])), REGISTRY_ACCESS);
        assertEquals(0, wire.length);
    }

    @Test
    void airOnly_noLightTag_dropped() {
        byte[] wire = PaperNbtSectionSerializer.serializeChunkNbt(
                chunkNbt("minecraft:full", sectionNbt(0, false, true, null, null)), REGISTRY_ACCESS);
        assertEquals(0, wire.length);
    }

    @Test
    void missingBiomes_defaultBiomePath_roundTrips() {
        // Paper takes a divergent default-biome branch (factory.createForBiomes()) — assert it
        // still produces a valid section that round-trips.
        byte[] wire = PaperNbtSectionSerializer.serializeChunkNbt(
                chunkNbt("minecraft:full", sectionNbt(0, true, false, null, null)), REGISTRY_ACCESS);
        var sections = decode(wire);
        assertEquals(1, sections.size());
        assertEquals(Blocks.STONE.defaultBlockState(), sections.get(0).section().getBlockState(0, 0, 0));
        assertTrue(sections.get(0).section().getBiomes().get(0, 0, 0).is(Biomes.PLAINS),
                "missing-biomes NBT defaults to PLAINS (must match Fabric's explicit default)");
    }

    @Test
    void statusNotFull_returnsNull() {
        assertNull(PaperNbtSectionSerializer.serializeChunkNbt(
                chunkNbt("minecraft:features", sectionNbt(0, true, true, null, null)), REGISTRY_ACCESS));
    }

    @Test
    void noSectionsList_returnsNull() {
        var c = new CompoundTag();
        c.putString("Status", "minecraft:full");
        assertNull(PaperNbtSectionSerializer.serializeChunkNbt(c, REGISTRY_ACCESS));
    }

    @Test
    void multiSection_orderAndNegativeY() {
        byte[] wire = PaperNbtSectionSerializer.serializeChunkNbt(
                chunkNbt("minecraft:full",
                        sectionNbt(0, true, true, null, null),
                        sectionNbt(-4, true, true, null, null)),
                REGISTRY_ACCESS);
        var sections = decode(wire);
        assertEquals(2, sections.size());
        assertEquals(0, sections.get(0).y());
        assertEquals(-4, sections.get(1).y());
    }

    @Test
    void statusMissing_returnsNull() {
        assertNull(PaperNbtSectionSerializer.serializeChunkNbt(
                chunkNbt(null, sectionNbt(0, true, true, null, null)), REGISTRY_ACCESS));
    }

    @Test
    void missingBlockStates_sectionDropped() {
        var noStates = new CompoundTag();
        noStates.putInt("Y", 1);
        noStates.put("biomes", FACTORY.biomeContainerCodec()
                .encodeStart(NbtOps.INSTANCE, new LevelChunkSection(FACTORY).getBiomes()).getOrThrow());
        byte[] wire = PaperNbtSectionSerializer.serializeChunkNbt(
                chunkNbt("minecraft:full", noStates, sectionNbt(2, true, true, null, null)), REGISTRY_ACCESS);
        var sections = decode(wire);
        assertEquals(1, sections.size());
        assertEquals(2, sections.get(0).y());
    }

    @Test
    void sectionMissingY_skipped() {
        var noY = new CompoundTag();
        noY.put("block_states", FACTORY.blockStatesContainerCodec()
                .encodeStart(NbtOps.INSTANCE, new LevelChunkSection(FACTORY).getStates()).getOrThrow());
        byte[] wire = PaperNbtSectionSerializer.serializeChunkNbt(
                chunkNbt("minecraft:full", noY, sectionNbt(7, true, true, null, null)), REGISTRY_ACCESS);
        var sections = decode(wire);
        assertEquals(1, sections.size());
        assertEquals(7, sections.get(0).y());
    }

    @Test
    void airOnly_skyLightOnly_dropped() {
        byte[] wire = PaperNbtSectionSerializer.serializeChunkNbt(
                chunkNbt("minecraft:full", sectionNbt(3, false, true, null, light(100, (byte) 0x0F))), REGISTRY_ACCESS);
        assertEquals(0, wire.length, "air-only with only SkyLight (no BlockLight) is dropped");
    }

    @Test
    void stoneSection_allZeroSavedLight_omitsLightLayer() {
        // Vanilla saves the light engine's allocated-but-zeroed arrays; "absent" means
        // all-zero on the wire, so a Paper disk serve must byte-match a live serve of the
        // same content (and Fabric's output) — pins the hasNonZeroNibble guards.
        byte[] wire = PaperNbtSectionSerializer.serializeChunkNbt(
                chunkNbt("minecraft:full", sectionNbt(0, true, true, new byte[2048], new byte[2048])), REGISTRY_ACCESS);
        var sections = decode(wire);
        assertEquals(1, sections.size(), "non-air section is kept");
        assertEquals(Blocks.STONE.defaultBlockState(), sections.get(0).section().getBlockState(0, 0, 0));
        assertFalse(sections.get(0).hasBlockLight(), "all-zero saved BlockLight is omitted, not shipped");
        assertFalse(sections.get(0).hasSkyLight(), "all-zero saved SkyLight is omitted, not shipped");
    }

    @Test
    void corruptLightLength_skippedWithoutWireDesync() {
        // Third-party world converters emit non-2048-byte light arrays; writing one raw would
        // shift every byte after it and desync the client decoder for the rest of the column.
        byte[] shortBlockLight = new byte[1024];
        shortBlockLight[0] = 0x0F;
        byte[] longSkyLight = new byte[4096];
        longSkyLight[0] = 0x0F;
        byte[] validSky = light(9, (byte) 0x0F);
        byte[] wire = PaperNbtSectionSerializer.serializeChunkNbt(
                chunkNbt("minecraft:full",
                        sectionNbt(0, true, true, shortBlockLight, longSkyLight),
                        sectionNbt(1, true, true, null, validSky)),
                REGISTRY_ACCESS);
        var sections = decode(wire); // decode() asserts the buffer drains exactly
        assertEquals(2, sections.size());
        assertFalse(sections.get(0).hasBlockLight(), "1024-byte BlockLight is skipped, not written");
        assertFalse(sections.get(0).hasSkyLight(), "4096-byte SkyLight is skipped, not written");
        assertEquals(Blocks.STONE.defaultBlockState(), sections.get(0).section().getBlockState(0, 0, 0),
                "block data of the corrupt-light section still serves");
        assertTrue(sections.get(1).hasSkyLight(), "valid light after the corrupt section still decodes");
        assertArrayEquals(validSky, sections.get(1).skyLight());
    }

    @Test
    void airOnly_corruptLightLength_sectionDropped() {
        byte[] shortLight = new byte[1024];
        shortLight[5] = 0x0F; // non-zero, but wrong length cannot rescue an air-only section
        byte[] wire = PaperNbtSectionSerializer.serializeChunkNbt(
                chunkNbt("minecraft:full", sectionNbt(2, false, true, shortLight, null)), REGISTRY_ACCESS);
        assertEquals(0, wire.length, "air-only section with malformed light is dropped, not served");
    }

    @Test
    void multiBlockSection_paletteRoundTrips() {
        var states = FACTORY.createForBlockStates();
        states.set(0, 0, 0, Blocks.STONE.defaultBlockState());
        states.set(15, 15, 15, Blocks.DIRT.defaultBlockState());
        states.set(7, 8, 9, Blocks.GLASS.defaultBlockState());
        var section = decode(PaperNbtSectionSerializer.serializeChunkNbt(
                chunkNbt("minecraft:full", sectionFrom(1, states, FACTORY.createForBiomes())), REGISTRY_ACCESS))
                .get(0).section();
        assertEquals(Blocks.STONE.defaultBlockState(), section.getBlockState(0, 0, 0));
        assertEquals(Blocks.DIRT.defaultBlockState(), section.getBlockState(15, 15, 15));
        assertEquals(Blocks.GLASS.defaultBlockState(), section.getBlockState(7, 8, 9), "multi-entry palette round-trips");
    }

    @Test
    void biomeData_roundTrips() {
        var states = FACTORY.createForBlockStates();
        states.set(0, 0, 0, Blocks.STONE.defaultBlockState());
        var biomes = FACTORY.createForBiomes();
        biomes.set(0, 0, 0, REGISTRY_ACCESS.lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.DESERT));
        var section = decode(PaperNbtSectionSerializer.serializeChunkNbt(
                chunkNbt("minecraft:full", sectionFrom(0, states, biomes)), REGISTRY_ACCESS))
                .get(0).section();
        assertTrue(section.getBiomes().get(0, 0, 0).is(Biomes.DESERT), "non-default biome (DESERT) survives the round-trip");
        assertEquals(Blocks.STONE.defaultBlockState(), section.getBlockState(0, 0, 0));
    }

    private CompoundTag sectionFrom(int y, net.minecraft.world.level.chunk.PalettedContainer<net.minecraft.world.level.block.state.BlockState> states,
                                    net.minecraft.world.level.chunk.PalettedContainer<net.minecraft.core.Holder<Biome>> biomes) {
        var s = new CompoundTag();
        s.putInt("Y", y);
        s.put("block_states", FACTORY.blockStatesContainerCodec().encodeStart(NbtOps.INSTANCE, states).getOrThrow());
        s.put("biomes", FACTORY.biomeContainerCodec().encodeStart(NbtOps.INSTANCE, biomes).getOrThrow());
        return s;
    }

    @Test
    void sectionY_signedByteRange_extremesRoundTrip() {
        // The wire carries sectionY as ONE signed byte (writeByte/readByte): -128 and 127
        // are the representable extremes and must survive sign extension on decode.
        byte[] wire = PaperNbtSectionSerializer.serializeChunkNbt(
                chunkNbt("minecraft:full",
                        sectionNbt(-128, true, true, null, null),
                        sectionNbt(127, true, true, null, null)),
                REGISTRY_ACCESS);
        var sections = decode(wire);
        assertEquals(2, sections.size());
        assertEquals(-128, sections.get(0).y(), "-128 survives the signed-byte write/read");
        assertEquals(127, sections.get(1).y(), "127 survives the signed-byte write/read");
    }

    @Test
    void nonCompoundSectionElement_failsWholeChunk() {
        // Each sections-list element is blindly cast to CompoundTag (PaperNbtSectionSerializer
        // first pass). A non-compound element — NBT lists are heterogeneous since 1.21.5,
        // so a corrupt region or rogue tool can produce one — throws CCE out of
        // serializeChunkNbt; AbstractChunkDiskReader.readAndDeliver catches it on the
        // reader thread, counts an error, and answers the not-found envelope. Pinned:
        // the WHOLE chunk resolves NOT_GENERATED — surviving siblings do not serve
        // (contrast with the per-section skip of malformed block_states below).
        var corrupt = new CompoundTag();
        corrupt.putString("Status", "minecraft:full");
        var list = new ListTag();
        list.add(sectionNbt(0, true, true, null, null));
        list.add(StringTag.valueOf("not-a-section"));
        list.add(sectionNbt(2, true, true, null, null));
        corrupt.put("sections", list);
        assertThrows(ClassCastException.class,
                () -> PaperNbtSectionSerializer.serializeChunkNbt(corrupt, REGISTRY_ACCESS));
    }

    @Test
    void malformedBlockStates_codecParseError_sectionSkippedSiblingsServe() {
        // block_states PRESENT but unparseable (unknown block id — a world touched by a
        // newer or modded version): the codec returns a DataResult error and parseSection
        // resolves it via result().orElse(null), never getOrThrow, so only that section is
        // skipped and its siblings still serve. There is deliberately NO counter for these
        // silent partials — pinned so a refactor to getOrThrow (whole chunk lost) or a
        // surprise counter fails loudly here.
        var malformed = new CompoundTag();
        malformed.putInt("Y", 1);
        var badStates = new CompoundTag();
        var palette = new ListTag();
        var entry = new CompoundTag();
        entry.putString("Name", "lss:no_such_block");
        palette.add(entry);
        badStates.put("palette", palette);
        malformed.put("block_states", badStates);

        byte[] wire = PaperNbtSectionSerializer.serializeChunkNbt(
                chunkNbt("minecraft:full", malformed, sectionNbt(2, true, true, null, null)),
                REGISTRY_ACCESS);
        var sections = decode(wire);
        assertEquals(1, sections.size(), "malformed-block_states section skipped, sibling serves");
        assertEquals(2, sections.get(0).y());
        assertEquals(Blocks.STONE.defaultBlockState(), sections.get(0).section().getBlockState(0, 0, 0));
    }

    // ---- Golden wire-byte corpus (cross-module parity) ----
    //
    // Twin of the Fabric NbtSectionSerializerTest corpus: the IDENTICAL deterministic
    // in-code chunk NBT cases, serialized by PaperNbtSectionSerializer and byte-compared
    // against identically-named fixtures in THIS module's src/test/resources/nbt-corpus/.
    // goldenCorpusIsByteIdenticalToTheFabricTwin diffs the committed fabric and paper
    // fixture copies for cross-module parity, so any byte drift between the two
    // serializers (or across an MC version bump regenerated per-module) fails here.
    //
    // Goldens are NEVER authored by hand. To (re)generate: run these tests with
    // -Dlss.regenGoldens=true on the test JVM (env LSS_REGEN_GOLDENS=true also works; with
    // Gradle use --no-daemon so the env reaches the forked test worker) — the run writes
    // the fixtures and fails with "re-run"; then re-run without the flag and commit.

    private static final String GOLDEN_DIR = "src/test/resources/nbt-corpus";

    private static boolean regenGoldens() {
        return Boolean.getBoolean("lss.regenGoldens")
                || "true".equalsIgnoreCase(System.getenv("LSS_REGEN_GOLDENS"));
    }

    /** Locate this module's source tree regardless of the test JVM's working directory
     *  (module dir under Gradle; walks up from run/build dirs; repo root via the nested probe). */
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

    @Test
    void goldenCorpusIsByteIdenticalToTheFabricTwin() throws IOException {
        // The corpus exists for CROSS-MODULE parity, but each module's golden tests only
        // self-compare against their OWN fixtures — and regeneration (-Dlss.regenGoldens)
        // rewrites each module's fixtures from its own serializer. Without this test, an
        // MC bump that makes the two serializers drift lets a developer regenerate both
        // fixture sets and commit the divergence green; Fabric clients would then
        // mis-decode Paper servers' disk-read columns.
        Path paperDir = goldenPath("probe").getParent();
        Path fabricDir = locateRepoRelative("fabric/src/test/resources/nbt-corpus");
        java.util.Map<String, Path> paper = corpusFiles(paperDir);
        java.util.Map<String, Path> fabric = corpusFiles(fabricDir);
        assertEquals(fabric.keySet(), paper.keySet(),
                "both modules must carry the same corpus cases");
        assertFalse(paper.isEmpty(), "corpus must not be empty");
        for (var entry : paper.entrySet()) {
            long mismatch = Files.mismatch(entry.getValue(), fabric.get(entry.getKey()));
            assertEquals(-1L, mismatch, () -> entry.getKey()
                    + ": fabric and paper fixtures diverge at byte " + mismatch
                    + " — the two serializers no longer produce identical wire bytes;"
                    + " regenerate on BOTH modules from the same code state and fix the drift");
        }
    }

    private static Path locateRepoRelative(String repoRelative) {
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 6 && dir != null; i++, dir = dir.getParent()) {
            Path candidate = dir.resolve(repoRelative);
            if (Files.exists(candidate)) return candidate;
        }
        throw new IllegalStateException("cannot locate " + repoRelative + " above "
                + Path.of("").toAbsolutePath());
    }

    private static java.util.Map<String, Path> corpusFiles(Path dir) throws IOException {
        try (var stream = Files.list(dir)) {
            return stream.filter(f -> f.getFileName().toString().endsWith(".bin"))
                    .collect(java.util.stream.Collectors.toMap(
                            f -> f.getFileName().toString(), f -> f));
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
                    + " (golden " + expected.length + " B, actual " + wire.length + " B). If intentional"
                    + " (MC bump / deliberate wire change), regenerate with -Dlss.regenGoldens=true on BOTH"
                    + " the fabric and paper modules and verify the two fixture copies still byte-match.");
        }
    }

    // Corpus builders — twin-identical in the Fabric NbtSectionSerializerTest. Any edit here
    // must be mirrored there and all goldens regenerated on both modules.

    private static final Block[] CORPUS_PALETTE = {
            Blocks.STONE, Blocks.DIRT, Blocks.GRASS_BLOCK, Blocks.GLASS,
            Blocks.OAK_PLANKS, Blocks.SAND, Blocks.GRAVEL, Blocks.GRANITE};

    @Test
    void golden_multiSection_listOrderPreserved() throws IOException {
        var bottom = FACTORY.createForBlockStates();
        bottom.set(0, 0, 0, Blocks.STONE.defaultBlockState());
        var middle = FACTORY.createForBlockStates();
        middle.set(8, 8, 8, Blocks.DIRT.defaultBlockState());
        var top = FACTORY.createForBlockStates();
        top.set(15, 15, 15, Blocks.GLASS.defaultBlockState());
        byte[] wire = PaperNbtSectionSerializer.serializeChunkNbt(
                chunkNbt("minecraft:full",
                        sectionFrom(0, middle, FACTORY.createForBiomes()),
                        sectionFrom(-4, bottom, FACTORY.createForBiomes()),
                        sectionFrom(7, top, FACTORY.createForBiomes())),
                REGISTRY_ACCESS);
        var sections = decode(wire);
        assertEquals(3, sections.size());
        assertEquals(0, sections.get(0).y(), "NBT list order is preserved, never sorted by Y");
        assertEquals(-4, sections.get(1).y());
        assertEquals(7, sections.get(2).y());
        assertMatchesGolden("multi-section", wire);
    }

    @Test
    void golden_negativeYSections() throws IOException {
        byte[] wire = PaperNbtSectionSerializer.serializeChunkNbt(
                chunkNbt("minecraft:full",
                        sectionNbt(-128, true, true, null, null),
                        sectionNbt(-1, true, true, null, null)),
                REGISTRY_ACCESS);
        var sections = decode(wire);
        assertEquals(2, sections.size());
        assertEquals(-128, sections.get(0).y());
        assertEquals(-1, sections.get(1).y());
        assertMatchesGolden("negative-y", wire);
    }

    @Test
    void golden_multiPaletteFullSection() throws IOException {
        var states = FACTORY.createForBlockStates();
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    states.set(x, y, z, CORPUS_PALETTE[(x + 3 * z + 5 * y) % CORPUS_PALETTE.length].defaultBlockState());
                }
            }
        }
        byte[] wire = PaperNbtSectionSerializer.serializeChunkNbt(
                chunkNbt("minecraft:full", sectionFrom(2, states, FACTORY.createForBiomes())),
                REGISTRY_ACCESS);
        var section = decode(wire).get(0).section();
        assertEquals(Blocks.STONE.defaultBlockState(), section.getBlockState(0, 0, 0));
        assertEquals(Blocks.DIRT.defaultBlockState(), section.getBlockState(1, 1, 1), "(1 + 3*1 + 5*1) % 8 = 1 = DIRT");
        assertMatchesGolden("multi-palette", wire);
    }

    @Test
    void golden_nonDefaultBiomePattern() throws IOException {
        var biomeRegistry = REGISTRY_ACCESS.lookupOrThrow(Registries.BIOME);
        var corpusBiomes = List.of(Biomes.DESERT, Biomes.JUNGLE, Biomes.SNOWY_TAIGA);
        var biomes = FACTORY.createForBiomes();
        for (int qy = 0; qy < 4; qy++) {
            for (int qz = 0; qz < 4; qz++) {
                for (int qx = 0; qx < 4; qx++) {
                    biomes.set(qx, qy, qz, biomeRegistry.getOrThrow(corpusBiomes.get((qx + qz + qy) % 3)));
                }
            }
        }
        var states = FACTORY.createForBlockStates();
        states.set(0, 0, 0, Blocks.STONE.defaultBlockState());
        byte[] wire = PaperNbtSectionSerializer.serializeChunkNbt(
                chunkNbt("minecraft:full", sectionFrom(0, states, biomes)), REGISTRY_ACCESS);
        var section = decode(wire).get(0).section();
        assertTrue(section.getBiomes().get(0, 0, 0).is(Biomes.DESERT));
        assertTrue(section.getBiomes().get(1, 0, 0).is(Biomes.JUNGLE));
        assertTrue(section.getBiomes().get(2, 0, 0).is(Biomes.SNOWY_TAIGA));
        assertMatchesGolden("non-default-biomes", wire);
    }

    @Test
    void golden_lightPresenceCombos_fourSectionBlob() throws IOException {
        // All four presence combos in ONE column: any mis-written flag desyncs every byte
        // after it, so the exact-drain decode plus the per-section asserts pin the full
        // (BL,SL) flag grammar in a single stream (not just per-combo in isolation).
        byte[] bl0 = light(0, (byte) 0x11);
        byte[] sl0 = light(1, (byte) 0x22);
        byte[] bl1 = light(2, (byte) 0x33);
        byte[] sl2 = light(3, (byte) 0x44);
        byte[] wire = PaperNbtSectionSerializer.serializeChunkNbt(
                chunkNbt("minecraft:full",
                        sectionNbt(0, true, true, bl0, sl0),
                        sectionNbt(1, true, true, bl1, null),
                        sectionNbt(2, true, true, null, sl2),
                        sectionNbt(3, true, true, null, null)),
                REGISTRY_ACCESS);
        var sections = decode(wire);
        assertEquals(4, sections.size());
        assertTrue(sections.get(0).hasBlockLight());
        assertTrue(sections.get(0).hasSkyLight());
        assertArrayEquals(bl0, sections.get(0).blockLight());
        assertArrayEquals(sl0, sections.get(0).skyLight());
        assertTrue(sections.get(1).hasBlockLight());
        assertFalse(sections.get(1).hasSkyLight());
        assertArrayEquals(bl1, sections.get(1).blockLight());
        assertFalse(sections.get(2).hasBlockLight());
        assertTrue(sections.get(2).hasSkyLight());
        assertArrayEquals(sl2, sections.get(2).skyLight());
        assertFalse(sections.get(3).hasBlockLight());
        assertFalse(sections.get(3).hasSkyLight());
        assertMatchesGolden("light-combos", wire);
    }

    @Test
    void golden_waterloggedBlockStateProperty() throws IOException {
        var states = FACTORY.createForBlockStates();
        states.set(0, 0, 0, Blocks.OAK_STAIRS.defaultBlockState().setValue(BlockStateProperties.WATERLOGGED, true));
        states.set(1, 0, 0, Blocks.OAK_STAIRS.defaultBlockState());
        states.set(2, 0, 0, Blocks.STONE.defaultBlockState());
        byte[] wire = PaperNbtSectionSerializer.serializeChunkNbt(
                chunkNbt("minecraft:full", sectionFrom(1, states, FACTORY.createForBiomes())),
                REGISTRY_ACCESS);
        var section = decode(wire).get(0).section();
        assertTrue(section.getBlockState(0, 0, 0).getValue(BlockStateProperties.WATERLOGGED),
                "waterlogged=true property survives the palette round-trip");
        assertFalse(section.getBlockState(1, 0, 0).getValue(BlockStateProperties.WATERLOGGED),
                "the two stair variants stay distinct palette entries");
        assertMatchesGolden("waterlogged", wire);
    }

    @Test
    void golden_uniform15SkyLight() throws IOException {
        // The overworld daylight shape: a full 2048-byte 0xFF layer must ship verbatim
        // (no homogeneous-layer special case may creep into the wire format).
        byte[] sky = new byte[2048];
        Arrays.fill(sky, (byte) 0xFF);
        byte[] wire = PaperNbtSectionSerializer.serializeChunkNbt(
                chunkNbt("minecraft:full", sectionNbt(4, true, true, null, sky)), REGISTRY_ACCESS);
        var d = decode(wire).get(0);
        assertFalse(d.hasBlockLight());
        assertTrue(d.hasSkyLight());
        assertArrayEquals(sky, d.skyLight());
        assertMatchesGolden("uniform-15-sky", wire);
    }
}
