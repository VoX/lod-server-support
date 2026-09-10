package dev.vox.lss.common.region;

import dev.vox.lss.common.wire.WireBytes;
import dev.vox.lss.common.wire.WireFormatException;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Hostile-decode twins of the FarPlayerWire suite for the region-summary codec
 * (region-summary-sync-plan.md §4): round-trip fidelity over the full stamp domain
 * (epoch seconds + both sentinels, negative tile coordinates), and every malformed
 * shape — wrong version, out-of-range radius, oversized dimension, truncation,
 * trailing bytes, below-domain stamps, overlong varlongs — throws
 * {@link WireFormatException} with bounded allocation.
 */
class RegionSummaryWireTest {

    private static final long NOW = System.currentTimeMillis() / 1000L;

    @Test
    void requestRoundTripsIncludingNegativeCenters() {
        var req = new RegionSummaryWire.Request("minecraft:the_end", -37, 1024, 17);
        var decoded = RegionSummaryWire.decodeRequest(RegionSummaryWire.encodeRequest(req));
        assertEquals(req, decoded);
    }

    @Test
    void summaryRoundTripsOverTheFullStampDomain() {
        int radius = 1;
        long[] stamps = {
                RegionSummaryWire.STAMP_NO_REGION, NOW - 5000, RegionSummaryWire.STAMP_NEVER_CLEAN,
                NOW, RegionSummaryWire.STAMP_NEVER_CLEAN, RegionSummaryWire.STAMP_NO_REGION,
                NOW - 1, NOW - 86_400, NOW - 2,
        };
        var s = new RegionSummaryWire.Summary("minecraft:overworld", -2, 3, radius, stamps);
        var decoded = RegionSummaryWire.decodeSummary(RegionSummaryWire.encodeSummary(s));
        assertEquals(s.dimension(), decoded.dimension());
        assertEquals(s.centerTileX(), decoded.centerTileX());
        assertEquals(s.centerTileZ(), decoded.centerTileZ());
        assertEquals(s.tileRadius(), decoded.tileRadius());
        assertArrayEquals(stamps, decoded.stampSeconds());
    }

    @Test
    void maxRadiusSummaryRoundTrips() {
        int radius = RegionSummaryWire.MAX_SUMMARY_TILE_RADIUS;
        int side = 2 * radius + 1;
        long[] stamps = new long[side * side];
        Arrays.fill(stamps, NOW - 100);
        stamps[0] = RegionSummaryWire.STAMP_NEVER_CLEAN;
        stamps[stamps.length - 1] = RegionSummaryWire.STAMP_NO_REGION;
        var s = new RegionSummaryWire.Summary("minecraft:overworld", 0, 0, radius, stamps);
        byte[] wire = RegionSummaryWire.encodeSummary(s);
        // Delta coding keeps the flat-stamp bulk at ~1 byte/tile.
        assertTrue(wire.length < stamps.length * 3,
                "delta coding regressed: " + wire.length + " bytes for " + stamps.length + " tiles");
        assertArrayEquals(stamps, RegionSummaryWire.decodeSummary(wire).stampSeconds());
    }

    @Test
    void wrongVersionThrows() {
        byte[] req = RegionSummaryWire.encodeRequest(
                new RegionSummaryWire.Request("minecraft:overworld", 0, 0, 1));
        req[0] = 2;
        assertThrows(WireFormatException.class, () -> RegionSummaryWire.decodeRequest(req));
        byte[] sum = RegionSummaryWire.encodeSummary(new RegionSummaryWire.Summary(
                "minecraft:overworld", 0, 0, 0, new long[]{NOW}));
        sum[0] = 0;
        assertThrows(WireFormatException.class, () -> RegionSummaryWire.decodeSummary(sum));
    }

    @Test
    void hostileRadiusThrowsBeforeAllocation() {
        // Craft a request frame with radius far beyond the cap (also the int-overflow
        // shape: (2r+1)^2 wraps int at r ~ 23170 — the guard must fire first).
        var w = new WireBytes.Writer(32);
        w.writeByte(RegionSummaryWire.VERSION);
        w.writeUtf("minecraft:overworld");
        RegionSummaryWire.writeZigVarLong(w, 0);
        RegionSummaryWire.writeZigVarLong(w, 0);
        w.writeVarInt(RegionSummaryWire.MAX_SUMMARY_TILE_RADIUS + 1);
        assertThrows(WireFormatException.class,
                () -> RegionSummaryWire.decodeRequest(w.toByteArray()));

        var w2 = new WireBytes.Writer(32);
        w2.writeByte(RegionSummaryWire.VERSION);
        w2.writeUtf("minecraft:overworld");
        RegionSummaryWire.writeZigVarLong(w2, 0);
        RegionSummaryWire.writeZigVarLong(w2, 0);
        w2.writeVarInt(46_341); // sqrt(Integer.MAX_VALUE) — (2r+1)^2 wraps int here
        assertThrows(WireFormatException.class,
                () -> RegionSummaryWire.decodeSummary(w2.toByteArray()));

        var w3 = new WireBytes.Writer(32);
        w3.writeByte(RegionSummaryWire.VERSION);
        w3.writeUtf("minecraft:overworld");
        RegionSummaryWire.writeZigVarLong(w3, 0);
        RegionSummaryWire.writeZigVarLong(w3, 0);
        w3.writeVarInt(-1);
        assertThrows(WireFormatException.class,
                () -> RegionSummaryWire.decodeRequest(w3.toByteArray()));
    }

    @Test
    void oversizedDimensionThrows() {
        var w = new WireBytes.Writer(600);
        w.writeByte(RegionSummaryWire.VERSION);
        w.writeUtf("minecraft:" + "x".repeat(300));
        RegionSummaryWire.writeZigVarLong(w, 0);
        RegionSummaryWire.writeZigVarLong(w, 0);
        w.writeVarInt(1);
        assertThrows(WireFormatException.class,
                () -> RegionSummaryWire.decodeRequest(w.toByteArray()));
    }

    @Test
    void truncationAndTrailingBytesThrow() {
        byte[] good = RegionSummaryWire.encodeSummary(new RegionSummaryWire.Summary(
                "minecraft:overworld", 0, 0, 1, new long[]{
                        NOW, NOW, NOW, NOW, NOW, NOW, NOW, NOW, NOW}));
        byte[] truncated = Arrays.copyOf(good, good.length - 3);
        assertThrows(WireFormatException.class,
                () -> RegionSummaryWire.decodeSummary(truncated));
        byte[] trailing = Arrays.copyOf(good, good.length + 1);
        assertThrows(WireFormatException.class,
                () -> RegionSummaryWire.decodeSummary(trailing));

        byte[] goodReq = RegionSummaryWire.encodeRequest(
                new RegionSummaryWire.Request("minecraft:overworld", 0, 0, 1));
        byte[] trailingReq = Arrays.copyOf(goodReq, goodReq.length + 1);
        assertThrows(WireFormatException.class,
                () -> RegionSummaryWire.decodeRequest(trailingReq));
    }

    @Test
    void belowDomainStampThrows() {
        // A delta chain that dips below -1 (the wire NEVER_CLEAN) is malformed — the
        // domain guard must catch it on both encode and decode.
        assertThrows(WireFormatException.class, () -> RegionSummaryWire.encodeSummary(
                new RegionSummaryWire.Summary("minecraft:overworld", 0, 0, 0, new long[]{-2})));
        var w = new WireBytes.Writer(32);
        w.writeByte(RegionSummaryWire.VERSION);
        w.writeUtf("minecraft:overworld");
        RegionSummaryWire.writeZigVarLong(w, 0);
        RegionSummaryWire.writeZigVarLong(w, 0);
        w.writeVarInt(0);
        RegionSummaryWire.writeZigVarLong(w, -2);
        assertThrows(WireFormatException.class,
                () -> RegionSummaryWire.decodeSummary(w.toByteArray()));
    }

    @Test
    void overlongVarlongThrows() {
        var w = new WireBytes.Writer(32);
        w.writeByte(RegionSummaryWire.VERSION);
        w.writeUtf("minecraft:overworld");
        for (int i = 0; i < 11; i++) {
            w.writeByte(0xFF); // continuation forever — must stop at 64 bits, not run off
        }
        assertThrows(WireFormatException.class,
                () -> RegionSummaryWire.decodeRequest(w.toByteArray()));
    }

    @Test
    void mismatchedStampArrayLengthThrowsOnEncode() {
        assertThrows(WireFormatException.class, () -> RegionSummaryWire.encodeSummary(
                new RegionSummaryWire.Summary("minecraft:overworld", 0, 0, 1, new long[]{NOW})));
    }

    @Test
    void underAllocatedFrameThrowsBeforeStampAllocation() {
        // W-M3: a max-radius header with NO stamp bytes must be rejected by the
        // remaining-bytes floor (each stamp costs >= 1 byte) BEFORE the ~137 KB
        // stamp-array allocation — a byte-cheap hostile frame buys no memory.
        var w = new WireBytes.Writer(32);
        w.writeByte(RegionSummaryWire.VERSION);
        w.writeUtf("minecraft:overworld");
        RegionSummaryWire.writeZigVarLong(w, 0);
        RegionSummaryWire.writeZigVarLong(w, 0);
        w.writeVarInt(RegionSummaryWire.MAX_SUMMARY_TILE_RADIUS);
        var e = assertThrows(WireFormatException.class,
                () -> RegionSummaryWire.decodeSummary(w.toByteArray()));
        assertTrue(e.getMessage().contains("remaining"),
                "must fail the remaining-bytes floor, not a read underflow: " + e.getMessage());
    }

    @Test
    void recordConstructionValidatesWireBounds() {
        // Compact-ctor validation: a Request/Summary that EXISTS is wire-legal, so a
        // local bug cannot assemble a frame the twin then rejects.
        assertThrows(WireFormatException.class, () -> new RegionSummaryWire.Request(
                "minecraft:overworld", 0, 0, RegionSummaryWire.MAX_SUMMARY_TILE_RADIUS + 1));
        assertThrows(WireFormatException.class,
                () -> new RegionSummaryWire.Request(null, 0, 0, 1));
        assertThrows(WireFormatException.class, () -> new RegionSummaryWire.Request(
                "minecraft:" + "x".repeat(300), 0, 0, 1));
        assertThrows(WireFormatException.class, () -> new RegionSummaryWire.Summary(
                "minecraft:overworld", 0, 0, 0, null));
        assertThrows(WireFormatException.class, () -> new RegionSummaryWire.Summary(
                "minecraft:" + "x".repeat(300), 0, 0, 0, new long[]{NOW}));
    }

    @Test
    void hostileCenterOutsideTheDomainThrowsOnBothRecords() {
        // P2 client review MAJOR-1: an unbounded center made the client's window walk
        // wrap int (center=MAX_VALUE, r=0: tx++ overflows, the loop runs off the stamp
        // array) and aliased tileX<<2 onto REAL leaves. The domain bound in the compact
        // ctors kills the class on BOTH sides — a frame carrying such a center must
        // not decode into an applicable object at all.
        var w = new WireBytes.Writer(48);
        w.writeByte(RegionSummaryWire.VERSION);
        w.writeUtf("minecraft:overworld");
        RegionSummaryWire.writeZigVarLong(w, Integer.MAX_VALUE);
        RegionSummaryWire.writeZigVarLong(w, Integer.MAX_VALUE);
        w.writeVarInt(0);
        RegionSummaryWire.writeZigVarLong(w, 0); // one stamp for the radius-0 window
        assertThrows(WireFormatException.class,
                () -> RegionSummaryWire.decodeSummary(w.toByteArray()));
        assertThrows(WireFormatException.class, () -> new RegionSummaryWire.Request(
                "minecraft:overworld", Integer.MAX_VALUE, 0, 1));
        assertThrows(WireFormatException.class, () -> new RegionSummaryWire.Summary(
                "minecraft:overworld", 0, -RegionSummaryWire.MAX_SUMMARY_TILE_ABS - 1, 0,
                new long[]{0}));
        // The full legal domain still round-trips (the world border sits ~59k tiles out
        // — the bound must never reject an honest center).
        var legal = new RegionSummaryWire.Request("minecraft:overworld",
                RegionSummaryWire.MAX_SUMMARY_TILE_ABS,
                -RegionSummaryWire.MAX_SUMMARY_TILE_ABS, 1);
        assertEquals(legal, RegionSummaryWire.decodeRequest(RegionSummaryWire.encodeRequest(legal)));
    }

    @Test
    void craftedMaxLongStampAliasesToNeverCleanFailSafe() {
        // A crafted wire value of Long.MAX_VALUE (not the -1 sentinel form) decodes
        // EQUAL to STAMP_NEVER_CLEAN — the alias direction is fail-safe (the client
        // validates NOTHING there), and no honest stamp can reach it (margined epoch
        // seconds). Pinned so a future re-encode never flips the alias direction.
        var w = new WireBytes.Writer(48);
        w.writeByte(RegionSummaryWire.VERSION);
        w.writeUtf("minecraft:overworld");
        RegionSummaryWire.writeZigVarLong(w, 0);
        RegionSummaryWire.writeZigVarLong(w, 0);
        w.writeVarInt(0);
        RegionSummaryWire.writeZigVarLong(w, Long.MAX_VALUE); // delta from prev=0
        var decoded = RegionSummaryWire.decodeSummary(w.toByteArray());
        assertEquals(RegionSummaryWire.STAMP_NEVER_CLEAN, decoded.stampSeconds()[0]);
    }
}
