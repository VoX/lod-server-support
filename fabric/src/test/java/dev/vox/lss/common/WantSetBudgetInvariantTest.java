package dev.vox.lss.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Global Constraint #28 successor: no client budget derives from any server cap anymore
 *  (the caps left the wire under server-owned generation), so the old validate() cross-clamp is
 *  gone. What survives is the REASON it existed, now a static inequality between constants: the
 *  declared want-set must dominate the worst-case per-player in-flight (sync slots + the global
 *  generation ceiling — per-player gen can never exceed the global clamp) with frontier headroom
 *  left over, and must always fit one wire batch. If any constant changes, this test re-derives
 *  the boundary. */
class WantSetBudgetInvariantTest {

    @Test
    void wantSetDominatesWorstCaseInFlightAndFitsOneBatch() {
        assertTrue(LSSConstants.WANT_SET_BUDGET <= LSSConstants.MAX_BATCH_CHUNK_REQUESTS,
                "want-set budget must fit one wire batch");
        assertTrue(LSSConstants.SYNC_ON_LOAD_SLOT_CAP
                        + LSSConstants.MAX_CONCURRENT_GENERATIONS
                        + LSSConstants.WANT_SET_FRONTIER_RESERVE
                        <= LSSConstants.WANT_SET_BUDGET,
                "budget must dominate max in-flight (sync cap + global gen ceiling) + frontier reserve");
        assertTrue(LSSConstants.SYNC_ON_LOAD_SLOT_CAP <= LSSConstants.MAX_CONCURRENCY_LIMIT,
                "the constant slot cap must respect the protocol concurrency ceiling");
    }
}
