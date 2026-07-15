package dev.vox.lss.mixin;

import net.minecraft.util.thread.PriorityConsecutiveExecutor;
import net.minecraft.world.level.chunk.storage.IOWorker;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes {@link IOWorker}'s private {@code consecutiveExecutor} and {@code storage} so the
 * background-priority read path (see {@code ChunkDiskReader}) can schedule a BACKGROUND-priority
 * region read on the same single-threaded executor vanilla loads chunks through, reading straight
 * from {@link RegionFileStorage} — the thread-safe target of IOWorker's own reads.
 *
 * <p>Methods are {@code lss$}-prefixed because mixin adds them to the target class: unprefixed
 * names could collide with vanilla methods.
 */
@Mixin(IOWorker.class)
public interface AccessorIOWorker {
    @Accessor("consecutiveExecutor")
    PriorityConsecutiveExecutor lss$getConsecutiveExecutor();

    @Accessor("storage")
    RegionFileStorage lss$getStorage();
}
