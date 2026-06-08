package dev.vox.lss.networking.server;

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
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Coverage for {@link NbtSectionSerializer#serializeChunkNbt} — the region-NBT -> MC-native
 * wire-bytes path used by the async disk reader. Builds in-memory chunk NBT, serializes it, and
 * round-trips the output through the same wire grammar the client decodes with, asserting the
 * section data and light survive and that the air-only / missing-biome / status edge cases behave.
 */
class NbtSectionSerializerTest {

    private static RegistryAccess REGISTRY_ACCESS;
    private static PalettedContainerFactory FACTORY;

    @BeforeAll
    static void setup() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        REGISTRY_ACCESS = buildRegistryAccess();
        FACTORY = PalettedContainerFactory.create(REGISTRY_ACCESS);
    }

    /**
     * Build a RegistryAccess carrying the BIOME registry (the only registry
     * {@link PalettedContainerFactory#create} reads — block states come from static bootstrap
     * state). VanillaRegistries.createLookup() is MC's canonical full-vanilla-registry builder
     * (also used by data generation in a bare JVM), so it sidesteps the data-pack/tag wiring.
     */
    private static RegistryAccess buildRegistryAccess() {
        HolderLookup.Provider provider = VanillaRegistries.createLookup();
        HolderLookup.RegistryLookup<Biome> src = provider.lookupOrThrow(Registries.BIOME);
        MappedRegistry<Biome> biomes = new MappedRegistry<>(Registries.BIOME, Lifecycle.stable());
        src.listElements().forEach(ref -> biomes.register(ref.key(), ref.value(), RegistrationInfo.BUILT_IN));
        biomes.freeze();
        return new RegistryAccess.ImmutableRegistryAccess(List.of(biomes));
    }

    // ---- NBT builders ----

    private CompoundTag chunkNbt(String status, CompoundTag... sections) {
        var c = new CompoundTag();
        if (status != null) c.putString("Status", status);
        var list = new ListTag();
        for (var s : sections) list.add(s);
        c.put("sections", list);
        return c;
    }

    /** A section CompoundTag carrying a STONE block at (0,0,0) when {@code stone}. */
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

    // ---- Wire decode (mirrors ClientColumnProcessor) ----

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

    // ---- Tests ----

    @Test
    void happyPath_stoneSection_roundTrips() {
        byte[] wire = NbtSectionSerializer.serializeChunkNbt(
                chunkNbt("minecraft:full", sectionNbt(0, true, true, null, null)), REGISTRY_ACCESS);
        var sections = decode(wire);
        assertEquals(1, sections.size());
        assertEquals(0, sections.get(0).y());
        assertFalse(sections.get(0).section().hasOnlyAir());
        assertEquals(Blocks.STONE.defaultBlockState(), sections.get(0).section().getBlockState(0, 0, 0));
        assertFalse(sections.get(0).hasBlockLight());
        assertFalse(sections.get(0).hasSkyLight());
    }

    @Test
    void lightBytesPreserved() {
        byte[] bl = light(0, (byte) 0x10);
        byte[] sl = light(5, (byte) 0x0F);
        byte[] wire = NbtSectionSerializer.serializeChunkNbt(
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
        byte[] wire = NbtSectionSerializer.serializeChunkNbt(
                chunkNbt("minecraft:full", sectionNbt(3, false, true, bl, null)), REGISTRY_ACCESS);
        var sections = decode(wire);
        assertEquals(1, sections.size());
        assertTrue(sections.get(0).section().hasOnlyAir());
        assertTrue(sections.get(0).hasBlockLight());
        assertArrayEquals(bl, sections.get(0).blockLight());
    }

    @Test
    void airOnly_zeroBlockLight_dropped() {
        byte[] wire = NbtSectionSerializer.serializeChunkNbt(
                chunkNbt("minecraft:full", sectionNbt(0, false, true, new byte[2048], new byte[2048])), REGISTRY_ACCESS);
        assertEquals(0, wire.length, "all-air, no meaningful light -> empty byte[]");
    }

    @Test
    void airOnly_noLightTag_dropped() {
        byte[] wire = NbtSectionSerializer.serializeChunkNbt(
                chunkNbt("minecraft:full", sectionNbt(0, false, true, null, null)), REGISTRY_ACCESS);
        assertEquals(0, wire.length);
    }

    @Test
    void missingBlockStates_sectionDropped() {
        var noStates = new CompoundTag();
        noStates.putInt("Y", 1);
        noStates.put("biomes", FACTORY.biomeContainerCodec()
                .encodeStart(NbtOps.INSTANCE, new LevelChunkSection(FACTORY).getBiomes()).getOrThrow());
        byte[] wire = NbtSectionSerializer.serializeChunkNbt(
                chunkNbt("minecraft:full", noStates, sectionNbt(2, true, true, null, null)), REGISTRY_ACCESS);
        var sections = decode(wire);
        assertEquals(1, sections.size(), "only the valid section survives");
        assertEquals(2, sections.get(0).y());
    }

    @Test
    void missingBiomes_defaultBiomePath_roundTrips() {
        byte[] wire = NbtSectionSerializer.serializeChunkNbt(
                chunkNbt("minecraft:full", sectionNbt(0, true, false, null, null)), REGISTRY_ACCESS);
        var sections = decode(wire);
        assertEquals(1, sections.size());
        assertEquals(Blocks.STONE.defaultBlockState(), sections.get(0).section().getBlockState(0, 0, 0));
    }

    @Test
    void statusNotFull_returnsNull() {
        assertNull(NbtSectionSerializer.serializeChunkNbt(
                chunkNbt("minecraft:features", sectionNbt(0, true, true, null, null)), REGISTRY_ACCESS));
    }

    @Test
    void statusMissing_returnsNull() {
        assertNull(NbtSectionSerializer.serializeChunkNbt(
                chunkNbt(null, sectionNbt(0, true, true, null, null)), REGISTRY_ACCESS));
    }

    @Test
    void noSectionsList_returnsNull() {
        var c = new CompoundTag();
        c.putString("Status", "minecraft:full");
        assertNull(NbtSectionSerializer.serializeChunkNbt(c, REGISTRY_ACCESS));
    }

    @Test
    void sectionMissingY_skipped() {
        var noY = new CompoundTag();
        noY.put("block_states", FACTORY.blockStatesContainerCodec()
                .encodeStart(NbtOps.INSTANCE, new LevelChunkSection(FACTORY).getStates()).getOrThrow());
        byte[] wire = NbtSectionSerializer.serializeChunkNbt(
                chunkNbt("minecraft:full", noY, sectionNbt(7, true, true, null, null)), REGISTRY_ACCESS);
        var sections = decode(wire);
        assertEquals(1, sections.size());
        assertEquals(7, sections.get(0).y());
    }

    @Test
    void multiSection_orderAndNegativeY() {
        byte[] wire = NbtSectionSerializer.serializeChunkNbt(
                chunkNbt("minecraft:full",
                        sectionNbt(0, true, true, null, null),
                        sectionNbt(-4, true, true, null, null)),
                REGISTRY_ACCESS);
        var sections = decode(wire);
        assertEquals(2, sections.size());
        assertEquals(0, sections.get(0).y());
        assertEquals(-4, sections.get(1).y(), "negative section Y survives the signed-byte write/read");
    }

    @Test
    void airOnly_skyLightOnly_dropped() {
        // parseSection only consults BlockLight when deciding to keep an air-only section;
        // a non-zero SkyLight alone does not rescue it.
        byte[] wire = NbtSectionSerializer.serializeChunkNbt(
                chunkNbt("minecraft:full", sectionNbt(3, false, true, null, light(100, (byte) 0x0F))), REGISTRY_ACCESS);
        assertEquals(0, wire.length, "air-only with only SkyLight (no BlockLight) is dropped");
    }

    @Test
    void multiBlockSection_paletteRoundTrips() {
        var states = FACTORY.createForBlockStates();
        states.set(0, 0, 0, Blocks.STONE.defaultBlockState());
        states.set(15, 15, 15, Blocks.DIRT.defaultBlockState());
        states.set(7, 8, 9, Blocks.GLASS.defaultBlockState());
        var section = decode(NbtSectionSerializer.serializeChunkNbt(
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
        var section = decode(NbtSectionSerializer.serializeChunkNbt(
                chunkNbt("minecraft:full", sectionFrom(0, states, biomes)), REGISTRY_ACCESS))
                .get(0).section();
        assertTrue(section.getBiomes().get(0, 0, 0).is(Biomes.DESERT), "non-default biome (DESERT) survives the round-trip");
        assertEquals(Blocks.STONE.defaultBlockState(), section.getBlockState(0, 0, 0));
    }

    /** Build a section CompoundTag from explicit state/biome containers (for multi-entry palettes). */
    private CompoundTag sectionFrom(int y, net.minecraft.world.level.chunk.PalettedContainer<net.minecraft.world.level.block.state.BlockState> states,
                                    net.minecraft.world.level.chunk.PalettedContainer<net.minecraft.core.Holder<Biome>> biomes) {
        var s = new CompoundTag();
        s.putInt("Y", y);
        s.put("block_states", FACTORY.blockStatesContainerCodec().encodeStart(NbtOps.INSTANCE, states).getOrThrow());
        s.put("biomes", FACTORY.biomeContainerCodec().encodeStart(NbtOps.INSTANCE, biomes).getOrThrow());
        return s;
    }
}
