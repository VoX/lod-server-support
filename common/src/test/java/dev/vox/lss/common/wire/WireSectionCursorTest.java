package dev.vox.lss.common.wire;

import dev.vox.lss.common.wire.WireSectionCursor.Layout;
import dev.vox.lss.common.wire.WireSectionCursor.WireColumn;
import dev.vox.lss.common.wire.WireSectionCursor.WireContainer;
import dev.vox.lss.common.wire.WireSectionCursor.WireSection;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The layout walker: parse/re-emit byte identity on hand-built fixtures of BOTH
 * layouts (the real-emitter cross-check against the committed nbt-corpus lives in
 * {@code WireSectionCursorCorpusTest}, where the corpus is reachable), skip/scan
 * correctness, and hostile-input containment — every malformed shape must surface as
 * {@link WireFormatException}, never an array error or a wire-claimed allocation.
 */
class WireSectionCursorTest {

    // ---- fixture builders ------------------------------------------------------

    private static long[] filledData(int entries, int bits, int seed) {
        int valuesPerLong = 64 / bits;
        var data = new long[(entries + valuesPerLong - 1) / valuesPerLong];
        var rng = new Random(seed);
        for (int i = 0; i < data.length; i++) {
            data[i] = rng.nextLong();
        }
        // Zero the tail beyond the last real entry so pack/unpack round-trips exactly.
        int used = entries % valuesPerLong;
        if (used != 0) {
            long mask = (1L << ((long) used * bits)) - 1;
            data[data.length - 1] &= mask;
        }
        return data;
    }

    private static byte[] light(int fill) {
        var b = new byte[2048];
        java.util.Arrays.fill(b, (byte) fill);
        return b;
    }

    /** Indexed blocks (bits 4), single-value biomes, both light layers. */
    private static WireSection typicalSection(int y) {
        var blocks = new WireContainer(4, new int[] { 0, 9, 130 }, filledData(4096, 4, y));
        var biomes = new WireContainer(0, new int[] { 2 }, new long[0]);
        return new WireSection(y, 271, 3, blocks, biomes, light(0x12), light(0xFF));
    }

    /** DIRECT blocks (bits 15) + DIRECT biomes (bits 4), no light. */
    private static WireSection directSection(int y) {
        var blocks = new WireContainer(15, null, filledData(4096, 15, y));
        var biomes = new WireContainer(4, null, filledData(64, 4, y + 1));
        return new WireSection(y, 4096, 0, blocks, biomes, null, null);
    }

    // ---- parse / emit ----------------------------------------------------------

    @Test
    void nativeParseEmitByteIdentity() {
        var column = new WireColumn(List.of(), List.of(typicalSection(-4), directSection(7)));
        byte[] bytes = WireSectionCursor.emit(column, Layout.NATIVE);
        var reparsed = WireSectionCursor.parse(bytes, Layout.NATIVE);
        assertArrayEquals(bytes, WireSectionCursor.emit(reparsed, Layout.NATIVE));
        assertEquals(2, reparsed.sections().size());
        assertEquals(-4, reparsed.sections().get(0).sectionY());
        assertTrue(reparsed.sections().get(1).blocks().isDirect());
        assertNull(reparsed.sections().get(1).blockLight());
    }

    @Test
    void v20ParseEmitByteIdentity() {
        var blocks = new WireContainer(4, new int[] { 0, 1, 2 }, filledData(4096, 4, 5));
        var biomes = new WireContainer(0, new int[] { 3 }, new long[0]);
        var wide = new WireContainer(12, new int[] { 0, 1 }, filledData(4096, 12, 6));
        var column = new WireColumn(
                List.of("minecraft:stone", "minecraft:dirt",
                        "minecraft:oak_stairs[facing=north,half=top,waterlogged=false]",
                        "minecraft:plains"),
                List.of(new WireSection(-4, 100, 0, blocks, biomes, light(1), null),
                        new WireSection(0, 4096, 12, wide, biomes, null, light(15))));
        byte[] bytes = WireSectionCursor.emit(column, Layout.V20);
        var reparsed = WireSectionCursor.parse(bytes, Layout.V20);
        assertEquals(column.dictionary(), reparsed.dictionary());
        assertArrayEquals(bytes, WireSectionCursor.emit(reparsed, Layout.V20));
    }

    @Test
    void clearColumnIsTheEmptyDictionaryEmptySectionFrameInBothLayouts() {
        var clear = new WireColumn(List.of(), List.of());
        assertArrayEquals(new byte[] { 0 }, WireSectionCursor.emit(clear, Layout.NATIVE));
        // v20: dictCount 0 + sectionCount 0 — the ghost-clear shape (§2.1).
        assertArrayEquals(new byte[] { 0, 0 }, WireSectionCursor.emit(clear, Layout.V20));
        assertEquals(0, WireSectionCursor.parse(new byte[] { 0, 0 }, Layout.V20).sections().size());
    }

    @Test
    void scanReturnsConsumedLengthAndHonorsOffset() {
        byte[] body = WireSectionCursor.emit(
                new WireColumn(List.of(), List.of(typicalSection(3))), Layout.NATIVE);
        assertEquals(body.length, WireSectionCursor.scan(body, 0, Layout.NATIVE));
        byte[] shifted = new byte[body.length + 5];
        System.arraycopy(body, 0, shifted, 5, body.length);
        assertEquals(body.length, WireSectionCursor.scan(shifted, 5, Layout.NATIVE));
    }

    @Test
    void trailingBytesAfterTheSectionArrayAreRejectedByParse() {
        byte[] body = WireSectionCursor.emit(
                new WireColumn(List.of(), List.of(typicalSection(0))), Layout.NATIVE);
        byte[] padded = java.util.Arrays.copyOf(body, body.length + 1);
        assertThrows(WireFormatException.class,
                () -> WireSectionCursor.parse(padded, Layout.NATIVE));
        // scan tolerates trailing content by design — it reports what it consumed.
        assertEquals(body.length, WireSectionCursor.scan(padded, 0, Layout.NATIVE));
    }

    // ---- hostile-input containment --------------------------------------------

    /** Every strict prefix of a valid body must throw WireFormatException — nothing else. */
    @Test
    void truncationAtEveryByteIsContained() {
        for (Layout layout : Layout.values()) {
            byte[] body = layout == Layout.NATIVE
                    ? WireSectionCursor.emit(new WireColumn(List.of(),
                            List.of(typicalSection(-1), directSection(2))), Layout.NATIVE)
                    : WireSectionCursor.emit(new WireColumn(List.of("minecraft:stone"),
                            List.of(new WireSection(0, 1, 0,
                                    new WireContainer(0, new int[] { 0 }, new long[0]),
                                    new WireContainer(0, new int[] { 0 }, new long[0]),
                                    light(2), null))), Layout.V20);
            for (int len = 0; len < body.length; len++) {
                byte[] prefix = java.util.Arrays.copyOf(body, len);
                try {
                    WireSectionCursor.parse(prefix, layout);
                    fail(layout + " prefix of " + len + " bytes parsed without error");
                } catch (WireFormatException expected) {
                    // the pinned failure mode
                } catch (RuntimeException other) {
                    fail(layout + " prefix of " + len + " bytes threw " + other, other);
                }
            }
        }
    }

    @Test
    void negativeAndOversizedCountsAreContained() {
        // sectionCount = -1 (five-byte VarInt).
        byte[] negativeCount = { (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, 0x0F };
        assertThrows(WireFormatException.class,
                () -> WireSectionCursor.parse(negativeCount, Layout.NATIVE));
        // dictCount claims 2^28 with 2 bytes remaining — must throw BEFORE allocating.
        byte[] hugeDict = { (byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, 0x01, 0, 0 };
        assertThrows(WireFormatException.class,
                () -> WireSectionCursor.parse(hugeDict, Layout.V20));
        // paletteCount above the container's entry count.
        var out = new WireBytes.Writer(64);
        out.writeVarInt(1).writeByte(0).writeShort(0).writeShort(0)
                .writeByte(4).writeVarInt(4097);
        assertThrows(WireFormatException.class,
                () -> WireSectionCursor.parse(out.toByteArray(), Layout.NATIVE));
        // VarInt wider than 5 bytes.
        byte[] wideVarInt = { (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, 0x01 };
        assertThrows(WireFormatException.class,
                () -> WireSectionCursor.parse(wideVarInt, Layout.NATIVE));
    }

    @Test
    void v20WidthCeilingsAndClearInvariantAreEnforcedOnParse() {
        // Block container claiming width 13 (> v20 max 12).
        var out = new WireBytes.Writer(64);
        out.writeVarInt(1).writeUtf("minecraft:stone")
                .writeVarInt(1).writeByte(0).writeShort(0).writeShort(0)
                .writeByte(13);
        assertThrows(WireFormatException.class,
                () -> WireSectionCursor.parse(out.toByteArray(), Layout.V20));
        // Dictionary content with zero sections — the leak isClearColumn must never see.
        var leak = new WireBytes.Writer(32);
        leak.writeVarInt(1).writeUtf("minecraft:stone").writeVarInt(0);
        assertThrows(WireFormatException.class,
                () -> WireSectionCursor.parse(leak.toByteArray(), Layout.V20));
        // Sections with an EMPTY dictionary — indices with nothing to reference.
        var empty = new WireBytes.Writer(32);
        empty.writeVarInt(0).writeVarInt(1);
        assertThrows(WireFormatException.class,
                () -> WireSectionCursor.parse(empty.toByteArray(), Layout.V20));
    }

    /** MAJOR 1: v20 palette values are dictionary references — the cursor owns the
     *  range check on BOTH directions, so no consumer can index out of the dict. */
    @Test
    void v20PaletteIndicesAreRangeCheckedAgainstTheDictionaryBothDirections() {
        // Parse: single-value index == dictSize (one past the end).
        var out = new WireBytes.Writer(64);
        out.writeVarInt(1).writeUtf("minecraft:stone")
                .writeVarInt(1).writeByte(0).writeShort(0).writeShort(0)
                .writeByte(0).writeVarInt(1);
        assertThrows(WireFormatException.class,
                () -> WireSectionCursor.parse(out.toByteArray(), Layout.V20));
        // Parse: negative palette value (5-byte VarInt -1).
        var neg = new WireBytes.Writer(64);
        neg.writeVarInt(1).writeUtf("minecraft:stone")
                .writeVarInt(1).writeByte(0).writeShort(0).writeShort(0)
                .writeByte(0).writeVarInt(-1);
        assertThrows(WireFormatException.class,
                () -> WireSectionCursor.parse(neg.toByteArray(), Layout.V20));
        // Emit: an index outside the dictionary must be refused, not shipped.
        var single = new WireContainer(0, new int[] { 5 }, new long[0]);
        var ok = new WireContainer(0, new int[] { 0 }, new long[0]);
        assertThrows(WireFormatException.class, () -> WireSectionCursor.emit(
                new WireColumn(List.of("minecraft:stone"),
                        List.of(new WireSection(0, 1, 0, single, ok, null, null))), Layout.V20));
        // Emit: negative native palette value refused too.
        var negNative = new WireContainer(0, new int[] { -1 }, new long[0]);
        assertThrows(WireFormatException.class, () -> WireSectionCursor.emit(
                new WireColumn(List.of(),
                        List.of(new WireSection(0, 1, 0, negNative, ok, null, null))), Layout.NATIVE));
    }

    /** MAJOR 4/5: block containers at bits 1-3 have no vanilla shape (native) and
     *  violate the §2.1 floor (v20) — illegal on BOTH parse and emit, both layouts. */
    @Test
    void blockWidths1To3AreRejectedOnBothLayoutsAndBothDirections() {
        for (int bits = 1; bits <= 3; bits++) {
            var nat = new WireBytes.Writer(64);
            nat.writeVarInt(1).writeByte(0).writeShort(0).writeShort(0).writeByte(bits);
            byte[] natBytes = nat.toByteArray();
            assertThrows(WireFormatException.class,
                    () -> WireSectionCursor.parse(natBytes, Layout.NATIVE), "native parse bits " + bits);
            var v20 = new WireBytes.Writer(64);
            v20.writeVarInt(1).writeUtf("minecraft:stone")
                    .writeVarInt(1).writeByte(0).writeShort(0).writeShort(0).writeByte(bits);
            byte[] v20Bytes = v20.toByteArray();
            assertThrows(WireFormatException.class,
                    () -> WireSectionCursor.parse(v20Bytes, Layout.V20), "v20 parse bits " + bits);

            var badBlocks = new WireContainer(bits, new int[] { 0, 1 },
                    new long[(4096 + (64 / bits) - 1) / (64 / bits)]);
            var okBiome = new WireContainer(0, new int[] { 0 }, new long[0]);
            assertThrows(WireFormatException.class, () -> WireSectionCursor.emit(
                    new WireColumn(List.of(), List.of(
                            new WireSection(0, 1, 0, badBlocks, okBiome, null, null))), Layout.NATIVE),
                    "native emit bits " + bits);
        }
        // Biomes keep their 1-3-bit linear shapes — bits 2 biomes must stay legal.
        var biome2 = new WireContainer(2, new int[] { 0, 1, 2 }, new long[2]);
        var single = new WireContainer(0, new int[] { 0 }, new long[0]);
        byte[] ok = WireSectionCursor.emit(new WireColumn(List.of(),
                List.of(new WireSection(0, 1, 0, single, biome2, null, null))), Layout.NATIVE);
        assertEquals(2, WireSectionCursor.parse(ok, Layout.NATIVE).sections().get(0).biomes().bits());
    }

    /** Emit refuses out-of-domain scalar fields instead of silently truncating them. */
    @Test
    void emitRejectsOutOfRangeSectionYAndCounts() {
        var single = new WireContainer(0, new int[] { 0 }, new long[0]);
        assertThrows(WireFormatException.class, () -> WireSectionCursor.emit(
                new WireColumn(List.of(), List.of(
                        new WireSection(300, 1, 0, single, single, null, null))), Layout.NATIVE));
        assertThrows(WireFormatException.class, () -> WireSectionCursor.emit(
                new WireColumn(List.of(), List.of(
                        new WireSection(0, 70000, 0, single, single, null, null))), Layout.NATIVE));
    }

    @Test
    void emitRefusesMalformedColumns() {
        var direct = new WireContainer(15, null, filledData(4096, 15, 1));
        var single = new WireContainer(0, new int[] { 0 }, new long[0]);
        var directSection = new WireSection(0, 1, 0, direct, single, null, null);
        assertThrows(WireFormatException.class, () -> WireSectionCursor.emit(
                new WireColumn(List.of("minecraft:stone"), List.of(directSection)), Layout.V20));
        assertThrows(WireFormatException.class, () -> WireSectionCursor.emit(
                new WireColumn(List.of("minecraft:stone"), List.of()), Layout.V20));
        assertThrows(WireFormatException.class, () -> WireSectionCursor.emit(
                new WireColumn(List.of("minecraft:stone"), List.of(directSection)), Layout.NATIVE));
        var badData = new WireSection(0, 1, 0,
                new WireContainer(4, new int[] { 1 }, new long[7]), single, null, null);
        assertThrows(WireFormatException.class, () -> WireSectionCursor.emit(
                new WireColumn(List.of(), List.of(badData)), Layout.NATIVE));
        var badLight = new WireSection(0, 1, 0, single, single, new byte[100], null);
        assertThrows(WireFormatException.class, () -> WireSectionCursor.emit(
                new WireColumn(List.of(), List.of(badLight)), Layout.NATIVE));
    }

    // ---- packed-array helpers --------------------------------------------------

    @Test
    void packUnpackRoundTripsAcrossWidths() {
        var rng = new Random(42);
        for (int bits = 1; bits <= 15; bits++) {
            for (int entries : new int[] { 64, 4096 }) {
                var values = new int[entries];
                for (int i = 0; i < entries; i++) {
                    values[i] = rng.nextInt(1 << bits);
                }
                assertArrayEquals(values,
                        WireSectionCursor.unpack(WireSectionCursor.pack(values, bits), bits, entries),
                        "width " + bits + " x " + entries);
            }
        }
    }

    @Test
    void packRejectsValuesThatDoNotFit() {
        assertThrows(WireFormatException.class,
                () -> WireSectionCursor.pack(new int[] { 16 }, 4));
        assertThrows(WireFormatException.class,
                () -> WireSectionCursor.pack(new int[] { -1 }, 4));
        assertThrows(WireFormatException.class,
                () -> WireSectionCursor.unpack(new long[3], 4, 4096));
    }
}
