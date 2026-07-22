package dev.vox.lss.networking.payloads;

import dev.vox.lss.common.LSSConstants;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Four fields since the server-owned-generation fold into v17: both per-player concurrency
 * caps left the wire — they are server-internal admission limiters, and no client budget
 * derives from them (the scanner's want-set budget is the WANT_SET_BUDGET constant).
 *
 * <p><b>v16 compat:</b> a legacy protocol-16 session ({@code enableV16Compat}) is replied
 * to with {@link #v16Legacy}, which encodes the OLD 6-field layout — version echoing 16
 * (the v0.6.2 client's codec hard-gates on that leading VarInt and disables itself
 * otherwise) with the two concurrency-cap VarInts restored between lodDistance and
 * generationEnabled. The legacy components are server-encode-only: the read side is
 * v18-only and always produces {@code v16Wire=false}, and when the flag is off the encode
 * is byte-identical to the pre-compat 4-field layout (pinned by the wire tests).
 */
public record SessionConfigS2CPayload(
        int protocolVersion,
        boolean enabled,
        int lodDistanceChunks,
        boolean generationEnabled,
        int legacySyncCap,
        int legacyGenCap,
        boolean v16Wire
) implements CustomPacketPayload {

    /** Canonical current-protocol shape (the only one the client ever decodes). */
    public SessionConfigS2CPayload(int protocolVersion, boolean enabled,
                                   int lodDistanceChunks, boolean generationEnabled) {
        this(protocolVersion, enabled, lodDistanceChunks, generationEnabled, 0, 0, false);
    }

    /** The v16 compat reply: 6-field legacy layout echoing protocol version 16. The caps are
     *  the client's PACING (in-flight ceilings and scan budget = syncCap x 4), so callers pass
     *  the server's real admission values. */
    public static SessionConfigS2CPayload v16Legacy(boolean enabled, int lodDistanceChunks,
                                                    int syncCap, int genCap,
                                                    boolean generationEnabled) {
        return new SessionConfigS2CPayload(LSSConstants.V16_COMPAT_PROTOCOL_VERSION, enabled,
                lodDistanceChunks, generationEnabled, syncCap, genCap, true);
    }

    public static final CustomPacketPayload.Type<SessionConfigS2CPayload> TYPE =
            new CustomPacketPayload.Type<>(Identifier.parse(LSSConstants.CHANNEL_SESSION_CONFIG));

    public static final StreamCodec<FriendlyByteBuf, SessionConfigS2CPayload> CODEC =
            StreamCodec.of(
                    (buf, payload) -> {
                        buf.writeVarInt(payload.protocolVersion);
                        buf.writeBoolean(payload.enabled);
                        buf.writeVarInt(payload.lodDistanceChunks);
                        if (payload.v16Wire) {
                            // Legacy 6-field layout: the two caps sit between lodDistance
                            // and generationEnabled (v0.6.2 SessionConfigS2CPayload order).
                            buf.writeVarInt(payload.legacySyncCap);
                            buf.writeVarInt(payload.legacyGenCap);
                        }
                        buf.writeBoolean(payload.generationEnabled);
                    },
                    buf -> {
                        int version = buf.readVarInt();
                        // Establish (on the netty decode thread, before any column decodes)
                        // whether the SUBSEQUENT VoxelColumn frames omit the source byte —
                        // true only for a v16 server. See V16ClientWire.
                        V16ClientWire.observeSessionConfigVersion(version);
                        if (version == LSSConstants.PROTOCOL_VERSION) {
                            boolean enabled = buf.readBoolean();
                            int lodDist = buf.readVarInt();
                            boolean genEnabled = buf.readBoolean();
                            return new SessionConfigS2CPayload(version, enabled, lodDist, genEnabled);
                        }
                        if (version == LSSConstants.V16_COMPAT_PROTOCOL_VERSION) {
                            // Client v16 backward compat: an old server's 6-field layout — the
                            // two concurrency-cap VarInts sit between lodDistance and
                            // generationEnabled (the exact mirror of the v16Legacy ENCODE
                            // above). The gate accepts version 16 only when
                            // enableV16ServerCompat is on; we only ever RECEIVE this by having
                            // announced 16 ourselves, so a v18-only client never reaches here.
                            boolean enabled = buf.readBoolean();
                            int lodDist = buf.readVarInt();
                            int syncCap = buf.readVarInt();
                            int genCap = buf.readVarInt();
                            boolean genEnabled = buf.readBoolean();
                            return SessionConfigS2CPayload.v16Legacy(enabled, lodDist, syncCap,
                                    genCap, genEnabled);
                        }
                        // Foreign layout (e.g. a v15 server's 10-field config). The version
                        // VarInt is the first field in every layout, so drain the rest to
                        // avoid a trailing-bytes decoder kick and let the handler's version
                        // gate log the mismatch and disable LSS.
                        buf.skipBytes(buf.readableBytes());
                        return new SessionConfigS2CPayload(version, false, 0, false);
                    }
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
