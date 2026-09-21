package dev.vox.lss.common.config;

import org.junit.jupiter.api.Test;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class RuntimeBatchTest {
    @Test void validatorDerivedProtectedChangeRejectsWholePreview() {
        var config = new Config();
        config.farPlayersMinDistanceBlocks = 500;
        config.validate();
        assertThrows(IllegalArgumentException.class, () -> RuntimeSettings.previewBatch(config,
                java.util.Map.of("farPlayersMaxDistanceBlocks", "128"), Set.of("farPlayersMaxDistanceBlocks")));
        assertEquals(2048, config.farPlayersMaxDistanceBlocks);
        assertEquals(500, config.farPlayersMinDistanceBlocks);
    }

    @org.junit.jupiter.api.Test void globalBatchRejectsWorldDistanceSyntaxWithoutMutation() {
        var config = new Config();
        config.validate();
        var before = java.util.Map.copyOf(config.lodDistanceChunksByWorld);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () ->
                RuntimeSettings.previewBatch(config, java.util.Map.of("lodDistanceChunks", "world 200"),
                        java.util.Set.of("lodDistanceChunks")));
        org.junit.jupiter.api.Assertions.assertEquals(before, config.lodDistanceChunksByWorld);
    }

    public static class Config extends ServerConfigBase {
        transient CountDownLatch entered, release;
        transient int saves;
        @Override public void validate() {
            if (entered != null) {
                entered.countDown();
                try { if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("reader did not release publisher"); }
                catch (InterruptedException e) { throw new AssertionError(e); }
            }
            super.validate();
        }
        @Override public boolean trySave() { saves++; return true; }
    }
    @Test void generationReadersSeeOnePublicationForRaiseAndLowerInEitherInputOrder() throws Exception {
        for (int target : new int[]{5, 80}) for (boolean reverse : new boolean[]{false, true}) {
            var config = new Config(); config.validate();
            var request = new LinkedHashMap<String, String>();
            String first = reverse ? "generationConcurrencyLimitPerPlayer" : "generationConcurrencyLimitGlobal";
            String second = reverse ? "generationConcurrencyLimitGlobal" : "generationConcurrencyLimitPerPlayer";
            request.put(first, "" + target); request.put(second, "" + target);
            var preview = RuntimeSettings.previewBatch(config, request, Set.copyOf(request.keySet()));
            config.entered = new CountDownLatch(1); config.release = new CountDownLatch(1);
            var published = java.util.concurrent.CompletableFuture.supplyAsync(() -> RuntimeSettings.applyBatch(config, preview));
            try {
                assertTrue(config.entered.await(5, TimeUnit.SECONDS));
                assertEquals(new ServerConfigBase.GenerationLimits(40, 40), config.generationLimits(),
                        "reader must retain the prior pair while stored fields are being replaced");
            } finally { config.release.countDown(); }
            assertTrue(published.get(5, TimeUnit.SECONDS));
            assertEquals(new ServerConfigBase.GenerationLimits(target, target), config.generationLimits());
            assertEquals(1, config.saves);
        }
    }
    @Test void parseFailureAndStalePreviewCannotSaveOrPublish() {
        var config = new Config(); config.validate();
        assertThrows(IllegalArgumentException.class, () -> RuntimeSettings.previewBatch(config,
                java.util.Map.of("maxConcurrentDiskReads", "oops"), Set.of("maxConcurrentDiskReads")));
        assertEquals(0, config.saves);
        var preview = RuntimeSettings.previewBatch(config, java.util.Map.of("maxConcurrentDiskReads", "4"), Set.of("maxConcurrentDiskReads"));
        config.maxConcurrentDiskReads = 3;
        assertThrows(IllegalStateException.class, () -> RuntimeSettings.applyBatch(config, preview));
        assertEquals(0, config.saves);
        assertEquals(3, config.maxConcurrentDiskReads);
    }
}
