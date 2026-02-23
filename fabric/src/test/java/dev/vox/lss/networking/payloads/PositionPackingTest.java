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
}
