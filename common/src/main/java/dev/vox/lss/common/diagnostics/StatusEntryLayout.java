package dev.vox.lss.common.diagnostics;

/** One-time menu layout against actual vanilla/Sodium child hit areas. No optional API linkage. */
public final class StatusEntryLayout {
    @FunctionalInterface public interface HitTest { boolean occupied(int x, int y); }
    public record Bounds(int x, int y, int width, int height) {}
    public static Bounds find(int width, int height, boolean modern, HitTest hit) {
        // Modern Sodium reserves a right-hand stack of Done/Apply/Undo controls;
        // legacy generations use a bottom action row. Never use the donation/search header.
        int top = modern ? 30 : 45;
        int bottom = height - (modern ? 80 : 30);
        for (int buttonWidth : new int[]{105, 85}) {
            for (int x = width - buttonWidth - 5; x >= 5; x -= buttonWidth + 5) {
                for (int y = bottom - 25; y >= top; y -= 25) {
                    boolean free = true;
                    outer: for (int px = x - 2; px < x + buttonWidth + 2; px++) {
                        for (int py = y - 2; py < y + 22; py++) {
                            if (hit.occupied(px, py)) { free = false; break outer; }
                        }
                    }
                    if (free) return new Bounds(x, y, buttonWidth, 20);
                }
            }
        }
        return null;
    }
    private StatusEntryLayout() {}
}
