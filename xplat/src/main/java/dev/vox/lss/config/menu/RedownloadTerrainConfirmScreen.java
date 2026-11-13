package dev.vox.lss.config.menu;

import dev.vox.lss.common.Brand;
import dev.vox.lss.common.LSSLogger;
import dev.vox.lss.common.diagnostics.ScreenEscapeRelease;
import dev.vox.lss.networking.client.ClientCommandActions;
import dev.vox.lss.networking.client.ClientCommandActions.ClearCacheScope;
import dev.vox.lss.networking.client.ClientStatusScreen;
import it.unimi.dsi.fastutil.booleans.BooleanConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The confirm step of the Re-download Terrain button
 * (docs/planning/2026-11-12-redownload-terrain-button-plan.md). It clears the download
 * records so the server sends distant terrain again; it is {@code clearcache} with the scope
 * shown first:
 *
 * <ul>
 *   <li>{@code CURRENT_SERVER}: this server's terrain is downloaded again;</li>
 *   <li>{@code ALL_SERVERS} (singleplayer, title screen): every server's records;</li>
 *   <li>{@code NOT_RECEIVING} (on a server that is sending nothing): nothing can be cleared,
 *       so the screen only explains that and offers Back.</li>
 * </ul>
 *
 * <p>The scope is captured when the screen opens and read again on confirm. If it changed,
 * nothing is cleared and the screen is shown again with the new text: a scope the player was
 * not shown never runs. A clear that throws is reported, never rethrown (a screen click has
 * no brigadier around it to catch the failure the command survives). Every path returns to
 * the SAME parent instance: Sodium keeps the player's staged edits in its option objects, and
 * a new settings screen would reset them.
 *
 * <p>Escape is swallowed on the press and acted on at the release ({@link ScreenEscapeRelease}).
 * Vanilla's confirm screen answers on the press; Sodium's settings screen would then get the
 * release of the same key, close, and undo the player's pending changes.
 *
 * <p>LINE FLAVOR: showing a screen ({@code setScreenAndShow}), the toast manager
 * ({@code gui.toastManager()}), the key event type and vanilla's {@code addButtons(LinearLayout)}.
 */
public final class RedownloadTerrainConfirmScreen extends ConfirmScreen {

    private final Screen parent;
    private final ClearCacheScope shown;
    private final Supplier<ClearCacheScope> scope;
    private final Consumer<Consumer<Component>> clear;
    private final ScreenEscapeRelease escape = new ScreenEscapeRelease();
    private boolean answered;

    /** The button's entry point. A failure here costs the dialog, never the game. */
    public static void open(Screen parent) {
        try {
            show(create(parent, ClientCommandActions::clearCacheScope, ClientCommandActions::clearCache));
        } catch (RuntimeException failure) {
            LSSLogger.warn("Re-download Terrain could not open its confirm screen", failure);
        }
    }

    /** The scope read and the clear are parameters so the client gametest can drive the real
     *  screen without touching a cache; production passes {@link ClientCommandActions}. */
    public static RedownloadTerrainConfirmScreen create(Screen parent, Supplier<ClearCacheScope> scope,
                                                        Consumer<Consumer<Component>> clear) {
        return create(parent, scope.get(), scope, clear);
    }

    private static RedownloadTerrainConfirmScreen create(Screen parent, ClearCacheScope shown,
                                                         Supplier<ClearCacheScope> scope,
                                                         Consumer<Consumer<Component>> clear) {
        var answer = new Answer();
        var screen = new RedownloadTerrainConfirmScreen(answer, parent, shown, scope, clear);
        answer.screen = screen;
        return screen;
    }

    /** Vanilla wants its callback before {@code this} exists. */
    private static final class Answer implements BooleanConsumer {
        private RedownloadTerrainConfirmScreen screen;

        @Override
        public void accept(boolean confirmed) {
            screen.answer(confirmed);
        }
    }

    private RedownloadTerrainConfirmScreen(Answer answer, Screen parent, ClearCacheScope shown,
                                           Supplier<ClearCacheScope> scope,
                                           Consumer<Consumer<Component>> clear) {
        super(answer,
                Component.translatable(shown == ClearCacheScope.NOT_RECEIVING
                        ? "lss.redownload.title.not_receiving" : "lss.redownload.title"),
                message(shown),
                Component.translatable("lss.redownload.confirm"),
                shown == ClearCacheScope.NOT_RECEIVING ? CommonComponents.GUI_BACK : CommonComponents.GUI_CANCEL);
        this.parent = parent;
        this.shown = shown;
        this.scope = scope;
        this.clear = clear;
    }

    /** What will happen for this scope, then the two facts every variant states: saved terrain
     *  stays, and which command deletes it. The command root is an argument so the Voxy Server
     *  Side jar names its own. */
    private static Component message(ClearCacheScope shown) {
        String key = switch (shown) {
            case CURRENT_SERVER -> "lss.redownload.this_server";
            case ALL_SERVERS -> "lss.redownload.all_servers";
            case NOT_RECEIVING -> "lss.redownload.not_receiving";
        };
        return Component.translatable(key).append("\n\n")
                .append(Component.translatable("lss.redownload.saved_terrain", Brand.clientCommand()));
    }

    /** With nothing to clear there is nothing to confirm: Back is the only button. */
    @Override
    protected void addButtons(LinearLayout buttons) {
        if (shown != ClearCacheScope.NOT_RECEIVING) {
            super.addButtons(buttons);
            return;
        }
        this.noButton = buttons.addChild(Button.builder(this.noButtonComponent,
                button -> this.callback.accept(false)).build());
        this.yesButton = this.noButton; // vanilla's delay tick writes both fields
    }

    /** Cancel, never the first button: Sodium opens this on Enter as well as on a click, and a
     *  held Enter must not confirm. */
    @Override
    protected void setInitialFocus() {
        setInitialFocus(this.noButton);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        return escape.press(event.isEscape()) || super.keyPressed(event);
    }

    @Override
    public boolean keyReleased(KeyEvent event) {
        if (escape.release(event.isEscape())) {
            onClose();
            return true;
        }
        return super.keyReleased(event);
    }

    /** Closing is "no": back to the parent, never out of the menu. */
    @Override
    public void onClose() {
        answer(false);
    }

    private void answer(boolean confirmed) {
        if (answered) {
            return;
        }
        answered = true;
        if (!confirmed || shown == ClearCacheScope.NOT_RECEIVING) {
            show(parent);
            return;
        }
        Screen next = parent;
        try {
            ClearCacheScope now = scope.get();
            if (now != shown) {
                // The connection changed while this was open. Ask again with the new text.
                next = create(parent, now, scope, clear);
                return;
            }
            List<Component> lines = new ArrayList<>();
            Component summary;
            try {
                clear.accept(lines::add);
                summary = Component.translatable(shown == ClearCacheScope.ALL_SERVERS
                        ? "lss.redownload.toast.all_servers" : "lss.redownload.toast.this_server");
            } catch (RuntimeException failure) {
                LSSLogger.warn("Re-download Terrain could not clear the download records", failure);
                summary = Component.translatable("lss.redownload.failed");
                lines.add(summary);
            }
            SystemToast.add(minecraft.gui.toastManager(), SystemToast.SystemToastId.PERIODIC_NOTIFICATION,
                    Component.translatable("lss.config.redownload_terrain"), summary);
            lines.forEach(ClientStatusScreen::sendChatFeedback);
        } catch (RuntimeException failure) {
            LSSLogger.warn("Re-download Terrain did not finish", failure);
        } finally {
            show(next);
        }
    }

    private static void show(Screen screen) {
        Minecraft.getInstance().setScreenAndShow(screen);
    }
}
