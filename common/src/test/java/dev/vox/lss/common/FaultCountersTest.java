package dev.vox.lss.common;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FaultCountersTest {

    private final AtomicLong now = new AtomicLong();
    private final List<String> logged = new ArrayList<>();
    private final FaultCounters counters = new FaultCounters(this.now::get, this.logged::add);

    @Test
    void nothingFailedPrintsNothing() {
        assertEquals("", this.counters.diagLine());
    }

    @Test
    void everySurvivedFailureIsCountedUnderItsSite() {
        this.counters.record(FaultCounters.Site.STORE_MAINTENANCE, new IllegalStateException("disk full"));
        this.counters.record(FaultCounters.Site.STORE_MAINTENANCE, "checkpoint failed");
        this.counters.record(FaultCounters.Site.PERMISSION_CHECK, (Throwable) null);
        assertEquals(2, this.counters.count(FaultCounters.Site.STORE_MAINTENANCE));
        assertEquals(1, this.counters.count(FaultCounters.Site.PERMISSION_CHECK));
        assertEquals(0, this.counters.count(FaultCounters.Site.VANISH_CHECK));
        assertEquals("Recovered errors: store.maintenance=2 permission.check=1", this.counters.diagLine());
    }

    @Test
    void theFirstFailureIsLoggedAtOnceAndARepeatingOneAtMostOnceAMinutePerSite() {
        this.counters.record(FaultCounters.Site.STORE_MAINTENANCE, "first");
        for (int i = 0; i < 50; i++) {
            this.now.addAndGet(1_000);
            this.counters.record(FaultCounters.Site.STORE_MAINTENANCE, "again");
        }
        assertEquals(1, this.logged.size(), "50 seconds of repeats stay quiet");
        assertTrue(this.logged.get(0).contains("store.maintenance") && this.logged.get(0).contains("first"));

        // Another site is not silenced by the first one's window.
        this.counters.record(FaultCounters.Site.VANISH_CHECK, "other site");
        assertEquals(2, this.logged.size());

        this.now.addAndGet(10_000);
        this.counters.record(FaultCounters.Site.STORE_MAINTENANCE, "after the minute");
        assertEquals(3, this.logged.size());
        assertTrue(this.logged.get(2).contains("(51 since the last report)"), this.logged.get(2));
        assertTrue(this.logged.get(2).contains("Total so far: 52"), this.logged.get(2));
        assertEquals(52, this.counters.count(FaultCounters.Site.STORE_MAINTENANCE));
    }
}
