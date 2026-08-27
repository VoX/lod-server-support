package dev.vox.lss.paper;

import dev.vox.lss.common.HandshakeGate;
import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.common.PositionUtil;
import io.netty.buffer.Unpooled;
import net.minecraft.SharedConstants;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.Bootstrap;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pins {@link LSSPaperPlugin}'s extracted glue — the layer between the pure pieces
 * (HandshakeGateTest pins the decision ladder, WireParityTest/PaperPayloadEdgeTest pin the
 * codecs) and the Bukkit environment. Three contracts live here and were previously decided
 * by untested call-site code:
 *
 * <ul>
 *   <li>Handshake glue obedience: a VERSION_MISMATCH decision sends ZERO frames (the
 *       sender seam is the glue's only path to sendRawNmsPayload; any reply kicks the
 *       skewed client), NO_CONSUMER replies without registering, and the session-config
 *       reply wires each {@link PaperConfig} field to its own wire slot — the adjacent
 *       sync/generation limit args are wire-valid if transposed and survive live soak runs,
 *       so only pairwise-distinct values catch a swap.</li>
 *   <li>Plugin-message dispatch containment: one hostile frame is caught and logged, never
 *       propagates into Bukkit's messenger, and later messages still dispatch.</li>
 *   <li>Per-player service gate (ticket #6): with {@code requireServicePermission} off the
 *       permission backend is never consulted at all; with it on, a player missing EITHER of
 *       {@code lss.use} / {@code vss.use} gets an {@code enabled=false} SessionConfig in
 *       their OWN dialect (v16/v18/v19 included) and no registration — never silence, which
 *       is the version-skew signal and would send the client's ladder into its retry rungs —
 *       plus one INFO line, latched once per player per session and claimed ONLY where the
 *       missing grant is what actually took the service away (never on a consumer-less
 *       handshake or a server-wide disable, which would spend the release on a denial the
 *       gate never decided and silence the client's real one).</li>
 *   <li>Enable-plan order and the enabled=false gate: PaperWorldHandler must never be
 *       constructed when the service tick is disabled, or the DirtyColumnTracker grows
 *       without bound for the whole server run (the B12/6df2c53 regression class). /reload
 *       re-runs onEnable, so the pinned step order is also the re-enable contract.</li>
 * </ul>
 */
class LSSPaperPluginGlueTest {

    private static final int V = LSSConstants.PROTOCOL_VERSION;
    private static final int VOXEL_CAPS = LSSConstants.CAPABILITY_VOXEL_COLUMNS;

    @BeforeAll
    static void setup() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @org.junit.jupiter.api.BeforeEach
    void resetHostileFrameThrottle() {
        // The containment pins each assert exactly one ERROR row; the production throttle
        // (60 s window, JVM-wide) would suppress whichever containment test runs second.
        LSSPaperPlugin.hostileFrameLog = new dev.vox.lss.common.LogThrottle(60_000);
    }

    // ---- frame + recorder plumbing ----

    private static byte[] frame(Consumer<FriendlyByteBuf> ops) {
        var b = new FriendlyByteBuf(Unpooled.buffer());
        ops.accept(b);
        byte[] out = new byte[b.readableBytes()];
        b.readBytes(out);
        b.release();
        return out;
    }

    private static byte[] handshakeFrame(int version, int caps) {
        return frame(b -> {
            b.writeVarInt(version);
            b.writeVarInt(caps);
        });
    }

    private static PaperConfig config(boolean enabled) {
        var c = new PaperConfig();
        c.enabled = enabled;
        return c;
    }

    private record Reply(HandshakeGate.WireDialect dialect, boolean enabled, int lodDistanceChunks,
                         int syncCap, int genCap, boolean generationEnabled) {}

    private static final class RecordingSender implements LSSPaperPlugin.SessionConfigSender {
        final List<Reply> replies = new ArrayList<>();

        @Override
        public void send(HandshakeGate.WireDialect dialect, boolean enabled, int lodDistanceChunks,
                         int syncCap, int genCap, boolean generationEnabled) {
            replies.add(new Reply(dialect, enabled, lodDistanceChunks, syncCap, genCap,
                    generationEnabled));
        }
    }

    /** Registrar recorder: capabilities + dialect + the DEFERRED reply the glue handed over.
     *  It deliberately does NOT run the reply — tests that want the SessionConfig must call
     *  {@link #runDeferredReplies()}, mirroring the production lifecycle drain. That makes
     *  every registering-path test also pin the Folia pre-registration fix: no reply may be
     *  sent inline before the registration applies. */
    private static final class RecordingRegistrar implements LSSPaperPlugin.HandshakeRegistrar {
        final List<Integer> caps = new ArrayList<>();
        final List<HandshakeGate.WireDialect> dialects = new ArrayList<>();
        final List<Runnable> deferredReplies = new ArrayList<>();

        @Override
        public void register(int capabilities, HandshakeGate.WireDialect dialect, Runnable replyAfterRegister) {
            caps.add(capabilities);
            dialects.add(dialect);
            deferredReplies.add(replyAfterRegister);
        }

        void runDeferredReplies() {
            deferredReplies.forEach(Runnable::run);
        }
    }

    /** Service-gate recorder: which nodes the core asked about, and how many times it tried
     *  to claim the once-per-session denial log (the latch itself releases exactly once). */
    private static final class RecordingGate implements dev.vox.lss.common.PlayerServiceGate {
        final Set<String> held;
        final List<String> checked = new ArrayList<>();
        final List<int[]> denials = new ArrayList<>(); // {protocolVersion, capabilities}
        int denialClaims;
        private boolean latched;

        RecordingGate(String... nodes) {
            held = new LinkedHashSet<>(List.of(nodes));
        }

        @Override
        public boolean hasPermission(String node) {
            checked.add(node);
            return held.contains(node);
        }

        @Override
        public boolean claimDenialLog() {
            denialClaims++;
            if (latched) return false;
            latched = true;
            return true;
        }

        @Override
        public void onServiceDenied(int protocolVersion, int capabilities) {
            denials.add(new int[]{protocolVersion, capabilities});
        }
    }

    private static PaperConfig gatedConfig() {
        var c = config(true);
        c.requireServicePermission = true;
        return c;
    }

    private static List<LogRow> denialRows(LssLogCapture capture) {
        return capture.rows().stream()
                .filter(r -> r.message().contains(LSSPaperPlugin.PERMISSION_SERVICE_LSS))
                .toList();
    }

    // ---- handshake glue obedience (sender/registrar seams) ----

    @Test
    void versionMismatchSendsZeroFramesAndRegistersNobody() {
        var sender = new RecordingSender();
        var registrar = new RecordingRegistrar();
        // V-1 (18) and 16 are no longer mismatches on default config — they have compat
        // rungs (tested below); 17 never shipped and V+1 is the future-client shape.
        for (int version : new int[]{V + 1, 17}) {
            LSSPaperPlugin.handleHandshake(handshakeFrame(version, VOXEL_CAPS),
                    "Steve", config(true), true, sender, registrar);
        }
        assertEquals(List.of(), sender.replies,
                "VERSION_MISMATCH must send NOTHING: the sender seam is the glue's only path to "
                        + "sendRawNmsPayload, and any reply decodes as garbage on the skewed client and kicks it");
        assertEquals(List.of(), registrar.caps, "a version-skewed client must never be registered");
    }

    @Test
    void legacyOneFieldFrameRepliesNoConsumerWithoutRegistering() {
        // Pre-capabilities clients send only the protocol VarInt; the decoder defaults caps=0,
        // which must classify NO_CONSUMER: reply with the session config, never register.
        var sender = new RecordingSender();
        var registrar = new RecordingRegistrar();
        LSSPaperPlugin.handleHandshake(frame(b -> b.writeVarInt(V)),
                "Steve", config(true), true, sender, registrar);
        assertEquals(1, sender.replies.size(), "NO_CONSUMER still receives the session config");
        assertTrue(sender.replies.get(0).enabled(),
                "enabled config + present service advertise effectiveEnabled=true even to a consumer-less client");
        assertEquals(List.of(), registrar.caps,
                "caps=0 must never register: a registered consumer-less client is a zombie state that ignores every request");
    }

    @Test
    void happyPathRegistersWithTheExactCapabilitiesBitmask() {
        var sender = new RecordingSender();
        var registrar = new RecordingRegistrar();
        int caps = VOXEL_CAPS | 0x40; // future bit must pass through untouched
        LSSPaperPlugin.handleHandshake(handshakeFrame(V, caps),
                "Steve", config(true), true, sender, registrar);
        assertEquals(List.of(), sender.replies,
                "a REGISTERING handshake must not reply inline — the SessionConfig is deferred "
                        + "into the registration so the client cannot declare into the Folia "
                        + "pre-registration gap (its first want-set would be dropped uncounted)");
        registrar.runDeferredReplies();
        assertEquals(1, sender.replies.size(), "the deferred reply fires after registration");
        assertEquals(List.of(caps), registrar.caps,
                "registration receives the client's full capabilities bitmask, not a normalized one");
        assertEquals(List.of(HandshakeGate.WireDialect.CURRENT), registrar.dialects);
    }

    @Test
    void sessionConfigReplyWiresEachConfigFieldToItsWireSlot() {
        // Distinct values and opposed booleans: any argument transposition at the call site
        // produces a wire-valid frame that live runs survive, so this is the only place a
        // swap can fail. (4-field frame — the concurrency caps left the wire.)
        var config = config(true);
        config.lodDistanceChunks = 101;
        config.generationConcurrencyLimitPerPlayer = 7; // pairwise-distinct from the 200 sync cap
        config.enableChunkGeneration = false; // differs from effectiveEnabled=true
        var sender = new RecordingSender();
        LSSPaperPlugin.handleHandshake(handshakeFrame(V, VOXEL_CAPS),
                "Steve", config, true, sender, (caps, dialect, reply) -> reply.run());

        assertEquals(List.of(new Reply(HandshakeGate.WireDialect.CURRENT, true, 101,
                        LSSConstants.SYNC_ON_LOAD_SLOT_CAP, 7, false)), sender.replies,
                "each PaperConfig field must land in its own session-config slot");
    }

    @Test
    void v16HandshakeGetsTheV16DialectReplyAndRegistration() {
        // A legacy protocol-16 client under enableV16Compat (default true) must take the
        // SAME ladder with the V16 dialect: the sender gets the dialect + the real admission
        // caps (they are the old client's pacing), and the registrar learns the dialect so
        // it can create the compat session before the mailboxed registration.
        var config = config(true);
        config.lodDistanceChunks = 101;
        config.generationConcurrencyLimitPerPlayer = 7;
        config.enableChunkGeneration = false; // opposed to effectiveEnabled=true (swap guard)
        var sender = new RecordingSender();
        var registrar = new RecordingRegistrar();
        LSSPaperPlugin.handleHandshake(handshakeFrame(16, VOXEL_CAPS),
                "Herobrine", config, true, sender, registrar);

        assertEquals(List.of(), sender.replies, "v16 registration defers its reply too");
        registrar.runDeferredReplies();
        assertEquals(List.of(new Reply(HandshakeGate.WireDialect.V16, true, 101,
                        LSSConstants.SYNC_ON_LOAD_SLOT_CAP, 7, false)), sender.replies);
        assertEquals(List.of(VOXEL_CAPS), registrar.caps);
        assertEquals(List.of(HandshakeGate.WireDialect.V16), registrar.dialects);
    }

    @Test
    void v16HandshakeWithCompatDisabledSendsNothing() {
        var config = config(true);
        config.enableV16Compat = false;
        var sender = new RecordingSender();
        var registrar = new RecordingRegistrar();
        LSSPaperPlugin.handleHandshake(handshakeFrame(16, VOXEL_CAPS),
                "Herobrine", config, true, sender, registrar);
        assertEquals(List.of(), sender.replies,
                "the kill switch restores the strict silent version gate");
        assertEquals(List.of(), registrar.caps);
    }

    @Test
    void v18HandshakeGetsTheV18DialectReplyAndRegistration() {
        // A protocol-18 client (v0.7.x–v0.8.x) under enableV18Compat (default true) takes
        // the SAME ladder with the V18 dialect, and this drives the PRODUCTION
        // handleHandshake — the 6-arg gate call site — so a silent fall-back to the 5-arg
        // overload (which would drop v18 clients to the v16 fallback) fails here
        // (v18-compat design §2.1, review F5). The reply is deferred exactly like every
        // registering outcome: the pre-registration gap applies to v18 joins too.
        var config = config(true);
        config.lodDistanceChunks = 101;
        config.generationConcurrencyLimitPerPlayer = 7;
        config.enableChunkGeneration = false; // opposed to effectiveEnabled=true (swap guard)
        var sender = new RecordingSender();
        var registrar = new RecordingRegistrar();
        LSSPaperPlugin.handleHandshake(handshakeFrame(18, VOXEL_CAPS),
                "vx7m", config, true, sender, registrar);

        assertEquals(List.of(), sender.replies, "v18 registration defers its reply too");
        registrar.runDeferredReplies();
        assertEquals(List.of(new Reply(HandshakeGate.WireDialect.V18, true, 101,
                        LSSConstants.SYNC_ON_LOAD_SLOT_CAP, 7, false)), sender.replies);
        assertEquals(List.of(VOXEL_CAPS), registrar.caps);
        assertEquals(List.of(HandshakeGate.WireDialect.V18), registrar.dialects);
    }

    @Test
    void v18HandshakeWithCompatDisabledSendsNothing() {
        var config = config(true);
        config.enableV18Compat = false;
        var sender = new RecordingSender();
        var registrar = new RecordingRegistrar();
        LSSPaperPlugin.handleHandshake(handshakeFrame(18, VOXEL_CAPS),
                "vx7m", config, true, sender, registrar);
        assertEquals(List.of(), sender.replies,
                "the v18 kill switch restores the strict silent version gate");
        assertEquals(List.of(), registrar.caps);
    }

    @Test
    void v19HandshakeGetsTheV19DialectReplyAndRegistration() {
        // A protocol-19 client (v0.9.x) under enableV19Compat (default true) takes the
        // SAME ladder with the V19 dialect, driving the PRODUCTION handleHandshake — the
        // 7-arg gate call site — so a silent fall-back to the 6-arg overload (which
        // would drop v19 clients to the v16 discovery fallback) fails HERE (the C1
        // recon's named drift hazard).
        var config = config(true);
        config.lodDistanceChunks = 103;
        config.generationConcurrencyLimitPerPlayer = 9;
        var sender = new RecordingSender();
        var registrar = new RecordingRegistrar();
        LSSPaperPlugin.handleHandshake(handshakeFrame(19, VOXEL_CAPS),
                "vx9m", config, true, sender, registrar);

        assertEquals(List.of(), sender.replies, "v19 registration defers its reply too");
        registrar.runDeferredReplies();
        assertEquals(List.of(new Reply(HandshakeGate.WireDialect.V19, true, 103,
                        LSSConstants.SYNC_ON_LOAD_SLOT_CAP, 9, true)), sender.replies);
        assertEquals(List.of(VOXEL_CAPS), registrar.caps);
        assertEquals(List.of(HandshakeGate.WireDialect.V19), registrar.dialects);
    }

    @Test
    void viaMismatchOnALegacyHandshakeSendsNothingAndRegistersNobody() {
        // C5 (XVER §7): the seam's 8-arg overload with a POSITIVE foreign Via answer
        // must stay silent for a legacy client — no reply frame (each legacy ladder
        // reads silence as "no LSS here"), no registration. The v20 client case and
        // the no-signal equivalence ride the gate suite; this drives the PRODUCTION
        // glue path.
        var config = config(true);
        var sender = new RecordingSender();
        var registrar = new RecordingRegistrar();
        LSSPaperPlugin.handleHandshake(handshakeFrame(19, VOXEL_CAPS),
                "vx9m", config, true, 763, 774, sender, registrar);
        assertEquals(List.of(), sender.replies, "a Via-mismatched legacy client gets silence");
        assertEquals(List.of(), registrar.caps);

        // The no-signal overload is the pre-C5 ladder verbatim: same frame registers.
        LSSPaperPlugin.handleHandshake(handshakeFrame(19, VOXEL_CAPS),
                "vx9m", config, true, sender, registrar);
        registrar.runDeferredReplies();
        assertEquals(List.of(VOXEL_CAPS), registrar.caps,
                "no Via signal must leave the ladder untouched (fail-open)");
    }

    @Test
    void v19HandshakeWithCompatDisabledSendsNothing() {
        var config = config(true);
        config.enableV19Compat = false;
        var sender = new RecordingSender();
        var registrar = new RecordingRegistrar();
        LSSPaperPlugin.handleHandshake(handshakeFrame(19, VOXEL_CAPS),
                "vx9m", config, true, sender, registrar);
        assertEquals(List.of(), sender.replies,
                "the v19 kill switch restores the strict silent version gate");
        assertEquals(List.of(), registrar.caps);
    }

    @Test
    void sessionConfigVersionEchoesV19ForTheV19Dialect() {
        assertEquals(LSSConstants.V19_COMPAT_PROTOCOL_VERSION,
                LSSPaperPlugin.sessionConfigVersionFor(HandshakeGate.WireDialect.V19),
                "the v19 client's gate hard-requires its own version echo");
    }

    // ---- the production sender/flip bodies (execution-review finding 1: these sat one
    // seam ABOVE the recording seams, so a silent regression in either compiled clean) ----

    @Test
    void sessionConfigVersionEchoes18ForTheV18DialectOnly() {
        // A V18 session answered with version 19 self-disables the v0.8.x client
        // ("incompatible protocol version"); CURRENT must keep echoing PROTOCOL_VERSION.
        assertEquals(LSSConstants.V18_COMPAT_PROTOCOL_VERSION,
                LSSPaperPlugin.sessionConfigVersionFor(HandshakeGate.WireDialect.V18));
        assertEquals(LSSConstants.PROTOCOL_VERSION,
                LSSPaperPlugin.sessionConfigVersionFor(HandshakeGate.WireDialect.CURRENT));
        assertEquals(LSSConstants.PROTOCOL_VERSION,
                LSSPaperPlugin.sessionConfigVersionFor(HandshakeGate.WireDialect.V16),
                "V16 never reaches the 4-field sender, but a defined answer keeps the "
                        + "helper total");
    }

    @Test
    void dialectFlipMarksOwnIdentityAndShedsTheOther() {
        // The switch body the pump runs before registerPlayer: V18 must MARK v18
        // membership (dropping that mark mis-derives wantsCompressedColumns and leaks
        // the codec byte to every v0.8.x client) and shed v16; V16 the mirror; CURRENT
        // sheds both. Driven against real manager/tracker instances.
        var uuid = java.util.UUID.randomUUID();

        var v16 = new dev.vox.lss.common.compat.V16CompatManager();
        var dialects = new dev.vox.lss.common.compat.WireDialectTracker();
        LSSPaperPlugin.dialectFlipFor(HandshakeGate.WireDialect.V18, v16, dialects, uuid).run();
        assertTrue(dialects.isV18(uuid), "the V18 flip must mark v18 membership");
        assertFalse(v16.isV16(uuid));

        // V16 flip on the same player (a cross-dialect re-handshake): marks v16, sheds v18.
        LSSPaperPlugin.dialectFlipFor(HandshakeGate.WireDialect.V16, v16, dialects, uuid).run();
        assertTrue(v16.isV16(uuid), "the V16 flip must mark the v16 session");
        assertTrue(dialects.isV16(uuid), "the tracker must carry the v16 dialect");
        assertFalse(dialects.isV18(uuid), "the V16 flip must shed stale v18 membership");

        // V19 flip on the same player: marks v19, sheds the v16 session.
        LSSPaperPlugin.dialectFlipFor(HandshakeGate.WireDialect.V19, v16, dialects, uuid).run();
        assertTrue(dialects.isV19(uuid), "the V19 flip must mark v19 membership");
        assertFalse(v16.isV16(uuid), "the V19 flip must shed the v16 session");

        // CURRENT flip sheds everything legacy.
        LSSPaperPlugin.dialectFlipFor(HandshakeGate.WireDialect.CURRENT, v16, dialects, uuid).run();
        assertFalse(v16.isV16(uuid), "the CURRENT flip must shed the v16 session");
        assertFalse(dialects.isV18(uuid) || dialects.isV19(uuid) || dialects.isV16(uuid),
                "the CURRENT flip must leave no legacy membership");
    }

    @Test
    void disabledConfigOrAbsentServiceAdvertisesDisabledWithoutRegistering() {
        var sender = new RecordingSender();
        var registrar = new RecordingRegistrar();
        LSSPaperPlugin.handleHandshake(handshakeFrame(V, VOXEL_CAPS),
                "Steve", config(false), true, sender, registrar);
        LSSPaperPlugin.handleHandshake(handshakeFrame(V, VOXEL_CAPS),
                "Steve", config(true), false, sender, registrar);
        assertEquals(2, sender.replies.size(), "DISABLED still replies (advertises disabled)");
        assertFalse(sender.replies.get(0).enabled(), "enabled=false config advertises disabled");
        assertFalse(sender.replies.get(1).enabled(), "absent service advertises disabled");
        assertEquals(List.of(), registrar.caps);
    }

    @Test
    void emptyFrameNeverRepliesNorRegisters() {
        var sender = new RecordingSender();
        var registrar = new RecordingRegistrar();
        LSSPaperPlugin.handleHandshake(new byte[0], "Steve", config(true), true, sender, registrar);
        assertEquals(List.of(), sender.replies, "undecodable handshake must not produce a reply");
        assertEquals(List.of(), registrar.caps);
    }

    // ---- per-player service gate (ticket #6) ----

    @Test
    void requireServicePermissionOffNeverConsultsTheGate() {
        // The kill-switch arm: at the shipped default the core must behave EXACTLY as it did
        // before the gate existed — not "asks and is told yes", but never asks at all, so a
        // permission backend that throws or blocks cannot touch the default install.
        var sender = new RecordingSender();
        var registrar = new RecordingRegistrar();
        var gate = new RecordingGate(); // holds nothing
        var config = config(true);
        assertFalse(config.requireServicePermission, "the gate must ship OFF");

        LSSPaperPlugin.handleHandshake(handshakeFrame(V, VOXEL_CAPS), "Steve", config, true,
                dev.vox.lss.common.compat.ViaProbe.NO_SIGNAL, 0, gate, sender, registrar);

        assertEquals(List.of(), gate.checked, "the switch is off: no permission lookup may happen");
        assertEquals(0, gate.denialClaims);
        assertEquals(List.of(VOXEL_CAPS), registrar.caps, "a permission-less player is served verbatim");
        registrar.runDeferredReplies();
        assertTrue(sender.replies.get(0).enabled());
    }

    @Test
    void withoutTheServicePermissionTheReplyAdvertisesDisabledAndNobodyRegisters() {
        // The denial SHAPE: a SessionConfig with enabled=false, not silence. Silence would
        // send the client's discovery ladder into its three-rung retry (and a v16 client
        // dark forever); enabled=false is the disarm the client already implements.
        var sender = new RecordingSender();
        var registrar = new RecordingRegistrar();
        var gate = new RecordingGate();
        var config = gatedConfig();

        try (var capture = new LssLogCapture()) {
            LSSPaperPlugin.handleHandshake(handshakeFrame(V, VOXEL_CAPS), "Steve", config, true,
                    dev.vox.lss.common.compat.ViaProbe.NO_SIGNAL, 0, gate, sender, registrar);

            assertEquals(1, sender.replies.size(), "the denial REPLIES — silence would make the client retry");
            assertEquals(new Reply(HandshakeGate.WireDialect.CURRENT, false, config.lodDistanceChunks,
                            LSSConstants.SYNC_ON_LOAD_SLOT_CAP, config.generationConcurrencyLimitPerPlayer,
                            config.enableChunkGeneration), sender.replies.get(0),
                    "the denial rides the existing enabled=false slot; every other slot is untouched");
            assertEquals(List.of(), registrar.caps, "a denied player must never be registered");
            assertEquals(List.of(LSSPaperPlugin.PERMISSION_SERVICE_LSS), gate.checked,
                    "the check is AND and short-circuits: the first missing spelling already denies");
            assertEquals(1, gate.denials.size(),
                    "the denial hook fires exactly once, under the deniedByServiceGate conjunction");
            assertArrayEquals(new int[]{V, VOXEL_CAPS}, gate.denials.get(0),
                    "the memo carries the denied handshake verbatim — the grant sweep replays it");

            var rows = denialRows(capture);
            assertEquals(1, rows.size(), "a denial must be visible to admins, not silent: " + capture.rows());
            assertEquals(Level.INFO, rows.get(0).level());
            assertTrue(rows.get(0).message().contains("Steve")
                            && rows.get(0).message().contains(LSSPaperPlugin.PERMISSION_SERVICE_VSS),
                    "the log names the player and both nodes: " + rows.get(0).message());
        }
    }

    @Test
    void aNegativeGrantOnEitherBrandSpellingDeniesTheService() {
        // The nodes ship `default: true`, so this is a DENY model and the enforcement is AND:
        // an admin's single negative grant must bite whichever spelling they typed, on either
        // jar. With OR, the spelling they did NOT touch still resolves to its declared true
        // and out-votes them — the gate could then never deny anyone (user, 2026-08-25).
        for (String revoked : List.of(LSSPaperPlugin.PERMISSION_SERVICE_LSS, LSSPaperPlugin.PERMISSION_SERVICE_VSS)) {
            String stillHeld = revoked.equals(LSSPaperPlugin.PERMISSION_SERVICE_LSS)
                    ? LSSPaperPlugin.PERMISSION_SERVICE_VSS : LSSPaperPlugin.PERMISSION_SERVICE_LSS;
            var sender = new RecordingSender();
            var registrar = new RecordingRegistrar();
            var gate = new RecordingGate(stillHeld); // only the untouched spelling resolves true

            LSSPaperPlugin.handleHandshake(handshakeFrame(V, VOXEL_CAPS), "Steve", gatedConfig(), true,
                    dev.vox.lss.common.compat.ViaProbe.NO_SIGNAL, 0, gate, sender, registrar);

            assertEquals(List.of(), registrar.caps,
                    "a negative grant on " + revoked + " must deny even though " + stillHeld + " is held");
            assertEquals(1, sender.replies.size());
            assertFalse(sender.replies.get(0).enabled(),
                    "revoking " + revoked + " must advertise disabled");
            assertEquals(1, gate.denialClaims, "the denial is the gate's, so it claims the log");
        }
    }

    @Test
    void holdingBothSpellingsServesNormally() {
        // The default state of every player once the key is armed: plugin.yml declares both
        // nodes `default: true`, so arming requireServicePermission alone denies NOBODY.
        var sender = new RecordingSender();
        var registrar = new RecordingRegistrar();
        var gate = new RecordingGate(LSSPaperPlugin.PERMISSION_SERVICE_LSS,
                LSSPaperPlugin.PERMISSION_SERVICE_VSS);

        LSSPaperPlugin.handleHandshake(handshakeFrame(V, VOXEL_CAPS), "Steve", gatedConfig(), true,
                dev.vox.lss.common.compat.ViaProbe.NO_SIGNAL, 0, gate, sender, registrar);

        assertEquals(List.of(LSSPaperPlugin.PERMISSION_SERVICE_LSS, LSSPaperPlugin.PERMISSION_SERVICE_VSS),
                gate.checked, "both spellings are consulted, lss first");
        assertEquals(List.of(VOXEL_CAPS), registrar.caps, "an untouched player is served verbatim");
        assertEquals(0, gate.denialClaims);
        registrar.runDeferredReplies();
        assertTrue(sender.replies.get(0).enabled());
    }

    @Test
    void theDenialLogIsThrottledToOncePerPlayerPerSession() {
        // A client may re-handshake at packet rate; the denial line must not become a flood.
        var sender = new RecordingSender();
        var registrar = new RecordingRegistrar();
        var gate = new RecordingGate();

        try (var capture = new LssLogCapture()) {
            for (int i = 0; i < 3; i++) {
                LSSPaperPlugin.handleHandshake(handshakeFrame(V, VOXEL_CAPS), "Steve", gatedConfig(), true,
                        dev.vox.lss.common.compat.ViaProbe.NO_SIGNAL, 0, gate, sender, registrar);
            }
            assertEquals(3, sender.replies.size(), "every handshake still gets its disarm reply");
            assertEquals(3, gate.denialClaims, "the core asks the latch on every denial");
            assertEquals(1, denialRows(capture).size(), "the latch releases exactly once per session");
        }
    }

    @Test
    void everyCompatDialectIsGatedToo() {
        // The v16/v18/v19 rungs are reachable on a default config, so an ungated compat
        // branch would be a hole a legacy client walks straight through.
        for (var expected : List.of(
                java.util.Map.entry(16, HandshakeGate.WireDialect.V16),
                java.util.Map.entry(18, HandshakeGate.WireDialect.V18),
                java.util.Map.entry(19, HandshakeGate.WireDialect.V19))) {
            var sender = new RecordingSender();
            var registrar = new RecordingRegistrar();

            LSSPaperPlugin.handleHandshake(handshakeFrame(expected.getKey(), VOXEL_CAPS), "Herobrine",
                    gatedConfig(), true, dev.vox.lss.common.compat.ViaProbe.NO_SIGNAL, 0,
                    new RecordingGate(), sender, registrar);

            assertEquals(1, sender.replies.size(), "protocol " + expected.getKey() + " must get the disarm reply");
            assertEquals(expected.getValue(), sender.replies.get(0).dialect(),
                    "the denial keeps the client's own dialect — a CURRENT-shaped frame would kick it");
            assertFalse(sender.replies.get(0).enabled(), "protocol " + expected.getKey() + " must be disarmed");
            assertEquals(List.of(), registrar.caps, "protocol " + expected.getKey() + " must not register");
        }
    }

    @Test
    void aSilentlyDeniedClientNeverBurnsTheDenialLatch() {
        // VERSION_MISMATCH/VIA_MISMATCH reply with NOTHING and are not permission denials;
        // logging (and latching) there would mislabel a skew as a missing grant — and would
        // spend the once-per-session release on a player who never saw the gate.
        var sender = new RecordingSender();
        var registrar = new RecordingRegistrar();
        var gate = new RecordingGate();

        try (var capture = new LssLogCapture()) {
            LSSPaperPlugin.handleHandshake(handshakeFrame(V + 1, VOXEL_CAPS), "Steve", gatedConfig(), true,
                    dev.vox.lss.common.compat.ViaProbe.NO_SIGNAL, 0, gate, sender, registrar);
            assertEquals(List.of(), sender.replies, "a skewed client must still receive NOTHING");
            assertEquals(0, gate.denialClaims);
            assertEquals(List.of(), denialRows(capture));
        }
    }

    @Test
    void aConsumerlessDeniedClientKeepsItsDenialLineForItsRealHandshake() {
        // The capability rung outranks the enabled check, so a caps=0 client is NO_CONSUMER
        // whatever its permissions — the gate did not decide anything. If the denial line
        // fired here it would (a) double up with the NO_CONSUMER line and (b) burn the one
        // per-session release, so the SAME client's later consumer-bearing handshake would
        // be denied in silence. That silent second denial is the bug this pins.
        var sender = new RecordingSender();
        var registrar = new RecordingRegistrar();
        var gate = new RecordingGate();

        try (var capture = new LssLogCapture()) {
            LSSPaperPlugin.handleHandshake(frame(b -> b.writeVarInt(V)), "Steve", gatedConfig(), true,
                    dev.vox.lss.common.compat.ViaProbe.NO_SIGNAL, 0, gate, sender, registrar);

            assertEquals(1, sender.replies.size(), "NO_CONSUMER still replies");
            assertFalse(sender.replies.get(0).enabled(),
                    "§8 n17, direction one: the gate rides the same evaluate input as the kill "
                            + "switch, so a DENIED consumer-less client's NO_CONSUMER reply "
                            + "advertises enabled=false — were it true, a consumer registered "
                            + "later this session would believe the server serves it");
            assertEquals(List.of(), registrar.caps);
            assertEquals(List.of(), denialRows(capture), "a consumer-less client is not a permission denial");
            assertEquals(0, gate.denialClaims, "the once-per-session release must NOT be burned here");
            assertEquals(0, gate.denials.size(),
                    "the memo deposit is under EXACTLY the deniedByServiceGate conjunction — "
                            + "never NO_CONSUMER (§8 O2-m3)");
            assertEquals(1, capture.rows().stream().filter(r -> r.message().contains("no LOD consumer")).count(),
                    "the NO_CONSUMER line is the only denial line: " + capture.rows());

            // Same session, now with a consumer: the permission line must still be available.
            LSSPaperPlugin.handleHandshake(handshakeFrame(V, VOXEL_CAPS), "Steve", gatedConfig(), true,
                    dev.vox.lss.common.compat.ViaProbe.NO_SIGNAL, 0, gate, sender, registrar);
            assertEquals(1, denialRows(capture).size(),
                    "the real handshake gets its permission line — the latch was not spent");
            assertFalse(sender.replies.get(1).enabled());
            assertEquals(List.of(), registrar.caps, "still denied, still unregistered");
        }
    }

    @Test
    void aConsumerlessHoldingClientStillSeesEnabledTrue() {
        // §8 n17, direction two: with the gate armed but the player HOLDING both nodes, the
        // caps=0 NO_CONSUMER reply keeps advertising enabled=true — the gate must be
        // invisible to players it does not deny, in every outcome.
        var sender = new RecordingSender();
        var gate = new RecordingGate(LSSPaperPlugin.PERMISSION_SERVICE_LSS,
                LSSPaperPlugin.PERMISSION_SERVICE_VSS);

        LSSPaperPlugin.handleHandshake(frame(b -> b.writeVarInt(V)), "Steve", gatedConfig(), true,
                dev.vox.lss.common.compat.ViaProbe.NO_SIGNAL, 0, gate, sender, new RecordingRegistrar());

        assertEquals(1, sender.replies.size());
        assertTrue(sender.replies.get(0).enabled(),
                "a holding player's NO_CONSUMER reply is byte-identical to the ungated one");
        assertEquals(0, gate.denials.size());
    }

    @Test
    void aServerWideDisableIsNeverBlamedOnTheMissingPermission() {
        // With LSS off (or its service absent) the player is dark regardless of any grant;
        // naming a permission would send the admin hunting a grant that changes nothing —
        // and would burn the release before the gate ever decided anything.
        var gate = new RecordingGate();
        var offConfig = config(false);
        offConfig.requireServicePermission = true;

        try (var capture = new LssLogCapture()) {
            var sender = new RecordingSender();
            LSSPaperPlugin.handleHandshake(handshakeFrame(V, VOXEL_CAPS), "Steve", offConfig, true,
                    dev.vox.lss.common.compat.ViaProbe.NO_SIGNAL, 0, gate, sender, new RecordingRegistrar());
            LSSPaperPlugin.handleHandshake(handshakeFrame(V, VOXEL_CAPS), "Steve", gatedConfig(), false,
                    dev.vox.lss.common.compat.ViaProbe.NO_SIGNAL, 0, gate, sender, new RecordingRegistrar());

            assertEquals(2, sender.replies.size(), "both still advertise disabled");
            assertFalse(sender.replies.get(0).enabled());
            assertFalse(sender.replies.get(1).enabled());
            assertEquals(List.of(), denialRows(capture), "neither denial is the gate's to claim");
            assertEquals(0, gate.denialClaims, "the release stays unspent");
        }
    }

    @Test
    void productionGateReadsBothNodesOffTheBukkitPlayerAndLatchesPerSession() {
        // The production seam: the injected gate must be a REAL hasPermission read (a
        // hard-coded true would make the whole feature inert on a live server) and the
        // latch must be keyed by UUID on the service's gate state so quit/stop can sweep.
        var uuid = UUID.randomUUID();
        var bukkit = mock(org.bukkit.entity.Player.class);
        when(bukkit.hasPermission(LSSPaperPlugin.PERMISSION_SERVICE_VSS)).thenReturn(true);
        var state = new dev.vox.lss.common.ServiceGateState();
        var compositeRuns = new java.util.concurrent.atomic.AtomicInteger();
        var gate = LSSPaperPlugin.serviceGateFor(bukkit, uuid, "Steve", state,
                compositeRuns::incrementAndGet);

        assertFalse(gate.hasPermission(LSSPaperPlugin.PERMISSION_SERVICE_LSS));
        assertTrue(gate.hasPermission(LSSPaperPlugin.PERMISSION_SERVICE_VSS));

        assertTrue(gate.claimDenialLog(), "the first denial releases");
        assertFalse(gate.claimDenialLog(), "later denials in the same session stay silent");

        gate.onServiceDenied(20, 1);
        assertTrue(state.isDenied(uuid), "the denial hook deposits the re-offer memo");
        assertEquals(1, state.permissionDeniedTotal(), "absent->present is the counted transition");
        gate.onServiceDenied(20, 1);
        assertEquals(1, state.permissionDeniedTotal(), "re-handshakes while denied are not re-counted");
        assertEquals(2, compositeRuns.get(),
                "every denial marshals the composite (it no-ops on the pump for unregistered uuids)");

        state.onDisconnect(uuid);
        assertFalse(state.isDenied(uuid), "quit sweeps the memo");
        assertTrue(gate.claimDenialLog(), "a rejoin gets its line again — 'per session', not 'per server run'");
    }

    @Test
    void aThrowingBukkitPermissibleIsContainedAndServes() {
        // Fail-open doctrine (plan §3): a throwing permission backend answers TRUE with a
        // once-warn — a throw must never escape into handshake silence or a denial.
        LSSPaperPlugin.resetPermissibleThrowWarnedForTest();
        var bukkit = mock(org.bukkit.entity.Player.class);
        when(bukkit.hasPermission(org.mockito.ArgumentMatchers.anyString()))
                .thenThrow(new IllegalStateException("backend exploded"));
        var gate = LSSPaperPlugin.serviceGateFor(bukkit, UUID.randomUUID(), "Steve",
                new dev.vox.lss.common.ServiceGateState(), null);

        try (var capture = new LssLogCapture()) {
            assertTrue(gate.hasPermission(LSSPaperPlugin.PERMISSION_SERVICE_LSS),
                    "a throwing permissible must serve, not deny");
            assertTrue(gate.hasPermission(LSSPaperPlugin.PERMISSION_SERVICE_VSS));
            assertTrue(LSSPaperPlugin.holdsServicePermission(gate),
                    "the armed gate composed over the throw still clears — fail-open");
            assertEquals(1, capture.rows().stream()
                            .filter(r -> r.message().contains("permission read threw")).count(),
                    "one warn, once per JVM: " + capture.rows());
        }
    }

    // ---- plugin-message dispatch containment ----

    @Test
    void garbageHandshakeFrameIsContainedLoggedAndNextMessageStillDispatches() {
        var sender = new RecordingSender();
        // Truncated VarInt (continuation bit, no next byte): the decode throws from inside
        // the real handshake glue, exactly as a hostile client frame would in production.
        byte[] garbage = {(byte) 0xFF};
        try (var capture = new LssLogCapture()) {
            assertDoesNotThrow(() -> LSSPaperPlugin.dispatchPluginMessage(
                    LSSConstants.CHANNEL_HANDSHAKE, "Steve", garbage,
                    data -> LSSPaperPlugin.handleHandshake(data, "Steve", config(true), true, sender, (caps, dialect, reply) -> reply.run()),
                    data -> { throw new AssertionError("handshake frame must not reach the chunk-request handler"); },
                    data -> { throw new AssertionError("handshake frame must not reach the client-info handler"); },
                    data -> { throw new AssertionError("handshake frame must not reach the far-player prefs handler"); },
                    data -> { throw new AssertionError("handshake frame must not reach the region-summary handler"); }),
                    "a malformed frame must never propagate into Bukkit's messenger");
            assertEquals(List.of(), sender.replies, "no partial handshake handling");

            var errors = capture.rows().stream().filter(r -> r.level() == Level.ERROR).toList();
            assertEquals(1, errors.size(), "containment must be logged, not silent");
            assertTrue(errors.get(0).message().contains(LSSConstants.CHANNEL_HANDSHAKE)
                            && errors.get(0).message().contains("Steve"),
                    "log names the channel and player: " + errors.get(0).message());
            assertNotNull(errors.get(0).thrown(), "the decode failure is attached to the log row");

            // The channel survives: the next (valid) message dispatches normally.
            LSSPaperPlugin.dispatchPluginMessage(
                    LSSConstants.CHANNEL_HANDSHAKE, "Steve", handshakeFrame(V, VOXEL_CAPS),
                    data -> LSSPaperPlugin.handleHandshake(data, "Steve", config(true), true, sender, (caps, dialect, reply) -> reply.run()),
                    data -> { throw new AssertionError("handshake frame must not reach the chunk-request handler"); },
                    data -> { throw new AssertionError("handshake frame must not reach the client-info handler"); },
                    data -> { throw new AssertionError("handshake frame must not reach the far-player prefs handler"); },
                    data -> { throw new AssertionError("handshake frame must not reach the region-summary handler"); });
            assertEquals(1, sender.replies.size(), "subsequent messages still dispatch after a contained failure");
            assertEquals(1, capture.rows().stream().filter(r -> r.level() == Level.ERROR).count());
        }
    }

    @Test
    void garbageChunkRequestFrameIsContainedLoggedAndNextMessageStillDispatches() {
        var decoded = new ArrayList<PaperPayloadHandler.DecodedBatchChunkRequest>();
        LSSPaperPlugin.PluginMessageHandler chunkHandler = data -> {
            // Same decode-then-handoff shape as the production handleBatchChunkRequest.
            var batch = PaperPayloadHandler.decodeBatchChunkRequest(data);
            if (batch != null) decoded.add(batch);
        };
        // Declares 2 entries, carries 1 — PaperPayloadEdgeTest pins this throws an Exception.
        byte[] garbage = frame(b -> {
            b.writeVarInt(2);
            b.writeLong(PositionUtil.packPosition(1, 1));
            b.writeLong(-1L);
        });
        try (var capture = new LssLogCapture()) {
            assertDoesNotThrow(() -> LSSPaperPlugin.dispatchPluginMessage(
                    LSSConstants.CHANNEL_CHUNK_REQUEST, "Alex", garbage,
                    data -> { throw new AssertionError("chunk-request frame must not reach the handshake handler"); },
                    chunkHandler,
                    data -> { throw new AssertionError("chunk-request frame must not reach the client-info handler"); },
                    data -> { throw new AssertionError("chunk-request frame must not reach the far-player prefs handler"); },
                    data -> { throw new AssertionError("chunk-request frame must not reach the region-summary handler"); }));
            assertEquals(List.of(), decoded, "no partial batch decode survives");

            var errors = capture.rows().stream().filter(r -> r.level() == Level.ERROR).toList();
            assertEquals(1, errors.size());
            assertTrue(errors.get(0).message().contains(LSSConstants.CHANNEL_CHUNK_REQUEST)
                    && errors.get(0).message().contains("Alex"));

            LSSPaperPlugin.dispatchPluginMessage(
                    LSSConstants.CHANNEL_CHUNK_REQUEST, "Alex",
                    frame(b -> {
                        b.writeVarInt(1);
                        b.writeLong(PositionUtil.packPosition(-3, 9));
                        b.writeLong(123L);
                    }),
                    data -> { throw new AssertionError("chunk-request frame must not reach the handshake handler"); },
                    chunkHandler,
                    data -> { throw new AssertionError("chunk-request frame must not reach the client-info handler"); },
                    data -> { throw new AssertionError("chunk-request frame must not reach the far-player prefs handler"); },
                    data -> { throw new AssertionError("chunk-request frame must not reach the region-summary handler"); });
            assertEquals(1, decoded.size(), "subsequent messages still dispatch after a contained failure");
            assertEquals(PositionUtil.packPosition(-3, 9), decoded.get(0).packedPositions()[0]);
        }
    }

    @Test
    void hostileFrameFloodIsThrottledToOneLogPerWindow() {
        // A griefer can spam malformed frames at packet rate; each used to emit a full
        // stack-trace ERROR (log-disk fill, drowning real diagnostics). The throttle keeps
        // containment (every frame caught, channel healthy) while logging once per window.
        byte[] garbage = {(byte) 0xFF};
        try (var capture = new LssLogCapture()) {
            for (int i = 0; i < 5; i++) {
                LSSPaperPlugin.dispatchPluginMessage(
                        LSSConstants.CHANNEL_HANDSHAKE, "Griefer", garbage,
                        data -> { throw new IllegalStateException("injected hostile-frame failure"); },
                        data -> { throw new AssertionError("handshake frame must not reach the chunk-request handler"); },
                        data -> { throw new AssertionError("handshake frame must not reach the client-info handler"); },
                        data -> { throw new AssertionError("handshake frame must not reach the far-player prefs handler"); },
                    data -> { throw new AssertionError("handshake frame must not reach the region-summary handler"); });
            }
            var errors = capture.rows().stream().filter(r -> r.level() == Level.ERROR).toList();
            assertEquals(1, errors.size(),
                    "a malformed-frame flood logs once per throttle window, not once per frame");
        }
    }

    @Test
    void unknownChannelDispatchesToNeitherHandlerAndLogsNothing() {
        try (var capture = new LssLogCapture()) {
            LSSPaperPlugin.dispatchPluginMessage("lss:not_a_channel", "Steve", new byte[]{1, 2, 3},
                    data -> { throw new AssertionError("unknown channel must not reach the handshake handler"); },
                    data -> { throw new AssertionError("unknown channel must not reach the chunk-request handler"); },
                    data -> { throw new AssertionError("unknown channel must not reach the client-info handler"); },
                    data -> { throw new AssertionError("unknown channel must not reach the far-player prefs handler"); },
                    data -> { throw new AssertionError("unknown channel must not reach the region-summary handler"); });
            assertEquals(List.of(), capture.rows(), "unknown channels are silently ignored");
        }
    }

    // ---- enable plan: step order + enabled=false gate ----

    private static final class RecordingSteps implements LSSPaperPlugin.EnableSteps {
        final List<String> order = new ArrayList<>();
        final PaperConfig config = new PaperConfig();
        // Mock: the real service needs a live NMS MinecraftServer; the plan must treat it as opaque.
        final PaperRequestProcessingService service = mock(PaperRequestProcessingService.class);
        PaperConfig startServiceConfig;
        PaperRequestProcessingService worldHandlerService;
        PaperConfig worldHandlerConfig;

        RecordingSteps(boolean enabled) {
            config.enabled = enabled;
        }

        @Override
        public void loadBranding() {
            order.add("loadBranding");
        }

        @Override
        public PaperConfig loadConfig() {
            order.add("loadConfig");
            return config;
        }

        @Override
        public void registerChannels() {
            order.add("registerChannels");
        }

        @Override
        public void registerQuitListener() {
            order.add("registerQuitListener");
        }

        @Override
        public PaperRequestProcessingService startService(PaperConfig config) {
            order.add("startService");
            this.startServiceConfig = config;
            return service;
        }

        @Override
        public void registerWorldHandler(PaperRequestProcessingService service, PaperConfig config) {
            order.add("registerWorldHandler");
            this.worldHandlerService = service;
            this.worldHandlerConfig = config;
        }

        @Override
        public void registerCommands() {
            order.add("registerCommands");
        }

        @Override
        public void scheduleServiceTick() {
            order.add("scheduleServiceTick");
        }

        @Override
        public void initSoakBridge() {
            order.add("initSoakBridge");
        }
    }

    @Test
    void enablePlanRunsEveryStepInProductionOrderWhenEnabled() {
        var steps = new RecordingSteps(true);
        LSSPaperPlugin.runEnablePlan(steps);
        assertEquals(List.of("loadBranding", "loadConfig", "registerChannels", "registerQuitListener", "startService",
                        "registerWorldHandler", "registerCommands", "scheduleServiceTick", "initSoakBridge"),
                steps.order,
                "/reload re-runs onEnable, so this order is the re-enable contract; the soak bridge "
                        + "runs last so the driver sees a fully wired plugin");
        assertSame(steps.config, steps.startServiceConfig, "the service is built from the loaded config");
        assertSame(steps.service, steps.worldHandlerService,
                "the world handler feeds the dirty tracker of the service the plan just started");
        assertSame(steps.config, steps.worldHandlerConfig, "updateEvents come from the loaded config");
    }

    @Test
    void enabledFalseNeverConstructsTheWorldHandler() {
        // B12 regression guard: with enabled=false the service tick (and so the dirty-broadcast
        // drain) never runs, so a registered PaperWorldHandler would grow the DirtyColumnTracker
        // without bound for the whole server run. The gate must skip ONLY the world-handler step.
        var steps = new RecordingSteps(false);
        LSSPaperPlugin.runEnablePlan(steps);
        assertFalse(steps.order.contains("registerWorldHandler"),
                "enabled=false must never construct PaperWorldHandler (tracker would grow unbounded)");
        assertEquals(List.of("loadBranding", "loadConfig", "registerChannels", "registerQuitListener", "startService",
                        "registerCommands", "scheduleServiceTick", "initSoakBridge"),
                steps.order, "every other enable step still runs, in the same order");
    }

    // ---- log capture (LSSLogger is static-final; observing 'logged' must hook the backend) ----

    private record LogRow(Level level, String message, Throwable thrown) {}

    /**
     * Captures rows logged through {@code LSSLogger} by attaching a synchronous appender to
     * the log4j root logger config (the Paper dev bundle binds SLF4J to log4j-core). Appender
     * refs added programmatically to the root LoggerConfig are invoked on the logging thread,
     * so no async flush is needed before asserting.
     */
    private static final class LssLogCapture extends AbstractAppender implements AutoCloseable {
        private final List<LogRow> rows = new CopyOnWriteArrayList<>();
        private final LoggerContext ctx;

        LssLogCapture() {
            super("lss-glue-test-capture", null, null, true, Property.EMPTY_ARRAY);
            start();
            ctx = (LoggerContext) LogManager.getContext(false);
            ctx.getConfiguration().getRootLogger().addAppender(this, Level.ALL, null);
            ctx.updateLoggers();
        }

        @Override
        public void append(LogEvent event) {
            if ("LSS".equals(event.getLoggerName())) {
                rows.add(new LogRow(event.getLevel(), event.getMessage().getFormattedMessage(),
                        event.getThrown()));
            }
        }

        List<LogRow> rows() {
            return rows;
        }

        @Override
        public void close() {
            ctx.getConfiguration().getRootLogger().removeAppender(getName());
            ctx.updateLoggers();
            stop();
        }
    }
}
