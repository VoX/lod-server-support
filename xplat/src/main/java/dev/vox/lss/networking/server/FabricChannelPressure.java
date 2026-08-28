package dev.vox.lss.networking.server;

import dev.vox.lss.common.LSSLogger;
import dev.vox.lss.common.processing.ChannelPressureProbe;
import dev.vox.lss.common.processing.OutboundBufferMath;
import dev.vox.lss.mixin.AccessorConnection;
import dev.vox.lss.mixin.AccessorServerCommonPacketListener;
import io.netty.channel.Channel;
import net.minecraft.server.level.ServerPlayer;

/**
 * Fabric adapter for the per-player outbound-buffer gauge: player → packet listener →
 * {@code Connection} → netty {@code Channel}, then
 * {@link OutboundBufferMath#pendingBytes}. See
 * docs/planning/elytra-chunk-wall-investigation-2026-08-01.md §8.3.
 *
 * <p>Every failure shape degrades to no-signal, warned once. A diagnostic must never be
 * able to take a server down, and a no-signal probe leaves the deference gate inert — the
 * same fail-safe direction as {@code backgroundIncompatible} on the read path.
 *
 * <p>Public since the probe-snapshot refactor (mega plan R-2): the move-desync tracer
 * reads envelope {@code obuf} for EVERY player — including unregistered/LSS-disabled
 * control arms — so it builds its own probe here instead of going through a request
 * state that may not exist.
 */
public final class FabricChannelPressure {

    private static volatile boolean warned;

    private FabricChannelPressure() {}

    /** A probe bound to one player. Re-reads the channel each call — connections are
     *  replaced on reconnect and the field is assigned on the netty thread. */
    public static ChannelPressureProbe forPlayer(ServerPlayer player) {
        return new ChannelPressureProbe() {
            @Override
            public long pendingOutboundBytes() {
                // The whole body is inside the catch (review A-3): the class doc's "must
                // never throw" contract covers the netty reads too, not just resolution —
                // flushSendQueue's belt catches only Exception, and a LinkageError from a
                // drifted netty ABI must not take down every later player's flush.
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
                    // A-4): a channel closing between two reads of the same flag must
                    // not produce a snapshot asserting writability over a dead channel.
                    boolean active = channel.isActive();
                    boolean writable = channel.isWritable();
                    long pending = OutboundBufferMath.pendingBytes(
                            active, writable,
                            channel.bytesBeforeUnwritable(), channel.bytesBeforeWritable(),
                            config.getWriteBufferHighWaterMark(), config.getWriteBufferLowWaterMark());
                    if (!active) {
                        // A closed channel has no meaningful writability — and yielding to
                        // a corpse would strand the queue until disconnect sweeps it.
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
            var connection = ((AccessorServerCommonPacketListener) listener).lss$getConnection();
            if (connection == null) return null;
            return ((AccessorConnection) connection).lss$getChannel();
        } catch (Throwable t) {
            // ClassCastException here means the accessor mixins did not apply (a future
            // MC renaming either field) — latch a warning and stay silent thereafter.
            if (!warned) {
                warned = true;
                LSSLogger.warn("Outbound-buffer gauge unavailable (" + t + ") —"
                        + " /" + dev.vox.lss.common.Brand.serverCommand() + " diag will show obuf=n/a and transport deference stays inert");
            }
            return null;
        }
    }
}
