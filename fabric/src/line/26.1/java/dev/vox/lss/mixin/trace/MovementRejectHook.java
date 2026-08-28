// OVERLAY OF fabric/src/main/java/dev/vox/lss/mixin/trace/MovementRejectHook.java @ c675dfcbf9f17ee01fe2e46a0f23e544fbc28429e6bf890d35f679122572ef14
// 26.1 line overlay (single-branch-consolidation-plan.md §3.2). Refresh the stamp when the shared file changes.
package dev.vox.lss.mixin.trace;

import dev.vox.lss.trace.MoveDesyncHooks;
import dev.vox.lss.trace.MoveDesyncTracer;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Slice;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The move-desync tracer's four observation points in {@code handleMovePlayer}
 * (move-desync-tracer-plan.md §1.2). Every body is one static-gate check plus a delegation
 * to {@link MoveDesyncHooks} (the {@code ChunkSaveDataHook} pattern — logic in a mixin is
 * untestable logic); all injects are {@code require = 0} and this class lives in the
 * non-required {@code lss-trace.mixins.json}, so any drift degrades to missing rows, never
 * a crash (§0 constraint 4).
 *
 * <p>26.1 line: the census is byte-for-byte identical on 26.1 AND 26.1.2 (one
 * warn(String,Object[]), one warn(String,Object), three teleport(DDDFF)V, the wrongly
 * warn between teleports #2 and #3 — re-verified at the v0.11.0 review, 2026-08-15).
 *
 * <p>Targeting scheme (reviews F-6/F-7, verified against the 26.2 bytecode): the two
 * vanilla warns have DISTINCT slf4j descriptors — {@code warn(String,Object[])} is the
 * "moved too quickly!" site, {@code warn(String,Object)} the "moved wrongly!" site — so no
 * ordinals are needed there ({@code remap = false}: slf4j is not an MC class). The
 * rejection teleport is anchored semantically: "the first {@code teleport(DDDFF)V} after
 * the wrongly-warn site" via a {@code @Slice}, which also fires for the SILENT
 * {@code isEntityCollidingWithAnythingNew} rejection (bytecode-ordered after the warn,
 * whether or not the warn executed at runtime). A bare ordinal would silently retarget if
 * vanilla reordered its teleports; the slice encodes what the code means, and the Tier 1
 * ASM contract scan is its tripwire.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public class MovementRejectHook {

    /** Pre-move position captured at the post-ensure entry (review F-5): at that point
     *  the entity has not moved — these equal the method's {@code startX/Y/Z} locals
     *  exactly, making claimed-target recomputation exact for Rot/StatusOnly packets
     *  whose {@code packet.get*(default)} falls back to the current position. Also the
     *  rejection's {@code restored} target. */
    @Unique
    private double lss$startX;

    @Unique
    private double lss$startY;

    @Unique
    private double lss$startZ;

    /** True once the post-ensure capture has run at least once. Java-default false with
     *  no initializer (mixin field initializers do not merge), so a partially-applied
     *  config — every inject is require = 0 — degrades to {@code restored} ABSENT
     *  instead of a confidently-wrong zero triple (review C-10). */
    @Unique
    private boolean lss$startCaptured;

    /** Packet-identity token, not a boolean (review F-3): {@code logged_wrongly} is "the
     *  wrongly warn fired for THIS packet", self-expiring with the packet — no HEAD clear
     *  to forget, and a partially-applied mixin degrades to {@code logged_wrongly:false}
     *  instead of latching true. Retains a strong reference to the last rejected packet
     *  for the listener's lifetime — a few hundred bytes that die with the connection;
     *  deliberate (Fable F2-11). */
    @Unique
    private Object lss$wronglyPacket;

    // NOT a HEAD inject: handleMovePlayer's first statement is
    // PacketUtils.ensureRunningOnSameThread, which on the NETTY thread schedules the
    // packet onto the main thread and THROWS — so a HEAD inject runs on the netty
    // event loop for every packet (and again on the server thread), racing the gap
    // clock and the @Unique captures (review A-1/C-1). Injecting AFTER that call
    // executes exactly once, on the server thread, before anything has moved the
    // player — the F-5 capture semantics preserved verbatim. The Tier 1 ASM pin
    // asserts ensureRunningOnSameThread is the method's first INVOKE.
    @Inject(method = "handleMovePlayer",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/server/level/ServerLevel;)V",
                    shift = At.Shift.AFTER),
            require = 0)
    private void lss$onMoveHead(ServerboundMovePlayerPacket packet, CallbackInfo ci) {
        if (!MoveDesyncTracer.enabled()) return;
        var self = (ServerGamePacketListenerImpl) (Object) this;
        this.lss$startX = self.player.getX();
        this.lss$startY = self.player.getY();
        this.lss$startZ = self.player.getZ();
        this.lss$startCaptured = true;
        MoveDesyncHooks.onMoveHead(self);
    }

    @Inject(method = "handleMovePlayer",
            at = @At(value = "INVOKE",
                    target = "Lorg/slf4j/Logger;warn(Ljava/lang/String;[Ljava/lang/Object;)V",
                    remap = false),
            require = 0)
    private void lss$onMovedTooQuickly(ServerboundMovePlayerPacket packet, CallbackInfo ci) {
        if (!MoveDesyncTracer.enabled()) return;
        var self = (ServerGamePacketListenerImpl) (Object) this;
        MoveDesyncHooks.onMovedTooQuickly(self, packet, lss$startX, lss$startY, lss$startZ, lss$startCaptured);
    }

    @Inject(method = "handleMovePlayer",
            at = @At(value = "INVOKE",
                    target = "Lorg/slf4j/Logger;warn(Ljava/lang/String;Ljava/lang/Object;)V",
                    remap = false),
            require = 0)
    private void lss$onMovedWrongly(ServerboundMovePlayerPacket packet, CallbackInfo ci) {
        if (!MoveDesyncTracer.enabled()) return;
        var self = (ServerGamePacketListenerImpl) (Object) this;
        this.lss$wronglyPacket = packet;
        MoveDesyncHooks.onMovedWrongly(self, packet, lss$startX, lss$startY, lss$startZ, lss$startCaptured);
    }

    @Inject(method = "handleMovePlayer",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/server/network/ServerGamePacketListenerImpl;teleport(DDDFF)V",
                    ordinal = 0),
            slice = @Slice(from = @At(value = "INVOKE",
                    target = "Lorg/slf4j/Logger;warn(Ljava/lang/String;Ljava/lang/Object;)V",
                    remap = false)),
            require = 0)
    private void lss$onMoveRejected(ServerboundMovePlayerPacket packet, CallbackInfo ci) {
        if (!MoveDesyncTracer.enabled()) return;
        var self = (ServerGamePacketListenerImpl) (Object) this;
        MoveDesyncHooks.onMoveRejected(self, packet, lss$wronglyPacket == packet,
                lss$startX, lss$startY, lss$startZ, lss$startCaptured);
    }
}
