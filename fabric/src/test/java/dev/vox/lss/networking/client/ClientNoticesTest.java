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
    void thePlayersOwnWorldGetsNoNoticeAndACountIsSavedBeforeTheLineIsSpoken() throws IOException {
        String source = normalized("xplat/src/main/java/dev/vox/lss/networking/client/ClientNotices.java");
        assertTrue(source.contains("mc.hasSingleplayerServer()) return;"),
                "singleplayer and a LAN host get no notice");
        int save = source.indexOf("if (!ClientNoticeCapsFile.save(file.get(), after)) return;");
        int speak = source.indexOf("sink.accept(line(announce));");
        assertTrue(save > 0 && speak > save,
                "count first, speak second: an unsaved count would repeat the line every game start");
    }

    @Test
    void theClientTickDrivesTheNoticesAfterTheStatusAndEveryJoinStartsAFreshWatch() throws IOException {
        String glue = normalized("xplat/src/main/java/dev/vox/lss/networking/client/ClientNetGlue.java");
        assertTrue(glue.contains("ClientStatus.tick(); ClientNotices.tick();"),
                "the notice reads the status the same tick published");
        assertTrue(glue.contains("LSSClientConfig.CONFIG.enableV19ServerCompat()); ClientNotices.onJoin();"),
                "a join (the one event that brackets a play session) resets the watch");
        assertTrue(glue.contains("sessionGate.installChannelProbe(DhClientSession::nativeChannelPresent);"),
                "without the probe a server without the mod is never named");
    }
}
