package dev.vox.lss.test;

import dev.vox.lss.mixin.AccessorServerChunkCache;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/** Establishes the persisted-superflat premise before a test measures disk routing.
 * Owns only one bounded batch of read futures; it submits no service-reader requests,
 * takes no extra tickets, and creates no threads. Callers retain their existing tickets
 * until this assertion succeeds. This line uses the old CompoundTag getter family. */
final class SavedColumnFixture {
    private final ServerLevel level;
    private final TestPositions.ChunkAt[] positions;
    private List<CompletableFuture<Optional<CompoundTag>>> reads;

    SavedColumnFixture(ServerLevel level, TestPositions.ChunkAt... positions) {
        this.level = level;
        this.positions = positions.clone();
    }

    void assertSaved(GameTestHelper helper) {
        var chunkSource = this.level.getChunkSource();
        if (this.reads == null) {
            helper.assertTrue(helper.getTick() >= 2, "waiting for held chunks to enter the save set");
            for (var pos : this.positions) {
                var holder = TestPositions.saveHolder(chunkSource, pos);
                helper.assertTrue(holder != null && holder.wasAccessibleSinceLastSave()
                                && holder.isReadyForSaving(),
                        "waiting for held chunk's save eligibility at " + pos);
            }
            // Save before release: getChunkNow == null during an asynchronous unload
            // does not establish that its later serialization has completed.
            this.level.save(null, true, false);
            var chunkMap = ((AccessorServerChunkCache) chunkSource).getChunkMap();
            this.reads = new ArrayList<>(this.positions.length);
            for (var pos : this.positions) this.reads.add(chunkMap.read(pos.pos()));
        }
        if (this.reads.stream().anyMatch(read -> !read.isDone())) {
            // The engine ticks unthrottled, while these are real IO futures.
            try {
                Thread.sleep(50L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted waiting for saved fixture columns", e);
            }
        }
        for (int i = 0; i < this.reads.size(); i++) {
            var read = this.reads.get(i);
            var pos = this.positions[i];
            helper.assertTrue(read.isDone(), "waiting for saved-column read at " + pos);
            var nbt = read.join().orElse(null); // Read errors fail; never retry into a passing premise.
            helper.assertTrue(nbt != null && ChunkStatus.byName(nbt.getString("Status")) == ChunkStatus.FULL,
                    "held/save-flushed fixture must read as FULL before request admission at " + pos
                            + "; status=" + (nbt == null ? "missing" : nbt.getString("Status")));
            // Inspect actual saved terrain, not merely the FULL label or a nonempty
            // sections list (vanilla can retain light-only/air-only sections).
            boolean hasSurface = false;
            for (var entry : nbt.getList("sections", Tag.TAG_COMPOUND)) {
                var section = (CompoundTag) entry;
                for (var paletteEntry : section.getCompound("block_states").getList("palette", Tag.TAG_COMPOUND)) {
                    if (((CompoundTag) paletteEntry).getString("Name").equals("minecraft:grass_block")) {
                        hasSurface = true;
                    }
                }
            }
            helper.assertTrue(hasSurface, "saved fixture must contain superflat surface blocks at " + pos);
        }
    }
}
