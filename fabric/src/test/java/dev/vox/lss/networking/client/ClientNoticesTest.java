package dev.vox.lss.networking.client;

import dev.vox.lss.common.Brand;
import dev.vox.lss.common.diagnostics.ClientVerdict;
import dev.vox.lss.testutil.SourcePaths;
import net.minecraft.ChatFormatting;
import net.minecraft.SharedConstants;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The chat notice's shape and wiring (0.22.0 plan §1). When and how often is
 * {@code ClientNoticePolicyTest}'s; what the verdict is, {@code ClientVerdictTest}'s.
 */
class ClientNoticesTest {

    @BeforeAll
    static void setup() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static String normalized(String repoPath) throws IOException {
        return Files.readString(SourcePaths.repoFile(repoPath)).replaceAll("\\s+", " ");
    }

    @Test
    void theLineIsGreyStartsWithTheBrandInBracketsAndIsTranslated() {
        var line = ClientNotices.line(ClientVerdict.NO_SUPPORT);
        assertEquals(net.minecraft.network.chat.TextColor.fromLegacyFormat(ChatFormatting.GRAY),
                line.getStyle().getColor(), "grey, so it does not read as the server speaking");
        assertTrue(line.getString().startsWith("[" + Brand.shortName() + "] "), line.getString());
        assertEquals(1, line.getSiblings().size());
        var body = (TranslatableContents) line.getSiblings().getFirst().getContents();
        assertEquals("lss.notice.no_support", body.getKey());
        assertEquals(0, body.getArgs().length, "the text carries the full name; the rebrand rewrites lang values");
    }

    @Test
    void thePlayersOwnWorldGetsNoNoticeAndTheBookDecidesTheRest() throws IOException {
        String source = normalized("xplat/src/main/java/dev/vox/lss/networking/client/ClientNotices.java");
        assertTrue(source.contains("mc.hasSingleplayerServer()) return;"),
                "singleplayer and a LAN host get no notice");
        assertTrue(source.contains("ClientVerdict announce = BOOK.tick(ClientVerdict.of(snapshot)); "
                        + "if (announce != null) sink.accept(line(announce));"),
                "when and how often is the book's (ClientNoticeBookTest), from the same verdict the status shows");
        assertTrue(source.contains("ColumnCacheStore.cacheRoot().resolveSibling(\"notices.properties\")"),
                "the counters live beside the terrain cache");
    }

    @Test
    void theClientTickDrivesTheNoticesAfterTheStatusAndTheConnectionsEdgesReachTheBook() throws IOException {
        String glue = normalized("xplat/src/main/java/dev/vox/lss/networking/client/ClientNetGlue.java");
        assertTrue(glue.contains("ClientStatus.tick(); ClientNotices.tick();"),
                "the notice reads the status the same tick published");
        assertTrue(glue.contains("LSSClientConfig.CONFIG.enableV19ServerCompat()); ClientNotices.onJoin();"),
                "a join (the one event that brackets a play session) resets the watch");
        assertTrue(glue.contains("sessionGate.onDisconnect(); ClientNotices.onDisconnect();"),
                "a real disconnect ends the \"this connection has seen the mod work\" memory");
        assertTrue(glue.contains("sessionGate.installChannelProbe(DhClientSession::nativeChannelPresent);"),
                "without the probe a server without the mod is never named");
    }

    @Test
    void bothStatusSnapshotsCarryTheGatesAnswerAndTheChannelFactWaitsForTheWorld() throws IOException {
        // The verdict tests build snapshots by hand. If the capture stopped passing the gate's
        // discovery state (the Distant Horizons branch is the easy one to miss), a map-only
        // client on a server without the mod would read "not ready yet" forever.
        String status = normalized("xplat/src/main/java/dev/vox/lss/networking/client/ClientStatus.java");
        assertEquals(2, status.split("ClientNetGlue\\.discoveryStatus\\(\\)", -1).length - 1,
                "the Distant Horizons snapshot and the native one");
        assertEquals(2, status.split("ClientNetGlue\\.statusProtocol\\(\\)", -1).length - 1,
                "both carry the protocol number a rejected server answered with");
        // A plugin server sends its channel list after the login packet and before any chunk:
        // "no channel" counts only once the player's own chunk is here.
        String session = normalized("xplat/src/main/java/dev/vox/lss/networking/client/DhClientSession.java");
        assertTrue(session.contains("if (nativeAvailable.getAsBoolean()) return true;"), "present is present at once");
        assertTrue(session.contains("return player == null || level == null || !loaded(level, "
                        + "net.minecraft.core.SectionPos.blockToSectionCoord(player.getBlockX()), "
                        + "net.minecraft.core.SectionPos.blockToSectionCoord(player.getBlockZ()));"),
                "absent is believed only once the world around the player has arrived");
    }
}
