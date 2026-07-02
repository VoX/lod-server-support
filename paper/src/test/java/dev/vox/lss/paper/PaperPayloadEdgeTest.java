package dev.vox.lss.paper;

import dev.vox.lss.common.HandshakeGate;
import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.common.PositionUtil;
import dev.vox.lss.common.processing.QueuedPayload;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.EncoderException;
import net.minecraft.SharedConstants;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerPlayer;
import org.bukkit.craftbukkit.v1_20_R1.entity.CraftPlayer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Hostile/edge-frame hardening for the Paper plugin-message codec. {@code WireParityTest}
 * pins the happy-path bytes; this suite pins the defensive contracts: count guards run
 * BEFORE array allocation (allocation DoS), legacy handshakes without a capabilities
 * VarInt still decode, truncated frames fail via a catchable Exception (the plugin's
 * onPluginMessageReceived catches Exception, not Error), trailing bytes are tolerated
 * (forward compat), the dirty-columns encoder clamps to the wire limit, and oversized
 * columns are dropped by {@link PaperOffThreadProcessor} because the client decoder's
 * {@code readByteArray(MAX_SECTIONS_SIZE)} guard would reject (and disconnect on) them.
 */
class PaperPayloadEdgeTest {

    @BeforeAll
    static void setup() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static byte[] frame(Consumer<FriendlyByteBuf> ops) {
        var b = new FriendlyByteBuf(Unpooled.buffer());
        ops.accept(b);
        byte[] out = new byte[b.readableBytes()];
        b.readBytes(out);
        b.release();
        return out;
    }

    // ---- C2S: batch chunk request decode guards ----

    @Test
    void batchRequestCountGuardRejectsBeforeAllocation() {
        // Integer.MAX_VALUE would be a ~16 GB allocation if the guard ran after `new long[count]`
        assertNull(PaperPayloadHandler.decodeBatchChunkRequest(
                frame(b -> b.writeVarInt(Integer.MAX_VALUE))));
        assertNull(PaperPayloadHandler.decodeBatchChunkRequest(
                frame(b -> b.writeVarInt(LSSConstants.MAX_BATCH_CHUNK_REQUESTS + 1))));
    }

    @Test
    void batchRequestNegativeCountRejected() {
        assertNull(PaperPayloadHandler.decodeBatchChunkRequest(frame(b -> b.writeVarInt(-1))));
    }

    @Test
    void batchRequestAtMaxCountAccepted() {
        int max = LSSConstants.MAX_BATCH_CHUNK_REQUESTS;
        byte[] data = frame(b -> {
            b.writeVarInt(max);
            for (int i = 0; i < max; i++) {
                b.writeLong(PositionUtil.packPosition(i, -i));
                b.writeLong(i);
            }
        });
        var decoded = PaperPayloadHandler.decodeBatchChunkRequest(data);
        assertNotNull(decoded);
        assertEquals(max, decoded.count());
        assertEquals(PositionUtil.packPosition(0, 0), decoded.packedPositions()[0]);
        assertEquals(PositionUtil.packPosition(max - 1, -(max - 1)), decoded.packedPositions()[max - 1]);
        assertEquals(max - 1, decoded.clientTimestamps()[max - 1]);
    }

    @Test
    void batchRequestNullOrEmptyPayloadRejected() {
        assertNull(PaperPayloadHandler.decodeBatchChunkRequest(null));
        assertNull(PaperPayloadHandler.decodeBatchChunkRequest(new byte[0]));
    }

    @Test
    void batchRequestTruncatedFrameThrowsCatchableException() {
        // Declares 2 entries but carries only one; must not produce a partial decode, and the
        // failure must be an Exception (LSSPaperPlugin.onPluginMessageReceived catches Exception)
        byte[] truncated = frame(b -> {
            b.writeVarInt(2);
            b.writeLong(PositionUtil.packPosition(1, 1));
            b.writeLong(-1L);
        });
        assertThrows(Exception.class, () -> PaperPayloadHandler.decodeBatchChunkRequest(truncated));
    }

    @Test
    void batchRequestTrailingBytesIgnored() {
        byte[] data = frame(b -> {
            b.writeVarInt(1);
            b.writeLong(PositionUtil.packPosition(-3, 9));
            b.writeLong(123L);
            b.writeBytes(new byte[]{0x7F, 0x00, 0x55}); // future protocol extension bytes
        });
        var decoded = PaperPayloadHandler.decodeBatchChunkRequest(data);
        assertNotNull(decoded);
        assertEquals(1, decoded.count());
        assertEquals(PositionUtil.packPosition(-3, 9), decoded.packedPositions()[0]);
        assertEquals(123L, decoded.clientTimestamps()[0]);
    }

    // ---- C2S: handshake decode guards ----

    @Test
    void handshakeLegacyFrameWithoutCapabilitiesDefaultsToZero() {
        var decoded = PaperPayloadHandler.decodeHandshake(frame(b -> b.writeVarInt(9)));
        assertNotNull(decoded);
        assertEquals(9, decoded.protocolVersion());
        assertEquals(0, decoded.capabilities());
    }

    @Test
    void handshakeNullOrEmptyPayloadRejected() {
        assertNull(PaperPayloadHandler.decodeHandshake(null));
        assertNull(PaperPayloadHandler.decodeHandshake(new byte[0]));
    }

    @Test
    void handshakeTrailingBytesIgnored() {
        byte[] data = frame(b -> {
            b.writeVarInt(LSSConstants.PROTOCOL_VERSION);
            b.writeVarInt(LSSConstants.CAPABILITY_VOXEL_COLUMNS);
            b.writeBytes(new byte[]{0x01, 0x02});
        });
        var decoded = PaperPayloadHandler.decodeHandshake(data);
        assertNotNull(decoded);
        assertEquals(LSSConstants.PROTOCOL_VERSION, decoded.protocolVersion());
        assertEquals(LSSConstants.CAPABILITY_VOXEL_COLUMNS, decoded.capabilities());
    }

    @Test
    void handshakeNegativeVersionDecodesAndGateRefusesReply() {
        // A negative protocolVersion VarInt must survive decode sign-intact and classify as
        // VERSION_MISMATCH (no reply, no registration) — guards a relational rewrite of the
        // gate (e.g. `version < PROTOCOL_VERSION` would invert for hostile negative input).
        var decoded = PaperPayloadHandler.decodeHandshake(frame(b -> {
            b.writeVarInt(-1);
            b.writeVarInt(LSSConstants.CAPABILITY_VOXEL_COLUMNS);
        }));
        assertNotNull(decoded);
        assertEquals(-1, decoded.protocolVersion(), "negative version must decode sign-intact");
        var decision = HandshakeGate.evaluate(
                decoded.protocolVersion(), decoded.capabilities(), true, true);
        assertEquals(HandshakeGate.Outcome.VERSION_MISMATCH, decision.outcome());
        assertFalse(decision.sendSessionConfig());
        assertFalse(decision.registerPlayer());
    }

    @Test
    void handshakeMalformedVarIntOverflowThrowsCatchableException() {
        // Six continuation bytes can never terminate a VarInt. The throw must be an
        // Exception — LSSPaperPlugin.onPluginMessageReceived catches Exception, so an Error
        // here would escape the containment and take down the channel handler.
        byte[] garbage = {(byte) 0x80, (byte) 0x80, (byte) 0x80,
                (byte) 0x80, (byte) 0x80, (byte) 0x80};
        assertThrows(Exception.class, () -> PaperPayloadHandler.decodeHandshake(garbage));
    }

    // ---- S2C: dirty-columns encoder clamp ----

    @Test
    void dirtyColumnsEncoderClampsToWireLimit() {
        int max = LSSConstants.MAX_DIRTY_COLUMN_POSITIONS;
        long[] positions = new long[max + 5];
        for (int i = 0; i < positions.length; i++) {
            positions[i] = PositionUtil.packPosition(i, i + 1);
        }
        var buf = new FriendlyByteBuf(Unpooled.wrappedBuffer(PaperPayloadHandler.encodeDirtyColumns(positions)));
        try {
            assertEquals(max, buf.readVarInt(), "count clamped to MAX_DIRTY_COLUMN_POSITIONS");
            for (int i = 0; i < max; i++) {
                assertEquals(positions[i], buf.readLong());
            }
            assertEquals(0, buf.readableBytes(), "no positions written beyond the clamp");
        } finally {
            buf.release();
        }
    }

    @Test
    void dirtyColumnsEncoderAtLimitKeepsEveryPosition() {
        int max = LSSConstants.MAX_DIRTY_COLUMN_POSITIONS;
        long[] positions = new long[max];
        for (int i = 0; i < max; i++) {
            positions[i] = PositionUtil.packPosition(-i, i);
        }
        var buf = new FriendlyByteBuf(Unpooled.wrappedBuffer(PaperPayloadHandler.encodeDirtyColumns(positions)));
        try {
            assertEquals(max, buf.readVarInt());
            assertEquals(positions[0], buf.readLong());
            buf.skipBytes((max - 2) * Long.BYTES);
            assertEquals(positions[max - 1], buf.readLong(), "boundary frame is not truncated");
            assertEquals(0, buf.readableBytes());
        } finally {
            buf.release();
        }
    }

    // ---- S2C: voxel column MAX_SECTIONS_SIZE boundary ----

    /** Decode a voxel-column frame with the exact grammar + guards of the Fabric client decoder. */
    private record ClientDecoded(int cx, int cz, String dimension, long timestamp, byte[] sections) {}

    private static ClientDecoded clientDecode(byte[] data) {
        var buf = new FriendlyByteBuf(Unpooled.wrappedBuffer(data));
        try {
            var decoded = new ClientDecoded(
                    buf.readInt(), buf.readInt(),
                    buf.readUtf(LSSConstants.MAX_DIMENSION_STRING_LENGTH),
                    buf.readLong(),
                    buf.readByteArray(LSSConstants.MAX_SECTIONS_SIZE));
            assertEquals(0, buf.readableBytes(), "frame fully drained");
            return decoded;
        } finally {
            buf.release();
        }
    }

    @Test
    void voxelColumnAtSectionsLimitPassesClientGuard() {
        byte[] sections = new byte[LSSConstants.MAX_SECTIONS_SIZE];
        sections[0] = 0x42;
        sections[sections.length - 1] = 0x24;
        byte[] data = PaperPayloadHandler.encodeVoxelColumnPreEncoded(
                7, -8, "minecraft:the_end", 1234L, sections);
        var decoded = clientDecode(data);
        assertEquals(7, decoded.cx());
        assertEquals(-8, decoded.cz());
        assertEquals("minecraft:the_end", decoded.dimension());
        assertEquals(1234L, decoded.timestamp());
        assertEquals(LSSConstants.MAX_SECTIONS_SIZE, decoded.sections().length);
        assertEquals(0x42, decoded.sections()[0]);
        assertEquals(0x24, decoded.sections()[decoded.sections().length - 1]);
    }

    @Test
    void voxelColumnOverSectionsLimitRejectedByClientGuard() {
        // One byte over the limit: the client-side readByteArray guard throws, which on a live
        // connection disconnects the player — this is why the server must drop instead of send.
        byte[] data = PaperPayloadHandler.encodeVoxelColumnPreEncoded(
                0, 0, "minecraft:overworld", 1L, new byte[LSSConstants.MAX_SECTIONS_SIZE + 1]);
        assertThrows(DecoderException.class, () -> clientDecode(data));
    }

    // ---- S2C: voxel column dimension-string cap (Paper arms of the 256-char contract) ----

    @Test
    void voxelColumnDimensionAt256CharsEncodesAndPassesClientGuard() {
        // Exactly at the cap: Paper must encode it and the Fabric client decoder (same
        // grammar + guards as clientDecode) must accept the frame — writer cap == reader cap.
        String dim = "lss:" + "a".repeat(LSSConstants.MAX_DIMENSION_STRING_LENGTH - 4);
        assertEquals(LSSConstants.MAX_DIMENSION_STRING_LENGTH, dim.length());
        byte[] data = PaperPayloadHandler.encodeVoxelColumnPreEncoded(1, 2, dim, 9L, new byte[]{0});
        var decoded = clientDecode(data);
        assertEquals(dim, decoded.dimension(),
                "a 256-char datapack dimension must survive Paper encode -> client decode");
    }

    @Test
    void voxelColumnDimensionOver256CharsThrowsOnEncode() {
        // The writer cap must hold on Paper too: an uncapped writeUtf would emit a frame the
        // client reader's cap rejects, disconnecting every client receiving that dimension.
        String dim = "lss:" + "a".repeat(LSSConstants.MAX_DIMENSION_STRING_LENGTH - 3);
        assertEquals(LSSConstants.MAX_DIMENSION_STRING_LENGTH + 1, dim.length());
        assertThrows(EncoderException.class, () -> PaperPayloadHandler.encodeVoxelColumnPreEncoded(
                0, 0, dim, 0L, new byte[]{0}));
    }

    // ---- PaperOffThreadProcessor: oversized-column drop (Paper mirror of the client guard) ----

    private static PaperOffThreadProcessor newProcessor() {
        // Thread is created but never start()ed; null diskReader/dataDir follow the
        // OffThreadProcessorMailboxTest harness pattern.
        return new PaperOffThreadProcessor(new ConcurrentHashMap<>(), null, false, null, 1);
    }

    @Test
    void processorDropsOversizedColumnInsteadOfEncoding() {
        var proc = newProcessor();
        var state = mock(PaperPlayerRequestState.class);
        proc.buildAndEnqueueColumnPayload(state, 1, 2, "minecraft:overworld", 42L, 7L,
                new byte[LSSConstants.MAX_SEND_SECTIONS_SIZE + 1], 99);
        verify(state, never()).addReadyPayload(any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void processorEncodesAndEnqueuesColumnAtSectionsLimit() {
        var proc = newProcessor();
        var state = mock(PaperPlayerRequestState.class);
        byte[] sections = new byte[LSSConstants.MAX_SEND_SECTIONS_SIZE];
        sections[123] = 0x5A;
        proc.buildAndEnqueueColumnPayload(state, 1, 2, "minecraft:overworld", 42L, 7L, sections, 99);

        var captor = ArgumentCaptor.forClass((Class<QueuedPayload<byte[]>>) (Class<?>) QueuedPayload.class);
        verify(state).addReadyPayload(captor.capture());
        var queued = captor.getValue();
        assertArrayEquals(PaperPayloadHandler.encodeVoxelColumnPreEncoded(
                1, 2, "minecraft:overworld", 42L, sections), queued.payload());
        assertEquals(99, queued.estimatedBytes());
        assertEquals(7L, queued.submissionOrder());
    }

    @Test
    void processorDropReturnsFalseSoTheDropGetsAnswered() {
        // The false return is what routes the dropped position to a terminal ColumnUpToDate
        // in OffThreadProcessor (rejected-enqueue path): a regression returning true while
        // dropping would stamp diskReadDone with nothing in the send queue, so the client's
        // ts<=0 re-asks re-resolve the unserveable position forever. Pin both directions.
        var proc = newProcessor();
        var state = mock(PaperPlayerRequestState.class);
        assertFalse(proc.buildAndEnqueueColumnPayload(state, 1, 2, "minecraft:overworld", 42L, 7L,
                new byte[LSSConstants.MAX_SEND_SECTIONS_SIZE + 1], 99),
                "an oversized drop must report false so the caller answers up-to-date");
        verify(state, never()).addReadyPayload(any());
        assertTrue(proc.buildAndEnqueueColumnPayload(state, 1, 2, "minecraft:overworld", 42L, 7L,
                new byte[]{0}, 9),
                "a successful enqueue must report true (false would answer up-to-date AND send)");
    }

    // ---- sendRawNmsPayload: disconnect-race guard ----

    @Test
    void sendRawNmsPayloadWithNullConnectionIsNoOp() {
        // Disconnect race: the send-queue flush can run after the player's connection is
        // torn down. A Mockito-created ServerPlayer never runs a constructor, so its public
        // `connection` field is null — exactly the shape of the race. Removing the null
        // guard turns this into an NPE.
        var nms = mock(ServerPlayer.class);
        var craft = mock(CraftPlayer.class);
        when(craft.getHandle()).thenReturn(nms);
        assertDoesNotThrow(() -> PaperPayloadHandler.sendRawNmsPayload(
                craft, PaperPayloadHandler.ID_VOXEL_COLUMN, new byte[]{1, 2, 3}));
    }
}
