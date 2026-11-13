package dev.vox.lss.networking.payloads;

import dev.vox.lss.common.LSSConstants;
import io.netty.buffer.Unpooled;
import net.minecraft.SharedConstants;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The SessionConfig {@code sessionFlags} append (map-policy plan A2/A5 "wire"): the
 * frames a 0.18.0 client and every legacy echo receive must stay BYTE-IDENTICAL to
 * 0.18.0 — the expected hex below was captured from the pre-change codec
 * (2026-10-13) before the field existed, not re-derived from the new code. The v20
 * frames here carry an EXPLICIT data version, 4554 = {@code ca23} (the constructor
 * argument), so they do not move with the line. Every 0.10.0–0.18.0 v20 client
 * hard-kicks on a trailing byte, and a 0.19 client's 19 arm would read stray flags as
 * the data version, so the append rides ONLY a version-20 frame to a session that
 * declared {@code CAPABILITY_MAP_POLICY}. The Paper hand encoder pins the same LAYOUT
 * ({@code dev.vox.lss.paper.WireParityTest}); its v20 bytes embed the RUNTIME data
 * version instead (5023 = {@code 9f27} on this line), and the legacy echoes are
 * byte-identical across the two.
 */
class SessionConfigFlagsWireTest {

    private static final int BIT = LSSConstants.CAPABILITY_MAP_POLICY;
    /** Every capability a pre-0.19 client could declare: no map-policy bit. */
    private static final int OLD_CAPS = LSSConstants.CAPABILITY_VOXEL_COLUMNS
            | LSSConstants.CAPABILITY_ZSTD_COLUMNS | LSSConstants.CAPABILITY_FAR_PLAYERS;
    private static final int NEW_CAPS = OLD_CAPS | BIT;

    // Captured from the pre-change codec (see the class javadoc).
    private static final String V20_ON_512_GEN_DV4554 = "1401800401ca23";
    private static final String V20_OFF_64_NOGEN_DV4554 = "14004000ca23";
    private static final String V19_ON_512_GEN = "1301800401";
    private static final String V19_OFF_512_GEN = "1300800401";
    private static final String V18_ON_512_GEN = "1201800401";
    private static final String V18_OFF_512_GEN = "1200800401";
    private static final String V16_ON_512_200_40_GEN = "10018004c8012801";
    private static final String V16_OFF_512_200_40_GEN = "10008004c8012801";

    @BeforeAll
    static void setup() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @AfterEach
    void resetWireState() {
        V16ClientWire.reset();
    }

    private static String hex(SessionConfigS2CPayload p) {
        var b = new FriendlyByteBuf(Unpooled.buffer());
        SessionConfigS2CPayload.CODEC.encode(b, p);
        byte[] out = new byte[b.readableBytes()];
        b.readBytes(out);
        b.release();
        return HexFormat.of().formatHex(out);
    }

    private static SessionConfigS2CPayload decode(String hex, int[] leftover) {
        var b = new FriendlyByteBuf(Unpooled.wrappedBuffer(HexFormat.of().parseHex(hex)));
        var p = SessionConfigS2CPayload.CODEC.decode(b);
        leftover[0] = b.readableBytes();
        b.release();
        return p;
    }

    private static SessionConfigS2CPayload decode(String hex) {
        int[] leftover = new int[1];
        var p = decode(hex, leftover);
        assertEquals(0, leftover[0], "the decode must consume the whole frame (a leftover byte "
                + "is a netty-level hard kick): " + hex);
        return p;
    }

    @Test
    void aSessionWithoutTheBitGetsThe0180BytesWhateverThePolicy() {
        for (boolean deny : new boolean[]{false, true}) {
            assertEquals(V20_ON_512_GEN_DV4554, hex(SessionConfigS2CPayload.forSession(
                    LSSConstants.PROTOCOL_VERSION, true, 512, true, 4554, OLD_CAPS, deny)),
                    "a 0.18.0 client hard-kicks on any trailing byte (deny=" + deny + ")");
            assertEquals(V20_OFF_64_NOGEN_DV4554, hex(SessionConfigS2CPayload.forSession(
                    LSSConstants.PROTOCOL_VERSION, false, 64, false, 4554, OLD_CAPS, deny)),
                    "the refusal/revocation shape too (deny=" + deny + ")");
        }
        assertEquals(V20_ON_512_GEN_DV4554, hex(new SessionConfigS2CPayload(
                LSSConstants.PROTOCOL_VERSION, true, 512, true, 4554)),
                "the flag-less constructor encodes the pre-change frame");
    }

    @Test
    void everyEchoDialectToADeclaringHandshakeIsThe0180Frame() {
        // A 0.19 client declares the bit at EVERY ladder rung — the 19/18 echoes must
        // still never carry the field (the 19 arm would read it as the data version).
        for (boolean deny : new boolean[]{false, true}) {
            assertEquals(V19_ON_512_GEN, hex(SessionConfigS2CPayload.forSession(
                    LSSConstants.V19_COMPAT_PROTOCOL_VERSION, true, 512, true, 5023, NEW_CAPS, deny)));
            assertEquals(V19_OFF_512_GEN, hex(SessionConfigS2CPayload.forSession(
                    LSSConstants.V19_COMPAT_PROTOCOL_VERSION, false, 512, true, 5023, NEW_CAPS, deny)),
                    "the QUIET refusal reply to a raced 19 rung");
            assertEquals(V18_ON_512_GEN, hex(SessionConfigS2CPayload.forSession(
                    LSSConstants.V18_COMPAT_PROTOCOL_VERSION, true, 512, true, 5023, NEW_CAPS, deny)));
            assertEquals(V18_OFF_512_GEN, hex(SessionConfigS2CPayload.forSession(
                    LSSConstants.V18_COMPAT_PROTOCOL_VERSION, false, 512, true, 5023, NEW_CAPS, deny)));
        }
        assertEquals(V16_ON_512_200_40_GEN, hex(SessionConfigS2CPayload.v16Legacy(true, 512, 200, 40, true)));
        assertEquals(V16_OFF_512_200_40_GEN, hex(SessionConfigS2CPayload.v16Legacy(false, 512, 200, 40, true)));
        assertEquals(LSSConstants.SESSION_FLAGS_ABSENT, SessionConfigS2CPayload.forSession(
                LSSConstants.V19_COMPAT_PROTOCOL_VERSION, false, 512, true, 5023, NEW_CAPS, true)
                .sessionFlags(), "an echo payload object mirrors its wire: no flags field");
        // Even a v16-layout payload that somehow carried flags never appends them.
        assertFalse(SessionConfigS2CPayload.appendsSessionFlags(
                LSSConstants.PROTOCOL_VERSION, true, LSSConstants.SESSION_FLAG_DENY_MAP_INGESTION));
    }

    @Test
    void aDeclaringV20SessionGetsTheFlagsAppendedAfterTheDataVersion() {
        assertEquals(V20_ON_512_GEN_DV4554 + "01", hex(SessionConfigS2CPayload.forSession(
                LSSConstants.PROTOCOL_VERSION, true, 512, true, 4554, NEW_CAPS, true)));
        assertEquals(V20_ON_512_GEN_DV4554 + "00", hex(SessionConfigS2CPayload.forSession(
                LSSConstants.PROTOCOL_VERSION, true, 512, true, 4554, NEW_CAPS, false)),
                "flags 0 is SENT (the disarm re-push must reach a capable session)");
        assertEquals(LSSConstants.SESSION_FLAGS_ABSENT,
                SessionConfigS2CPayload.sessionFlagsFor(OLD_CAPS, true));
        assertEquals(LSSConstants.SESSION_FLAG_DENY_MAP_INGESTION,
                SessionConfigS2CPayload.sessionFlagsFor(BIT, true));
        assertEquals(0, SessionConfigS2CPayload.sessionFlagsFor(BIT, false));
    }

    // ---- 0.23.0 plan §3: the reason an answer says "not here" ----

    @Test
    void aReasonRidesOnlyAFrameThatAlreadyCarriesFlagsAndSaysNotHere() {
        var off = SessionConfigS2CPayload.forSession(
                LSSConstants.PROTOCOL_VERSION, false, 64, false, 4554, NEW_CAPS, false);
        assertEquals(V20_OFF_64_NOGEN_DV4554 + "00", hex(off));
        assertEquals(V20_OFF_64_NOGEN_DV4554 + "02", hex(off.withOffReason(LSSConstants.SESSION_FLAG_OFF_SERVER)));
        assertEquals(V20_OFF_64_NOGEN_DV4554 + "04", hex(off.withOffReason(LSSConstants.SESSION_FLAG_OFF_WORLD)));
        assertEquals(V20_OFF_64_NOGEN_DV4554 + "08", hex(off.withOffReason(LSSConstants.SESSION_FLAG_OFF_PERMISSION)));
        assertEquals(V20_OFF_64_NOGEN_DV4554 + "05", hex(SessionConfigS2CPayload.forSession(
                LSSConstants.PROTOCOL_VERSION, false, 64, false, 4554, NEW_CAPS, true)
                .withOffReason(LSSConstants.SESSION_FLAG_OFF_WORLD)), "beside the map bit");
        assertEquals(V20_OFF_64_NOGEN_DV4554 + "02", hex(off.withOffReason(0x43)),
                "only reason bits get through; the map bit comes from the decision alone");
        assertEquals(hex(off), hex(off.withOffReason(0)), "no reason: the same bytes");
        // An enabled answer never carries a reason.
        assertEquals(V20_ON_512_GEN_DV4554 + "00", hex(SessionConfigS2CPayload.forSession(
                LSSConstants.PROTOCOL_VERSION, true, 512, true, 4554, NEW_CAPS, false)
                .withOffReason(LSSConstants.SESSION_FLAG_OFF_SERVER)));
        // A session without the bit, and every echo: the 0.18.0 bytes, whatever the reason.
        for (int reason : new int[]{LSSConstants.SESSION_FLAG_OFF_SERVER, LSSConstants.SESSION_FLAG_OFF_WORLD,
                LSSConstants.SESSION_FLAG_OFF_PERMISSION}) {
            assertEquals(V20_OFF_64_NOGEN_DV4554, hex(SessionConfigS2CPayload.forSession(
                    LSSConstants.PROTOCOL_VERSION, false, 64, false, 4554, OLD_CAPS, true).withOffReason(reason)),
                    "a 0.18.0 client hard-kicks on any trailing byte");
            assertEquals(V19_OFF_512_GEN, hex(SessionConfigS2CPayload.forSession(
                    LSSConstants.V19_COMPAT_PROTOCOL_VERSION, false, 512, true, 4554, NEW_CAPS, false).withOffReason(reason)));
            assertEquals(V18_OFF_512_GEN, hex(SessionConfigS2CPayload.forSession(
                    LSSConstants.V18_COMPAT_PROTOCOL_VERSION, false, 512, true, 4554, NEW_CAPS, false).withOffReason(reason)));
        }
    }

    @Test
    void aClientFrom019To022ReadsTheMapBitAloneFromAReasonedFrame() {
        // Those clients test bit 0 and nothing else (ClientSessionGate, unchanged since 0.19.0).
        var reasoned = decode(V20_OFF_64_NOGEN_DV4554 + "0e");
        assertEquals(0xE, reasoned.sessionFlags());
        assertEquals(0, reasoned.sessionFlags() & LSSConstants.SESSION_FLAG_DENY_MAP_INGESTION,
                "every reason bit set, and the map request reads as not armed");
        assertEquals(LSSConstants.SESSION_FLAG_DENY_MAP_INGESTION,
                decode(V20_OFF_64_NOGEN_DV4554 + "0f").sessionFlags() & LSSConstants.SESSION_FLAG_DENY_MAP_INGESTION);
        // This client reads the reason through one function.
        assertEquals(LSSConstants.SESSION_FLAG_OFF_WORLD, dev.vox.lss.common.SessionOffReason.read(
                false, decode(V20_OFF_64_NOGEN_DV4554 + "05").sessionFlags()));
        assertEquals(0, dev.vox.lss.common.SessionOffReason.read(false, decode(V20_OFF_64_NOGEN_DV4554).sessionFlags()),
                "a frame without flags says nothing");
    }

    @Test
    void decodeReadsTheFlagsAndTreatsAbsenceAsUnchanged() {
        var plain = decode(V20_ON_512_GEN_DV4554);
        assertEquals(4554, plain.serverDataVersion());
        assertEquals(LSSConstants.SESSION_FLAGS_ABSENT, plain.sessionFlags(),
                "absent decodes as -1 = unchanged (the gate keeps the connection's value)");
        var deny = decode(V20_ON_512_GEN_DV4554 + "01");
        assertEquals(LSSConstants.SESSION_FLAG_DENY_MAP_INGESTION, deny.sessionFlags());
        assertEquals(4554, deny.serverDataVersion());
        assertEquals(0, decode(V20_ON_512_GEN_DV4554 + "00").sessionFlags());
        // Unknown flag bits are carried (and ignored by the gate's mask).
        assertEquals(0x41, decode(V20_ON_512_GEN_DV4554 + "41").sessionFlags());
        // A pre-append same-version frame (no data version either) still decodes.
        var bare = decode("1401800401");
        assertEquals(0, bare.serverDataVersion());
        assertEquals(LSSConstants.SESSION_FLAGS_ABSENT, bare.sessionFlags());
    }

    @Test
    void decodeDrainsAnyFutureTrailingBytes() {
        // Future appends need no new capability for 0.19+ clients: the v20 arm drains.
        var future = decode(V20_ON_512_GEN_DV4554 + "01" + "ff7f" + "2a");
        assertEquals(LSSConstants.SESSION_FLAG_DENY_MAP_INGESTION, future.sessionFlags());
        assertTrue(future.enabled());
        assertEquals(512, future.lodDistanceChunks());
    }

    @Test
    void theNineteenArmNeverReadsFlags() {
        V16ClientWire.markAnnouncedVersion(LSSConstants.V19_COMPAT_PROTOCOL_VERSION);
        var echo = decode(V19_ON_512_GEN);
        assertEquals(LSSConstants.V19_COMPAT_PROTOCOL_VERSION, echo.protocolVersion());
        assertEquals(LSSConstants.SESSION_FLAGS_ABSENT, echo.sessionFlags(),
                "a 19 echo carries no flags field: absent = unchanged");
        var v16 = decode(V16_ON_512_200_40_GEN);
        assertEquals(LSSConstants.SESSION_FLAGS_ABSENT, v16.sessionFlags(),
                "a v16 frame (a Paper re-attach prompt) carries no flags either");
    }

    @Test
    void anEncodeDecodeRoundTripCarriesEveryComponent() {
        var sent = SessionConfigS2CPayload.forSession(LSSConstants.PROTOCOL_VERSION, true, 300,
                false, 5023, NEW_CAPS, true);
        var got = decode(hex(sent));
        assertEquals(sent, got);
    }

    @Test
    void theWitherCopiesEveryComponent() {
        var p = SessionConfigS2CPayload.forSession(LSSConstants.PROTOCOL_VERSION, true, 9999,
                true, 5023, NEW_CAPS, true);
        var clamped = p.withLodDistanceChunks(LSSConstants.MAX_LOD_DISTANCE);
        assertEquals(LSSConstants.MAX_LOD_DISTANCE, clamped.lodDistanceChunks());
        assertEquals(p.protocolVersion(), clamped.protocolVersion());
        assertEquals(p.enabled(), clamped.enabled());
        assertEquals(p.generationEnabled(), clamped.generationEnabled());
        assertEquals(5023, clamped.serverDataVersion(), "the old four-field rebuild dropped this");
        assertEquals(LSSConstants.SESSION_FLAG_DENY_MAP_INGESTION, clamped.sessionFlags(),
                "…and would have dropped the flags (the A2 client trap)");
        var legacy = SessionConfigS2CPayload.v16Legacy(true, 9999, 200, 40, true)
                .withLodDistanceChunks(64);
        assertTrue(legacy.v16Wire());
        assertEquals(200, legacy.legacySyncCap());
        assertEquals(40, legacy.legacyGenCap());
    }
}
