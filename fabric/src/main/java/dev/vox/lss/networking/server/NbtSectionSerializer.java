package dev.vox.lss.networking.server;

import com.mojang.serialization.Codec;
import dev.vox.lss.common.LSSConstants;
import io.netty.buffer.Unpooled;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;

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
        return serializeChunkNbt(optionalTag.get(), registryAccess);
    }

    /**
     * Serialize a chunk's NBT (as read from a region file) into MC-native wire format.
     * Returns {@code null} if the chunk is not FULL or has no sections, an empty array if every
     * section is empty, or the serialized section bytes. Package-visible for testing.
     */
    static byte[] serializeChunkNbt(CompoundTag chunkNbt, RegistryAccess registryAccess) {
        var statusStr = chunkNbt.getString("Status");
        if (statusStr.isEmpty() || ChunkStatus.byName(statusStr) != ChunkStatus.FULL) return null;

        var biomeRegistry = registryAccess.registryOrThrow(Registries.BIOME);
        // Same codec vanilla's ChunkSerializer.BLOCK_STATE_CODEC wraps (that field is private
        // on Fabric mojmap; Paper patches it public, which is why the Paper twin references it
        // directly — the construction below is byte-identical).
        var blockStateCodec = PalettedContainer.codecRW(
                Block.BLOCK_STATE_REGISTRY, BlockState.CODEC,
                PalettedContainer.Strategy.SECTION_STATES, Blocks.AIR.defaultBlockState());
        var biomeCodec = PalettedContainer.codecRO(
                biomeRegistry.asHolderIdMap(), biomeRegistry.holderByNameCodec(),
                PalettedContainer.Strategy.SECTION_BIOMES,
                biomeRegistry.getHolderOrThrow(Biomes.PLAINS));

        var sectionsList = chunkNbt.getList("sections", Tag.TAG_COMPOUND);
        if (sectionsList.isEmpty()) return null;

        // First pass: parse sections and check if any are non-empty
        record ParsedSection(int sectionY, LevelChunkSection section, byte[] blockLight, byte[] skyLight) {}
        var parsed = new java.util.ArrayList<ParsedSection>(sectionsList.size());

        for (var sectionElement : sectionsList) {
            var sectionTag = (CompoundTag) sectionElement;
            int sectionY = sectionTag.contains("Y") ? sectionTag.getInt("Y") : Integer.MIN_VALUE;
            if (sectionY == Integer.MIN_VALUE) continue;

            byte[] blockLightData = sectionTag.getByteArray("BlockLight");
            var result = parseSection(sectionTag, sectionY, blockStateCodec, biomeCodec,
                    biomeRegistry, blockLightData);
            if (result != null) {
                byte[] skyLightData = sectionTag.getByteArray("SkyLight");
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

                // All-zero layers are skipped to match SectionSerializer exactly: the live path
                // omits them ("absent" means all-zero on the wire), and vanilla saves the light
                // engine's allocated-but-zeroed arrays (e.g. after a light source is removed), so
                // shipping them would make disk serves byte-diverge from live serves of the same
                // content — breaking the up-to-date economy and DirtyContentFilter seeding.
                boolean hasBlockLight = p.blockLight.length == 2048 && hasNonZeroNibble(p.blockLight);
                buf.writeBoolean(hasBlockLight);
                if (hasBlockLight) {
                    buf.writeBytes(p.blockLight);
                }

                boolean hasSkyLight = p.skyLight.length == 2048 && hasNonZeroNibble(p.skyLight);
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
            Registry<Biome> biomeRegistry,
            byte[] blockLightData) {

        if (!sectionTag.contains("block_states", Tag.TAG_COMPOUND)) return null;

        var blockStatesResult = blockStateCodec.parse(NbtOps.INSTANCE, sectionTag.getCompound("block_states"));
        var blockStates = blockStatesResult.result().orElse(null);
        if (blockStates == null) return null;

        PalettedContainerRO<Holder<Biome>> biomes;
        if (sectionTag.contains("biomes", Tag.TAG_COMPOUND)) {
            var biomesResult = biomeCodec.parse(NbtOps.INSTANCE, sectionTag.getCompound("biomes"));
            biomes = biomesResult.result().orElse(null);
        } else {
            biomes = null;
        }

        LevelChunkSection section;
        if (biomes instanceof PalettedContainer<Holder<Biome>> biomeContainer) {
            section = new LevelChunkSection(blockStates, biomeContainer);
        } else {
            section = new LevelChunkSection(blockStates, new PalettedContainer<>(
                    biomeRegistry.asHolderIdMap(), biomeRegistry.getHolderOrThrow(Biomes.PLAINS),
                    PalettedContainer.Strategy.SECTION_BIOMES));
        }

        if (section.hasOnlyAir()) {
            if (blockLightData.length != 2048 || !hasNonZeroNibble(blockLightData)) {
                return null;
            }
        }

        return section;
    }

    private static boolean hasNonZeroNibble(byte[] light) {
        for (byte b : light) {
            if (b != 0) return true;
        }
        return false;
    }
}
