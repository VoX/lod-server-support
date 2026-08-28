package dev.vox.lss.paper;

import dev.vox.lss.common.LSSLogger;
import dev.vox.lss.common.processing.ChannelPressureProbe;
import dev.vox.lss.common.processing.OutboundBufferMath;
import io.netty.channel.Channel;
import net.minecraft.network.Connection;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;

import java.lang.reflect.Field;

/**
 * Paper adapter for the per-player outbound-buffer gauge — the twin of Fabric's
 * {@code FabricChannelPressure}, using reflection where Fabric uses accessor mixins:
 * {@code ServerCommonPacketListenerImpl.connection} is {@code protected} and
 * {@code Connection.channel} is {@code private}, neither reachable from this package.
 *
 * <p>Resolution happens once per JVM behind a lazy holder (the
 * {@code MoonriseReadCompat} shape); any failure — a renamed field on a future NMS, a
 * module/access restriction — yields no-signal forever after one warning, which leaves the
 * gauge blank and the deference gate inert rather than misreporting.
 *
 * <p>Public since the probe-snapshot refactor (mega plan R-2), matching the Fabric twin's
 * factory widening — the combined shape is owned by the tracer PR even though only the
 * transport yield consumes {@code snapshot()} on Paper.
 */
public final class PaperChannelPressure {

    private PaperChannelPressure() {}

    /** Resolved once; both fields null when the shape could not be resolved. */
    private static final class Holder {
        static final Field CONNECTION;
        static final Field CHANNEL;

        static {
            Field connection = null;
            Field channel = null;
            try {
                connection = ServerCommonPacketListenerImpl.class.getDeclaredField("connection");
                connection.setAccessible(true);
                channel = Connection.class.getDeclaredField("channel");
                channel.setAccessible(true);
            } catch (Throwable t) {
                connection = null;
                channel = null;
                LSSLogger.warn("Outbound-buffer gauge unavailable (" + t + ") —"
                        + " /" + dev.vox.lss.common.Brand.serverCommand() + " diag will show obuf=n/a and transport deference stays inert");
            }
            CONNECTION = connection;
            CHANNEL = channel;
        }
    }

    public static ChannelPressureProbe forPlayer(ServerPlayer player) {
        if (Holder.CONNECTION == null || Holder.CHANNEL == null) {
            return ChannelPressureProbe.NO_SIGNAL;
        }
        return new ChannelPressureProbe() {
            @Override
            public long pendingOutboundBytes() {
                // Whole body inside the catch (review A-3), matching the Fabric twin.
                try {
                    var channel = channelOf(player);
                    if (channel == null) return OutboundBufferMath.NO_SIGNAL;
                    var config = channel.config();
                    return OutboundBufferMath.pendingBytes(
                            channel.isActive(), channel.isWritable(),
                            channel.bytesBeforeUnwritable(), channel.bytesBeforeWritable(),
                            config.getWriteBufferHighWaterMark(), config.getWriteBufferLowWaterMark());
                } catch (Throwable t) {
                    return OutboundBufferMath.NO_SIGNAL;
                }
            }

            @Override
            public Snapshot snapshot() {
                try {
                    var channel = channelOf(player);
                    if (channel == null) {
                        return new Snapshot(OutboundBufferMath.NO_SIGNAL, Snapshot.UNKNOWN_MARK,
                                Writability.UNKNOWN);
                    }
                    var config = channel.config();
                    // isActive/isWritable each read exactly ONCE per snapshot (review
                    // A-4), matching the Fabric twin.
                    boolean active = channel.isActive();
                    boolean writable = channel.isWritable();
                    long pending = OutboundBufferMath.pendingBytes(
                            active, writable,
                            channel.bytesBeforeUnwritable(), channel.bytesBeforeWritable(),
                            config.getWriteBufferHighWaterMark(), config.getWriteBufferLowWaterMark());
                    if (!active) {
                        // A closed channel has no meaningful writability — yielding to a
                        // corpse would strand the queue until disconnect sweeps it.
                        return new Snapshot(pending, Snapshot.UNKNOWN_MARK, Writability.UNKNOWN);
                    }
                    return new Snapshot(pending, config.getWriteBufferHighWaterMark(),
                            writable ? Writability.WRITABLE : Writability.NOT_WRITABLE);
                } catch (Throwable t) {
                    return new Snapshot(OutboundBufferMath.NO_SIGNAL, Snapshot.UNKNOWN_MARK,
                            Writability.UNKNOWN);
                }
            }
        };
    }

    /** Channel resolution shared by both reads; null on any failure shape. */
    private static Channel channelOf(ServerPlayer player) {
        try {
            var listener = player.connection;
            if (listener == null) return null;
            var connection = (Connection) Holder.CONNECTION.get(listener);
            if (connection == null) return null;
            return (Channel) Holder.CHANNEL.get(connection);
        } catch (Throwable t) {
            return null;
        }
    }
}
