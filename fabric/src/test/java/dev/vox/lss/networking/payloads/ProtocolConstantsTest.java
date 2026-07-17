package dev.vox.lss.networking.payloads;

import dev.vox.lss.common.LSSConstants;
import io.netty.buffer.Unpooled;
import net.minecraft.SharedConstants;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the protocol constants and their relationships to the vanilla transport caps they
 * must live under. These are arithmetic tripwires: a "harmless" constant bump can ship
 * green through every functional test and still break every server or client in the field
 * at the netty layer, so each failure message names the constant decision to revisit.
 *
 * <p>Vanilla caps referenced (verified against MC 26.1.2):
 * {@code ServerboundCustomPayloadPacket.MAX_PAYLOAD_SIZE} = 32767,
 * {@code ClientboundCustomPayloadPacket.MAX_PAYLOAD_SIZE} = 1_048_576 (applies only to
 * channels the receiver did NOT register, i.e. the DiscardedPayload path),
 * {@code CompressionDecoder.MAXIMUM_UNCOMPRESSED_LENGTH} = 8_388_608.
 */
class ProtocolConstantsTest {

    @BeforeAll
    static void setup() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static int varIntSize(int value) {
        var b = new FriendlyByteBuf(Unpooled.buffer());
        b.writeVarInt(value);
        int size = b.readableBytes();
        b.release();
        return size;
    }

    private static <T> byte[] encode(StreamCodec<FriendlyByteBuf, T> codec, T payload) {
        var b = new FriendlyByteBuf(Unpooled.buffer());
        codec.encode(b, payload);
        byte[] out = new byte[b.readableBytes()];
        b.readBytes(out);
        b.release();
        return out;
    }

    // ---- C2S envelope vs the 32767-byte serverbound custom-payload cap ----

    @Test
    void c2sFramesFitTheServerboundCustomPayloadCap() {
        // Paper receives every LSS C2S frame as a plugin message under exactly this vanilla
        // cap; an oversized frame is rejected at the protocol layer before LSS sees it.
        // 5 bytes = worst-case count VarInt, 16 = packed position + clientTimestamp pair.
        int maxBatchRequestFrame = 5 + LSSConstants.MAX_BATCH_CHUNK_REQUESTS * 16;
        assertTrue(maxBatchRequestFrame <= 32767,
                "MAX_BATCH_CHUNK_REQUESTS=" + LSSConstants.MAX_BATCH_CHUNK_REQUESTS
                + " makes the largest batch-request frame " + maxBatchRequestFrame
                + " bytes, over the 32767-byte vanilla serverbound custom-payload cap;"
                + " with the 16-byte pair encoding the constant must stay <= 2047");
        int maxHandshakeFrame = 5 + 5; // two VarInts
        assertTrue(maxHandshakeFrame <= 32767,
                "handshake (two VarInts) must fit the serverbound cap");
    }

    // ---- S2C envelope table vs the clientbound caps ----

    @Test
    void s2cFrameEnvelopesVsVanillaCaps() {
        // The three lightweight S2C payloads must fit even the 1 MiB unregistered-channel
        // (DiscardedPayload) decode cap — Paper ships them through that wrapper class, and
        // sub-1-MiB keeps them safe regardless of receiver-side channel registration.
        int batchResponseMax = varIntSize(LSSConstants.MAX_BATCH_RESPONSES)
                + LSSConstants.MAX_BATCH_RESPONSES * 9;
        int dirtyColumnsMax = varIntSize(LSSConstants.MAX_DIRTY_COLUMN_POSITIONS)
                + LSSConstants.MAX_DIRTY_COLUMN_POSITIONS * Long.BYTES;
        int sessionConfigMax = 5 + 1 + 5 + 5 + 5 + 1; // four VarInts + two booleans
        assertTrue(batchResponseMax < 1_048_576,
                "MAX_BATCH_RESPONSES makes the largest batch-response frame " + batchResponseMax
                + " bytes; it must stay under the 1 MiB clientbound custom-payload cap");
        assertTrue(dirtyColumnsMax < 1_048_576,
                "MAX_DIRTY_COLUMN_POSITIONS makes the largest dirty-columns frame "
                + dirtyColumnsMax + " bytes; it must stay under the 1 MiB cap");
        assertTrue(sessionConfigMax < 1_048_576);

        // VoxelColumn is the DOCUMENTED EXEMPTION from the naive 1 MiB inequality: a
        // max-size column frame intentionally exceeds it. The true safety relationship:
        // only handshaken clients receive columns, every such client has the LSS channel
        // codec registered (so the 1 MiB DiscardedPayload cap never applies to it; the
        // governing client cap is readByteArray(MAX_SECTIONS_SIZE)), and both platforms'
        // processors DROP any column whose sectionBytes exceed MAX_SEND_SECTIONS_SIZE before
        // send (pinned by FabricOffThreadProcessorDropTest and PaperPayloadEdgeTest). The
        // frame must still clear the 8 MiB uncompressed packet ceiling that vanilla
        // enforces on compressed connections.
        int dimensionMax = varIntSize(LSSConstants.MAX_DIMENSION_STRING_LENGTH * 3)
                + LSSConstants.MAX_DIMENSION_STRING_LENGTH * 3; // worst-case UTF-8 expansion
        int voxelColumnMax = 4 + 4 + dimensionMax + 8
                + varIntSize(LSSConstants.MAX_SECTIONS_SIZE) + LSSConstants.MAX_SECTIONS_SIZE;
        assertTrue(voxelColumnMax > 1_048_576,
                "the voxel-column exemption above is only documented while it is real: if"
                + " MAX_SECTIONS_SIZE now fits the 1 MiB cap, delete the exemption comment"
                + " and fold the payload into the naive table");
        assertTrue(voxelColumnMax <= 8_388_608,
                "a max-size voxel-column frame (" + voxelColumnMax + " bytes) must fit the"
                + " 8 MiB vanilla uncompressed packet ceiling — shrink MAX_SECTIONS_SIZE or"
                + " redesign the framing before raising it");

        // The SEND threshold governs what actually goes on the wire, and its binding cap is
        // netty's frame limit: Varint21LengthFieldPrepender throws (killing the connection)
        // for any frame over 2_097_151 bytes, and with network compression disabled the frame
        // is the raw packet. The largest sendable column body plus generous envelope headroom
        // (packet id, channel identifier, framing varint) must stay under it — otherwise an
        // admitted column kicks the client in a rejoin/re-serve loop.
        int sendColumnBodyMax = 4 + 4 + dimensionMax + 8
                + varIntSize(LSSConstants.MAX_SEND_SECTIONS_SIZE) + LSSConstants.MAX_SEND_SECTIONS_SIZE;
        assertTrue(sendColumnBodyMax + 256 <= 2_097_151,
                "the largest sendable voxel-column frame (" + sendColumnBodyMax + " + envelope)"
                + " must fit netty's 2_097_151-byte frame cap — shrink MAX_SEND_SECTIONS_SIZE");
        assertTrue(LSSConstants.MAX_SEND_SECTIONS_SIZE < LSSConstants.MAX_SECTIONS_SIZE,
                "the client decode cap must stay above the send threshold so frames from"
                + " older servers (which sent up to the decode cap) remain readable");
    }

    // ---- ESTIMATED_COLUMN_OVERHEAD_BYTES vs real encoded headers ----

    @Test
    void estimatedColumnOverheadCoversRealVanillaHeaders() {
        // estimatedBytes() = sectionBytes.length + ESTIMATED_COLUMN_OVERHEAD_BYTES feeds the
        // bandwidth limiter; if the real header outgrows the constant, every column
        // under-counts and the limiter overshoots its caps.
        record Case(ResourceKey<Level> key, String dim) {}
        for (Case c : new Case[]{new Case(Level.OVERWORLD, "minecraft:overworld"),
                new Case(Level.NETHER, "minecraft:the_nether"),
                new Case(Level.END, "minecraft:the_end")}) {
            byte[] frame = encode(VoxelColumnS2CPayload.CODEC, new VoxelColumnS2CPayload(
                    Integer.MIN_VALUE, Integer.MAX_VALUE, c.key(), Long.MAX_VALUE, new byte[0]));
            assertTrue(frame.length <= LSSConstants.ESTIMATED_COLUMN_OVERHEAD_BYTES,
                    c.dim() + " header is " + frame.length + " bytes — raise"
                    + " ESTIMATED_COLUMN_OVERHEAD_BYTES so bandwidth accounting stays an"
                    + " upper bound for vanilla dimensions");
        }
        // Worst case includes the 4-byte section length prefix at MAX_SECTIONS_SIZE with
        // the longest vanilla dimension string.
        byte[] big = encode(VoxelColumnS2CPayload.CODEC, new VoxelColumnS2CPayload(
                0, 0, Level.NETHER, 0L, new byte[LSSConstants.MAX_SECTIONS_SIZE]));
        assertTrue(big.length - LSSConstants.MAX_SECTIONS_SIZE
                        <= LSSConstants.ESTIMATED_COLUMN_OVERHEAD_BYTES,
                "max-size column overhead is " + (big.length - LSSConstants.MAX_SECTIONS_SIZE)
                + " bytes — raise ESTIMATED_COLUMN_OVERHEAD_BYTES");
    }

    // ---- Channel ids + protocol version literals ----

    @Test
    void channelConstantsParseDistinctUnderTheLssNamespace() {
        String[] channels = {
                LSSConstants.CHANNEL_HANDSHAKE, LSSConstants.CHANNEL_CHUNK_REQUEST,
                LSSConstants.CHANNEL_SESSION_CONFIG, LSSConstants.CHANNEL_DIRTY_COLUMNS,
                LSSConstants.CHANNEL_VOXEL_COLUMN, LSSConstants.CHANNEL_BATCH_RESPONSE};
        var distinct = new HashSet<String>();
        for (String channel : channels) {
            var id = Identifier.parse(channel); // throws on a typo'd channel string
            assertEquals(LSSConstants.MOD_ID, id.getNamespace(),
                    channel + " must live under the lss: namespace");
            distinct.add(id.toString());
        }
        assertEquals(6, distinct.size(), "channel ids must be pairwise distinct");
    }

    @Test
    void protocolVersionIsPinnedLiterally() {
        // Every other test references the constant symbolically, so a silent bump fails
        // nothing else. Bump this literal only together with a deliberate wire change, a
        // matching bump in release notes, and a review of both platforms' codecs.
        // 16 -> 17: the declarative want-set model. The client now declares its whole
        // unsatisfied set every scan and the server replaces its backlog wholesale, so the
        // rate-limited bounce (response byte 0) is gone from the wire — a v16 client would
        // drip-feed against a server that no longer answers bounces. Byte 0 stays RESERVED.
        assertEquals(18, LSSConstants.PROTOCOL_VERSION); // 18: VoxelColumn serve-source byte
    }

    @Test
    void payloadTypesBindTheDeclaredChannels() {
        assertEquals(LSSConstants.CHANNEL_HANDSHAKE,
                HandshakeC2SPayload.TYPE.id().toString());
        assertEquals(LSSConstants.CHANNEL_CHUNK_REQUEST,
                BatchChunkRequestC2SPayload.TYPE.id().toString());
        assertEquals(LSSConstants.CHANNEL_SESSION_CONFIG,
                SessionConfigS2CPayload.TYPE.id().toString());
        assertEquals(LSSConstants.CHANNEL_DIRTY_COLUMNS,
                DirtyColumnsS2CPayload.TYPE.id().toString());
        assertEquals(LSSConstants.CHANNEL_VOXEL_COLUMN,
                VoxelColumnS2CPayload.TYPE.id().toString());
        assertEquals(LSSConstants.CHANNEL_BATCH_RESPONSE,
                BatchResponseS2CPayload.TYPE.id().toString());
    }
}
