package dev.vox.lss.paper;

import dev.vox.lss.common.processing.ChunkReadResult;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerLevel;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * The read-priority flag must not disturb the Paper read envelope. {@code useBackgroundReadPriority}
 * swaps only the read source ({@code chunkMap.read} → Moonrise {@code loadDataAsync} at
 * {@code Priority.LOW}); everything downstream — triage, result queues, timestamps — is unchanged,
 * and an injected read override must still win over the Moonrise path (otherwise a flag flip would
 * silently blind {@link PaperDiskReaderEnvelopeTest}'s five envelope cases).
 *
 * <p>The live Moonrise LOW-priority call itself needs a real server and is validated by the soak
 * harness on real Paper/Folia, per the Paper validation strategy in CLAUDE.md.
 */
class PaperChunkDiskReaderPriorityTest {

    @BeforeAll
    static void setup() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void flagOnReaderKeepsTheReadOverrideAndStillDeliversThroughTheEnvelope() throws Exception {
        var reader = new PaperChunkDiskReader(1, /* useBackgroundReadPriority = */ true);
        var uuid = UUID.randomUUID();
        reader.registerPlayer(uuid);
        try {
            // An empty tag short-circuits to notFound before registryAccess is ever read, so a bare
            // mock level suffices — and reaching the assertion at all proves the override, not the
            // Moonrise path, served this read (Moonrise would explode on a mock level).
            reader.setReadOverride((cx, cz) -> CompletableFuture.completedFuture(Optional.<CompoundTag>empty()));
            reader.submitReadDirect(uuid, "minecraft:overworld", mock(ServerLevel.class), 10, 10, 0L);

            ChunkReadResult result = awaitResult(reader, uuid);
            assertTrue(result.notFound(), "empty override tag resolves as not-found with the flag on");
            assertFalse(result.saturated(), "a single read on a fresh reader must not saturate");
        } finally {
            reader.shutdown();
        }
    }

    private static ChunkReadResult awaitResult(PaperChunkDiskReader reader, UUID uuid) throws InterruptedException {
        var queue = reader.getPlayerQueue(uuid);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            var r = queue.poll();
            if (r != null) return r;
            Thread.sleep(1);
        }
        throw new AssertionError("no disk-read result within 5s");
    }
}
