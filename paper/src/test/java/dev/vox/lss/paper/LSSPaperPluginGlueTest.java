package dev.vox.lss.paper;

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
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

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

    private record Reply(int protocolVersion, boolean enabled, int lodDistanceChunks,
                         boolean generationEnabled) {}

    private static final class RecordingSender implements LSSPaperPlugin.SessionConfigSender {
        final List<Reply> replies = new ArrayList<>();

        @Override
        public void send(int protocolVersion, boolean enabled, int lodDistanceChunks,
                         boolean generationEnabled) {
            replies.add(new Reply(protocolVersion, enabled, lodDistanceChunks, generationEnabled));
        }
    }

    // ---- handshake glue obedience (sender/registrar seams) ----

    @Test
    void versionMismatchSendsZeroFramesAndRegistersNobody() {
        var sender = new RecordingSender();
        var registered = new ArrayList<Integer>();
        for (int version : new int[]{V + 1, V - 1}) {
            LSSPaperPlugin.handleHandshake(handshakeFrame(version, VOXEL_CAPS),
                    "Steve", config(true), true, sender, registered::add);
        }
        assertEquals(List.of(), sender.replies,
                "VERSION_MISMATCH must send NOTHING: the sender seam is the glue's only path to "
                        + "sendRawNmsPayload, and any reply decodes as garbage on the skewed client and kicks it");
        assertEquals(List.of(), registered, "a version-skewed client must never be registered");
    }

    @Test
    void legacyOneFieldFrameRepliesNoConsumerWithoutRegistering() {
        // Pre-capabilities clients send only the protocol VarInt; the decoder defaults caps=0,
        // which must classify NO_CONSUMER: reply with the session config, never register.
        var sender = new RecordingSender();
        var registered = new ArrayList<Integer>();
        LSSPaperPlugin.handleHandshake(frame(b -> b.writeVarInt(V)),
                "Steve", config(true), true, sender, registered::add);
        assertEquals(1, sender.replies.size(), "NO_CONSUMER still receives the session config");
        assertTrue(sender.replies.get(0).enabled(),
                "enabled config + present service advertise effectiveEnabled=true even to a consumer-less client");
        assertEquals(List.of(), registered,
                "caps=0 must never register: a registered consumer-less client is a zombie state that ignores every request");
    }

    @Test
    void happyPathRegistersWithTheExactCapabilitiesBitmask() {
        var sender = new RecordingSender();
        var registered = new ArrayList<Integer>();
        int caps = VOXEL_CAPS | 0x40; // future bit must pass through untouched
        LSSPaperPlugin.handleHandshake(handshakeFrame(V, caps),
                "Steve", config(true), true, sender, registered::add);
        assertEquals(1, sender.replies.size());
        assertEquals(List.of(caps), registered,
                "registration receives the client's full capabilities bitmask, not a normalized one");
    }

    @Test
    void sessionConfigReplyWiresEachConfigFieldToItsWireSlot() {
        // Distinct values and opposed booleans: any argument transposition at the call site
        // produces a wire-valid frame that live runs survive, so this is the only place a
        // swap can fail. (4-field frame — the concurrency caps left the wire.)
        var config = config(true);
        config.lodDistanceChunks = 101;
        config.enableChunkGeneration = false; // differs from effectiveEnabled=true
        var sender = new RecordingSender();
        LSSPaperPlugin.handleHandshake(handshakeFrame(V, VOXEL_CAPS),
                "Steve", config, true, sender, caps -> {});

        assertEquals(List.of(new Reply(V, true, 101, false)), sender.replies,
                "each PaperConfig field must land in its own session-config slot");
    }

    @Test
    void disabledConfigOrAbsentServiceAdvertisesDisabledWithoutRegistering() {
        var sender = new RecordingSender();
        var registered = new ArrayList<Integer>();
        LSSPaperPlugin.handleHandshake(handshakeFrame(V, VOXEL_CAPS),
                "Steve", config(false), true, sender, registered::add);
        LSSPaperPlugin.handleHandshake(handshakeFrame(V, VOXEL_CAPS),
                "Steve", config(true), false, sender, registered::add);
        assertEquals(2, sender.replies.size(), "DISABLED still replies (advertises disabled)");
        assertFalse(sender.replies.get(0).enabled(), "enabled=false config advertises disabled");
        assertFalse(sender.replies.get(1).enabled(), "absent service advertises disabled");
        assertEquals(List.of(), registered);
    }

    @Test
    void emptyFrameNeverRepliesNorRegisters() {
        var sender = new RecordingSender();
        var registered = new ArrayList<Integer>();
        LSSPaperPlugin.handleHandshake(new byte[0], "Steve", config(true), true, sender, registered::add);
        assertEquals(List.of(), sender.replies, "undecodable handshake must not produce a reply");
        assertEquals(List.of(), registered);
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
                    data -> LSSPaperPlugin.handleHandshake(data, "Steve", config(true), true, sender, caps -> {}),
                    data -> { throw new AssertionError("handshake frame must not reach the chunk-request handler"); }),
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
                    data -> LSSPaperPlugin.handleHandshake(data, "Steve", config(true), true, sender, caps -> {}),
                    data -> { throw new AssertionError("handshake frame must not reach the chunk-request handler"); });
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
                    chunkHandler));
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
                    chunkHandler);
            assertEquals(1, decoded.size(), "subsequent messages still dispatch after a contained failure");
            assertEquals(PositionUtil.packPosition(-3, 9), decoded.get(0).packedPositions()[0]);
        }
    }

    @Test
    void unknownChannelDispatchesToNeitherHandlerAndLogsNothing() {
        try (var capture = new LssLogCapture()) {
            LSSPaperPlugin.dispatchPluginMessage("lss:not_a_channel", "Steve", new byte[]{1, 2, 3},
                    data -> { throw new AssertionError("unknown channel must not reach the handshake handler"); },
                    data -> { throw new AssertionError("unknown channel must not reach the chunk-request handler"); });
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
        assertEquals(List.of("loadConfig", "registerChannels", "registerQuitListener", "startService",
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
        assertEquals(List.of("loadConfig", "registerChannels", "registerQuitListener", "startService",
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
