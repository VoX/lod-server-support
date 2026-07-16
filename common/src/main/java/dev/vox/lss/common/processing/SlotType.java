package dev.vox.lss.common.processing;

/**
 * Which per-player admission slot an in-flight request occupies. Every request is
 * disk-first and occupies a SYNC_ON_LOAD slot; it swaps to a GENERATION slot only when
 * a disk miss escalates it into an actual in-flight generation (server-owned generation
 * — the server decides on the miss; the client never classifies).
 */
public enum SlotType {
    SYNC_ON_LOAD,
    GENERATION
}
