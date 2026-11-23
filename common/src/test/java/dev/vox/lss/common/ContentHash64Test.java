package dev.vox.lss.common;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContentHash64Test {

    private static long ascii(String s) {
        return ContentHash64.hash(s.getBytes(StandardCharsets.US_ASCII));
    }

    /** XXH64, seed 0. Each value was also produced by lz4-java 1.8.0's XXH64. */
    @Test
    void publishedValues() {
        assertEquals(0xEF46DB3751D8E999L, ascii(""));
        assertEquals(0xD24EC4F1A98C6E5BL, ascii("a"));
        assertEquals(0x44BC2CF5AD770999L, ascii("abc"));
        assertEquals(0xFBCEA83C8A378BF1L, ascii("Nobody inspects the spammish repetition"));
        assertEquals(0x0B242D361FDA71BCL, ascii("The quick brown fox jumps over the lazy dog"));
    }

    /** The generated buffer of the reference implementation's self-test: lengths that end
     *  in the byte tail (1), the four-byte step (14) and the 32-byte stripes (101). */
    @Test
    void referenceSelfTestBuffer() {
        byte[] buffer = new byte[101];
        int gen = (int) 2654435761L;
        for (int i = 0; i < buffer.length; i++) {
            buffer[i] = (byte) (gen >>> 24);
            gen *= gen;
        }
        assertEquals(0xEF46DB3751D8E999L, ContentHash64.hash(buffer, 0, 0));
        assertEquals(0x4FCE394CC88952D8L, ContentHash64.hash(buffer, 0, 1));
        assertEquals(0xCFFA8DB881BC3A3DL, ContentHash64.hash(buffer, 0, 14));
        assertEquals(0x0EAB543384F878ADL, ContentHash64.hash(buffer, 0, 101));
    }

    @Test
    void aRangeHashesLikeACopyOfIt() {
        var random = new Random(7);
        for (int length = 0; length <= 130; length++) {
            byte[] padded = new byte[length + 9];
            random.nextBytes(padded);
            for (int offset = 0; offset <= 9; offset += 3) {
                byte[] copy = java.util.Arrays.copyOfRange(padded, offset, offset + length);
                assertEquals(ContentHash64.hash(copy), ContentHash64.hash(padded, offset, length),
                        "length " + length + " offset " + offset);
            }
        }
    }

    /** What the change detector relies on: one changed bit anywhere changes the hash. */
    @Test
    void everySingleBitFlipChangesTheHash() {
        var random = new Random(11);
        for (int length : new int[]{1, 7, 8, 31, 32, 33, 64, 257}) {
            byte[] data = new byte[length];
            random.nextBytes(data);
            long base = ContentHash64.hash(data);
            for (int bit = 0; bit < length * 8; bit++) {
                data[bit >> 3] ^= (byte) (1 << (bit & 7));
                assertNotEquals(base, ContentHash64.hash(data), "length " + length + " bit " + bit);
                data[bit >> 3] ^= (byte) (1 << (bit & 7));
            }
        }
    }

    /** A truncated or extended input is a different input, zeros included. */
    @Test
    void lengthIsPartOfTheHash() {
        var seen = new HashSet<Long>();
        for (int length = 0; length < 200; length++) {
            assertTrue(seen.add(ContentHash64.hash(new byte[length])), "zeros of length " + length);
        }
    }

    @Test
    void aRangeOutsideTheArrayIsRefused() {
        byte[] data = new byte[16];
        assertThrows(IndexOutOfBoundsException.class, () -> ContentHash64.hash(data, 8, 9));
        assertThrows(IndexOutOfBoundsException.class, () -> ContentHash64.hash(data, -1, 4));
        assertThrows(IndexOutOfBoundsException.class, () -> ContentHash64.hash(data, 0, -1));
    }
}
