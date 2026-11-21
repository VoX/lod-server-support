package dev.vox.lss.common.processing;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiskSpaceHoldTest {

    /** {@code -Dlss.lowDiskHoldMiB}: the floor an operator can move, and 0 turns the hold off. */
    @org.junit.jupiter.api.Test
    void theFloorCanBeMovedAndZeroTurnsTheHoldOff() {
        org.junit.jupiter.api.Assertions.assertEquals(2L << 30, DiskSpaceHold.floorBytes(null), "the default");
        org.junit.jupiter.api.Assertions.assertEquals(2L << 30, DiskSpaceHold.floorBytes("not a number"));
        org.junit.jupiter.api.Assertions.assertEquals(512L << 20, DiskSpaceHold.floorBytes(" 512 "));
        org.junit.jupiter.api.Assertions.assertEquals(0L, DiskSpaceHold.floorBytes("0"));
        org.junit.jupiter.api.Assertions.assertEquals(0L, DiskSpaceHold.floorBytes("-5"));
        org.junit.jupiter.api.Assertions.assertEquals(1L << 40, DiskSpaceHold.floorBytes("99999999999"));
        org.junit.jupiter.api.Assertions.assertEquals(2L << 30, DiskSpaceHold.HOLD_BELOW_BYTES,
                "this test JVM runs with the default");
        org.junit.jupiter.api.Assertions.assertEquals(3L << 30, DiskSpaceHold.RELEASE_AT_BYTES);

        long[] free = {0L};
        var off = new DiskSpaceHold(() -> free[0], 0L, 0L, 0L);
        org.junit.jupiter.api.Assertions.assertFalse(off.poll(1L), "a floor of 0 never holds, even on a full disk");
        org.junit.jupiter.api.Assertions.assertFalse(off.held());

        var moved = new DiskSpaceHold(() -> free[0], 0L, 512L << 20, 768L << 20);
        free[0] = 600L << 20;
        org.junit.jupiter.api.Assertions.assertFalse(moved.poll(2L), "600 MB is above a 512 MB floor");
        free[0] = 500L << 20;
        org.junit.jupiter.api.Assertions.assertTrue(moved.poll(3L));
        free[0] = 700L << 20;
        org.junit.jupiter.api.Assertions.assertFalse(moved.poll(4L), "held until the higher mark");
        free[0] = 768L << 20;
        org.junit.jupiter.api.Assertions.assertTrue(moved.poll(5L));
        org.junit.jupiter.api.Assertions.assertFalse(moved.held());
    }
    private static final long GIB = 1L << 30;

    @Test
    void theHoldStartsBelowTheFloorAndEndsAtTheHigherMark() {
        long[] free = {10 * GIB};
        var hold = new DiskSpaceHold(() -> free[0], 0);
        assertFalse(hold.poll(1));
        assertFalse(hold.held());

        free[0] = DiskSpaceHold.HOLD_BELOW_BYTES; // exactly the floor is still enough
        assertFalse(hold.poll(2));
        free[0] = DiskSpaceHold.HOLD_BELOW_BYTES - 1;
        assertTrue(hold.poll(3), "the change is reported once");
        assertTrue(hold.held());
        assertFalse(hold.poll(4), "and not again while nothing changes");

        // between the two marks the hold stays: a server hovering at the floor must not flap
        free[0] = DiskSpaceHold.HOLD_BELOW_BYTES + GIB / 2;
        assertFalse(hold.poll(5));
        assertTrue(hold.held());
        free[0] = DiskSpaceHold.RELEASE_AT_BYTES - 1;
        assertFalse(hold.poll(6));
        assertTrue(hold.held());

        free[0] = DiskSpaceHold.RELEASE_AT_BYTES;
        assertTrue(hold.poll(7));
        assertFalse(hold.held());
        assertEquals(DiskSpaceHold.RELEASE_AT_BYTES, hold.lastReadingBytes());

        // and falling back between the marks does not start a hold
        free[0] = DiskSpaceHold.HOLD_BELOW_BYTES + 1;
        assertFalse(hold.poll(8));
        assertFalse(hold.held());
    }

    /** An unknown disk is not a full one, and does not end a hold either. */
    @Test
    void aFailedReadingChangesNothing() {
        long[] free = {-1};
        boolean[] throwing = {false};
        var hold = new DiskSpaceHold(() -> {
            if (throwing[0]) throw new IllegalStateException("boom");
            return free[0];
        }, 0);
        assertFalse(hold.poll(1));
        assertFalse(hold.held());
        throwing[0] = true;
        assertFalse(hold.poll(2));
        assertFalse(hold.held());

        throwing[0] = false;
        free[0] = GIB;
        assertTrue(hold.poll(3));
        free[0] = -1;
        assertFalse(hold.poll(4));
        assertTrue(hold.held(), "a failed reading must not release a hold");
        throwing[0] = true;
        assertFalse(hold.poll(5));
        assertTrue(hold.held());
    }

    @Test
    void readingsAreFiveSecondsApartAndTheFirstIsImmediate() {
        int[] readings = {0};
        var hold = new DiskSpaceHold(() -> { readings[0]++; return 10 * GIB; });
        long start = 123_456_789L;
        hold.poll(start);
        assertEquals(1, readings[0], "the first poll reads");
        hold.poll(start + 1);
        hold.poll(start + DiskSpaceHold.INTERVAL_NANOS - 1);
        assertEquals(1, readings[0]);
        hold.poll(start + DiskSpaceHold.INTERVAL_NANOS);
        assertEquals(2, readings[0]);
        // nanoTime may be negative or wrap; the comparison is by difference
        var wrapping = new DiskSpaceHold(() -> { readings[0]++; return 10 * GIB; });
        wrapping.poll(Long.MAX_VALUE - 10);
        int before = readings[0];
        wrapping.poll(Long.MAX_VALUE - 10 + DiskSpaceHold.INTERVAL_NANOS); // wrapped to negative
        assertEquals(before + 1, readings[0]);
    }

    @Test
    void aRealDirectoryGivesAReadingAndAMissingOneUsesItsParent(@TempDir Path directory) {
        var existing = DiskSpaceHold.forDirectory(directory);
        existing.poll(1);
        assertTrue(existing.lastReadingBytes() > 0, "a real volume reports its free space");

        var missing = DiskSpaceHold.forDirectory(directory.resolve("not/created/yet"));
        missing.poll(1);
        assertTrue(missing.lastReadingBytes() > 0, "the nearest existing parent is on the same volume");

        var none = DiskSpaceHold.forDirectory(null);
        assertFalse(none.poll(1));
        assertFalse(none.held());
        assertTrue(none.lastReadingBytes() < 0);
    }
}
