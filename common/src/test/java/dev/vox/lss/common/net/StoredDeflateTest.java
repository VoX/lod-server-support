package dev.vox.lss.common.net;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.Random;
import java.util.zip.Adler32;
import java.util.zip.Inflater;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StoredDeflateTest {

    private static byte[] random(int length, long seed) {
        byte[] data = new byte[length];
        new Random(seed).nextBytes(data);
        return data;
    }

    private static byte[] stream(byte[] data, boolean direct) {
        ByteBuffer source = direct ? ByteBuffer.allocateDirect(data.length) : ByteBuffer.allocate(data.length);
        source.put(data).flip();
        int size = StoredDeflate.streamLength(data.length);
        ByteBuffer target = direct ? ByteBuffer.allocateDirect(size) : ByteBuffer.allocate(size);
        StoredDeflate.write(source, target);
        assertEquals(0, source.remaining(), "the source is consumed");
        assertEquals(0, target.remaining(), "streamLength is exact");
        target.flip();
        byte[] out = new byte[size];
        target.get(out);
        return out;
    }

    /** The JDK's inflater (zlib) is the judge: it restores the input and reports the end
     *  of the stream, so the header, every block header and the checksum are valid. */
    @Test
    void anInflaterRestoresTheInput() throws Exception {
        for (int length : new int[]{0, 1, 2, 255, 256, 5868, 65_534, 65_535, 65_536, 131_070, 131_071, 200_000}) {
            for (boolean direct : new boolean[]{false, true}) {
                byte[] data = random(length, length);
                byte[] stream = stream(data, direct);
                var inflater = new Inflater();
                try {
                    inflater.setInput(stream);
                    byte[] restored = new byte[length + 1];
                    int n = 0;
                    while (!inflater.finished()) {
                        int got = inflater.inflate(restored, n, restored.length - n);
                        assertTrue(got > 0 || inflater.finished(), "the inflater stalled at " + n + " of " + length);
                        n += got;
                    }
                    assertEquals(length, n, "length " + length);
                    assertArrayEquals(data, java.util.Arrays.copyOf(restored, length), "length " + length);
                    assertEquals(stream.length, inflater.getTotalIn(), "nothing follows the stream");
                    var adler = new Adler32();
                    adler.update(data);
                    assertEquals(adler.getValue(), inflater.getAdler() & 0xFFFFFFFFL);
                } finally {
                    inflater.end();
                }
            }
        }
    }

    /** What the framing costs: 2 bytes of header, 5 per block of 65,535, 4 of checksum. */
    @Test
    void overheadIsElevenBytesForATypicalColumn() {
        assertEquals(11, StoredDeflate.streamLength(0));
        assertEquals(5868 + 11, StoredDeflate.streamLength(5868));
        assertEquals(65_535 + 11, StoredDeflate.streamLength(65_535));
        assertEquals(65_536 + 16, StoredDeflate.streamLength(65_536));
        assertEquals(2_097_152 + 2 + 4 + 5 * 33, StoredDeflate.streamLength(2_097_152));
    }

    /** RFC 1950: the two header bytes, read as a big-endian number, divide by 31. */
    @Test
    void theHeaderIsAValidZlibHeader() {
        byte[] stream = stream(new byte[]{1, 2, 3}, false);
        assertEquals(0x78, stream[0] & 0xFF);
        assertEquals(0, (((stream[0] & 0xFF) << 8) | (stream[1] & 0xFF)) % 31);
        assertEquals(0, stream[1] & 0x20, "no preset dictionary");
        assertEquals(1, stream[2], "one final stored block");
    }

    @Test
    void aSourceSliceIsWrittenFromItsPosition() throws Exception {
        byte[] data = random(1000, 3);
        ByteBuffer source = ByteBuffer.wrap(data, 100, 500);
        ByteBuffer target = ByteBuffer.allocate(8 + StoredDeflate.streamLength(500));
        target.position(8);
        StoredDeflate.write(source, target);
        assertEquals(600, source.position());
        var inflater = new Inflater();
        try {
            inflater.setInput(target.array(), 8, target.position() - 8);
            byte[] restored = new byte[500];
            assertEquals(500, inflater.inflate(restored));
            assertArrayEquals(java.util.Arrays.copyOfRange(data, 100, 600), restored);
        } finally {
            inflater.end();
        }
    }

    @Test
    void aTargetThatIsTooSmallIsRefusedBeforeAnythingIsWritten() {
        ByteBuffer source = ByteBuffer.wrap(new byte[100]);
        ByteBuffer target = ByteBuffer.allocate(StoredDeflate.streamLength(100) - 1);
        assertThrows(IllegalArgumentException.class, () -> StoredDeflate.write(source, target));
        assertEquals(0, target.position());
        assertEquals(0, source.position());
    }
}
