package dev.vox.lss.networking.server;

import com.mojang.serialization.Codec;
import dev.vox.lss.common.LSSConstants;
import io.netty.buffer.Unpooled;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.world.level.chunk.Strategy;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.util.concurrent.TimeUnit;

/**
 * Reads chunk NBT from disk and serializes sections into MC-native wire format.
 * Used by {@link ChunkDiskReader} for async disk reads.
 */
final class NbtSectionSerializer {
    private NbtSectionSerializer() {}

    private static final byte[] EMPTY = new byte[0];

    /**
     * Read chunk NBT from disk, verify FULL status, and serialize sections
     * into MC-native wire format.
     * Returns the serialized byte array, or null if the chunk is missing/not FULL/empty.
     */
    static byte[] readAndSerializeSections(ChunkMap chunkMap, RegistryAccess registryAccess,
                                            int cx, int cz) throws Exception {
        var future = chunkMap.read(new ChunkPos(cx, cz));
        var optionalTag = future.get(LSSConstants.DISK_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (optionalTag.isEmpty()) return null;
        var chunkNbt = optionalTag.get();

        var statusStr = chunkNbt.getStringOr("Status", null);
        if (statusStr == null || ChunkStatus.byName(statusStr) != ChunkStatus.FULL) return null;

        var factory = PalettedContainerFactory.create(registryAccess);
        var blockStateCodec = factory.blockStatesContainerCodec();
        var biomeCodec = factory.biomeContainerCodec();
        var biomeRegistry = registryAccess.lookupOrThrow(Registries.BIOME);
        var defaultBiome = biomeRegistry.getOrThrow(Biomes.PLAINS);
        var biomeHolderMap = biomeRegistry.asHolderIdMap();

        var sectionsTag = chunkNbt.getList("sections");
        if (sectionsTag.isEmpty()) return null;

        var sectionsList = sectionsTag.orElseThrow();

        // First pass: parse sections and check if any are non-empty
        record ParsedSection(int sectionY, LevelChunkSection section, byte[] blockLight, byte[] skyLight) {}
        var parsed = new java.util.ArrayList<ParsedSection>(sectionsList.size());

        for (var sectionElement : sectionsList) {
            var sectionTag = (CompoundTag) sectionElement;
            int sectionY = sectionTag.getIntOr("Y", Integer.MIN_VALUE);
            if (sectionY == Integer.MIN_VALUE) continue;

            byte[] blockLightData = sectionTag.getByteArray("BlockLight").orElse(EMPTY);
            var result = parseSection(sectionTag, sectionY, blockStateCodec, biomeCodec,
                    defaultBiome, biomeHolderMap, blockLightData);
            if (result != null) {
                byte[] skyLightData = sectionTag.getByteArray("SkyLight").orElse(EMPTY);
                parsed.add(new ParsedSection(sectionY, result, blockLightData, skyLightData));
            }
        }

        if (parsed.isEmpty()) return new byte[0];

        // Second pass: serialize to wire format
        var buf = new FriendlyByteBuf(Unpooled.buffer(parsed.size() * 1024));
        try {
            buf.writeVarInt(parsed.size());
            for (var p : parsed) {
                buf.writeByte(p.sectionY);
                p.section.write(buf);

                boolean hasBlockLight = p.blockLight.length == 2048;
                buf.writeBoolean(hasBlockLight);
                if (hasBlockLight) {
                    buf.writeBytes(p.blockLight);
                }

                boolean hasSkyLight = p.skyLight.length == 2048;
                buf.writeBoolean(hasSkyLight);
                if (hasSkyLight) {
                    buf.writeBytes(p.skyLight);
                }
            }

            byte[] result = new byte[buf.readableBytes()];
            buf.readBytes(result);
            return result;
        } finally {
            buf.release();
        }
    }

    /**
     * Parse a section NBT tag into a LevelChunkSection.
     * Returns null if the section has no block states or only air (and no block light).
     */
    private static LevelChunkSection parseSection(
            CompoundTag sectionTag, int sectionY,
            Codec<PalettedContainer<BlockState>> blockStateCodec,
            Codec<PalettedContainerRO<Holder<Biome>>> biomeCodec,
            Holder<Biome> defaultBiome,
            net.minecraft.core.IdMap<Holder<Biome>> biomeHolderMap,
            byte[] blockLightData) {

        var blockStatesOpt = sectionTag.getCompound("block_states");
        if (blockStatesOpt.isEmpty()) return null;

        var blockStatesResult = blockStateCodec.parse(NbtOps.INSTANCE, blockStatesOpt.get());
        var blockStates = blockStatesResult.result().orElse(null);
        if (blockStates == null) return null;

        PalettedContainerRO<Holder<Biome>> biomes;
        var optBiomes = sectionTag.getCompound("biomes");
        if (optBiomes.isPresent()) {
            var biomesResult = biomeCodec.parse(NbtOps.INSTANCE, optBiomes.get());
            biomes = biomesResult.result().orElse(null);
        } else {
            biomes = null;
        }

        LevelChunkSection section;
        if (biomes instanceof PalettedContainer<Holder<Biome>> biomeContainer) {
            section = new LevelChunkSection(blockStates, biomeContainer);
        } else {
            var defaultBiomeContainer = new PalettedContainer<>(
                    defaultBiome, Strategy.createForBiomes(biomeHolderMap));
            section = new LevelChunkSection(blockStates, defaultBiomeContainer);
        }

        if (section.hasOnlyAir()) {
            if (blockLightData.length != 2048) {
                return null;
            }
            // Check if block light has any non-zero nibbles
            boolean hasLight = false;
            for (byte b : blockLightData) {
                if (b != 0) { hasLight = true; break; }
            }
            if (!hasLight) return null;
        }

        return section;
    }
}
