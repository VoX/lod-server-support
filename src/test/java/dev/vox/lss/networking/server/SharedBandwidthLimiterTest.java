package dev.vox.lss.networking.server;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SharedBandwidthLimiterTest {

    @Test
    void singlePlayerGetsFullAllocation() {
        var limiter = new SharedBandwidthLimiter(10_000_000);
        long allocation = limiter.getPerPlayerAllocation(1);
        assertEquals(10_000_000, allocation);
    }

    @Test
    void multiPlayerFairSplit() {
        var limiter = new SharedBandwidthLimiter(10_000_000);
        long allocation = limiter.getPerPlayerAllocation(4);
        assertEquals(2_500_000, allocation);
    }

    @Test
    void zeroPlayersReturnsZero() {
        var limiter = new SharedBandwidthLimiter(10_000_000);
        long allocation = limiter.getPerPlayerAllocation(0);
        assertEquals(0, allocation);
    }

    @Test
    void recordSendReducesRemaining() {
        var limiter = new SharedBandwidthLimiter(10_000_000);
        limiter.recordSend(3_000_000);
        long allocation = limiter.getPerPlayerAllocation(1);
        assertEquals(7_000_000, allocation);
    }

    @Test
    void budgetExhaustedReturnsZero() {
        var limiter = new SharedBandwidthLimiter(10_000_000);
        limiter.recordSend(10_000_000);
        long allocation = limiter.getPerPlayerAllocation(1);
        assertEquals(0, allocation);
    }

    @Test
    void overBudgetReturnsZero() {
        var limiter = new SharedBandwidthLimiter(10_000_000);
        limiter.recordSend(15_000_000);
        long allocation = limiter.getPerPlayerAllocation(1);
        assertEquals(0, allocation);
    }

    @Test
    void totalBytesSentTracked() {
        var limiter = new SharedBandwidthLimiter(10_000_000);
        limiter.recordSend(1000);
        limiter.recordSend(2000);
        assertEquals(3000, limiter.getTotalBytesSentThisSecond());
    }
}
