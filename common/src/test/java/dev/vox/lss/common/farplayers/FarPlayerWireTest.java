package dev.vox.lss.common.farplayers;

import dev.vox.lss.common.wire.WireFormatException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The far-player wire codec (E1): full round-trips for all three frames, encoding
 * determinism (the wire-parity property — both platforms ship these bytes verbatim),
 * the per-payload dictionary rules, the quantization helpers, and the hostile-frame
 * decode guards (bounded counts, dictionary range, trailing bytes).
 */
class FarPlayerWireTest {

    private static final UUID U1 = new UUID(0x1122334455667788L, 0x99AABBCCDDEEFF00L);
    private static final UUID U2 = new UUID(2L, 3L);

    @Test
    void prefsRoundTrip() {
        var p = new FarPlayerWire.Prefs(true, 2048, 64, false, 512);
        var back = FarPlayerWire.decodePrefs(FarPlayerWire.encodePrefs(p));
        assertEquals(p, back);
    }

    @Test
    void rosterRoundTrip() {
        var roster = new FarPlayerWire.Roster(7, true,
                List.of(new FarPlayerWire.RosterEntry(0, U1, "Alice"),
                        new FarPlayerWire.RosterEntry(1, U2, "Bob_")),
                new int[]{3, 9});
        var back = FarPlayerWire.decodeRoster(FarPlayerWire.encodeRoster(roster));
        assertEquals(roster.epoch(), back.epoch());
        assertTrue(back.full());
        assertEquals(roster.added(), back.added());
        assertArrayEquals(roster.removedIndices(), back.removedIndices());
    }

    private static FarPlayerWire.UpdateEntry fullEntry() {
        return new FarPlayerWire.UpdateEntry(1,
                FarPlayerWire.quantizePos(100.5), FarPlayerWire.quantizePos(64.0),
                FarPlayerWire.quantizePos(-9000.25),
                FarPlayerWire.angleToByte(90f), FarPlayerWire.angleToByte(85f),
                FarPlayerWire.angleToByte(-10f),
                (byte) 0b101,
                FarPlayerWire.velocityToShort(40.0), FarPlayerWire.velocityToShort(-2.5),
                FarPlayerWire.velocityToShort(0),
                new int[]{1, 0, 0, 0, 2, 0}, new int[]{1, 0, 0, 0, 64, 0},
                new String[]{"minecraft:netherite_helmet", null, null, null, "minecraft:arrow", null},
                new FarPlayerWire.Vehicle("minecraft:horse", U2,
                        FarPlayerWire.quantizePos(100.5), FarPlayerWire.quantizePos(63.0),
                        FarPlayerWire.quantizePos(-9000.25),
                        FarPlayerWire.angleToByte(90f), (byte) 0));
    }

    @Test
    void updatesRoundTripWithEquipmentAndVehicle() {
        var minimal = new FarPlayerWire.UpdateEntry(0, 16, 1024, -16,
                (byte) 0, (byte) 0, (byte) 0, (byte) 0, (short) 0, (short) 0, (short) 0,
                null, null, null, null);
        var updates = new FarPlayerWire.Updates(3, "minecraft:overworld", 10,
                List.of(minimal, fullEntry()));
        var back = FarPlayerWire.decodeUpdates(FarPlayerWire.encodeUpdates(updates));

        assertEquals(3, back.epoch());
        assertEquals("minecraft:overworld", back.dimension());
        assertEquals(10, back.cadenceTicks());
        assertEquals(2, back.entries().size());

        var m = back.entries().get(0);
        assertNull(m.equipmentIdentities(), "absent equipment stays absent");
        assertNull(m.vehicle());
        assertEquals(16, m.quantX());

        var f = back.entries().get(1);
        assertNotNull(f.equipmentIdentities());
        assertEquals("minecraft:netherite_helmet", f.equipmentIdentities()[0]);
        assertNull(f.equipmentIdentities()[1], "empty slot decodes null");
        assertEquals(64, f.equipmentCounts()[4]);
        assertNotNull(f.vehicle());
        assertEquals("minecraft:horse", f.vehicle().typeIdentity());
        assertEquals(U2, f.vehicle().uuid());
        assertEquals(FarPlayerWire.quantizePos(100.5), f.vehicle().quantX());
    }

    @Test
    void encodingIsDeterministicAcrossCalls() {
        var updates = new FarPlayerWire.Updates(1, "minecraft:the_end", 20,
                List.of(fullEntry(), fullEntry()));
        assertArrayEquals(FarPlayerWire.encodeUpdates(updates), FarPlayerWire.encodeUpdates(updates),
                "identical values must produce identical bytes (the parity property)");
    }

    @Test
    void dictionaryDeduplicatesSharedIdentities() {
        // Two riders on the same vehicle type + same helmet: the identity strings must
        // appear ONCE in the frame (the dictionary is the wire-cost win over SeeU).
        var updates = new FarPlayerWire.Updates(1, "minecraft:overworld", 10,
                List.of(fullEntry(), fullEntry()));
        byte[] bytes = FarPlayerWire.encodeUpdates(updates);
        assertEquals(1, countOccurrences(bytes, "minecraft:horse"),
                "shared vehicle type crosses once");
        assertEquals(1, countOccurrences(bytes, "minecraft:netherite_helmet"));
    }

    private static int countOccurrences(byte[] haystack, String needle) {
        String s = new String(haystack, java.nio.charset.StandardCharsets.ISO_8859_1);
        int count = 0;
        for (int i = s.indexOf(needle); i >= 0; i = s.indexOf(needle, i + 1)) count++;
        return count;
    }

    @Test
    void quantizationHelpersAreInverseWithinPrecision() {
        assertEquals(100.5, FarPlayerWire.dequantizePos(FarPlayerWire.quantizePos(100.5)));
        assertEquals(-0.0625, FarPlayerWire.dequantizePos(FarPlayerWire.quantizePos(-0.0625)));
        assertEquals(90f, FarPlayerWire.byteToAngle(FarPlayerWire.angleToByte(90f)), 1.5f);
        assertEquals(40.0, FarPlayerWire.shortToVelocity(FarPlayerWire.velocityToShort(40.0)), 0.01);
        // The clamp: elytra-class is in range, absurd values saturate.
        assertEquals(64.0, FarPlayerWire.shortToVelocity(FarPlayerWire.velocityToShort(500.0)), 0.01);
        assertEquals(-64.0, FarPlayerWire.shortToVelocity(FarPlayerWire.velocityToShort(-500.0)), 0.01);
    }

    @Test
    void hostileFramesAreRejectedBounded() {
        // Oversized roster count
        var w = new dev.vox.lss.common.wire.WireBytes.Writer(8);
        w.writeVarInt(1).writeByte(1).writeVarInt(FarPlayerWire.MAX_ROSTER_ENTRIES + 1);
        byte[] evilRoster = w.toByteArray();
        assertThrows(WireFormatException.class, () -> FarPlayerWire.decodeRoster(evilRoster));

        // Dictionary index outside the table
        var w2 = new dev.vox.lss.common.wire.WireBytes.Writer(32);
        w2.writeVarInt(1).writeUtf("minecraft:overworld").writeVarInt(10);
        w2.writeVarInt(0); // empty dictionary
        w2.writeVarInt(1); // one entry
        w2.writeVarInt(0).writeByte(FarPlayerWire.PRESENCE_VEHICLE);
        w2.writeInt(0).writeInt(0).writeInt(0);
        w2.writeByte(0).writeByte(0).writeByte(0).writeByte(0);
        w2.writeShort(0).writeShort(0).writeShort(0);
        w2.writeVarInt(5); // vehicle type index into the EMPTY dictionary
        w2.writeLong(0).writeLong(0).writeInt(0).writeInt(0).writeInt(0).writeByte(0).writeByte(0);
        byte[] evilDict = w2.toByteArray();
        assertThrows(WireFormatException.class, () -> FarPlayerWire.decodeUpdates(evilDict));

        // Trailing bytes are a format error, not silently ignored
        byte[] prefs = FarPlayerWire.encodePrefs(new FarPlayerWire.Prefs(true, 1, 0, true, 1));
        byte[] trailing = java.util.Arrays.copyOf(prefs, prefs.length + 1);
        assertThrows(WireFormatException.class, () -> FarPlayerWire.decodePrefs(trailing));

        // Truncation surfaces as WireFormatException, never IndexOutOfBounds
        byte[] roster = FarPlayerWire.encodeRoster(new FarPlayerWire.Roster(1, true,
                List.of(new FarPlayerWire.RosterEntry(0, U1, "Alice")), new int[0]));
        byte[] cut = java.util.Arrays.copyOf(roster, roster.length - 3);
        assertThrows(WireFormatException.class, () -> FarPlayerWire.decodeRoster(cut));
    }
}
