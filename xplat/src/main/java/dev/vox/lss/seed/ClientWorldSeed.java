package dev.vox.lss.seed;

import dev.vox.lss.common.LSSLogger;
import dev.vox.lss.config.LSSClientConfig;
import dev.vox.lss.mixin.AccessorBiomeManager;
import net.minecraft.client.Minecraft;

import java.util.OptionalLong;

/**
 * The world axis's Minecraft-facing half: build a {@link WorldSubKey.Context} out of
 * the live client. Deliberately thin — every DECISION lives in {@link WorldSubKey},
 * which is pure and unit-tested; this class only reads, and is exercised in-game.
 *
 * <p>Called at every manager build AND at every dimension/cache-phase entry (plan
 * §2.1's fresh-per-derive rule): per-world seeds are real — the respawn packet carries
 * one per destination world — and 26.2 re-sends spawn info through the config state on
 * a proxy backend switch, so each derive point reads whatever seed the CURRENT level
 * carries rather than keeping a stale one. {@code Minecraft.level} is non-null at every
 * real derive point (the session config arrives long after the level exists; a
 * dimension entry IS a level).
 *
 * <p>No timing hazard, no state: an unreadable read is the caller's carry-forward
 * question ({@link WorldSubKey#carryForward}), never latched here.
 */
public final class ClientWorldSeed {

    private ClientWorldSeed() {
    }

    /** The live context, with the {@code useWorldSubBuckets} switch folded in. */
    public static WorldSubKey.Context context() {
        return context(LSSClientConfig.CONFIG.useWorldSubBuckets);
    }

    /** Seam for the switch (config-independent tests). */
    public static WorldSubKey.Context context(boolean subBucketsEnabled) {
        var mc = Minecraft.getInstance();
        var serverData = mc.getCurrentServer();
        boolean singleplayer = mc.getSingleplayerServer() != null;
        // The world axis's remote-eligibility: every shape that is NOT a single-player
        // world qualifies — the direct remote test (serverData with an ip), and BOTH
        // "unknown" flavors (no serverData at all, and serverData with a null ip; the
        // glue buckets both as `unknown`, and stock Voxy splits its UNKNOWN twin by
        // world id, so LSS must not go coarser there — plan §2.3). Single-player worlds
        // (which have seeds) are excluded here AND by the predicate's own term; a stale
        // currentServer surviving into single-player is likewise killed by that term.
        boolean remote = (serverData != null && serverData.ip != null) || !singleplayer;
        boolean realm = serverData != null && serverData.isRealm();
        OptionalLong seed = readBiomeZoomSeed(mc, subBucketsEnabled);
        return new WorldSubKey.Context(subBucketsEnabled, remote, realm, singleplayer,
                seed.isPresent(), seed.orElse(0L));
    }

    /**
     * Reads {@code BiomeManager.biomeZoomSeed} through the {@code @Accessor}.
     *
     * <p>Empty means "we could not read it", and every reason lands here identically: no
     * level yet, or the mixin never applied (a loader whose config missed the entry — the
     * {@code instanceof} is the graceful-degradation seam, and it is why the value is never
     * assumed). Contained: a seed read must never be the thing that breaks a join.
     *
     * <p>{@code diagnose} keeps "switch off = zero behaviour change" true of the LOG as
     * well as the key: with the switch on, a failed read IS the difference between the
     * two behaviours and is worth one debug line.
     */
    private static OptionalLong readBiomeZoomSeed(Minecraft mc, boolean diagnose) {
        try {
            var level = mc.level;
            if (level == null) return OptionalLong.empty();
            if (level.getBiomeManager() instanceof AccessorBiomeManager accessor) {
                return OptionalLong.of(accessor.lss$getBiomeZoomSeed());
            }
            if (diagnose) {
                LSSLogger.debug("world-seed accessor not applied — keeping the bare address bucket");
            }
            return OptionalLong.empty();
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError vme) throw vme;
            if (diagnose) {
                LSSLogger.debug("world seed unreadable (" + t + ") — keeping the bare address bucket");
            }
            return OptionalLong.empty();
        }
    }
}
