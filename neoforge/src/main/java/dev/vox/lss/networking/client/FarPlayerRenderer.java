package dev.vox.lss.networking.client;

/**
 * NeoForge far-player renderer — the fabric {@code FarPlayerRenderer}'s
 * same-FQN twin, satisfying xplat's compile coupling
 * (the xplat session-end path calls {@link #clearInstance()}).
 * <b>N-2 state: the render path is a NO-OP</b> — per the plan's precise cut
 * form (§N-3): the tracker, wire channels, and capability arm term are
 * untouched (the bit is the PREFS CARRIER); only rendering is absent. N-3
 * attempts the real renderer on the RenderLevelStageEvent/Extract pair.
 */
public final class FarPlayerRenderer {

    /** Whether THIS loader's tree renders far players (the options catalog hides the
     *  renderer-only options where it does not — sodium-options-page-generations-plan.md
     *  implementation review). NeoForge v1: the render path is a no-op stub. */
    public static final boolean RENDER_AVAILABLE = false;

    private FarPlayerRenderer() {
    }

    /** Session-end proxy teardown — nothing to clear while the render path is a no-op. */
    static void clearInstance() {
    }

    /** The /lss diag line (far-player-render-hardening-plan.md WI-10) — the xplat command
     *  gates the call on {@link #RENDER_AVAILABLE}, so this twin only satisfies the compile
     *  coupling; it never prints on this loader. */
    public static String diagLine() {
        return "FarPlayerRender: off (no renderer on this loader)";
    }
}
