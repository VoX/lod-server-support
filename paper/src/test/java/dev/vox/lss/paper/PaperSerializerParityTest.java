package dev.vox.lss.paper;

import com.mojang.serialization.Lifecycle;
import dev.vox.lss.common.processing.LoadedColumnData;
import io.netty.buffer.Unpooled;
import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
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
import net.minecraft.world.level.chunk.PalettedContainer;
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
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Byte parity between Paper's two serializer paths: {@link PaperSectionSerializer} (live,
 * loaded chunks) vs {@link PaperNbtSectionSerializer} (disk NBT). The up-to-date economy
 * and DirtyContentFilter seeding require a disk serve to byte-match a live serve of the
 * same content; Fabric pins this end-to-end in the T2 SerializerParityGameTests, but Paper
 * has no gametest tier (check_soak.py records Paper NBT-vs-live parity as an unverified
 * follow-up) — so this test IS the Paper parity gate. Each case builds REAL
 * LevelChunkSections once, serializes them live (mocked ServerLevel/light-engine plumbing,
 * same harness pattern as PaperChunkGenerationServiceTest) and from the equivalent NBT,
 * and requires identical wire bytes.
 */
// new LevelChunkSection(TestContainerFactory) is @Deprecated on Paper (anti-xray overload),
// but is the canonical decode ctor; same suppression as NbtSectionSerializerTest.
@SuppressWarnings("deprecation")
class PaperSerializerParityTest {

    private static RegistryAccess REGISTRY_ACCESS;
    private static TestContainerFactory FACTORY;

    private static final int CX = 9, CZ = -6, MIN_SECTION_Y = -4;

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

    // ---- the two paths over the same in-memory content ----

    /** Live path: mocked ServerLevel/LevelChunk/light-engine around the real sections.
     *  Null DataLayer entries mean "light engine has no data for that section". */
    private LoadedColumnData serializeLive(LevelChunkSection[] sections, DataLayer[] blockLight, DataLayer[] skyLight) {
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
        return PaperSectionSerializer.serializeColumn(level, chunk, CX, CZ);
    }

    /** Disk path: region-file NBT equivalent of the SAME sections/light (vanilla save
     *  shape: codec-encoded containers, raw 2048-byte light arrays, Y = minSectionY + index). */
    private byte[] serializeNbt(LevelChunkSection[] sections, DataLayer[] blockLight, DataLayer[] skyLight) {
        var c = new CompoundTag();
        c.putString("Status", "minecraft:full");
        var list = new ListTag();
        for (int i = 0; i < sections.length; i++) {
            if (sections[i] == null) continue;
            var s = new CompoundTag();
            s.putInt("Y", MIN_SECTION_Y + i);
            s.put("block_states", FACTORY.blockStatesContainerCodec().encodeStart(NbtOps.INSTANCE, sections[i].getStates()).getOrThrow(false, msg -> {}));
            s.put("biomes", FACTORY.biomeContainerCodec().encodeStart(NbtOps.INSTANCE, sections[i].getBiomes()).getOrThrow(false, msg -> {}));
            if (blockLight[i] != null) s.putByteArray("BlockLight", blockLight[i].getData());
            if (skyLight[i] != null) s.putByteArray("SkyLight", skyLight[i].getData());
            list.add(s);
        }
        c.put("sections", list);
        return PaperNbtSectionSerializer.serializeChunkNbt(c, REGISTRY_ACCESS);
    }

    private static void assertWireBytesIdentical(byte[] nbtBytes, byte[] liveBytes) {
        int mismatch = Arrays.mismatch(nbtBytes, liveBytes);
        if (mismatch == -1) return;
        fail("NBT vs live wire bytes diverge at index " + mismatch
                + " (nbt " + nbtBytes.length + " B, live " + liveBytes.length + " B): nbt="
                + hexWindow(nbtBytes, mismatch) + " live=" + hexWindow(liveBytes, mismatch));
    }

    private static String hexWindow(byte[] b, int center) {
        int from = Math.max(0, center - 4);
        int to = Math.min(b.length, center + 4);
        var sb = new StringBuilder();
        for (int i = from; i < to; i++) sb.append(String.format("%02x", b[i]));
        return sb.toString();
    }

    // ---- content builders ----

    private LevelChunkSection stoneAndGlassSection() {
        var sec = new LevelChunkSection(FACTORY.biomeRegistry());
        sec.setBlockState(0, 0, 0, Blocks.STONE.defaultBlockState());
        sec.setBlockState(15, 15, 15, Blocks.GLASS.defaultBlockState());
        return normalized(sec);
    }

    private LevelChunkSection dirtSection() {
        var sec = new LevelChunkSection(FACTORY.biomeRegistry());
        sec.setBlockState(3, 4, 5, Blocks.DIRT.defaultBlockState());
        return normalized(sec);
    }

    /**
     * Codec round-trip (save -> read) so the palette is in vanilla's packed disk order. A
     * freshly set()-built container keeps evolution-ordered palette entries and legitimately
     * serializes differently from its own save until the chunk is reloaded; the disk-vs-live
     * parity contract is for disk-LOADED chunks (the warm-rejoin path), so fixtures must start
     * from the canonical disk shape both paths then share.
     */
    private LevelChunkSection normalized(LevelChunkSection sec) {
        var statesNbt = FACTORY.blockStatesContainerCodec().encodeStart(NbtOps.INSTANCE, sec.getStates()).getOrThrow(false, msg -> {});
        var biomesNbt = FACTORY.biomeContainerCodec().encodeStart(NbtOps.INSTANCE, sec.getBiomes()).getOrThrow(false, msg -> {});
        var states = FACTORY.blockStatesContainerCodec().parse(NbtOps.INSTANCE, statesNbt).getOrThrow(false, msg -> {});
        if (!(FACTORY.biomeContainerCodec().parse(NbtOps.INSTANCE, biomesNbt).getOrThrow(false, msg -> {})
                instanceof PalettedContainer<Holder<Biome>> biomes)) {
            throw new IllegalStateException("parsed biome container is not the concrete PalettedContainer");
        }
        var out = new LevelChunkSection(states, biomes);
        out.recalcBlockCounts();
        return out;
    }

    private static byte[] light(int index, byte value) {
        byte[] b = new byte[2048];
        b[index] = value;
        return b;
    }

    // ---- wire decode (mirrors the Fabric client's grammar; spot asserts only) ----

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

    // ---- parity cases ----

    @Test
    void mixedContentSparseColumn_liveAndNbtWireBytesIdentical() {
        var sections = new LevelChunkSection[]{stoneAndGlassSection(), null, dirtSection()};
        var blockLight = new DataLayer[]{new DataLayer(light(0, (byte) 0x1F)), null, null};
        var skyLight = new DataLayer[]{null, null, new DataLayer(light(9, (byte) 0xF0))};

        byte[] nbt = serializeNbt(sections, blockLight, skyLight);
        var live = serializeLive(sections, blockLight, skyLight);
        assertWireBytesIdentical(nbt, live.serializedSections());

        var decoded = decode(live.serializedSections());
        assertEquals(2, decoded.size(), "null section entry skipped by both paths");
        assertEquals(-4, decoded.get(0).y(), "live Y derives from minSectionY + index == the NBT Y tag");
        assertEquals(-2, decoded.get(1).y());
        assertTrue(decoded.get(0).hasBlockLight());
        assertFalse(decoded.get(0).hasSkyLight());
        assertFalse(decoded.get(1).hasBlockLight());
        assertTrue(decoded.get(1).hasSkyLight());
    }

    @Test
    void allZeroSavedLight_bothPathsOmitTheLayer_bytesIdentical() {
        // The 6c54607 bug class (WP-040): vanilla saves the light engine's
        // allocated-but-zeroed arrays, so PaperSectionSerializer's hasNonZeroData guards
        // and PaperNbtSectionSerializer's hasNonZeroNibble guards must agree — a
        // divergence makes every warm rejoin re-send columns whose only "change" is the
        // zeroed layer representation.
        var sections = new LevelChunkSection[]{stoneAndGlassSection()};
        var allZeroBl = new DataLayer[]{new DataLayer(new byte[2048])};
        var allZeroSl = new DataLayer[]{new DataLayer(new byte[2048])};

        byte[] nbt = serializeNbt(sections, allZeroBl, allZeroSl);
        var live = serializeLive(sections, allZeroBl, allZeroSl);
        assertWireBytesIdentical(nbt, live.serializedSections());

        var d = decode(live.serializedSections()).get(0);
        assertFalse(d.hasBlockLight(), "all-zero BlockLight omitted on both paths, never shipped");
        assertFalse(d.hasSkyLight(), "all-zero SkyLight omitted on both paths, never shipped");
    }

    @Test
    void airOnlyWithBlockLight_keptByBothPaths_bytesIdentical() {
        // H-13: an air section adjacent to lava carries propagated BLOCK light that must
        // ship. Both paths keep the air section for its block light alone.
        byte[] lavaGlow = light(100, (byte) 0x0E);
        var sections = new LevelChunkSection[]{new LevelChunkSection(FACTORY.biomeRegistry())};
        var blockLight = new DataLayer[]{new DataLayer(lavaGlow)};
        var skyLight = new DataLayer[]{null};

        byte[] nbt = serializeNbt(sections, blockLight, skyLight);
        var live = serializeLive(sections, blockLight, skyLight);
        assertWireBytesIdentical(nbt, live.serializedSections());

        var d = decode(live.serializedSections()).get(0);
        assertTrue(d.section().hasOnlyAir(), "the air-only section itself ships");
        assertTrue(d.hasBlockLight());
        assertArrayEquals(lavaGlow, d.blockLight());
    }

    @Test
    void airOnlyWithSkyLightOnly_droppedByBothPaths_asTheAllAirSentinel() {
        // Drop pinned as INTENT, identical on both paths: sky light alone never rescues an
        // air-only section (only block light does — the H-13 rule). The resulting all-air
        // column collapses to each path's sentinel: live = null serializedSections,
        // disk = empty byte[]; both resolve as "all air, found" upstream.
        var sections = new LevelChunkSection[]{new LevelChunkSection(FACTORY.biomeRegistry())};
        var blockLight = new DataLayer[]{null};
        var skyLight = new DataLayer[]{new DataLayer(light(100, (byte) 0x0F))};

        byte[] nbt = serializeNbt(sections, blockLight, skyLight);
        assertEquals(0, nbt.length, "disk path: all sections dropped -> empty array sentinel");

        var live = serializeLive(sections, blockLight, skyLight);
        assertNull(live.serializedSections(), "live path: all sections dropped -> null sentinel");
        assertEquals(0, live.estimatedBytes());
        assertEquals(CX, live.cx());
        assertEquals(CZ, live.cz());
    }
}
