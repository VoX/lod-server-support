package dev.vox.lss.config.menu;

import dev.vox.lss.networking.client.ClientCommandActions.ClearCacheScope;
import net.minecraft.SharedConstants;
import net.minecraft.client.GameNarrator;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.components.toasts.ToastComponent;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.objenesis.ObjenesisStd;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Re-download Terrain confirm screen, run for real (re-download plan §5 and decisions 2
 * to 6). LINE FLAVOR (1.21.1): on the other lines these cases are the client gametest
 * ({@code LSSClientGameTests.runRedownloadDialogTest}); this line has no client gametest
 * task, so the same cases run here in the unit JVM. The screen under test is the production
 * class with its real vanilla superclass: its buttons, its initial focus, its three-int key
 * methods and its answer path all run. What stands in for the client is a constructor-free
 * {@link Minecraft} whose {@code setScreen} records the screen and initialises it the way
 * the client does ({@code Screen.init(Minecraft, int, int)}), with a mocked font, toast
 * list, narrator and sound manager. The scope read and the clear are test doubles, so no
 * cache is touched.
 *
 * <p>Not covered here, and still to be looked at on a live client: the dialog drawn over
 * Sodium's settings screen, real key events arriving from the keyboard (the press and
 * release pair as GLFW delivers it), and the toast on screen.
 */
class RedownloadTerrainConfirmScreenBehaviorTest {

    private static final int ENTER = 257;   // GLFW_KEY_ENTER, InputConstants.KEY_RETURN
    private static final int ESCAPE = 256;  // InputConstants.KEY_ESCAPE on this line

    private Object previousMinecraft;
    private TestMinecraft minecraft;
    private Screen parent;
    private final AtomicInteger runs = new AtomicInteger();
    private final AtomicReference<ClearCacheScope> scope = new AtomicReference<>(ClearCacheScope.CURRENT_SERVER);
    private final Consumer<Consumer<Component>> clear = feedback -> {
        runs.incrementAndGet();
        feedback.accept(Component.literal("unit-test clear"));
    };

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @BeforeEach
    void setup() throws Exception {
        previousMinecraft = instanceField().get(null);
        // Allocated without Minecraft's graphics constructor.
        minecraft = new ObjenesisStd().newInstance(TestMinecraft.class);
        minecraft.toasts = Mockito.mock(ToastComponent.class);
        minecraft.narrator = Mockito.mock(GameNarrator.class);
        minecraft.sounds = Mockito.mock(SoundManager.class);
        var font = Minecraft.class.getDeclaredField("font");
        font.setAccessible(true);
        font.set(minecraft, Mockito.mock(Font.class));
        instanceField().set(null, minecraft);
        // A parent that is only an identity: vanilla's own initial focus asks the client
        // for its last input device, which a constructor-free client does not have.
        parent = new Screen(Component.literal("re-download dialog parent")) {
            @Override protected void setInitialFocus() { }
        };
    }

    @AfterEach
    void cleanup() throws Exception {
        instanceField().set(null, previousMinecraft);
    }

    private static java.lang.reflect.Field instanceField() throws NoSuchFieldException {
        var field = Minecraft.class.getDeclaredField("instance");
        field.setAccessible(true);
        return field;
    }

    private RedownloadTerrainConfirmScreen open() {
        var dialog = RedownloadTerrainConfirmScreen.create(parent, scope::get, clear);
        minecraft.setScreen(dialog);
        assertSame(dialog, minecraft.shown, "the dialog is shown");
        return dialog;
    }

    private static List<Button> buttons(Screen screen) {
        var buttons = new ArrayList<Button>();
        for (var child : screen.children()) {
            if (child instanceof Button button) buttons.add(button);
        }
        return buttons;
    }

    private static boolean isKey(Button button, String key) {
        return button.getMessage().getContents() instanceof TranslatableContents text && text.getKey().equals(key);
    }

    private void assertToasts(int count) {
        Mockito.verify(minecraft.toasts, Mockito.times(count)).addToast(Mockito.any(SystemToast.class));
    }

    @Test
    void confirmRunsTheActionOnceAndReturnsToTheSameParent() {
        var dialog = open();
        var buttons = buttons(dialog);
        assertEquals(2, buttons.size(), "Re-download and Cancel");
        assertTrue(isKey(buttons.get(0), "lss.redownload.confirm"), "the first button confirms");
        assertEquals(CommonComponents.GUI_CANCEL, buttons.get(1).getMessage(), "the second is Cancel");
        assertSame(buttons.get(1), dialog.getFocused(), "Cancel holds the initial focus");
        assertFalse(buttons.get(0).isFocused());

        buttons.get(0).onPress();
        assertSame(parent, minecraft.shown, "back on the SAME parent instance");
        assertEquals(1, runs.get(), "the action ran once");
        assertToasts(1);

        // A second answer on the same instance (a queued click, a held key) is ignored.
        buttons.get(0).onPress();
        buttons.get(1).onPress();
        assertEquals(1, runs.get());
        assertToasts(1);
    }

    /** Sodium opens the dialog on Enter as well as on a click; a held Enter must not confirm. */
    @Test
    void enterOnAFreshDialogLandsOnCancel() {
        var dialog = open();
        assertTrue(dialog.keyPressed(ENTER, 0, 0), "the focused button takes Enter");
        assertSame(parent, minecraft.shown);
        assertEquals(0, runs.get(), "nothing ran");
        assertToasts(0);
    }

    @Test
    void cancelAndClosingAnswerNoAndReturnToTheParent() {
        var dialog = open();
        buttons(dialog).get(1).onPress();
        assertSame(parent, minecraft.shown);

        dialog = open();
        dialog.onClose();
        assertSame(parent, minecraft.shown, "closing is no: back to the parent, never out of the menu");
        assertEquals(0, runs.get());
        assertToasts(0);
    }

    /**
     * Sodium's settings screen closes, and undoes pending changes, on the RELEASE of Escape.
     * Vanilla's confirm screen answers on the press, which would hand the parent that release.
     * So the press is swallowed, the dialog stays, and the release answers no.
     */
    @Test
    void escapeIsSwallowedOnThePressAndActsOnTheRelease() {
        var dialog = open();
        assertFalse(dialog.keyReleased(ESCAPE, 0, 0), "a release without its press is not this dialog's");
        assertSame(dialog, minecraft.shown, "an unpaired Escape release must not close the dialog");

        assertTrue(dialog.keyPressed(ESCAPE, 0, 0), "the press is swallowed");
        assertSame(dialog, minecraft.shown, "Escape must not close the dialog on the press");

        assertTrue(dialog.keyReleased(ESCAPE, 0, 0), "the release is consumed");
        assertSame(parent, minecraft.shown, "and returns to the parent");
        assertEquals(0, runs.get(), "Escape runs nothing");
        assertToasts(0);
    }

    @Test
    void aScopeThatChangedWhileTheDialogWasOpenAsksAgainAndClearsNothing() {
        var dialog = open();
        scope.set(ClearCacheScope.ALL_SERVERS);
        buttons(dialog).get(0).onPress();
        var again = assertInstanceOf(RedownloadTerrainConfirmScreen.class, minecraft.shown,
                "the dialog is shown again with the new text");
        assertNotSame(dialog, again);
        assertEquals(0, runs.get(), "a scope the player was not shown never runs");
        assertToasts(0);
        assertSame(buttons(again).get(1), again.getFocused(), "Cancel holds the focus on the new dialog too");

        buttons(again).get(0).onPress();
        assertSame(parent, minecraft.shown);
        assertEquals(1, runs.get(), "confirmed under the scope that was shown");
        assertToasts(1);
    }

    @Test
    void withNothingToClearThereIsOnlyBack() {
        scope.set(ClearCacheScope.NOT_RECEIVING);
        var dialog = open();
        var buttons = buttons(dialog);
        assertEquals(1, buttons.size(), "one button");
        assertEquals(CommonComponents.GUI_BACK, buttons.get(0).getMessage());
        assertSame(buttons.get(0), dialog.getFocused());

        buttons.get(0).onPress();
        assertSame(parent, minecraft.shown);
        assertEquals(0, runs.get(), "Back runs nothing");

        // Enter and Escape on that variant return too, and still run nothing.
        dialog = open();
        dialog.keyPressed(ENTER, 0, 0);
        assertSame(parent, minecraft.shown);
        dialog = open();
        dialog.keyPressed(ESCAPE, 0, 0);
        dialog.keyReleased(ESCAPE, 0, 0);
        assertSame(parent, minecraft.shown);
        assertEquals(0, runs.get());
        assertToasts(0);
    }

    /** A click has no command dispatcher around it: a clear that throws must be reported. */
    @Test
    void aClearThatThrowsIsReportedAndStillReturnsToTheParent() {
        var dialog = RedownloadTerrainConfirmScreen.create(parent, scope::get, feedback -> {
            runs.incrementAndGet();
            throw new IllegalStateException("unit test: the clear failed");
        });
        minecraft.setScreen(dialog);
        buttons(dialog).get(0).onPress();
        assertSame(parent, minecraft.shown, "no failure may leave the dialog open or close the menu");
        assertEquals(1, runs.get());
        assertToasts(1);   // the failure toast
    }

    private static final class TestMinecraft extends Minecraft {
        private Screen shown;
        private ToastComponent toasts;
        private GameNarrator narrator;
        private SoundManager sounds;

        private TestMinecraft() { super(null); } // never invoked: no native window in unit tests

        /** What the client does that matters here: the screen becomes current and is initialised. */
        @Override public void setScreen(Screen screen) {
            this.shown = screen;
            if (screen != null) screen.init(this, 427, 240);
        }

        @Override public ToastComponent getToasts() { return toasts; }
        @Override public GameNarrator getNarrator() { return narrator; }
        @Override public SoundManager getSoundManager() { return sounds; }
    }
}
