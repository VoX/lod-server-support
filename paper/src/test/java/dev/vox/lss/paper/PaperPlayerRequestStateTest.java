package dev.vox.lss.paper;

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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Floods the REAL {@link PaperPlayerRequestState} subclass (PP-042). The 16384 bound,
 * drop counting, and drain-reopen mechanics are pinned on the shared base by Fabric's
 * SlotAdmissionTest against local harness subclasses — this test exists to catch
 * constructor drift in the Paper subclass itself (UUID wiring, slot caps, the join-
 * dimension capture, and the addRequest glue), which no other test constructs.
 */
class PaperPlayerRequestStateTest {

    // Mirror of AbstractPlayerRequestState.MAX_INCOMING_QUEUE (private by design).
    private static final int MAX_INCOMING_QUEUE = 16384;

    @BeforeAll
    static void setup() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void floodThroughTheRealSubclassDropsAtTheSharedBoundAndReopensOnDrain() {
        var uuid = UUID.randomUUID();
        var level = mock(ServerLevel.class);
        when(level.dimension()).thenReturn(Level.OVERWORLD);
        var player = mock(ServerPlayer.class);
        when(player.getUUID()).thenReturn(uuid);
        when(player.serverLevel()).thenReturn(level);

        var state = new PaperPlayerRequestState(player, 7, 3);
        assertEquals(uuid, state.getPlayerUUID(), "constructor wires the player's UUID");
        assertEquals(7, state.getSyncSlotCap(), "sync concurrency reaches the shared base");
        assertEquals(3, state.getGenSlotCap(), "gen concurrency reaches the shared base");
        assertFalse(state.checkDimensionChange(), "the join dimension is captured at construction");

        for (int i = 0; i < MAX_INCOMING_QUEUE + 100; i++) {
            state.addRequest(i, 0, -1L);
        }
        assertEquals(MAX_INCOMING_QUEUE, state.getTotalRequestsReceived(),
                "the flood bound holds through the Paper addRequest glue");
        assertEquals(100, state.getTotalIncomingDropped());

        // Draining reopens exactly the drained capacity (shared-base contract via the subclass)
        for (int i = 0; i < 50; i++) {
            assertNotNull(state.pollIncomingRequest());
        }
        for (int i = 0; i < 60; i++) {
            state.addRequest(100_000 + i, 0, 5L);
        }
        assertEquals(MAX_INCOMING_QUEUE + 50, state.getTotalRequestsReceived());
        assertEquals(110, state.getTotalIncomingDropped(), "the 10 past the reopened capacity drop");

        // Coordinates and clientTimestamp survive the Paper glue verbatim
        var next = state.pollIncomingRequest();
        assertEquals(50, next.cx());
        assertEquals(0, next.cz());
        assertEquals(-1L, next.clientTimestamp());
    }
}
