package dev.vox.lss.mixin;

import net.minecraft.world.level.chunk.storage.IOWorker;
import net.minecraft.world.level.chunk.storage.SimpleRegionStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes {@code SimpleRegionStorage.worker} so LSS can reach the IOWorker that {@link
 * net.minecraft.server.level.ChunkMap} (which extends SimpleRegionStorage) reads through, to
 * schedule BACKGROUND-priority reads on the same executor vanilla uses.
 *
 * <p>The method is {@code lss$}-prefixed because mixin adds it to the target class: an
 * unprefixed {@code getWorker()} could collide with a vanilla method of that name.
 */
@Mixin(SimpleRegionStorage.class)
public interface AccessorSimpleRegionStorage {
    @Accessor("worker")
    IOWorker lss$getWorker();
}
