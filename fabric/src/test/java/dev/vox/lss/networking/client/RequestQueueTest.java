package dev.vox.lss.networking.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the remainder-preservation contract that {@link SpiralScanner#maybeScan} relies on:
 * a scan that accepts nothing performs no writes and no commit, so an undrained remainder
 * from the previous scan keeps draining, and {@link RequestQueue#ensureCapacity} must
 * preserve existing contents (an undrained remainder must survive a capacity increase).
 */
class RequestQueueTest {

    @Test
    void undrainedRemainderSurvivesCapacityIncrease() {
        RequestQueue queue = new RequestQueue();
        queue.ensureCapacity(3);
        queue.put(0, 100L, 1000L);
        queue.put(1, 200L, 2000L);
        queue.put(2, 300L, 3000L);
        queue.commit(3);

        queue.skip(); // drain one entry

        queue.ensureCapacity(8); // a later scan may grow the buffer before accepting anything

        assertEquals(2, queue.remaining());
        assertEquals(200L, queue.peekPosition());
        assertEquals(2000L, queue.peekTimestamp());
        queue.skip();
        assertEquals(300L, queue.peekPosition());
        assertEquals(3000L, queue.peekTimestamp());
    }

    @Test
    void remainderKeepsDrainingWhenNoCommitFollows() {
        RequestQueue queue = new RequestQueue();
        queue.ensureCapacity(2);
        queue.put(0, 100L, 1000L);
        queue.put(1, 200L, 2000L);
        queue.commit(2);

        // No further put/commit (an empty scan) — the remainder drains to empty.
        assertTrue(queue.hasNext());
        assertEquals(100L, queue.peekPosition());
        queue.skip();
        assertTrue(queue.hasNext());
        assertEquals(200L, queue.peekPosition());
        queue.skip();
        assertFalse(queue.hasNext());
        assertEquals(0, queue.remaining());
    }

    @Test
    void commitReplacesPreviousRemainderAndResetsReadIndex() {
        RequestQueue queue = new RequestQueue();
        queue.ensureCapacity(3);
        queue.put(0, 100L, 1000L);
        queue.put(1, 200L, 2000L);
        queue.put(2, 300L, 3000L);
        queue.commit(3);
        queue.skip(); // leave an undrained remainder with readIndex > 0

        queue.put(0, 900L, 9000L);
        queue.commit(1);

        assertEquals(1, queue.remaining());
        assertEquals(900L, queue.peekPosition());
        assertEquals(9000L, queue.peekTimestamp());
        queue.skip();
        assertFalse(queue.hasNext());
    }

    @Test
    void clearEmptiesQueue() {
        RequestQueue queue = new RequestQueue();
        queue.ensureCapacity(2);
        queue.put(0, 100L, 1000L);
        queue.put(1, 200L, 2000L);
        queue.commit(2);

        queue.clear();

        assertFalse(queue.hasNext());
        assertEquals(0, queue.remaining());
    }
}
