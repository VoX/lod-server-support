package dev.vox.lss.networking.payloads;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PositionPackingTest {

    @Test
    void positiveCoords() {
        long packed = ChunkRequestC2SPayload.packPosition(100, 200);
        assertEquals(100, ChunkRequestC2SPayload.unpackX(packed));
        assertEquals(200, ChunkRequestC2SPayload.unpackZ(packed));
    }

    @Test
    void negativeCoords() {
        long packed = ChunkRequestC2SPayload.packPosition(-50, -75);
        assertEquals(-50, ChunkRequestC2SPayload.unpackX(packed));
        assertEquals(-75, ChunkRequestC2SPayload.unpackZ(packed));
    }

    @Test
    void mixedSigns() {
        long packed = ChunkRequestC2SPayload.packPosition(-10, 20);
        assertEquals(-10, ChunkRequestC2SPayload.unpackX(packed));
        assertEquals(20, ChunkRequestC2SPayload.unpackZ(packed));

        long packed2 = ChunkRequestC2SPayload.packPosition(10, -20);
        assertEquals(10, ChunkRequestC2SPayload.unpackX(packed2));
        assertEquals(-20, ChunkRequestC2SPayload.unpackZ(packed2));
    }

    @Test
    void zeroCoords() {
        long packed = ChunkRequestC2SPayload.packPosition(0, 0);
        assertEquals(0, ChunkRequestC2SPayload.unpackX(packed));
        assertEquals(0, ChunkRequestC2SPayload.unpackZ(packed));
    }

    @Test
    void extremeValues() {
        long packed = ChunkRequestC2SPayload.packPosition(Integer.MAX_VALUE, Integer.MIN_VALUE);
        assertEquals(Integer.MAX_VALUE, ChunkRequestC2SPayload.unpackX(packed));
        assertEquals(Integer.MIN_VALUE, ChunkRequestC2SPayload.unpackZ(packed));

        long packed2 = ChunkRequestC2SPayload.packPosition(Integer.MIN_VALUE, Integer.MAX_VALUE);
        assertEquals(Integer.MIN_VALUE, ChunkRequestC2SPayload.unpackX(packed2));
        assertEquals(Integer.MAX_VALUE, ChunkRequestC2SPayload.unpackZ(packed2));
    }

    @Test
    void distinctness() {
        long packed12 = ChunkRequestC2SPayload.packPosition(1, 2);
        long packed21 = ChunkRequestC2SPayload.packPosition(2, 1);
        assertNotEquals(packed12, packed21);
    }
}
