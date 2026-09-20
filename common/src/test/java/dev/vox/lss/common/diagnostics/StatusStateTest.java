package dev.vox.lss.common.diagnostics;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static dev.vox.lss.common.diagnostics.ClientStatusSnapshot.*;

class StatusStateTest {
    private static ClientStatusSnapshot snapshot(boolean connected, boolean receive, boolean consumer,
            Discovery discovery, boolean server, Availability integration, int queue, int ingest, int cap, long gated, int rebuilds) {
        return new ClientStatusSnapshot(1, 4, 123, connected, discovery == Discovery.NEGOTIATED,
                receive, server, consumer, false, 20, 512, 128, 0, 0, queue, ingest, 0, cap, gated,
                rebuilds, discovery, integration, null, DiagnosticVersions.unknown());
    }
    @Test void noManagerStatesRemainUsefulAndMutuallyHonest() {
        record Case(ClientStatusSnapshot snapshot, List<Reason> expected) {}
        var cases = List.of(
                new Case(snapshot(false,true,false,Discovery.NOT_CONNECTED,false,Availability.UNKNOWN,0,-1,0,0,0), List.of(Reason.NOT_CONNECTED,Reason.NO_CONSUMER)),
                new Case(snapshot(true,false,true,Discovery.DORMANT,false,Availability.DISABLED,0,-1,0,0,0), List.of(Reason.RECEPTION_OFF)),
                new Case(snapshot(true,true,true,Discovery.AWAITING_NEGOTIATION,false,Availability.UNKNOWN,0,-1,0,0,0), List.of(Reason.AWAITING_NEGOTIATION)),
                new Case(snapshot(true,true,true,Discovery.SEND_FAILED,false,Availability.UNKNOWN,0,-1,0,0,0), List.of(Reason.HANDSHAKE_SEND_FAILED)),
                new Case(snapshot(true,true,true,Discovery.PROTOCOL_REJECTED,false,Availability.UNKNOWN,0,-1,0,0,0), List.of(Reason.PROTOCOL_REJECTED)),
                new Case(snapshot(true,true,true,Discovery.NEGOTIATED,false,Availability.ABSENT,0,-1,0,0,0), List.of(Reason.SERVER_DISABLED)),
                new Case(snapshot(true,true,false,Discovery.DORMANT,false,Availability.UNAVAILABLE,0,-1,0,0,0), List.of(Reason.NO_CONSUMER,Reason.XAERO_UNAVAILABLE)),
                new Case(snapshot(true,true,false,Discovery.DORMANT,false,Availability.FAILED,0,-1,0,0,0), List.of(Reason.NO_CONSUMER,Reason.XAERO_FAILED)));
        for (var test : cases) {
            assertEquals(test.expected(), test.snapshot().reasons());
            assertTrue(test.snapshot().lines().stream().anyMatch(line -> line.contains("Snapshot age:")));
            assertTrue(test.snapshot().lines().stream().anyMatch(line -> line.contains("Versions:")));
            assertTrue(test.snapshot().lines().stream().anyMatch(line -> line.contains("unavailable on this loader/line")));
        }
    }
    @Test void intervalCapAndQueueObservationsCoexistWithoutHistoricalPressureClaim() {
        var pending = snapshot(true,true,true,Discovery.NEGOTIATED,true,Availability.AVAILABLE,3,2,20,1,4);
        assertEquals(List.of(Reason.LOCAL_RATE_CAP,Reason.DECODE_QUEUE_PENDING,Reason.INGEST_QUEUE_PENDING,Reason.XAERO_REBUILDS),pending.reasons());
        assertEquals(List.of(Reason.LOCAL_RATE_CAP),snapshot(true,true,true,Discovery.NEGOTIATED,true,Availability.AVAILABLE,0,0,0,1,0).reasons());
        assertEquals(List.of(),snapshot(true,true,true,Discovery.NEGOTIATED,true,Availability.AVAILABLE,0,0,20,0,0).reasons());
    }
}
