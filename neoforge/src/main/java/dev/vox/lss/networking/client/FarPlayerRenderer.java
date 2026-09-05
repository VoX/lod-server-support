package dev.vox.lss.networking.client;

import com.mojang.authlib.GameProfile;
import dev.vox.lss.common.LSSLogger;
import dev.vox.lss.common.farplayers.FarPlayerClientTracker;
import dev.vox.lss.common.farplayers.FarPlayerWire;
import dev.vox.lss.config.LSSClientConfig;
import net.minecraft.client.Minecraft;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityAttachment;
import net.minecraft.world.phys.AABB;
import org.joml.Matrix4f;
import net.minecraft.client.multiplayer.ClientLevel;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ArmorMaterial;
import java.util.IdentityHashMap;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.player.RemotePlayer;
import dev.vox.lss.mixin.AccessorLivingEntity;
import net.minecraft.world.scores.Team;
import net.neoforged.neoforge.common.NeoForgeMod;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The far-player proxy renderer — the NeoForge 1.21.1 twin of the Fabric
 * {@code FarPlayerRenderer} (the SOURCE OF TRUTH; keep the two in step). Same
 * same-FQN {@code dev.vox.lss.networking.client} package; the ONLY deltas from the Fabric
 * twin are the render event ({@link RenderLevelStageEvent} at {@code Stage.AFTER_ENTITIES}
 * in place of Fabric's {@code WorldRenderContext} pass), the entity-load edge trigger
 * ({@link EntityJoinLevelEvent} in place of {@code ClientEntityEvents.ENTITY_LOAD}), and one
 * NeoForge-specific containment (the unseated {@code dispatcher.render} is wrapped — NeoForge
 * fires third-party render events inside it). The buffer source is
 * {@code minecraft.renderBuffers().bufferSource()}; {@code AFTER_ENTITIES} fires at the same
 * instruction boundary Fabric injects at (after vanilla's own post-entity flushes), and the pass
 * ends the ONE shared batch it opened with {@code endLastBatch()} — never the arg-less
 * {@code endBatch()}, which would drain the deferred glint/translucent buffers early (the
 * 2026-09-04 hardening plan superseded the earlier "no explicit flush" decision on exactly
 * this distinction). The E2, FARP §3.3/§7-B {@code RemotePlayer}-proxy immediate-render approach.
 * Differences from SeeU that are DECISIONS, not drift (all review-pinned in the FARP plan):
 *
 * <ul>
 *   <li><b>No glow, ever, by default</b> — SeeU's {@code setGlowingTag(true)} is a
 *       through-wall outline that contradicts the privacy stance.</li>
 *   <li><b>No fog mixin</b> — a proxy beyond fog-end fades like terrain would;
 *       {@code farPlayersMaxRenderDistanceBlocks} is the alignment knob.</li>
 *   <li><b>Handoff = vanilla's own cull test</b> (E2 review M3 — this REPLACES the
 *       planned Euclidean ±16 band, decisions log entry 16): the proxy renders
 *       exactly when the REAL entity would not — {@code real == null || !chunkLoaded
 *       || !real.shouldRenderAtSqrDistance(camDistSq)}. A distance band measured
 *       Euclidean against square chunk geometry both double-rendered at the render
 *       square's diagonal AND left an invisibility annulus at high render distance
 *       (entity cull ~256 blocks sits far inside a 32-chunk circle). Keying on the
 *       same predicate both directions self-synchronizes the swap with vanilla's
 *       entity pop (the exact crossfade), so no hysteresis band is needed;
 *       {@link EntityJoinLevelEvent} (client-side) stays as the same-frame kill when a
 *       real entity spawns in.</li>
 *   <li><b>Mounts (E3, R-10 v1.3)</b>: the rider renders at its OWN wire position
 *       (the server-side seated position already encodes the seat offset); the mount
 *       renders once per vehicle UUID at its own wire position driven by the RIDER's
 *       velocity hint; an edge-triggered persistent ride link (never per-frame
 *       eject/re-seat) makes isPassenger() true at extraction — vanilla's one path
 *       to seated legs. Degrade ladder in {@link FarPlayerMountLadder}: unknown/
 *       uncreatable types render the rider unmounted, ride-link failure renders
 *       standing at the mounted position — never a crash, never a floating sit.</li>
 *   <li><b>Containment</b>: the whole pass is latch-guarded — a renderer bug degrades
 *       to "no proxies" for the session, never a render-thread crash loop.</li>
 *   <li><b>Light, culling, tags, batch end (2026-09-04, far-player-render-hardening-plan.md)</b>:
 *       proxies are lit with a sky-15 FLOOR (or full-bright, {@code farPlayersFullBright}) —
 *       never from the client's stored light data, which reads zero in the C2ME no-tick ring;
 *       the DRAW calls (only) are frustum-culled; LSS draws its own depth-tested, sneak-hidden,
 *       sqrt-scaled name tags (vanilla gates tags at 64 blocks on every line); the pass ends
 *       its own shared batch with {@code endLastBatch()}; the model-parts byte is set so the
 *       overlay skin layers render. See {@code packedLightFor}/{@code isInFrustum}/
 *       {@code renderFarNameTag}.</li>
 * </ul>
 *
 * <p>Threading: every touchpoint runs on the client MAIN thread, which IS the render
 * thread (network receivers hop via execute(); the client-side {@code EntityJoinLevelEvent}
 * fires from {@code ClientLevel.addEntity} during packet handling; {@code AFTER_ENTITIES} is a
 * main-thread render-pass event). {@code EntityJoinLevelEvent}'s {@code isClientSide()} filter
 * is load-bearing TWICE: dist safety AND thread confinement — on an integrated server the same
 * event ALSO fires on the SERVER thread, and the proxy map is render-thread-only. snapshot() is
 * a shallow
 * copy sharing mutable FarPlayerMotion — it is defense-in-depth, NOT a thread
 * boundary; do not move this pass off-thread trusting it (E2 review n9).
 */
public final class FarPlayerRenderer {

    /** Whether THIS loader's tree renders far players (the options catalog hides the
     *  renderer-only options where it does not — sodium-options-page-generations-plan.md
     *  implementation review). NeoForge 1.21.1 now renders far players (was a no-op stub
     *  through v0.13.x; the render path landed in v0.14.0). */
    public static final boolean RENDER_AVAILABLE = true;

    /** Proxy entity-id base: far above vanilla's server-assigned counter AND disjoint
     *  from SeeU's 1_000_000_000 block (both installed must never collide). Each id is
     *  additionally probed against the live level before use. */
    private static final int PROXY_ID_BASE = 1_900_000_000;

    private static final float WALK_ANIMATION_SCALE = 0.4f;
    /** Entity.FLAG_FALL_FLYING (private there) — the bit isFallFlying() reads. */
    private static final int SHARED_FLAG_FALL_FLYING = 7;
    /** Fold (e): the skin OVERLAY layers (jacket/sleeves/pants 1/64 block, hat 1/32 above the
     *  body) share the body's render type, so they cannot be depth-lifted like armor; past
     *  ~80 blocks their gap is under two depth steps and they z-fight the body — beyond this
     *  the proxy shows the base skin only (the cape bit still rides with an elytra). */
    private static final double OVERLAY_MAX_DISTANCE_BLOCKS = 80.0;
    private static final EquipmentSlot[] ARMOR_EQUIPMENT_SLOTS = {EquipmentSlot.HEAD, EquipmentSlot.CHEST,
            EquipmentSlot.LEGS, EquipmentSlot.FEET};

    private static volatile FarPlayerRenderer instance;

    private final Map<UUID, Proxy> proxies = new HashMap<>();
    /** Render-only mount instances by vehicle UUID (R-10): rider-velocity motion,
     *  evicted when no rider references the UUID in the current frame. Two riders
     *  sharing a vehicle render it ONCE (multi-passenger dedup = render dedup). */
    private final Map<UUID, MountInstance> vehicles = new HashMap<>();
    private final Set<UUID> activeVehicles = new HashSet<>();
    private final Set<UUID> submittedVehicles = new HashSet<>();
    private final FarPlayerMountLadder mountLadder = FarPlayerMountLadder.production();
    /** Small identity→Item cache (equipment strings repeat every frame). */
    /** Identity-string → Item memo. CAPPED (review-wave C-M2): every sibling
     *  structure carries explicit hostile-input armor (the tracker's 4096-identity
     *  cap, the mount ladder's 256-type cap) and this map was the one unbounded
     *  one — a hostile server feeding unique equipment identities grew client heap
     *  for the JVM's life. Past the cap, identities resolve uncached (correct,
     *  just unmemoized); the cache also empties with the proxies in clear(). */
    private static final int ITEM_CACHE_CAP = 1024;
    private final Map<String, Item> itemCache = new ConcurrentHashMap<>();
    private int nextProxyId = PROXY_ID_BASE;
    private boolean crashLatched;
    /** The pass's sentinel pose (review fold D1) — see markPose/unwindPose. */
    private PoseStack.Pose passMark;
    /** Last pass's counters for the /lss diag line ({@link #diagLine()}). */
    private int lastDrawn, lastCulled, lastMounts, lastTags;
    /** Once-per-session guard for the per-player containment warn ({@link #dropProxyContained}). */
    private boolean loggedContainedDrop;

    private static final class MountInstance {
        final net.minecraft.world.entity.Entity entity;
        final String typeIdentity;
        final dev.vox.lss.common.farplayers.FarPlayerMotion motion;
        long appliedStamp;
        Vec3 lastWalkPosition;

        MountInstance(net.minecraft.world.entity.Entity entity, String typeIdentity,
                      dev.vox.lss.common.farplayers.FarPlayerMotion motion) {
            this.entity = entity;
            this.typeIdentity = typeIdentity;
            this.motion = motion;
        }
    }

    /** Installed by {@link #initRenderer()} (called from
     *  {@code LSSClient}); static so the session-end path can clear it. */
    static void install(FarPlayerRenderer renderer) {
        instance = renderer;
    }

    /** Session end: proxies die AND the crash latch resets (E2 review m5 — one
     *  contained throw next session beats a per-JVM dead feature). */
    static void clearInstance() {
        var r = instance;
        if (r != null) {
            r.clear();
            r.crashLatched = false;
            r.loggedContainedDrop = false;
            r.mountLadder.reset(); // m7: type latches are per-session, as documented
        }
    }

    /** Per-player render-pass containment for NeoForge's wider throw surface: unlike Fabric,
     *  NeoForge fires third-party listeners INSIDE the render pass — RenderLivingEvent/
     *  RenderNameTagEvent inside dispatcher.render, EntityEvent.Size inside
     *  apply()->setPose->refreshDimensions, and entity hooks at proxy construction. A throw
     *  from any of them drops THIS proxy for the frame (rebuilt next frame) instead of latching
     *  the whole feature off via the whole-pass crash latch. Once-guarded warn so a
     *  persistently-throwing listener cannot spam the log. The mount and seated paths keep their
     *  own granular type-latching containment; this is the backstop for the non-mounted path. */
    private void dropProxyContained(UUID uuid, Set<UUID> active, String phase, Throwable t) {
        proxies.remove(uuid);
        active.remove(uuid);
        if (!loggedContainedDrop) {
            loggedContainedDrop = true;
            LSSLogger.warn("Far-player proxy " + phase + " threw for one player — dropping it"
                    + " this frame; the feature stays up (a third-party render/entity event"
                    + " listener likely threw). Further occurrences are silent this session.", t);
        }
    }

    /** The ENTITY_LOAD edge trigger: a REAL player entity appearing kills its proxy
     *  the same frame (crossfade guard — never render both). Main client thread.
     *  CONTAINED (issue-#160 review MAJOR-1): this fires from ClientLevel's entity-add
     *  path during PACKET HANDLING with no latch above it, and stopRiding dispatches
     *  virtually into the modded vehicle class (Create's removePassenger reads
     *  contraption state a bare client-created instance does not have) — an escaping
     *  throw here crashed the client on the COMMON approach-a-mounted-far-player path.
     *  The proxy is already removed either way; a stuck link dies with it. */
    static void onRealPlayerLoad(UUID uuid) {
        var r = instance;
        if (r != null) {
            var removed = r.proxies.remove(uuid);
            if (removed != null && removed.isPassenger()) {
                try {
                    removed.stopRiding(); // m2: never leave the link on the live mount
                } catch (Throwable t) {
                    // Modded removePassenger threw on half-initialized state — the
                    // proxy is dropped regardless; nothing renders it again.
                }
            }
        }
    }

    void clear() {
        // Break ride links before dropping the maps. Per-mount containment (E3
        // review m5): eject runs virtual methods on possibly-modded entities, and
        // clear() is called from the crash-latch CATCH and from session end — a
        // throwing override must neither escape those paths nor strand the other
        // mounts' links.
        for (var mount : vehicles.values()) {
            try {
                mount.entity.ejectPassengers();
            } catch (Throwable ignored) {
                // Throwable, not Exception (issue-#160 review MINOR-3): clear() runs
                // INSIDE the whole-pass catch, so a LinkageError from a modded
                // removePassenger here would escape render() entirely — the one
                // remaining render-thread crash window. The maps drop either way; a
                // stuck link dies with the instance.
            }
        }
        proxies.clear();
        vehicles.clear();
        activeVehicles.clear();
        submittedVehicles.clear();
        itemCache.clear(); // C-M2: the memo empties with the session's proxies
    }

    /** The AFTER_ENTITIES pass. */
    public void render(RenderLevelStageEvent event) {
        if (crashLatched) return;
        // Review fold (D1): run above a sentinel pose that is ALWAYS unwound — see markPose.
        PoseStack poseStack = event.getPoseStack();
        passMark = poseStack == null ? null : markPose(poseStack);
        try {
            renderContained(event);
        } catch (Throwable t) {
            crashLatched = true;
            clear();
            LSSLogger.error("Far-player renderer failed — proxies disabled for this session"
                    + " (a renderer bug must never take the render thread down)", t);
        } finally {
            if (passMark != null) unwindPose(poseStack, passMark);
            passMark = null;
        }
    }

    private void renderContained(RenderLevelStageEvent event) {
        var config = LSSClientConfig.CONFIG;
        // The bit gate covers arm + the soak/benchmark properties; the EFFECTIVE
        // enabled term (config AND the SeeU-coexist gate, E3) is checked HERE because
        // the bit deliberately no longer carries it (the subscription is the prefs
        // carrier — E2 review M2): a disabled viewer still delivers its shareSelf
        // opt-out, it just renders nothing.
        if (FarPlayerClientSupport.capabilityBit() == 0
                || !FarPlayerClientSupport.effectiveFarPlayersEnabled()) {
            if (!proxies.isEmpty() || !vehicles.isEmpty()) clear();
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        var localPlayer = minecraft.player;
        var poseStack = event.getPoseStack();
        // The shared buffer source vanilla renders entities into (== Fabric's
        // context.consumers()); non-null during a render pass. The pass ends its own last
        // shared batch (WI-4, endLastBatch below). Captured ONCE and reused at every
        // dispatcher.render site.
        var bufferSource = minecraft.renderBuffers().bufferSource();
        if (level == null || localPlayer == null || poseStack == null) {
            clear();
            return;
        }
        // WI-3: the frame frustum from the event (NeoForge hands it over directly).
        Frustum frustum = event.getFrustum();

        FarPlayerClientTracker tracker = FarPlayerClientSupport.tracker();
        String trackerDimension = tracker.dimension();
        if (trackerDimension == null
                || !trackerDimension.equals(level.dimension().location().toString())) {
            clear();
            return;
        }

        Vec3 cameraPosition = minecraft.gameRenderer.getMainCamera().getPosition();
        var dispatcher = minecraft.getEntityRenderDispatcher();
        // 1.21.1 line: the DeltaTracker accessor is getTimer() on this MC.
        float partialTick = minecraft.getTimer().getGameTimeDeltaPartialTick(false);
        int animationTick = localPlayer.tickCount;
        long now = FarPlayerClientSupport.monotonicMillis();
        int maxRender = config.farPlayersMaxRenderDistanceBlocks;
        int minRender = config.farPlayersMinDistanceBlocks;
        boolean fullBright = config.farPlayersFullBright;
        boolean nameTags = config.farPlayersNameTags && Minecraft.renderNames(); // F1/hide-GUI hides every tag (fold D3)
        int drawn = 0, culled = 0, mounts = 0;
        List<PendingTag> pendingTags = new ArrayList<>();

        Set<UUID> active = new HashSet<>();
        for (var tracked : tracker.snapshot().values()) {
            var sample = tracked.motion().sample(now);
            Vec3 position = new Vec3(sample.x(), sample.y(), sample.z());
            double distance = position.distanceTo(localPlayer.position());
            // Port-review fold (h): the depth-buffer quantities (armor lift, overlay gate) are CAMERA
            // distances, like the tag math — freecam/replay; the caps below stay player distances.
            double cameraDistance = cameraPosition.distanceTo(position);
            if (distance < minRender || (maxRender > 0 && distance > maxRender)) {
                continue;
            }

            // Handoff = vanilla's own draw decision (M3 — see the class javadoc):
            // the proxy renders exactly when the real entity would not. Same
            // predicate both directions, so the swap is frame-synchronized with
            // vanilla's entity pop — no band, no flicker, no diagonal double-render,
            // no high-render-distance invisibility annulus.
            // NOTE (2026-09-04, far-player-render-hardening-plan.md F1/F3): ClientLevel
            // .hasChunk is unconditionally true on this MC, so the chunkLoaded conjunct
            // below is inert — the live handoff is `real == null ||
            // !real.shouldRenderAtSqrDistance(...)`, and RemotePlayer culls at ~640 blocks,
            // so every TRACKED real player is vanilla's to draw. Kept for line parity.
            var real = level.getPlayerByUUID(tracked.uuid());
            if (real != null
                    && level.hasChunk(Mth.floor(position.x) >> 4,
                            Mth.floor(position.z) >> 4)
                    && real.shouldRenderAtSqrDistance(
                            cameraPosition.distanceToSqr(real.position()))) {
                continue;
            }

            Proxy proxy;
            try {
                proxy = proxies.compute(tracked.uuid(), (uuid, current) ->
                        current == null || current.level() != level
                                ? new Proxy(level, uuid, tracked.name(), nextEntityId(level))
                                : current);
            } catch (Throwable t) {
                // RemotePlayer construction can fire NeoForge entity hooks Fabric lacks.
                dropProxyContained(tracked.uuid(), active, "construction", t);
                restorePose(poseStack, passMark);
                continue;
            }
            boolean allowWalk = config.farPlayersMaxAnimationDistanceBlocks > 0
                    && distance <= config.farPlayersMaxAnimationDistanceBlocks;
            // Rider-while-seated attribution (issue-#160 review MINOR-1): from frame 2
            // of a ride the proxy IS a passenger, and apply's snapTo/setPose reach
            // makeBoundingBox — which Create-class mixins wrap with vehicle-state
            // reads (getSeatPos on a bare client-created contraption is the literal
            // issue-#160 NPE). Seated, that throw must latch the VEHICLE's type and
            // re-apply unmounted — not fall through to the whole-pass latch and kill
            // the feature for the session.
            if (proxy.isPassenger()) {
                try {
                    proxy.apply(tracked, sample, position, cameraDistance,
                            maxRender > 0 ? maxRender : 16384, allowWalk, animationTick,
                            itemCache);
                } catch (Throwable t) {
                    latchSeatedFailure(tracked, proxy, t);
                    // Unmounted, the vehicle-state mixin path is inert — one retry.
                    // Guarded: if even the link-break threw (latchSeatedFailure dropped
                    // the proxy), a still-seated retry would re-throw into the
                    // whole-pass latch — skip this rider this frame instead.
                    if (!proxy.isPassenger()) {
                        proxy.apply(tracked, sample, position, cameraDistance,
                                maxRender > 0 ? maxRender : 16384, allowWalk,
                                animationTick, itemCache);
                    }
                }
            } else {
                try {
                    proxy.apply(tracked, sample, position, cameraDistance,
                            maxRender > 0 ? maxRender : 16384, allowWalk, animationTick,
                            itemCache);
                } catch (Throwable t) {
                    // apply()->setPose->refreshDimensions fires EntityEvent.Size into other
                    // mods on NeoForge (no Fabric analogue): drop this proxy, not the feature.
                    dropProxyContained(tracked.uuid(), active, "apply", t);
                    restorePose(poseStack, passMark);
                    continue;
                }
            }
            active.add(tracked.uuid());

            // R-10 v1.3 mounts: the rider renders at its OWN wire position (the
            // server-side seated position already encodes the seat offset); the ride
            // link exists ONLY to make isPassenger() true at render-state extraction
            // (the one vanilla path to seated legs). Edge-triggered — never SeeU's
            // per-frame eject/re-seat.
            var wireVehicle = tracked.latest().vehicle();
            if (wireVehicle != null) {
                // Per-type render containment (E3 review m6, symmetric with rung 2):
                // a modded mount whose link/extract/submit THROWS latches ITS type and
                // this rider falls through to unmounted — never the global crash latch
                // (which would take the whole feature down for one bad mount type).
                try {
                    int mountResult = renderMount(wireVehicle, tracked, proxy, level, now, animationTick,
                            dispatcher, partialTick, cameraPosition, poseStack, bufferSource,
                            frustum, fullBright);
                    if (mountResult > 0) mounts++; else if (mountResult < 0) culled++;
                } catch (Throwable e) {
                    // Throwable, not Exception (MINOR-3): a LinkageError from a modded
                    // entity class init must latch the TYPE, not the whole feature.
                    mountLadder.latchRenderFailure(wireVehicle.typeIdentity(), e);
                    restorePose(poseStack, passMark);
                    vehicles.remove(wireVehicle.uuid());
                    stopRidingContained(proxy); // MINOR-2: a throwing removePassenger
                    // here escaped this catch to the whole-pass latch — same frame,
                    // whole feature off where the type was already latched.
                }
            } else if (proxy.isPassenger()) {
                // Dismount edge (MINOR-2): the wire says unmounted, the local link may
                // sit on a broken modded vehicle. A throwing dismount drops the proxy
                // wholesale (fresh unmounted proxy next frame) instead of feature-off.
                if (!stopRidingContained(proxy)) {
                    proxies.remove(tracked.uuid());
                    active.remove(tracked.uuid());
                    continue;
                }
            }

            // Rider extraction while seated runs extraction mixins against the vehicle
            // (MINOR-1's second half): attribute a seated throw to the vehicle type and
            // skip this rider this frame — it renders unmounted next frame.
            if (proxy.isPassenger()) {
                try {
                    int light = packedLightFor(dispatcher, proxy, partialTick, fullBright);
                    if (isInFrustum(frustum, proxy)) {
                        // 1.21.1 line: immediate dispatcher.render (no extract/submit on this MC); yaw
                        // is the sampled value (proxy yRot == yRotO by construction).
                        dispatcher.render(proxy,
                                position.x - cameraPosition.x,
                                position.y - cameraPosition.y,
                                position.z - cameraPosition.z,
                                sample.yaw(), partialTick, poseStack, new LiftedBufferSource(bufferSource, skinRenderType(dispatcher, proxy),
                                        armorLiftBlocks(cameraDistance), proxy.liftTiers), light);
                        drawn++;
                    } else {
                        culled++;
                    }
                    queueProxyTag(pendingTags, frustum, nameTags, tracked, proxy, localPlayer, position, light);
                } catch (Throwable t) {
                    latchSeatedFailure(tracked, proxy, t);
                }
            } else {
                // NeoForge-specific containment: NeoForge fires third-party render events
                // (RenderLivingEvent, RenderNameTagEvent) INSIDE dispatcher.render that Fabric
                // does not. A throwing listener here drops THIS proxy for the frame, not the
                // whole feature — symmetric with the seated path's per-rider containment above,
                // and with the construction/apply containment (dropProxyContained) on this path.
                try {
                    int light = packedLightFor(dispatcher, proxy, partialTick, fullBright);
                    if (isInFrustum(frustum, proxy)) {
                        // 1.21.1 line: immediate dispatcher.render (no extract/submit on this MC); yaw
                        // is the sampled value (proxy yRot == yRotO by construction).
                        dispatcher.render(proxy,
                                position.x - cameraPosition.x,
                                position.y - cameraPosition.y,
                                position.z - cameraPosition.z,
                                sample.yaw(), partialTick, poseStack, new LiftedBufferSource(bufferSource, skinRenderType(dispatcher, proxy),
                                        armorLiftBlocks(cameraDistance), proxy.liftTiers), light);
                        drawn++;
                    } else {
                        culled++;
                    }
                    queueProxyTag(pendingTags, frustum, nameTags, tracked, proxy, localPlayer, position, light);
                } catch (Throwable t) {
                    dropProxyContained(tracked.uuid(), active, "render", t);
                    restorePose(poseStack, passMark);
                }
            }
        }
        // Prune with the ride link BROKEN (E3 review m2): a proxy dropped while
        // riding otherwise stays in the mount's passenger list — no ghost render
        // (no 26.2 renderer reads passengers), but each anchored-mount flicker
        // cycle would strand another RemotePlayer reachable from the live mount.
        var proxyIt = proxies.entrySet().iterator();
        while (proxyIt.hasNext()) {
            var entry = proxyIt.next();
            if (!active.contains(entry.getKey())) {
                if (entry.getValue().isPassenger()) stopRidingContained(entry.getValue());
                proxyIt.remove(); // dropped either way — a stuck link dies with it
            }
        }
        // Vehicle lifecycle (R-10): evict instances no rider referenced this frame
        // (level-change/type-change recreation happens in mountFor).
        vehicles.keySet().removeIf(uuid -> !activeVehicles.contains(uuid));
        activeVehicles.clear();
        submittedVehicles.clear();
        // WI-6 gap fill (live rig 2026-09-04): between vanilla's own 64-block name-tag cap and
        // the entity-tracking radius the REAL player entity is vanilla's to draw (the handoff
        // above), yet vanilla draws no tag that far out — so the tag vanished in a band just
        // inside the loaded chunks. Tag those players here under vanilla's own visibility
        // ladder minus its distance clause; proxies never appear in level.players(), and a
        // tracked far player whose proxy drew this frame is skipped via the active set.
        // Accepted residuals (review F8): a nameplate-range-extending mod, and one frame at
        // exactly 64 blocks (LSS lerps, vanilla compares the unlerped position). The
        // camera/vehicle/hide-GUI clauses run BEFORE the team ladder here, where vanilla's
        // team switch returns first — stricter only (F5).
        if (nameTags) {
            for (var realPlayer : level.players()) {
                if (realPlayer == localPlayer || realPlayer == minecraft.getCameraEntity()) continue;
                if (active.contains(realPlayer.getUUID())) continue;
                if (realPlayer.isDiscrete() || realPlayer.isVehicle()) continue; // LSS sneak policy; vanilla's mount rule
                Vec3 realPosition = new Vec3(Mth.lerp(partialTick, realPlayer.xOld, realPlayer.getX()),
                        Mth.lerp(partialTick, realPlayer.yOld, realPlayer.getY()),
                        Mth.lerp(partialTick, realPlayer.zOld, realPlayer.getZ()));
                var realBlockPos = realPlayer.blockPosition();
                if (!level.isOutsideBuildHeight(realBlockPos.getY())
                        && !minecraft.levelRenderer.isSectionCompiled(realBlockPos)) continue; // vanilla's own body gate (F4 + port review)
                double cameraDistanceSq = cameraPosition.distanceToSqr(realPosition);
                if (cameraDistanceSq < 64.0 * 64.0) continue; // vanilla's own tag range (camera-based, as its cap is)
                double realDistance = realPosition.distanceTo(localPlayer.position());
                if (realDistance < minRender || (maxRender > 0 && realDistance > maxRender)) continue;
                if (!vanillaNameVisibleIgnoringDistance(realPlayer, localPlayer,
                        !realPlayer.isInvisibleTo(localPlayer))) continue;
                var nameplateRange = realPlayer.getAttribute(NeoForgeMod.NAMETAG_DISTANCE);
                if (nameplateRange != null) {
                    double range = nameplateRange.getValue();
                    if (range < 64.0) continue; // a server shortened tags on purpose
                    if (range > 64.0 && cameraDistanceSq < range * range) continue; // vanilla draws it out to there (fold D2)
                }
                queueNameTag(pendingTags, frustum, realPlayer, realPlayer.getDisplayName(), realPosition,
                        packedLightFor(dispatcher, realPlayer, partialTick, fullBright));
            }
        }
        // WI-6: the tags, batched AFTER every model so the entity->text->entity render-type
        // switches happen once per frame, not once per proxy (on Iris-free stacks each switch
        // flushes the shared buffer).
        int tagsDrawn = 0;
        for (var tag : pendingTags) {
            renderFarNameTag(dispatcher, tag, cameraPosition, poseStack, bufferSource,
                    Font.DisplayMode.NORMAL, true);
            tagsDrawn++;
        }
        for (var tag : pendingTags) {
            renderFarNameTag(dispatcher, tag, cameraPosition, poseStack, bufferSource,
                    Font.DisplayMode.POLYGON_OFFSET, false);
        }
        // WI-4: end the shared batch OUR proxies opened, the way vanilla ended its own entity
        // batch one instruction before this event fired (endLastBatch — NEVER the arg-less
        // endBatch, which drains the deferred glint/translucent buffers early). Inert under
        // Iris's batched buffer source (it never sets lastSharedType); on an Iris-free stack it
        // stops our last skin batch from riding whichever block entity next requests a shared
        // render type, i.e. from being drawn under that later GL state.
        if (bufferSource instanceof MultiBufferSource.BufferSource bs) bs.endLastBatch();
        this.lastDrawn = drawn;
        this.lastCulled = culled;
        this.lastMounts = mounts;
        this.lastTags = tagsDrawn;
    }

    /** Contained dismount: modded removePassenger overrides can throw on the same
     *  half-initialized state that motivated the type latch (issue-#160 review
     *  MINOR-2). Returns false when the link could not be cleanly broken — the caller
     *  drops the proxy so a broken link never persists. */
    private static boolean stopRidingContained(Proxy proxy) {
        try {
            proxy.stopRiding();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Attributes a rider-while-seated throw (apply/extract reaching vehicle-state
     *  mixins — the literal issue-#160 stack) to the VEHICLE's type: latch it, drop
     *  the mount instance, break the link (contained), so the rider continues
     *  unmounted and the feature keeps working for every other type (MINOR-1). */
    private void latchSeatedFailure(FarPlayerClientTracker.TrackedFarPlayer tracked,
                                    Proxy proxy, Throwable t) {
        var wireVehicle = tracked.latest().vehicle();
        // The fallback key must use the same minecraft:x identity format as every
        // other latch site, or the entry can never suppress a future creation of
        // the type (round-3 review NIT — EntityType#toString is a different shape).
        String type = wireVehicle != null ? wireVehicle.typeIdentity()
                : proxy.getVehicle() == null ? "null"
                        : BuiltInRegistries.ENTITY_TYPE.getKey(
                                proxy.getVehicle().getType()).toString();
        mountLadder.latchRenderFailure(type, t);
        if (wireVehicle != null) vehicles.remove(wireVehicle.uuid());
        if (!stopRidingContained(proxy)) {
            proxies.remove(tracked.uuid());
        }
    }

    /** The per-rider mount pass: resolve/create/link/draw. Returns 1 = drawn, -1 = the
     *  mount was frustum-culled (its link/bookkeeping still ran), 0 = ladder-degraded or
     *  not this rider's turn. Throws propagate to the per-type containment at the call
     *  site. */
    private int renderMount(dev.vox.lss.common.farplayers.FarPlayerWire.Vehicle wireVehicle,
                             FarPlayerClientTracker.TrackedFarPlayer tracked, Proxy proxy,
                             ClientLevel level, long now, int animationTick,
                             net.minecraft.client.renderer.entity.EntityRenderDispatcher dispatcher,
                             float partialTick, Vec3 cameraPosition,
                             com.mojang.blaze3d.vertex.PoseStack poseStack,
                             MultiBufferSource bufferSource,
                             Frustum frustum, boolean fullBright) {
        var mount = mountFor(wireVehicle, tracked, level, now);
        if (mount == null) {
            if (proxy.isPassenger()) {
                proxy.stopRiding(); // ladder-degraded: render unmounted
            }
            return 0;
        }
        activeVehicles.add(wireVehicle.uuid());
        if (proxy.getVehicle() != mount.entity) {
            proxy.stopRiding();
            // 1.21.1 line: the 2-arg (entity, force) overload — force=TRUE because
            // render-only instances can fail vanilla's canRide/distance checks (the
            // 26.x third GameEvent arg does not exist here). A false return is rung 3
            // of the ladder — mounted-position standing pose, never a floating sit.
            proxy.startRiding(mount.entity, true);
        }
        if (!submittedVehicles.add(wireVehicle.uuid())) return 0;
        var vSample = mount.motion.sample(now);
        applyMountState(mount, vSample, animationTick);
        // WI-3: frustum-test the MOUNT itself right before its draw (after applyMountState);
        // the ride link / activeVehicles / submittedVehicles bookkeeping above ran regardless,
        // so a culled mount is never re-created or re-seated next frame.
        if (!isInFrustum(frustum, mount.entity)) return -1;
        // 1.21.1 line: immediate dispatcher.render (no extract/submit on this MC).
        dispatcher.render(mount.entity,
                vSample.x() - cameraPosition.x,
                vSample.y() - cameraPosition.y,
                vSample.z() - cameraPosition.z,
                vSample.yaw(), partialTick, poseStack,
                bufferSource,
                packedLightFor(dispatcher, mount.entity, partialTick, fullBright));
        return 1;
    }

    /** Resolves/creates/updates the render-only mount instance for one rider's wire
     *  vehicle block. Null = ladder-degraded (render the rider unmounted). */
    private MountInstance mountFor(dev.vox.lss.common.farplayers.FarPlayerWire.Vehicle wire,
                                   FarPlayerClientTracker.TrackedFarPlayer rider,
                                   ClientLevel level, long now) {
        MountInstance mount = vehicles.get(wire.uuid());
        if (mount != null
                && (mount.entity.level() != level || !mount.typeIdentity.equals(wire.typeIdentity()))) {
            mount = null; // level change pins the old ClientLevel; same-UUID new type recreates
        }
        if (mount == null) {
            var entity = mountLadder.createMount(wire.typeIdentity(), level);
            if (entity == null) return null;
            entity.setId(nextEntityId(level));
            entity.noPhysics = true;
            entity.setNoGravity(true);
            entity.setInvisible(false);
            var seedEntry = rider.latest();
            mount = new MountInstance(entity, wire.typeIdentity(),
                    new dev.vox.lss.common.farplayers.FarPlayerMotion(
                            FarPlayerWire.dequantizePos(wire.quantX()),
                            FarPlayerWire.dequantizePos(wire.quantY()),
                            FarPlayerWire.dequantizePos(wire.quantZ()),
                            FarPlayerWire.byteToAngle(wire.yaw()),
                            FarPlayerWire.byteToAngle(wire.pitch()),
                            // The RIDER's velocity from creation (E3 review m3): a
                            // zero-velocity seed held the mount still for one full
                            // window while the rider dead-reckoned ahead — exactly
                            // the shear the rider-velocity design exists to prevent.
                            FarPlayerWire.shortToVelocity(seedEntry.velX()),
                            FarPlayerWire.shortToVelocity(seedEntry.velY()),
                            FarPlayerWire.shortToVelocity(seedEntry.velZ()),
                            rider.cadenceTicks(), now));
            mount.appliedStamp = rider.receivedAtMillis();
            vehicles.put(wire.uuid(), mount);
        } else if (rider.receivedAtMillis() > mount.appliedStamp) {
            // STRICTLY newer (E3 review m4): with two riders whose stamps diverge
            // (delta suppression), != alternated newer/older every frame, rewinding
            // the lerp origin — > makes the mount follow the freshest frame only.
            // A new updates frame for this rider: feed the mount's wire position with
            // the RIDER's velocity hint (R-10 v1.3 — they share a velocity by
            // definition; separate hints shear visibly at horse/boat speeds).
            var e = rider.latest();
            mount.motion.applyRaw(
                    FarPlayerWire.dequantizePos(wire.quantX()),
                    FarPlayerWire.dequantizePos(wire.quantY()),
                    FarPlayerWire.dequantizePos(wire.quantZ()),
                    FarPlayerWire.byteToAngle(wire.yaw()),
                    FarPlayerWire.byteToAngle(wire.yaw()),
                    FarPlayerWire.byteToAngle(wire.pitch()),
                    FarPlayerWire.shortToVelocity(e.velX()),
                    FarPlayerWire.shortToVelocity(e.velY()),
                    FarPlayerWire.shortToVelocity(e.velZ()),
                    rider.cadenceTicks(), rider.receivedAtMillis());
            mount.appliedStamp = rider.receivedAtMillis();
        }
        return mount;
    }

    private static void applyMountState(MountInstance mount,
                                        dev.vox.lss.common.farplayers.FarPlayerMotion.Sample s,
                                        int animationTick) {
        var entity = mount.entity;
        Vec3 position = new Vec3(s.x(), s.y(), s.z());
        boolean advanceTick = entity.tickCount != animationTick;
        entity.tickCount = animationTick;
        // 1.21.1 line: no 3-arg setOldPosAndRot — the explicit old-field writes below
        // (plus the rotation olds) cover it; snapTo is this line's moveTo.
        entity.xo = position.x;
        entity.yo = position.y;
        entity.zo = position.z;
        entity.xOld = position.x;
        entity.yOld = position.y;
        entity.zOld = position.z;
        entity.moveTo(position, s.yaw(), s.pitch());
        entity.setYRot(s.yaw());
        entity.yRotO = s.yaw();
        entity.setXRot(s.pitch());
        entity.xRotO = s.pitch();
        if (entity instanceof net.minecraft.world.entity.LivingEntity living) {
            // E3 review MAJOR (a deliberate SeeU deviation — theirs omits this): body
            // rot is only ever written by tick logic render-only entities never run,
            // and 26.2's solveBodyRot reads yBodyRot for BOTH the living mount AND
            // (via the passenger branch) its rider — unset, a horse renders locked
            // facing south with the rider's torso wrenched along. Vehicles have no
            // separate head, mirroring the vehicle-seed doc.
            living.setYBodyRot(s.yaw());
            living.yBodyRotO = s.yaw();
            living.setYHeadRot(s.yaw());
            living.yHeadRotO = s.yaw();
            // n9: drive leg animation from positional deltas (the Proxy's own
            // pattern) — otherwise a galloping horse's legs are frozen.
            if (mount.lastWalkPosition != null && advanceTick) {
                float movement = (float) Mth.length(position.x - mount.lastWalkPosition.x,
                        0, position.z - mount.lastWalkPosition.z);
                // 1.21.1 line: 2-arg WalkAnimationState.update (no trailing position scale).
                living.walkAnimation.update(Math.min(movement * 4.0f, 1.0f),
                        WALK_ANIMATION_SCALE);
            }
            if (advanceTick || mount.lastWalkPosition == null) {
                mount.lastWalkPosition = position;
            }
        }
    }

    /**
     * The proxy's packed light (far-player-render-hardening-plan.md WI-1/WI-2, user decision
     * 2026-09-04: BRIGHTER than possibly correct beats dark). Sky is FLOORED to 15; the real
     * block light is kept (a torch-lit far player at night still reads lit); {@code fullBright}
     * (client {@code farPlayersFullBright}) short-circuits to vanilla's FULL_BRIGHT.
     *
     * <p>Why a floor: a proxy stands in for LOD terrain, which Voxy lights as sky-lit, but
     * vanilla's lookup hands back whatever light data the client HOLDS at the proxy's eye. On
     * this MC {@code ClientLevel.hasChunk} is unconditionally true, so the fresh cut's
     * loaded/unloaded split never ran and every proxy read that data. Where the client holds
     * a chunk with no real entity in it (C2ME's no-tick ring beyond the server's
     * entity-tracking radius) the stored sky light is often ZERO — an unbaked send decodes as
     * an all-zero layer — and the proxy went BLACK in daylight while the terrain there is
     * never drawn (outside Sodium's circle, Voxy paints LOD over it); past the drop radius
     * the engine has no data and answers 15, so the same player lit up again farther away.
     * The floor makes the proxy immune to that data whatever its cause. We still call
     * {@code getPackedLightCoords}, so third-party return hooks on it (Sable's sub-level
     * lighting) apply before the floor.
     */
    private static int packedLightFor(EntityRenderDispatcher dispatcher, Entity entity,
                                      float partialTick, boolean fullBright) {
        if (fullBright) return LightTexture.FULL_BRIGHT;
        int vanilla = dispatcher.getPackedLightCoords(entity, partialTick);
        return LightTexture.pack(LightTexture.block(vanilla), 15);
    }

    /** WI-3: vanilla's frustum predicate MINUS its distance term ({@code EntityRenderer
     *  .shouldRender}'s box + NaN fallback). Deliberately not {@code dispatcher.shouldRender}:
     *  that carries vanilla's distance cull (a far horse would vanish) and Sable injects into
     *  {@code EntityRenderer.shouldRender}. Null frustum = draw. Culls the DRAW only — every
     *  caller keeps its tracking/apply/mount bookkeeping running regardless. */
    private static boolean isInFrustum(Frustum frustum, Entity entity) {
        if (frustum == null || entity.noCulling) return true;
        AABB box = entity.getBoundingBoxForCulling().inflate(0.5);
        if (box.hasNaN() || box.getSize() == 0.0) {
            box = new AABB(entity.getX() - 2.0, entity.getY() - 2.0, entity.getZ() - 2.0,
                    entity.getX() + 2.0, entity.getY() + 2.0, entity.getZ() + 2.0);
        }
        return frustum.isVisible(box);
    }


    /** Fold (e): the lift for {@link LiftedBufferSource} — about five 24-bit depth steps at
     *  distance {@code d}, floored where steps are tiny and capped where the far plane is near. */
    private static float armorLiftBlocks(double distance) {
        return (float) Math.clamp(distance * distance * 6e-6, 0.02, 4.0);
    }

    /** Fold (e): the proxy's OWN skin render type — the one buffer the lift must not touch.
     *  PlayerModel is built on RenderType::entityTranslucent; ask the live model so a replaced
     *  renderer still answers, and fall back to vanilla's function. */
    private static RenderType skinRenderType(EntityRenderDispatcher dispatcher, Proxy proxy) {
        var skin = proxy.getSkin().texture();
        if (dispatcher.getRenderer(proxy) instanceof LivingEntityRenderer<?, ?> living) {
            return living.getModel().renderType(skin);
        }
        return RenderType.entityTranslucent(skin);
    }

    /** Review fold (D1): a throw inside {@code dispatcher.render} or the tag draw leaves pushes
     *  on VANILLA's pose stack, and {@code LevelRenderer.checkPoseStack} throws "Pose stack not
     *  empty" AFTER this pass returns — outside every containment here, a hard client crash.
     *  The pass therefore runs above a sentinel pose it always unwinds to, and the per-proxy /
     *  per-mount containments restore to the same mark before continuing the frame. */
    private static PoseStack.Pose markPose(PoseStack poseStack) {
        poseStack.pushPose();
        return poseStack.last();
    }

    /** Pops back to {@code mark} (which is left in place); bounded by the stack root. */
    private static void restorePose(PoseStack poseStack, PoseStack.Pose mark) {
        if (mark == null) return;
        while (!poseStack.clear() && poseStack.last() != mark) poseStack.popPose();
    }

    /** {@link #restorePose} and then pops the mark itself. */
    private static void unwindPose(PoseStack poseStack, PoseStack.Pose mark) {
        restorePose(poseStack, mark);
        if (poseStack.last() == mark) poseStack.popPose();
    }

    /** A tag queued during the pass and drawn after it (WI-6): over a Proxy, or over a
     *  vanilla-tracked player past vanilla's own 64-block tag cap (the gap fill in the pass).
     *  The anchor is resolved ONCE here (review fold F9/D7). */
    /**
     * Fold (e) (live rig 2026-09-04, SoakPlayer in armor): vanilla's armor is a 1/16-block
     * shell around the body (leggings 1/32); a 24-bit depth buffer behind vanilla's 0.05 near
     * plane resolves ≈ d²·1.2e-6 blocks per step, so past ~160-230 blocks armor and body land
     * inside ONE step and z-fight — vanilla never draws players that far, proxies always are.
     * This buffer source pulls every NON-skin render type (armor, trims, glint, elytra, held
     * items) toward the camera by a distance-scaled lift ALONG EACH VERTEX'S OWN VIEW RAY: the
     * screen position is unchanged (the projection is radial about the camera, which is the
     * origin of the pass's camera-relative pose), the depth lands decisively nearer than the
     * body, and terrain occlusion is off by at most the lift. The skin's own render type passes
     * through untouched — body and overlay cubes share it — so the 1/64-block overlay layers
     * are gated by {@link #OVERLAY_MAX_DISTANCE_BLOCKS} instead. Mounts are not wrapped: a
     * uniform lift cannot separate a mount's own layers, and their shells are thicker.
     */
    private static final class LiftedBufferSource implements MultiBufferSource {
        private final MultiBufferSource delegate;
        private final RenderType skinType;
        private final float lift;
        private final Map<RenderType, Integer> tiers;

        LiftedBufferSource(MultiBufferSource delegate, RenderType skinType, float lift,
                           Map<RenderType, Integer> tiers) {
            this.delegate = delegate;
            this.skinType = skinType;
            this.lift = lift;
            this.tiers = tiers;
        }

        @Override
        public VertexConsumer getBuffer(RenderType type) {
            VertexConsumer buffer = delegate.getBuffer(type);
            if (type == skinType) return buffer;
            // Fold (e2): TIERED lift — the pieces that overlap each other (leggings under a
            // chestplate, boots over leggings, a shield against the chest) are ~1/32 block apart
            // and would still fight under one uniform lift; tier 0 = inner armor model, 1 = outer
            // armor + trims + glint, 2 = held items / elytra / anything else, ~4 depth steps apart.
            int tier = tiers.getOrDefault(type, 2);
            // A fresh wrapper per request: a foil item holds TWO consumers at once
            // (VertexMultiConsumer), so a reused wrapper would cross their writes.
            return new LiftingConsumer(buffer, lift * (1.0f + 0.8f * tier));
        }
    }

    /** Radial pull toward the origin (the camera) by {@code lift} blocks; every other call
     *  forwards. The abstract six are the whole surface — the interface defaults (the
     *  11-arg addVertex ModelPart uses, putBulkData for items, the pose overloads) all
     *  route through them, so Sodium's/Iris's fast paths simply see a plain consumer. */
    private static final class LiftingConsumer implements VertexConsumer {
        private final VertexConsumer delegate;
        private final float lift;

        LiftingConsumer(VertexConsumer delegate, float lift) {
            this.delegate = delegate;
            this.lift = lift;
        }

        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            float length = (float) Math.sqrt(x * x + y * y + z * z);
            if (length > lift + 1.0f) {
                float f = (length - lift) / length;
                delegate.addVertex(x * f, y * f, z * f);
            } else {
                delegate.addVertex(x, y, z);
            }
            return this;
        }

        @Override
        public VertexConsumer setColor(int red, int green, int blue, int alpha) {
            delegate.setColor(red, green, blue, alpha);
            return this;
        }

        @Override
        public VertexConsumer setUv(float u, float v) {
            delegate.setUv(u, v);
            return this;
        }

        @Override
        public VertexConsumer setUv1(int u, int v) {
            delegate.setUv1(u, v);
            return this;
        }

        @Override
        public VertexConsumer setUv2(int u, int v) {
            delegate.setUv2(u, v);
            return this;
        }

        @Override
        public VertexConsumer setNormal(float normalX, float normalY, float normalZ) {
            delegate.setNormal(normalX, normalY, normalZ);
            return this;
        }
    }

    private record PendingTag(Entity entity, Component name, Vec3 anchor, int light) {}

    /** The name-tag anchor in world space: vanilla's NAME_TAG attachment + 0.5. */
    private static Vec3 tagAnchor(Entity entity, Vec3 position) {
        Vec3 attachment = entity.getAttachments()
                .getNullable(EntityAttachment.NAME_TAG, 0, entity.getYRot());
        return attachment == null ? null : position.add(attachment.x, attachment.y + 0.5, attachment.z);
    }

    /** WI-6 proxy gate: the option (already folded with the hide-GUI key), the sneak rule (a
     *  NEW LSS policy — "sneak = don't advertise me"; vanilla's own sneak cap lives in code
     *  that never runs for a proxy) and — review fold D3 — vanilla's invisibility + team
     *  name-tag rules, so a team set to "never" is honoured beyond the tracking radius exactly
     *  as inside it (the proxy's team resolves through the scoreboard by name). */
    private static void queueProxyTag(List<PendingTag> out, Frustum frustum, boolean nameTags,
                                      FarPlayerClientTracker.TrackedFarPlayer tracked, Proxy proxy,
                                      LocalPlayer localPlayer, Vec3 position, int light) {
        if (!nameTags) return;
        if ((tracked.latest().poseFlags() & FarPlayerWire.POSE_SNEAK) != 0) return;
        if (!vanillaNameVisibleIgnoringDistance(proxy, localPlayer, true)) return; // a proxy is never invisible; see Proxy.isInvisibleTo
        queueNameTag(out, frustum, proxy, proxy.farName, position, light);
    }

    /** Resolves the anchor once, runs the tag's OWN frustum test (the body's box can be just
     *  off screen while the tag is on it), then queues. */
    private static void queueNameTag(List<PendingTag> out, Frustum frustum, Entity entity,
                                     Component name, Vec3 position, int light) {
        Vec3 a = tagAnchor(entity, position);
        if (a == null) return;
        if (frustum != null && !frustum.isVisible(new AABB(a.x - 0.5, a.y - 0.5, a.z - 0.5,
                a.x + 0.5, a.y + 0.5, a.z + 0.5))) {
            return;
        }
        out.add(new PendingTag(entity, name, a, light));
    }

    /**
     * Vanilla's {@code LivingEntityRenderer.shouldShowName} ladder MINUS its distance clause
     * (that clause is exactly what the gap fill supplies): the caller's invisibility verdict
     * ({@code !isInvisibleTo(localPlayer)} for a real player, {@code true} for a proxy — whose
     * {@code isInvisibleTo} is overridden to keep VANILLA's tag off it) and the team name-tag
     * visibility rules, verbatim.
     */
    private static boolean vanillaNameVisibleIgnoringDistance(AbstractClientPlayer player,
                                                              LocalPlayer localPlayer, boolean visible) {
        Team team = player.getTeam();
        Team own = localPlayer.getTeam();
        if (team == null) return visible;
        return switch (team.getNameTagVisibility()) {
            case ALWAYS -> visible;
            case NEVER -> false;
            case HIDE_FOR_OTHER_TEAMS -> own == null ? visible
                    : team.isAlliedTo(own) && (team.canSeeFriendlyInvisibles() || visible);
            case HIDE_FOR_OWN_TEAM -> own == null ? visible : !team.isAlliedTo(own) && visible;
        };
    }

    /**
     * WI-6: LSS draws the far-player name tag itself — vanilla gates entity name tags at 64
     * blocks on every MC line and a proxy only exists beyond the tracking radius, so
     * {@code setCustomNameVisible} could never produce one. Vanilla's {@code renderNameTag}
     * math (attachment + 0.5, camera billboard, {@code (s, -s, s)}), with three deliberate
     * deviations: NORMAL (depth-tested) display mode only — no see-through pass, the privacy
     * stance that also rejects glow; vanilla's 25% background behind the depth-tested text
     * (legibility at distance); and {@code s = 0.025 × clamp(sqrt(d/64), 1, 8)} so the tag
     * stays readable without turning a two-pixel body into a full-size plate (constant
     * apparent size looked like an ESP HUD — panel Q4). {@code d} is the CAMERA distance
     * (review fold F1: freecam/replay). The text batch is a shared-buffer type, drained by
     * the pass's {@code endLastBatch()}.
     *
     * <p>TWO draws, not one (live rig 2026-09-04 — the text flickered white/grey): the font
     * draws its background plate as a glyph effect 0.01 text-units NEARER than the glyphs,
     * which at far-tag range is a fraction of one depth-buffer step (24-bit depth, 0.05 near
     * plane: one step ≈ d² × 1.2e-6 blocks — 0.03 blocks at 160), so plate and glyphs z-fight
     * and the translucent plate tints the text wherever it wins. Vanilla escapes that only
     * because its plate lives in the depth-test-free pass this tag deliberately lacks. So:
     * draw 1 = plate + glyphs as vanilla lays them out; draw 2 = the glyphs alone in
     * {@code POLYGON_OFFSET} display mode — vanilla's own outline idiom
     * ({@code glPolygonOffset(-1, -10)}: at least ten depth units nearer, resolution-adaptive
     * on every depth format, zero perspective shift, no see-through window — the review's
     * replacement for a geometric lift). The pass runs all first draws, then all second
     * draws, so the shared batch switches render type once per frame, not once per tag.
     * The push is unwound in a finally (fold D1).
     */
    private static void renderFarNameTag(EntityRenderDispatcher dispatcher, PendingTag tag,
                                         Vec3 cameraPosition, PoseStack poseStack,
                                         MultiBufferSource bufferSource, Font.DisplayMode mode,
                                         boolean withPlate) {
        Vec3 anchor = tag.anchor();
        double cameraDistance = anchor.distanceTo(cameraPosition);
        float scale = 0.025f * (float) Math.clamp(Math.sqrt(cameraDistance / 64.0), 1.0, 8.0);
        Minecraft minecraft = Minecraft.getInstance();
        Font font = minecraft.font;
        Component name = tag.name();
        poseStack.pushPose();
        try {
            poseStack.translate(anchor.x - cameraPosition.x, anchor.y - cameraPosition.y,
                    anchor.z - cameraPosition.z);
            poseStack.mulPose(dispatcher.cameraOrientation());
            poseStack.scale(scale, -scale, scale);
            Matrix4f matrix = poseStack.last().pose();
            float x = -font.width(name) / 2.0f;
            int background = (int) (minecraft.options.getBackgroundOpacity(0.25f) * 255.0f) << 24;
            font.drawInBatch(name, x, 0.0f, -1, false, matrix, bufferSource, mode,
                    withPlate ? background : 0, tag.light());
        } finally {
            poseStack.popPose();
        }
    }

    /** The /lss diag line (WI-10): last pass's draw/cull/mount/tag counts + the light mode —
     *  the live-gate instrument the original black-proxy sighting lacked. */
    public static String diagLine() {
        var r = instance;
        if (r == null) return "FarPlayerRender: off";
        return "FarPlayerRender: drawn=" + r.lastDrawn + ", culled=" + r.lastCulled
                + ", mounts=" + r.lastMounts + ", tags=" + r.lastTags
                + ", light=" + (LSSClientConfig.CONFIG.farPlayersFullBright ? "full" : "floor");
    }

    /** Monotonic id from the LSS block, probed against the live level (a taken id —
     *  another mod's synthetic entity — is skipped, never reused). */
    private int nextEntityId(ClientLevel level) {
        int id = nextProxyId;
        while (level.getEntity(id) != null) id++;
        nextProxyId = id + 1;
        if (nextProxyId >= Integer.MAX_VALUE - 4096) nextProxyId = PROXY_ID_BASE;
        return id;
    }

    private static final class Proxy extends RemotePlayer {
        private final UUID trackedUuid;
        /** The cached tag text (WI-6) — never re-allocated per frame. */
        final Component farName;
        /** Fold (e2): render type -> lift tier for this proxy's equipment (refreshLiftTiers). */
        final Map<RenderType, Integer> liftTiers = new IdentityHashMap<>();
        private int maxRenderDistanceBlocks = 16384;
        private Vec3 lastWalkPosition;
        private int lastWalkTick = Integer.MIN_VALUE;

        private Proxy(ClientLevel level, UUID trackedUuid, String name, int entityId) {
            super(level, new GameProfile(trackedUuid, name));
            this.trackedUuid = trackedUuid;
            this.farName = Component.literal(name);
            // WI-6: LSS draws the tag itself; vanilla's path can never fire for a proxy.
            this.setCustomNameVisible(false);
            this.setId(entityId);
            this.noPhysics = true;
            this.setNoGravity(true);
            this.setInvisible(false);
            // Deliberately NO setGlowingTag — see the class javadoc.
        }

        void apply(FarPlayerClientTracker.TrackedFarPlayer tracked,
                   dev.vox.lss.common.farplayers.FarPlayerMotion.Sample sample,
                   Vec3 position, double distance, int maxRenderDistanceBlocks,
                   boolean allowWalk, int animationTick, Map<String, Item> itemCache) {
            byte pose = tracked.latest().poseFlags();
            boolean gliding = (pose & FarPlayerWire.POSE_GLIDE) != 0;
            boolean swimming = (pose & FarPlayerWire.POSE_SWIM) != 0;
            boolean sneaking = (pose & FarPlayerWire.POSE_SNEAK) != 0;

            this.maxRenderDistanceBlocks = maxRenderDistanceBlocks;
            boolean newTick = this.tickCount != animationTick;
            this.tickCount = animationTick;
            // 1.21.1 line: no 3-arg setOldPosAndRot — explicit old-field writes below
            // (plus the rotation olds) cover it; snapTo is this line's moveTo.
            this.xo = position.x;
            this.yo = position.y;
            this.zo = position.z;
            this.xOld = position.x;
            this.yOld = position.y;
            this.zOld = position.z;
            this.moveTo(position, sample.yaw(), sample.pitch());
            this.setYRot(sample.yaw());
            this.yRotO = sample.yaw();
            this.setXRot(sample.pitch());
            this.xRotO = sample.pitch();
            this.setYBodyRot(sample.yaw());
            this.yBodyRotO = sample.yaw();
            this.setYHeadRot(sample.headYaw());
            this.yHeadRotO = sample.headYaw();
            this.setShiftKeyDown(sneaking);
            this.setSwimming(swimming);
            this.wasTouchingWater = swimming; // F7: the swim pitch term + isVisuallyCrawling read it
            this.setPose(gliding ? Pose.FALL_FLYING
                    : swimming ? Pose.SWIMMING
                    : sneaking ? Pose.CROUCHING
                    : Pose.STANDING);
            // Tick-only render inputs that vanilla advances in LivingEntity.tick(), which never
            // runs for a render-only proxy (the walk cycle's family — hardening plan WI-6 fold
            // (d), live rig 2026-09-04): the FALL_FLYING shared flag (PlayerRenderer, HumanoidModel
            // and ElytraModel read isFallFlying(), NOT the pose — without it a glider stood
            // upright with folded wings), the fall-fly tick count behind the -90° glide tilt
            // (vanilla's fade: full at 10 ticks), and the swim amount behind the swimming roll +
            // stroke (vanilla's ±0.09/tick ramp, via AccessorLivingEntity — the fields are private).
            this.setSharedFlag(SHARED_FLAG_FALL_FLYING, gliding);
            if (newTick) {
                this.fallFlyTicks = gliding ? Math.min(this.fallFlyTicks + 1, 10) : 0;
                var swim = (AccessorLivingEntity) (Object) this; // Proxy is final: cast via Object
                float swimAmount = swim.lss$getSwimAmount();
                swim.lss$setSwimAmountO(swimAmount);
                swim.lss$setSwimAmount(swimming ? Math.min(1.0f, swimAmount + 0.09f)
                        : Math.max(0.0f, swimAmount - 0.09f));
            }
            applyEquipment(tracked, itemCache);
            refreshLiftTiers();
            // WI-5: the model-parts byte (defaults to 0 = every overlay layer hidden — hat,
            // jacket, sleeves, pants were invisible on every proxy). All layers EXCEPT the
            // cape: CapeLayer positions the cape from cloak fields only Player.tick's cloak
            // physics update — never run on a render-only proxy — so an enabled cape renders
            // relative to the origin. ElytraLayer selects the cape-textured elytra through the
            // same CAPE bit and CapeLayer bails on an elytra chest, so the full byte is safe
            // exactly then.
            boolean elytra = this.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA);
            byte parts = distance <= OVERLAY_MAX_DISTANCE_BLOCKS
                    ? (byte) (elytra ? 0x7F : 0x7E)
                    : (byte) (elytra ? 0x01 : 0x00); // fold (e): base skin only past the overlay range
            this.getEntityData().set(DATA_PLAYER_MODE_CUSTOMISATION, parts);
            updateWalkAnimation(position, allowWalk,
                    gliding || swimming || tracked.latest().vehicle() != null,
                    animationTick);
        }

        /** Fold (e2): the lift TIER per render type for THIS proxy's equipment (see
         *  LiftedBufferSource): the inner armor model (LEGS — HumanoidArmorLayer.usesInnerModel;
         *  a 1/32-block shell) is tier 0, outer armor + trims + glint tier 1, everything else
         *  (held items, the elytra) tier 2. Exact render-type identities, computed the way the
         *  armor layer computes them (memoized per texture), never string-sniffed. Rebuilt per
         *  apply from the equipment the wire just set; identity-keyed. */
        private void refreshLiftTiers() {
            liftTiers.clear();
            liftTiers.put(Sheets.armorTrimsSheet(true), 1);
            liftTiers.put(Sheets.armorTrimsSheet(false), 1);
            liftTiers.put(RenderType.armorEntityGlint(), 1);
            for (EquipmentSlot slot : ARMOR_EQUIPMENT_SLOTS) {
                if (this.getItemBySlot(slot).getItem() instanceof ArmorItem armor) {
                    boolean inner = slot == EquipmentSlot.LEGS;
                    for (ArmorMaterial.Layer layer : armor.getMaterial().value().layers()) {
                        liftTiers.put(RenderType.armorCutoutNoCull(layer.texture(inner)), inner ? 0 : 1);
                    }
                }
            }
        }

        private void applyEquipment(FarPlayerClientTracker.TrackedFarPlayer tracked,
                                    Map<String, Item> itemCache) {
            // Wire slot order (FarPlayerWire/EQUIPMENT docs): HEAD CHEST LEGS FEET MAIN OFF.
            EquipmentSlot[] slots = {EquipmentSlot.HEAD, EquipmentSlot.CHEST,
                    EquipmentSlot.LEGS, EquipmentSlot.FEET,
                    EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND};
            String[] ids = tracked.equipmentIdentities();
            int[] counts = tracked.equipmentCounts();
            for (int i = 0; i < slots.length; i++) {
                this.setItemSlot(slots[i], stackFor(
                        ids == null ? null : ids[i],
                        counts == null ? 1 : Math.max(1, counts[i]), itemCache));
            }
        }

        private static ItemStack stackFor(String identity, int count,
                                          Map<String, Item> itemCache) {
            if (identity == null) return ItemStack.EMPTY;
            Item item = itemCache.size() >= ITEM_CACHE_CAP && !itemCache.containsKey(identity)
                    ? resolveItem(identity) // cap reached: resolve uncached (C-M2 armor)
                    : itemCache.computeIfAbsent(identity, Proxy::resolveItem);
            return item == null || item == Items.AIR ? ItemStack.EMPTY
                    : new ItemStack(item, count);
        }

        private static Item resolveItem(String id) {
            try {
                // Cross-version sessions (Via) can carry identities this client's
                // registry lacks — an unknown identity renders as an EMPTY slot,
                // the far-player analogue of the column fallback ladder.
                // 1.21.1 line: get() is this MC's defaulted lookup (getValue is 1.21.2+).
                return BuiltInRegistries.ITEM.get(ResourceLocation.parse(id));
            } catch (Exception e) {
                return Items.AIR;
            }
        }

        /** 1.21.1 line: no {@code WalkAnimationState.stop()} on this MC. {@code setSpeed(0)}
         *  alone leaves the PREVIOUS speed in {@code speedOld}, which the renderer lerps toward
         *  zero across every tick's partial ticks — a 20 Hz amplitude sawtooth on the limb
         *  swing that reads as a super-speed walk cycle (live rig 2026-09-04, seen past
         *  {@code farPlayersMaxAnimationDistanceBlocks}). One {@code update(0, 1)} after it
         *  copies the zero into {@code speedOld} and leaves the phase untouched, which is what
         *  the newer lines' {@code stop()} does. */
        private void stopWalkAnimation() {
            this.walkAnimation.setSpeed(0.0f);
            this.walkAnimation.update(0.0f, 1.0f);
        }

        private void updateWalkAnimation(Vec3 position, boolean allowWalk,
                                         boolean nonWalkingPose, int animationTick) {
            if (lastWalkPosition == null || animationTick == lastWalkTick) {
                if (lastWalkPosition == null) {
                    lastWalkPosition = position;
                    lastWalkTick = animationTick;
                    stopWalkAnimation();
                }
                return;
            }
            lastWalkTick = animationTick;
            if (!allowWalk || nonWalkingPose) {
                stopWalkAnimation();
                lastWalkPosition = position;
                return;
            }
            float movement = (float) Mth.length(position.x - lastWalkPosition.x, 0,
                    position.z - lastWalkPosition.z);
            // 1.21.1 line: 2-arg WalkAnimationState.update (no trailing position scale).
            this.walkAnimation.update(Math.min(movement * 4.0f, 1.0f),
                    WALK_ANIMATION_SCALE);
            lastWalkPosition = position;
        }

        @Override
        protected PlayerInfo getPlayerInfo() {
            // TAB-listed players carry skins; the proxy borrows them (SeeU's approach).
            var connection = Minecraft.getInstance().getConnection();
            if (connection != null) {
                PlayerInfo info = connection.getPlayerInfo(trackedUuid);
                if (info != null) return info;
            }
            return super.getPlayerInfo();
        }

        /** Port-review fold (h): with an entity-tracking radius under 64 blocks (view-distance ≤ 3)

         *  a proxy exists INSIDE vanilla's 64-block tag range, and {@code dispatcher.render} would

         *  then draw vanilla's own name tag over ours — {@code LivingEntityRenderer.shouldShowName}

         *  has no per-entity switch except this invisibility verdict, which it reads for the TAG

         *  only (the body reads {@code isInvisible()}, still false). Our own ladder is handed

         *  {@code visible = true} for proxies. Residual: an allied team with

         *  canSeeFriendlyInvisibles still gets vanilla's tag (accepted). */

        @Override

        public boolean isInvisibleTo(Player player) {

            return true;

        }


        @Override
        public boolean shouldRenderAtSqrDistance(double distanceSquared) {
            double max = Math.max(64, maxRenderDistanceBlocks);
            return distanceSquared <= max * max;
        }
    }

    /**
     * E2 renderer wiring, called once from {@link dev.vox.lss.neoforge.LSSNeoClientBootstrap}
     * (the NeoForge twin of Fabric's {@code LSSClient.initRenderer()} call — event registration
     * is per-loader wiring; the support class is xplat): the {@code AFTER_ENTITIES} pass
     * (contained — a renderer bug degrades to no proxies) plus the {@link EntityJoinLevelEvent}
     * edge trigger (a real player entity appearing kills its proxy the same frame — the crossfade
     * guard; UNLOAD needs no hook, the per-frame real-present conjunct picks it up next pass).
     * Both events are game-bus ({@link NeoForge#EVENT_BUS}).
     */
    public static void initRenderer() {
        if (instance != null) return; // double-registration armor (called once by construction)
        var renderer = new FarPlayerRenderer();
        FarPlayerRenderer.install(renderer);
        NeoForge.EVENT_BUS.addListener(RenderLevelStageEvent.class, e -> {
            // One listener, stage-filtered (the per-stage event subclasses are a later-NeoForge
            // thing); AFTER_ENTITIES is the Fabric-parity injection point (see the class javadoc).
            if (e.getStage() == RenderLevelStageEvent.Stage.AFTER_ENTITIES) {
                renderer.render(e);
            }
        });
        NeoForge.EVENT_BUS.addListener(EntityJoinLevelEvent.class, e -> {
            // isClientSide() is load-bearing TWICE (see the class javadoc): dist safety AND
            // thread confinement (an integrated server fires this same event on the SERVER
            // thread; the proxy map is render-thread-only).
            if (e.getLevel().isClientSide() && e.getEntity() instanceof Player p) {
                FarPlayerRenderer.onRealPlayerLoad(p.getUUID());
            }
        });
    }
}
