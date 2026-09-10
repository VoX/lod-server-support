package dev.vox.lss.common.diagnostics;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class DiagnosticExportTest {
    @TempDir Path directory;
    @Test void exportHasTypedAllowlistAndBoundedRetention() throws Exception {
        var snapshot = new ClientStatusSnapshot(1, 5, 1000, true, false, false,
                false, false, false, 0, 0, 0, 0, 0, 0, -1, 0, 0, 0, -1);
        Path output = DiagnosticExport.write(directory, snapshot).get();
        String json = Files.readString(output);
        assertTrue(json.contains("schemaVersion"));
        assertFalse(json.contains(directory.toString()));
        assertFalse(json.contains("serverAddress"));
        for (int i = 0; i < 12; i++) DiagnosticExport.write(directory, snapshot).get();
        try (var files = Files.list(directory)) { assertEquals(20, files.count()); }
    }
    @Test void rejectsSymlinksAndOversizeAndDeniesFileAsDirectory() throws Exception {
        Path file = Files.writeString(directory.resolve("file"), "existing");
        assertThrows(java.io.IOException.class, () -> DiagnosticExport.writeCaptured(file, new byte[1], "safe"));
        assertThrows(java.io.IOException.class, () -> DiagnosticExport.writeCaptured(directory, new byte[65537], "safe"));
        assertThrows(java.io.IOException.class, () -> DiagnosticExport.writeCaptured(directory, new byte[1], "界".repeat(22000)));
        Path link = Files.createSymbolicLink(directory.resolve("link"), directory);
        assertThrows(java.io.IOException.class, () -> DiagnosticExport.writeCaptured(link, new byte[1], "safe"));
        assertEquals("existing", Files.readString(file));
    }
    @Test void independentReasonsCoexistWithoutGuessingRemoteCauses() {
        var snapshot = new ClientStatusSnapshot(1, 1, 0, true, true, false, false,
                false, false, 20, 512, 0, 0, 0, 3, 4, 1, 20, 3, 5);
        assertTrue(snapshot.reasons().containsAll(java.util.List.of(
                ClientStatusSnapshot.Reason.RECEPTION_OFF, ClientStatusSnapshot.Reason.NO_CONSUMER,
                ClientStatusSnapshot.Reason.SERVER_DISABLED, ClientStatusSnapshot.Reason.XAERO_REBUILDS)));
        assertFalse(snapshot.reasons().contains(ClientStatusSnapshot.Reason.AWAITING_NEGOTIATION));
        assertTrue(snapshot.lines().stream().anyMatch(s -> s.contains("Remote limiting cause: unknown")));
    }
}
