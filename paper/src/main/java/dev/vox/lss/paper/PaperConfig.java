package dev.vox.lss.paper;

import dev.vox.lss.common.LSSLogger;
import dev.vox.lss.common.config.ServerConfigBase;

import java.nio.file.Path;
import java.util.List;

/**
 * Paper server config: the shared fields/defaults/clamps live in {@link ServerConfigBase};
 * this subclass adds only the Bukkit event list for dirty chunk detection.
 * Stored in plugins/LodServerSupport/lss-server-config.json.
 */
public class PaperConfig extends ServerConfigBase {

    {
        // PAPER default override: the periodic resweep is Paper's stale bound for its
        // documented unfired-event dirty gaps (console setblock, walk-in generation
        // without ChunkPopulateEvent, plugin edits) — with 0 the "≈ save + one sweep"
        // staleness guarantee simply doesn't run. 300 s ≈ the autosave cadence. Fabric
        // keeps the shared 0: its content-hash save hook invalidates at runtime, and a
        // periodic resweep there would only churn-drop rows on metadata re-saves.
        lodStoreResweepSeconds = 300;
        // The FOLIA override that used to live here is gone (2026-08-03): the shared default
        // is now "off" on every platform, so forcing it off again for Folia was redundant —
        // and the config save-back made it leaky anyway, since a Paper run persisted
        // lodStore=full into the file and carrying that folder to Folia armed the store with
        // no opt-in. With the shared default off, a carried-over file carries "off" too.
        // validate() still WARNS when an explicit "full" arms the store on Folia, which is
        // the case that actually deserves an operator's attention.
    }

    // Bukkit events that mark a chunk dirty for LOD re-send. Broadened to better match the
    // Fabric chunk-save hook's coverage (decay, growth, ice/snow, fire, falling blocks).
    // High-frequency fluid flow (BlockFromToEvent) is intentionally NOT a default — admins who
    // want fluid-flow LOD accuracy can add it. Unrecognized events are skipped gracefully.
    public List<String> updateEvents = List.of(
            "org.bukkit.event.block.BlockPlaceEvent",
            "org.bukkit.event.block.BlockBreakEvent",
            "org.bukkit.event.block.BlockExplodeEvent",
            // Entity-caused explosions (creeper/TNT/wither) — BlockExplodeEvent only covers
            // block-caused ones (beds, respawn anchors), so without this the most common
            // explosions never mark chunks dirty on Paper. Its blockList() feeds the same
            // extractor. Fabric catches these via the chunk-save hook.
            "org.bukkit.event.entity.EntityExplodeEvent",
            "org.bukkit.event.block.BlockBurnEvent",
            "org.bukkit.event.block.BlockFadeEvent",
            "org.bukkit.event.block.BlockFormEvent",
            "org.bukkit.event.block.BlockGrowEvent",
            "org.bukkit.event.block.BlockSpreadEvent",
            "org.bukkit.event.block.LeavesDecayEvent",
            "org.bukkit.event.block.BlockPistonExtendEvent",
            "org.bukkit.event.block.BlockPistonRetractEvent",
            "org.bukkit.event.entity.EntityChangeBlockEvent",
            "org.bukkit.event.world.StructureGrowEvent"
            // ChunkPopulateEvent is deliberately NOT a default: it fires for every chunk LSS
            // itself generates, and the resulting dirty mark re-sends the identical column on
            // the next broadcast (every generated column delivered twice — the same waste
            // Fabric's DirtyContentFilter generation-seed eliminates). Clients that need a
            // freshly populated chunk request it through the normal scan path anyway.
    );

    // R-5 log-on-change (v0.11.0 stage C): validate() re-runs on every /lsslod set, so
    // the Folia store warn must fire on TRANSITION, not per call. Transient — never
    // serialized, per-process state only.
    private transient String lastAdvisedFoliaStoreMode;
    private transient boolean lastAdvisedBackfillInert;

    @Override
    public void validate() {
        super.validate();
        if (updateEvents == null) updateEvents = List.of();
        // Under the 2026-08-08 split default an armed store on Folia is either a fresh
        // install's generated "on" or an explicit choice — either way it stays a warning
        // rather than a gate (Folia support is experimental wholesale; the store adds
        // one more unvalidated surface, not the first), but it must not be silent.
        if (FoliaSupport.IS_FOLIA
                && dev.vox.lss.common.store.LodStoreMode.normalize(lodStore)
                        != dev.vox.lss.common.store.LodStoreMode.OFF
                && !lodStore.equals(this.lastAdvisedFoliaStoreMode)) {
            LSSLogger.warn("The LOD store is armed on FOLIA (lodStore=" + lodStore
                    + "), where it is NOT validated (single-player soaks only;"
                    + " concurrent multi-region ingress is untested). Set"
                    + " lodStore=off to disable it if you see store-related issues.");
        }
        this.lastAdvisedFoliaStoreMode = lodStore;
        // R15 (Folia review 2026-08-27): the backfill keys are written into this
        // platform's config file but are Fabric-only — an admin tuning them here gets
        // no effect and, without this line, no explanation. INFO once per transition
        // (same log-on-change discipline as the store warn above); fires only when the
        // store is armed (backfill is doubly inert store-off).
        boolean backfillAsk = lodStoreBackfill
                && dev.vox.lss.common.store.LodStoreMode.normalize(lodStore)
                        != dev.vox.lss.common.store.LodStoreMode.OFF;
        if (backfillAsk && !this.lastAdvisedBackfillInert) {
            LSSLogger.info("lodStoreBackfill is Fabric-only — on Paper/Folia the LOD"
                    + " store warms from serves (the backfill keys in this file are"
                    + " accepted but inert).");
        }
        this.lastAdvisedBackfillInert = backfillAsk;
    }

    public static PaperConfig load(Path dataFolder) {
        return load(PaperConfig.class, serverConfigCandidates(), dataFolder);
    }
}
