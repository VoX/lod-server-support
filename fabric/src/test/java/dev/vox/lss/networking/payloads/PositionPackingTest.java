package dev.vox.lss.networking.payloads;

import dev.vox.lss.common.PositionUtil;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PositionPackingTest {

    @Test
    void positiveCoords() {
        long packed = PositionUtil.packPosition(100, 200);
        assertEquals(100, PositionUtil.unpackX(packed));
        assertEquals(200, PositionUtil.unpackZ(packed));
    }

    @Test
    void negativeCoords() {
        long packed = PositionUtil.packPosition(-50, -75);
        assertEquals(-50, PositionUtil.unpackX(packed));
        assertEquals(-75, PositionUtil.unpackZ(packed));
    }

    @Test
    void mixedSigns() {
        long packed = PositionUtil.packPosition(-10, 20);
        assertEquals(-10, PositionUtil.unpackX(packed));
        assertEquals(20, PositionUtil.unpackZ(packed));

        long packed2 = PositionUtil.packPosition(10, -20);
        assertEquals(10, PositionUtil.unpackX(packed2));
        assertEquals(-20, PositionUtil.unpackZ(packed2));
    }

    @Test
    void zeroCoords() {
        long packed = PositionUtil.packPosition(0, 0);
        assertEquals(0, PositionUtil.unpackX(packed));
        assertEquals(0, PositionUtil.unpackZ(packed));
    }

    @Test
    void extremeValues() {
        long packed = PositionUtil.packPosition(Integer.MAX_VALUE, Integer.MIN_VALUE);
        assertEquals(Integer.MAX_VALUE, PositionUtil.unpackX(packed));
        assertEquals(Integer.MIN_VALUE, PositionUtil.unpackZ(packed));

        long packed2 = PositionUtil.packPosition(Integer.MIN_VALUE, Integer.MAX_VALUE);
        assertEquals(Integer.MIN_VALUE, PositionUtil.unpackX(packed2));
        assertEquals(Integer.MAX_VALUE, PositionUtil.unpackZ(packed2));
    }

    @Test
    void distinctness() {
        long packed12 = PositionUtil.packPosition(1, 2);
        long packed21 = PositionUtil.packPosition(2, 1);
        assertNotEquals(packed12, packed21);
    }

    // ---- chebyshevDistance / isOutOfRange (the request distance gate) ----

    @Test
    void chebyshevDistanceBasics() {
        assertEquals(0, PositionUtil.chebyshevDistance(5, -5, 5, -5));
        assertEquals(4, PositionUtil.chebyshevDistance(0, 0, 3, -4));
        assertEquals(4, PositionUtil.chebyshevDistance(3, -4, 0, 0), "distance must be symmetric");
    }

    @Test
    void chebyshevDistanceExtremeCoordsClampInsteadOfOverflowing() {
        // int math would wrap: MIN_VALUE - MAX_VALUE == 1, and Math.abs(MIN_VALUE) stays negative
        assertEquals(Integer.MAX_VALUE,
                PositionUtil.chebyshevDistance(Integer.MIN_VALUE, 0, Integer.MAX_VALUE, 0));
        assertEquals(Integer.MAX_VALUE,
                PositionUtil.chebyshevDistance(0, Integer.MIN_VALUE, 0, 0));
    }

    @Test
    void isOutOfRangeBoundaryIsInclusive() {
        assertFalse(PositionUtil.isOutOfRange(PositionUtil.packPosition(64, -64), 0, 0, 64),
                "exactly at the distance limit is still in range");
        assertTrue(PositionUtil.isOutOfRange(PositionUtil.packPosition(65, 0), 0, 0, 64));
        assertTrue(PositionUtil.isOutOfRange(PositionUtil.packPosition(0, -65), 0, 0, 64));
    }

    @Test
    void hostileExtremeCoordinatesCannotSlipUnderTheGate() {
        // Overflowed int math reports these as near the player (negative or wrapped-small
        // distance) and the server would serve them. 2048 = MAX_LOD_DISTANCE.
        assertTrue(PositionUtil.isOutOfRange(
                PositionUtil.packPosition(Integer.MIN_VALUE, Integer.MIN_VALUE), 0, 0, 2048));
        // Player near the world border, request wraps around: int distance would be 21
        assertTrue(PositionUtil.isOutOfRange(
                PositionUtil.packPosition(Integer.MIN_VALUE + 10, 0), Integer.MAX_VALUE - 10, 0, 2048));
    }
}
