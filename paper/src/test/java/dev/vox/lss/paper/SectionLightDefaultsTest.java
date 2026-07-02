package dev.vox.lss.paper;

import com.mojang.serialization.Lifecycle;
import io.netty.buffer.Unpooled;
import net.minecraft.SharedConstants;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.lighting.LayerLightEventListener;
import net.minecraft.world.level.lighting.LevelLightEngine;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Paper twin of the Fabric {@code dev.vox.lss.networking.server.SectionLightDefaultsTest}:
 * per-dimension light defaults for MISSING DataLayers (the H-12/H-13 regression class),
 * pinned on BOTH Paper serializer paths.
 *
 * Neither {@link PaperNbtSectionSerializer} (disk) nor {@link PaperSectionSerializer}
 * (live) sees a dimension: an absent light layer is one false presence flag on the wire,
 * and the Fabric client (which decodes Paper's bytes with the identical grammar — mirrored
 * by {@code decode()} below) leaves the corresponding consumer DataLayer NULL. "Absent
 * means all-zero" is the single pinned default for EVERY dimension; consumers derive
 * vanilla-style dimension lighting (overworld sky-lit vs nether/end hasSkyLight()=false)
 * from DimensionType themselves.
 *
 * The live path runs against a mocked ServerLevel/light engine around REAL sections —
 * Paper has no gametest tier, so this is the only executable home for the live flag rules
 * (same harness pattern as PaperChunkGenerationServiceTest).
 */
// new LevelChunkSection(TestContainerFactory) is @Deprecated on Paper (anti-xray overload),
// but is the canonical decode ctor; same suppression as NbtSectionSerializerTest.
@SuppressWarnings("deprecation")
class SectionLightDefaultsTest {

    private static RegistryAccess REGISTRY_ACCESS;
    private static TestContainerFactory FACTORY;

    @BeforeAll
    static void setup() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        HolderLookup.Provider provider = VanillaRegistries.createLookup();
        HolderLookup.RegistryLookup<Biome> src = provider.lookupOrThrow(Registries.BIOME);
        MappedRegistry<Biome> biomes = new MappedRegistry<>(Registries.BIOME, Lifecycle.stable());
        src.listElements().forEach(ref -> biomes.register(ref.key(), ref.value(), Lifecycle.stable()));
        biomes.freeze();
        REGISTRY_ACCESS = new RegistryAccess.ImmutableRegistryAccess(List.of(biomes));
        FACTORY = TestContainerFactory.create(REGISTRY_ACCESS);
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
        var sec = new LevelChunkSection(FACTORY.biomeRegistry());
        sec.setBlockState(0, 0, 0, Blocks.STONE.defaultBlockState());
        var s = new CompoundTag();
        s.putInt("Y", y);
        s.put("block_states", FACTORY.blockStatesContainerCodec().encodeStart(NbtOps.INSTANCE, sec.getStates()).getOrThrow(false, msg -> {}));
        s.put("biomes", FACTORY.biomeContainerCodec().encodeStart(NbtOps.INSTANCE, sec.getBiomes()).getOrThrow(false, msg -> {}));
        if (blockLight != null) s.putByteArray("BlockLight", blockLight);
        if (skyLight != null) s.putByteArray("SkyLight", skyLight);
        return s;
    }

    private static byte[] light(int index, byte value) {
        byte[] b = new byte[2048];
        b[index] = value;
        return b;
    }

    // ---- Wire decode (mirrors the Fabric client's ClientColumnProcessor) ----

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
                var section = new LevelChunkSection(FACTORY.biomeRegistry());
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

    // ---- NBT-path save-shape matrix ----

    @Test
    void netherSaveShape_blockLightOnly_skyLayerNullAtDecode() {
        // Vanilla never saves SkyLight where DimensionType.hasSkyLight() is false (nether,
        // end). The serializer ships the absence verbatim; the client-side DataLayer stays
        // null and the consumer must pick its own dimension default (H-12: treating null
        // as sky-lit made nether air glow).
        byte[] bl = light(7, (byte) 0x0F);
        byte[] wire = PaperNbtSectionSerializer.serializeChunkNbt(
                chunkNbt(sectionNbt(4, bl, null)), REGISTRY_ACCESS);
        var sections = decode(wire);
        assertEquals(1, sections.size());
        assertTrue(sections.get(0).hasBlockLight(), "saved BlockLight ships");
        assertArrayEquals(bl, sections.get(0).blockLight());
        assertFalse(sections.get(0).hasSkyLight(), "unsaved SkyLight stays absent, not synthesized");
        assertNull(sections.get(0).skyLight());
    }

    @Test
    void overworldSaveShape_uniform15SkyShipsVerbatim_absenceIsNeverSynthesized() {
        // Two sections with identical block content; only one carries the overworld
        // daylight layer. Presence is purely data-driven: the daylight section ships its
        // 0xFF bytes verbatim, the bare section decodes a NULL sky layer — nothing in the
        // path fabricates a "full bright because overworld" default for it.
        byte[] daylight = new byte[2048];
        Arrays.fill(daylight, (byte) 0xFF);
        byte[] wire = PaperNbtSectionSerializer.serializeChunkNbt(
                chunkNbt(sectionNbt(0, null, daylight), sectionNbt(1, null, null)), REGISTRY_ACCESS);
        var sections = decode(wire);
        assertEquals(2, sections.size());
        assertTrue(sections.get(0).hasSkyLight());
        assertArrayEquals(daylight, sections.get(0).skyLight(), "uniform-15 sky ships verbatim");
        assertFalse(sections.get(1).hasSkyLight(), "the same content without a saved layer decodes absent");
        assertNull(sections.get(1).skyLight());
        assertFalse(sections.get(0).hasBlockLight());
        assertFalse(sections.get(1).hasBlockLight());
    }

    @Test
    void endVoidSaveShape_noLightTags_bothLayersNullAtDecode() {
        byte[] wire = PaperNbtSectionSerializer.serializeChunkNbt(
                chunkNbt(sectionNbt(0, null, null)), REGISTRY_ACCESS);
        var sections = decode(wire);
        assertEquals(1, sections.size());
        assertFalse(sections.get(0).hasBlockLight(), "no BlockLight tag -> absent on the wire");
        assertFalse(sections.get(0).hasSkyLight(), "no SkyLight tag -> absent on the wire");
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
        byte[] withoutTags = PaperNbtSectionSerializer.serializeChunkNbt(
                chunkNbt(sectionNbt(0, null, null)), REGISTRY_ACCESS);
        byte[] withAllZeroTags = PaperNbtSectionSerializer.serializeChunkNbt(
                chunkNbt(sectionNbt(0, new byte[2048], new byte[2048])), REGISTRY_ACCESS);
        assertArrayEquals(withoutTags, withAllZeroTags,
                "absent and all-zero light layers are indistinguishable on the wire");
        assertTrue(withoutTags.length > 0, "the non-air section itself still serves");
    }

    // ---- Live-path legs (PaperSectionSerializer over a mocked level/light engine) ----

    private static final int CX = 5, CZ = -3, MIN_SECTION_Y = -4;

    /** Mocked ServerLevel/LevelChunk plumbing around REAL sections; null DataLayer array
     *  entries mean "light engine has no data for that section" (listener returns null). */
    private byte[] serializeLive(LevelChunkSection[] sections, DataLayer[] blockLight, DataLayer[] skyLight) {
        var blockListener = mock(LayerLightEventListener.class);
        var skyListener = mock(LayerLightEventListener.class);
        for (int i = 0; i < sections.length; i++) {
            var pos = SectionPos.of(CX, MIN_SECTION_Y + i, CZ);
            if (blockLight[i] != null) when(blockListener.getDataLayerData(pos)).thenReturn(blockLight[i]);
            if (skyLight[i] != null) when(skyListener.getDataLayerData(pos)).thenReturn(skyLight[i]);
        }
        var lightEngine = mock(LevelLightEngine.class);
        when(lightEngine.getLayerListener(LightLayer.BLOCK)).thenReturn(blockListener);
        when(lightEngine.getLayerListener(LightLayer.SKY)).thenReturn(skyListener);
        ServerLevel level = mock(ServerLevel.class);
        when(level.getMinSection()).thenReturn(MIN_SECTION_Y);
        when(level.getLightEngine()).thenReturn(lightEngine);
        var chunk = mock(LevelChunk.class);
        when(chunk.getSections()).thenReturn(sections);
        return PaperSectionSerializer.serializeColumn(level, chunk, CX, CZ).serializedSections();
    }

    private LevelChunkSection stoneSection() {
        var sec = new LevelChunkSection(FACTORY.biomeRegistry());
        sec.setBlockState(0, 0, 0, Blocks.STONE.defaultBlockState());
        return sec;
    }

    @Test
    void livePath_missingDataLayers_absentFlagsOnWire() {
        // A live section whose light engine has no DataLayer for either layer writes two
        // false flags — the same wire shape as the missing-tag NBT path above, so disk and
        // live serves of the same content cannot diverge on light absence.
        byte[] wire = serializeLive(new LevelChunkSection[]{stoneSection()},
                new DataLayer[]{null}, new DataLayer[]{null});
        var sections = decode(wire);
        assertEquals(1, sections.size());
        assertEquals(MIN_SECTION_Y, sections.get(0).y(), "sectionY derives from minSectionY + index");
        assertFalse(sections.get(0).hasBlockLight());
        assertFalse(sections.get(0).hasSkyLight());
    }

    @Test
    void livePath_allZeroDataLayers_wireIdenticalToMissingLayers() {
        // The live twin of the absent==all-zero pin: allocated-but-zero DataLayers (the
        // light engine keeps them after sources are removed) are omitted by the
        // hasNonZeroData guards, byte-identical to layers the engine never had.
        byte[] missing = serializeLive(new LevelChunkSection[]{stoneSection()},
                new DataLayer[]{null}, new DataLayer[]{null});
        byte[] allZero = serializeLive(new LevelChunkSection[]{stoneSection()},
                new DataLayer[]{new DataLayer(new byte[2048])},
                new DataLayer[]{new DataLayer(new byte[2048])});
        assertTrue(missing != null && missing.length > 0, "the non-air section itself still serves");
        assertArrayEquals(missing, allZero,
                "allocated-but-all-zero live layers are omitted, not shipped");
    }

    @Test
    void livePath_presentLayers_shipVerbatim_acrossSparseSections() {
        // Sparse column: [stone, null, stone] with per-section light keyed by SectionPos.
        // The null array entry is skipped, Ys map from minSectionY + index, and each
        // section's own layers ship verbatim under true flags.
        byte[] blA = light(0, (byte) 0x1F);
        byte[] slB = light(5, (byte) 0x0F);
        byte[] wire = serializeLive(
                new LevelChunkSection[]{stoneSection(), null, stoneSection()},
                new DataLayer[]{new DataLayer(blA), null, null},
                new DataLayer[]{null, null, new DataLayer(slB)});
        var sections = decode(wire);
        assertEquals(2, sections.size(), "null section array entries are skipped");
        assertEquals(-4, sections.get(0).y());
        assertTrue(sections.get(0).hasBlockLight());
        assertArrayEquals(blA, sections.get(0).blockLight());
        assertFalse(sections.get(0).hasSkyLight());
        assertEquals(-2, sections.get(1).y());
        assertFalse(sections.get(1).hasBlockLight());
        assertTrue(sections.get(1).hasSkyLight());
        assertArrayEquals(slB, sections.get(1).skyLight());
    }
}
