package dev.vox.lss.common.diagnostics;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class DiagnosticPrivacyTest {
    @Test void displayOnlyStringsAndInvalidVersionMetadataNeverEnterExports() {
        var details = new ClientDiagnosticSnapshot(
                7L,
                7,
                7,
                7,
                7,
                7L,
                "alice@example.test /home/alice/token abcdef12-3456-7890-abcd-abcdef123456",
                7L,
                7,
                7,
                7,
                7,
                7,
                7L,
                7L,
                7.5,
                7,
                7L,
                7,
                7,
                7.5,
                7,
                7L,
                7L,
                7L,
                7L,
                7L,
                7L,
                7L,
                7L,
                7L,
                7L,
                7L,
                7L,
                7L,
                7L,
                7,
                7L,
                7L,
                7L,
                7L,
                7,
                7,
                "alice@example.test /home/alice/token abcdef12-3456-7890-abcd-abcdef123456",
                "alice@example.test /home/alice/token abcdef12-3456-7890-abcd-abcdef123456",
                "alice@example.test /home/alice/token abcdef12-3456-7890-abcd-abcdef123456",
                "alice@example.test /home/alice/token abcdef12-3456-7890-abcd-abcdef123456");
        var versions = new DiagnosticVersions(Map.of(DiagnosticVersions.Component.LSS, "1.2.3+mc1.21.1",
                DiagnosticVersions.Component.XAERO, "/home/alice/token"));
        var snapshot = new ClientStatusSnapshot(1, 1, 123, true, true, true, true, true, true,
                20, 512, 128, 50, 1000, 0, -1, 0, 0, 0, 0,
                ClientStatusSnapshot.Discovery.NEGOTIATED, ClientStatusSnapshot.Availability.AVAILABLE, details, versions);
        String json = DiagnosticExport.clientJson(snapshot);
        assertTrue(json.contains("1.2.3+mc1.21.1"));
        assertTrue(json.contains("getAuditHeals"));
        assertFalse(json.contains("alice"));
        assertFalse(json.contains("abcdef12"));
        assertFalse(json.contains("describeCacheKey"));
        assertFalse(json.contains("getGovernedRateLabel"));
        assertTrue(json.contains("redacted"));
    }
    @Test void everyDiscoveryAndOptionalOutcomeRemainsDistinct() {
        for (var discovery : ClientStatusSnapshot.Discovery.values()) {
            for (var integration : ClientStatusSnapshot.Availability.values()) {
                var snapshot = new ClientStatusSnapshot(1, 1, 123, true,
                        discovery == ClientStatusSnapshot.Discovery.NEGOTIATED,
                        true, false, true, false, 20, 512, 0, 0, 0, 0, -1, 0, 0, 0, 0,
                        discovery, integration, null, DiagnosticVersions.unknown());
                assertEquals(discovery, snapshot.discovery());
                assertEquals(integration, snapshot.xaeroAvailability());
                if (discovery == ClientStatusSnapshot.Discovery.PROTOCOL_REJECTED) {
                    assertTrue(snapshot.reasons().contains(ClientStatusSnapshot.Reason.PROTOCOL_REJECTED));
                    assertFalse(snapshot.reasons().contains(ClientStatusSnapshot.Reason.SERVER_DISABLED));
                }
            }
        }
    }
}
