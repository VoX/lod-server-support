package dev.vox.lss.common.diagnostics;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class DiagnosticPrivacyTest {
    @Test void dhExportsAcceptOnlyNumericBooleanOrEnumeratedObservationsAndKeepNativeFieldsUnset() {
        for(var field:DhStatusSnapshot.class.getRecordComponents())
            assertTrue(field.getType().isPrimitive() || field.getType().isEnum(),
                    "DH export must not acquire an opaque string/identity field: "+field.getName());
        var dh=new DhStatusSnapshot(dev.vox.lss.common.dh.DhProfile.OFFICIAL_16,DhStatusSnapshot.State.BLOCKED,DhStatusSnapshot.Reason.VANILLA_LIMIT,DhStatusSnapshot.Activity.STOPPED,10,5,2,1,45,3,2,1000,100,1,2,3,32,1,2,500,true,true,true,true,0,0,100,4096,7,0);
        var snapshot=new ClientStatusSnapshot(3,1,100,true,false,true,false,true,false,0,0,0,0,0,0,-1,0,0,0,0,
                ClientStatusSnapshot.Discovery.DORMANT,ClientStatusSnapshot.Availability.DISABLED,null,
                new DiagnosticVersions(Map.of(DiagnosticVersions.Component.LSS,"/home/alice/token")),null,dh);
        var json=com.google.gson.JsonParser.parseString(DiagnosticExport.clientJson(snapshot)).getAsJsonObject();
        assertEquals("BLOCKED",json.getAsJsonObject("dh").get("state").getAsString());
        assertEquals("VANILLA_LIMIT",json.getAsJsonObject("dh").get("reason").getAsString());
        assertEquals("STOPPED",json.getAsJsonObject("dh").get("activity").getAsString());
        assertEquals(0,json.get("protocol").getAsInt());assertFalse(json.get("negotiated").getAsBoolean());
        assertFalse(json.get("serverEnabled").getAsBoolean());assertFalse(json.toString().contains("alice"));
        assertTrue(snapshot.lines().stream().anyMatch(line->line.contains("sync on load=true")));
    }
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
                7L,
                7L,
                7,
                7,
                "alice@example.test /home/alice/token abcdef12-3456-7890-abcd-abcdef123456",
                "alice@example.test /home/alice/token abcdef12-3456-7890-abcd-abcdef123456",
                "alice@example.test /home/alice/token abcdef12-3456-7890-abcd-abcdef123456",
                "alice@example.test /home/alice/token abcdef12-3456-7890-abcd-abcdef123456",
                "alice@example.test /home/alice/token abcdef12-3456-7890-abcd-abcdef123456");
        var versions = new DiagnosticVersions(Map.of(DiagnosticVersions.Component.LSS, "1.2.3+mc1.21.1",
                DiagnosticVersions.Component.XAERO, "/home/alice/token",
                DiagnosticVersions.Component.JOURNEYMAP, "/home/alice/journeymap"));
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
