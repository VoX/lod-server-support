package dev.vox.lss.common.config;

import java.util.*;

/** Immutable settings; mutable document nodes never enter runtime snapshots. */
public record ServerSettings(Service service, Lod lod, Generation generation, Network network, Updates updates, Storage storage, Serialization serialization, Compatibility compatibility, Privacy privacy, FarPlayers farPlayers, Paper paper) {
    public record Service(boolean enabled, boolean requirePermission, boolean showPermissionRefusal) {}
    public record LodDistance(int defaultChunks, Map<String, Integer> byDimension, Map<String, Integer> byWorld) {
        public LodDistance { byDimension = Collections.unmodifiableMap(new LinkedHashMap<>(byDimension)); byWorld = Collections.unmodifiableMap(new LinkedHashMap<>(byWorld)); }
    }
    public record Lod(LodDistance distance) {}
    public record GenerationConcurrency(int global, int perPlayer) {}
    public record Generation(boolean enabled, GenerationConcurrency concurrency, int timeoutTicks) {}
    public record NetworkBandwidth(double perPlayerMibPerSecond, double globalMibPerSecond) {}
    public record Network(NetworkBandwidth bandwidth, int sendQueueLimitPerPlayer, boolean pingBackstop, boolean sendPacing, boolean yieldToVanilla) {}
    public record Updates(int dirtyBroadcastIntervalTicks, boolean regionSummaries) {}
    public record StorageDisk(int readerThreads, int maxConcurrentReads, boolean backgroundPriority, boolean splitBackgroundReads) {}
    public record StorageLodStoreBackfill(boolean enabled, int columnsPerSecond) {}
    public record StorageLodStore(boolean enabled, int maxSizeMib, int resweepIntervalSeconds, StorageLodStoreBackfill backfill) {}
    public record Storage(StorageDisk disk, int timestampCacheMibPerDimension, int missMemoTtlSeconds, StorageLodStore lodStore) {}
    public record Serialization(boolean selectiveNbtParse, boolean nbtTranscode, boolean compressedColumns) {}
    public record CompatibilityProtocols(boolean v16, boolean v18, boolean v19) {}
    public record Compatibility(CompatibilityProtocols protocols, boolean viaMismatchGuard) {}
    public record PrivacyXray(String mode, int maxYBlocks, List<String> hiddenBlocks) {
        public PrivacyXray { hiddenBlocks = List.copyOf(hiddenBlocks); }
    }
    public record Privacy(PrivacyXray xray, boolean requestNoMapIngestion) {}
    public record FarPlayersDistance(int minBlocks, int maxBlocks) {}
    public record FarPlayers(String mode, int updateIntervalTicks, FarPlayersDistance distance, boolean sendSpectators, List<String> excludedPlayers, boolean hidePermissionHolders) {
        public FarPlayers { excludedPlayers = List.copyOf(excludedPlayers); }
    }
    public record Paper(List<String> updateEvents) {
        public Paper { updateEvents = List.copyOf(updateEvents); }
    }
    public static ServerSettings fromValues(Map<String,Object> values) { return ServerSettings(values); }
    public Map<String,Object> values() {
        var v = new LinkedHashMap<String,Object>();
        var s = this;
        v.put("service.enabled", s.service().enabled());
        v.put("service.require_permission", s.service().requirePermission());
        v.put("service.show_permission_refusal", s.service().showPermissionRefusal());
        v.put("lod.distance.default_chunks", s.lod().distance().defaultChunks());
        v.put("lod.distance.by_dimension", s.lod().distance().byDimension());
        v.put("lod.distance.by_world", s.lod().distance().byWorld());
        v.put("generation.enabled", s.generation().enabled());
        v.put("generation.concurrency.global", s.generation().concurrency().global());
        v.put("generation.concurrency.per_player", s.generation().concurrency().perPlayer());
        v.put("generation.timeout_ticks", s.generation().timeoutTicks());
        v.put("network.bandwidth.per_player_mib_per_second", s.network().bandwidth().perPlayerMibPerSecond());
        v.put("network.bandwidth.global_mib_per_second", s.network().bandwidth().globalMibPerSecond());
        v.put("network.send_queue_limit_per_player", s.network().sendQueueLimitPerPlayer());
        v.put("network.ping_backstop", s.network().pingBackstop());
        v.put("network.send_pacing", s.network().sendPacing());
        v.put("network.yield_to_vanilla", s.network().yieldToVanilla());
        v.put("updates.dirty_broadcast_interval_ticks", s.updates().dirtyBroadcastIntervalTicks());
        v.put("updates.region_summaries", s.updates().regionSummaries());
        v.put("storage.disk.reader_threads", s.storage().disk().readerThreads());
        v.put("storage.disk.max_concurrent_reads", s.storage().disk().maxConcurrentReads());
        v.put("storage.disk.background_priority", s.storage().disk().backgroundPriority());
        v.put("storage.disk.split_background_reads", s.storage().disk().splitBackgroundReads());
        v.put("storage.timestamp_cache_mib_per_dimension", s.storage().timestampCacheMibPerDimension());
        v.put("storage.miss_memo_ttl_seconds", s.storage().missMemoTtlSeconds());
        v.put("storage.lod_store.enabled", s.storage().lodStore().enabled());
        v.put("storage.lod_store.max_size_mib", s.storage().lodStore().maxSizeMib());
        v.put("storage.lod_store.resweep_interval_seconds", s.storage().lodStore().resweepIntervalSeconds());
        v.put("storage.lod_store.backfill.enabled", s.storage().lodStore().backfill().enabled());
        v.put("storage.lod_store.backfill.columns_per_second", s.storage().lodStore().backfill().columnsPerSecond());
        v.put("serialization.selective_nbt_parse", s.serialization().selectiveNbtParse());
        v.put("serialization.nbt_transcode", s.serialization().nbtTranscode());
        v.put("serialization.compressed_columns", s.serialization().compressedColumns());
        v.put("compatibility.protocols.v16", s.compatibility().protocols().v16());
        v.put("compatibility.protocols.v18", s.compatibility().protocols().v18());
        v.put("compatibility.protocols.v19", s.compatibility().protocols().v19());
        v.put("compatibility.via_mismatch_guard", s.compatibility().viaMismatchGuard());
        v.put("privacy.xray.mode", s.privacy().xray().mode());
        v.put("privacy.xray.max_y_blocks", s.privacy().xray().maxYBlocks());
        v.put("privacy.xray.hidden_blocks", s.privacy().xray().hiddenBlocks());
        v.put("privacy.request_no_map_ingestion", s.privacy().requestNoMapIngestion());
        v.put("far_players.mode", s.farPlayers().mode());
        v.put("far_players.update_interval_ticks", s.farPlayers().updateIntervalTicks());
        v.put("far_players.distance.min_blocks", s.farPlayers().distance().minBlocks());
        v.put("far_players.distance.max_blocks", s.farPlayers().distance().maxBlocks());
        v.put("far_players.send_spectators", s.farPlayers().sendSpectators());
        v.put("far_players.excluded_players", s.farPlayers().excludedPlayers());
        v.put("far_players.hide_permission_holders", s.farPlayers().hidePermissionHolders());
        v.put("paper.update_events", s.paper().updateEvents());
        return Collections.unmodifiableMap(v);
    }
    @SuppressWarnings("unchecked")
    private static Service Service(Map<String,Object> v) {
        return new Service((boolean)v.get("service.enabled"), (boolean)v.get("service.require_permission"),
                (boolean)v.get("service.show_permission_refusal"));
    }
    @SuppressWarnings("unchecked")
    private static LodDistance LodDistance(Map<String,Object> v) {
        return new LodDistance((int)v.get("lod.distance.default_chunks"), (Map<String, Integer>)v.get("lod.distance.by_dimension"), (Map<String, Integer>)v.get("lod.distance.by_world"));
    }
    @SuppressWarnings("unchecked")
    private static Lod Lod(Map<String,Object> v) {
        return new Lod(LodDistance(v));
    }
    @SuppressWarnings("unchecked")
    private static GenerationConcurrency GenerationConcurrency(Map<String,Object> v) {
        return new GenerationConcurrency((int)v.get("generation.concurrency.global"), (int)v.get("generation.concurrency.per_player"));
    }
    @SuppressWarnings("unchecked")
    private static Generation Generation(Map<String,Object> v) {
        return new Generation((boolean)v.get("generation.enabled"), GenerationConcurrency(v), (int)v.get("generation.timeout_ticks"));
    }
    @SuppressWarnings("unchecked")
    private static NetworkBandwidth NetworkBandwidth(Map<String,Object> v) {
        return new NetworkBandwidth(((Number)v.get("network.bandwidth.per_player_mib_per_second")).doubleValue(), ((Number)v.get("network.bandwidth.global_mib_per_second")).doubleValue());
    }
    @SuppressWarnings("unchecked")
    private static Network Network(Map<String,Object> v) {
        return new Network(NetworkBandwidth(v), (int)v.get("network.send_queue_limit_per_player"), (boolean)v.get("network.ping_backstop"), (boolean)v.get("network.send_pacing"), (boolean)v.get("network.yield_to_vanilla"));
    }
    @SuppressWarnings("unchecked")
    private static Updates Updates(Map<String,Object> v) {
        return new Updates((int)v.get("updates.dirty_broadcast_interval_ticks"), (boolean)v.get("updates.region_summaries"));
    }
    @SuppressWarnings("unchecked")
    private static StorageDisk StorageDisk(Map<String,Object> v) {
        return new StorageDisk((int)v.get("storage.disk.reader_threads"), (int)v.get("storage.disk.max_concurrent_reads"), (boolean)v.get("storage.disk.background_priority"), (boolean)v.get("storage.disk.split_background_reads"));
    }
    @SuppressWarnings("unchecked")
    private static StorageLodStoreBackfill StorageLodStoreBackfill(Map<String,Object> v) {
        return new StorageLodStoreBackfill((boolean)v.get("storage.lod_store.backfill.enabled"), (int)v.get("storage.lod_store.backfill.columns_per_second"));
    }
    @SuppressWarnings("unchecked")
    private static StorageLodStore StorageLodStore(Map<String,Object> v) {
        return new StorageLodStore((boolean)v.get("storage.lod_store.enabled"), (int)v.get("storage.lod_store.max_size_mib"), (int)v.get("storage.lod_store.resweep_interval_seconds"), StorageLodStoreBackfill(v));
    }
    @SuppressWarnings("unchecked")
    private static Storage Storage(Map<String,Object> v) {
        return new Storage(StorageDisk(v), (int)v.get("storage.timestamp_cache_mib_per_dimension"), (int)v.get("storage.miss_memo_ttl_seconds"), StorageLodStore(v));
    }
    @SuppressWarnings("unchecked")
    private static Serialization Serialization(Map<String,Object> v) {
        return new Serialization((boolean)v.get("serialization.selective_nbt_parse"), (boolean)v.get("serialization.nbt_transcode"), (boolean)v.get("serialization.compressed_columns"));
    }
    @SuppressWarnings("unchecked")
    private static CompatibilityProtocols CompatibilityProtocols(Map<String,Object> v) {
        return new CompatibilityProtocols((boolean)v.get("compatibility.protocols.v16"), (boolean)v.get("compatibility.protocols.v18"), (boolean)v.get("compatibility.protocols.v19"));
    }
    @SuppressWarnings("unchecked")
    private static Compatibility Compatibility(Map<String,Object> v) {
        return new Compatibility(CompatibilityProtocols(v), (boolean)v.get("compatibility.via_mismatch_guard"));
    }
    @SuppressWarnings("unchecked")
    private static PrivacyXray PrivacyXray(Map<String,Object> v) {
        return new PrivacyXray((String)v.get("privacy.xray.mode"), (int)v.get("privacy.xray.max_y_blocks"), (List<String>)v.get("privacy.xray.hidden_blocks"));
    }
    @SuppressWarnings("unchecked")
    private static Privacy Privacy(Map<String,Object> v) {
        return new Privacy(PrivacyXray(v), (boolean)v.get("privacy.request_no_map_ingestion"));
    }
    @SuppressWarnings("unchecked")
    private static FarPlayersDistance FarPlayersDistance(Map<String,Object> v) {
        return new FarPlayersDistance((int)v.get("far_players.distance.min_blocks"), (int)v.get("far_players.distance.max_blocks"));
    }
    @SuppressWarnings("unchecked")
    private static FarPlayers FarPlayers(Map<String,Object> v) {
        return new FarPlayers((String)v.get("far_players.mode"), (int)v.get("far_players.update_interval_ticks"), FarPlayersDistance(v), (boolean)v.get("far_players.send_spectators"), (List<String>)v.get("far_players.excluded_players"), (boolean)v.get("far_players.hide_permission_holders"));
    }
    @SuppressWarnings("unchecked")
    private static Paper Paper(Map<String,Object> v) {
        return new Paper((List<String>)v.get("paper.update_events"));
    }
    @SuppressWarnings("unchecked")
    private static ServerSettings ServerSettings(Map<String,Object> v) {
        return new ServerSettings(Service(v), Lod(v), Generation(v), Network(v), Updates(v), Storage(v), Serialization(v), Compatibility(v), Privacy(v), FarPlayers(v), Paper(v));
    }
}
