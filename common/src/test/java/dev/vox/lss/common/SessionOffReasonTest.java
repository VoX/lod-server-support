package dev.vox.lss.common;

import org.junit.jupiter.api.Test;
import static dev.vox.lss.common.LSSConstants.*;
import static org.junit.jupiter.api.Assertions.*;

/** 0.23.0 plan §3: one reason, by precedence, and none when the operator keeps it private. */
class SessionOffReasonTest {
    @Test void everyCombinationOfCausesGivesExactlyOneReasonOrNone() {
        for (int mask = 0; mask < 16; mask++) {
            boolean on = (mask & 1) != 0, denied = (mask & 2) != 0, show = (mask & 4) != 0, worldOff = (mask & 8) != 0;
            int reason = SessionOffReason.of(on, denied, show, worldOff);
            assertTrue(Integer.bitCount(reason) <= 1, "one reason, never two: " + mask);
            assertEquals(0, reason & ~SESSION_FLAG_OFF_REASONS, "only reason bits: " + mask);
            int expected = !on ? SESSION_FLAG_OFF_SERVER
                    : denied ? (show ? SESSION_FLAG_OFF_PERMISSION : 0)
                    : worldOff ? SESSION_FLAG_OFF_WORLD : 0;
            assertEquals(expected, reason, "mask " + mask);
        }
    }

    @Test void aDeniedPlayerIsNeverToldAboutTheWorld() {
        // They get no pushes after the refusal, so "off in this world" could never be corrected.
        assertEquals(SESSION_FLAG_OFF_PERMISSION, SessionOffReason.of(true, true, true, true));
        assertEquals(0, SessionOffReason.of(true, true, false, true), "private: nothing at all");
        assertEquals(SESSION_FLAG_OFF_WORLD, SessionOffReason.of(true, false, true, true));
    }

    @Test void theBitsAreDistinctAndLeaveTheMapBitAlone() {
        assertEquals(0x2, SESSION_FLAG_OFF_SERVER);
        assertEquals(0x4, SESSION_FLAG_OFF_WORLD);
        assertEquals(0x8, SESSION_FLAG_OFF_PERMISSION);
        assertEquals(0, SESSION_FLAG_OFF_REASONS & SESSION_FLAG_DENY_MAP_INGESTION);
        assertEquals(0xE, SESSION_FLAG_OFF_REASONS);
    }

    @Test void theClientIgnoresReasonsOnAnEnabledAnswerAndOnAFrameWithoutFlags() {
        assertEquals(0, SessionOffReason.read(true, SESSION_FLAG_OFF_SERVER | SESSION_FLAG_OFF_WORLD));
        assertEquals(0, SessionOffReason.read(false, SESSION_FLAGS_ABSENT));
        assertEquals(0, SessionOffReason.read(false, SESSION_FLAG_DENY_MAP_INGESTION), "an older server: not said");
        assertEquals(SESSION_FLAG_OFF_WORLD, SessionOffReason.read(false, SESSION_FLAG_OFF_WORLD | SESSION_FLAG_DENY_MAP_INGESTION));
        // Two bits from a server that broke the rule: the server's own precedence.
        assertEquals(SESSION_FLAG_OFF_SERVER, SessionOffReason.read(false, 0xE));
        assertEquals(SESSION_FLAG_OFF_PERMISSION, SessionOffReason.read(false, 0xC));
    }
}
