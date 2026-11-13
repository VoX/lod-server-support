package dev.vox.lss.paper;

import com.mojang.serialization.Lifecycle;
import dev.vox.lss.common.processing.ColumnDecodeNotes;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
     * The corpus-fixed biome RegistryAccess — extracted to {@link CorpusRegistryAccess}
     * at C2 (twin of the Fabric extraction) so the translation-chain suite
     * ({@code PaperLegacyEgressTest}) provably decodes against the same registry these
     * fixtures were generated with. The append-only discipline lives on the helper.
     */
    private static RegistryAccess buildRegistryAccess() {
        return CorpusRegistryAccess.build();
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

    /** v20 wire -> the native view every assertion below predates (exact inverse
     *  resolvers over the SAME registries the emit used). Since C2 this decodes through
     *  the PRODUCTION inverse statics (review C1-15); independence is anchored by
     *  {@code PaperLegacyEgressTest}'s chain against the FROZEN committed goldens. */
    private static byte[] toNativeForTest(byte[] v20Wire) {
        // C2 (review C1-15): the exact inverses are production statics now — decode
        // through the same tables the legacy egress translators use.
        var blockInverse = PaperIdentityTables.blockIdsByIdentity();
        return dev.vox.lss.common.wire.V20ToNativeTranslator.translate(v20Wire,
                ident -> blockInverse.getOrDefault(ident, -1),
                PaperNbtSectionSerializer.biomeIdLookup(REGISTRY_ACCESS),
                net.minecraft.world.level.block.Block.BLOCK_STATE_REGISTRY.size(),
                PaperNbtSectionSerializer.biomeIdCount(REGISTRY_ACCESS));
    }

    private List<DecodedSection> decode(byte[] v20Wire) {
        byte[] wire = toNativeForTest(v20Wire);
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
     * wire, bytes differing from the unmasked 2-arg call). Deleting the mask block
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
        // block_states PRESENT with an unknown block id: under resultOrPartial the codec
        // recovers a PARTIAL (the unknown entry substitutes air), so this single-entry
        // palette parses to an all-air section, which the air/no-light gate then drops —
        // the sibling serves and nothing is condemned. (Pre-round-2 this took the strict
        // result() drop; the observable outcome for THIS shape is identical.)
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

    @Test
    void renamedBlockPaletteEntry_sectionKeptWithAirSubstitution() {
        // The vanilla-upgrade case (R2-1) — see the Fabric twin: raw pre-DFU NBT with a
        // renamed block id keeps the section, substituting air for the unknown entry.
        var sec = new LevelChunkSection(FACTORY);
        sec.setBlockState(0, 0, 0, Blocks.STONE.defaultBlockState());
        sec.setBlockState(1, 0, 0, Blocks.DIRT.defaultBlockState());
        var s = new CompoundTag();
        s.putInt("Y", 1);
        s.put("block_states", FACTORY.blockStatesContainerCodec()
                .encodeStart(NbtOps.INSTANCE, sec.getStates()).getOrThrow());
        var palette = s.getCompound("block_states").orElseThrow().getList("palette").orElseThrow();
        boolean renamed = false;
        for (var e : palette) {
            var pe = (CompoundTag) e;
            if ("minecraft:dirt".equals(pe.getStringOr("Name", ""))) {
                pe.putString("Name", "lss:renamed_away");
                renamed = true;
            }
        }
        assertTrue(renamed, "premise: the dirt palette entry was renamed");

        byte[] wire = PaperNbtSectionSerializer.serializeChunkNbt(
                chunkNbt("minecraft:full", s), REGISTRY_ACCESS);
        var sections = decode(wire);
        assertEquals(1, sections.size(), "the renamed-palette section is KEPT, not dropped");
        assertEquals(Blocks.STONE.defaultBlockState(), sections.get(0).section().getBlockState(0, 0, 0),
                "known palette entries keep their states");
        assertEquals(Blocks.AIR.defaultBlockState(), sections.get(0).section().getBlockState(1, 0, 0),
                "the unknown entry's cells substitute air (vanilla's own leniency)");
    }

    @Test
    void trulyUnparseableSection_condemnsTheColumnAsAuthoritativeMiss() {
        // No partial exists (structural corruption) — see the Fabric twin: the whole
        // column resolves null (authoritative miss) instead of serving with a silently
        // missing section or, worse, a cache-wiping 0-section CLEAR.
        var bad = new CompoundTag();
        bad.putInt("Y", 1);
        var badStates = new CompoundTag();
        badStates.putInt("palette", 7); // not even a list — no partial recoverable
        bad.put("block_states", badStates);

        assertNull(PaperNbtSectionSerializer.serializeChunkNbt(
                chunkNbt("minecraft:full", bad, sectionNbt(2, true, true, null, null)),
                REGISTRY_ACCESS),
                "a truly-unparseable section condemns the whole column, not just itself");
    }

    @Test
    void outOfWorldSections_droppedOnTheRangedProductionPath() {
        // See the Fabric twin: vanilla's light-only cap entries (blockRange±1) drop on
        // the ranged production path (live/disk parity); the range-free path is unchanged.
        var capEntry = new CompoundTag();
        capEntry.putInt("Y", 5);
        capEntry.putByteArray("SkyLight", light(0, (byte) 15));
        var chunk = chunkNbt("minecraft:full",
                sectionNbt(4, true, true, null, light(0, (byte) 15)), capEntry);

        byte[] ranged = PaperNbtSectionSerializer.serializeChunkNbt(chunk, REGISTRY_ACCESS, null, 0, 4);
        assertEquals(List.of(4), decode(ranged).stream().map(DecodedSection::y).toList(),
                "the out-of-range cap entry is dropped on the ranged path");

        byte[] unranged = PaperNbtSectionSerializer.serializeChunkNbt(chunk, REGISTRY_ACCESS);
        assertEquals(List.of(4, 5), decode(unranged).stream().map(DecodedSection::y).toList(),
                "the range-free path (tests/corpus goldens) is unchanged");
    }

    @Test
    void outOfRangeGarbageNeverCondemnsAndTheMinSideAlsoGates() {
        // See the Fabric twin: the range gate runs BEFORE parse, so out-of-range garbage
        // neither condemns the column nor serves, on both range sides.
        var garbage = new CompoundTag();
        garbage.putInt("Y", 5); // beyond max=4
        var badStates = new CompoundTag();
        badStates.putInt("palette", 7); // not even a list — no partial recoverable
        garbage.put("block_states", badStates);

        var belowMin = new CompoundTag(); // light-only entry at minSection-1
        belowMin.putInt("Y", -1);
        belowMin.putByteArray("SkyLight", light(0, (byte) 15));

        var chunk = chunkNbt("minecraft:full",
                belowMin, sectionNbt(4, true, true, null, light(0, (byte) 15)), garbage);

        byte[] ranged = PaperNbtSectionSerializer.serializeChunkNbt(chunk, REGISTRY_ACCESS, null, 0, 4);
        assertNotNull(ranged,
                "out-of-range garbage must not condemn a column the gate drops it from");
        assertEquals(List.of(4), decode(ranged).stream().map(DecodedSection::y).toList(),
                "both sides gate: min-1 and max+1 dropped, the in-range section serves");
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

    // v20-corpus since C1 (see the Fabric twin); the NATIVE nbt-corpus stays committed
    // as a frozen fixture (its generator now emits v20) for C2's translation targets.
    private static final String GOLDEN_DIR = "src/test/resources/v20-corpus";

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
        Path fabricDir = locateRepoRelative("fabric/src/test/resources/v20-corpus");
        java.util.Map<String, Path> paper = corpusFiles(paperDir);
        java.util.Map<String, Path> fabric = corpusFiles(fabricDir);
        assertEquals(fabric.keySet(), paper.keySet(),
                "both modules must carry the same corpus cases");
        assertFalse(paper.isEmpty(), "corpus must not be empty");
        for (var entry : paper.entrySet()) {
            if (dev.vox.lss.common.wire.NativeSectionShape.familiesFoldIdentically()) {
                long mismatch = Files.mismatch(entry.getValue(), fabric.get(entry.getKey()));
                assertEquals(-1L, mismatch, () -> entry.getKey()
                        + ": fabric and paper fixtures diverge at byte " + mismatch
                        + " — the two serializers no longer produce identical wire bytes;"
                        + " regenerate on BOTH modules from the same code state and fix the drift");
            } else {
                // The S1-derived flip (recorded 1.21.11 flavor): the two families FOLD
                // the count header differently on this line (vanilla vs Moonrise recalc),
                // so byte identity is structurally impossible — compare with the count
                // headers normalized to zero; everything else must still match exactly.
                org.junit.jupiter.api.Assertions.assertArrayEquals(
                        countNormalized(Files.readAllBytes(fabric.get(entry.getKey()))),
                        countNormalized(Files.readAllBytes(entry.getValue())),
                        entry.getKey() + ": fixtures diverge beyond the per-family count"
                                + " headers — a real serializer drift");
            }
        }
    }

    /** Re-emits a v20 corpus body with every section's count header zeroed — the
     *  strict-vs-normalized parity's normalized arm (dormant while the families fold
     *  identically; exercised on 1-short lines). */
    private static byte[] countNormalized(byte[] body) {
        var column = dev.vox.lss.common.wire.WireSectionCursor.parse(
                body, dev.vox.lss.common.wire.WireSectionCursor.Layout.V20);
        var sections = new java.util.ArrayList
                <dev.vox.lss.common.wire.WireSectionCursor.WireSection>(column.sections().size());
        for (var s : column.sections()) {
            sections.add(new dev.vox.lss.common.wire.WireSectionCursor.WireSection(
                    s.sectionY(), 0, 0, s.blocks(), s.biomes(), s.blockLight(), s.skyLight()));
        }
        return dev.vox.lss.common.wire.WireSectionCursor.emit(
                new dev.vox.lss.common.wire.WireSectionCursor.WireColumn(
                        column.dictionary(), sections),
                dev.vox.lss.common.wire.WireSectionCursor.Layout.V20);
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

    // ---- Round-2 transcode goldens (2026-07-29): each pins one rung of the NBT->wire
    // transcoder's palette-width ladder against bytes GENERATED BY THE OBJECT PATH (the
    // fixtures were regenerated before the transcoder landed, so they are the transcoder's
    // ground truth, not its echo).

    @Test
    void golden_twoEntryPaletteBoundary() throws IOException {
        // Palette size 2 stores at FOUR bits, not ceillog2(2)=1 — the block strategy maps
        // ideal widths 1-4 all onto 4-bit linear; the boundary where ideal and stored
        // width diverge (a transcoder computing ceillog2 naively corrupts every 2-entry
        // section here).
        var states = FACTORY.createForBlockStates();
        for (int x = 0; x < 16; x++)
            for (int z = 0; z < 16; z++)
                states.set(x, 0, z, Blocks.STONE.defaultBlockState());
        byte[] wire = PaperNbtSectionSerializer.serializeChunkNbt(
                chunkNbt("minecraft:full", sectionFrom(0, states, FACTORY.createForBiomes())),
                REGISTRY_ACCESS);
        var section = decode(wire).get(0).section();
        assertEquals(Blocks.STONE.defaultBlockState(), section.getBlockState(0, 0, 0));
        assertEquals(Blocks.AIR.defaultBlockState(), section.getBlockState(0, 1, 0));
        assertMatchesGolden("two-entry-boundary", wire);
    }

    @Test
    void golden_hashmapWidePalette() throws IOException {
        // 24 distinct states -> ceillog2 = 5 -> the hashmap palette tier (17-256
        // entries), the first width past linear.
        var pool = Blocks.OAK_STAIRS.getStateDefinition().getPossibleStates();
        assertTrue(pool.size() >= 24, "premise: oak_stairs has at least 24 states");
        var states = FACTORY.createForBlockStates();
        int i = 0;
        for (int y = 0; y < 16; y++)
            for (int z = 0; z < 16; z++)
                for (int x = 0; x < 16; x++)
                    states.set(x, y, z, pool.get(i++ % 24));
        byte[] wire = PaperNbtSectionSerializer.serializeChunkNbt(
                chunkNbt("minecraft:full", sectionFrom(1, states, FACTORY.createForBiomes())),
                REGISTRY_ACCESS);
        var section = decode(wire).get(0).section();
        assertEquals(pool.get(0), section.getBlockState(0, 0, 0));
        assertEquals(pool.get(23), section.getBlockState(7, 0, 1), "cell 23 carries the 24th state");
        assertMatchesGolden("hashmap-wide", wire);
    }

    @Test
    void golden_globalPaletteFallback() throws IOException {
        // 320 distinct states -> past the 256-entry palette ceiling ->
        // Configuration.Global: the wire carries NO palette section, just re-encoded
        // global registry ids at the registry's bit width — the one disk shape whose
        // long array is NOT verbatim-copyable (the transcoder's object-fallback rung).
        var pool = new ArrayList<net.minecraft.world.level.block.state.BlockState>();
        for (var block : List.of(Blocks.OAK_STAIRS, Blocks.SPRUCE_STAIRS, Blocks.BIRCH_STAIRS,
                Blocks.JUNGLE_STAIRS)) {
            pool.addAll(block.getStateDefinition().getPossibleStates());
        }
        assertTrue(pool.size() > 256, "premise: four stair blocks exceed the 256-entry ceiling");
        var states = FACTORY.createForBlockStates();
        int i = 0;
        for (int y = 0; y < 16; y++)
            for (int z = 0; z < 16; z++)
                for (int x = 0; x < 16; x++)
                    states.set(x, y, z, pool.get(i++ % pool.size()));
        byte[] wire = PaperNbtSectionSerializer.serializeChunkNbt(
                chunkNbt("minecraft:full", sectionFrom(3, states, FACTORY.createForBiomes())),
                REGISTRY_ACCESS);
        var section = decode(wire).get(0).section();
        assertEquals(pool.get(0), section.getBlockState(0, 0, 0));
        assertEquals(pool.get(300), section.getBlockState(12, 1, 2), "cell 300 survives the global round-trip");
        assertMatchesGolden("global-palette", wire);
    }

    @Test
    void golden_duplicateAirLenientPalette() throws IOException {
        // Two unknown palette entries (the pre-DFU rename shape) substitute air IN PLACE,
        // so the decoded palette carries DUPLICATE air entries alongside the real one —
        // and the wire ships palette ids in list order, duplicates included
        // (LinearPalette/HashMapPalette write byId 0..size-1, no dedup). Indices never
        // shift: cells of the unknown entries decode as air, known cells keep states.
        var states = FACTORY.createForBlockStates();
        states.set(0, 0, 0, Blocks.STONE.defaultBlockState());
        states.set(1, 0, 0, Blocks.DIRT.defaultBlockState());
        states.set(2, 0, 0, Blocks.GLASS.defaultBlockState());
        var s = sectionFrom(0, states, FACTORY.createForBiomes());
        var palette = s.getCompound("block_states").orElseThrow().getList("palette").orElseThrow();
        int renamed = 0;
        for (var e : palette) {
            var pe = (CompoundTag) e;
            var name = pe.getStringOr("Name", "");
            if ("minecraft:dirt".equals(name)) {
                pe.putString("Name", "lss:gone_one");
                renamed++;
            }
            if ("minecraft:glass".equals(name)) {
                pe.putString("Name", "lss:gone_two");
                renamed++;
            }
        }
        assertEquals(2, renamed, "premise: both entries were renamed away");

        byte[] wire = PaperNbtSectionSerializer.serializeChunkNbt(
                chunkNbt("minecraft:full", s), REGISTRY_ACCESS);
        var section = decode(wire).get(0).section();
        assertEquals(Blocks.STONE.defaultBlockState(), section.getBlockState(0, 0, 0));
        assertEquals(Blocks.AIR.defaultBlockState(), section.getBlockState(1, 0, 0));
        assertEquals(Blocks.AIR.defaultBlockState(), section.getBlockState(2, 0, 0));
        assertMatchesGolden("duplicate-air", wire);
    }

    @Test
    void golden_biomeThreeBitLinear() throws IOException {
        // Six distinct biomes -> ceillog2 = 3, the widest LINEAR biome tier, with the
        // ragged 4-long data array (ceil(64 cells / 21 values-per-long)).
        var biomeRegistry = REGISTRY_ACCESS.lookupOrThrow(Registries.BIOME);
        var picks = List.of(Biomes.PLAINS, Biomes.DESERT, Biomes.JUNGLE,
                Biomes.SNOWY_TAIGA, Biomes.SWAMP, Biomes.TAIGA);
        var biomes = FACTORY.createForBiomes();
        int i = 0;
        for (int qy = 0; qy < 4; qy++)
            for (int qz = 0; qz < 4; qz++)
                for (int qx = 0; qx < 4; qx++)
                    biomes.set(qx, qy, qz, biomeRegistry.getOrThrow(picks.get(i++ % picks.size())));
        var states = FACTORY.createForBlockStates();
        states.set(0, 0, 0, Blocks.STONE.defaultBlockState());
        byte[] wire = PaperNbtSectionSerializer.serializeChunkNbt(
                chunkNbt("minecraft:full", sectionFrom(0, states, biomes)), REGISTRY_ACCESS);
        var section = decode(wire).get(0).section();
        assertTrue(section.getBiomes().get(0, 0, 0).is(Biomes.PLAINS));
        assertTrue(section.getBiomes().get(1, 0, 0).is(Biomes.DESERT));
        assertTrue(section.getBiomes().get(1, 1, 1).is(Biomes.SNOWY_TAIGA), "cell 21 -> pick 21 % 6 = 3");
        assertMatchesGolden("biome-three-bit", wire);
    }

    @Test
    void golden_biomeGlobalPalette() throws IOException {
        // Ten distinct biomes -> ceillog2 = 4 -> the biome GLOBAL tier (>8 entries): no
        // palette section on the wire, ids at the biome-registry width (the transcoder's
        // biome object-fallback rung).
        var biomeRegistry = REGISTRY_ACCESS.lookupOrThrow(Registries.BIOME);
        var picks = List.of(Biomes.PLAINS, Biomes.DESERT, Biomes.JUNGLE, Biomes.SNOWY_TAIGA,
                Biomes.SWAMP, Biomes.TAIGA, Biomes.SAVANNA, Biomes.BADLANDS, Biomes.BEACH,
                Biomes.RIVER);
        var biomes = FACTORY.createForBiomes();
        int i = 0;
        for (int qy = 0; qy < 4; qy++)
            for (int qz = 0; qz < 4; qz++)
                for (int qx = 0; qx < 4; qx++)
                    biomes.set(qx, qy, qz, biomeRegistry.getOrThrow(picks.get(i++ % picks.size())));
        var states = FACTORY.createForBlockStates();
        states.set(0, 0, 0, Blocks.STONE.defaultBlockState());
        byte[] wire = PaperNbtSectionSerializer.serializeChunkNbt(
                chunkNbt("minecraft:full", sectionFrom(0, states, biomes)), REGISTRY_ACCESS);
        var section = decode(wire).get(0).section();
        assertTrue(section.getBiomes().get(0, 0, 0).is(Biomes.PLAINS));
        assertTrue(section.getBiomes().get(1, 0, 1).is(Biomes.TAIGA), "cell 5 -> pick 5 % 10 = 5");
        assertMatchesGolden("biome-global", wire);
    }

    // ---- Headless serve path (2026-07-29): the disk path writes containers + histogram
    // counts without constructing LevelChunkSection. These pin it against the real thing.

    /**
     * The strongest count-semantics pin: for randomized sections (air mixes, fluids,
     * waterlogged states — the fluidCount cases, multi-palette, single-entry), the
     * headless wire bytes must equal an envelope assembled around the REAL
     * {@code new LevelChunkSection(...).write(buf)} — ctor recount and all. A drift in
     * {@link NbtSectionSerializer#countNonEmptyAndFluid}'s BlockCounter mirroring (the
     * fluid-inside-non-air nesting, the single-entry fast path) fails here before the
     * goldens ever see it.
     */
    @Test
    void headlessWriteMatchesLevelChunkSectionWriteForRandomizedSections() {
        var statePool = List.of(
                Blocks.AIR.defaultBlockState(),
                Blocks.STONE.defaultBlockState(),
                Blocks.WATER.defaultBlockState(),
                Blocks.LAVA.defaultBlockState(),
                Blocks.SEAGRASS.defaultBlockState(),
                Blocks.OAK_STAIRS.defaultBlockState()
                        .setValue(BlockStateProperties.WATERLOGGED, true),
                Blocks.CAVE_AIR.defaultBlockState(),
                Blocks.DEEPSLATE.defaultBlockState());
        var rng = new java.util.Random(20260729L);
        for (int round = 0; round < 24; round++) {
            var sec = new LevelChunkSection(FACTORY);
            // Vary density: sparse rounds keep big air counts, dense rounds force wide palettes
            int placements = switch (round % 4) {
                case 0 -> 1;                        // single non-air cell
                case 1 -> 64;
                case 2 -> 2048;
                default -> 4096;                    // full section
            };
            for (int i = 0; i < placements; i++) {
                int cell = rng.nextInt(4096);
                sec.setBlockState(cell & 15, (cell >> 8) & 15, (cell >> 4) & 15,
                        statePool.get(rng.nextInt(statePool.size())));
            }

            byte[] actual = PaperNbtSectionSerializer.serializeChunkNbt(
                    chunkNbt("minecraft:full", sectionNbtFor(4, sec)), REGISTRY_ACCESS);

            // Expected: identical envelope, but the section body written by the real
            // counting ctor + LevelChunkSection.write. Round-trip the SAME NBT through the
            // factory codec so palette order matches what the headless path parsed.
            var roundTripped = FACTORY.blockStatesContainerCodec()
                    .parse(NbtOps.INSTANCE, sectionNbtFor(4, sec).getCompound("block_states").orElseThrow())
                    .getOrThrow();
            var biomesRT = FACTORY.biomeContainerCodec()
                    .parse(NbtOps.INSTANCE, sectionNbtFor(4, sec).getCompound("biomes").orElseThrow())
                    .getOrThrow();
            var real = new LevelChunkSection(roundTripped,
                    (net.minecraft.world.level.chunk.PalettedContainer<net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome>>) biomesRT);
            var expectedBuf = new FriendlyByteBuf(Unpooled.buffer());
            try {
                expectedBuf.writeVarInt(1);
                expectedBuf.writeByte(4);
                real.write(expectedBuf);
                expectedBuf.writeBoolean(false);
                expectedBuf.writeBoolean(false);
                byte[] expected = new byte[expectedBuf.readableBytes()];
                expectedBuf.readBytes(expected);
                if (real.hasOnlyAir()) {
                    // all-air with no light: the headless path serves a CLEAR (empty array)
                    assertEquals(0, actual.length, "round " + round);
                } else {
                    // C1: headless == toV20(real section.write) — pins the native emit
                    // AND the produce-path hook together (Fabric twin).
                    assertArrayEquals(PaperNbtSectionSerializer.toV20(expected, REGISTRY_ACCESS),
                            actual, "round " + round);
                }
            } finally {
                expectedBuf.release();
            }
        }
    }

    /** Section NBT for an arbitrary prepared section (the {@link #sectionNbt} sibling). */
    private CompoundTag sectionNbtFor(int y, LevelChunkSection sec) {
        var s = new CompoundTag();
        s.putInt("Y", y);
        s.put("block_states", FACTORY.blockStatesContainerCodec()
                .encodeStart(NbtOps.INSTANCE, sec.getStates()).getOrThrow());
        s.put("biomes", FACTORY.biomeContainerCodec()
                .encodeStart(NbtOps.INSTANCE, sec.getBiomes()).getOrThrow());
        return s;
    }

    // ---- Round-2 transcode equivalence (2026-07-29): the transcoder and the object path
    // may never disagree on a byte — these drive BOTH flag values through the full
    // serialize and compare, across every palette tier and the fallback rungs.

    /**
     * The transcode equivalence fuzz: randomized sections spanning every palette tier
     * (single, 4-bit linear, 5-8-bit hashmap, >256 Global fallback) and biome width
     * (single through 3-bit linear, >8 global fallback) must serialize byte-identically
     * with {@code useNbtTranscode} true and false.
     */
    @Test
    void transcodeAndObjectPathsMatchForRandomizedSections() {
        var narrowPool = List.of(
                Blocks.AIR.defaultBlockState(),
                Blocks.STONE.defaultBlockState(),
                Blocks.WATER.defaultBlockState(),
                Blocks.LAVA.defaultBlockState(),
                Blocks.SEAGRASS.defaultBlockState(),
                Blocks.OAK_STAIRS.defaultBlockState()
                        .setValue(BlockStateProperties.WATERLOGGED, true),
                Blocks.CAVE_AIR.defaultBlockState(),
                Blocks.DEEPSLATE.defaultBlockState());
        var widePool = new ArrayList<net.minecraft.world.level.block.state.BlockState>();
        for (var block : List.of(Blocks.OAK_STAIRS, Blocks.SPRUCE_STAIRS, Blocks.BIRCH_STAIRS,
                Blocks.JUNGLE_STAIRS)) {
            widePool.addAll(block.getStateDefinition().getPossibleStates());
        }
        var biomeRegistry = REGISTRY_ACCESS.lookupOrThrow(Registries.BIOME);
        var allBiomes = List.of(Biomes.PLAINS, Biomes.DESERT, Biomes.JUNGLE, Biomes.SNOWY_TAIGA,
                Biomes.SWAMP, Biomes.TAIGA, Biomes.SAVANNA, Biomes.BADLANDS, Biomes.BEACH,
                Biomes.RIVER);
        var rng = new java.util.Random(20260730L);
        long directBefore = PaperNbtSectionSerializer.DIRECT_V20_EMITS.get();
        for (int round = 0; round < 32; round++) {
            var pool = round % 8 < 6 ? narrowPool : widePool;
            int placements = switch (round % 4) {
                case 0 -> 1;
                case 1 -> 64;
                case 2 -> 2048;
                default -> 4096;
            };
            var states = FACTORY.createForBlockStates();
            for (int i = 0; i < placements; i++) {
                int cell = rng.nextInt(4096);
                states.set(cell & 15, (cell >> 8) & 15, (cell >> 4) & 15,
                        pool.get(rng.nextInt(pool.size())));
            }
            var biomes = FACTORY.createForBiomes();
            int biomeWidth = 1 + round % 10;
            for (int q = 0; q < 64; q++) {
                biomes.set(q & 3, (q >> 4) & 3, (q >> 2) & 3,
                        biomeRegistry.getOrThrow(allBiomes.get(rng.nextInt(biomeWidth))));
            }
            var chunk = chunkNbt("minecraft:full", sectionFrom(4, states, biomes));
            byte[] transcoded = PaperNbtSectionSerializer.serializeChunkNbt(chunk, REGISTRY_ACCESS,
                    null, Integer.MIN_VALUE, Integer.MAX_VALUE, true);
            byte[] object = PaperNbtSectionSerializer.serializeChunkNbt(chunk, REGISTRY_ACCESS,
                    null, Integer.MIN_VALUE, Integer.MAX_VALUE, false);
            assertArrayEquals(object, transcoded, "round " + round);
        }
        assertTrue(PaperNbtSectionSerializer.DIRECT_V20_EMITS.get() > directBefore,
                "the fuzz must actually drive the direct emit — a pre-gate widening that"
                        + " pushed every round to the object path would leave the"
                        + " direct-vs-translate comparison vacuous");
    }

    /**
     * The masked-drift pin (final review round, 2026-07-30): the transcoder's mask
     * PRE-GATE mirrors {@code needsMasking} textually, so this fuzz drives randomized
     * sections, hidden sets, section Ys (negative included), and cutoffs (mid-section
     * and negative included) through BOTH flag values. If the mirror ever
     * under-triggers — a mask-needing section transcoding verbatim, the ore-leak
     * direction — the object path masks it and the byte compare reds. Over-triggering
     * (needless fallback) stays byte-equal by design and is deliberately unpinned
     * (perf-only).
     */
    @Test
    void transcodeAndObjectPathsMatchUnderRandomizedMasks() {
        var pool = List.of(
                Blocks.AIR.defaultBlockState(),
                Blocks.STONE.defaultBlockState(),
                Blocks.DEEPSLATE.defaultBlockState(),
                Blocks.DIAMOND_ORE.defaultBlockState(),
                Blocks.DEEPSLATE_DIAMOND_ORE.defaultBlockState(),
                Blocks.IRON_ORE.defaultBlockState(),
                Blocks.WATER.defaultBlockState());
        var hiddenChoices = List.of(
                List.of("diamond_ore"),
                List.of("deepslate_diamond_ore"),
                List.of("diamond_ore", "deepslate_diamond_ore", "iron_ore"),
                List.of("iron_ore"));
        int[] cutoffs = {-16, -1, 0, 8, 16, 40, 64}; // mid-section and negative included
        var rng = new java.util.Random(20260730L);
        for (int round = 0; round < 48; round++) {
            var entry = new PaperXrayMaskManager.MaskEntry(
                    PaperXrayMaskFilter.MaskSet.resolve(
                            hiddenChoices.get(rng.nextInt(hiddenChoices.size())),
                            cutoffs[rng.nextInt(cutoffs.length)]),
                    dev.vox.lss.common.XrayMaskPolicy.FallbackKind.OVERWORLD, "test");
            var sectionList = new ArrayList<CompoundTag>();
            int sectionCount = 1 + rng.nextInt(3);
            int baseY = -4 + rng.nextInt(8);
            for (int i = 0; i < sectionCount; i++) {
                var states = FACTORY.createForBlockStates();
                int placements = 1 + rng.nextInt(4096);
                for (int p = 0; p < placements; p++) {
                    int cell = rng.nextInt(4096);
                    states.set(cell & 15, (cell >> 8) & 15, (cell >> 4) & 15,
                            pool.get(rng.nextInt(pool.size())));
                }
                sectionList.add(sectionFrom(baseY + i, states, FACTORY.createForBiomes()));
            }
            var chunk = chunkNbt("minecraft:full", sectionList.toArray(new CompoundTag[0]));
            byte[] transcoded = PaperNbtSectionSerializer.serializeChunkNbt(chunk, REGISTRY_ACCESS,
                    entry, Integer.MIN_VALUE, Integer.MAX_VALUE, true);
            byte[] object = PaperNbtSectionSerializer.serializeChunkNbt(chunk, REGISTRY_ACCESS,
                    entry, Integer.MIN_VALUE, Integer.MAX_VALUE, false);
            assertArrayEquals(object, transcoded, "round " + round);
        }
    }

    /**
     * Deterministic tiers the randomized fuzz statistically jumps over: exactly 100
     * palette entries (the 7-bit hashmap tier) and exactly 256 (the 8-bit boundary,
     * one short of the Global fallback) — air is placed as a pool member so the
     * palette size is exact, not distinct-written-plus-maybe-stale-air. Each round
     * also serves a single-entry NON-default biome palette (all-desert — common on
     * real worlds, never produced by the fuzz's plains-heavy rounds) and a light-only
     * AIR_SINGLE cap entry, byte-compared across both paths for the first time.
     */
    @Test
    void deterministicPaletteTiersMatchAcrossBothPaths() {
        var widePool = new ArrayList<net.minecraft.world.level.block.state.BlockState>();
        for (var block : List.of(Blocks.OAK_STAIRS, Blocks.SPRUCE_STAIRS, Blocks.BIRCH_STAIRS,
                Blocks.JUNGLE_STAIRS)) {
            widePool.addAll(block.getStateDefinition().getPossibleStates());
        }
        var biomeRegistry = REGISTRY_ACCESS.lookupOrThrow(Registries.BIOME);
        for (int paletteSize : new int[]{100, 256}) {
            var tierPool = new ArrayList<net.minecraft.world.level.block.state.BlockState>();
            tierPool.add(Blocks.AIR.defaultBlockState());
            tierPool.addAll(widePool.subList(0, paletteSize - 1));
            var states = FACTORY.createForBlockStates();
            for (int cell = 0; cell < 4096; cell++) {
                states.set(cell & 15, (cell >> 8) & 15, (cell >> 4) & 15,
                        tierPool.get(cell % paletteSize));
            }
            var desert = FACTORY.createForBiomes();
            for (int q = 0; q < 64; q++) {
                desert.set(q & 3, (q >> 4) & 3, (q >> 2) & 3,
                        biomeRegistry.getOrThrow(Biomes.DESERT));
            }
            byte[] sky = new byte[2048];
            java.util.Arrays.fill(sky, (byte) 0xF0);
            var lightOnly = new CompoundTag();
            lightOnly.putInt("Y", 1);
            lightOnly.putByteArray("SkyLight", sky);
            var chunk = chunkNbt("minecraft:full",
                    sectionFrom(0, states, desert), lightOnly);
            byte[] transcoded = PaperNbtSectionSerializer.serializeChunkNbt(chunk, REGISTRY_ACCESS,
                    null, Integer.MIN_VALUE, Integer.MAX_VALUE, true);
            byte[] object = PaperNbtSectionSerializer.serializeChunkNbt(chunk, REGISTRY_ACCESS,
                    null, Integer.MIN_VALUE, Integer.MAX_VALUE, false);
            assertArrayEquals(object, transcoded, "palette size " + paletteSize);
        }
    }

    @Test
    void renamedPaletteEntryTranscodesIdenticallyToTheObjectPath() {
        // The air-substitution rung the transcoder handles ITSELF (no fallback): unknown
        // entries resolve to air meta through the memo's hardError rung, in place; the
        // bytes must equal the object path's orElsePartial substitution exactly.
        var sec = new LevelChunkSection(FACTORY);
        sec.setBlockState(0, 0, 0, Blocks.STONE.defaultBlockState());
        sec.setBlockState(1, 0, 0, Blocks.DIRT.defaultBlockState());
        var s = sectionNbtFor(1, sec);
        var palette = s.getCompound("block_states").orElseThrow().getList("palette").orElseThrow();
        boolean renamed = false;
        for (var e : palette) {
            var pe = (CompoundTag) e;
            if ("minecraft:dirt".equals(pe.getStringOr("Name", ""))) {
                pe.putString("Name", "lss:renamed_away");
                renamed = true;
            }
        }
        assertTrue(renamed, "premise: the dirt palette entry was renamed");
        var chunk = chunkNbt("minecraft:full", s);
        byte[] transcoded = PaperNbtSectionSerializer.serializeChunkNbt(chunk, REGISTRY_ACCESS,
                null, Integer.MIN_VALUE, Integer.MAX_VALUE, true);
        byte[] object = PaperNbtSectionSerializer.serializeChunkNbt(chunk, REGISTRY_ACCESS,
                null, Integer.MIN_VALUE, Integer.MAX_VALUE, false);
        assertArrayEquals(object, transcoded);
        assertTrue(transcoded.length > 0, "the substituted section still serves");
    }

    @Test
    void dataLengthMismatchCondemnsTheColumnOnBothPaths() {
        // SimpleBitStorage's exact-length rule: a 2-entry palette needs exactly 256
        // longs; 255 is structural corruption with no partial — the whole column is an
        // authoritative miss on the object path, and the transcoder's fallback rung must
        // land on the same outcome, never silently re-derive.
        var states = FACTORY.createForBlockStates();
        states.set(0, 0, 0, Blocks.STONE.defaultBlockState());
        var s = sectionFrom(0, states, FACTORY.createForBiomes());
        var bs = s.getCompound("block_states").orElseThrow();
        long[] data = bs.getLongArray("data").orElseThrow();
        bs.putLongArray("data", Arrays.copyOf(data, data.length - 1));
        var chunk = chunkNbt("minecraft:full", s, sectionNbt(2, true, true, null, null));
        assertNull(PaperNbtSectionSerializer.serializeChunkNbt(chunk, REGISTRY_ACCESS,
                null, Integer.MIN_VALUE, Integer.MAX_VALUE, true));
        assertNull(PaperNbtSectionSerializer.serializeChunkNbt(chunk, REGISTRY_ACCESS,
                null, Integer.MIN_VALUE, Integer.MAX_VALUE, false));
    }

    // ---- Undecodable columns (undecodable-chunks plan, 2026-11-12) ----
    //
    // A column whose cells use only block palette entries that failed to decode (a removed
    // mod's blocks, a chunk format this build cannot read) used to serialize as all-air, or
    // as lit air sections: stamped, stored, and sent as a clear over the client's cached
    // terrain. It is null now, with the caller's notes marked so the reader does not
    // escalate it to generation. Same text in the Fabric and Paper twins.

    private static net.minecraft.nbt.ListTag blockPaletteOf(CompoundTag section) {
        return section.getCompound("block_states").orElseThrow().getList("palette").orElseThrow();
    }

    /** Makes EVERY palette entry an unknown block, whatever shape the line writes (a bare
     *  string, {id, ...} or {Name, ...}); returns how many entries it changed. */
    private static int makeEveryPaletteEntryUnknown(net.minecraft.nbt.ListTag palette, String prefix) {
        for (int i = 0; i < palette.size(); i++) {
            String unknown = "lss:" + prefix + "_" + i;
            if (palette.get(i) instanceof CompoundTag entry) {
                entry.putString(entry.contains("id") ? "id" : "Name", unknown);
            } else {
                palette.set(i, StringTag.valueOf(unknown));
            }
        }
        return palette.size();
    }

    private static byte[] serializeWithNotes(CompoundTag chunk, boolean transcode, ColumnDecodeNotes notes) {
        return PaperNbtSectionSerializer.serializeChunkNbt(chunk, REGISTRY_ACCESS, null,
                Integer.MIN_VALUE, Integer.MAX_VALUE, transcode, notes);
    }

    /** Undecodable on BOTH routes: null bytes, the notes marked with a decode message,
     *  and a caller that passes no notes (the store backfill) sees a plain null = skip. */
    private static void assertUndecodableOnBothRoutes(CompoundTag chunk, String what) {
        for (boolean transcode : new boolean[]{true, false}) {
            String route = what + " (transcode=" + transcode + ")";
            var notes = new ColumnDecodeNotes();
            assertNull(serializeWithNotes(chunk, transcode, notes), route + ": never air, never bytes");
            assertTrue(notes.undecodable(), route + ": the notes report the column undecodable");
            assertNotNull(notes.firstDecodeError(), route + ": with the first decode message");
            assertNull(PaperNbtSectionSerializer.serializeChunkNbt(chunk, REGISTRY_ACCESS, null,
                            Integer.MIN_VALUE, Integer.MAX_VALUE, transcode),
                    route + ": a caller without notes skips the chunk");
        }
    }

    /** Serves identically on both routes and reports nothing; returns the bytes. */
    private static byte[] assertServesAndReportsNothing(CompoundTag chunk, String what) {
        var transcodedNotes = new ColumnDecodeNotes();
        var objectNotes = new ColumnDecodeNotes();
        byte[] transcoded = serializeWithNotes(chunk, true, transcodedNotes);
        byte[] object = serializeWithNotes(chunk, false, objectNotes);
        assertNotNull(transcoded, what + ": served, not a miss");
        assertArrayEquals(object, transcoded, what + ": both routes agree");
        for (var notes : List.of(transcodedNotes, objectNotes)) {
            assertFalse(notes.undecodable(), what + ": nothing reported undecodable");
            assertNull(notes.firstDecodeError(), what + ": no decode message");
        }
        return transcoded;
    }

    @Test
    void everyUsedBlockEntryUnknown_columnIsUndecodableNeverAir() {
        // A single-entry palette (a full section of one block) and a multi-entry palette
        // with data, every entry unknown: the wholesale shape (a chunk format this build
        // cannot read fails every entry, air included).
        var full = FACTORY.createForBlockStates();
        for (int y = 0; y < 16; y++)
            for (int z = 0; z < 16; z++)
                for (int x = 0; x < 16; x++)
                    full.set(x, y, z, Blocks.STONE.defaultBlockState());
        var mixed = FACTORY.createForBlockStates();
        mixed.set(0, 0, 0, Blocks.STONE.defaultBlockState());
        mixed.set(1, 0, 0, Blocks.DIRT.defaultBlockState());
        var single = sectionFrom(0, full, FACTORY.createForBiomes());
        var multi = sectionFrom(1, mixed, FACTORY.createForBiomes());
        var chunk = chunkNbt("minecraft:full", single, multi);
        assertEquals(2, decode(PaperNbtSectionSerializer.serializeChunkNbt(chunk, REGISTRY_ACCESS)).size(),
                "premise: both sections serve before their palettes are broken");
        assertEquals(1, makeEveryPaletteEntryUnknown(blockPaletteOf(single), "gone_single"));
        assertTrue(makeEveryPaletteEntryUnknown(blockPaletteOf(multi), "gone_multi") >= 3,
                "premise: the multi-entry palette carries air, stone and dirt");
        assertUndecodableOnBothRoutes(chunk, "every entry unknown");

        // The removed-mod shape: real air still decodes, every BLOCK entry is unknown.
        var removed = FACTORY.createForBlockStates();
        removed.set(0, 0, 0, Blocks.STONE.defaultBlockState());
        removed.set(1, 0, 0, Blocks.DIRT.defaultBlockState());
        var section = sectionFrom(3, removed, FACTORY.createForBiomes());
        var palette = blockPaletteOf(section);
        assertEquals(2, renamePaletteEntry(palette, "minecraft:stone", "lss:removed_one")
                + renamePaletteEntry(palette, "minecraft:dirt", "lss:removed_two"),
                "premise: both block entries were renamed, air left alone");
        assertUndecodableOnBothRoutes(chunkNbt("minecraft:full", section),
                "every block entry unknown beside decodable air");
    }

    @Test
    void everyUsedBlockEntryUnknown_withSkyOrBlockLight_isStillUndecodable() {
        // Light changes nothing: sky-lit substituted air was dropped to the empty array, and
        // block-lit substituted air was served as real lit air sections. Both are the same
        // blank answer over terrain the server could not read.
        byte[] sky = new byte[2048];
        Arrays.fill(sky, (byte) 0xFF);
        byte[] block = light(7, (byte) 5);
        var lit = List.of(
                sectionNbt(0, true, true, null, sky),
                sectionNbt(0, true, true, block, null),
                sectionNbt(0, true, true, block, sky));
        for (int i = 0; i < lit.size(); i++) {
            var section = lit.get(i);
            assertEquals(1, renamePaletteEntry(blockPaletteOf(section), "minecraft:stone", "lss:lit_gone_" + i),
                    "premise: the stone entry was renamed");
            assertUndecodableOnBothRoutes(chunkNbt("minecraft:full", section), "lit variant " + i);
        }
    }

    @Test
    void rejectedKeyShapeEntry_columnIsUndecodable() {
        // An entry the codec rejects outright (neither id nor Name): the shape of a format
        // change this build has no mirror for.
        var full = FACTORY.createForBlockStates();
        for (int y = 0; y < 16; y++)
            for (int z = 0; z < 16; z++)
                for (int x = 0; x < 16; x++)
                    full.set(x, y, z, Blocks.STONE.defaultBlockState());
        var section = sectionFrom(0, full, FACTORY.createForBiomes());
        var palette = blockPaletteOf(section);
        assertEquals(1, palette.size(), "premise: a single-entry palette");
        var rejected = new CompoundTag();
        rejected.putString("lss_unreadable_key", "minecraft:stone");
        palette.set(0, rejected);
        assertUndecodableOnBothRoutes(chunkNbt("minecraft:full", section), "rejected key shape");
    }

    @Test
    void unknownEntriesBesideADecodedBlock_stillServeAndReportNothing() {
        // The pinned leniency (R2-1) is untouched: one decoded block anywhere in the column
        // keeps today's behaviour exactly, unknown entries showing as air in place.
        var unknown = sectionNbt(1, true, true, null, null);
        assertEquals(1, renamePaletteEntry(blockPaletteOf(unknown), "minecraft:stone", "lss:beside_gone"));
        byte[] wire = assertServesAndReportsNothing(
                chunkNbt("minecraft:full", unknown, sectionNbt(2, true, true, null, null)),
                "an all-unknown section beside a stone section");
        var sections = decode(wire);
        assertEquals(1, sections.size(), "the all-unknown section drops as air, the sibling serves");
        assertEquals(2, sections.get(0).y());
        assertEquals(Blocks.STONE.defaultBlockState(), sections.get(0).section().getBlockState(0, 0, 0));

        // Same section: an unknown entry beside a decoded one.
        var sec = new LevelChunkSection(FACTORY);
        sec.setBlockState(0, 0, 0, Blocks.STONE.defaultBlockState());
        sec.setBlockState(1, 0, 0, Blocks.DIRT.defaultBlockState());
        var same = sectionNbtFor(1, sec);
        assertEquals(1, renamePaletteEntry(blockPaletteOf(same), "minecraft:dirt", "lss:beside_gone_too"));
        var kept = decode(assertServesAndReportsNothing(chunkNbt("minecraft:full", same),
                "an unknown entry beside a decoded one in the same section"));
        assertEquals(Blocks.STONE.defaultBlockState(), kept.get(0).section().getBlockState(0, 0, 0));
        assertEquals(Blocks.AIR.defaultBlockState(), kept.get(0).section().getBlockState(1, 0, 0));
    }

    @Test
    void realAir_isStillTheEmptyArrayAndReportsNothing() {
        byte[] sky = new byte[2048];
        Arrays.fill(sky, (byte) 0xFF);
        for (var section : List.of(
                sectionNbt(0, false, true, null, null),
                sectionNbt(0, false, true, null, sky))) {
            byte[] wire = assertServesAndReportsNothing(chunkNbt("minecraft:full", section), "real air");
            assertEquals(0, wire.length, "a truly empty column is still the empty array, not a miss");
        }
    }

    @Test
    void unusedUnknownPaletteEntry_inAnAllAirColumn_isStillTheEmptyArray() {
        // Cells are counted, not palette entries: a leftover entry no cell points at changes
        // nothing on screen and must not turn a truly empty column into an undecodable one.
        var states = FACTORY.createForBlockStates();
        states.set(15, 15, 15, Blocks.STONE.defaultBlockState()); // last cell: air packs first
        var section = sectionFrom(0, states, FACTORY.createForBiomes());
        var bs = section.getCompound("block_states").orElseThrow();
        assertEquals(2, blockPaletteOf(section).size(), "premise: an [air, stone] palette");
        bs.putLongArray("data", new long[bs.getLongArray("data").orElseThrow().length]); // every cell -> entry 0
        var chunk = chunkNbt("minecraft:full", section);
        assertEquals(0, PaperNbtSectionSerializer.serializeChunkNbt(chunk, REGISTRY_ACCESS).length,
                "premise: with every cell on entry 0 the column is all air, the stone entry unused");

        assertEquals(1, renamePaletteEntry(blockPaletteOf(section), "minecraft:stone", "lss:unused_gone"),
                "premise: the unused entry is now unknown");
        byte[] wire = assertServesAndReportsNothing(chunk, "an unused unknown palette entry");
        assertEquals(0, wire.length, "still the empty array");
    }

    @Test
    void fallbackSections_decideUndecodableExactlyLikeTranscodedOnes() {
        // The transcoder hands some shapes to the object path per section. The decision
        // must not depend on which route parsed the section.
        // (a) a >256-entry palette (Global config): the transcoder never counts it.
        var wide = FACTORY.createForBlockStates();
        int i = 0;
        for (var state : Block.BLOCK_STATE_REGISTRY) {
            if (i == 300) break;
            wide.set(i & 15, (i >> 8) & 15, (i >> 4) & 15, state);
            i++;
        }
        var wideSection = sectionFrom(0, wide, FACTORY.createForBiomes());
        assertTrue(makeEveryPaletteEntryUnknown(blockPaletteOf(wideSection), "gone_wide") > 256,
                "premise: a palette past the transcoder's 256-entry bound");
        assertUndecodableOnBothRoutes(chunkNbt("minecraft:full", wideSection), "wide palette");

        // (b) an empty biome palette: the transcoder resolves the block palette first and
        // falls back afterwards.
        byte[] sky = new byte[2048];
        Arrays.fill(sky, (byte) 0xFF);
        var biomeFallback = sectionNbt(0, true, true, null, sky);
        assertEquals(1, renamePaletteEntry(blockPaletteOf(biomeFallback), "minecraft:stone", "lss:gone_biome_fallback"));
        var emptyBiomes = new CompoundTag();
        emptyBiomes.put("palette", new ListTag());
        biomeFallback.put("biomes", emptyBiomes);
        assertUndecodableOnBothRoutes(chunkNbt("minecraft:full", biomeFallback), "biome fallback");
    }

    @Test
    void unparseableSection_besideUsedUnknownBlocks_isUndecodableNeverAPlainMiss() {
        // A plain miss may escalate to a generation ticket; a chunk whose cells use blocks
        // this server does not know must never be loaded by LSS (vanilla turns them into
        // air on load and may save the chunk). So wherever the unparseable-section rung
        // would condemn the column, a USED unknown entry turns the verdict into undecodable.
        var bad = new CompoundTag();
        bad.putInt("Y", 1);
        var badStates = new CompoundTag();
        badStates.putInt("palette", 7); // not even a list — no partial recoverable
        bad.put("block_states", badStates);

        // (a) nothing decoded at all beside the corrupt section.
        var unknown = sectionNbt(2, true, true, null, null);
        assertEquals(1, renamePaletteEntry(blockPaletteOf(unknown), "minecraft:stone", "lss:gone_beside_corrupt"));
        assertUndecodableOnBothRoutes(chunkNbt("minecraft:full", bad, unknown),
                "an all-unknown section beside an unparseable one");

        // (b) decoded blocks AND used unknown ones beside the corrupt section: without the
        // corrupt section this column serves (unknown shown as air); with it, it must not
        // become the generation-escalating miss.
        var sec = new LevelChunkSection(FACTORY);
        sec.setBlockState(0, 0, 0, Blocks.STONE.defaultBlockState());
        sec.setBlockState(1, 0, 0, Blocks.DIRT.defaultBlockState());
        var mixed = sectionNbtFor(2, sec);
        assertEquals(1, renamePaletteEntry(blockPaletteOf(mixed), "minecraft:dirt", "lss:gone_in_mixed"));
        assertServesAndReportsNothing(chunkNbt("minecraft:full", mixed),
                "premise: decoded and unknown blocks without a corrupt section");
        assertUndecodableOnBothRoutes(chunkNbt("minecraft:full", bad, mixed),
                "decoded and used unknown blocks beside an unparseable section");

        // (c) the unknown blocks in a SIBLING of the decoded content.
        var decoded = sectionNbt(3, true, true, null, null);
        var gone = sectionNbt(4, true, true, null, null);
        assertEquals(1, renamePaletteEntry(blockPaletteOf(gone), "minecraft:stone", "lss:gone_sibling"));
        assertUndecodableOnBothRoutes(chunkNbt("minecraft:full", bad, decoded, gone),
                "unknown blocks in a sibling of decoded content, beside an unparseable section");
    }

    @Test
    void unparseableSection_withoutUsedUnknownBlocks_staysThePlainMiss() {
        // No unknown block in use: today's behaviour, pinned by
        // trulyUnparseableSection_condemnsTheColumnAsAuthoritativeMiss. Generation may
        // repair such a chunk, and loading it destroys nothing.
        var bad = new CompoundTag();
        bad.putInt("Y", 1);
        var badStates = new CompoundTag();
        badStates.putInt("palette", 7);
        bad.put("block_states", badStates);

        // An UNUSED unknown palette entry does not count (cells, not entries).
        var states = FACTORY.createForBlockStates();
        states.set(15, 15, 15, Blocks.STONE.defaultBlockState());
        var leftover = sectionFrom(3, states, FACTORY.createForBiomes());
        var bs = leftover.getCompound("block_states").orElseThrow();
        bs.putLongArray("data", new long[bs.getLongArray("data").orElseThrow().length]);
        assertEquals(1, renamePaletteEntry(blockPaletteOf(leftover), "minecraft:stone", "lss:unused_beside_corrupt"));

        for (var chunk : List.of(
                chunkNbt("minecraft:full", bad, sectionNbt(2, true, true, null, null)),
                chunkNbt("minecraft:full", bad, sectionNbt(2, true, true, null, null), leftover))) {
            for (boolean transcode : new boolean[]{true, false}) {
                var notes = new ColumnDecodeNotes();
                assertNull(serializeWithNotes(chunk, transcode, notes),
                        "the unparseable section still condemns the column (transcode=" + transcode + ")");
                assertFalse(notes.undecodable(),
                        "with no unknown block in use it stays the plain authoritative miss (transcode="
                                + transcode + ")");
            }
        }
    }

    /** A section whose block palette is hand-built: {@code entries} in order, every cell
     *  on entry 0 except cell 1, which is on {@code cellOneEntry}. The data is packed at
     *  the disk width for that palette size (4 bits up to 16 entries, then ceil(log2 n)). */
    private CompoundTag craftedPaletteSection(int y, List<net.minecraft.nbt.Tag> entries, int cellOneEntry) {
        int n = entries.size();
        int bits = n <= 16 ? 4 : 32 - Integer.numberOfLeadingZeros(n - 1);
        int valuesPerLong = 64 / bits;
        long[] data = new long[(4096 + valuesPerLong - 1) / valuesPerLong];
        data[0] = ((long) cellOneEntry) << bits; // cell 1 sits in the second slot of word 0
        var palette = new ListTag();
        palette.addAll(entries);
        var blockStates = new CompoundTag();
        blockStates.put("palette", palette);
        blockStates.putLongArray("data", data);
        var section = sectionFrom(y, FACTORY.createForBlockStates(), FACTORY.createForBiomes());
        section.put("block_states", blockStates);
        return section;
    }

    private static net.minecraft.nbt.Tag paletteEntry(net.minecraft.world.level.block.state.BlockState state) {
        return net.minecraft.world.level.block.state.BlockState.CODEC
                .encodeStart(NbtOps.INSTANCE, state).getOrThrow();
    }

    private static net.minecraft.nbt.Tag unknownPaletteEntry(String name) {
        var entry = new CompoundTag();
        entry.putString("Name", name);
        return entry;
    }

    @Test
    void usedEntryWalk_readsWidePalettesAtTheirOwnBitWidth() {
        // The "is the failed entry used by a cell" walk must read the cell indices at the
        // palette's own width. Two tiers beyond the 4-bit one: 20 entries (5 bits, the
        // transcoder's route) and 300 entries (9 bits, the object route). In each, cell 1
        // holds the LAST entry, a value whose bits read as other entries at any other width.
        for (int size : new int[]{20, 300}) {
            // (a) every entry between air and the last is unknown and UNUSED; the cells
            // use air and cave air only. A truly empty column: served as the empty array,
            // nothing reported. A wrong width lands on an unknown entry and reports it.
            var unusedUnknown = new ArrayList<net.minecraft.nbt.Tag>();
            unusedUnknown.add(paletteEntry(Blocks.AIR.defaultBlockState()));
            for (int i = 1; i < size - 1; i++) unusedUnknown.add(unknownPaletteEntry("lss:width_unused_" + size + "_" + i));
            unusedUnknown.add(paletteEntry(Blocks.CAVE_AIR.defaultBlockState()));
            byte[] wire = assertServesAndReportsNothing(
                    chunkNbt("minecraft:full", craftedPaletteSection(0, unusedUnknown, size - 1)),
                    size + " entries, unknown entries unused");
            assertEquals(0, wire.length, size + " entries: air and cave air only is the empty array");

            // (b) the LAST entry is unknown and USED by cell 1; every entry between is a
            // real block no cell uses. Undecodable. A wrong width lands on a real entry
            // (or past the palette) and misses it.
            var usedUnknown = new ArrayList<net.minecraft.nbt.Tag>();
            usedUnknown.add(paletteEntry(Blocks.AIR.defaultBlockState()));
            for (var state : Block.BLOCK_STATE_REGISTRY) {
                if (usedUnknown.size() == size - 1) break;
                if (!state.isAir()) usedUnknown.add(paletteEntry(state));
            }
            assertEquals(size - 1, usedUnknown.size(), "premise: enough real block states");
            usedUnknown.add(unknownPaletteEntry("lss:width_used_" + size));
            assertUndecodableOnBothRoutes(
                    chunkNbt("minecraft:full", craftedPaletteSection(0, usedUnknown, size - 1)),
                    size + " entries, the last one unknown and used");
        }
    }

    // ---- Columns saved without sky light (lightless-chunks plan, fold item 7) ----
    //
    // A diagnostics flag on the caller's notes and never a byte: the reader counts it for
    // dimensions that have sky light, so operators can see that a world was saved without
    // light (the client estimates the light). Same text in the Fabric and Paper twins.

    /** Serializes on both routes with notes. The routes must agree with each other and
     *  with the no-notes overload byte for byte (the flag never changes the bytes) and
     *  must report the same; returns whether the column was reported lightless. */
    private static boolean reportsLightless(CompoundTag chunk, int minSectionY, int maxSectionY, String what) {
        var transcodedNotes = new ColumnDecodeNotes();
        var objectNotes = new ColumnDecodeNotes();
        byte[] transcoded = PaperNbtSectionSerializer.serializeChunkNbt(chunk, REGISTRY_ACCESS, null,
                minSectionY, maxSectionY, true, transcodedNotes);
        byte[] object = PaperNbtSectionSerializer.serializeChunkNbt(chunk, REGISTRY_ACCESS, null,
                minSectionY, maxSectionY, false, objectNotes);
        assertArrayEquals(object, transcoded, what + ": both routes agree");
        assertArrayEquals(PaperNbtSectionSerializer.serializeChunkNbt(chunk, REGISTRY_ACCESS, null,
                        minSectionY, maxSectionY, true),
                transcoded, what + ": passing notes never changes the bytes");
        assertEquals(transcodedNotes.lightless(), objectNotes.lightless(),
                what + ": both routes report the same");
        assertFalse(transcodedNotes.undecodable(), what + ": not an undecodable column");
        return transcodedNotes.lightless();
    }

    private static boolean reportsLightless(CompoundTag chunk, String what) {
        return reportsLightless(chunk, Integer.MIN_VALUE, Integer.MAX_VALUE, what);
    }

    @Test
    void litChunk_isNotReportedLightless() {
        byte[] sky = new byte[2048];
        Arrays.fill(sky, (byte) 0xFF);
        assertFalse(reportsLightless(chunkNbt("minecraft:full",
                sectionNbt(0, true, true, null, sky)), "a sky-lit content section"));
        assertFalse(reportsLightless(chunkNbt("minecraft:full",
                        sectionNbt(0, true, true, null, light(2047, (byte) 0x10))),
                "one non-zero nibble at the very end of the layer"));
        // The lit entry need not be one that is served: sky-lit air far above dark content
        // is dropped from the wire by the content band, and still proves the chunk is lit.
        assertFalse(reportsLightless(chunkNbt("minecraft:full",
                        sectionNbt(0, true, true, null, null),
                        sectionNbt(6, false, true, null, sky)),
                "dark content below sky-lit air outside the content band"));
    }

    @Test
    void chunkSavedWithoutSkyLight_isReportedLightless() {
        // The shape a world upgrade leaves: blocks, and no light arrays at all.
        assertTrue(reportsLightless(chunkNbt("minecraft:full",
                        sectionNbt(0, true, true, null, null),
                        sectionNbt(1, true, true, null, null)),
                "blocks and no light arrays"));
        assertTrue(reportsLightless(chunkNbt("minecraft:full",
                        sectionNbt(0, true, true, light(3, (byte) 7), null)),
                "block light is not sky light"));
        // Allocated-but-zeroed sky arrays carry no sky light either. A column that is solid
        // to the top of the world looks like this too and is reported; that is accepted.
        assertTrue(reportsLightless(chunkNbt("minecraft:full",
                        sectionNbt(0, true, true, new byte[2048], new byte[2048]),
                        sectionNbt(1, true, true, null, new byte[2048])),
                "zeroed sky arrays"));
        byte[] missized = new byte[100];
        Arrays.fill(missized, (byte) 0xFF);
        assertTrue(reportsLightless(chunkNbt("minecraft:full",
                        sectionNbt(0, true, true, null, missized)),
                "a mis-sized sky array is not a layer the serializer would send"));
    }

    @Test
    void allAirChunk_isNeverReportedLightless() {
        // No blocks, nothing to light: an empty column (or lit air) is not the condition.
        assertFalse(reportsLightless(chunkNbt("minecraft:full",
                sectionNbt(0, false, true, null, null)), "all air, no light"));
        assertFalse(reportsLightless(chunkNbt("minecraft:full",
                        sectionNbt(0, false, true, light(1, (byte) 1), null)),
                "block-lit air, no blocks"));
    }

    @Test
    void skyLightOnlyInAnOutOfRangeEntry_isNotReportedLightless() {
        // Vanilla saves a fully lit light-only entry one section above the block range. The
        // range gate drops it before parsing, but it still shows the chunk was saved lit.
        byte[] sky = new byte[2048];
        Arrays.fill(sky, (byte) 0xFF);
        var capEntry = new CompoundTag();
        capEntry.putInt("Y", 5);
        capEntry.putByteArray("SkyLight", sky);
        assertFalse(reportsLightless(chunkNbt("minecraft:full",
                        sectionNbt(4, true, true, null, null), capEntry), 0, 4,
                "sky light only in the entry above the block range"));
        assertTrue(reportsLightless(chunkNbt("minecraft:full",
                        sectionNbt(4, true, true, null, null)), 0, 4,
                "premise: the same column without that entry is lightless"));
    }

    @Test
    void strayDataOnASingleEntryPaletteIsIgnoredOnBothPaths() {
        // ZeroBitStorage: a 1-entry palette ignores any data tag on disk (the bits-0
        // branch never reads it) — zero longs reach the wire on both paths.
        var states = FACTORY.createForBlockStates();
        for (int y = 0; y < 16; y++)
            for (int z = 0; z < 16; z++)
                for (int x = 0; x < 16; x++)
                    states.set(x, y, z, Blocks.STONE.defaultBlockState());
        var s = sectionFrom(0, states, FACTORY.createForBiomes());
        var bs = s.getCompound("block_states").orElseThrow();
        assertTrue(bs.getLongArray("data").isEmpty(), "premise: a single palette packs no data");
        bs.putLongArray("data", new long[]{-1L, 123L});
        var chunk = chunkNbt("minecraft:full", s);
        byte[] transcoded = PaperNbtSectionSerializer.serializeChunkNbt(chunk, REGISTRY_ACCESS,
                null, Integer.MIN_VALUE, Integer.MAX_VALUE, true);
        byte[] object = PaperNbtSectionSerializer.serializeChunkNbt(chunk, REGISTRY_ACCESS,
                null, Integer.MIN_VALUE, Integer.MAX_VALUE, false);
        assertArrayEquals(object, transcoded);
        assertEquals(Blocks.STONE.defaultBlockState(),
                decode(transcoded).get(0).section().getBlockState(5, 5, 5));
    }

    @Test
    void directEmitRoutesExactlyTheAllTranscodedColumns() {
        // The C6 follow-up's routing pin — see the Fabric twin: direct-emit output is
        // byte-identical to the translate route, so only this counter catches a silent
        // re-route back through the native intermediate.
        long before = PaperNbtSectionSerializer.DIRECT_V20_EMITS.get();
        PaperNbtSectionSerializer.serializeChunkNbt(
                chunkNbt("minecraft:full", sectionNbt(0, true, true, null, null)), REGISTRY_ACCESS);
        assertEquals(before + 1, PaperNbtSectionSerializer.DIRECT_V20_EMITS.get(),
                "an all-transcoded column must take the direct v20 emit");

        var pool = new ArrayList<net.minecraft.world.level.block.state.BlockState>();
        for (var block : List.of(Blocks.OAK_STAIRS, Blocks.SPRUCE_STAIRS, Blocks.BIRCH_STAIRS,
                Blocks.JUNGLE_STAIRS)) {
            pool.addAll(block.getStateDefinition().getPossibleStates());
        }
        var globalStates = FACTORY.createForBlockStates();
        int i = 0;
        for (int y = 0; y < 16; y++)
            for (int z = 0; z < 16; z++)
                for (int x = 0; x < 16; x++)
                    globalStates.set(x, y, z, pool.get(i++ % pool.size()));
        long beforeMixed = PaperNbtSectionSerializer.DIRECT_V20_EMITS.get();
        byte[] mixed = PaperNbtSectionSerializer.serializeChunkNbt(
                chunkNbt("minecraft:full",
                        sectionNbt(0, true, true, null, null),
                        sectionFrom(1, globalStates, FACTORY.createForBiomes())),
                REGISTRY_ACCESS);
        assertEquals(2, decode(mixed).size(),
                "premise: the mixed column still SERVES both sections via translate");
        assertEquals(beforeMixed, PaperNbtSectionSerializer.DIRECT_V20_EMITS.get(),
                "a column with any fallback section keeps the translate route wholesale");

        byte[] flagOff = PaperNbtSectionSerializer.serializeChunkNbt(
                chunkNbt("minecraft:full", sectionNbt(0, true, true, null, null)),
                REGISTRY_ACCESS, null, Integer.MIN_VALUE, Integer.MAX_VALUE, false);
        assertEquals(1, decode(flagOff).size(), "premise: the flag-off column still serves");
        assertEquals(beforeMixed, PaperNbtSectionSerializer.DIRECT_V20_EMITS.get(),
                "useNbtTranscode=false is the object path — no direct emit");
    }

    @Test
    void maskedColumnMixesTranscodedAndFallbackSectionsByteIdentically() {
        // Under a mask, sections split by the pre-gate: ore-bearing below the cutoff go
        // to the object fallback and get masked; ore-free or above-cutoff sections
        // transcode (mask() provably no-ops on them). The mixed column must byte-match
        // the all-object path, the hidden ore must be gone, and the above-cutoff ore
        // must stay real (the height-gate pin).
        var ore = FACTORY.createForBlockStates();
        var clean = FACTORY.createForBlockStates();
        var high = FACTORY.createForBlockStates();
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                ore.set(x, 0, z, Blocks.STONE.defaultBlockState());
                clean.set(x, 0, z, Blocks.STONE.defaultBlockState());
                high.set(x, 0, z, Blocks.STONE.defaultBlockState());
            }
        }
        ore.set(3, 0, 3, Blocks.DIAMOND_ORE.defaultBlockState());
        high.set(1, 0, 1, Blocks.DIAMOND_ORE.defaultBlockState());
        var entry = new PaperXrayMaskManager.MaskEntry(
                PaperXrayMaskFilter.MaskSet.resolve(List.of("diamond_ore"), 32),
                dev.vox.lss.common.XrayMaskPolicy.FallbackKind.OVERWORLD, "test");
        var chunk = chunkNbt("minecraft:full",
                sectionFrom(0, ore, FACTORY.createForBiomes()),
                sectionFrom(1, clean, FACTORY.createForBiomes()),
                sectionFrom(4, high, FACTORY.createForBiomes()));
        byte[] transcoded = PaperNbtSectionSerializer.serializeChunkNbt(chunk, REGISTRY_ACCESS,
                entry, Integer.MIN_VALUE, Integer.MAX_VALUE, true);
        byte[] object = PaperNbtSectionSerializer.serializeChunkNbt(chunk, REGISTRY_ACCESS,
                entry, Integer.MIN_VALUE, Integer.MAX_VALUE, false);
        assertArrayEquals(object, transcoded, "mixed masked column must byte-match the object path");
        var sections = decode(transcoded);
        assertEquals(3, sections.size());
        assertEquals(Blocks.STONE.defaultBlockState(), sections.get(0).section().getBlockState(3, 0, 3),
                "the below-cutoff ore is masked to the dominant state");
        assertEquals(Blocks.DIAMOND_ORE.defaultBlockState(), sections.get(2).section().getBlockState(1, 0, 1),
                "the above-cutoff ore stays real (height gate)");
    }

    /** The exact pre-size must never fall back to the copy path — a mismatch means
     *  {@code getSerializedSize} drifted from {@code write} and the zero-copy steal died. */
    @Test
    void exactPreSizingNeverFallsBackAcrossTheCorpusShapes() {
        long before = PaperNbtSectionSerializer.SIZE_MISMATCH_FALLBACKS.get();
        byte[] sky = new byte[2048];
        Arrays.fill(sky, (byte) 0xFF);
        PaperNbtSectionSerializer.serializeChunkNbt(chunkNbt("minecraft:full",
                sectionNbt(-4, true, true, light(7, (byte) 5), sky),
                sectionNbt(0, true, false, null, null),
                sectionNbt(4, false, true, light(0, (byte) 1), null)), REGISTRY_ACCESS);
        PaperNbtSectionSerializer.serializeChunkNbt(chunkNbt("minecraft:full",
                sectionNbt(2, true, true, null, null)), REGISTRY_ACCESS);
        // Mixed column — see the Fabric twin: since the direct v20 emit, only a column
        // with a fallback section still exercises the exact-sizing arithmetic.
        var pool = new ArrayList<net.minecraft.world.level.block.state.BlockState>();
        for (var block : List.of(Blocks.OAK_STAIRS, Blocks.SPRUCE_STAIRS, Blocks.BIRCH_STAIRS,
                Blocks.JUNGLE_STAIRS)) {
            pool.addAll(block.getStateDefinition().getPossibleStates());
        }
        var globalStates = FACTORY.createForBlockStates();
        int gi = 0;
        for (int y = 0; y < 16; y++)
            for (int z = 0; z < 16; z++)
                for (int x = 0; x < 16; x++)
                    globalStates.set(x, y, z, pool.get(gi++ % pool.size()));
        long directBefore = PaperNbtSectionSerializer.DIRECT_V20_EMITS.get();
        PaperNbtSectionSerializer.serializeChunkNbt(chunkNbt("minecraft:full",
                sectionNbt(0, true, true, light(3, (byte) 9), null),
                sectionFrom(1, globalStates, FACTORY.createForBiomes())), REGISTRY_ACCESS);
        assertEquals(directBefore, PaperNbtSectionSerializer.DIRECT_V20_EMITS.get(),
                "premise: the mixed column took the sized-buffer route, not the direct emit");
        assertEquals(before, PaperNbtSectionSerializer.SIZE_MISMATCH_FALLBACKS.get(),
                "exact sizing fell back to the copy path — getSerializedSize drifted from write");
    }

    /** LINE FLAVOR (1.21.x): vanilla writes every block palette entry as a {Name, Properties}
     *  compound on this line (26.3 writes a property-less state as a bare string); renames
     *  the matching entries in place and returns how many it renamed. */
    private static int renamePaletteEntry(net.minecraft.nbt.ListTag palette, String from, String to) {
        int renamed = 0;
        for (var e : palette) {
            if (e instanceof CompoundTag pe && from.equals(pe.getStringOr("Name", ""))) {
                pe.putString("Name", to);
                renamed++;
            }
        }
        return renamed;
    }
}
