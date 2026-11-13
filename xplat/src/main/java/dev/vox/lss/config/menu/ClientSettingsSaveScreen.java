package dev.vox.lss.config.menu;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Failed Apply owns a retained draft independent of Sodium's already-clean controls. */
public final class ClientSettingsSaveScreen extends Screen {
    private final Screen parent;
    private final ClientSettingsEditSession draft;
    private final dev.vox.lss.common.diagnostics.ScreenEscapeRelease escape =
            new dev.vox.lss.common.diagnostics.ScreenEscapeRelease();
    private ClientSettingsSaveScreen(Screen parent, ClientSettingsEditSession draft) {
        super(Component.translatable("lss.settings.save_failed"));
        this.parent = parent;
        this.draft = draft;
    }
    public static void show(ClientSettingsEditSession draft) {
        var client = Minecraft.getInstance();
        if (client == null) return;
        // Defer until Sodium's Apply iteration has returned.
        client.execute(() -> client.setScreen(new ClientSettingsSaveScreen(client.screen, draft)));
    }
    @Override protected void init() {
        int x = width / 2 - 155;
        boolean conflict = draft.outcome() == ClientSettingsEditSession.Outcome.CONFLICT;
        addRenderableWidget(Button.builder(Component.translatable(conflict
                ? "lss.settings.rebase" : "lss.settings.retry"), button -> {
            boolean saved = conflict ? draft.rebaseAndApply() : draft.apply();
            if (saved) { SodiumDraftRefresh.resetOwnBindings(parent); onClose(); }
            else rebuildWidgets();
        }).bounds(x, height - 40, 100, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("lss.settings.keep_draft"), button -> onClose())
                .bounds(x + 105, height - 40, 100, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("lss.settings.discard"), button -> {
            draft.discard();
            SodiumDraftRefresh.resetOwnBindings(parent);
            onClose();
        }).bounds(x + 210, height - 40, 100, 20).build());
    }
    @Override public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        graphics.fill(0, 0, width, height, 0xe0101010);
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        graphics.centeredText(font, title, width / 2, 20, 0xffffffff);
        Component message = Component.translatable(draft.outcome() == ClientSettingsEditSession.Outcome.CONFLICT
                ? "lss.settings.conflict_detail" : "lss.settings.failure_detail");
        int y = 48;
        for (var line : font.split(message, Math.max(80, width - 40))) {
            graphics.text(font, line, 20, y, 0xffffffff);
            y += 12;
        }
        if (draft.error() != null) for (var line : font.split(Component.translatable("lss.settings.error_logged"), Math.max(80, width - 40))) {
            graphics.text(font, line, 20, y, 0xffffaaaa);
            y += 12;
        }
    }
    @Override public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
        return escape.press(event.isEscape()) || super.keyPressed(event);
    }
    @Override public boolean keyReleased(net.minecraft.client.input.KeyEvent event) {
        if (escape.release(event.isEscape())) { onClose(); return true; }
        return super.keyReleased(event);
    }
    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() { minecraft.setScreen(parent); }
}
