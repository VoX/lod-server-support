package dev.vox.lss.networking.server;

import com.mojang.serialization.Lifecycle;
import dev.vox.lss.api.VoxelColumnData;
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
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Per-dimension light defaults for MISSING DataLayers (the H-12/H-13 regression class:
 * nether air rendered sky-lit, lava block light lost).
 *
 * The wire format carries no dimension-conditional defaulting anywhere:
 * {@code NbtSectionSerializer.serializeChunkNbt} never sees a dimension (its only inputs
 * are the chunk NBT and the registry), an absent light layer is one false presence flag,
 * and the client decode ({@code ClientColumnProcessor}) leaves the corresponding
 * {@link VoxelColumnData.SectionData} DataLayer NULL. "Absent means all-zero" is the single
 * pinned default for EVERY dimension; a consumer wanting vanilla-style dimension lighting
 * (overworld sky-lit air vs nether/end {@code hasSkyLight()=false}) must derive it from
 * DimensionType itself — H-12 re-ships the moment any layer of this path starts
 * synthesizing per-dimension defaults.
 *
 * The matrix drives the three vanilla save shapes: overworld (SkyLight saved, commonly
 * uniform 15), nether (BlockLight only), end void (no light tags at all). The live
 * SectionSerializer twin of these rules cannot run in Tier 1 (a real ServerLevel/light
 * engine needs a running server, and the fabric test module has no mocking library);
 * disk-vs-live byte parity on real chunks is covered by the T2 SerializerParityGameTests,
 * and the Paper live serializer twin is pinned directly in the paper-module
 * SectionLightDefaultsTest.
 */
class SectionLightDefaultsTest {

    private static RegistryAccess REGISTRY_ACCESS;
    private static PalettedContainerFactory FACTORY;

    @BeforeAll
    static void setup() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        HolderLookup.Provider provider = VanillaRegistries.createLookup();
        HolderLookup.RegistryLookup<Biome> src = provider.lookupOrThrow(Registries.BIOME);
        MappedRegistry<Biome> biomes = new MappedRegistry<>(Registries.BIOME, Lifecycle.stable());
        src.listElements().forEach(ref -> biomes.register(ref.key(), ref.value(), RegistrationInfo.BUILT_IN));
        biomes.freeze();
        REGISTRY_ACCESS = new RegistryAccess.ImmutableRegistryAccess(List.of(biomes));
        FACTORY = PalettedContainerFactory.create(REGISTRY_ACCESS);
    }

    // ---- NBT builders (same grammar as NbtSectionSerializerTest) ----

    private CompoundTag chunkNbt(CompoundTag... sections) {
        var c = new CompoundTag();
        c.putString("Status", "minecraft:full");
        var list = new ListTag();
        for (var s : sections) list.add(s);
        c.put("sections", list);
        return c;
    }

    private CompoundTag sectionNbt(int y, byte[] blockLight, byte[] skyLight) {
        var sec = new LevelChunkSection(FACTORY);
        sec.setBlockState(0, 0, 0, Blocks.STONE.defaultBlockState());
        var s = new CompoundTag();
        s.putInt("Y", y);
        s.put("block_states", FACTORY.blockStatesContainerCodec().encodeStart(NbtOps.INSTANCE, sec.getStates()).getOrThrow());
        s.put("biomes", FACTORY.biomeContainerCodec().encodeStart(NbtOps.INSTANCE, sec.getBiomes()).getOrThrow());
        if (blockLight != null) s.putByteArray("BlockLight", blockLight);
        if (skyLight != null) s.putByteArray("SkyLight", skyLight);
        return s;
    }

    private static byte[] light(int index, byte value) {
        byte[] b = new byte[2048];
        b[index] = value;
        return b;
    }

    /**
     * Decodes wire bytes into the exact consumer-facing records, mirroring
     * {@code ClientColumnProcessor.drainColumnQueue} statement-for-statement: a false
     * presence flag leaves the {@link VoxelColumnData.SectionData} DataLayer NULL — never
     * a zeroed array, never a dimension-derived default.
     */
    private List<VoxelColumnData.SectionData> decodeAsConsumerSections(byte[] wire) {
        var buf = new FriendlyByteBuf(Unpooled.wrappedBuffer(wire));
        try {
            int count = buf.readVarInt();
            var out = new ArrayList<VoxelColumnData.SectionData>(count);
            for (int i = 0; i < count; i++) {
                int sectionY = buf.readByte();
                var section = new LevelChunkSection(FACTORY);
                section.read(buf);
                DataLayer blockLight = null;
                if (buf.readBoolean()) {
                    byte[] lightBytes = new byte[2048];
                    buf.readBytes(lightBytes);
                    blockLight = new DataLayer(lightBytes);
                }
                DataLayer skyLight = null;
                if (buf.readBoolean()) {
                    byte[] lightBytes = new byte[2048];
                    buf.readBytes(lightBytes);
                    skyLight = new DataLayer(lightBytes);
                }
                out.add(new VoxelColumnData.SectionData(sectionY, section, blockLight, skyLight));
            }
            assertEquals(0, buf.readableBytes(), "wire buffer fully drained");
            return out;
        } finally {
            buf.release();
        }
    }

    // ---- Save-shape matrix ----

    @Test
    void netherSaveShape_blockLightOnly_skyLayerNullAtConsumer() {
        // Vanilla never saves SkyLight where DimensionType.hasSkyLight() is false (nether,
        // end). The serializer ships the absence verbatim and the consumer record's
        // skyLight() is NULL — the consumer must pick its own dimension default (H-12:
        // treating null as sky-lit made nether air glow).
        byte[] bl = light(7, (byte) 0x0F);
        byte[] wire = NbtSectionSerializer.serializeChunkNbt(
                chunkNbt(sectionNbt(4, bl, null)), REGISTRY_ACCESS);
        var sections = decodeAsConsumerSections(wire);
        assertEquals(1, sections.size());
        assertNotNull(sections.get(0).blockLight(), "saved BlockLight ships");
        assertArrayEquals(bl, sections.get(0).blockLight().getData());
        assertNull(sections.get(0).skyLight(), "unsaved SkyLight is null at the consumer, not synthesized");
    }

    @Test
    void overworldSaveShape_uniform15SkyShipsVerbatim_absenceIsNeverSynthesized() {
        // Two sections with identical block content; only one carries the overworld
        // daylight layer. Presence is purely data-driven: the daylight section ships its
        // 0xFF bytes verbatim, the bare section decodes a NULL sky layer — nothing in the
        // path fabricates a "full bright because overworld" default for it.
        byte[] daylight = new byte[2048];
        Arrays.fill(daylight, (byte) 0xFF);
        byte[] wire = NbtSectionSerializer.serializeChunkNbt(
                chunkNbt(sectionNbt(0, null, daylight), sectionNbt(1, null, null)), REGISTRY_ACCESS);
        var sections = decodeAsConsumerSections(wire);
        assertEquals(2, sections.size());
        assertNotNull(sections.get(0).skyLight());
        assertArrayEquals(daylight, sections.get(0).skyLight().getData(), "uniform-15 sky ships verbatim");
        assertNull(sections.get(1).skyLight(), "the same content without a saved layer decodes null");
        assertNull(sections.get(0).blockLight());
        assertNull(sections.get(1).blockLight());
    }

    @Test
    void endVoidSaveShape_noLightTags_bothLayersNullAtConsumer() {
        byte[] wire = NbtSectionSerializer.serializeChunkNbt(
                chunkNbt(sectionNbt(0, null, null)), REGISTRY_ACCESS);
        var sections = decodeAsConsumerSections(wire);
        assertEquals(1, sections.size());
        assertNull(sections.get(0).blockLight(), "no BlockLight tag -> null at the consumer");
        assertNull(sections.get(0).skyLight(), "no SkyLight tag -> null at the consumer");
        assertEquals(Blocks.STONE.defaultBlockState(), sections.get(0).section().getBlockState(0, 0, 0),
                "block content is unaffected by absent light");
    }

    @Test
    void absentLayerIsWireIdenticalToAllZeroLayer_theOnlyPinnedDefault() {
        // THE pinned default, identical for every dimension: "absent means all-zero" on the
        // wire. A section saved with allocated-but-zero light arrays (vanilla does this
        // after light sources are removed) serializes byte-identically to one saved with no
        // light tags at all — so decode-side null can only ever mean zero light, and no
        // per-dimension branch can exist anywhere between region file and consumer.
        byte[] withoutTags = NbtSectionSerializer.serializeChunkNbt(
                chunkNbt(sectionNbt(0, null, null)), REGISTRY_ACCESS);
        byte[] withAllZeroTags = NbtSectionSerializer.serializeChunkNbt(
                chunkNbt(sectionNbt(0, new byte[2048], new byte[2048])), REGISTRY_ACCESS);
        assertArrayEquals(withoutTags, withAllZeroTags,
                "absent and all-zero light layers are indistinguishable on the wire");
        assertTrue(withoutTags.length > 0, "the non-air section itself still serves");
    }
}
