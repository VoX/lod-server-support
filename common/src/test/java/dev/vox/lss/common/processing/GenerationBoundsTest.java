package dev.vox.lss.common.processing;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenerationBoundsTest {
    private static final String OW = "minecraft:overworld";

    /** The rule, stated independently: chunk c covers blocks [16c, 16c + 16) and the border
     *  covers [centre - size/2, centre + size/2). They overlap or they do not. */
    private static boolean overlaps(int chunk, double center, double size) {
        double min = center - size / 2, max = center + size / 2;
        return chunk * 16.0 < max && chunk * 16.0 + 16.0 > min;
    }

    @Test
    void aBorderOnChunkBoundariesCoversExactlyItsChunks() {
        // 512 blocks wide around the origin: blocks -256..255, chunks -16..15
        assertArrayEquals(new int[]{-16, 15, -16, 15}, GenerationBounds.chunkRectangle(0, 0, 512));
        // the vanilla default: 59,999,968 blocks wide
        assertArrayEquals(new int[]{-1_874_999, 1_874_998, -1_874_999, 1_874_998},
                GenerationBounds.chunkRectangle(0, 0, 5.9999968E7));
    }

    @Test
    void aChunkTheBorderCutsThroughIsInside() {
        // centre 0.5, 512 wide: blocks -255.5 .. 256.5, so chunk 16 (blocks 256..271) has
        // half a block inside and chunk -16 (blocks -256..-241) is cut at -255.5
        assertArrayEquals(new int[]{-16, 16, -16, 16}, GenerationBounds.chunkRectangle(0.5, 0.5, 512));
        // a border smaller than a chunk still lets its own chunk generate
        assertArrayEquals(new int[]{6, 6, -7, -7}, GenerationBounds.chunkRectangle(100, -100, 1));
        // and one that straddles a chunk corner covers the four chunks around it
        assertArrayEquals(new int[]{-1, 0, -1, 0}, GenerationBounds.chunkRectangle(0, 0, 2));
    }

    /** Every chunk near the edge, for many borders, against the independent rule. */
    @Test
    void theRectangleIsTheOverlapRuleForEveryChunk() {
        double[] centers = {0, 0.5, -0.5, 7.25, -1000.75, 123456.5, 16, -16, 8};
        double[] sizes = {1, 2, 15.5, 16, 17, 31.9, 32, 100, 511, 512, 513, 1000.5, 30000};
        for (double center : centers) {
            for (double size : sizes) {
                int[] r = GenerationBounds.chunkRectangle(center, -center, size);
                var bounds = GenerationBounds.of(Map.of(OW, r));
                int lo = (int) Math.floor((center - size / 2) / 16) - 3;
                int hi = (int) Math.ceil((center + size / 2) / 16) + 3;
                for (int cx = lo; cx <= hi; cx++) {
                    assertEquals(overlaps(cx, center, size), cx >= r[0] && cx <= r[1],
                            "x: centre " + center + " size " + size + " chunk " + cx);
                    int cz = -cx;
                    assertEquals(overlaps(cx, center, size) && overlaps(cz, -center, size),
                            bounds.allows(OW, cx, cz), "centre " + center + " size " + size + " chunk " + cx + "," + cz);
                }
            }
        }
    }

    @Test
    void aDimensionThatWasNeverPublishedHasNoBound() {
        var bounds = GenerationBounds.of(Map.of(OW, GenerationBounds.chunkRectangle(0, 0, 512)));
        assertFalse(bounds.allows(OW, 16, 0));
        assertTrue(bounds.allows("minecraft:the_nether", 1_000_000, -1_000_000));
        assertTrue(GenerationBounds.NONE.allows(OW, Integer.MAX_VALUE, Integer.MIN_VALUE));
        assertTrue(GenerationBounds.NONE.isUnbounded());
        assertSame(GenerationBounds.NONE, GenerationBounds.of(Map.of()));
    }

    /** The publisher compares to decide whether to publish, and the published object must
     *  not change under the reader when the publisher reuses its arrays. */
    @Test
    void boundsCompareByValueAndCopyWhatTheyAreGiven() {
        int[] rectangle = GenerationBounds.chunkRectangle(0, 0, 512);
        var a = GenerationBounds.of(Map.of(OW, rectangle));
        var b = GenerationBounds.of(Map.of(OW, GenerationBounds.chunkRectangle(0, 0, 512)));
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, GenerationBounds.of(Map.of(OW, GenerationBounds.chunkRectangle(0, 0, 528))));
        assertNotEquals(a, GenerationBounds.of(Map.of("minecraft:the_end", rectangle)));
        assertNotEquals(a, GenerationBounds.NONE);
        rectangle[1] = 1000;
        assertFalse(a.allows(OW, 16, 0), "a later change to the caller's array must not move the bound");
        assertThrows(IllegalArgumentException.class, () -> GenerationBounds.of(Map.of(OW, new int[3])));
    }

    @Test
    void absurdSizesDoNotOverflow() {
        int[] huge = GenerationBounds.chunkRectangle(0, 0, 1e300);
        assertEquals(Integer.MIN_VALUE, huge[0]);
        assertEquals(Integer.MAX_VALUE, huge[1]);
        assertTrue(GenerationBounds.of(Map.of(OW, huge)).allows(OW, 2_000_000_000, -2_000_000_000));
        int[] negative = GenerationBounds.chunkRectangle(0, 0, -5);
        assertArrayEquals(new int[]{0, -1, 0, -1}, negative, "a negative size is an empty border, not a huge one");
        assertFalse(GenerationBounds.of(Map.of(OW, negative)).allows(OW, 0, 0));
    }
}
