package dev.vox.lss.networking.client;

import dev.vox.lss.common.Brand;
import dev.vox.lss.common.diagnostics.ClientNoticeCapsFile;
import dev.vox.lss.common.diagnostics.ClientNoticePolicy;
import dev.vox.lss.common.diagnostics.ClientVerdict;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.nio.file.Path;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Tells the player in chat, a few times ever, why no distant terrain is arriving (0.22.0 plan
 * §1): the server lacks the mod, has it switched off, speaks another version, or nothing on
 * this client can show the terrain. When and how often is {@link ClientNoticePolicy}'s; this
 * class reads the status verdict each tick and prints the line.
 *
 * <p>The line is grey and starts with the brand in brackets, so it does not read as the server
 * speaking. The player's own world and a LAN host get none. Client thread.
 */
public final class ClientNotices {
    private static final ClientNoticePolicy POLICY = new ClientNoticePolicy();
    /** Read once per game start, on the first tick that has a verdict. */
    private static ClientNoticePolicy.Caps caps;
    /** Seams: the chat sink and the counters' file. */
    static Consumer<Component> sink = ClientStatusScreen::sendChatFeedback;
    static Supplier<Path> file = () -> ColumnCacheStore.cacheRoot().resolveSibling("notices.properties");

    private ClientNotices() {}

    static void onJoin() {
        POLICY.onJoin();
    }

    static void tick() {
        var mc = Minecraft.getInstance();
        if (mc.player == null || mc.getConnection() == null || mc.hasSingleplayerServer()) return;
        var snapshot = ClientStatus.latest();
        if (snapshot == null) return;
        ClientVerdict verdict = ClientVerdict.of(snapshot);
        if (caps == null) {
            // Not before a verdict could ever be announced: most sessions never read the file.
            if (verdict.working()) return;
            caps = ClientNoticeCapsFile.load(file.get());
        }
        ClientVerdict announce = POLICY.tick(verdict, caps);
        if (announce == null) return;
        // Count first, speak second: a count that cannot be saved must not become a line
        // repeated on every game start.
        var after = caps.after(announce);
        if (!ClientNoticeCapsFile.save(file.get(), after)) return;
        caps = after;
        sink.accept(line(announce));
    }

    static Component line(ClientVerdict verdict) {
        return Component.literal("[" + Brand.shortName() + "] ")
                .append(Component.translatable("lss.notice." + verdict.key()))
                .withStyle(ChatFormatting.GRAY);
    }
}
