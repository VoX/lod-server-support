package dev.vox.lss.networking.client;

import dev.vox.lss.common.Brand;
import dev.vox.lss.common.diagnostics.ClientNoticeBook;
import dev.vox.lss.common.diagnostics.ClientVerdict;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.function.Consumer;

/**
 * Tells the player in chat, a few times ever, why no distant terrain is arriving (0.22.0 plan
 * §1): the server lacks the mod, has it switched off, runs another version, or nothing on this
 * client can show the terrain; and, once per game start, that this mod's own settings file did
 * not load (0.23.0 plan §1). When, how often and the counters on disk are
 * {@link ClientNoticeBook}'s; this class reads the status verdict each tick and prints the line.
 *
 * <p>The line is grey and starts with the brand in brackets, so it does not read as the server
 * speaking. The player's own world and a LAN host get none. Client thread.
 */
public final class ClientNotices {
    /** The counters live beside the terrain cache (the brand's state directory). */
    private static final ClientNoticeBook BOOK = new ClientNoticeBook(
            () -> ColumnCacheStore.cacheRoot().resolveSibling("notices.properties"));
    /** Seam: the chat sink. */
    static Consumer<Component> sink = ClientStatusScreen::sendChatFeedback;

    private ClientNotices() {}

    static void onJoin() {
        BOOK.onJoin();
    }

    static void onDisconnect() {
        BOOK.onDisconnect();
    }

    static void tick() {
        var mc = Minecraft.getInstance();
        if (mc.player == null || mc.getConnection() == null || mc.hasSingleplayerServer()) return;
        var snapshot = ClientStatus.latest();
        if (snapshot == null) return;
        ClientVerdict announce = BOOK.tick(ClientVerdict.of(snapshot),
                snapshot.negotiated() && snapshot.serverEnabled());
        if (announce != null) sink.accept(line(announce));
    }

    static Component line(ClientVerdict verdict) {
        String key = "lss.notice." + verdict.key();
        return Component.literal("[" + Brand.shortName() + "] ")
                // One notice names a command, so it takes the build's own as its argument (a
                // lang value never spells a command root). The others carry the full text.
                .append(verdict == ClientVerdict.SETTINGS_BROKEN
                        ? Component.translatable(key, Brand.clientCommand()) : Component.translatable(key))
                .withStyle(ChatFormatting.GRAY);
    }
}
