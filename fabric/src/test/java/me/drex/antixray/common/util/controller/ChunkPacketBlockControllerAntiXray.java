package me.drex.antixray.common.util.controller;

import it.unimi.dsi.fastutil.objects.Object2BooleanOpenHashMap;
import net.minecraft.world.level.block.state.BlockState;

/**
 * TEST STUB — see {@link ChunkPacketBlockController}'s stub note. The two fields the LSS
 * engine probe reads reflectively, declared with the REAL mod's exact names and modifiers
 * (branch 26.1: {@code protected final int maxBlockHeight},
 * {@code private final Object2BooleanOpenHashMap<BlockState> obfuscateGlobal}).
 */
public abstract class ChunkPacketBlockControllerAntiXray implements ChunkPacketBlockController {

    protected final int maxBlockHeight;
    private final Object2BooleanOpenHashMap<BlockState> obfuscateGlobal;

    protected ChunkPacketBlockControllerAntiXray(Object2BooleanOpenHashMap<BlockState> obfuscateGlobal,
                                                 int maxBlockHeight) {
        this.obfuscateGlobal = obfuscateGlobal;
        this.maxBlockHeight = maxBlockHeight;
    }
}
