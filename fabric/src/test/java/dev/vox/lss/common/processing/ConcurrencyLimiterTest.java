package dev.vox.lss.common.processing;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ConcurrencyLimiterTest {

    @Test
    void tryAcquireSucceedsUpToMaxConcurrency() {
        var limiter = new ConcurrencyLimiter(3);
        assertTrue(limiter.tryAcquire());
        assertTrue(limiter.tryAcquire());
        assertTrue(limiter.tryAcquire());
        assertEquals(3, limiter.getCurrentConcurrency());
    }

    @Test
    void tryAcquireFailsAtMaxConcurrency() {
        var limiter = new ConcurrencyLimiter(3);
        assertTrue(limiter.tryAcquire());
        assertTrue(limiter.tryAcquire());
        assertTrue(limiter.tryAcquire());
        assertFalse(limiter.tryAcquire());
    }

    @Test
    void releaseFreesSlotAndNextAcquireSucceeds() {
        var limiter = new ConcurrencyLimiter(2);
        assertTrue(limiter.tryAcquire());
        assertTrue(limiter.tryAcquire());
        assertFalse(limiter.tryAcquire());

        limiter.release();
        assertEquals(1, limiter.getCurrentConcurrency());
        assertTrue(limiter.tryAcquire());
    }

    @Test
    void releaseBelowZeroIsClamped() {
        var limiter = new ConcurrencyLimiter(10);
        assertEquals(0, limiter.getCurrentConcurrency());
        limiter.release();
        limiter.release();
        assertEquals(0, limiter.getCurrentConcurrency());
    }

    @Test
    void getMaxConcurrencyReturnsConfiguredValue() {
        var limiter = new ConcurrencyLimiter(5);
        assertEquals(5, limiter.getMaxConcurrency());
    }
}
