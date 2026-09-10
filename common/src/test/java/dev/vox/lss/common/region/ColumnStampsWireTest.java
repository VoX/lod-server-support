package dev.vox.lss.common.region;

import dev.vox.lss.common.PositionUtil;
import dev.vox.lss.common.wire.WireBytes;
import dev.vox.lss.common.wire.WireFormatException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The stamped-up_to_date wire codec's pins (stamped-up-to-date-plan.md §3/§9.6) — the
 * FarPlayerWire/RegionSummaryWire hostile-decode bar, plus the SEMANTIC stamp bounds
 * that are this codec's own: the client-side ratchet is monotonic and revocation only
 * touches summary provenance, so a single hostile huge second would seal a position
 * against offline edits permanently. Frames violating any bound drop WHOLE.
 */
class ColumnStampsWireTest {

    private static final long NOW = 1_750_000_000L;
    private static final String DIM = "minecraft:overworld";

    private static byte[] frame(long... seconds) {
        long[] positions = new long[seconds.length];
        for (int i = 0; i < seconds.length; i++) {
            positions[i] = PositionUtil.packPosition(i * 3 - 7, -i);
        }
        return ColumnStampsWire.encode(DIM, positions, seconds, seconds.length);
    }

    @Test
    void roundTripsPositionsAndSeconds() {
        long[] positions = {PositionUtil.packPosition(3, -4),
                PositionUtil.packPosition(-2_000_000, 2_000_000),
                PositionUtil.packPosition(0, 0)};
        long[] seconds = {NOW, NOW - 3600, NOW + 5};
        var decoded = ColumnStampsWire.decode(
                ColumnStampsWire.encode(DIM, positions, seconds, 3), NOW + 10);
        assertEquals(DIM, decoded.dimension());
        assertArrayEquals(positions, decoded.packedPositions());
        assertArrayEquals(seconds, decoded.stampSeconds());
    }

    @Test
    void unknownVersionDrops() {
        byte[] f = frame(NOW);
        f[0] = 9;
        assertThrows(WireFormatException.class, () -> ColumnStampsWire.decode(f, NOW + 10));
    }

    @Test
    void truncatedFrameDropsBeforeAllocation() {
        byte[] f = frame(NOW, NOW + 1, NOW + 2);
        byte[] cut = java.util.Arrays.copyOf(f, f.length - 12);
        assertThrows(WireFormatException.class, () -> ColumnStampsWire.decode(cut, NOW + 10));
    }

    @Test
    void trailingBytesDrop() {
        byte[] f = frame(NOW);
        byte[] padded = java.util.Arrays.copyOf(f, f.length + 3);
        assertThrows(WireFormatException.class, () -> ColumnStampsWire.decode(padded, NOW + 10));
    }

    @Test
    void hostileCountsDrop() {
        // count = 0 rejects at the count bound; count > MAX rejects at the count cap
        // (the remaining-bytes floor's own pin is truncatedFrameDropsBeforeAllocation).
        var w = new WireBytes.Writer(32);
        w.writeByte(ColumnStampsWire.VERSION);
        w.writeUtf(DIM);
        RegionSummaryWire.writeZigVarLong(w, NOW);
        w.writeVarInt(0);
        assertThrows(WireFormatException.class,
                () -> ColumnStampsWire.decode(w.toByteArray(), NOW + 10));

        var w2 = new WireBytes.Writer(32);
        w2.writeByte(ColumnStampsWire.VERSION);
        w2.writeUtf(DIM);
        RegionSummaryWire.writeZigVarLong(w2, NOW);
        w2.writeVarInt(ColumnStampsWire.MAX_STAMP_ENTRIES + 1);
        assertThrows(WireFormatException.class,
                () -> ColumnStampsWire.decode(w2.toByteArray(), NOW + 10));
    }

    @Test
    void oversizedDimensionDrops() {
        // Hand-crafted: encode itself now refuses an over-cap dimension (the
        // producer-side guard, pinned below) — the hostile frame must be built raw.
        String longDim = "lss:" + "d".repeat(300);
        var w = new WireBytes.Writer(512);
        w.writeByte(ColumnStampsWire.VERSION);
        w.writeUtf(longDim);
        RegionSummaryWire.writeZigVarLong(w, NOW);
        w.writeVarInt(1);
        w.writeLong(PositionUtil.packPosition(1, 1));
        RegionSummaryWire.writeZigVarLong(w, 0);
        assertThrows(WireFormatException.class,
                () -> ColumnStampsWire.decode(w.toByteArray(), NOW + 10));
    }

    @Test
    void encodeRefusesAnOversizedOrEmptyDimension() {
        // The encode-side guard (final panel — RegionSummaryWire's rule mirrored):
        // the client rejects the frame WHOLE, so a bad dimension must fail loudly at
        // the server, not ship frames every client silently discards.
        long[] pos = {PositionUtil.packPosition(1, 1)};
        long[] sec = {NOW};
        assertThrows(WireFormatException.class, () -> ColumnStampsWire.encode(
                "lss:" + "d".repeat(300), pos, sec, 1));
        assertThrows(WireFormatException.class, () -> ColumnStampsWire.encode(
                "", pos, sec, 1));
    }

    @Test
    void futureSecondBeyondSkewDropsWhole() {
        // The permanent-seal shape (§9.6): one hostile huge second must reject the
        // WHOLE frame — partial application of a corrupt frame is worse than losing
        // it (loss is the designed-tolerant case). Hand-crafted: encode itself now
        // refuses out-of-bound seconds (the producer-side bound), so the hostile
        // frame must be built raw.
        var w = new WireBytes.Writer(64);
        w.writeByte(ColumnStampsWire.VERSION);
        w.writeUtf(DIM);
        RegionSummaryWire.writeZigVarLong(w, NOW);
        w.writeVarInt(2);
        w.writeLong(PositionUtil.packPosition(0, 0));
        RegionSummaryWire.writeZigVarLong(w, 0); // NOW — fine
        w.writeLong(PositionUtil.packPosition(1, 1));
        RegionSummaryWire.writeZigVarLong(w,
                ColumnStampsWire.FUTURE_SKEW_ALLOWANCE_SECONDS + 100); // beyond skew
        assertThrows(WireFormatException.class,
                () -> ColumnStampsWire.decode(w.toByteArray(), NOW));
        // At the boundary it passes (receiver clock == NOW here).
        var ok = ColumnStampsWire.decode(
                frame(NOW + ColumnStampsWire.FUTURE_SKEW_ALLOWANCE_SECONDS), NOW);
        assertEquals(1, ok.stampSeconds().length);
    }

    @Test
    void encodeRefusesAClockDamagedSecond() {
        // The producer-side bound (3-Opus fold): the client rejects out-of-bound
        // frames WHOLE, so one damaged second would silently discard up to 1023 good
        // entries at every client — the server must fail loudly at build instead.
        long damaged = System.currentTimeMillis() / 1000L
                + ColumnStampsWire.FUTURE_SKEW_ALLOWANCE_SECONDS + 1000;
        assertThrows(WireFormatException.class, () -> ColumnStampsWire.encode(
                DIM, new long[]{1L}, new long[]{damaged}, 1));
    }

    @Test
    void nonPositiveSecondsDropAtDecodeAndRefuseAtEncode() {
        // Decode side: craft a frame whose delta walks the second to 0.
        var w = new WireBytes.Writer(64);
        w.writeByte(ColumnStampsWire.VERSION);
        w.writeUtf(DIM);
        RegionSummaryWire.writeZigVarLong(w, NOW);
        w.writeVarInt(1);
        w.writeLong(PositionUtil.packPosition(1, 1));
        RegionSummaryWire.writeZigVarLong(w, -NOW); // second = 0
        assertThrows(WireFormatException.class,
                () -> ColumnStampsWire.decode(w.toByteArray(), NOW + 10));
        // Encode side: a producer bug fails loudly at build, not at the client.
        assertThrows(WireFormatException.class, () -> ColumnStampsWire.encode(
                DIM, new long[]{1L}, new long[]{0L}, 1));
    }
}
