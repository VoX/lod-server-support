package me.drex.antixray.common.util.controller;

/**
 * TEST STUB of the real AntiXray class (same fully-qualified name, same shape — see the
 * source-read record in docs/planning/antixray-compat-design.md §3 Detection). The engine
 * probe in {@code AntiXrayCompat} resolves these names via {@code Class.forName}, so these
 * stubs let Tier 1 drive the REAL resolution path — method signatures, field declarations,
 * setAccessible — without the mod on the classpath. Keep declarations matching the real
 * mod's; a drift here silently weakens the probe tests.
 */
public interface ChunkPacketBlockController {
}
