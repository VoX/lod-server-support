package dev.vox.lss.networking.client;

import com.mojang.authlib.GameProfile;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.vox.lss.common.LSSLogger;
import dev.vox.lss.common.farplayers.FarPlayerClientTracker;
import dev.vox.lss.common.farplayers.FarPlayerWire;
import dev.vox.lss.config.LSSClientConfig;
import dev.vox.lss.mixin.AccessorEntityRenderDispatcher;
import dev.vox.lss.mixin.AccessorLivingEntity;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.OrderedSubmitNodeCollector;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.MovingBlockRenderState;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.BlockStateModel;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.CameraRenderState;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.EquipmentAssetManager;
import net.minecraft.client.resources.model.EquipmentClientInfo;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityAttachment;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.equipment.Equippable;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.Team;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The far-player proxy renderer (E2, FARP §3.3/§7-B — the SeeU
 * {@code RemotePlayer}-proxy + {@code WorldRenderContext} submission approach, proven
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
 *   <li><b>Light, tags, depth lift (2026-09-04, far-player-render-hardening-plan.md; ported to
 *       this 1.21.11 line 2026-09-05)</b>: proxies are lit with a sky-15 FLOOR (or full-bright,
 *       {@code farPlayersFullBright}) set on the extracted render state — never from the
 *       client's stored light data, which reads zero in the C2ME no-tick ring; LSS draws its own
 *       depth-tested, sneak-hidden, sqrt-scaled name tags through {@code submitText} (vanilla
 *       gates tags at 64 blocks on every line); armor/held items are depth-lifted through
 *       {@link LiftedSubmitCollector}; the model-parts byte is set so the overlay skin layers
 *       render. NOT on this line (a named cut, per-version-surfaces.md): the draw-call frustum
 *       cull — this line's render path exposes no frustum ({@code WorldRenderContext} carries
 *       {@code commandQueue/matrices/worldState}, {@code CameraRenderState} has none) — and the
 *       shared-batch end, because submits are deferred here and the pass opens no batch of its
 *       own. See {@code floorLight}/{@code submitFarNameTag}.</li>
 * </ul>
 *
 * <p>Threading: every touchpoint runs on the client MAIN thread, which IS the render
 * thread (network receivers hop via execute(), ENTITY_LOAD fires from addEntity,
 * BEFORE_ENTITIES is the main-thread extract/submit phase). snapshot() is a shallow
 * copy sharing mutable FarPlayerMotion — it is defense-in-depth, NOT a thread
 * boundary; do not move this pass off-thread trusting it (E2 review n9).
 */
public final class FarPlayerRenderer {

    /** Whether THIS loader's tree renders far players (the options catalog hides the
     *  renderer-only options where it does not — sodium-options-page-generations-plan.md
     *  implementation review). The NeoForge twin's render path is a no-op stub. */
    public static final boolean RENDER_AVAILABLE = true;

    /** Proxy entity-id base: far above vanilla's server-assigned counter AND disjoint
     *  from SeeU's 1_000_000_000 block (both installed must never collide). Each id is
     *  additionally probed against the live level before use. */
    private static final int PROXY_ID_BASE = 1_900_000_000;

    private static final float WALK_ANIMATION_SCALE = 0.4f;
    /** Entity.FLAG_FALL_FLYING (protected there) — the bit isFallFlying() reads. */
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
    private int lastDrawn, lastMounts, lastTags;

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

    /** The BEFORE_ENTITIES pass (1.21.11 line: BEFORE, not 26.2's COLLECT_SUBMITS
     *  analog AFTER_ENTITIES — on this line AFTER_ENTITIES fires after
     *  FeatureRenderDispatcher.renderAllFeatures() has already drained AND CLEARED the
     *  submit-node storage, so submits landed there are drawn in the later particles
     *  pass or dropped. BEFORE_ENTITIES fires at popPush("submitEntities"), before
     *  vanilla's own submits and the drain — the actual COLLECT_SUBMITS semantics;
     *  bytecode-verified against LevelRenderer + fabric-api 16.2.10, review 2026-08-15). */
    public void render(WorldRenderContext context) {
        if (crashLatched) return;
        // Review fold (D1): run above a sentinel pose that is ALWAYS unwound — see markPose.
        PoseStack poseStack = context.matrices();
        passMark = poseStack == null ? null : markPose(poseStack);
        try {
            renderContained(context);
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
        var poseStack = context.matrices();
        SubmitNodeCollector collector = context.commandQueue();
        if (level == null || localPlayer == null || poseStack == null
                || collector == null || context.worldState() == null) {
            clear();
            return;
        }
        CameraRenderState cameraState = context.worldState().cameraRenderState;

        FarPlayerClientTracker tracker = FarPlayerClientSupport.tracker();
        String trackerDimension = tracker.dimension();
        if (trackerDimension == null
                || !trackerDimension.equals(level.dimension().identifier().toString())) {
            clear();
            return;
        }

        Vec3 cameraPosition = minecraft.gameRenderer.getMainCamera().position();
        var dispatcher = minecraft.getEntityRenderDispatcher();
        float partialTick = minecraft.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        int animationTick = localPlayer.tickCount;
        long now = FarPlayerClientSupport.monotonicMillis();
        int maxRender = config.farPlayersMaxRenderDistanceBlocks;
        int minRender = config.farPlayersMinDistanceBlocks;
        boolean fullBright = config.farPlayersFullBright;
        boolean nameTags = config.farPlayersNameTags && Minecraft.renderNames(); // F1/hide-GUI hides every tag (fold D3)
        // Fold (e2): the client-side equipment assets behind the armor layer's render types
        // (the dispatcher's private field, reached through the accessor; absent = every armor
        // piece on one tier, the pre-(e2) shape — never a throw).
        EquipmentAssetManager equipmentAssets = dispatcher instanceof AccessorEntityRenderDispatcher a
                ? a.lss$getEquipmentAssets() : null;
        int drawn = 0, mounts = 0;
        List<PendingTag> pendingTags = new ArrayList<>();

        Set<UUID> active = new HashSet<>();
        for (var tracked : tracker.snapshot().values()) {
            var sample = tracked.motion().sample(now);
            Vec3 position = new Vec3(sample.x(), sample.y(), sample.z());
            double distance = position.distanceTo(localPlayer.position());
            if (distance < minRender || (maxRender > 0 && distance > maxRender)) {
                continue;
            }
            // Review fold: depth work (the armor lift, the overlay gate) is CAMERA-based like the tag
            // math — freecam/replay; the range and animation caps stay player-based.
            double cameraDistance = position.distanceTo(cameraPosition);

            // Handoff = vanilla's own draw decision (M3 — see the class javadoc):
            // the proxy renders exactly when the real entity would not. Same
            // predicate both directions, so the swap is frame-synchronized with
            // vanilla's entity pop — no band, no flicker, no diagonal double-render,
            // no high-render-distance invisibility annulus.
            // NOTE (2026-09-04, far-player-render-hardening-plan.md F1/F3): ClientLevel
            // .hasChunk is unconditionally true on this MC too, so the chunkLoaded conjunct
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
                    proxy.apply(tracked, sample, position, cameraDistance,
                            maxRender > 0 ? maxRender : 16384, allowWalk, animationTick,
                            itemCache, equipmentAssets);
                } catch (Throwable t) {
                    latchSeatedFailure(tracked, proxy, t);
                    // Unmounted, the vehicle-state mixin path is inert — one retry.
                    // Guarded: if even the link-break threw (latchSeatedFailure dropped
                    // the proxy), a still-seated retry would re-throw into the
                    // whole-pass latch — skip this rider this frame instead.
                    if (!proxy.isPassenger()) {
                        proxy.apply(tracked, sample, position, cameraDistance,
                                maxRender > 0 ? maxRender : 16384, allowWalk,
                                animationTick, itemCache, equipmentAssets);
                    }
                }
            } else {
                proxy.apply(tracked, sample, position, cameraDistance,
                        maxRender > 0 ? maxRender : 16384, allowWalk, animationTick,
                        itemCache, equipmentAssets);
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
                    if (renderMount(wireVehicle, tracked, proxy, level, now, animationTick,
                            dispatcher, partialTick, cameraPosition, poseStack, collector,
                            cameraState, fullBright)) {
                        mounts++;
                    }
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
                    int light = submitProxy(dispatcher, proxy, partialTick, fullBright, cameraDistance,
                            position, cameraPosition, poseStack, collector, cameraState);
                    drawn++;
                    queueProxyTag(pendingTags, nameTags, tracked, proxy, localPlayer, position, light);
                } catch (Throwable t) {
                    latchSeatedFailure(tracked, proxy, t);
                    restorePose(poseStack, passMark);
                }
            } else {
                int light = submitProxy(dispatcher, proxy, partialTick, fullBright, cameraDistance,
                        position, cameraPosition, poseStack, collector, cameraState);
                drawn++;
                queueProxyTag(pendingTags, nameTags, tracked, proxy, localPlayer, position, light);
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
        // team switch returns first — stricter only (F5). The body clause is vanilla's own
        // entity-extraction predicate on this line (LevelRenderer: isOutsideBuildHeight ||
        // isSectionCompiledAndVisible — the latter also carries vanilla's section-visibility test, so a tag
        // never draws where the body was culled by it);
        // a player above the build ceiling keeps its vanilla body and therefore its tag.
        if (nameTags) {
            for (var realPlayer : level.players()) {
                if (realPlayer == localPlayer || realPlayer == minecraft.getCameraEntity()) continue;
                if (active.contains(realPlayer.getUUID())) continue;
                if (realPlayer.isDiscrete() || realPlayer.isVehicle()) continue; // LSS sneak policy; vanilla's mount rule
                Vec3 realPosition = new Vec3(Mth.lerp(partialTick, realPlayer.xOld, realPlayer.getX()),
                        Mth.lerp(partialTick, realPlayer.yOld, realPlayer.getY()),
                        Mth.lerp(partialTick, realPlayer.zOld, realPlayer.getZ()));
                var pos = realPlayer.blockPosition();
                if (!level.isOutsideBuildHeight(pos.getY())
                        && !minecraft.levelRenderer.isSectionCompiledAndVisible(pos)) continue; // vanilla's own body predicate (F4)
                double cameraDistanceSq = cameraPosition.distanceToSqr(realPosition);
                if (cameraDistanceSq < 64.0 * 64.0) continue; // vanilla's own tag range (camera-based, as its cap is)
                double realDistance = realPosition.distanceTo(localPlayer.position());
                if (realDistance < minRender || (maxRender > 0 && realDistance > maxRender)) continue;
                if (!vanillaNameVisibleIgnoringDistance(realPlayer, localPlayer)) continue;
                queueNameTag(pendingTags, realPlayer, realPlayer.getDisplayName().getVisualOrderText(),
                        realPosition, realPlayer.getYRot(partialTick), // the render-basis yaw, as vanilla's extraction uses
                        packedLightFor(dispatcher, realPlayer, partialTick, fullBright));
            }
        }
        // WI-6: the tags, submitted AFTER every model — all first (plate + glyphs) draws, then
        // all second (glyph-only, POLYGON_OFFSET) draws, so the text batch switches render type
        // once per frame, not once per tag (the text phase draws submits in submission order).
        int tagsDrawn = 0;
        for (var tag : pendingTags) {
            submitFarNameTag(collector, cameraState, tag, cameraPosition, poseStack,
                    Font.DisplayMode.NORMAL, true);
            tagsDrawn++;
        }
        for (var tag : pendingTags) {
            submitFarNameTag(collector, cameraState, tag, cameraPosition, poseStack,
                    Font.DisplayMode.POLYGON_OFFSET, false);
        }
        // No shared-batch end on this line (1.21.1's WI-4): every submit above is DEFERRED to
        // vanilla's FeatureRenderDispatcher, which draws it into its own buffer source and ends
        // that batch itself — this pass never opens one.
        this.lastDrawn = drawn;
        this.lastMounts = mounts;
        this.lastTags = tagsDrawn;
    }

    /** Extract + light-floor + submit one proxy through the lifting collector (fold (e)).
     *  Returns the light the proxy was submitted with (the tag rides the same value).
     *  Throws propagate to the caller's containment. */
    private static int submitProxy(EntityRenderDispatcher dispatcher, Proxy proxy, float partialTick,
                                   boolean fullBright, double cameraDistance, Vec3 position,
                                   Vec3 cameraPosition, PoseStack poseStack,
                                   SubmitNodeCollector collector, CameraRenderState cameraState) {
        var renderState = dispatcher.extractEntity(proxy, partialTick);
        // WI-1/WI-2 on the extract/submit pipeline: the extracted lightCoords IS vanilla's
        // getPackedLightCoords (third-party return hooks included) — floor it before submit;
        // LivingEntityRenderer.submit reads the field at draw time.
        renderState.lightCoords = floorLight(renderState.lightCoords, fullBright);
        // WI-6: vanilla's own tag for the proxy is null past 64 blocks, and LSS draws its own
        // in every case — never both (the one place a proxy inside 64 blocks would double-tag).
        renderState.nameTag = null;
        // ...and the below_name score plate beside it (vanilla draws it see-through inside 10 blocks).
        if (renderState instanceof AvatarRenderState avatar) avatar.scoreText = null;
        dispatcher.submit(
                renderState,
                cameraState,
                position.x - cameraPosition.x,
                position.y - cameraPosition.y,
                position.z - cameraPosition.z,
                poseStack,
                new LiftedSubmitCollector(collector, skinRenderType(dispatcher, proxy),
                        armorLiftBlocks(cameraDistance), proxy.liftTiers));
        return renderState.lightCoords;
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

    /** The per-rider mount pass: resolve/create/link/submit. Returns true when the mount
     *  was submitted this frame, false when ladder-degraded or not this rider's turn.
     *  Throws propagate to the per-type containment at the call site. */
    private boolean renderMount(dev.vox.lss.common.farplayers.FarPlayerWire.Vehicle wireVehicle,
                                FarPlayerClientTracker.TrackedFarPlayer tracked, Proxy proxy,
                                ClientLevel level, long now, int animationTick,
                                EntityRenderDispatcher dispatcher,
                                float partialTick, Vec3 cameraPosition,
                                PoseStack poseStack,
                                SubmitNodeCollector collector, CameraRenderState cameraState,
                                boolean fullBright) {
        var mount = mountFor(wireVehicle, tracked, level, now);
        if (mount == null) {
            if (proxy.isPassenger()) {
                proxy.stopRiding(); // ladder-degraded: render unmounted
            }
            return false;
        }
        activeVehicles.add(wireVehicle.uuid());
        if (proxy.getVehicle() != mount.entity) {
            proxy.stopRiding();
            // 26.2's 3-arg overload: the 1-arg form delegates to (entity, false,
            // true) — bytecode-checked (the third arg emits a GameEvent, a client
            // no-op) — so this passes force=TRUE (render-only instances can fail
            // vanilla's canRide/distance checks) and keeps the third at its vanilla
            // default. A false return is rung 3 of the ladder — mounted-position
            // standing pose, never a floating sit.
            proxy.startRiding(mount.entity, true, true);
        }
        if (!submittedVehicles.add(wireVehicle.uuid())) return false;
        var vSample = mount.motion.sample(now);
        applyMountState(mount, vSample, animationTick);
        var vState = dispatcher.extractEntity(mount.entity, partialTick);
        vState.lightCoords = floorLight(vState.lightCoords, fullBright); // WI-1: mounts too
        dispatcher.submit(
                vState,
                cameraState,
                vSample.x() - cameraPosition.x,
                vSample.y() - cameraPosition.y,
                vSample.z() - cameraPosition.z,
                poseStack,
                collector); // fold (e): mounts are NOT lifted (thicker shells, own layer set)
        return true;
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
        entity.setOldPosAndRot(position, s.yaw(), s.pitch());
        entity.xo = position.x;
        entity.yo = position.y;
        entity.zo = position.z;
        entity.xOld = position.x;
        entity.yOld = position.y;
        entity.zOld = position.z;
        entity.snapTo(position, s.yaw(), s.pitch());
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
                living.walkAnimation.update(Math.min(movement * 4.0f, 1.0f),
                        WALK_ANIMATION_SCALE, 1.0f);
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
     * vanilla's lookup hands back whatever light data the client HOLDS at the proxy's eye
     * ({@code EntityRenderer.extractRenderState} sets {@code lightCoords = getPackedLightCoords}
     * — third-party return hooks on it, Sable's sub-level lighting, apply before the floor).
     * Where the client holds a chunk with no real entity in it (C2ME's no-tick ring beyond the
     * server's entity-tracking radius) the stored sky light is often ZERO — an unbaked send
     * decodes as an all-zero layer — and the proxy went BLACK in daylight while the terrain
     * there is never drawn (outside Sodium's circle, Voxy paints LOD over it); past the drop
     * radius the engine has no data and answers 15, so the same player lit up again farther
     * away. The floor makes the proxy immune to that data whatever its cause. On this line
     * the value lives on the extracted render state ({@code lightCoords}, read at draw time),
     * so the floor is applied between extract and submit.
     */
    private static int floorLight(int vanilla, boolean fullBright) {
        if (fullBright) return LightTexture.FULL_BRIGHT;
        return LightTexture.pack(LightTexture.block(vanilla), 15);
    }

    /** {@link #floorLight} over vanilla's lookup for an entity this pass never extracts
     *  (the gap-fill tags over vanilla-drawn real players). */
    private static int packedLightFor(EntityRenderDispatcher dispatcher, Entity entity,
                                      float partialTick, boolean fullBright) {
        return floorLight(dispatcher.getPackedLightCoords(entity, partialTick), fullBright);
    }

    /** Fold (e): the lift for {@link LiftedSubmitCollector} — about five 24-bit depth steps at
     *  CAMERA distance {@code d} (the depth buffer's basis — freecam/replay), floored where steps
     *  are tiny and capped where the far plane is near. */
    private static float armorLiftBlocks(double distance) {
        return (float) Math.clamp(distance * distance * 6e-6, 0.02, 4.0);
    }

    /** Fold (e): the proxy's OWN skin render type — the one submit the lift must not touch.
     *  The avatar model is built on entityTranslucent; ask the live renderer's model so a
     *  replaced renderer still answers, and fall back to vanilla's function. */
    private static RenderType skinRenderType(EntityRenderDispatcher dispatcher, Proxy proxy) {
        Identifier skin = proxy.getSkin().body().texturePath();
        if (dispatcher.getRenderer(proxy) instanceof LivingEntityRenderer<?, ?, ?> living) {
            return living.getModel().renderType(skin);
        }
        return RenderTypes.entityTranslucent(skin);
    }

    /** Review fold (D1): a throw inside {@code dispatcher.submit} or the tag draw leaves pushes
     *  on VANILLA's pose stack, and {@code LevelRenderer.checkPoseStack} throws "Pose stack not
     *  empty" AFTER this pass returns — outside every containment here, a hard client crash.
     *  The pass therefore runs above a sentinel pose it always unwinds to, and the per-proxy /
     *  per-mount containments restore to the same mark before continuing the frame. (This
     *  line's PoseStack recycles its Pose objects by index, so the mark's identity is stable
     *  across the pushes/pops beneath it.) */
    private static PoseStack.Pose markPose(PoseStack poseStack) {
        poseStack.pushPose();
        return poseStack.last();
    }

    /** Pops back to {@code mark} (which is left in place); bounded by the stack root. */
    private static void restorePose(PoseStack poseStack, PoseStack.Pose mark) {
        if (mark == null) return;
        while (!poseStack.isEmpty() && poseStack.last() != mark) poseStack.popPose();
    }

    /** {@link #restorePose} and then pops the mark itself. */
    private static void unwindPose(PoseStack poseStack, PoseStack.Pose mark) {
        restorePose(poseStack, mark);
        if (poseStack.last() == mark) poseStack.popPose();
    }

    /**
     * Fold (e) (live rig 2026-09-04, SoakPlayer in armor): vanilla's armor is a 1/16-block
     * shell around the body (leggings 1/32); a 24-bit depth buffer behind vanilla's 0.05 near
     * plane resolves ≈ d²·1.2e-6 blocks per step, so past ~160-230 blocks armor and body land
     * inside ONE step and z-fight — vanilla never draws players that far, proxies always are.
     * On this line's extract/submit pipeline there is no buffer source to wrap at submit time
     * (vanilla draws every submit later, from its own {@code FeatureRenderDispatcher}), so the
     * lift is applied to the SUBMITTED POSE instead: every non-skin submit that carries a pose
     * (models, model parts, items, blocks, custom geometry) goes in with a copy of that pose
     * pre-multiplied by a uniform scale about the ORIGIN of the pass's camera-relative pose —
     * the camera — by {@code (d − lift)/d}, {@code d} the piece origin's camera distance. A
     * uniform scale about the projection centre is screen-exact (x/z and y/z are invariant),
     * and it pulls every vertex along its OWN view ray by {@code lift·|p|/d ≈ lift} blocks —
     * the same radial pull 1.21.1's per-vertex {@code LiftingConsumer} applies, off by at most
     * lift/d over a body's extent, far inside one depth step. Normals, light and overlay are
     * untouched; vanilla's outline/crumbling/sprite handling of the submit is untouched (the
     * submit itself is forwarded, only its pose differs). The skin's own render type passes
     * through with the original pose — body and overlay cubes share it — so the 1/64-block
     * overlay layers are gated by {@link #OVERLAY_MAX_DISTANCE_BLOCKS} instead. Mounts are not
     * wrapped: a uniform lift cannot separate a mount's own layers, and their shells are
     * thicker. {@code order(int)} is wrapped too — the armor layer submits every piece through
     * it. Deliberately NOT lifted: shadow (ground-anchored), flame, leash, text and name-tag
     * submits (none of them are a shell over the body; vanilla submits no tag for a proxy anyway)
     * — they forward with the original pose. A nested {@code order()} on a wrapper built from a
     * sub-collector is unreachable through vanilla's types (a sub-collector is handed out as
     * {@code OrderedSubmitNodeCollector}, which has no {@code order()}); if it were called it
     * would resolve against the ROOT collector, never chain.
     */
    private static final class LiftedSubmitCollector implements SubmitNodeCollector {
        private final SubmitNodeCollector root;
        private final OrderedSubmitNodeCollector delegate;
        private final RenderType skinType;
        private final float lift;
        private final Map<RenderType, Integer> tiers;
        /** One scratch pose stack per wrapper; every submit COPIES the pose it is handed
         *  (SubmitNodeCollection stores {@code last().copy()} / {@code new Matrix4f}), so the
         *  scratch is free to reuse between calls. */
        private final PoseStack scratch = new PoseStack();

        LiftedSubmitCollector(SubmitNodeCollector root, RenderType skinType, float lift,
                              Map<RenderType, Integer> tiers) {
            this(root, root, skinType, lift, tiers);
        }

        private LiftedSubmitCollector(SubmitNodeCollector root, OrderedSubmitNodeCollector delegate,
                                      RenderType skinType, float lift, Map<RenderType, Integer> tiers) {
            this.root = root;
            this.delegate = delegate;
            this.skinType = skinType;
            this.lift = lift;
            this.tiers = tiers;
        }

        /** The lifted pose for a submit of {@code type}: the skin's own pose untouched, every
         *  other type scaled toward the camera by its TIER's lift. */
        private PoseStack lifted(PoseStack poseStack, RenderType type) {
            if (type == skinType) return poseStack;
            // Fold (e2): TIERED lift — the pieces that overlap each other (leggings under a
            // chestplate, boots over leggings, a shield against the chest) are ~1/32 block apart
            // and would still fight under one uniform lift; tier 0 = inner armor model, 1 = outer
            // armor + trims + glint, 2 = held items / elytra / anything else, ~4 depth steps apart.
            int tier = tiers.getOrDefault(type, 2);
            return scaledTowardCamera(poseStack, lift * (1.0f + 0.8f * tier));
        }

        /** Every non-skin submit without a render type (blocks, moving blocks) — tier 2. */
        private PoseStack liftedUntyped(PoseStack poseStack) {
            return scaledTowardCamera(poseStack, lift * (1.0f + 0.8f * 2));
        }

        private PoseStack scaledTowardCamera(PoseStack poseStack, float tierLift) {
            PoseStack.Pose pose = scratch.last();
            pose.set(poseStack.last());
            Matrix4f m = pose.pose();
            // The pose's translation column = the piece origin in camera space; its length is
            // the camera distance the radial pull is scaled by.
            float d = (float) Math.sqrt(m.m30() * m.m30() + m.m31() * m.m31() + m.m32() * m.m32());
            if (d > tierLift + 1.0f) m.scaleLocal((d - tierLift) / d); // S · M: scale in camera space
            return scratch;
        }

        @Override
        public OrderedSubmitNodeCollector order(int order) {
            return new LiftedSubmitCollector(root, root.order(order), skinType, lift, tiers);
        }

        @Override
        public void submitShadow(PoseStack poseStack, float radius, List<EntityRenderState.ShadowPiece> pieces) {
            delegate.submitShadow(poseStack, radius, pieces);
        }

        @Override
        public void submitNameTag(PoseStack poseStack, Vec3 attachment, int yOffset, Component text,
                                  boolean seeThrough, int light, double distanceToCameraSq,
                                  CameraRenderState camera) {
            delegate.submitNameTag(poseStack, attachment, yOffset, text, seeThrough, light,
                    distanceToCameraSq, camera);
        }

        @Override
        public void submitText(PoseStack poseStack, float x, float y, FormattedCharSequence text,
                               boolean dropShadow, Font.DisplayMode mode, int light, int color,
                               int backgroundColor, int outlineColor) {
            delegate.submitText(poseStack, x, y, text, dropShadow, mode, light, color, backgroundColor,
                    outlineColor);
        }

        @Override
        public void submitFlame(PoseStack poseStack, EntityRenderState state, Quaternionf rotation) {
            delegate.submitFlame(poseStack, state, rotation);
        }

        @Override
        public void submitLeash(PoseStack poseStack, EntityRenderState.LeashState leash) {
            delegate.submitLeash(poseStack, leash);
        }

        @Override
        public <S> void submitModel(Model<? super S> model, S state, PoseStack poseStack, RenderType type,
                                    int light, int overlay, int color, TextureAtlasSprite sprite,
                                    int outlineColor, ModelFeatureRenderer.CrumblingOverlay crumbling) {
            delegate.submitModel(model, state, lifted(poseStack, type), type, light, overlay, color, sprite,
                    outlineColor, crumbling);
        }

        @Override
        public void submitModelPart(ModelPart part, PoseStack poseStack, RenderType type, int light,
                                    int overlay, TextureAtlasSprite sprite, boolean flag1, boolean flag2,
                                    int color, ModelFeatureRenderer.CrumblingOverlay crumbling, int outlineColor) {
            delegate.submitModelPart(part, lifted(poseStack, type), type, light, overlay, sprite, flag1, flag2,
                    color, crumbling, outlineColor);
        }

        @Override
        public void submitBlock(PoseStack poseStack, BlockState state, int light, int overlay, int outlineColor) {
            delegate.submitBlock(liftedUntyped(poseStack), state, light, overlay, outlineColor);
        }

        @Override
        public void submitMovingBlock(PoseStack poseStack, MovingBlockRenderState state) {
            delegate.submitMovingBlock(liftedUntyped(poseStack), state);
        }

        @Override
        public void submitBlockModel(PoseStack poseStack, RenderType type, BlockStateModel model, float r,
                                     float g, float b, int light, int overlay, int outlineColor) {
            delegate.submitBlockModel(lifted(poseStack, type), type, model, r, g, b, light, overlay, outlineColor);
        }

        @Override
        public void submitItem(PoseStack poseStack, ItemDisplayContext context, int light, int overlay,
                               int outlineColor, int[] tints, List<BakedQuad> quads, RenderType type,
                               ItemStackRenderState.FoilType foil) {
            delegate.submitItem(lifted(poseStack, type), context, light, overlay, outlineColor, tints, quads,
                    type, foil);
        }

        @Override
        public void submitCustomGeometry(PoseStack poseStack, RenderType type,
                                         SubmitNodeCollector.CustomGeometryRenderer renderer) {
            delegate.submitCustomGeometry(lifted(poseStack, type), type, renderer);
        }

        @Override
        public void submitParticleGroup(SubmitNodeCollector.ParticleGroupRenderer renderer) {
            delegate.submitParticleGroup(renderer);
        }
    }

    /** A tag queued during the pass and drawn after it (WI-6): over a Proxy, or over a
     *  vanilla-tracked player past vanilla's own 64-block tag cap (the gap fill in the pass).
     *  The anchor is resolved ONCE here (review fold F9/D7). */
    private record PendingTag(Entity entity, FormattedCharSequence text, Vec3 anchor, int light) {}

    /** The name-tag anchor in world space: vanilla's NAME_TAG attachment + 0.5. */
    private static Vec3 tagAnchor(Entity entity, Vec3 position, float yaw) {
        Vec3 attachment = entity.getAttachments()
                .getNullable(EntityAttachment.NAME_TAG, 0, yaw);
        return attachment == null ? null : position.add(attachment.x, attachment.y + 0.5, attachment.z);
    }

    /** WI-6 proxy gate: the option (already folded with the hide-GUI key), the sneak rule (a
     *  NEW LSS policy — "sneak = don't advertise me"; vanilla's own sneak cap lives in code
     *  that never runs for a proxy) and — review fold D3 — vanilla's invisibility + team
     *  name-tag rules, so a team set to "never" is honoured beyond the tracking radius exactly
     *  as inside it (the proxy's team resolves through the scoreboard by name). */
    private static void queueProxyTag(List<PendingTag> out, boolean nameTags,
                                      FarPlayerClientTracker.TrackedFarPlayer tracked, Proxy proxy,
                                      LocalPlayer localPlayer, Vec3 position, int light) {
        if (!nameTags) return;
        if ((tracked.latest().poseFlags() & FarPlayerWire.POSE_SNEAK) != 0) return;
        if (!vanillaNameVisibleIgnoringDistance(proxy, localPlayer)) return;
        queueNameTag(out, proxy, proxy.farName, position, proxy.getYRot(), light); // yRot == yRotO by construction
    }

    /** Resolves the anchor once, then queues (no frustum on this line's render path — see
     *  the class javadoc; the tag is depth-tested like everything else). */
    private static void queueNameTag(List<PendingTag> out, Entity entity,
                                     FormattedCharSequence text, Vec3 position, float yaw, int light) {
        Vec3 a = tagAnchor(entity, position, yaw);
        if (a == null) return;
        out.add(new PendingTag(entity, text, a, light));
    }

    /**
     * Vanilla's {@code LivingEntityRenderer.shouldShowName} ladder MINUS its distance clause
     * (that clause is exactly what the gap fill supplies): invisibility to the local player
     * and the team name-tag visibility rules, verbatim.
     */
    private static boolean vanillaNameVisibleIgnoringDistance(AbstractClientPlayer player,
                                                              LocalPlayer localPlayer) {
        boolean visible = !player.isInvisibleTo(localPlayer);
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
     * {@code setCustomNameVisible} could never produce one. Vanilla's own name-tag math
     * ({@code NameTagFeatureRenderer.Storage.add}: attachment + 0.5, camera billboard,
     * {@code (s, -s, s)}) through this line's {@code submitText} (10-arg: pose, x, y, text,
     * dropShadow, display mode, light, colour, background, outline), with three deliberate
     * deviations: NORMAL (depth-tested) display mode only — no see-through pass, the privacy
     * stance that also rejects glow; vanilla's 25% background behind the depth-tested text
     * (legibility at distance); and {@code s = 0.025 × clamp(sqrt(d/64), 1, 8)} so the tag
     * stays readable without turning a two-pixel body into a full-size plate (constant
     * apparent size looked like an ESP HUD — panel Q4). {@code d} is the CAMERA distance
     * (review fold F1: freecam/replay). {@code submitText} captures the pose matrix verbatim,
     * so it is built exactly as {@code drawInBatch} would consume it.
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
     * replacement for a geometric lift). The pass submits all first draws, then all second
     * draws (the text phase draws in submission order). The push is unwound in a finally
     * (fold D1).
     */
    private static void submitFarNameTag(SubmitNodeCollector collector, CameraRenderState cameraState,
                                         PendingTag tag, Vec3 cameraPosition, PoseStack poseStack,
                                         Font.DisplayMode mode, boolean withPlate) {
        Vec3 anchor = tag.anchor();
        double cameraDistance = anchor.distanceTo(cameraPosition);
        float scale = 0.025f * (float) Math.clamp(Math.sqrt(cameraDistance / 64.0), 1.0, 8.0);
        Minecraft minecraft = Minecraft.getInstance();
        Font font = minecraft.font;
        poseStack.pushPose();
        try {
            poseStack.translate(anchor.x - cameraPosition.x, anchor.y - cameraPosition.y,
                    anchor.z - cameraPosition.z);
            poseStack.mulPose(cameraState.orientation);
            poseStack.scale(scale, -scale, scale);
            float x = -font.width(tag.text()) / 2.0f;
            int background = (int) (minecraft.options.getBackgroundOpacity(0.25f) * 255.0f) << 24;
            collector.submitText(poseStack, x, 0.0f, tag.text(), false, mode, tag.light(), -1,
                    withPlate ? background : 0, 0);
        } finally {
            poseStack.popPose();
        }
    }

    /** The /lss diag line (WI-10): last pass's draw/mount/tag counts + the light mode — the
     *  live-gate instrument the original black-proxy sighting lacked. No {@code culled=} on
     *  this line: the draw-call frustum cull is a named cut here (no frustum on the render
     *  path), so there is nothing to count. */
    public static String diagLine() {
        var r = instance;
        if (r == null) return "FarPlayerRender: off";
        return "FarPlayerRender: drawn=" + r.lastDrawn
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
        final FormattedCharSequence farName;
        /** Fold (e2): render type -> lift tier for this proxy's equipment (refreshLiftTiers). */
        final Map<RenderType, Integer> liftTiers = new IdentityHashMap<>();
        private int maxRenderDistanceBlocks = 16384;
        private Vec3 lastWalkPosition;
        private int lastWalkTick = Integer.MIN_VALUE;

        private Proxy(ClientLevel level, UUID trackedUuid, String name, int entityId) {
            super(level, new GameProfile(trackedUuid, name));
            this.trackedUuid = trackedUuid;
            this.farName = Component.literal(name).getVisualOrderText();
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
                   Vec3 position, double cameraDistance, int maxRenderDistanceBlocks,
                   boolean allowWalk, int animationTick, Map<String, Item> itemCache,
                   EquipmentAssetManager equipmentAssets) {
            byte pose = tracked.latest().poseFlags();
            boolean gliding = (pose & FarPlayerWire.POSE_GLIDE) != 0;
            boolean swimming = (pose & FarPlayerWire.POSE_SWIM) != 0;
            boolean sneaking = (pose & FarPlayerWire.POSE_SNEAK) != 0;

            this.maxRenderDistanceBlocks = maxRenderDistanceBlocks;
            boolean newTick = this.tickCount != animationTick;
            this.tickCount = animationTick;
            this.setOldPosAndRot(position, sample.yaw(), sample.pitch());
            this.xo = position.x;
            this.yo = position.y;
            this.zo = position.z;
            this.xOld = position.x;
            this.yOld = position.y;
            this.zOld = position.z;
            this.snapTo(position, sample.yaw(), sample.pitch());
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
            // (d), live rig 2026-09-04): the FALL_FLYING shared flag (the avatar renderer,
            // HumanoidModel and ElytraModel read isFallFlying(), NOT the pose — without it a
            // glider stood upright with folded wings), the fall-fly tick count behind the -90°
            // glide tilt (vanilla's fade: full at 10 ticks), and the swim amount behind the
            // swimming roll + stroke (vanilla's ±0.09/tick ramp, via AccessorLivingEntity — the
            // fields are private). The render state extracts all three from the entity.
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
            refreshLiftTiers(equipmentAssets);
            // WI-5: the model-parts byte (defaults to 0 = every overlay layer hidden — hat,
            // jacket, sleeves, pants were invisible on every proxy). All layers EXCEPT the
            // cape: CapeLayer positions the cape from cloak fields only Player.tick's cloak
            // physics update — never run on a render-only proxy — so an enabled cape renders
            // relative to the origin. The wings layer selects the cape-textured elytra through
            // the same CAPE bit and CapeLayer bails on an elytra chest, so the full byte is safe
            // exactly then. The byte lives on Avatar on this line (Player extends Avatar).
            boolean elytra = this.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA);
            byte parts = cameraDistance <= OVERLAY_MAX_DISTANCE_BLOCKS
                    ? (byte) (elytra ? 0x7F : 0x7E)
                    : (byte) (elytra ? 0x01 : 0x00); // fold (e): base skin only past the overlay range
            this.getEntityData().set(DATA_PLAYER_MODE_CUSTOMISATION, parts);
            updateWalkAnimation(position, allowWalk,
                    gliding || swimming || tracked.latest().vehicle() != null,
                    animationTick);
        }

        /** Fold (e2): the lift TIER per render type for THIS proxy's equipment (see
         *  LiftedSubmitCollector): the inner armor model (LEGS — HumanoidArmorLayer.usesInnerModel;
         *  a 1/32-block shell) is tier 0, outer armor + trims + glint tier 1, everything else
         *  (held items, the elytra) tier 2. Exact render-type identities, computed the way the
         *  armor layer computes them on this line ({@code EquipmentLayerRenderer.renderLayers}:
         *  {@code armorCutoutNoCull(layer.getTextureLocation(layerType))} per
         *  {@code EquipmentClientInfo} layer of the piece's {@code Equippable} asset — memoized
         *  per texture), never string-sniffed. Rebuilt per apply from the equipment the wire
         *  just set; identity-keyed. Without the asset manager every armor piece lands on tier
         *  2 with the items (the pre-(e2) shape). */
        private void refreshLiftTiers(EquipmentAssetManager equipmentAssets) {
            liftTiers.clear();
            liftTiers.put(Sheets.armorTrimsSheet(true), 1);
            liftTiers.put(Sheets.armorTrimsSheet(false), 1);
            liftTiers.put(RenderTypes.armorEntityGlint(), 1);
            if (equipmentAssets == null) return;
            for (EquipmentSlot slot : ARMOR_EQUIPMENT_SLOTS) {
                Equippable equippable = this.getItemBySlot(slot).get(DataComponents.EQUIPPABLE);
                if (equippable == null || equippable.slot() != slot || equippable.assetId().isEmpty()) continue;
                boolean inner = slot == EquipmentSlot.LEGS;
                EquipmentClientInfo.LayerType layerType = inner
                        ? EquipmentClientInfo.LayerType.HUMANOID_LEGGINGS
                        : EquipmentClientInfo.LayerType.HUMANOID;
                for (EquipmentClientInfo.Layer layer : equipmentAssets.get(equippable.assetId().get()).getLayers(layerType)) {
                    liftTiers.put(RenderTypes.armorCutoutNoCull(layer.getTextureLocation(layerType)), inner ? 0 : 1);
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
                return BuiltInRegistries.ITEM.getValue(Identifier.parse(id));
            } catch (Exception e) {
                return Items.AIR;
            }
        }

        /** The walk-cycle stop on this line is vanilla's own {@code WalkAnimationState.stop()}
         *  — verified at the port to zero {@code speedOld} as well as {@code speed} (the
         *  1.21.1 twins have no {@code stop()} and needed a setSpeed(0)+update(0,1) helper:
         *  hardening plan fold (c)); the renderer's {@code speed(partialTick)} lerp then has
         *  nothing to sawtooth. */
        private void updateWalkAnimation(Vec3 position, boolean allowWalk,
                                         boolean nonWalkingPose, int animationTick) {
            if (lastWalkPosition == null || animationTick == lastWalkTick) {
                if (lastWalkPosition == null) {
                    lastWalkPosition = position;
                    lastWalkTick = animationTick;
                    this.walkAnimation.stop();
                }
                return;
            }
            lastWalkTick = animationTick;
            if (!allowWalk || nonWalkingPose) {
                this.walkAnimation.stop();
                lastWalkPosition = position;
                return;
            }
            float movement = (float) Mth.length(position.x - lastWalkPosition.x, 0,
                    position.z - lastWalkPosition.z);
            this.walkAnimation.update(Math.min(movement * 4.0f, 1.0f),
                    WALK_ANIMATION_SCALE, 1.0f);
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
     * wiring; the support class is xplat): the BEFORE_ENTITIES pass (contained — a
     * renderer bug degrades to no proxies) plus the ENTITY_LOAD edge trigger (a real
     * player entity appearing kills its proxy the same frame — the crossfade guard;
     * UNLOAD needs no hook, the per-frame real-present conjunct picks it up next pass).
     */
    public static void initRenderer() {
        var renderer = new FarPlayerRenderer();
        FarPlayerRenderer.install(renderer);
        net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents
                .BEFORE_ENTITIES.register(renderer::render);
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientEntityEvents
                .ENTITY_LOAD.register((entity, world) -> {
                    if (entity instanceof net.minecraft.world.entity.player.Player p) {
                        FarPlayerRenderer.onRealPlayerLoad(p.getUUID());
                    }
                });
    }
}
