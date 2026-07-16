package dev.vox.lss.paper;

import dev.vox.lss.common.processing.IncomingBatch;
import dev.vox.lss.common.processing.IncomingRequest;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Drives the REAL {@link PaperPlayerRequestState} subclass (PP-042). Replace/supersede
 * mechanics are pinned on the shared base by Fabric's BacklogReplaceTest against local
 * harness subclasses — this test exists to catch constructor drift in the Paper subclass
 * itself (UUID wiring, slot caps, the join-dimension capture, and the ingress glue), which
 * no other test constructs.
 *
 * <p>v17: the 16384-entry incoming queue and its drop/reopen mechanics are gone. The flood
 * bound they enforced is now structural — a player holds exactly ONE pending want-set batch
 * (itself capped at MAX_BATCH_CHUNK_REQUESTS on the wire), so there is no unbounded ingress
 * to defend against and nothing to "reopen by draining".
 */
class PaperPlayerRequestStateTest {

    @BeforeAll
    static void setup() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void secondBatchThroughTheRealSubclassSupersedesTheFirst() {
        var uuid = UUID.randomUUID();
        var level = mock(ServerLevel.class);
        when(level.dimension()).thenReturn(Level.OVERWORLD);
        var player = mock(ServerPlayer.class);
        when(player.getUUID()).thenReturn(uuid);
        when(player.level()).thenReturn(level);

        var state = new PaperPlayerRequestState(player, 7, 3);
        assertEquals(uuid, state.getPlayerUUID(), "constructor wires the player's UUID");
        assertEquals(7, state.getSyncSlotCap(), "sync concurrency reaches the shared base");
        assertEquals(3, state.getGenSlotCap(), "gen concurrency reaches the shared base");
        assertFalse(state.checkDimensionChange(), "the join dimension is captured at construction");

        state.offerIncomingBatch(new IncomingBatch(new IncomingRequest[]{
                new IncomingRequest(1, 0, -1L),
                new IncomingRequest(2, 0, -1L),
        }));
        assertEquals(2, state.getTotalRequestsReceived(),
                "every declared entry is counted received through the Paper glue");
        assertEquals(0, state.drainPendingSuperseded());

        // The client re-declares: the newer want-set REPLACES the older one wholesale. The
        // dropped entries are booked superseded, which is what closes the conservation ledger
        // without any wire response.
        state.offerIncomingBatch(new IncomingBatch(new IncomingRequest[]{
                new IncomingRequest(3, 0, 5L),
        }));
        assertEquals(3, state.getTotalRequestsReceived(), "received counts declarations, not survivors");
        assertEquals(2, state.drainPendingSuperseded(),
                "the superseded entries of the replaced batch are counted exactly once");
        assertEquals(0, state.drainPendingSuperseded(), "the drain is destructive");

        // Coordinates and clientTimestamp survive the Paper glue verbatim, and only the
        // newest declaration is visible to the processing thread.
        var taken = state.takeIncomingBatch();
        assertEquals(1, taken.size());
        assertEquals(3, taken.requests()[0].cx());
        assertEquals(0, taken.requests()[0].cz());
        assertEquals(5L, taken.requests()[0].clientTimestamp());
        assertNull(state.takeIncomingBatch(), "the mailbox is consumed by the take");
    }
}
