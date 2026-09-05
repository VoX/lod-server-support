package dev.vox.lss.testutil;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Mutable control state for the {@code me.drex.vanish.api.VanishAPI} test stub (an
 *  interface of static methods cannot hold mutable fields itself). Reset between tests. */
public final class VanishStubState {
    public static final Set<UUID> VANISHED = new HashSet<>();
    public static final UUID[] LAST_ACTOR = new UUID[1];
    public static final boolean[] THROW = new boolean[1];

    private VanishStubState() {
    }

    public static void reset() {
        VANISHED.clear();
        LAST_ACTOR[0] = null;
        THROW[0] = false;
    }

    public static void maybeThrow() {
        if (THROW[0]) throw new IllegalStateException("vanish backend mid-reload");
    }
}
