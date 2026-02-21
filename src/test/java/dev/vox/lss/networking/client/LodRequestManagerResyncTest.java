package dev.vox.lss.networking.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LodRequestManagerResyncTest {

    @Test
    void resyncBudgetNonZeroWhenNeeded() {
        // maxRequestsPerBatch=256, resyncBatchSize=32, needsResync=true
        int[] budgets = LodRequestManager.computeBudgets(256, 32, true);
        int budgetRemaining = budgets[0];
        int resyncRemaining = budgets[1];

        assertTrue(resyncRemaining > 0, "resyncRemaining should be > 0 when needsResync is true");
        assertEquals(32, resyncRemaining, "resyncRemaining should be min(32, 256/4=64) = 32");
        assertEquals(224, budgetRemaining, "budgetRemaining should be 256 - 32 = 224");
        assertEquals(256, budgetRemaining + resyncRemaining, "total budget should equal maxRequestsPerBatch");
    }

    @Test
    void resyncBudgetZeroWhenNotNeeded() {
        int[] budgets = LodRequestManager.computeBudgets(256, 32, false);
        assertEquals(256, budgets[0]);
        assertEquals(0, budgets[1]);
    }

    @Test
    void resyncBudgetCappedToQuarter() {
        // resyncBatchSize=200, maxRequestsPerBatch=256 → cap to 256/4=64
        int[] budgets = LodRequestManager.computeBudgets(256, 200, true);
        assertEquals(64, budgets[1], "resyncRemaining should be capped to maxRequestsPerBatch/4");
        assertEquals(192, budgets[0]);
    }

    @Test
    void resyncBudgetSmallBatch() {
        // maxRequestsPerBatch=4, resyncBatchSize=32 → 4/4=1
        int[] budgets = LodRequestManager.computeBudgets(4, 32, true);
        assertEquals(1, budgets[1]);
        assertEquals(3, budgets[0]);
    }

    @Test
    void resyncBudgetZeroBatch() {
        // maxRequestsPerBatch=0 edge case
        int[] budgets = LodRequestManager.computeBudgets(0, 32, true);
        assertEquals(0, budgets[1]);
        assertEquals(0, budgets[0]);
    }
}
