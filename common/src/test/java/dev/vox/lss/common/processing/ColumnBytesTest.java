package dev.vox.lss.common.processing;

import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.common.store.StoreCodec;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The compressed-column holder (compressed-columns-implementation-plan.md §0.4): one
 * holder per drained result, lazy raw&harr;frame conversion memoized in both directions,
 * and the codec-0 rules — no codec attached, below {@link
 * LSSConstants#COLUMN_COMPRESS_MIN_BYTES}, or a frame that did not shrink the input.
 */
class ColumnBytesTest {

    private static StoreCodec codec;

    @BeforeAll
    static void probeCodec() {
        codec = StoreCodec.zstdOrNull();
        assumeTrue(codec != null, "zstd native unavailable on this platform");
    }

    private static byte[] compressible(int len) {
        return new byte[len]; // zeros compress extremely well
    }

    private static byte[] incompressible(int len) {
        byte[] b = new byte[len];
        new Random(42).nextBytes(b);
        return b;
    }

    @Test
    void belowThresholdNeverFrames() {
        var bytes = ColumnBytes.ofRaw(codec, compressible(LSSConstants.COLUMN_COMPRESS_MIN_BYTES - 1));
        assertNull(bytes.frame(), "sub-threshold columns ship raw even when compressible");
    }

    @Test
    void zeroSectionClearBodyStaysRaw() {
        // The §0.8 pin at holder level: the 1-byte authoritative clear must never frame —
        // the client's clear detection reads the leading varint without a decompress.
        var bytes = ColumnBytes.ofRaw(codec, new byte[]{0});
        assertNull(bytes.frame());
        assertEquals(1, bytes.rawSize());
    }

    @Test
    void compressibleAboveThresholdFramesAndRoundTrips() {
        byte[] raw = compressible(4096);
        var bytes = ColumnBytes.ofRaw(codec, raw);
        byte[] frame = bytes.frame();
        assertNotNull(frame);
        assertTrue(frame.length < raw.length, "the holder only ships shrinking frames");
        assertArrayEquals(raw, codec.decompress(frame, raw.length));
        assertEquals(raw.length, bytes.rawSize());
    }

    @Test
    void nonShrinkingFrameRefusedAndRefusalMemoized() {
        // Random bytes are incompressible: zstd's frame overhead makes frame >= raw, and
        // shipping raw is then strictly better bytes AND spares the client a decompress.
        byte[] raw = incompressible(600);
        var bytes = ColumnBytes.ofRaw(codec, raw);
        assertNull(bytes.frame());
        assertNull(bytes.frame(), "the refusal is memoized — no second compress attempt");
        assertSame(raw, bytes.raw(), "raw stays the original array");
    }

    @Test
    void frameMemoizedAcrossRecipients() {
        var bytes = ColumnBytes.ofRaw(codec, compressible(8192));
        byte[] first = bytes.frame();
        assertSame(first, bytes.frame(),
                "the dedup fan-out must compress once — identical instance per recipient");
        assertTrue(bytes.frameMaterialized());
    }

    @Test
    void nullCodecNeverFrames() {
        // The server-side latch (plan §0.11): config off or natives unavailable attaches
        // no codec, and every column ships raw without touching zstd.
        var bytes = ColumnBytes.ofRaw(null, compressible(100_000));
        assertNull(bytes.frame());
        assertNull(bytes.frame());
    }

    @Test
    void ofFrameDecompressesLazilyAndMemoizes() {
        byte[] raw = compressible(10_000);
        byte[] frame = codec.compress(raw);
        var bytes = ColumnBytes.ofFrame(codec, frame, raw.length);
        assertFalse(bytes.rawMaterialized(), "store frames stay compressed until needed");
        assertSame(frame, bytes.frame(), "the stored frame ships verbatim");
        byte[] first = bytes.raw();
        assertArrayEquals(raw, first);
        assertSame(first, bytes.raw(), "one decompress per mixed fan-out, memoized");
    }

    @Test
    void ofFrameWrongSizeThrows() {
        byte[] raw = compressible(1000);
        byte[] frame = codec.compress(raw);
        var bytes = ColumnBytes.ofFrame(codec, frame, raw.length + 1);
        assertThrows(Exception.class, bytes::raw,
                "a frame that does not decompress to the recorded usize is row-poison class");
    }
}
