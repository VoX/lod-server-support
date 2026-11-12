package dev.vox.lss.networking.server;

import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;

/** Test door onto the package-private disk serializer, for end-to-end tests in other
 *  packages (server disk path -> client decode -> consumer). */
public final class DiskSerializerTestAccess {
    private DiskSerializerTestAccess() {}

    /** The production disk-path call without an x-ray mask, gated to the world's section range. */
    public static byte[] serialize(CompoundTag chunkNbt, RegistryAccess registryAccess,
                                   int minSectionY, int maxSectionY, boolean transcode) {
        return NbtSectionSerializer.serializeChunkNbt(chunkNbt, registryAccess, null,
                minSectionY, maxSectionY, transcode);
    }
}
