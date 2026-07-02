package dev.vox.lss.paper;

import dev.vox.lss.common.config.ServerConfigBase;

import java.nio.file.Path;
import java.util.List;

/**
 * Paper server config: the shared fields/defaults/clamps live in {@link ServerConfigBase};
 * this subclass adds only the Bukkit event list for dirty chunk detection.
 * Stored in plugins/LodServerSupport/lss-server-config.json.
 */
public class PaperConfig extends ServerConfigBase {

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

    @Override
    public void validate() {
        super.validate();
        if (updateEvents == null) updateEvents = List.of();
    }

    public static PaperConfig load(Path dataFolder) {
        return load(PaperConfig.class, FILE_NAME, dataFolder);
    }
}
