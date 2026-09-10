package dev.vox.lss.mixin;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A status entry in both Sodium generations, using only vanilla widgets/mappings.
 * Install after every widget clear so page switches retain it. No optional classes
 * are loaded, and status collection never runs from this screen hook. */
@Mixin(Screen.class)
public abstract class SodiumStatusEntryHook {
    @Shadow public int width;
    @Shadow public int height;
    @Shadow public abstract java.util.List<? extends GuiEventListener> children();
    @org.spongepowered.asm.mixin.Unique private Button lss$statusButton;
    @org.spongepowered.asm.mixin.Unique private boolean lss$statusModern;
    @org.spongepowered.asm.mixin.Unique private boolean lss$statusLayoutPending;
    @Shadow protected abstract <T extends GuiEventListener & Renderable & NarratableEntry> T addRenderableWidget(T widget);

    @Inject(method = "clearWidgets", at = @At("RETURN"))
    private void lss$statusEntry(CallbackInfo callback) {
        Class<?> type = getClass();
        boolean sodium = false;
        lss$statusButton = null;
        for (int depth = 0; type != null && depth < 8; depth++, type = type.getSuperclass()) {
            String name = type.getName();
            if (name.equals("net.caffeinemc.mods.sodium.client.gui.SodiumOptionsGUI")
                    || name.equals("me.jellysquid.mods.sodium.client.gui.SodiumOptionsGUI")
                    || name.equals("net.caffeinemc.mods.sodium.client.gui.VideoSettingsScreen")) {
                sodium = true;
                lss$statusModern = name.endsWith("VideoSettingsScreen");
                break;
            }
        }
        if (!sodium) return;
        Screen parent = (Screen) (Object) this;
        lss$statusButton = addRenderableWidget(Button.builder(Component.translatable("lss.status.open"), button ->
                net.minecraft.client.Minecraft.getInstance().setScreen(
                        new dev.vox.lss.networking.client.ClientStatusScreen(parent,
                                dev.vox.lss.config.menu.SodiumStatusReturn.capture(parent))))
                .bounds(0, 0, 105, 20).build());
        lss$statusButton.visible = false;
        lss$statusLayoutPending = true;
    }
    @Inject(method = "extractRenderState", at = @At("HEAD"))
    private void lss$placeStatusEntry(net.minecraft.client.gui.GuiGraphicsExtractor graphics,
            int mouseX, int mouseY, float delta, CallbackInfo callback) {
        if (!lss$statusLayoutPending || lss$statusButton == null) return;
        lss$statusLayoutPending = false;
        var bounds = dev.vox.lss.common.diagnostics.StatusEntryLayout.find(width, height, lss$statusModern,
                (x, y) -> {
                    for (var child : children())
                        if (child != lss$statusButton && child.isMouseOver(x, y)) return true;
                    return false;
                });
        if (bounds == null) return;
        lss$statusButton.setX(bounds.x());
        lss$statusButton.setY(bounds.y());
        lss$statusButton.setWidth(bounds.width());
        lss$statusButton.visible = true;
    }

}
