package dev.vox.lss.networking.client;

import dev.vox.lss.common.Brand;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Small loader-neutral status surface; render performs no collection or I/O. */
public final class ClientStatusScreen extends Screen {
    public static Screen currentScreen() { return net.minecraft.client.Minecraft.getInstance().screen; }
    private final Screen parent;
    private final Runnable onReturn;
    private final dev.vox.lss.common.diagnostics.ScreenEscapeRelease escape =
            new dev.vox.lss.common.diagnostics.ScreenEscapeRelease();
    public ClientStatusScreen(Screen parent) { this(parent, () -> {}); }
    public ClientStatusScreen(Screen parent, Runnable onReturn) {
        super(Component.translatable("lss.status.title"));
        this.parent = parent;
        this.onReturn = onReturn;
    }
    private int textPage;
    private Component lastActionFeedback;
    @Override protected void init() {
        int left = Math.max(3, width / 2 - 155);
        addRenderableWidget(Button.builder(Component.translatable("lss.status.export"), button ->
                ClientCommandActions.exportDiagnostics(this::feedback)).bounds(left, height - 27, 90, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("lss.status.more"), button -> textPage++)
                .bounds(left + 186, height - 27, 60, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("lss.status.done"), button -> onClose())
                .bounds(left + 249, height - 27, 60, 20).build());
    }
    private void feedback(Component message) {
        lastActionFeedback = message;
        sendChatFeedback(message);
    }
    /** Local action feedback, shared with automatic Sodium reloads across screen API generations. */
    public static void sendChatFeedback(Component message) {
        var client = net.minecraft.client.Minecraft.getInstance();
        if (client.player != null) client.gui.getChat().addClientSystemMessage(message);
    }
    @Override public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        graphics.fill(0, 0, width, height, 0xe0101010);
        // The newer text submission API discards zero-alpha colors. Use opaque
        // ARGB and submit foreground after the background/base screen.
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        graphics.centeredText(font, title, width / 2, 12, 0xffffffff);
        var snapshot = ClientStatus.latest();
        var lines = ClientStatusComponents.lines(snapshot);
        var wrappedLines = new java.util.ArrayList<net.minecraft.util.FormattedCharSequence>();
        var config = dev.vox.lss.config.LSSClientConfig.CONFIG;
        if (config.error() != null) wrappedLines.addAll(font.split(
                Component.translatable("lss.settings.inactive", config.error()), Math.max(50, width - 30)));
        if (!config.pendingReconnect().isEmpty()) wrappedLines.addAll(font.split(
                Component.translatable("lss.settings.pending_reconnect_notice"), Math.max(50, width - 30)));
        if (lastActionFeedback != null) wrappedLines.addAll(font.split(lastActionFeedback, Math.max(50, width - 30)));
        for (Component line : lines) wrappedLines.addAll(font.split(line, Math.max(50, width - 30)));
        int rows = Math.max(1, (height - 72) / 11);
        int pages = Math.max(1, (wrappedLines.size() + rows - 1) / rows);
        int first = Math.floorMod(textPage, pages) * rows;
        for (int row = 0; row < rows && first + row < wrappedLines.size(); row++) {
            graphics.text(font, wrappedLines.get(first + row), 15, 35 + row * 11, 0xffdddddd);
        }
        if (pages > 1) graphics.centeredText(font, Component.translatable("lss.status.page", Math.floorMod(textPage, pages) + 1, pages), width / 2, height - 40, 0xffaaaaaa);
    }
    // Modern Sodium closes on Escape RELEASE. Keep the whole key pair here so
    // returning on PRESS cannot deliver that same release to the restored parent.
    @Override public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
        return escape.press(event.isEscape()) || super.keyPressed(event);
    }
    @Override public boolean keyReleased(net.minecraft.client.input.KeyEvent event) {
        if (escape.release(event.isEscape())) {
            onClose();
            return true;
        }
        return super.keyReleased(event);
    }
    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() { onReturn.run(); minecraft.setScreen(parent); }
}
