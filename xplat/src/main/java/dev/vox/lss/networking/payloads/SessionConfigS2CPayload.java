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
 * generationEnabled. The legacy components are server-encode-only: the read side decodes
 * only the current 4-field layout and always produces {@code v16Wire=false}, and when the
 * flag is off the encode is byte-identical to the pre-compat 4-field layout (pinned by
 * the wire tests). The v18 compat rung needs no shape of its own here — it is the SAME
 * 4-field encode with the version value 18 (the encoder never branches on the value).
 *
 * <p><b>Session flags</b> (map-policy plan A2): a trailing {@code sessionFlags} VarInt
 * after {@code serverDataVersion}, appended ONLY when {@code protocolVersion == 20 &&
 * !v16Wire && sessionFlags >= 0} — and {@link #forSession} makes {@code sessionFlags >= 0}
 * mean "the session declared {@code CAPABILITY_MAP_POLICY}". Both halves are hard
 * requirements: every 0.10.0–0.18.0 v20 client hard-kicks on a trailing byte, so a frame to
 * a session without the bit must stay byte-identical to 0.18.0; and a 19/18/16 echo must
 * never carry the field, or a 0.19 client's 19 arm would read the flags as the data
 * version. Production construction goes through {@link #forSession} (or
 * {@link #v16Legacy}) — a source census forbids the flag-less constructors there.
 */
public record SessionConfigS2CPayload(
        int protocolVersion,
        boolean enabled,
        int lodDistanceChunks,
        boolean generationEnabled,
        int legacySyncCap,
        int legacyGenCap,
        boolean v16Wire,
        int serverDataVersion,
        int sessionFlags
) implements CustomPacketPayload {

    /** Pre-session-flags canonical shape: no flags field ({@code SESSION_FLAGS_ABSENT}). */
    public SessionConfigS2CPayload(int protocolVersion, boolean enabled,
                                   int lodDistanceChunks, boolean generationEnabled,
                                   int legacySyncCap, int legacyGenCap, boolean v16Wire,
                                   int serverDataVersion) {
        this(protocolVersion, enabled, lodDistanceChunks, generationEnabled, legacySyncCap,
                legacyGenCap, v16Wire, serverDataVersion, LSSConstants.SESSION_FLAGS_ABSENT);
    }

    /** Test/decode convenience: the current-protocol shape WITHOUT the flags field. The
     *  data-version append rides ONLY version-20 frames (XVER §2.2): the v19/v18 echo
     *  reuses this ctor with their version value and the encoder omits the append —
     *  their strict 4-field clients would hard-kick on a trailing byte. Production sends
     *  use {@link #forSession}. */
    public SessionConfigS2CPayload(int protocolVersion, boolean enabled,
                                   int lodDistanceChunks, boolean generationEnabled,
                                   int serverDataVersion) {
        this(protocolVersion, enabled, lodDistanceChunks, generationEnabled, 0, 0, false,
                serverDataVersion, LSSConstants.SESSION_FLAGS_ABSENT);
    }

    /**
     * The production construction for every non-v16 send site (handshake reply, reload
     * re-push, revocation push, dimension-change push — map-policy plan A2's site table):
     * each passes the SESSION's capabilities and reads the map policy at send time. The
     * flags field exists only for a session that declared
     * {@link LSSConstants#CAPABILITY_MAP_POLICY}; the encoder additionally requires a
     * version-20 frame, so the 19/18 echoes never carry it.
     */
    public static SessionConfigS2CPayload forSession(int protocolVersion, boolean enabled,
                                                     int lodDistanceChunks,
                                                     boolean generationEnabled,
                                                     int serverDataVersion,
                                                     int sessionCapabilities,
                                                     boolean denyMapIngestion) {
        // The field exists only on a version-20 frame (the 19/18 echoes have no slot for
        // it), so the payload object mirrors exactly what the encoder sends.
        return new SessionConfigS2CPayload(protocolVersion, enabled, lodDistanceChunks,
                generationEnabled, 0, 0, false, serverDataVersion,
                protocolVersion == LSSConstants.PROTOCOL_VERSION
                        ? sessionFlagsFor(sessionCapabilities, denyMapIngestion)
                        : LSSConstants.SESSION_FLAGS_ABSENT);
    }

    /**
     * This answer with the reason it says "not here" (0.23.0 plan §3): one
     * {@code SESSION_FLAG_OFF_*} bit from {@code SessionOffReason.of}, or 0 for "not said".
     * Unchanged when the answer is enabled, when the frame has no flags field (a session that
     * did not declare {@code CAPABILITY_MAP_POLICY}, or an echo of an older version), or when
     * there is no reason: the bit only ever rides a frame that already carries flags, so a
     * client that would be kicked by a trailing byte never gets one.
     */
    public SessionConfigS2CPayload withOffReason(int offReason) {
        int reason = offReason & LSSConstants.SESSION_FLAG_OFF_REASONS;
        if (this.enabled || this.sessionFlags < 0 || reason == 0) return this;
        return new SessionConfigS2CPayload(this.protocolVersion, this.enabled, this.lodDistanceChunks,
                this.generationEnabled, this.legacySyncCap, this.legacyGenCap, this.v16Wire,
                this.serverDataVersion, this.sessionFlags | reason);
    }

    /** The flags value for a session: absent unless it declared the capability. */
    public static int sessionFlagsFor(int sessionCapabilities, boolean denyMapIngestion) {
        if ((sessionCapabilities & LSSConstants.CAPABILITY_MAP_POLICY) == 0) {
            return LSSConstants.SESSION_FLAGS_ABSENT;
        }
        return denyMapIngestion ? LSSConstants.SESSION_FLAG_DENY_MAP_INGESTION : 0;
    }

    /** Whether the encoder appends the flags field (shared with Paper's hand encoder's
     *  parity pin): version 20, not the v16 layout, flags present. */
    public static boolean appendsSessionFlags(int protocolVersion, boolean v16Wire,
                                              int sessionFlags) {
        return protocolVersion == LSSConstants.PROTOCOL_VERSION && !v16Wire
                && sessionFlags >= 0;
    }

    /** A copy with only the LOD distance replaced — every other component (the data
     *  version and the session flags included) carries over. The client's protocol-bounds
     *  clamp uses this; a four-field rebuild silently dropped the appended fields. */
    public SessionConfigS2CPayload withLodDistanceChunks(int lodDistance) {
        return new SessionConfigS2CPayload(this.protocolVersion, this.enabled, lodDistance,
                this.generationEnabled, this.legacySyncCap, this.legacyGenCap, this.v16Wire,
                this.serverDataVersion, this.sessionFlags);
    }

    /** Test/legacy convenience: no data version (encodes without the append for any
     *  non-20 version anyway; a version-20 frame built this way appends 0). */
    public SessionConfigS2CPayload(int protocolVersion, boolean enabled,
                                   int lodDistanceChunks, boolean generationEnabled) {
        this(protocolVersion, enabled, lodDistanceChunks, generationEnabled, 0);
    }

    /** The v16 compat reply: 6-field legacy layout echoing protocol version 16. The caps are
     *  the client's PACING (in-flight ceilings and scan budget = syncCap x 4), so callers pass
     *  the server's real admission values. */
    public static SessionConfigS2CPayload v16Legacy(boolean enabled, int lodDistanceChunks,
                                                    int syncCap, int genCap,
                                                    boolean generationEnabled) {
        return new SessionConfigS2CPayload(LSSConstants.V16_COMPAT_PROTOCOL_VERSION, enabled,
                lodDistanceChunks, generationEnabled, syncCap, genCap, true, 0);
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
                        if (payload.protocolVersion == LSSConstants.PROTOCOL_VERSION
                                && !payload.v16Wire) {
                            // v20-only append (XVER §2.2): the client codec branches
                            // per-frame on the leading version, so ONLY the version-20
                            // arm reads this — the v19/v18 echoes must stay 4-field.
                            buf.writeVarInt(payload.serverDataVersion);
                        }
                        if (appendsSessionFlags(payload.protocolVersion, payload.v16Wire,
                                payload.sessionFlags)) {
                            // Map-policy plan A2: only to a session that declared the
                            // capability — a 0.10.0–0.18.0 client hard-kicks on this byte.
                            buf.writeVarInt(payload.sessionFlags);
                        }
                    },
                    buf -> {
                        int version = buf.readVarInt();
                        // Establish (on the netty decode thread, before any column decodes)
                        // whether the SUBSEQUENT VoxelColumn frames omit the source byte —
                        // true only for a v16 server. See V16ClientWire.
                        V16ClientWire.observeSessionConfigVersion(version);
                        // The C3 ladder's 19 rung: a client whose CONNECTION announced 19
                        // (the ladder fallback, or the soak lever's initial rung) decodes
                        // the server's 19 echo exactly as a real v0.9.x client would — the
                        // same 4-field layout this arm reads (the data-version append is
                        // version-20-only, so isReadable() is false and it decodes 0).
                        // STICKY announce memory (review CRITICAL-1): the ladder's own 16
                        // rung firing 10 s later must not push a still-in-flight 19 echo
                        // to the foreign arm — that fabricated (19, enabled=false) and
                        // silently disabled LOD for the session. Gated on the client's own
                        // announce (mark-before-send causality, see V16ClientWire), so an
                        // unsolicited 19 frame still falls to the foreign arm below.
                        boolean announced19 = V16ClientWire.hasAnnounced19()
                                && version == LSSConstants.V19_COMPAT_PROTOCOL_VERSION;
                        if (version == LSSConstants.PROTOCOL_VERSION || announced19) {
                            boolean enabled = buf.readBoolean();
                            int lodDist = buf.readVarInt();
                            boolean genEnabled = buf.readBoolean();
                            // Tolerate absence (a same-version peer built pre-append —
                            // dev-window frames): absent decodes as 0 = unknown.
                            int dataVersion = buf.isReadable() ? buf.readVarInt() : 0;
                            int flags = LSSConstants.SESSION_FLAGS_ABSENT;
                            if (version == LSSConstants.PROTOCOL_VERSION) {
                                // Map-policy plan A2: the flags field when present (absent
                                // = "unchanged" — the session gate keeps the connection's
                                // value), then DRAIN anything further, so a future server
                                // append needs no new capability for 0.19+ clients. A
                                // negative VarInt is not a flags value: read as absent.
                                if (buf.isReadable()) {
                                    int raw = buf.readVarInt();
                                    flags = raw < 0 ? LSSConstants.SESSION_FLAGS_ABSENT : raw;
                                }
                                buf.skipBytes(buf.readableBytes());
                            }
                            return new SessionConfigS2CPayload(version, enabled, lodDist,
                                    genEnabled, 0, 0, false, dataVersion, flags);
                        }
                        if (version == LSSConstants.V16_COMPAT_PROTOCOL_VERSION) {
                            // Client v16 backward compat: an old server's 6-field layout — the
                            // two concurrency-cap VarInts sit between lodDistance and
                            // generationEnabled (the exact mirror of the v16Legacy ENCODE
                            // above). A v18 client CAN receive this without having announced
                            // 16: a Paper /reload re-attach prompt speaks the v16 dialect
                            // (this branch is per-frame self-describing, so the decode is
                            // safe); the session gate's downgrade guard answers it, and the
                            // sourceless-column arming above stays inert unless this client
                            // itself last announced 16 (V16ClientWire.markAnnouncedVersion).
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
