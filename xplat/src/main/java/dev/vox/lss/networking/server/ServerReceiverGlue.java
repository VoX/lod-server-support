package dev.vox.lss.networking.server;

import dev.vox.lss.common.Brand;
import dev.vox.lss.common.HandshakeGate;
import dev.vox.lss.common.PlayerServiceGate;
import dev.vox.lss.common.ServiceGateState;
import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.common.LSSLogger;
import dev.vox.lss.config.LSSServerConfig;
import dev.vox.lss.networking.payloads.HandshakeC2SPayload;
import dev.vox.lss.networking.payloads.SessionConfigS2CPayload;
import net.minecraft.SharedConstants;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.concurrent.ConcurrentHashMap;

/**
 * The loader-neutral RECEIVER BODIES behind each loader's networking glue
 * (N-2, neoforge-support-plan.md §1.1): handshake policy dispatch, the dirty
 * save-hook body, the client_info sidecar facts, and the far-player prefs
 * ingress. Extracted VERBATIM from the Fabric {@code LSSServerNetworking}
 * (which keeps delegating statics so every existing test/gametest signature
 * survives) so the NeoForge module reuses this layer instead of drifting a
 * textual twin — {@code HandshakeGate} owns the policy ladder; this class owns
 * the glue AROUND it (logging, service calls, reply construction).
 *
 * <p>Per-loader responsibilities that deliberately stay OUT of this class:
 * event/receiver registration, the static service holder, LAN startup, and the
 * reply transport (the {@link SessionConfigResponder} the loader passes in).
 */
public final class ServerReceiverGlue {

    private ServerReceiverGlue() {
    }

    // Dimension strings for the save hook, cached per ResourceKey: Identifier.toString
    // allocates, and the hook runs on every committed chunk save. Keyed by the
    // lightweight interned ResourceKey (never the ServerLevel — that would pin departed
    // worlds); bounded by the distinct dimensions a JVM ever loads.
    private static final ConcurrentHashMap<ResourceKey<Level>, String> DIMENSION_STRINGS =
            new ConcurrentHashMap<>();

    // lss:client_info sidecar facts (XVER §2.2): the client's MC data version, keyed by
    // UUID, swept at disconnect. Absence = legacy client (no sidecar channel). Consumed
    // as diagnostics + the C5 Via-guard input.
    private static final ConcurrentHashMap<java.util.UUID, Integer> CLIENT_DATA_VERSIONS =
            new ConcurrentHashMap<>();

    /** The client's announced MC data version, or null for a legacy client. */
    public static Integer clientDataVersion(java.util.UUID uuid) {
        return CLIENT_DATA_VERSIONS.get(uuid);
    }

    /** lss:client_info receiver body — recorded at the network level. */
    public static void recordClientInfo(java.util.UUID uuid, int dataVersion) {
        CLIENT_DATA_VERSIONS.put(uuid, dataVersion);
    }

    /** Disconnect sweep: the sidecar fact dies with the connection. */
    public static void sweepClientInfo(java.util.UUID uuid) {
        CLIENT_DATA_VERSIONS.remove(uuid);
    }

    /** Server-stop sweep: sidecar facts die with the server (integrated-server world
     *  cycles would otherwise accrete entries across sessions — review C1-9). */
    public static void clearClientInfo() {
        CLIENT_DATA_VERSIONS.clear();
        clearPendingLoadSeeds();
    }

    /**
     * The dirty-detection hook body (each loader's {@code ChunkSaveDataHook} mixin —
     * the copyOf choke point vanilla's {@code ChunkMap.save} and Moonrise's replacement
     * save pipeline both call; issue #69). Runs on whatever thread legally snapshots
     * the live chunk for saving — the same access domain {@code copyOf} itself needs,
     * so reading section content here is safe wherever the call is legal.
     *
     * <p>Only FULL chunks: a ProtoChunk save during generation has no LOD-servable
     * content yet (re-requesting it reads "not found" and escalates to generation), and
     * the completed chunk reaches clients through the generation serve path. The
     * {@code enabled=false} gate also lives here: the service tick (and so the
     * dirty-broadcast drain) is disabled, so marking would grow the tracker without
     * bound — and the content hash serializes the column on every save for nothing.
     * Vanilla re-saves loaded chunks for metadata alone (inhabitedTime), so a save is
     * not evidence of change — only hash-confirmed content edits mark dirty.
     */
    public static void onChunkSaveData(ServerLevel level, ChunkAccess chunk,
                                       RequestProcessingService service) {
        if (!(chunk instanceof LevelChunk levelChunk)) return;
        if (service == null || !LSSServerConfig.CONFIG.enabled) return;
        // Skip gate (2026-08-05 review P3 + the three-lens follow-up): see skipDirtyHash.
        if (skipDirtyHash(service.hasEverRegisteredPlayer(), service.getLodStore() != null,
                service.timestampCacheBootedEmpty())) return;
        String dimension = DIMENSION_STRINGS.computeIfAbsent(level.dimension(),
                key -> key.identifier().toString());
        var obs = service.getDirtyContentFilter().observeSave(level, levelChunk, dimension);
        if (obs.changed()) {
            service.getDirtyTracker().markDirty(dimension, chunk.getPos().x, chunk.getPos().z);
            // Save-hook store bridge, DELETE-only (4-agent round R2-M2): the write-
            // through deposit this branch used to make could never survive — the same
            // mark it sets is drained by the broadcaster into the unconditional
            // dirty->store fan-out, whose tombstone strictly postdates the deposit's
            // enqueue, so every hook deposit died within one broadcast interval while
            // costing a compress+insert+delete and real shed pressure on the bounded
            // queue. The PROMPT delete is what carries the value: it closes the
            // up-to-10 s window in which the PRE-edit store row would keep serving hits
            // before the fan-out drain lands. Fresh bytes re-enter the store through
            // the dirty-broadcast re-serve's delivery-path deposit (which is what
            // actually re-warmed edited columns all along). Runs OUTSIDE the filter
            // monitor; tombstone put + control-queue add, safe off-main.
            applySaveObservationToStore(service.getLodStore(), dimension,
                    chunk.getPos().x, chunk.getPos().z, obs);
        }
    }

    /**
     * The chunk-LOAD seam body (xaero-scatter-remediation-plan.md WI-1b Option L; each
     * loader's chunk-load event — Fabric {@code ServerChunkEvents.CHUNK_LOAD}, NeoForge
     * {@code ChunkEvent.Load} — both fired from the FULL status task on the main thread,
     * with the light engine already holding the chunk's data). Seeds the dirty content
     * filter's baseline so the chunk's first metadata-only save no longer reads as a
     * content change (the first-observed-save storm). Same gates as the save hook: FULL
     * chunks only, service live + enabled, and the skip predicate below — a seed nobody
     * will ever be broadcast to is wasted serialization. Under a chunk system that never
     * fires the event the filter simply behaves as before (diag {@code seeded_load=}
     * reads 0 on a lively server — that is the live instrument).
     */
    public static void onChunkLoaded(ServerLevel level, ChunkAccess chunk,
                                     RequestProcessingService service) {
        if (!(chunk instanceof LevelChunk levelChunk)) return;
        if (!LSSServerConfig.CONFIG.enabled) return;
        if (service == null || skipDirtyHash(service.hasEverRegisteredPlayer(),
                service.getLodStore() != null, service.timestampCacheBootedEmpty())) {
            // Nobody can seed yet: the persistent spawn set loads in prepareLevels BEFORE
            // SERVER_STARTED creates the service, and a server with no LSS client yet keeps
            // the skip gate shut — both would leave a storm FLOOR (review M1: ~25 spawn
            // chunks by default, unbounded under /forceload). Record the POSITION (no
            // serialization) and seed it when the flush site opens.
            recordPendingLoadSeed(level.dimension(), chunk.getPos().x, chunk.getPos().z);
            return;
        }
        String dimension = DIMENSION_STRINGS.computeIfAbsent(level.dimension(),
                key -> key.identifier().toString());
        service.getDirtyContentFilter().seedLoaded(level, levelChunk, dimension);
    }

    /** Positions loaded while no one could seed them (see {@link #onChunkLoaded}); per
     *  dimension, bounded, main thread only (the load events and both flush sites run
     *  there). Cleared with the server's other sidecar facts. */
    private static final java.util.Map<ResourceKey<Level>, it.unimi.dsi.fastutil.longs.LongOpenHashSet>
            PENDING_LOAD_SEEDS = new java.util.HashMap<>();
    /** Past this many recorded positions the excess stays unseeded (one spurious mark
     *  each at its first save — today's behavior) rather than growing without bound on
     *  a server that never sees an LSS client. */
    static final int MAX_PENDING_LOAD_SEEDS = 8192;
    private static int pendingLoadSeedCount;

    static void recordPendingLoadSeed(ResourceKey<Level> dimension, int cx, int cz) {
        if (pendingLoadSeedCount >= MAX_PENDING_LOAD_SEEDS) return;
        var set = PENDING_LOAD_SEEDS.computeIfAbsent(dimension,
                k -> new it.unimi.dsi.fastutil.longs.LongOpenHashSet());
        if (set.add(dev.vox.lss.common.PositionUtil.packPosition(cx, cz))) pendingLoadSeedCount++;
    }

    public static int pendingLoadSeedCount() {
        return pendingLoadSeedCount;
    }

    public static void clearPendingLoadSeeds() {
        PENDING_LOAD_SEEDS.clear();
        pendingLoadSeedCount = 0;
    }

    /**
     * Seed every recorded position that is STILL loaded (resolved through the public
     * {@code getChunkNow} — chunk-system-proof, no holder enumeration) and forget the
     * rest. Called where seeding becomes possible: right after the loaders construct the
     * service (SERVER_STARTED / the LAN publish) and when the first registration opens
     * the skip gate. Main thread. While the gate is still shut nothing is flushed — the
     * set keeps recording. @return how many chunks were seeded.
     */
    public static int flushPendingLoadSeeds(net.minecraft.server.MinecraftServer server,
                                            RequestProcessingService service) {
        if (service == null || pendingLoadSeedCount == 0 || !LSSServerConfig.CONFIG.enabled) return 0;
        if (skipDirtyHash(service.hasEverRegisteredPlayer(), service.getLodStore() != null,
                service.timestampCacheBootedEmpty())) return 0;
        int seeded = 0;
        var filter = service.getDirtyContentFilter();
        for (var e : PENDING_LOAD_SEEDS.entrySet()) {
            ServerLevel level = server.getLevel(e.getKey());
            if (level == null) continue;
            String dimension = DIMENSION_STRINGS.computeIfAbsent(e.getKey(),
                    key -> key.identifier().toString());
            var it = e.getValue().iterator();
            while (it.hasNext()) {
                long packed = it.nextLong();
                LevelChunk chunk = level.getChunkSource().getChunkNow(
                        dev.vox.lss.common.PositionUtil.unpackX(packed),
                        dev.vox.lss.common.PositionUtil.unpackZ(packed));
                if (chunk == null) continue; // unloaded since: its reload records again
                filter.seedLoaded(level, chunk, dimension);
                seeded++;
            }
        }
        clearPendingLoadSeeds();
        if (seeded > 0) {
            LSSLogger.info("Dirty content filter: seeded " + seeded + " already-loaded chunk(s)");
        }
        return seeded;
    }

    /**
     * The review-P3 skip-gate predicate, pure so the truth table is pinnable (three-lens
     * review, test-adequacy MAJOR). Skip the dirty-content serialize+hash only while ALL
     * three hold:
     * <ul>
     *   <li>no LSS client has EVER registered this session (one-way latch — session
     *       state like held columns outlives its player, so the hash must resume forever
     *       after the first join);</li>
     *   <li>the store is inert (with a store, a skipped online edit would leave a
     *       pre-edit store row serving hits all session — Fabric sweeps at boot only);</li>
     *   <li>the persisted timestamp cache BOOTED EMPTY (correctness MAJOR:
     *       {@code <world>/data/lss-timestamps.bin} survives restarts, so a server that
     *       served clients last session boots with stamps a pre-first-join edit must
     *       invalidate — else a warm rejoin draws up_to_date for pre-edit terrain).</li>
     * </ul>
     * Under the full conjunction nothing the hash maintains is observable: no cache
     * stamps on any boot, no client-held columns, no store rows, and dirty marks with no
     * audience. A server running LSS with no LSS-playing users otherwise paid a
     * serialize+hash per chunk save forever (~30-60 µs each; 10-40 ms per save-all).
     * Accepted cost once a client DOES join: skip-era positions have no stored hash, so
     * their first post-join save reads absent-hash → changed → one spurious dirty
     * mark+broadcast each (bounded by loaded chunks, drained per interval).
     */
    public static boolean skipDirtyHash(boolean everRegistered, boolean storePresent,
                                        boolean timestampCacheBootedEmpty) {
        return !everRegistered && !storePresent && timestampCacheBootedEmpty;
    }

    /** The save-hook -> store bridge, extracted for direct testing: a content-changing
     *  save DELETES the position's store row (see onChunkSaveData — the old write-
     *  through deposit was provably always dead on arrival; delete-only keeps the
     *  stale-row-closure without the doomed work). Covers the serializer fail-open
     *  case by construction: changed-but-undepositable also just deletes. */
    public static void applySaveObservationToStore(dev.vox.lss.common.store.LodStoreService store,
                                                   String dimension, int cx, int cz,
                                                   DirtyContentFilter.SaveObservation obs) {
        if (store == null || !obs.changed()) return;
        store.delete(dimension, dev.vox.lss.common.PositionUtil.packPosition(cx, cz));
    }

    /** Reply hook for {@link #handleHandshake}; each loader wires its payload send. */
    @FunctionalInterface
    public interface SessionConfigResponder {
        void send(SessionConfigS2CPayload reply);
    }

    /**
     * The handshake receiver body, extracted so gametests can drive crafted frames through
     * the real call-site policy — gate evaluation, reply field wiring, registration — against
     * an explicit service and a recording responder (a caps=0 frame must reply without
     * registering, a foreign-version frame must produce zero reply frames). Production
     * behavior is unchanged: each loader's registered receiver calls this with the live
     * service and a real network sender.
     */
    public static void handleHandshake(HandshakeC2SPayload payload, ServerPlayer player,
                                       RequestProcessingService service,
                                       SessionConfigResponder responder) {
        // XVER §7: consult Via for the client's REAL protocol (a legacy LSS handshake
        // carries no MC version). Captured once so the log line and the gate see the
        // same number; the ternary keeps a disabled guard from ever triggering
        // resolution. Deliberately consulted for v20 handshakes too (the gate discards
        // it there) — the answer is future diagnostics, and the probe is one cached
        // MethodHandle invoke per join (review m12, kept with rationale).
        var config = LSSServerConfig.CONFIG;
        int viaProtocol = config.enableViaMismatchGuard
                ? dev.vox.lss.common.compat.ViaProbe.playerProtocol(player.getUUID())
                : dev.vox.lss.common.compat.ViaProbe.NO_SIGNAL;
        handleHandshake(payload, player, service, responder,
                viaProtocol, SharedConstants.getProtocolVersion(),
                serviceGateFor(player, service));
    }

    /**
     * The production {@link PlayerServiceGate} for the shared receivers
     * (service-permission-gate-plan.md §2.2): the permission read goes through the
     * {@code LoaderServices.checkPermission} seam (Fabric's reflective
     * fabric-permissions-api bridge / NeoForge's native nodes — every failure shape
     * answers the passed default TRUE: fail-open, serve), the log latch and the
     * denied-handshake memo live on the service's {@link ServiceGateState}, and a
     * denied re-handshake of an already-REGISTERED player unregisters inline (this
     * runs on the server thread — the same thread registerPlayer runs on).
     * Extracted static so it is source-pinnable — a hard-coded {@code true} here
     * would make the whole feature inert on two loaders with every core test green.
     */
    static PlayerServiceGate serviceGateFor(ServerPlayer player,
                                            RequestProcessingService service) {
        return new PlayerServiceGate() {
            @Override
            public boolean hasPermission(String node) {
                return dev.vox.lss.platform.LoaderServices.get()
                        .checkPermission(player, node, true);
            }

            @Override
            public boolean claimDenialLog() {
                return service != null
                        && service.getServiceGateState().claimDenialLog(player.getUUID());
            }

            @Override
            public void onServiceDenied(int protocolVersion, int capabilities) {
                if (service == null) return; // unreachable: the conjunction requires servicePresent
                service.getServiceGateState().rememberDenied(player.getUUID(),
                        player.getName().getString(), protocolVersion, capabilities);
                // A live registration does not survive a permission denial (an ADMIN
                // fact, unlike the protocol facts the keeps-registration rungs cover):
                // the enabled=false reply just sent is the client's disarm, and the
                // composite here is the same trio the departed-player sweep runs.
                service.unregisterForServiceGate(player.getUUID());
            }
        };
    }

    /** The Via-signal seam (review MAJOR-2, mirroring the Paper overload pair): the
     *  probe read happens in the caller above, so a gametest can force a mismatch
     *  through the PRODUCTION ladder — without this seam no test JVM can produce a
     *  VIA_MISMATCH at all (no real Via in any tier). */
    public static void handleHandshake(HandshakeC2SPayload payload, ServerPlayer player,
                                       RequestProcessingService service,
                                       SessionConfigResponder responder,
                                       int viaProtocol, int nativeProtocol) {
        handleHandshake(payload, player, service, responder, viaProtocol, nativeProtocol,
                PlayerServiceGate.OPEN);
    }

    /** The full core: Via seam + the per-player service gate seam
     *  (service-permission-gate-plan.md §2.2 — mirroring the Paper core's widest
     *  overload). The 6-arg overload above rides {@link PlayerServiceGate#OPEN},
     *  i.e. pre-gate behavior, for the existing crafted-frame tests; production
     *  receivers reach this through the 4-arg entry, which builds the real gate. */
    public static void handleHandshake(HandshakeC2SPayload payload, ServerPlayer player,
                                       RequestProcessingService service,
                                       SessionConfigResponder responder,
                                       int viaProtocol, int nativeProtocol,
                                       PlayerServiceGate serviceGate) {
        LSSLogger.info(Brand.shortName() + " handshake received from " + player.getName().getString()
                + " (protocol v" + payload.protocolVersion()
                + ", capabilities=" + payload.capabilities() + ")");

        var config = LSSServerConfig.CONFIG;
        // Per-player service gate (plan §2.2): rides the SAME input the server-wide
        // kill switch uses, so a denied player takes the already-pinned DISABLED path
        // verbatim — a SessionConfig advertising enabled=false in the client's OWN
        // dialect, no registration, never silence (silence is the version-skew signal
        // and sends the discovery ladder into its retry rungs). Short-circuit order is
        // load-bearing: at the shipped default the permission probe is never consulted.
        boolean serviceDenied = config.requireServicePermission
                && !PlayerServiceGate.holdsService(serviceGate);
        var decision = HandshakeGate.evaluate(payload.protocolVersion(),
                payload.capabilities(), config.enabled && !serviceDenied, service != null,
                config.enableV16Compat, config.enableV18Compat, config.enableV19Compat,
                dev.vox.lss.common.compat.ViaProbe.isMismatch(viaProtocol, nativeProtocol));

        if (decision.outcome() == HandshakeGate.Outcome.VIA_MISMATCH) {
            // Silent deny; "Minecraft protocol" because the handshake INFO one line up
            // prints an LSS protocol number and the two spaces must not be conflated
            // (review m5). Like VERSION_MISMATCH's early return below, an EXISTING
            // registration deliberately survives (review m1): the reachable window is
            // a no-signal FIRST handshake (Via mid-init) that registered legacy, then
            // a later re-handshake denying — bounded to that race, healed by rejoin;
            // shedding here would add remove-path surface for a corner Via itself
            // closes seconds later.
            LSSLogger.info("LOD unavailable for " + player.getName().getString()
                    + ": Via reports client Minecraft protocol " + viaProtocol
                    + " vs server " + nativeProtocol + " (cross-MC legacy session"
                    + " cannot be served) — the client must update "
                    + Brand.shortName());
            return;
        }
        if (!decision.sendSessionConfig()) {
            // See HandshakeGate.Outcome.VERSION_MISMATCH: replying would kick the player.
            // An EXISTING registration deliberately survives this rung (and NO_CONSUMER
            // below): only a hostile/buggy client re-handshakes cross-capability on a
            // live connection, and a stray duplicate frame must not kill a working
            // stream. The NO_CONSUMER keeps-registration shape is pinned by
            // ServiceLifecycleGameTests; the mismatch-survives shape follows from the
            // same early return but its gametest starts unregistered, so it is argued,
            // not pinned. Accepted residual: such a client keeps receiving columns it
            // just disclaimed, bounded to its own consenting connection.
            LSSLogger.warn("Player " + player.getName().getString()
                    + " has incompatible " + Brand.shortName() + " protocol version " + payload.protocolVersion()
                    + " (server: " + LSSConstants.PROTOCOL_VERSION + "), skipping LOD distribution");
            return;
        }

        // Anchored on the DECISION, not merely on serviceDenied (the Paper core's
        // twin carries the full rationale): outcome DISABLED — not NO_CONSUMER, whose
        // rung outranks the enabled check, so logging there would double up AND burn
        // the session's one line on a handshake the gate never decided; config.enabled
        // + servicePresent — with LSS dark regardless, naming a permission would send
        // the admin hunting a grant that changes nothing.
        boolean deniedByServiceGate = serviceDenied && config.enabled && service != null
                && decision.outcome() == HandshakeGate.Outcome.DISABLED;
        if (deniedByServiceGate && serviceGate.claimDenialLog()) {
            LSSLogger.info("LOD unavailable for " + player.getName().getString()
                    + ": requireServicePermission is on and this player does not hold both "
                    + dev.vox.lss.common.LSSPermissions.SERVICE_LSS + " and "
                    + dev.vox.lss.common.LSSPermissions.SERVICE_VSS
                    + " (a negative grant on either spelling denies) — the client was told"
                    + " " + Brand.shortName() + " is disabled and will stop asking. Restore"
                    + " both nodes to serve this player.");
        }

        boolean v16 = decision.dialect() == HandshakeGate.WireDialect.V16;
        boolean v18 = decision.dialect() == HandshakeGate.WireDialect.V18;
        boolean v19 = decision.dialect() == HandshakeGate.WireDialect.V19;
        if (service != null) {
            if (!v16) {
                // A cross-dialect re-handshake must shed the stale v16 ingress-shim
                // session (the dialect TRACKER shed is automatic on REGISTER — the
                // onHandshake overwrite — but the manager's synthetic want-set session
                // is separate state).
                service.getV16CompatManager().onNonV16Handshake(player.getUUID());
            }
            if (!decision.registerPlayer()) {
                // Non-register outcomes still shed a stale CROSS-dialect membership.
                service.getDialectTracker().onNonRegisterHandshake(
                        player.getUUID(), decision.dialect());
            }
        }
        responder.send(v16
                ? SessionConfigS2CPayload.v16Legacy(
                        decision.effectiveEnabled(),
                        config.lodDistanceChunks,
                        // The caps ARE the old client's pacing — advertise the server's real
                        // admission values (see the v16 compat design §4.1).
                        LSSConstants.SYNC_ON_LOAD_SLOT_CAP,
                        config.generationConcurrencyLimitPerPlayer,
                        config.enableChunkGeneration)
                : new SessionConfigS2CPayload(
                        // v18/v19 compat: the CURRENT 4-field layout, echoing the legacy
                        // client's own version — its gate hard-requires it (v18-compat
                        // §2.4; the v19 rung is the same echo trick).
                        v18 ? LSSConstants.V18_COMPAT_PROTOCOL_VERSION
                            : v19 ? LSSConstants.V19_COMPAT_PROTOCOL_VERSION
                                  : LSSConstants.PROTOCOL_VERSION,
                        decision.effectiveEnabled(),
                        config.lodDistanceChunks,
                        config.enableChunkGeneration,
                        // v20-only append (the encoder omits it for the echo versions).
                        net.minecraft.SharedConstants.getCurrentVersion()
                                .dataVersion().version()));

        if (deniedByServiceGate) {
            // AFTER the reply: the enabled=false config is the client's disarm, and the
            // hook's unregister composite must follow the push (plan §2.3's order). The
            // memo deposit inside makes this session re-offerable on a later grant.
            serviceGate.onServiceDenied(payload.protocolVersion(), payload.capabilities());
        }

        if (decision.outcome() == HandshakeGate.Outcome.NO_CONSUMER) {
            // A re-handshake that no longer carries a consumer sheds any prior
            // far-player subscription too (review: same-session downgrade). Null
            // guard (pre-G review): HandshakeGate returns NO_CONSUMER on caps 0
            // regardless of servicePresent, so a v16-era caps-0 client hitting the
            // LAN-construction gap or the SERVER_STOPPING race lands here with no
            // service — the sibling branches carry the same guard.
            if (service != null) {
                service.getFarPlayerService().removeViewer(player.getUUID());
            }
            // Visible to admins via this log.
            LSSLogger.info("Player " + player.getName().getString()
                    + " has no LOD consumer (caps=" + payload.capabilities()
                    + "), skipping LOD registration");
            return;
        }

        if (decision.registerPlayer()) {
            if (v16) {
                // Session identity first, so drip batches merge from the first frame.
                service.getV16CompatManager().onHandshake(player.getUUID());
            }
            // Dialect mark first: registerPlayer derives wantsCompressedColumns from it
            // (v18-compat §2.4 — the main-thread mark-before-register contract; one map,
            // so this is also the cross-dialect shed for the tracker).
            service.getDialectTracker().onHandshake(player.getUUID(), decision.dialect());
            service.registerPlayer(player, payload.capabilities());
            // Service gate: a successful HANDSHAKE registration ends the denied episode
            // (memo gone, log latch re-armed). Deliberately here and not inside
            // registerPlayer — that is also the dimension-change reuse path (R3).
            service.getServiceGateState().onRegistered(player.getUUID());
            // Far players (E1): subscription identity lands at handshake, next to the
            // dialect mark — a CURRENT-dialect session only (legacy layouts predate the
            // bit, so a legacy handshake setting it is noise, not a subscription).
            if ((payload.capabilities() & LSSConstants.CAPABILITY_FAR_PLAYERS) != 0
                    && decision.dialect() == HandshakeGate.WireDialect.CURRENT) {
                service.getFarPlayerService().subscribeViewer(player.getUUID());
            } else {
                // Bit absent or a legacy dialect on a RE-handshake: shed any stale
                // subscription rather than keep streaming frames the decoder no
                // longer expects (review; twin of the Paper Register-drain else).
                service.getFarPlayerService().removeViewer(player.getUUID());
            }
            LSSLogger.info("Player " + player.getName().getString()
                    + " registered for " + Brand.shortName() + " LOD request processing (caps="
                    + payload.capabilities()
                    + (v16 ? ", v16-compat" : "") + (v18 ? ", v18-compat" : "")
                    + (v19 ? ", v19-compat" : "") + ")");
        }
    }

    private static volatile long lastPrefsWarnMillis;

    /**
     * Far players (E1): prefs ingress body. Receivers on both loaders run on the server
     * thread — the broadcast core's single-threaded contract holds without marshaling. A
     * malformed frame is contained to a warn (the sidecar-guard doctrine): prefs are
     * additive, and a bad frame must never cost the session.
     */
    public static void onFarPlayerPrefs(RequestProcessingService service, ServerPlayer player,
                                        byte[] body) {
        if (service == null) return;
        try {
            var prefs = dev.vox.lss.common.farplayers.FarPlayerWire.decodePrefs(body);
            service.getFarPlayerService().onPrefs(player.getUUID(), prefs);
        } catch (Exception e) {
            // Throttled (review m5b): a hostile sender must not turn a
            // contained decode failure into log spam.
            long now = System.currentTimeMillis();
            if (now - lastPrefsWarnMillis > 60_000) {
                lastPrefsWarnMillis = now;
                LSSLogger.warn("Malformed far-player prefs from "
                        + player.getName().getString() + " — ignored ("
                        + e + ")");
            }
        }
    }
}
