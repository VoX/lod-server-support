package me.drex.vanish.api;

import dev.vox.lss.testutil.VanishStubState;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

import java.util.UUID;

/**
 * TEST STUB of Melius Vanish's {@code VanishAPI} (an interface of static methods) — keep
 * the declarations matching the real mod's: {@code isVanished(Entity)},
 * {@code isVanished(MinecraftServer, UUID)}, {@code canSeePlayer(ServerPlayer actor,
 * ServerPlayer observer)}, {@code canSeePlayer(MinecraftServer, UUID actor, ServerPlayer
 * observer)}, where {@code actor} is the possibly-vanished player and {@code observer} the
 * one looking. {@code MeliusVanishBridge} binds the two {@code (MinecraftServer, UUID…)}
 * overloads; the others are decoys the bind must not touch. Control via
 * {@link VanishStubState}.
 */
public interface VanishAPI {

    static boolean isVanished(Entity entity) {
        throw new AssertionError("the bridge must bind the (MinecraftServer, UUID) overload");
    }

    static boolean isVanished(MinecraftServer server, UUID uuid) {
        VanishStubState.maybeThrow();
        return VanishStubState.VANISHED.contains(uuid);
    }

    static boolean canSeePlayer(ServerPlayer actor, ServerPlayer observer) {
        throw new AssertionError("the bridge must bind the UUID-actor overload");
    }

    static boolean canSeePlayer(MinecraftServer server, UUID actor, ServerPlayer observer) {
        VanishStubState.maybeThrow();
        VanishStubState.LAST_ACTOR[0] = actor;
        return !VanishStubState.VANISHED.contains(actor);
    }
}
