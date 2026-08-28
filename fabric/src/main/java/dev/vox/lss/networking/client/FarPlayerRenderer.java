package dev.vox.lss.networking.client;

import com.mojang.authlib.GameProfile;
import dev.vox.lss.common.LSSLogger;
import dev.vox.lss.common.farplayers.FarPlayerClientTracker;
import dev.vox.lss.common.farplayers.FarPlayerWire;
import dev.vox.lss.config.LSSClientConfig;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The far-player proxy renderer (E2, FARP §3.3/§7-B — the SeeU
 * {@code RemotePlayer}-proxy + {@code WorldRenderContext} immediate-render approach, proven
 * on 26.2, reimplemented in LSS idiom). Differences from SeeU that are DECISIONS, not
 * drift (all review-pinned in the FARP plan):
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
 *       {@code ClientEntityEvents.ENTITY_LOAD} stays as the same-frame kill when a
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
 * </ul>
 *
 * <p>Threading: every touchpoint runs on the client MAIN thread, which IS the render
 * thread (network receivers hop via execute(), ENTITY_LOAD fires from addEntity,
 * AFTER_ENTITIES is a main-thread render-pass event). snapshot() is a shallow
 * copy sharing mutable FarPlayerMotion — it is defense-in-depth, NOT a thread
 * boundary; do not move this pass off-thread trusting it (E2 review n9).
 */
public final class FarPlayerRenderer {

    /** Whether THIS loader's tree renders far players (the options catalog hides the
     *  renderer-only options where it does not — sodium-options-page-generations-plan.md
     *  implementation review). On this 1.21.1 line the NeoForge twin also renders since
     *  v0.14.0 (it was a no-op stub through v0.13.x). */
    public static final boolean RENDER_AVAILABLE = true;

    /** Proxy entity-id base: far above vanilla's server-assigned counter AND disjoint
     *  from SeeU's 1_000_000_000 block (both installed must never collide). Each id is
     *  additionally probed against the live level before use. */
    private static final int PROXY_ID_BASE = 1_900_000_000;

    private static final float WALK_ANIMATION_SCALE = 0.4f;

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
            r.mountLadder.reset(); // m7: type latches are per-session, as documented
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
    public void render(WorldRenderContext context) {
        if (crashLatched) return;
        try {
            renderContained(context);
        } catch (Throwable t) {
            crashLatched = true;
            clear();
            LSSLogger.error("Far-player renderer failed — proxies disabled for this session"
                    + " (a renderer bug must never take the render thread down)", t);
        }
    }

    private void renderContained(WorldRenderContext context) {
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
        var poseStack = context.matrixStack();
        if (level == null || localPlayer == null || poseStack == null
                || context.consumers() == null) {
            clear();
            return;
        }

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

        Set<UUID> active = new HashSet<>();
        for (var tracked : tracker.snapshot().values()) {
            var sample = tracked.motion().sample(now);
            Vec3 position = new Vec3(sample.x(), sample.y(), sample.z());
            double distance = position.distanceTo(localPlayer.position());
            if (distance < minRender || (maxRender > 0 && distance > maxRender)) {
                continue;
            }

            // Handoff = vanilla's own draw decision (M3 — see the class javadoc):
            // the proxy renders exactly when the real entity would not. Same
            // predicate both directions, so the swap is frame-synchronized with
            // vanilla's entity pop — no band, no flicker, no diagonal double-render,
            // no high-render-distance invisibility annulus.
            var real = level.getPlayerByUUID(tracked.uuid());
            if (real != null
                    && level.hasChunk(Mth.floor(position.x) >> 4,
                            Mth.floor(position.z) >> 4)
                    && real.shouldRenderAtSqrDistance(
                            cameraPosition.distanceToSqr(real.position()))) {
                continue;
            }

            Proxy proxy = proxies.compute(tracked.uuid(), (uuid, current) ->
                    current == null || current.level() != level
                            ? new Proxy(level, uuid, tracked.name(), nextEntityId(level))
                            : current);
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
                    proxy.apply(tracked, sample, position, config.farPlayersNameTags,
                            maxRender > 0 ? maxRender : 16384, allowWalk, animationTick,
                            itemCache);
                } catch (Throwable t) {
                    latchSeatedFailure(tracked, proxy, t);
                    // Unmounted, the vehicle-state mixin path is inert — one retry.
                    // Guarded: if even the link-break threw (latchSeatedFailure dropped
                    // the proxy), a still-seated retry would re-throw into the
                    // whole-pass latch — skip this rider this frame instead.
                    if (!proxy.isPassenger()) {
                        proxy.apply(tracked, sample, position, config.farPlayersNameTags,
                                maxRender > 0 ? maxRender : 16384, allowWalk,
                                animationTick, itemCache);
                    }
                }
            } else {
                proxy.apply(tracked, sample, position, config.farPlayersNameTags,
                        maxRender > 0 ? maxRender : 16384, allowWalk, animationTick,
                        itemCache);
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
                    renderMount(wireVehicle, tracked, proxy, level, now, animationTick,
                            dispatcher, partialTick, cameraPosition, poseStack, context);
                } catch (Throwable e) {
                    // Throwable, not Exception (MINOR-3): a LinkageError from a modded
                    // entity class init must latch the TYPE, not the whole feature.
                    mountLadder.latchRenderFailure(wireVehicle.typeIdentity(), e);
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
                    // 1.21.1 line: no extract/submit phase on this MC — immediate
                    // dispatcher.render (the standard fake-entity approach); yaw is
                    // the sampled value (proxy yRot == yRotO by construction).
                    dispatcher.render(proxy,
                            position.x - cameraPosition.x,
                            position.y - cameraPosition.y,
                            position.z - cameraPosition.z,
                            sample.yaw(), partialTick, poseStack,
                            context.consumers(),
                            packedLightFor(dispatcher, proxy, level, position, partialTick));
                } catch (Throwable t) {
                    latchSeatedFailure(tracked, proxy, t);
                }
            } else {
                dispatcher.render(proxy,
                        position.x - cameraPosition.x,
                        position.y - cameraPosition.y,
                        position.z - cameraPosition.z,
                        sample.yaw(), partialTick, poseStack,
                        context.consumers(),
                        packedLightFor(dispatcher, proxy, level, position, partialTick));
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

    /** The per-rider mount pass: resolve/create/link/submit. Throws propagate to the
     *  per-type containment at the call site. */
    private void renderMount(dev.vox.lss.common.farplayers.FarPlayerWire.Vehicle wireVehicle,
                             FarPlayerClientTracker.TrackedFarPlayer tracked, Proxy proxy,
                             ClientLevel level, long now, int animationTick,
                             net.minecraft.client.renderer.entity.EntityRenderDispatcher dispatcher,
                             float partialTick, Vec3 cameraPosition,
                             com.mojang.blaze3d.vertex.PoseStack poseStack,
                             WorldRenderContext context) {
        var mount = mountFor(wireVehicle, tracked, level, now);
        if (mount == null) {
            if (proxy.isPassenger()) {
                proxy.stopRiding(); // ladder-degraded: render unmounted
            }
            return;
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
        if (submittedVehicles.add(wireVehicle.uuid())) {
            var vSample = mount.motion.sample(now);
            applyMountState(mount, vSample, animationTick);
            // 1.21.1 line: immediate dispatcher.render (no extract/submit on this MC).
            var vPos = new Vec3(vSample.x(), vSample.y(), vSample.z());
            dispatcher.render(mount.entity,
                    vSample.x() - cameraPosition.x,
                    vSample.y() - cameraPosition.y,
                    vSample.z() - cameraPosition.z,
                    vSample.yaw(), partialTick, poseStack,
                    context.consumers(),
                    packedLightFor(dispatcher, mount.entity, level, vPos, partialTick));
        }
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

    /** 1.21.1 line: the immediate render path needs a packed light value (26.x's
     *  submit pipeline derived it during extraction). Proxies mostly stand in
     *  UNLOADED chunks (the handoff predicate renders them exactly where the real
     *  entity would not draw), where the light engine has no data and vanilla's
     *  per-entity lookup reads dark — so loaded chunks use vanilla's own lookup and
     *  unloaded ones fall back to full-bright sky (LOD-range players are outdoors
     *  by construction; matches how the LOD terrain itself reads at distance). */
    private static int packedLightFor(
            net.minecraft.client.renderer.entity.EntityRenderDispatcher dispatcher,
            net.minecraft.world.entity.Entity entity, ClientLevel level, Vec3 position,
            float partialTick) {
        if (level.hasChunk(Mth.floor(position.x) >> 4, Mth.floor(position.z) >> 4)) {
            return dispatcher.getPackedLightCoords(entity, partialTick);
        }
        return net.minecraft.client.renderer.LightTexture.pack(15, 15);
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
        private int maxRenderDistanceBlocks = 16384;
        private Vec3 lastWalkPosition;
        private int lastWalkTick = Integer.MIN_VALUE;

        private Proxy(ClientLevel level, UUID trackedUuid, String name, int entityId) {
            super(level, new GameProfile(trackedUuid, name));
            this.trackedUuid = trackedUuid;
            this.setId(entityId);
            this.noPhysics = true;
            this.setNoGravity(true);
            this.setInvisible(false);
            // Deliberately NO setGlowingTag — see the class javadoc.
        }

        void apply(FarPlayerClientTracker.TrackedFarPlayer tracked,
                   dev.vox.lss.common.farplayers.FarPlayerMotion.Sample sample,
                   Vec3 position, boolean nameTags, int maxRenderDistanceBlocks,
                   boolean allowWalk, int animationTick, Map<String, Item> itemCache) {
            byte pose = tracked.latest().poseFlags();
            boolean gliding = (pose & FarPlayerWire.POSE_GLIDE) != 0;
            boolean swimming = (pose & FarPlayerWire.POSE_SWIM) != 0;
            boolean sneaking = (pose & FarPlayerWire.POSE_SNEAK) != 0;

            this.maxRenderDistanceBlocks = maxRenderDistanceBlocks;
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
            this.setPose(gliding ? Pose.FALL_FLYING
                    : swimming ? Pose.SWIMMING
                    : sneaking ? Pose.CROUCHING
                    : Pose.STANDING);
            applyEquipment(tracked, itemCache);
            this.setCustomName(Component.literal(tracked.name()));
            this.setCustomNameVisible(nameTags);
            updateWalkAnimation(position, allowWalk,
                    gliding || swimming || tracked.latest().vehicle() != null,
                    animationTick);
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

        private void updateWalkAnimation(Vec3 position, boolean allowWalk,
                                         boolean nonWalkingPose, int animationTick) {
            if (lastWalkPosition == null || animationTick == lastWalkTick) {
                if (lastWalkPosition == null) {
                    lastWalkPosition = position;
                    lastWalkTick = animationTick;
                    this.walkAnimation.setSpeed(0.0f); // 1.21.1 line: no stop() on this MC
                }
                return;
            }
            lastWalkTick = animationTick;
            if (!allowWalk || nonWalkingPose) {
                this.walkAnimation.setSpeed(0.0f); // 1.21.1 line: no stop() on this MC
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

        @Override
        public boolean shouldRenderAtSqrDistance(double distanceSquared) {
            double max = Math.max(64, maxRenderDistanceBlocks);
            return distanceSquared <= max * max;
        }
    }

    /**
     * E2 renderer wiring, called once from {@link dev.vox.lss.LSSClient} (moved here
     * from FarPlayerClientSupport at N-1b — Fabric event registration is per-loader
     * wiring; the support class is xplat): the AFTER_ENTITIES pass (contained — a
     * renderer bug degrades to no proxies) plus the ENTITY_LOAD edge trigger (a real
     * player entity appearing kills its proxy the same frame — the crossfade guard;
     * UNLOAD needs no hook, the per-frame real-present conjunct picks it up next pass).
     */
    public static void initRenderer() {
        var renderer = new FarPlayerRenderer();
        FarPlayerRenderer.install(renderer);
        net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents
                .AFTER_ENTITIES.register(renderer::render);
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientEntityEvents
                .ENTITY_LOAD.register((entity, world) -> {
                    if (entity instanceof net.minecraft.world.entity.player.Player p) {
                        FarPlayerRenderer.onRealPlayerLoad(p.getUUID());
                    }
                });
    }
}
