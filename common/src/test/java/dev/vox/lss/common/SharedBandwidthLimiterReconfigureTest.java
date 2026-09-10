package dev.vox.lss.common;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SharedBandwidthLimiter.reconfigure (v0.11.0 stage C — the /lsslod set tick-poll):
 * ceiling raise/lower semantics, the banked-token clamp-down on lowering, and refills
 * honoring the NEW ceiling from the next window.
 */
class SharedBandwidthLimiterReconfigureTest {

    @Test
    void loweringClampsBankedTokensAndRefillsAtTheNewRate() {
        var clock = new AtomicLong(0);
        var limiter = new SharedBandwidthLimiter(1000, clock::get);
        assertEquals(1000, limiter.getPerPlayerAllocation(1), "full bank at the old ceiling");

        limiter.reconfigure(100);
        assertEquals(100, limiter.getMaxBytesPerSecond());
        assertEquals(100, limiter.getPerPlayerAllocation(1),
                "a full old-ceiling bank must not ride out under the new cap");

        limiter.recordSend(100);
        clock.addAndGet(LSSConstants.NANOS_PER_SECOND);
        assertEquals(100, limiter.getPerPlayerAllocation(1),
                "one elapsed second refills to the NEW ceiling, not the old");
    }

    @Test
    void raisingTakesEffectAtTheNextRefillWindow() {
        var clock = new AtomicLong(0);
        var limiter = new SharedBandwidthLimiter(100, clock::get);
        limiter.recordSend(100); // drain the bank
        limiter.reconfigure(1000);
        clock.addAndGet(LSSConstants.NANOS_PER_SECOND);
        assertEquals(1000, limiter.getPerPlayerAllocation(1),
                "a second at the raised rate credits the raised ceiling");
    }

    @Test
    void unchangedValueIsANoOpAndDebtSurvivesReconfigure() {
        var clock = new AtomicLong(0);
        var limiter = new SharedBandwidthLimiter(1000, clock::get);
        limiter.recordSend(1500); // 500 into debt
        limiter.reconfigure(1000); // no-op compare
        assertEquals(0, limiter.getPerPlayerAllocation(1), "debt is not forgiven by a no-op");
        limiter.reconfigure(2000);
        assertEquals(0, limiter.getPerPlayerAllocation(1),
                "raising the ceiling never mints tokens instantly — debt still owed");
        clock.addAndGet(LSSConstants.NANOS_PER_SECOND);
        assertEquals(1500, limiter.getPerPlayerAllocation(1),
                "one second at 2000 pays the 500 debt and banks 1500");
    }
}
