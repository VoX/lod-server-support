package dev.vox.lss.networking.payloads;

import dev.vox.lss.common.LSSConstants;

/**
 * Client-side v16 backward-compat decode state (a v0.7.0 client talking to a v0.4.x–v0.6.2
 * protocol-16 server). See {@code docs/planning/v16-client-compat-design.md}.
 *
 * <p><b>Why a shared flag exists at all.</b> Two S2C frames differ on the wire between v16 and
 * v18: the SessionConfig (6 fields vs 4, but <em>self-describing</em> via its leading version
 * VarInt) and the VoxelColumn (no {@code source} byte, and <em>NOT</em> self-describing — the
 * frame carries no version marker). Payload decode runs on the netty thread <em>before</em>
 * any handler with connection context, so a stateless codec cannot know whether to expect the
 * source byte. This holds the one bit that resolves it.
 *
 * <p><b>Why it is race-free.</b> {@link #observeSessionConfigVersion} is called from the
 * SessionConfig decoder and {@link #isColumnSourceless} from the VoxelColumn decoder — both on
 * the same single netty decode thread, in frame order, and the server always sends the
 * SessionConfig before any column. So the flag is established before the first column decodes.
 * {@code volatile} carries the main-thread reset ({@link #reset} on JOIN/DISCONNECT) across to
 * the netty thread; a stale flag from a prior connection can never leak into a new one.
 *
 * <p><b>Why v18 is unaffected.</b> The default is {@code false}; a v18 SessionConfig sets it
 * {@code false}; with the flag {@code false} the VoxelColumn decoder reads the source byte
 * exactly as it always has. If the client never announces version 16 (compat disabled, or a
 * v18 server), it never receives a v16 config and the flag is never set. This class is
 * client-decode-only — a dedicated server never invokes it (it encodes S2C, never decodes).
 */
public final class V16ClientWire {

    private static volatile boolean columnSourceless = false;

    private V16ClientWire() {}

    /** Netty decode thread: called from {@code SessionConfigS2CPayload}'s decoder with the
     *  frame's protocol version, establishing (before any column decodes on the same thread)
     *  whether subsequent VoxelColumn frames omit the source byte. */
    public static void observeSessionConfigVersion(int protocolVersion) {
        columnSourceless = (protocolVersion == LSSConstants.V16_COMPAT_PROTOCOL_VERSION);
    }

    /** Netty decode thread: true when the current session is a v16 server, whose VoxelColumn
     *  frames carry no {@code source} byte (added in protocol 18). */
    public static boolean isColumnSourceless() {
        return columnSourceless;
    }

    /** Main thread: clear the flag at a connection boundary (JOIN before the handshake, and
     *  DISCONNECT) so no v16 state survives into a subsequent connection. */
    public static void reset() {
        columnSourceless = false;
    }
}
