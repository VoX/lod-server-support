package dev.vox.lss.paper;

import com.mojang.serialization.Codec;
import dev.vox.lss.common.LSSConstants;
import io.netty.buffer.Unpooled;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Reads chunk NBT from disk and serializes sections into MC-native wire format.
 * Used by {@link PaperChunkDiskReader} for async disk reads.
 */
final class PaperNbtSectionSerializer {
    private PaperNbtSectionSerializer() {}

    private static final byte[] EMPTY = new byte[0];

    /** Test seam: the region-file NBT read — the only NMS call in the Paper disk-read path.
     *  Production wires {@link ChunkMap#read}; tests inject empty / failing / timing-out
     *  futures to pin the submit-envelope triage. */
    @FunctionalInterface
    interface ChunkNbtRead {
        CompletableFuture<Optional<CompoundTag>> read(int cx, int cz);
    }

    /**
     * Read chunk NBT from disk, verify FULL status, and serialize sections
     * into MC-native wire format. {@code maskEntry} (nullable) is the dimension's x-ray
     * mask, captured by the caller at submit time.
     * Returns the serialized byte array, or null if the chunk is missing/not FULL/empty.
     */
    static byte[] readAndSerializeSections(ChunkNbtRead read, RegistryAccess registryAccess,
                                            int cx, int cz,
                                            PaperXrayMaskManager.MaskEntry maskEntry) throws Exception {
        var future = read.read(cx, cz);
        var optionalTag = future.get(LSSConstants.DISK_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (optionalTag.isEmpty()) return null;
        return serializeChunkNbt(optionalTag.get(), registryAccess, maskEntry);
    }

    /** Unmasked flavor — the shape the pre-masking tests and corpus pin. */
    static byte[] serializeChunkNbt(CompoundTag chunkNbt, RegistryAccess registryAccess) {
        return serializeChunkNbt(chunkNbt, registryAccess, null);
    }

    /**
     * Serialize a chunk's NBT (as read from a region file) into MC-native wire format.
     * Returns {@code null} if the chunk is not FULL or has no sections, an empty array if every
     * section is empty, or the serialized section bytes. Package-visible for testing.
     */
    // LevelChunkSection.write(buf) is @Deprecated on Paper (an anti-xray overload was added),
    // but the 1-arg form is the canonical vanilla serialization and is byte-identical to the
    // Fabric path. The wire format must match Fabric exactly, so keep this call (do not migrate).
    @SuppressWarnings("deprecation")
    static byte[] serializeChunkNbt(CompoundTag chunkNbt, RegistryAccess registryAccess,
                                    PaperXrayMaskManager.MaskEntry maskEntry) {
        var statusStr = chunkNbt.getStringOr("Status", null);
        if (statusStr == null || ChunkStatus.byName(statusStr) != ChunkStatus.FULL) return null;

        var factory = PalettedContainerFactory.create(registryAccess);
        var blockStateCodec = factory.blockStatesContainerCodec();
        var biomeCodec = factory.biomeContainerCodec();

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
                    factory, blockLightData);
            if (result != null) {
                byte[] skyLightData = sectionTag.getByteArray("SkyLight").orElse(EMPTY);
                parsed.add(new ParsedSection(sectionY, result, blockLightData, skyLightData));
            }
        }

        if (parsed.isEmpty()) return new byte[0];

        if (maskEntry != null) {
            // Parsed sections are throwaway — mask in place, inside the same choke point
            // the live path masks in, so disk and live serves stay byte-identical. The
            // counter attributes to whatever manager is current at COMPLETION time (a read
            // straddling a service restart credits the successor) — diag-only cosmetics;
            // the mask itself always comes from the immutable submit-time entry.
            for (var p : parsed) {
                if (PaperXrayMaskFilter.maskInPlace(p.section, p.sectionY,
                        maskEntry.mask(), maskEntry.kind())) {
                    var manager = PaperXrayMaskManager.current();
                    if (manager != null) manager.countMaskedSection();
                }
            }
        }

        // Second pass: serialize to wire format
        var buf = new FriendlyByteBuf(Unpooled.buffer(parsed.size() * 1024));
        try {
            buf.writeVarInt(parsed.size());
            for (var p : parsed) {
                buf.writeByte(p.sectionY);
                p.section.write(buf);

                // All-zero layers are skipped to match the live serializer exactly (mirrors
                // Fabric's NbtSectionSerializer): "absent" means all-zero on the wire, and
                // vanilla saves the light engine's allocated-but-zeroed arrays, which would
                // otherwise make disk serves byte-diverge from live serves of identical content.
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
            PalettedContainerFactory factory,
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
            section = new LevelChunkSection(blockStates, factory.createForBiomes());
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
