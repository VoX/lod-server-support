package dev.vox.lss.common.config;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

/** Behavioral acceptance; measured tokens are filled only after the accepted P6b decision. */
class ConservativePresetTest {
    @TempDir Path directory;
    private static int radius() { return Integer.parseInt("32"); }
    private static int global() { return Integer.parseInt("4"); }
    private static int perPlayer() { return Integer.parseInt("1"); }
    public static class Config extends ServerConfigBase {
        transient int saves;
        static Config load(Path directory) { return load(Config.class, "server.json", directory); }
        @Override public boolean trySave() { saves++; return super.trySave(); }
    }
    private Config baseline() {
        var config = Config.load(directory);
        config.lodDistanceChunks = radius() == 1 ? 2 : radius() - 1;
        config.requireServicePermission = true;
        config.maxConcurrentDiskReads = 3;
        config.validate();
        assertTrue(config.trySave());
        return config;
    }
    private JsonObject stored() throws Exception {
        return JsonParser.parseString(Files.readString(directory.resolve("server.json"))).getAsJsonObject();
    }
    private static void assertMeasured(Config config) {
        assertEquals(radius(), config.lodDistanceChunks);
        assertEquals(new ServerConfigBase.GenerationLimits(global(), perPlayer()), config.generationLimits());
    }
    @Test void previewDoesNotPublishOrSaveAndNumericUndoPreservesRestartOverlayAndUnrelatedEdits() throws Exception {
        var config = baseline();
        config.presetCommand("pregenerated-world"); config.presetCommand("apply");
        assertTrue(config.enableChunkGeneration); assertFalse(config.generationConfiguredForRestart());
        int originalRadius = config.lodDistanceChunks;
        var originalLimits = config.generationLimits();
        String before = Files.readString(directory.resolve("server.json")); int saves = config.saves;
        var preview = config.presetCommand("conservative");
        assertTrue(preview.stream().anyMatch(line -> line.contains("SERVER GLOBAL")));
        assertEquals(originalRadius, config.lodDistanceChunks); assertEquals(originalLimits, config.generationLimits());
        assertEquals(saves, config.saves); assertEquals(before, Files.readString(directory.resolve("server.json")));
        config.presetCommand("apply"); assertMeasured(config);
        assertTrue(config.enableChunkGeneration); assertFalse(config.generationConfiguredForRestart());
        assertFalse(stored().get("enableChunkGeneration").getAsBoolean());
        RuntimeSettings.applyWithPersistenceOutcome(config, RuntimeSettings.byName("maxConcurrentDiskReads"), "7");
        config.presetCommand("undo");
        assertEquals(originalRadius, config.lodDistanceChunks); assertEquals(originalLimits, config.generationLimits());
        assertEquals(7, config.maxConcurrentDiskReads); assertTrue(config.requireServicePermission);
        assertTrue(config.enableChunkGeneration); assertFalse(config.generationConfiguredForRestart());
        assertFalse(stored().get("enableChunkGeneration").getAsBoolean());
        assertEquals(7, stored().get("maxConcurrentDiskReads").getAsInt());
    }
    @Test void realFailedSaveCanBeRetriedByFreshNoOpPreviewWithoutRestoringExpiredUndo() throws Exception {
        var config = baseline(); String before = Files.readString(directory.resolve("server.json"));
        Files.createDirectory(directory.resolve("server.json.tmp"));
        config.presetCommand("conservative");
        assertTrue(config.presetCommand("apply").stream().anyMatch(line -> line.contains("applied, but not saved")));
        assertMeasured(config); assertEquals(before, Files.readString(directory.resolve("server.json")));
        Files.delete(directory.resolve("server.json.tmp"));
        int saves = config.saves;
        assertTrue(config.presetCommand("conservative").stream().anyMatch(line -> line.contains("no effective changes")));
        config.presetCommand("apply");
        assertEquals(saves + 1, config.saves, "empty apply retries persistence, per last-application semantics");
        assertEquals(radius(), stored().get("lodDistanceChunks").getAsInt());
        config.presetCommand("undo"); assertMeasured(config);
        assertThrows(IllegalStateException.class, () -> config.presetCommand("undo"));
    }
    @Test void emptyRuntimeApplicationRemainsRuntimeKindAndReplacesPriorRestartUndo() throws Exception {
        var config = baseline();
        config.lodDistanceChunks = radius(); config.generationConcurrencyLimitGlobal = global();
        config.generationConcurrencyLimitPerPlayer = perPlayer(); config.validate(); config.trySave();
        config.presetCommand("pregenerated-world"); config.presetCommand("apply");
        config.presetCommand("conservative"); config.presetCommand("apply"); config.presetCommand("undo");
        assertMeasured(config); assertTrue(config.enableChunkGeneration);
        assertFalse(config.generationConfiguredForRestart(), "empty runtime undo cannot resurrect prior restart undo");
        assertFalse(stored().get("enableChunkGeneration").getAsBoolean());
    }
    @Test void stalePreviewCannotPublishAnyOtherKeyOrSave() throws Exception {
        var config = baseline(); config.presetCommand("conservative");
        int later = config.lodDistanceChunks == 2048 ? 1 : config.lodDistanceChunks + 1;
        RuntimeSettings.applyWithPersistenceOutcome(config, RuntimeSettings.byName("lodDistanceChunks"), Integer.toString(later));
        int saves = config.saves; var limits = config.generationLimits();
        String disk = Files.readString(directory.resolve("server.json"));
        assertThrows(IllegalStateException.class, () -> config.presetCommand("apply"));
        assertEquals(later, config.lodDistanceChunks); assertEquals(limits, config.generationLimits());
        assertEquals(saves, config.saves); assertEquals(disk, Files.readString(directory.resolve("server.json")));
    }
    @Test void unrelatedEditAfterPreviewSurvivesApplyAndUndo() {
        var config = baseline(); int originalRadius = config.lodDistanceChunks;
        config.presetCommand("conservative");
        RuntimeSettings.applyWithPersistenceOutcome(config, RuntimeSettings.byName("maxConcurrentDiskReads"), "7");
        config.presetCommand("apply"); assertMeasured(config); assertEquals(7, config.maxConcurrentDiskReads);
        config.presetCommand("undo"); assertEquals(originalRadius, config.lodDistanceChunks); assertEquals(7, config.maxConcurrentDiskReads);
    }
    @Test void conflictingUndoRefusesWholePatchAndReloadHasNoPreviewOrUndo() throws Exception {
        var config = baseline(); config.presetCommand("conservative"); config.presetCommand("apply");
        int later = radius() == 2048 ? 1 : radius() + 1;
        RuntimeSettings.applyWithPersistenceOutcome(config, RuntimeSettings.byName("lodDistanceChunks"), Integer.toString(later));
        int saves = config.saves; var limits = config.generationLimits();
        assertThrows(IllegalStateException.class, () -> config.presetCommand("undo"));
        assertEquals(later, config.lodDistanceChunks); assertEquals(limits, config.generationLimits()); assertEquals(saves, config.saves);
        var reloaded = Config.load(directory);
        assertThrows(IllegalStateException.class, () -> reloaded.presetCommand("apply"));
        assertThrows(IllegalStateException.class, () -> reloaded.presetCommand("undo"));
        assertEquals(later, reloaded.lodDistanceChunks);
    }
}
