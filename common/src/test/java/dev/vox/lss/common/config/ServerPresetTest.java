package dev.vox.lss.common.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class ServerPresetTest {
    @TempDir Path directory;
    public static class Config extends ServerConfigBase {
        static Config load(Path directory) { return load(Config.class, "server.json", directory); }
    }
    @Test void restartOnlyPresetPreservesLiveGenerationAndUnrelatedSavesAndUndo() throws Exception {
        var config = Config.load(directory);
        assertTrue(config.enableChunkGeneration);
        var preview = config.presetCommand("pregenerated-world");
        assertTrue(preview.getFirst().contains("SERVER GLOBAL"));
        assertTrue(config.enableChunkGeneration);
        assertTrue(config.presetCommand("apply").getFirst().contains("saved"));
        assertTrue(config.enableChunkGeneration, "running services must not see a restart-only choice");
        assertFalse(config.generationConfiguredForRestart());
        var binding = RuntimeSettings.serializedBindings().stream()
                .filter(b -> b.descriptor().key().equals("enableChunkGeneration")).findFirst().orElseThrow();
        assertEquals(false, binding.storedValue().apply(config), "stored binding must show staged choice");
        var snapshot = new dev.vox.lss.common.diagnostics.ServerStatusSnapshot(1, 0, true, true,
                config.enableChunkGeneration, config.generationConfiguredForRestart(), config.lodDistanceChunks,
                0, 0, 0, 0, 0, dev.vox.lss.common.diagnostics.DiagnosticVersions.unknown());
        assertTrue(snapshot.generationEnabled());
        assertFalse(snapshot.generationConfiguredForRestart());
        assertTrue(snapshot.lines().stream().anyMatch(line -> line.contains("restart pending")));
        config.lodDistanceChunks = 200;
        config.save();
        var stored = JsonParser.parseString(Files.readString(directory.resolve("server.json"))).getAsJsonObject();
        assertFalse(stored.get("enableChunkGeneration").getAsBoolean());
        assertEquals(200, stored.get("lodDistanceChunks").getAsInt());
        config.presetCommand("undo");
        assertTrue(Config.load(directory).enableChunkGeneration);
        assertEquals(200, config.lodDistanceChunks);
    }
    @Test void restartAndNoPreviewRejectApplyUndoAndUnsavedIsHonest() throws Exception {
        var config = Config.load(directory);
        assertThrows(IllegalStateException.class, () -> config.presetCommand("apply"));
        assertThrows(IllegalStateException.class, () -> config.presetCommand("undo"));
        config.presetCommand("pregenerated-world");
        Files.delete(directory.resolve("server.json"));
        Files.delete(directory);
        Files.writeString(directory, "occupied");
        assertTrue(config.presetCommand("apply").getFirst().contains("not saved"));
        assertTrue(config.enableChunkGeneration);
    }
}
