package me.libraryaddict.disguise;

import org.bukkit.entity.Entity;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * Real-package-name TEST STUB of LibsDisguises' API surface (the {@code net.caffeinemc}
 * stub precedent): {@link dev.vox.lss.paper.LibsDisguisesBridge} resolves this class by
 * name through the default resolver, so the bound signature is exercised for real —
 * {@code public static boolean isDisguised(Entity disguised)}, the shape verified against
 * LibsDisguises master {@code DisguiseAPI.java:410}. Never shipped ({@code src/test}).
 *
 * <p>Switches: {@link #DISGUISED} (identity set) answers the read; {@link #THROW} makes the
 * next read throw that exact {@link Throwable} (an {@link Error} included — the bridge's
 * wrap is what the ladder's {@code catch (Exception)} depends on); {@link #CALLS} counts
 * reads so the ladder-order pins can prove a rung was never consulted.
 */
public final class DisguiseAPI {

    public static final Set<Entity> DISGUISED = Collections.newSetFromMap(new IdentityHashMap<>());
    public static volatile Throwable THROW;
    public static volatile int CALLS;

    private DisguiseAPI() {
    }

    public static boolean isDisguised(Entity disguised) {
        CALLS++;
        Throwable t = THROW;
        if (t != null) throw sneaky(t);
        return DISGUISED.contains(disguised);
    }

    public static void reset() {
        DISGUISED.clear();
        THROW = null;
        CALLS = 0;
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> RuntimeException sneaky(Throwable t) throws T {
        throw (T) t;
    }
}
