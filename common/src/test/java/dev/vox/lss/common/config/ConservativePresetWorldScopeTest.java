package dev.vox.lss.common.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/** Global presets preserve independent per-world distance overrides on every support line. */
class ConservativePresetWorldScopeTest {
    @TempDir Path directory;
    private ConservativePresetTest.Config configured() {
        var config = ConservativePresetTest.Config.load(directory);
        config.lodDistanceChunksByWorld = new LinkedHashMap<>(Map.of("creative", 128, "minecraft:overworld", 96));
        config.validate(); assertTrue(config.trySave()); return config;
    }
    @Test void worldEditAfterPreviewSurvivesGlobalApplyUndoAndKeepsNameDimensionPrecedence() {
        var config = configured(); int originalGlobal = config.lodDistanceChunks;
        config.presetCommand("conservative");
        RuntimeSettings.applyWithPersistenceOutcome(config, RuntimeSettings.byName("lodDistanceChunks"), "creative 144");
        var overrides = Map.copyOf(config.lodDistanceChunksByWorld);
        config.presetCommand("apply");
        assertEquals(Integer.parseInt("32"), config.lodDistanceChunks);
        assertEquals(overrides, config.lodDistanceChunksByWorld);
        assertEquals(144, config.lodDistanceForWorld("creative", "minecraft:overworld"));
        assertEquals(96, config.lodDistanceForWorld("unknown", "minecraft:overworld"));
        config.presetCommand("undo");
        assertEquals(originalGlobal, config.lodDistanceChunks); assertEquals(overrides, config.lodDistanceChunksByWorld);
        assertEquals(144, config.lodDistanceForWorld("creative", "minecraft:overworld"));
        assertEquals(originalGlobal, config.lodDistanceForWorld("unknown", "minecraft:the_end"));
    }
    @Test void laterWorldRemovalIsNotResurrectedByGlobalUndo() {
        var config = configured();
        config.presetCommand("conservative"); config.presetCommand("apply");
        RuntimeSettings.applyWithPersistenceOutcome(config, RuntimeSettings.byName("lodDistanceChunks"), "creative default");
        assertFalse(config.lodDistanceChunksByWorld.containsKey("creative"));
        config.presetCommand("undo");
        assertFalse(config.lodDistanceChunksByWorld.containsKey("creative"));
        assertEquals(96, config.lodDistanceForWorld("creative", "minecraft:overworld"));
    }
}
