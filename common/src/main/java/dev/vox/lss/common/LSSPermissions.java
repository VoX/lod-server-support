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
 * nodes' grant-model OR; see plugin.yml's comment block). The nodes are
 * default-TRUE everywhere: declared {@code default: true} in plugin.yml,
 * default-true resolvers on the NeoForge {@code PermissionNode}s, and
 * {@code defaultValue=true} at every {@code checkPermission} call site — arming
 * {@code requireServicePermission} on its own denies NOBODY.
 */
public final class LSSPermissions {

    /** The LSS spelling of the service-gate node. */
    public static final String SERVICE_LSS = "lss.use";

    /** The VSS spelling — survives in BOTH branded jars (release_check pins it). */
    public static final String SERVICE_VSS = "vss.use";

    private LSSPermissions() {
    }
}
