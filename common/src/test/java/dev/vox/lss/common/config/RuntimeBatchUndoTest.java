package dev.vox.lss.common.config;

import org.junit.jupiter.api.Test;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/** Undo races use the existing publication seam; these numeric values are synthetic, not preset choices. */
class RuntimeBatchUndoTest {
    @Test void undoPublishesOneGenerationPairForRaiseAndLowerInEitherRequestOrder() throws Exception {
        for (int appliedLimit : new int[]{5, 80}) for (boolean reverse : new boolean[]{false, true}) {
            var config = new RuntimeBatchTest.Config(); config.validate();
            var original = config.generationLimits();
            var request = new LinkedHashMap<String, String>();
            String first = reverse ? "generationConcurrencyLimitPerPlayer" : "generationConcurrencyLimitGlobal";
            String second = reverse ? "generationConcurrencyLimitGlobal" : "generationConcurrencyLimitPerPlayer";
            request.put(first, Integer.toString(appliedLimit)); request.put(second, Integer.toString(appliedLimit));
            var application = RuntimeSettings.previewBatch(config, request, Set.copyOf(request.keySet()));
            assertTrue(RuntimeSettings.applyBatch(config, application));
            var applied = config.generationLimits();
            config.entered = new CountDownLatch(1); config.release = new CountDownLatch(1);
            var undo = CompletableFuture.supplyAsync(() -> RuntimeSettings.undoBatch(config, application));
            try {
                assertTrue(config.entered.await(5, TimeUnit.SECONDS));
                assertEquals(applied, config.generationLimits(), "admission readers retain the complete applied pair until undo publishes");
            } finally { config.release.countDown(); }
            assertTrue(undo.get(5, TimeUnit.SECONDS));
            assertEquals(original, config.generationLimits()); assertEquals(2, config.saves);
        }
    }
    @Test void validationDependencyConflictRefusesUndoBeforeAnyPublicationOrSave() {
        var config = new RuntimeBatchTest.Config(); config.validate();
        var patch = RuntimeSettings.previewBatch(config, Map.of("generationConcurrencyLimitGlobal", "80"), Set.of("generationConcurrencyLimitGlobal"));
        assertTrue(RuntimeSettings.applyBatch(config, patch));
        RuntimeSettings.applyWithPersistenceOutcome(config, RuntimeSettings.byName("generationConcurrencyLimitPerPlayer"), "60");
        int saves = config.saves; var before = config.generationLimits();
        assertThrows(IllegalStateException.class, () -> RuntimeSettings.undoBatch(config, patch));
        assertEquals(before, config.generationLimits()); assertEquals(80, config.generationConcurrencyLimitGlobal);
        assertEquals(60, config.generationConcurrencyLimitPerPlayer); assertEquals(saves, config.saves);
    }
    @Test void validLaterEditToUnchangedDependencySurvivesUndo() {
        var config = new RuntimeBatchTest.Config(); config.validate(); int originalGlobal = config.generationLimits().global();
        var patch = RuntimeSettings.previewBatch(config, Map.of("generationConcurrencyLimitGlobal", "80"), Set.of("generationConcurrencyLimitGlobal"));
        RuntimeSettings.applyBatch(config, patch);
        RuntimeSettings.applyWithPersistenceOutcome(config, RuntimeSettings.byName("generationConcurrencyLimitPerPlayer"), "30");
        assertTrue(RuntimeSettings.undoBatch(config, patch));
        assertEquals(new ServerConfigBase.GenerationLimits(originalGlobal, 30), config.generationLimits());
    }
    @Test void previewAndUndoCannotCrossConfigIdentity() {
        var owner = new RuntimeBatchTest.Config(); owner.validate();
        var other = new RuntimeBatchTest.Config(); other.validate(); var original = other.generationLimits();
        var patch = RuntimeSettings.previewBatch(owner, Map.of("generationConcurrencyLimitGlobal", "80"), Set.of("generationConcurrencyLimitGlobal"));
        assertThrows(IllegalStateException.class, () -> RuntimeSettings.applyBatch(other, patch));
        RuntimeSettings.applyBatch(owner, patch);
        assertThrows(IllegalStateException.class, () -> RuntimeSettings.undoBatch(other, patch));
        assertEquals(original, other.generationLimits()); assertEquals(0, other.saves);
    }
}
