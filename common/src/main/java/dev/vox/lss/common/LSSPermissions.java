package dev.vox.lss.common;

/**
 * The service-gate permission node names
 * (docs/planning/service-permission-gate-plan.md §2.1) — ONE home for the strings
 * xplat's handshake gate, the NeoForge node registration, and the Paper plugin all
 * enforce, so the spellings cannot drift between modules (plugin.yml is Paper-only,
 * and neither Fabric nor NeoForge has a declaration registry to catch a typo).
 *
 * <p>BOTH brand spellings exist and enforcement requires BOTH (a negative grant on
 * EITHER denies — the deny model's De Morgan mirror of the far-player privacy
 * nodes' grant-model OR; see plugin.yml's comment block). The SERVICE nodes are
 * default-TRUE everywhere: declared {@code default: true} in plugin.yml,
 * default-true resolvers on the NeoForge {@code PermissionNode}s, and
 * {@code defaultValue=true} at every service-gate {@code checkPermission} call site —
 * arming {@code requireServicePermission} on its own denies NOBODY.
 *
 * <p>The far-player HIDE nodes (far-player-render-hardening-plan.md WI-7a) are the
 * opposite shape: a grant model, default FALSE everywhere ({@code default: false} in
 * plugin.yml, default-false NeoForge resolvers, {@code defaultValue=false} at the
 * snapshot read). Since 0.21.0 (hide-permission plan §2) holding EITHER spelling no
 * longer hides the player by itself — a wildcard grant resolves every node true, which
 * hid every admin. The snapshot carries the grant as a fact, and the broadcast service
 * hides holders only while the server sets
 * {@code far_players.hide_permission_holders: true}; players hide themselves with Share
 * My Position. Paper read the nodes from the start; since 2026-09-04 Fabric and NeoForge
 * read them through {@code LoaderServices.checkPermission} too (Sponge since 0.18.0).
 *
 * <p>The MAP-INGESTION nodes (map-ingestion-permission plan §2) are a grant model like the
 * hide nodes, default FALSE everywhere: while {@code privacy.request_no_map_ingestion} is
 * on, a player holding EITHER spelling is exempt (the server treats the request as off for
 * that player). Their reads are FAIL-CLOSED ({@code defaultValue=false}, a throwing
 * backend answers not exempt), unlike every service-gate read.
 */
public final class LSSPermissions {

    /** The LSS spelling of the service-gate node. */
    public static final String SERVICE_LSS = "lss.use";

    /** The VSS spelling — survives in BOTH branded jars (release_check pins it). */
    public static final String SERVICE_VSS = "vss.use";

    /** The LSS spelling of the far-player hide node (default false; a holder is hidden
     *  only while {@code far_players.hide_permission_holders} is on). */
    public static final String FARPLAYERS_HIDDEN_LSS = "lss.farplayers.hidden";

    /** The VSS spelling of the far-player hide node — declared and honored in BOTH jars. */
    public static final String FARPLAYERS_HIDDEN_VSS = "vss.farplayers.hidden";

    /** The LSS spelling of the map-ingestion exemption node (default false, grant = exempt
     *  from {@code privacy.request_no_map_ingestion}). */
    public static final String MAP_INGESTION_LSS = "lss.map_ingestion";

    /** The VSS spelling of the map-ingestion exemption node — declared and honored in BOTH jars. */
    public static final String MAP_INGESTION_VSS = "vss.map_ingestion";

    private LSSPermissions() {
    }
}
