package dev.vox.lss.platform;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;

import java.nio.file.Path;

/**
 * The common (both-physical-sides) Fabric impl of {@link LoaderServices},
 * installed by {@code LSSMod}. Deliberately references no client-only class —
 * {@code FabricClientLoaderServices} (installed by {@code LSSClient}, which
 * runs after) upgrades {@link #sendToServer} on physical clients.
 */
public class FabricLoaderServices implements LoaderServices {

    private final dev.vox.lss.common.diagnostics.DiagnosticVersions diagnosticVersions = captureDiagnosticVersions();
    @Override public dev.vox.lss.common.diagnostics.DiagnosticVersions diagnosticVersions() { return diagnosticVersions; }
    private static dev.vox.lss.common.diagnostics.DiagnosticVersions captureDiagnosticVersions() {
        var values = new java.util.EnumMap<dev.vox.lss.common.diagnostics.DiagnosticVersions.Component, String>(
                dev.vox.lss.common.diagnostics.DiagnosticVersions.Component.class);
        values.put(dev.vox.lss.common.diagnostics.DiagnosticVersions.Component.LSS, FabricLoader.getInstance().getModContainer("lss").map(mod -> mod.getMetadata().getVersion().getFriendlyString()).orElse("absent"));
        values.put(dev.vox.lss.common.diagnostics.DiagnosticVersions.Component.MINECRAFT, FabricLoader.getInstance().getModContainer("minecraft").map(mod -> mod.getMetadata().getVersion().getFriendlyString()).orElse("absent"));
        values.put(dev.vox.lss.common.diagnostics.DiagnosticVersions.Component.LOADER, FabricLoader.getInstance().getModContainer("fabricloader").map(mod -> mod.getMetadata().getVersion().getFriendlyString()).orElse("absent"));
        values.put(dev.vox.lss.common.diagnostics.DiagnosticVersions.Component.SODIUM, FabricLoader.getInstance().getModContainer("sodium").map(mod -> mod.getMetadata().getVersion().getFriendlyString()).orElse("absent"));
        values.put(dev.vox.lss.common.diagnostics.DiagnosticVersions.Component.XAERO, FabricLoader.getInstance().getModContainer("xaeroworldmap").map(mod -> mod.getMetadata().getVersion().getFriendlyString()).orElse("absent"));
        values.put(dev.vox.lss.common.diagnostics.DiagnosticVersions.Component.VOXY, FabricLoader.getInstance().getModContainer("voxy").map(mod -> mod.getMetadata().getVersion().getFriendlyString()).orElse("absent"));
        values.put(dev.vox.lss.common.diagnostics.DiagnosticVersions.Component.CONNECTOR, FabricLoader.getInstance().getModContainer("connector").map(mod -> mod.getMetadata().getVersion().getFriendlyString()).orElse("absent"));
        values.put(dev.vox.lss.common.diagnostics.DiagnosticVersions.Component.C2ME, FabricLoader.getInstance().getModContainer("c2me").map(mod -> mod.getMetadata().getVersion().getFriendlyString()).orElse("absent"));
        return new dev.vox.lss.common.diagnostics.DiagnosticVersions(values);
    }

    public static void installProduction() {
        LoaderServices.install(new FabricLoaderServices());
    }

    @Override
    public boolean isModLoaded(String modId) {
        return FabricLoader.getInstance().isModLoaded(modId);
    }

    @Override
    public Path configDir() {
        return FabricLoader.getInstance().getConfigDir();
    }

    @Override
    public Path gameDir() {
        return FabricLoader.getInstance().getGameDir();
    }

    @Override
    public void sendToPlayer(ServerPlayer player, CustomPacketPayload payload) {
        ServerPlayNetworking.send(player, payload);
    }

    @Override
    public boolean checkPermission(ServerPlayer player, String node, boolean defaultValue) {
        // The reflective fabric-permissions-api rung (service-permission-gate-plan.md
        // §2.1) — absent/unresolvable/throwing all answer the default (fail-open).
        return dev.vox.lss.compat.FabricPermissionsBridge.check(player, node, defaultValue);
    }

    @Override
    public String permissionProviderToken() {
        return dev.vox.lss.compat.FabricPermissionsBridge.providerToken();
    }

    @Override
    public void sendToServer(CustomPacketPayload payload) {
        throw new IllegalStateException("sendToServer on the dedicated-server LoaderServices impl"
                + " — the client entrypoint installs the client-capable impl");
    }
}
