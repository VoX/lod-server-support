package dev.vox.lss.mixin;

import net.minecraft.world.level.biome.BiomeManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * The world axis (cache-alias-keying-and-reset-override-plan.md §1.3): the obfuscated
 * seed, client-side, without a protocol change.
 *
 * <p>The chain (bytecode-verified on 26.2; re-verified on 1.21.11 at the v0.14 port): the server writes
 * {@code BiomeManager.obfuscateSeed(level.getSeed())} into {@code CommonPlayerSpawnInfo},
 * the client's {@code handleLogin} passes {@code CommonPlayerSpawnInfo.seed()} to the
 * {@code ClientLevel} constructor, and {@code Level.<init>} hands that parameter
 * straight to {@code new BiomeManager(this, seed)} — no second obfuscation pass. So
 * {@code biomeZoomSeed} IS the value the login packet carried, and reading it here is
 * equivalent to reading the packet, with no packet handler to hook.
 *
 * <p>It is a hashed seed, not the raw world seed. That is exactly what is wanted: it is
 * stable per world, identical across every entry address of one proxy network, and it
 * reveals nothing the vanilla client was not already told.
 *
 * <p>Method name is {@code lss$}-prefixed for the same reason every other accessor here is:
 * mixin adds it to the target class, and an unprefixed name could collide with a vanilla
 * method (see {@code AccessorClientPacketListener}).
 *
 * <p>MUST be registered in BOTH loaders' mixin configs. A missing NeoForge entry does
 * not fail loudly — the {@code instanceof} in {@link dev.vox.lss.seed.ClientWorldSeed}
 * simply never matches and NeoForge silently keeps the bare address bucket while
 * Fabric uses the world sub-bucket. {@code SeedAccessorContractTest} pins both files
 * for that reason.
 */
@Mixin(BiomeManager.class)
public interface AccessorBiomeManager {

    /** {@code private final long biomeZoomSeed} — javap-verified present on 26.2 and 1.21.11, and
     *  pinned reflectively by {@code SeedAccessorContractTest}. */
    @Accessor("biomeZoomSeed")
    long lss$getBiomeZoomSeed();
}
