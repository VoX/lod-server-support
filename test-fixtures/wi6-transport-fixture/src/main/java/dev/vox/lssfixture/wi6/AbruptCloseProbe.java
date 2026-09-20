package dev.vox.lssfixture.wi6;

import dev.vox.lssfixture.wi6.mixin.ConnectionAccessor;
import net.minecraft.server.MinecraftServer;
import org.slf4j.LoggerFactory;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/** One-shot opt-in transport close in an owned offline server, without a disconnect packet. */
public final class AbruptCloseProbe {
    private static boolean finished;
    private AbruptCloseProbe() {}
    public static void tick(MinecraftServer server) {
        if (finished || !Boolean.getBoolean("lss.rig.abruptClose")
                || System.getProperty("lss.rig.runId", "").isBlank()) return;
        try {
            String configured = System.getProperty("lss.rig.serverRoot", "");
            if (configured.isBlank()) throw new IllegalStateException("owned root required");
            Path root = Path.of(configured);
            if (Files.isSymbolicLink(root) || !root.toRealPath().equals(Path.of("").toRealPath())
                    || server.usesAuthentication()) throw new IllegalStateException("owned offline server required");
            Path marker = root.resolve("lss-rig-abrupt-close");
            if (!Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)) return;
            finished = true;
            var players = server.getPlayerList().getPlayers();
            if (players.size() != 1) throw new IllegalStateException("exactly one observer required");
            var connection = ((dev.vox.lssfixture.wi6.mixin.ListenerAccessor) players.getFirst().connection).lssFixtureConnection();
            var channel = ((ConnectionAccessor) connection).lssFixtureChannel();
            if (channel == null || !channel.isActive()) throw new IllegalStateException("active real transport required");
            // Netty schedules its native close on the channel owner. No game packet is sent.
            channel.close().addListener(result -> LoggerFactory.getLogger("LSS-Abrupt-Close").info(
                    "[LSS-ABRUPT-CLOSE] {} realTransport=true disconnectPacket=false",
                    result.isSuccess() ? "CLOSED" : "FAIL"));
        } catch (Throwable error) {
            finished = true;
            LoggerFactory.getLogger("LSS-Abrupt-Close").error("[LSS-ABRUPT-CLOSE] FAIL {}", error.getClass().getSimpleName());
        }
    }
}
