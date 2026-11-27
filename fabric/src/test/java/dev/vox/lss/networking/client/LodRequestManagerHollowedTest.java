package dev.vox.lss.networking.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.common.PositionUtil;
import dev.vox.lss.networking.payloads.SessionConfigS2CPayload;
import net.minecraft.SharedConstants;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Issue #437 (plan §3.2): a column Vertigo hollowed in Voxy is re-asked only once the server
 * has let the chunk go — far enough away and old enough, or after the maximum hold — so the
 * re-ask is a store serve and not a main-thread serialize of a loaded chunk.
 */
class LodRequestManagerHollowedTest {
    static final long S = 1_000_000_000L;
    ResourceKey<Level> OVERWORLD; // set after the bootstrap: Level must not initialize first
    static final long P = PositionUtil.packPosition(10, 0);

    LodRequestManager manager;
    final long[] now = {100 * S};

    @BeforeAll static void boot() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

    @BeforeEach void setUp() {
        OVERWORLD = Level.OVERWORLD;
        manager = new LodRequestManager();
        manager.joinSlowStartEnabled = () -> false;
        manager.onSessionConfig(new SessionConfigS2CPayload(LSSConstants.PROTOCOL_VERSION, true, 64, true), "lss-hollow-test");
        manager.markCacheLoadedForTest();
        manager.setBatchSenderForTest(p -> {});
        manager.hollowClock = () -> now[0];
        manager.tickWithContext(0, 0, OVERWORLD, 8, 0, 0L, -1, () -> 0); // the session's dimension
        manager.onColumnReceived(P, 5_000L, OVERWORLD);
        assertEquals(5_000L, manager.columnsForTest().timestampFor(P), "premise: delivered and stamped");
        assertEquals(ColumnStateMap.SATISFIED, manager.columnsForTest().classify(P));
    }

    @Test void theReaskWaitsUntilTheServerHasLetTheChunkGo() {
        manager.onHollowed(OVERWORLD, P, 8);
        now[0] += 2 * S;
        manager.tickHollowedPhase(-10, 0); // far, but too young
        assertEquals(5_000L, manager.columnsForTest().timestampFor(P));
        now[0] += 4 * S;
        manager.tickHollowedPhase(0, 0);   // old enough, but 10 <= 8 + 4: still loaded there
        assertEquals(5_000L, manager.columnsForTest().timestampFor(P));
        manager.tickHollowedPhase(-3, 0);  // 13 > 12
        assertEquals(-1L, manager.columnsForTest().classify(P), "now asked with ts=-1");
        assertEquals(1, manager.getVertigoRefills());
        assertEquals(0, manager.hollowPendingCount());
    }

    @Test void aHoverIsReleasedAfterTheMaximumHold() {
        manager.onHollowed(OVERWORLD, P, 8);
        now[0] += 29 * S;
        manager.tickHollowedPhase(0, 0);
        assertEquals(5_000L, manager.columnsForTest().timestampFor(P));
        now[0] += 2 * S;
        manager.tickHollowedPhase(0, 0);
        assertEquals(-1L, manager.columnsForTest().classify(P));
    }

    @Test void aCloseHeadNeverBlocksTheEntriesBehindIt() {
        long farOne = PositionUtil.packPosition(-40, 0);
        manager.onColumnReceived(farOne, 6_000L, OVERWORLD);
        manager.onHollowed(OVERWORLD, P, 8);      // head: stays close to the player
        manager.onHollowed(OVERWORLD, farOne, 8); // behind it: far away
        now[0] += 6 * S;
        manager.tickHollowedPhase(0, 0);
        assertEquals(-1L, manager.columnsForTest().classify(farOne), "released past the close head");
        assertEquals(5_000L, manager.columnsForTest().timestampFor(P));
        assertEquals(1, manager.hollowPendingCount());
    }

    @Test void theReleaseDropsTheInFlightEntrySoAStaleUpToDateCannotParkIt() {
        manager.onHollowed(OVERWORLD, P, 8);
        manager.trackerForTest().replaceWith(new long[]{P}, 1); // an ask still carrying 5000
        now[0] += 6 * S;
        manager.tickHollowedPhase(-10, 0);
        assertFalse(manager.trackerForTest().isInFlight(P));
        manager.onColumnUpToDate(P); // the answer to the old ask
        assertEquals(-1L, manager.columnsForTest().classify(P), "still re-asked, not parked");
    }

    @Test void aReloadOrAReceivedColumnDropsThePendingReask() {
        manager.onHollowed(OVERWORLD, P, 8);
        manager.onVanillaChunkLoaded(OVERWORLD, P);
        assertEquals(0, manager.hollowPendingCount(), "the next unload decides again");
        manager.onHollowed(OVERWORLD, P, 8);
        manager.onColumnReceived(P, 7_000L, OVERWORLD);
        assertEquals(0, manager.hollowPendingCount(), "repaired already");
    }

    @Test void anotherDimensionIsIgnored() {
        manager.onHollowed(Level.NETHER, P, 8);
        assertEquals(0, manager.hollowPendingCount());
    }

    @Test void pendingReasksArePersistedAsRemovalsBeforeASave() {
        manager.onHollowed(OVERWORLD, P, 8);
        manager.flushHollowedPending();
        assertEquals(0, manager.hollowPendingCount());
        assertEquals(-1L, manager.columnsForTest().timestampFor(P));
        assertTrue(manager.columnsForTest().persistentRemovalsForSave().contains(P),
                "the next session asks again instead of drawing a false up_to_date");
    }

    @Test void aRetiredSessionTakesNoReports() {
        manager.retireAcquisition();
        manager.onHollowed(OVERWORLD, P, 8);
        assertEquals(0, manager.hollowPendingCount());
    }
}
