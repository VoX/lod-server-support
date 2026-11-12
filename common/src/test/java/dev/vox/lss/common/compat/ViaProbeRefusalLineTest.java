package dev.vox.lss.common.compat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The refused-legacy-announce log line (seen live 2026-11-12: a 26.1 client on a 26.3
 * server through ViaVersion announced protocol 20, was registered, then retried as
 * protocol 19 after a slow reply). The old wording said "LOD unavailable ... the client
 * must update", which was wrong twice: the protocol-20 session was still registered, and
 * the client was current.
 */
class ViaProbeRefusalLineTest {

    @Test
    void namesTheAnnounceBothMinecraftProtocolsAndThatTheSessionIsKept() {
        String line = ViaProbe.legacyAnnounceRefusedLine("LSS", "Steve", 19, 775, 777);
        assertTrue(line.contains("protocol v19 announce from Steve"), line);
        assertTrue(line.contains("client Minecraft protocol 775 vs server 777"), line);
        assertTrue(line.contains("Any session already registered for Steve is kept"), line);
        assertTrue(line.contains("reply to the first announce was slow"), line);
    }

    @Test
    void neverClaimsLodIsUnavailableOrThatTheClientMustUpdate() {
        String line = ViaProbe.legacyAnnounceRefusedLine("VSS", "Alex", 16, 775, 777);
        assertFalse(line.contains("LOD unavailable"), line);
        assertFalse(line.contains("must update"), line);
        // Reached by a genuinely old client too, which has no session at all.
        assertFalse(line.contains("already has"), line);
        assertTrue(line.endsWith("needs a newer VSS."), line);
    }
}
