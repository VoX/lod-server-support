package dev.vox.lss.common.processing;

/**
 * An in-flight request tracked by the processing thread. The {@code heldSlot} records
 * which admission slot this entry occupies; slot occupancy is derived by counting
 * pending entries, so adding/removing a pending entry IS the acquire/release.
 *
 * <p>{@code claimsData} is true when the requesting client sent {@code clientTimestamp > 0}
 * (a resync — it already holds a column for this position). It is read at delivery to decide
 * whether an all-air resolution should send a clearing 0-section column (client claims data,
 * so clear its ghost terrain) or a cheap {@code up_to_date} (client has nothing to clear).
 */
public record PendingRequest(int cx, int cz, SlotType heldSlot, boolean claimsData) {}
