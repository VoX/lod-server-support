package dev.vox.lss.networking.server;

import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.common.LSSLogger;
import dev.vox.lss.common.net.ColumnFrame;
import dev.vox.lss.common.net.StoredDeflate;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import net.minecraft.network.CompressionEncoder;
import net.minecraft.network.VarInt;
import net.minecraft.server.level.ServerPlayer;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Sends a column packet whose payload is already a zstd frame without running Minecraft's
 * deflate over it again.
 *
 * <p>Vanilla compresses every packet at or above the threshold with zlib at its default
 * level. For a zstd frame that is all cost and no gain: measured 0.054 ms a column, 85 % of
 * the network thread in a warm fill, and an output slightly larger than the input. This
 * handler sits just in front of the compressor and writes such a packet as what the
 * compressor would have written, a length and a zlib stream, except that the stream stores
 * the bytes instead of deflating them ({@link StoredDeflate}). Every inflater reads that,
 * and the frame keeps its true uncompressed length, which is what Velocity and Krypton
 * check; a frame marked "not compressed" would not pass them at this size.
 *
 * <p>It acts only when the handler named {@code compress} is vanilla's
 * {@link CompressionEncoder}; a stack that replaced the compressor is left alone. Anything
 * that is not a compressed column takes the normal path untouched, and so does a column
 * whose frame could not be built. {@code -Dlss.skipDoubleCompression=false} turns it off.
 */
public final class ColumnPacketPassthrough extends ChannelOutboundHandlerAdapter {
    static final String NAME = "lss_column_passthrough";
    static final String COMPRESS = "compress";
    private static final boolean ENABLED =
            !"false".equalsIgnoreCase(System.getProperty("lss.skipDoubleCompression"));
    /** The protocol's packet size limit (CompressionEncoder refuses more). */
    private static final int MAX_PACKET_BYTES = 8_388_608;
    /** The identifier as it follows the one-byte packet id: its length, then its bytes. */
    private static final byte[] CHANNEL =
            LSSConstants.CHANNEL_VOXEL_COLUMN.getBytes(StandardCharsets.US_ASCII);

    private static final AtomicLong PACKETS = new AtomicLong();
    private static final AtomicLong BYTES = new AtomicLong();
    private static final AtomicLong FALLBACKS = new AtomicLong();

    /** Column packets sent without a second compression since start. */
    public static long packets() {
        return PACKETS.get();
    }

    /** Their payload bytes. */
    public static long bytes() {
        return BYTES.get();
    }

    /** Frames that could not be built and went the normal way (expected: 0). */
    public static long fallbacks() {
        return FALLBACKS.get();
    }

    /** The diag line, or null while nothing was sent this way. */
    public static String diagLineOrNull() {
        long packets = PACKETS.get();
        if (packets == 0 && FALLBACKS.get() == 0) {
            return null;
        }
        return "Compression: " + packets + " column packets sent without a second compression"
                + (FALLBACKS.get() > 0 ? ", " + FALLBACKS.get() + " fell back" : "");
    }

    /** Puts the handler on the player's connection if it has vanilla's compressor. Safe to
     *  call again: a connection gets one handler. */
    public static void install(ServerPlayer player) {
        if (!ENABLED) {
            return;
        }
        try {
            Channel channel = FabricChannelPressure.channel(player);
            if (channel != null) {
                channel.eventLoop().execute(() -> installOn(channel));
            }
        } catch (Throwable t) {
            LSSLogger.debug("Column passthrough not installed: " + t);
        }
    }

    /** The event-loop half of {@link #install}; package-visible for the test. */
    static boolean installOn(Channel channel) {
        try {
            var pipeline = channel.pipeline();
            if (pipeline.get(NAME) != null || !(pipeline.get(COMPRESS) instanceof CompressionEncoder)) {
                return false;
            }
            // After "compress" in pipeline order is before it on the way out: the packet
            // has been encoded (and rewritten by anything that sits behind the encoder).
            pipeline.addAfter(COMPRESS, NAME, new ColumnPacketPassthrough());
            return true;
        } catch (Throwable t) {
            LSSLogger.debug("Column passthrough not installed: " + t);
            return false;
        }
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        if (msg instanceof ByteBuf packet) {
            // Looked up for every write: compression can be reconfigured or removed
            // while the connection lives, and a frame only makes sense in front of the
            // compressor it stands in for.
            ChannelHandlerContext compress = ctx.pipeline().context(COMPRESS);
            if (compress != null && compress.handler() instanceof CompressionEncoder encoder) {
                int length = packet.readableBytes();
                if (length >= encoder.getThreshold() && length <= MAX_PACKET_BYTES
                        && startsLikeColumn(packet, length) && isCompressedColumn(packet, length)) {
                    ByteBuf framed = frame(ctx, packet, length);
                    if (framed != null) {
                        packet.release();
                        PACKETS.incrementAndGet();
                        BYTES.addAndGet(length);
                        // The compressor's context: the write continues at the handler
                        // behind it, the length prepender.
                        compress.write(framed, promise);
                        return;
                    }
                    FALLBACKS.incrementAndGet();
                }
            }
        }
        ctx.write(msg, promise);
    }

    /** The cheap part of the test, on the buffer itself: every outgoing packet at or
     *  above the threshold comes through here, and most are not ours. */
    private static boolean startsLikeColumn(ByteBuf packet, int length) {
        int at = packet.readerIndex();
        if (length < 2 + CHANNEL.length || packet.getByte(at) < 0
                || packet.getByte(at + 1) != CHANNEL.length) {
            return false;
        }
        for (int i = 0; i < CHANNEL.length; i++) {
            if (packet.getByte(at + 2 + i) != CHANNEL[i]) {
                return false;
            }
        }
        return true;
    }

    private static boolean isCompressedColumn(ByteBuf packet, int length) {
        try {
            return ColumnFrame.isCompressedColumn(packet.nioBuffer(packet.readerIndex(), length));
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** {@code VarInt(length)} and the stored stream, or null if it could not be built (the
     *  packet is untouched then). */
    private static ByteBuf frame(ChannelHandlerContext ctx, ByteBuf packet, int length) {
        ByteBuf out = null;
        try {
            int stream = StoredDeflate.streamLength(length);
            out = ctx.alloc().buffer(VarInt.getByteSize(length) + stream);
            VarInt.write(out, length);
            ByteBuffer source = packet.nioBuffer(packet.readerIndex(), length);
            int at = out.writerIndex();
            ByteBuffer target = out.internalNioBuffer(at, stream);
            StoredDeflate.write(source, target);
            out.writerIndex(at + stream);
            return out;
        } catch (Throwable t) {
            if (out != null) {
                out.release();
            }
            LSSLogger.debug("Column passthrough frame failed, sending the normal way: " + t);
            return null;
        }
    }
}
