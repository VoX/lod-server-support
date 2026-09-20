package dev.vox.lss.common.diagnostics;

/** Keep an Escape press/release pair on one screen before returning to its parent. */
public final class ScreenEscapeRelease {
    private boolean pressed;

    public boolean press(boolean escape) {
        if (!escape) return false;
        pressed = true;
        return true;
    }

    public boolean release(boolean escape) {
        if (!escape || !pressed) return false;
        pressed = false;
        return true;
    }
}
