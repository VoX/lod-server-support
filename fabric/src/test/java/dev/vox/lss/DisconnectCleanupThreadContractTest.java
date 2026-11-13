package dev.vox.lss;

import dev.vox.lss.testutil.SourcePaths;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Fabric server's disconnect cleanup runs on the server thread.
 *
 * <p>Fabric fires {@code ServerPlayConnectionEvents.DISCONNECT} from whichever comes first:
 * netty's {@code channelInactive} (the other end closed the socket, or a kick's close went
 * through) or the server thread's own disconnect handling. The cleanup touches state that only
 * the server thread may touch: the generation service's active map, the far-player and
 * region-summary state, the service gate. Run from a netty thread it raced the tick; a real-stack
 * run on 2026-11-13 crashed the server that way ({@code ConcurrentModificationException} in
 * {@code ChunkGenerationService.tick}, a player kicked with 40 generations in flight). The
 * handler had run where the event fired since the first release.
 *
 * <p>Source-flavored: the event needs a live server and a real connection. NeoForge's
 * logged-out event and Paper's quit mailbox already run their cleanup on the owning thread.
 */
class DisconnectCleanupThreadContractTest {

    private static String normalized(String repoPath) throws IOException {
        return Files.readString(SourcePaths.repoFile(repoPath)).replaceAll("\\s+", " ");
    }

    @Test
    void theHandlerOnlyHandsTheCleanupToTheServerThread() throws IOException {
        String source = normalized("fabric/src/main/java/dev/vox/lss/networking/server/LSSServerNetworking.java");
        String registration = "ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> { "
                + "java.util.UUID uuid = handler.getPlayer().getUUID(); "
                + "server.execute(() -> onDisconnect(uuid)); });";
        assertTrue(source.contains(registration),
                "the event body reads the id and hands everything else to the server thread");
        assertEquals(1, source.split("ServerPlayConnectionEvents\\.DISCONNECT\\.register", -1).length - 1,
                "one registration");
        // Everything that mutates the service is inside onDisconnect, which only that task calls.
        int method = source.indexOf("private static void onDisconnect(java.util.UUID uuid) {");
        assertTrue(method > source.indexOf(registration), "the cleanup is its own method");
        String cleanup = source.substring(method);
        for (String call : new String[]{"service.removePlayer(uuid);",
                "service.getV16CompatManager().onDisconnect(uuid);",
                "service.getDialectTracker().onDisconnect(uuid);",
                "service.getFarPlayerService().onDisconnect(uuid);",
                "service.getRegionSummaries().removePlayer(uuid);",
                "service.getServiceGateState().onDisconnect(uuid);",
                "ServerReceiverGlue.sweepClientInfo(uuid);"}) {
            assertTrue(cleanup.contains(call), call);
            assertFalse(source.substring(0, method).contains(call.replace("uuid", "handler.getPlayer().getUUID()")),
                    "not in the event body: " + call);
        }
        // A bare call (not service.x().onDisconnect(uuid)) exists once: in the server-thread task.
        assertEquals(1, source.split("(?<![.\\w])onDisconnect\\(uuid\\)", -1).length - 1,
                "called from the server-thread task and nowhere else");
    }

    @Test
    void theMoveTracerFollowsTheSameRule() throws IOException {
        String source = normalized("fabric/src/main/java/dev/vox/lss/trace/MoveTraceBootstrap.java");
        assertTrue(source.contains("server.execute(() -> MoveTraceTelemetry.onDisconnect(uuid));"));
        assertFalse(source.contains("MoveTraceTelemetry.onDisconnect(handler.getPlayer().getUUID())"));
    }
}
