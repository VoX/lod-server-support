package me.drex.antixray.common.util;

import me.drex.antixray.common.util.controller.ChunkPacketBlockController;
import net.minecraft.world.level.Level;

/**
 * TEST STUB of the real AntiXray Util (same fully-qualified name; the real
 * {@code getBlockController(Level)} reads a per-level field — the stub returns whatever
 * the test staged). See the stub note on
 * {@code me.drex.antixray.common.util.controller.ChunkPacketBlockController}.
 */
public final class Util {

    /** Staged by AntiXrayCompatTest before each probe call; the level argument is ignored. */
    public static ChunkPacketBlockController stagedController;

    public static ChunkPacketBlockController getBlockController(Level level) {
        return stagedController;
    }
}
