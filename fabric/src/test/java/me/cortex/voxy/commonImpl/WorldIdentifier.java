package me.cortex.voxy.commonImpl;

import net.minecraft.world.level.Level;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Test stub of Voxy's WorldIdentifier, mirroring the exact shape VoxyCompat resolves via
 * MethodHandles: {@code static WorldIdentifier of(Level)}. General-purpose — shared by
 * VoxyCompatTest and the SpiralScanner voxy-distance tests. Control {@link #returned},
 * call {@link #reset()} between tests.
 */
public final class WorldIdentifier {

    /** Returned by {@link #of}; set null to simulate "Voxy has no storage for this world yet". */
    public static volatile WorldIdentifier returned = new WorldIdentifier();

    /** Number of {@link #of} invocations since the last {@link #reset()}. */
    public static final AtomicInteger ofCalls = new AtomicInteger();

    public static WorldIdentifier of(Level level) {
        ofCalls.incrementAndGet();
        return returned;
    }

    public static void reset() {
        returned = new WorldIdentifier();
        ofCalls.set(0);
    }
}
