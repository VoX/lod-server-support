package dev.vox.lss.networking.server;

import net.minecraft.server.level.ChunkMap;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the background-read fail-safe. The live branch — a chunk-IO-overhaul mod (C2ME's chunkio
 * rewrite, and structurally similar mods) replacing vanilla's IOWorker executor, leaving
 * {@code consecutiveExecutor}/{@code storage} null — cannot be reached from a gametest or soak run,
 * because no test environment loads such a mod (dev/CI run plain vanilla chunk IO, so the executor
 * is always non-null). Without this the whole feature NPE-storms on every read on a C2ME server;
 * only a live server surfaced it. These unit tests pin the exact decision (the {@code ||} is not
 * "simplified" to {@code &&}, neither handle's null check is dropped) and the A→B fallback wiring
 * (a throwing resolver latches incompatible, engages the throttle, warns once).
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

    /**
     * The fail-safe must survive not only a null handle but any Throwable thrown while resolving the
     * accessor (an unanticipated chunk-IO mod could make the reach itself fail). Detection must not
     * propagate the throwable; it must latch the server-wide incompatible flag, engage the adaptive
     * read throttle (Approach B), and warn exactly once across repeated triggering reads.
     */
    @Test
    void aThrowingHandleResolverLatchesIncompatibleAndEngagesTheThrottleWarningOnce() {
        var warnCount = new AtomicInteger();
        var reader = new ChunkDiskReader(1, true) {
            @Override
            ChunkDiskReader.Handles resolveBackgroundHandles(ChunkMap chunkMap) {
                throw new NoSuchMethodError("simulated chunk-IO-overhaul mod changed vanilla internals");
            }
            @Override
            void warnBackgroundUnavailable() {
                warnCount.incrementAndGet();
            }
        };
        try {
            assertFalse(reader.isBackgroundIncompatibleForTest(), "not latched until a read resolves incompatible");
            assertEquals(-1, reader.adaptiveThrottleLimitOrDisabled(), "throttle off until the fallback engages");

            // Two triggering reads (the resolver throws each time). Detection must catch the
            // throwable, not propagate it; the latch + throttle-enable + warn-once must all hold.
            reader.backgroundReaderOrFallback(null);
            reader.backgroundReaderOrFallback(null);

            assertTrue(reader.isBackgroundIncompatibleForTest(),
                    "a throwing resolver latches incompatible (fail-safe against any Throwable)");
            assertTrue(reader.adaptiveThrottleLimitOrDisabled() >= 0,
                    "the adaptive throttle is engaged on fallback so LOD reads still yield to gameplay");
            assertEquals(1, warnCount.get(),
                    "the fallback warning fires exactly once across repeated triggering reads");
        } finally {
            reader.shutdown();
        }
    }
}
