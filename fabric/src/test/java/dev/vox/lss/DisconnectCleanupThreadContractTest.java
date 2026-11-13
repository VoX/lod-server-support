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
 * <p>One exception, and it is deliberate: once the server has stopped, {@code execute} runs a
 * task on the thread that called it. The cleanup then leaves the service alone (its shutdown,
 * on the server thread, drops every player's state) and only runs the thread-safe sweep.
 *
 * <p>Source-flavored: the event needs a live server and a real connection; the gametest
 * {@code aDisconnectFiredOffTheServerThreadIsCleanedUpOnIt} fires it from another thread.
 * NeoForge's logged-out event and Paper's quit mailbox already run their cleanup on the owning
 * thread.
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
                + "server.execute(() -> onDisconnect(server, uuid)); });";
        assertTrue(source.contains(registration),
                "the event body reads the id and hands everything else to the server thread");
        assertEquals(1, source.split("ServerPlayConnectionEvents\\.DISCONNECT\\.register", -1).length - 1,
                "one registration");
        // Everything that mutates the service is inside onDisconnect, which only that task calls.
        int method = source.indexOf(
                "private static void onDisconnect(net.minecraft.server.MinecraftServer server, java.util.UUID uuid) {");
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
        assertEquals(1, source.split("(?<![.\\w])onDisconnect\\(server, uuid\\)", -1).length - 1,
                "called from the handed-over task and nowhere else");
    }

    /** A stopped server runs the handed-over task on the calling network thread. */
    @Test
    void offTheServerThreadTheCleanupLeavesTheServiceAlone() throws IOException {
        String source = normalized("fabric/src/main/java/dev/vox/lss/networking/server/LSSServerNetworking.java");
        String cleanup = source.substring(source.indexOf(
                "private static void onDisconnect(net.minecraft.server.MinecraftServer server, java.util.UUID uuid) {"));
        int guard = cleanup.indexOf("var service = server.isSameThread() ? requestService : null;");
        assertTrue(guard >= 0, "the service is only touched on the server thread");
        assertTrue(guard < cleanup.indexOf("service.removePlayer(uuid);"), "…and the guard comes first");
        assertEquals(1, cleanup.split("requestService", -1).length - 1, "one read of the live service");
        // The sweep that runs on any thread is a concurrent map's remove.
        String glue = normalized("xplat/src/main/java/dev/vox/lss/networking/server/ServerReceiverGlue.java");
        assertTrue(glue.contains("private static final ConcurrentHashMap<java.util.UUID, Integer> CLIENT_DATA_VERSIONS"));
        assertTrue(glue.contains("public static void sweepClientInfo(java.util.UUID uuid) { CLIENT_DATA_VERSIONS.remove(uuid); }"));
    }

    @Test
    void theMoveTracerFollowsTheSameRule() throws IOException {
        String source = normalized("fabric/src/main/java/dev/vox/lss/trace/MoveTraceBootstrap.java");
        assertTrue(source.contains("server.execute(() -> MoveTraceTelemetry.onDisconnect(uuid));"));
        assertFalse(source.contains("MoveTraceTelemetry.onDisconnect(handler.getPlayer().getUUID())"));
    }
}
