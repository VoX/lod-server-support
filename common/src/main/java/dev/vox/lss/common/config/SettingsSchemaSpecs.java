package dev.vox.lss.common.config;

import java.util.*;
import static dev.vox.lss.common.config.SettingsSchema.*;

/** Complete persisted-field mapping; retired JSON names are deliberately absent. */
final class SettingsSchemaSpecs {
    static List<Spec> specs(boolean isClient) {
        var server = new ArrayList<Spec>(); var client = new ArrayList<Spec>();
        server.add(new Spec("service.enabled", "enabled", Kind.BOOLEAN, Timing.R, Platform.ALL, false));
        server.add(new Spec("service.require_permission", "requireServicePermission", Kind.BOOLEAN, Timing.H, Platform.ALL, false));
        server.add(new Spec("lod.distance.default_chunks", "lodDistanceChunks", Kind.INTEGER, Timing.H, Platform.ALL, false));
        server.add(new Spec("lod.distance.by_dimension", "lodDistanceChunksByWorld", Kind.INTEGER_MAP, Timing.H, Platform.ALL, false));
        server.add(new Spec("lod.distance.by_world", "lodDistanceChunksByWorld", Kind.INTEGER_MAP, Timing.H, Platform.PAPER, false));
        server.add(new Spec("generation.enabled", "enableChunkGeneration", Kind.BOOLEAN, Timing.H, Platform.ALL, false));
        server.add(new Spec("generation.concurrency.global", "generationConcurrencyLimitGlobal", Kind.INTEGER, Timing.H, Platform.ALL, false));
        server.add(new Spec("generation.concurrency.per_player", "generationConcurrencyLimitPerPlayer", Kind.INTEGER, Timing.H, Platform.ALL, false));
        server.add(new Spec("generation.timeout_ticks", "generationTimeoutSeconds", Kind.INTEGER, Timing.H, Platform.ALL, false));
        server.add(new Spec("network.bandwidth.per_player_mib_per_second", "mbPerSecondLimitPerPlayer", Kind.NUMBER, Timing.H, Platform.ALL, false));
        server.add(new Spec("network.bandwidth.global_mib_per_second", "mbPerSecondLimitGlobal", Kind.NUMBER, Timing.H, Platform.ALL, false));
        server.add(new Spec("network.send_queue_limit_per_player", "sendQueueLimitPerPlayer", Kind.INTEGER, Timing.H, Platform.ALL, false));
        server.add(new Spec("network.ping_backstop", "enablePingBackstop", Kind.BOOLEAN, Timing.H, Platform.ALL, false));
        server.add(new Spec("network.send_pacing", "enableSendPacing", Kind.BOOLEAN, Timing.H, Platform.ALL, false));
        server.add(new Spec("network.yield_to_vanilla", "lodYieldsToVanillaTransport", Kind.BOOLEAN, Timing.H, Platform.ALL, false));
        server.add(new Spec("updates.dirty_broadcast_interval_ticks", "dirtyBroadcastIntervalSeconds", Kind.INTEGER, Timing.H, Platform.ALL, false));
        server.add(new Spec("updates.region_summaries", "enableRegionSummaries", Kind.BOOLEAN, Timing.H, Platform.ALL, false));
        server.add(new Spec("storage.disk.reader_threads", "diskReaderThreads", Kind.INTEGER, Timing.R, Platform.ALL, false));
        server.add(new Spec("storage.disk.max_concurrent_reads", "maxConcurrentDiskReads", Kind.INTEGER, Timing.H, Platform.ALL, false));
        server.add(new Spec("storage.disk.background_priority", "useBackgroundReadPriority", Kind.BOOLEAN, Timing.R, Platform.ALL, false));
        server.add(new Spec("storage.disk.split_background_reads", "useBackgroundReadSplit", Kind.BOOLEAN, Timing.R, Platform.ALL, false));
        server.add(new Spec("storage.timestamp_cache_mib_per_dimension", "perDimensionTimestampCacheSizeMB", Kind.INTEGER, Timing.H, Platform.ALL, false));
        server.add(new Spec("storage.miss_memo_ttl_seconds", "missMemoTtlSeconds", Kind.INTEGER, Timing.H, Platform.ALL, false));
        server.add(new Spec("storage.lod_store.enabled", "lodStore", Kind.BOOLEAN, Timing.R, Platform.ALL, false));
        server.add(new Spec("storage.lod_store.max_size_mib", "lodStoreMaxMB", Kind.INTEGER, Timing.H, Platform.ALL, false));
        server.add(new Spec("storage.lod_store.resweep_interval_seconds", "lodStoreResweepSeconds", Kind.INTEGER, Timing.H, Platform.ALL, false));
        server.add(new Spec("storage.lod_store.backfill.enabled", "lodStoreBackfill", Kind.BOOLEAN, Timing.H, Platform.MOD, false));
        server.add(new Spec("storage.lod_store.backfill.columns_per_second", "lodStoreBackfillColumnsPerSecond", Kind.INTEGER, Timing.H, Platform.MOD, false));
        server.add(new Spec("serialization.selective_nbt_parse", "useSelectiveNbtParse", Kind.BOOLEAN, Timing.H, Platform.MOD, false));
        server.add(new Spec("serialization.nbt_transcode", "useNbtTranscode", Kind.BOOLEAN, Timing.H, Platform.ALL, false));
        server.add(new Spec("serialization.compressed_columns", "useCompressedColumns", Kind.BOOLEAN, Timing.R, Platform.ALL, false));
        server.add(new Spec("compatibility.protocols.v16", "enableV16Compat", Kind.BOOLEAN, Timing.R, Platform.ALL, false));
        server.add(new Spec("compatibility.protocols.v18", "enableV18Compat", Kind.BOOLEAN, Timing.R, Platform.ALL, false));
        server.add(new Spec("compatibility.protocols.v19", "enableV19Compat", Kind.BOOLEAN, Timing.R, Platform.ALL, false));
        server.add(new Spec("compatibility.via_mismatch_guard", "enableViaMismatchGuard", Kind.BOOLEAN, Timing.R, Platform.ALL, false));
        server.add(new Spec("privacy.xray.mode", "xrayObfuscation", Kind.STRING, Timing.R, Platform.ALL, false));
        server.add(new Spec("privacy.xray.max_y_blocks", "xrayMaxBlockHeight", Kind.INTEGER, Timing.R, Platform.ALL, false));
        server.add(new Spec("privacy.xray.hidden_blocks", "xrayHiddenBlocks", Kind.STRING_LIST, Timing.R, Platform.ALL, false));
        // Map policy (map-policy plan A3.1): hot via reload. The legacy JSON name never
        // existed (the key is YAML-born), so a JSON migration reads it absent = false.
        server.add(new Spec("privacy.request_no_map_ingestion", "requestNoMapIngestion", Kind.BOOLEAN, Timing.H, Platform.ALL, false));
        server.add(new Spec("far_players.mode", "farPlayers", Kind.STRING, Timing.H, Platform.ALL, false));
        server.add(new Spec("far_players.update_interval_ticks", "farPlayersUpdateIntervalTicks", Kind.INTEGER, Timing.H, Platform.ALL, false));
        server.add(new Spec("far_players.distance.min_blocks", "farPlayersMinDistanceBlocks", Kind.INTEGER, Timing.H, Platform.ALL, false));
        server.add(new Spec("far_players.distance.max_blocks", "farPlayersMaxDistanceBlocks", Kind.INTEGER, Timing.H, Platform.ALL, false));
        server.add(new Spec("far_players.send_spectators", "farPlayersSendSpectators", Kind.BOOLEAN, Timing.H, Platform.ALL, false));
        server.add(new Spec("far_players.excluded_players", "farPlayersExclude", Kind.STRING_LIST, Timing.H, Platform.ALL, true));
        // Hide-permission plan §2 (0.21.0): the hide permission hides a holder only while this
        // is on. Hot via reload; the legacy JSON name never existed, so a JSON migration reads
        // it absent = false.
        server.add(new Spec("far_players.hide_permission_holders", "farPlayersHidePermissionHolders", Kind.BOOLEAN, Timing.H, Platform.ALL, false));
        server.add(new Spec("paper.update_events", "updateEvents", Kind.STRING_LIST, Timing.R, Platform.PAPER, false));
        client.add(new Spec("lod.source", "dhTerrainSource", Kind.STRING, Timing.S, Platform.ALL, false));
        client.add(new Spec("lod.dh.sync_on_load", "dhSynchronizeOnLoad", Kind.BOOLEAN, Timing.H, Platform.ALL, false));
        client.add(new Spec("lod.dh.real_time_updates", "dhRealTimeUpdates", Kind.BOOLEAN, Timing.H, Platform.ALL, false));
        client.add(new Spec("lod.voxy_border_fill", "voxyBorderFill", Kind.BOOLEAN, Timing.H, Platform.ALL, false));
        client.add(new Spec("lod.receive", "receiveServerLods", Kind.BOOLEAN, Timing.H, Platform.ALL, false));
        client.add(new Spec("lod.distance_chunks", "lodDistanceChunks", Kind.INTEGER, Timing.H, Platform.ALL, false));
        client.add(new Spec("lod.download.max_columns_per_second", "lodColumnsPerSecondLimit", Kind.INTEGER, Timing.H, Platform.ALL, false));
        client.add(new Spec("lod.download.adaptive_rate", "enableAdaptiveTransferRate", Kind.BOOLEAN, Timing.H, Platform.ALL, false));
        client.add(new Spec("lod.download.slow_start_on_join", "enableJoinSlowStart", Kind.BOOLEAN, Timing.H, Platform.ALL, false));
        client.add(new Spec("lod.download.ingest_backpressure", "enableIngestBackpressure", Kind.BOOLEAN, Timing.H, Platform.ALL, false));
        client.add(new Spec("scan.region_order", "enableRegionScan", Kind.BOOLEAN, Timing.S, Platform.ALL, false));
        client.add(new Spec("scan.adaptive_cadence", "enableAdaptiveScanCadence", Kind.BOOLEAN, Timing.S, Platform.ALL, false));
        client.add(new Spec("scan.retain_completed_prefix", "enableScanPrefixRetention", Kind.BOOLEAN, Timing.S, Platform.ALL, false));
        client.add(new Spec("scan.quadtree", "enableQuadtreeScan", Kind.BOOLEAN, Timing.S, Platform.ALL, false));
        client.add(new Spec("scan.region_summaries", "enableRegionSummarySync", Kind.BOOLEAN, Timing.S, Platform.ALL, false));
        client.add(new Spec("cache.split_by_world", "useWorldSubBuckets", Kind.BOOLEAN, Timing.S, Platform.ALL, false));
        client.add(new Spec("cache.address_aliases", "cacheAddressAliases", Kind.STRING_GROUPS, Timing.S, Platform.ALL, true));
        client.add(new Spec("compatibility.protocols.v16", "enableV16ServerCompat", Kind.BOOLEAN, Timing.S, Platform.ALL, false));
        client.add(new Spec("compatibility.protocols.v19", "enableV19ServerCompat", Kind.BOOLEAN, Timing.S, Platform.ALL, false));
        client.add(new Spec("compatibility.v16_generation", "enableV16Generation", Kind.BOOLEAN, Timing.S, Platform.ALL, false));
        client.add(new Spec("compatibility.block_fallbacks.default", "unknownBlockFallback", Kind.STRING, Timing.S, Platform.ALL, false));
        client.add(new Spec("compatibility.block_fallbacks.overrides", "crossVersionBlockFallbacks", Kind.STRING_MAP, Timing.S, Platform.ALL, true));
        // Map ingestion (map-ingestion plan §2.1): the legacy JSON names stay what the old
        // Xaero-only keys were; integrations.xaero_map.* is a read alias (SettingsSchema).
        client.add(new Spec("integrations.map_ingestion.enabled", "enableXaeroMapBridge", Kind.BOOLEAN, Timing.S, Platform.ALL, false));
        client.add(new Spec("integrations.map_ingestion.backpressure", "enableXaeroMapBackpressure", Kind.BOOLEAN, Timing.H, Platform.ALL, false));
        // Xaero cave-layer fill store (cave-layers plan §B10.3): lives in the experimental
        // sidecar so the main file stays readable by 0.18.0 and older.
        client.add(new Spec("integrations.map_ingestion.nether_store_mib", "xaeroNetherStoreMB", Kind.INTEGER, Timing.H, Platform.ALL, false));
        client.add(new Spec("far_players.enabled", "farPlayersEnabled", Kind.BOOLEAN, Timing.H, Platform.ALL, false));
        client.add(new Spec("far_players.distance.min_blocks", "farPlayersMinDistanceBlocks", Kind.INTEGER, Timing.H, Platform.ALL, false));
        client.add(new Spec("far_players.distance.max_blocks", "farPlayersMaxDistanceBlocks", Kind.INTEGER, Timing.H, Platform.ALL, false));
        client.add(new Spec("far_players.name_tags", "farPlayersNameTags", Kind.BOOLEAN, Timing.H, Platform.ALL, false));
        client.add(new Spec("far_players.full_bright", "farPlayersFullBright", Kind.BOOLEAN, Timing.H, Platform.ALL, false));
        client.add(new Spec("far_players.render_distance_blocks", "farPlayersMaxRenderDistanceBlocks", Kind.INTEGER, Timing.H, Platform.ALL, false));
        // Far-plane extension (far-plane plan #364): experimental sidecar, so the main file
        // stays readable by 0.19.1 and older.
        client.add(new Spec("far_players.extend_far_plane", "farPlayersExtendFarPlane", Kind.BOOLEAN, Timing.H, Platform.ALL, false));
        client.add(new Spec("far_players.animation_distance_blocks", "farPlayersMaxAnimationDistanceBlocks", Kind.INTEGER, Timing.H, Platform.ALL, false));
        client.add(new Spec("far_players.sharing.enabled", "farPlayersShareSelf", Kind.BOOLEAN, Timing.H, Platform.ALL, false));
        client.add(new Spec("far_players.sharing.max_distance_blocks", "farPlayersShareDistanceBlocks", Kind.INTEGER, Timing.H, Platform.ALL, false));
        return List.copyOf(isClient ? client : server);
    }
}
