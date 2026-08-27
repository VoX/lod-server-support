package dev.vox.lss.paper;

import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.common.LSSLogger;
import dev.vox.lss.common.PositionUtil;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.DiscardedPayload;
import net.minecraft.resources.ResourceLocation;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;

import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Encodes S2C payloads and decodes C2S payloads using the same wire format as Fabric.
 *
 * S2C packets are sent directly via NMS using {@link DiscardedPayload} to wrap
 * raw bytes in a {@link ClientboundCustomPayloadPacket}. This bypasses Bukkit's
 * {@code sendPluginMessage()} which silently drops messages when the client hasn't
 * registered the channel via {@code minecraft:register} — a common issue with
 * Fabric clients connecting to Paper servers in 1.20.5+.
 */
public final class PaperPayloadHandler {
    private PaperPayloadHandler() {}

    // Cached ResourceLocation instances for constant channel strings
    private static final ResourceLocation ID_SESSION_CONFIG = ResourceLocation.parse(LSSConstants.CHANNEL_SESSION_CONFIG);
    private static final ResourceLocation ID_DIRTY_COLUMNS = ResourceLocation.parse(LSSConstants.CHANNEL_DIRTY_COLUMNS);
    static final ResourceLocation ID_VOXEL_COLUMN = ResourceLocation.parse(LSSConstants.CHANNEL_VOXEL_COLUMN);
    private static final ResourceLocation ID_BATCH_RESPONSE = ResourceLocation.parse(LSSConstants.CHANNEL_BATCH_RESPONSE);
    private static final ResourceLocation ID_REGION_SUMMARY = ResourceLocation.parse(LSSConstants.CHANNEL_REGION_SUMMARY);
    private static final ResourceLocation ID_COL_STAMPS = ResourceLocation.parse(LSSConstants.CHANNEL_COL_STAMPS);

    // ---- S2C Encoding ----

    /** Four fields since the server-owned-generation fold into v17: both per-player
     *  concurrency caps left the wire (server-internal admission limiters only). */
    public static byte[] encodeSessionConfig(int protocolVersion, boolean enabled,
                                             int lodDistanceChunks,
                                             boolean generationEnabled) {
        return encodeToBytes(buf -> {
            buf.writeVarInt(protocolVersion);
            buf.writeBoolean(enabled);
            buf.writeVarInt(lodDistanceChunks);
            buf.writeBoolean(generationEnabled);
            if (protocolVersion == LSSConstants.PROTOCOL_VERSION) {
                // v20-only append (XVER §2.2): the client branches per-frame on the
                // leading version, so only the version-20 arm reads this — the v19/v18
                // echoes must stay 4-field or their strict clients hard-kick.
                buf.writeVarInt(net.minecraft.SharedConstants.getCurrentVersion()
                        .dataVersion().version());
            }
        });
    }

    /** The lss:client_info sidecar's payload: one VarInt data version; trailing bytes
     *  tolerated (a future client may append — the SessionConfig foreign-arm stance). */
    public static int decodeClientInfo(byte[] data) {
        return withReadBuffer(data, FriendlyByteBuf::readVarInt);
    }

    public static void sendSessionConfig(Player player,
                                          int protocolVersion, boolean enabled,
                                          int lodDistanceChunks,
                                          boolean generationEnabled) {
        sendRawNmsPayload(player, ID_SESSION_CONFIG, encodeSessionConfig(
                protocolVersion, enabled, lodDistanceChunks, generationEnabled));
    }

    /** v16 compat reply: the OLD 6-field layout echoing protocol version 16 — the v0.6.2
     *  client's codec hard-gates on that leading VarInt and disables itself otherwise. The
     *  two cap VarInts sit between lodDistance and generationEnabled (v0.6.2 field order)
     *  and ARE the old client's pacing, so callers pass the server's real admission values. */
    public static byte[] encodeSessionConfigV16(boolean enabled, int lodDistanceChunks,
                                                int syncCap, int genCap,
                                                boolean generationEnabled) {
        return encodeToBytes(buf -> {
            buf.writeVarInt(LSSConstants.V16_COMPAT_PROTOCOL_VERSION);
            buf.writeBoolean(enabled);
            buf.writeVarInt(lodDistanceChunks);
            buf.writeVarInt(syncCap);
            buf.writeVarInt(genCap);
            buf.writeBoolean(generationEnabled);
        });
    }

    public static void sendSessionConfigV16(Player player, boolean enabled,
                                            int lodDistanceChunks, int syncCap, int genCap,
                                            boolean generationEnabled) {
        sendRawNmsPayload(player, ID_SESSION_CONFIG, encodeSessionConfigV16(
                enabled, lodDistanceChunks, syncCap, genCap, generationEnabled));
    }

    public static byte[] encodeBatchResponse(byte[] responseTypes, long[] packedPositions, int count) {
        // Exact size up front (1 byte type + 8 byte position per entry, +5 for the count
        // VarInt): a full broadcast frame is ~37 KB, which from the default 256 bytes forces
        // ~8 doubling reallocations + copies every flush tick.
        return encodeToBytes(count * 9 + 5, buf -> {
            buf.writeVarInt(count);
            for (int i = 0; i < count; i++) {
                buf.writeByte(responseTypes[i]);
                buf.writeLong(packedPositions[i]);
            }
        });
    }

    public static void sendBatchResponse(Player player, byte[] responseTypes, long[] packedPositions, int count) {
        sendRawNmsPayload(player, ID_BATCH_RESPONSE, encodeBatchResponse(responseTypes, packedPositions, count));
    }

    /**
     * Encode a column payload with serialized section bytes.
     * Writes the per-request header, then writes sectionBytes as a length-prefixed byte array.
     */
    /** Source-less convenience (tests/legacy rigs): source -1 = "unknown", a legal wire
     *  value. Production always passes a COLUMN_SOURCE_* tag. */
    public static byte[] encodeVoxelColumnPreEncoded(int chunkX, int chunkZ,
                                                      String dimensionStr, long columnTimestamp,
                                                      byte[] sectionBytes) {
        return encodeVoxelColumnPreEncoded(chunkX, chunkZ, dimensionStr, columnTimestamp,
                (byte) -1, sectionBytes);
    }

    /** Raw-shipping overload (codec 0) — kept so pre-19 call sites and test rigs encode a
     *  valid CURRENT-layout frame (the codec byte is version-carried, never optional). */
    public static byte[] encodeVoxelColumnPreEncoded(int chunkX, int chunkZ,
                                                      String dimensionStr, long columnTimestamp,
                                                      byte source, byte[] sectionBytes) {
        return encodeVoxelColumnPreEncoded(chunkX, chunkZ, dimensionStr, columnTimestamp,
                source, LSSConstants.COLUMN_CODEC_RAW, sectionBytes);
    }

    /** Full v19 layout: {@code sectionBytes} are the SHIPPED bytes for the given codec
     *  (raw for {@code COLUMN_CODEC_RAW}, a zstd-1 frame for {@code COLUMN_CODEC_ZSTD}).
     *  Byte-identical to Fabric's {@code VoxelColumnS2CPayload} encode (wire-parity
     *  fixtures pin it). */
    public static byte[] encodeVoxelColumnPreEncoded(int chunkX, int chunkZ,
                                                      String dimensionStr, long columnTimestamp,
                                                      byte source, byte codec, byte[] sectionBytes) {
        return encodeToBytes(sectionBytes.length + 64, buf -> {
            buf.writeInt(chunkX);
            buf.writeInt(chunkZ);
            buf.writeUtf(dimensionStr, LSSConstants.MAX_DIMENSION_STRING_LENGTH);
            buf.writeLong(columnTimestamp);
            buf.writeByte(source);
            buf.writeByte(codec);
            buf.writeByteArray(sectionBytes);
        });
    }

    /**
     * v16 compat: splice a CURRENT column frame (serve-source tag + codec tag between
     * columnTimestamp and sectionBytes — one byte each) into the legacy pre-18 layout by
     * removing exactly those two bytes. Parses only the fixed header to find the offset —
     * the section bytes are copied, never re-encoded. A CURRENT-shaped frame reaching a
     * v16 client hard-kicks it (the old decode reads the source byte as the section-array
     * length VarInt), so the per-player column egress converts UNCONDITIONALLY for v16
     * sessions. THROWS on a non-RAW codec (plan review A6): the legacy layout has nowhere
     * to carry a codec and a spliced zstd body decodes as garbage on the old client.
     * Reachable in the v19->v16 downgrade window (queued codec-1 payloads draining after
     * the manager flips — 4-agent round, pipeline F2); the egress guard's warn-drop
     * contains it and the ts<=0 re-declaration heals the dropped column.
     */
    public static byte[] rewriteColumnToV16(byte[] frame) {
        return withReadBuffer(frame, buf -> {
            buf.readInt();                                            // chunkX
            buf.readInt();                                            // chunkZ
            buf.readUtf(LSSConstants.MAX_DIMENSION_STRING_LENGTH);    // dimension
            buf.readLong();                                           // columnTimestamp
            int sourceIndex = buf.readerIndex();
            byte codec = frame[sourceIndex + 1];
            if (codec != LSSConstants.COLUMN_CODEC_RAW) {
                throw new IllegalStateException("v16 splice on codec-" + codec
                        + " column frame — the session flag should have forced raw");
            }
            byte[] out = new byte[frame.length - 2];
            System.arraycopy(frame, 0, out, 0, sourceIndex);
            System.arraycopy(frame, sourceIndex + 2, out, sourceIndex,
                    frame.length - sourceIndex - 2);
            return out;
        });
    }

    /**
     * v18 compat (docs/planning/v18-compat-design.md §2.6): splice a CURRENT column frame
     * into the protocol-18 layout by removing exactly the codec byte — the source byte
     * stays, verbatim (unknown source values incl. 3/store pass through under the
     * forward-safety rule). A CURRENT-shaped frame reaching a v18 client hard-kicks it
     * (its decode reads the byte after the source as the section-array length VarInt), so
     * the per-player column egress converts UNCONDITIONALLY for v18 sessions. THROWS on a
     * non-RAW codec, like the v16 splice: the v18 layout has nowhere to carry a codec and
     * a spliced zstd body decodes as garbage on the old client. Reachable in the same
     * cross-dialect downgrade window; the egress guard's warn-drop contains it and the
     * client's re-declaration heals the dropped column.
     */
    public static byte[] rewriteColumnToV18(byte[] frame) {
        return withReadBuffer(frame, buf -> {
            buf.readInt();                                            // chunkX
            buf.readInt();                                            // chunkZ
            buf.readUtf(LSSConstants.MAX_DIMENSION_STRING_LENGTH);    // dimension
            buf.readLong();                                           // columnTimestamp
            int sourceIndex = buf.readerIndex();
            byte codec = frame[sourceIndex + 1];
            if (codec != LSSConstants.COLUMN_CODEC_RAW) {
                throw new IllegalStateException("v18 splice on codec-" + codec
                        + " column frame — the session flag should have forced raw");
            }
            int codecIndex = sourceIndex + 1;
            byte[] out = new byte[frame.length - 1];
            System.arraycopy(frame, 0, out, 0, codecIndex);
            System.arraycopy(frame, codecIndex + 1, out, codecIndex,
                    frame.length - codecIndex - 1);
            return out;
        });
    }

    /** The packed chunk position of an encoded column frame (its first 8 bytes are the two
     *  big-endian coordinate ints) — the v16 shim's prune key at the column egress. */
    public static long readColumnPackedPos(byte[] frame) {
        return withReadBuffer(frame, buf -> PositionUtil.packPosition(buf.readInt(), buf.readInt()));
    }

    /**
     * Encode a DirtyColumnsS2CPayload. Wire format: VarInt length + long[] positions.
     * Identical to Fabric's DirtyColumnsS2CPayload.CODEC.
     */
    public static byte[] encodeDirtyColumns(long[] dirtyPositions) {
        int len = Math.min(dirtyPositions.length, LSSConstants.MAX_DIRTY_COLUMN_POSITIONS);
        // Exact size up front (8 bytes per position, +5 for the count VarInt): a full frame is
        // ~82 KB, otherwise grown from 256 bytes by repeated doubling+copy every broadcast.
        return encodeToBytes(len * 8 + 5, buf -> {
            buf.writeVarInt(len);
            for (int i = 0; i < len; i++) {
                buf.writeLong(dirtyPositions[i]);
            }
        });
    }

    public static void sendDirtyColumns(Player player, long[] dirtyPositions) {
        byte[] data = encodeDirtyColumns(dirtyPositions);
        sendRawNmsPayload(player, ID_DIRTY_COLUMNS, data);
    }

    // Malformed-C2S log guard (log-sweep top finding): these warns fire on
    // CLIENT-SUPPLIED bytes at packet rate and returned null WITHOUT throwing, so they
    // bypassed the plugin's hostile-frame throttle (which only covers the exception
    // path) — a cheap remote log-flood vector. One aggregated line per minute, max.
    private static final dev.vox.lss.common.LogThrottle MALFORMED_C2S_WARN =
            new dev.vox.lss.common.LogThrottle(60_000);

    private static void warnMalformed(String what) {
        long n = MALFORMED_C2S_WARN.recordAndTryAcquire(System.nanoTime() / 1_000_000);
        if (n > 0) {
            LSSLogger.warn(what + " (" + n + " malformed C2S frame(s) since the last"
                    + " report; contained — malformed frames are dropped)");
        }
    }

    // ---- C2S Decoding ----

    public record DecodedHandshake(int protocolVersion, int capabilities) {}

    /** The C2S handshake frame's ENCODE twin — the grant sweep's replay reconstructs
     *  the remembered handshake with it (service-permission-gate-plan.md §2.3); pinned
     *  round-trip-equal to {@link #decodeHandshake}. */
    public static byte[] encodeHandshakeFrame(int protocolVersion, int capabilities) {
        var buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        try {
            buf.writeVarInt(protocolVersion);
            buf.writeVarInt(capabilities);
            byte[] out = new byte[buf.readableBytes()];
            buf.readBytes(out);
            return out;
        } finally {
            buf.release();
        }
    }

    public static DecodedHandshake decodeHandshake(byte[] data) {
        if (data == null || data.length == 0) {
            warnMalformed("Received empty handshake payload");
            return null;
        }
        return withReadBuffer(data, buf -> {
            int version = buf.readVarInt();
            int caps = buf.isReadable() ? buf.readVarInt() : 0;
            return new DecodedHandshake(version, caps);
        });
    }

    public record DecodedBatchChunkRequest(long[] packedPositions, long[] clientTimestamps, int count) {}

    public static DecodedBatchChunkRequest decodeBatchChunkRequest(byte[] data) {
        if (data == null || data.length == 0) {
            warnMalformed("Received empty batch chunk request payload");
            return null;
        }
        return withReadBuffer(data, buf -> {
            int count = buf.readVarInt();
            if (count < 0 || count > LSSConstants.MAX_BATCH_CHUNK_REQUESTS) {
                warnMalformed("Batch chunk request count out of range: " + count);
                return null;
            }
            long[] packedPositions = new long[count];
            long[] clientTimestamps = new long[count];
            for (int i = 0; i < count; i++) {
                packedPositions[i] = buf.readLong();
                clientTimestamps[i] = buf.readLong();
            }
            return new DecodedBatchChunkRequest(packedPositions, clientTimestamps, count);
        });
    }

    // ---- Helpers ----

    private static <T> T withReadBuffer(byte[] data, Function<FriendlyByteBuf, T> fn) {
        var buf = new FriendlyByteBuf(Unpooled.wrappedBuffer(data));
        try {
            return fn.apply(buf);
        } finally {
            buf.release();
        }
    }

    private static byte[] encodeToBytes(Consumer<FriendlyByteBuf> writer) {
        return encodeToBytes(256, writer);
    }

    private static byte[] encodeToBytes(int initialCapacity, Consumer<FriendlyByteBuf> writer) {
        var buf = new FriendlyByteBuf(Unpooled.buffer(initialCapacity));
        try {
            writer.accept(buf);
            byte[] bytes = new byte[buf.readableBytes()];
            buf.readBytes(bytes);
            return bytes;
        } finally {
            buf.release();
        }
    }

    /**
     * Sends a pre-encoded payload directly via NMS for a given channel.
     * Used by the send queue flush in {@link PaperRequestProcessingService}.
     */
    public static void sendRawNmsPayload(Player player, ResourceLocation channelId, byte[] data) {
        var nmsPlayer = ((CraftPlayer) player).getHandle();
        if (nmsPlayer.connection == null) return;
        nmsPlayer.connection.send(new ClientboundCustomPayloadPacket(
                new DiscardedPayload(channelId, data)));
    }

    /** Region-summary S2C frame (P2 §5): the dedicated send lane's carrier — the raw
     *  RegionSummaryWire body on the NMS connection (DiscardedPayload, like every LSS
     *  S2C). Takes the NMS player directly — the pump looks players up by UUID.
     *  Returns whether the frame was actually handed to the connection — the summary
     *  counters mean "put on the wire", not "assembled". */
    public static boolean sendRegionSummary(net.minecraft.server.level.ServerPlayer nmsPlayer,
                                            byte[] body) {
        if (nmsPlayer.connection == null) return false;
        nmsPlayer.connection.send(new ClientboundCustomPayloadPacket(
                new DiscardedPayload(ID_REGION_SUMMARY, body)));
        return true;
    }

    /** Column-stamps S2C frame (stamped-up-to-date-plan.md §3): the raw
     *  ColumnStampsWire body, same carrier discipline as the summary frame. */
    public static boolean sendColumnStamps(net.minecraft.server.level.ServerPlayer nmsPlayer,
                                           byte[] body) {
        if (nmsPlayer.connection == null) return false;
        nmsPlayer.connection.send(new ClientboundCustomPayloadPacket(
                new DiscardedPayload(ID_COL_STAMPS, body)));
        return true;
    }
}
