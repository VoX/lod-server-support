package dev.vox.lss.common;

/**
 * The chat line a server sends a player it refuses distant terrain for lack of permission
 * (0.22.0 plan §1). An ordinary system message, so it reaches every client version: sent as a
 * translatable with the English text as its fallback. It names no permission node (a node
 * means nothing to a player, and the gate needs both spellings; the nodes are in the
 * operator's diagnostics) and no brand (the same words under both builds).
 *
 * <p>Sent once per player per server start, and again when a served player's permission is
 * revoked: {@link ServiceGateState#claimPermissionNotice}.
 */
public final class TerrainRefusalNotice {
    public static final String KEY = "lss.terrain.permission_refused";
    public static final String FALLBACK =
            "This server gives distant terrain only to some players, and you aren't one of them.";

    private TerrainRefusalNotice() {}
}
