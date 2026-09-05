package dev.vox.lss.platform;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;

import java.nio.file.Path;

/**
 * The per-loader seam (neoforge-support-plan.md §1.1): loader facts + payload
 * sends. Each loader entrypoint installs its impl as the FIRST act after
 * {@code Brand.load} — on Fabric that is {@code LSSMod} (the ModInitializer
 * runs before the ClientModInitializer), with the client entrypoint
 * re-installing the client-capable impl. xplat code must not reference a
 * loader API (pinned by {@code XplatLoaderPurityTest}); everything it needs
 * from the loader passes through this interface.
 *
 * <p><b>Send semantics ("sendIfListening", plan §1.2):</b> {@link #sendToPlayer}
 * and {@link #sendToServer} behave like Fabric's networking API — a send to a
 * peer that has not negotiated the channel is a SILENT no-op (the peer would
 * ignore the packet anyway). NeoForge THROWS in exactly that situation, so its
 * impl contains that one failure shape; connection-level failures still
 * propagate to the callers' send-failure ladders, which own them.
 */
public interface LoaderServices {

    boolean isModLoaded(String modId);

    Path configDir();

    Path gameDir();

    /** Server→client payload send (sendIfListening semantics — see class javadoc). */
    void sendToPlayer(ServerPlayer player, CustomPacketPayload payload);

    /**
     * Client→server payload send (sendIfListening semantics — see class javadoc).
     * Physical-client impls only; the dedicated-server impl throws
     * {@link IllegalStateException}.
     */
    void sendToServer(CustomPacketPayload payload);

    /**
     * Whether {@code player} holds {@code node}
     * (service-permission-gate-plan.md §2.1). {@code defaultValue} is what an
     * absent/unresolvable permission provider answers — the service gate always
     * passes TRUE (its nodes are default-true everywhere), so no provider = serve
     * everyone, the pre-gate behavior; the far-player HIDE nodes pass FALSE (a
     * grant-model deny-me lever — far-player-render-hardening-plan.md WI-7a), so no
     * provider = nobody hidden. Implementations NEVER throw; doubt answers
     * the default (which is why the hide read is fail-VISIBLE here, unlike Paper's
     * Folia-motivated fail-hidden). Fabric overrides via the reflective fabric-permissions-api
     * bridge; NeoForge via its native PermissionAPI nodes; Paper does not route
     * through this seam (Bukkit permissions, read in the paper module).
     */
    default boolean checkPermission(ServerPlayer player, String node, boolean defaultValue) {
        return defaultValue;
    }

    /**
     * The diag token naming this loader's permission backend for the {@code Gate:}
     * line — a loader fact {@code DiagnosticsFormatter} (common) cannot compute.
     * "none" = the default-only fallback above.
     */
    default String permissionProviderToken() {
        return "none";
    }

    /**
     * The installed impl. When no entrypoint has installed one yet (Tier-1 JUnit
     * runs under fabric-loader-junit never run entrypoints), falls back to the
     * loader module's {@link java.util.ServiceLoader} registration
     * (META-INF/services in each loader's MAIN resources — the same file the
     * neoforge module ships). Production still installs explicitly: the
     * entrypoints run before any consumer, and the client entrypoint's install
     * upgrades to the client-capable impl, which ServiceLoader deliberately
     * does not register (a client-only class must never load on a dedicated
     * server).
     */
    static LoaderServices get() {
        LoaderServices services = Holder.INSTANCE;
        if (services == null) {
            synchronized (Holder.class) {
                services = Holder.INSTANCE;
                if (services == null) {
                    var it = java.util.ServiceLoader.load(
                            LoaderServices.class, LoaderServices.class.getClassLoader()).iterator();
                    if (!it.hasNext()) {
                        throw new IllegalStateException("LoaderServices not installed and no"
                                + " ServiceLoader registration found — the loader module must"
                                + " install() from its entrypoint or ship the services file");
                    }
                    services = it.next();
                    Holder.INSTANCE = services;
                }
            }
        }
        return services;
    }

    /** Entrypoint-first installation; later installs overwrite (the client entrypoint upgrades
     *  the common impl to the client-capable one; tests inject fakes the same way). Locked so
     *  an install can never interleave inside {@link #get()}'s fallback critical section and
     *  be overwritten by the ServiceLoader-resolved common impl. */
    static void install(LoaderServices services) {
        synchronized (Holder.class) {
            Holder.INSTANCE = services;
        }
    }

    /** Mutable holder — an interface cannot hold non-final state directly. */
    final class Holder {
        private static volatile LoaderServices INSTANCE;

        private Holder() {
        }
    }
}
