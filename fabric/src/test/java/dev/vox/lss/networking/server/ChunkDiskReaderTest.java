package dev.vox.lss.networking.server;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the background-read fail-safe CONDITION. The live branch — a chunk-IO-overhaul mod
 * (C2ME's chunkio rewrite, and structurally similar mods) replacing vanilla's IOWorker executor,
 * leaving {@code consecutiveExecutor}/{@code storage} null — cannot be reached from a gametest or
 * soak run, because no test environment loads such a mod (dev/CI run plain vanilla chunk IO, so
 * the executor is always non-null). Without this the whole feature NPE-storms on every read on a
 * C2ME server; only a live server surfaced it. This unit test pins the exact decision so the
 * {@code ||} is not "simplified" to {@code &&} and neither handle's null check is dropped.
 */
class ChunkDiskReaderTest {

    @Test
    void backgroundReadIsUnavailableWhenEitherHandleIsNull() {
        var executor = new Object();
        var storage = new Object();
        assertFalse(ChunkDiskReader.backgroundReadUnavailable(executor, storage),
                "both handles present — the IOWorker executor path is usable");
        assertTrue(ChunkDiskReader.backgroundReadUnavailable(null, storage),
                "null executor (C2ME replaced the IO system) — must fall back, not NPE");
        assertTrue(ChunkDiskReader.backgroundReadUnavailable(executor, null),
                "null storage — must fall back, not NPE");
        assertTrue(ChunkDiskReader.backgroundReadUnavailable(null, null),
                "both null — must fall back");
    }
}
