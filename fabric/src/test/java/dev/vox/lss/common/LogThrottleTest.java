package dev.vox.lss.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins the aggregate-warning window behind the disk reader's saturation log (#32): the
 * first event releases immediately, the interval suppresses the flood, the next release
 * carries the full suppressed count, and every event is counted exactly once.
 */
class LogThrottleTest {

    private static final long INTERVAL = 60_000;

    @Test
    void firstEventReleasesImmediatelyWithCountOne() {
        var throttle = new LogThrottle(INTERVAL);
        assertEquals(1, throttle.recordAndTryAcquire(0),
                "the condition must surface as soon as it starts, not one interval later");
    }

    @Test
    void eventsInsideTheIntervalStaySilent() {
        var throttle = new LogThrottle(INTERVAL);
        throttle.recordAndTryAcquire(0);
        for (long t = 1; t < INTERVAL; t += 10_000) {
            assertEquals(0, throttle.recordAndTryAcquire(t), "silent at t=" + t);
        }
    }

    @Test
    void nextReleaseCarriesEverySuppressedEvent() {
        var throttle = new LogThrottle(INTERVAL);
        throttle.recordAndTryAcquire(0);
        for (int i = 0; i < 41; i++) {
            throttle.recordAndTryAcquire(1_000 + i);
        }
        assertEquals(42, throttle.recordAndTryAcquire(INTERVAL),
                "41 suppressed + the releasing event itself — no event may vanish from the count");
    }

    @Test
    void countResetsAfterEachRelease() {
        var throttle = new LogThrottle(INTERVAL);
        throttle.recordAndTryAcquire(0);
        throttle.recordAndTryAcquire(5);
        assertEquals(2, throttle.recordAndTryAcquire(INTERVAL));
        assertEquals(1, throttle.recordAndTryAcquire(INTERVAL * 2),
                "a release must not re-report events already reported");
    }

    @Test
    void exactlyOneReleasePerWindowAcrossManyWindows() {
        var throttle = new LogThrottle(INTERVAL);
        long released = 0;
        long releases = 0;
        long events = 0;
        for (long t = 0; t < INTERVAL * 5; t += 7_000) {
            events++;
            long got = throttle.recordAndTryAcquire(t);
            if (got > 0) {
                releases++;
                released += got;
            }
        }
        assertEquals(5, releases, "one release per elapsed interval");
        long tail = throttle.recordAndTryAcquire(INTERVAL * 10);
        assertEquals(events + 1, released + tail, "conservation: every event reported exactly once");
    }

    @Test
    void aQuietStretchDoesNotBankExtraReleases() {
        var throttle = new LogThrottle(INTERVAL);
        throttle.recordAndTryAcquire(0);
        // Ten intervals of silence, then a burst: only ONE release is due, not ten.
        long t = INTERVAL * 10;
        assertEquals(1, throttle.recordAndTryAcquire(t));
        assertEquals(0, throttle.recordAndTryAcquire(t + 1), "the burst is inside the new window");
        assertEquals(0, throttle.recordAndTryAcquire(t + 2));
    }
}
