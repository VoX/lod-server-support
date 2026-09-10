package dev.vox.lss.common.diagnostics;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StatusCacheTest {
    @Test void boundedCollectionAndImmediateInvalidation() {
        var cache = new StatusCache<String>();
        long first = cache.lifecycle();
        assertTrue(cache.due(1));
        assertFalse(cache.due(499_999_999));
        assertTrue(cache.publish(first, "old world"));
        cache.invalidate();
        assertNull(cache.latest());
        assertFalse(cache.publish(first, "late callback"));
        assertTrue(cache.due(2));
        assertTrue(cache.publish(cache.lifecycle(), "new world"));
        assertEquals("new world", cache.latest());
    }
    @Test void invalidationFromAnotherThreadRejectsCapturedExportState() throws Exception {
        var cache = new StatusCache<String>();
        long captured = cache.lifecycle();
        var thread = new Thread(cache::invalidate);
        thread.start(); thread.join();
        assertFalse(cache.publish(captured, "stale"));
    }
}
