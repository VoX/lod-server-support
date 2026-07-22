package dev.vox.lss.common;

import java.io.InputStream;
import java.util.Properties;

/**
 * Runtime display branding. Defaults to <b>LOD Server Support</b>; the Voxy Server Side
 * repackage ({@code voxyJar}) rewrites the bundled {@code lss-brand.properties} resource,
 * and each platform's entrypoint calls {@link #load} as its FIRST action — before any
 * service (and thus any branded thread name, log line, or command) is created.
 *
 * <p><b>Display-only, never on the wire.</b> The mod id, plugin name, protocol version,
 * channel ids ({@code lss:*}), and payload formats are all untouched by branding, so an
 * LSS client and a VSS server (or vice versa) stay fully wire-compatible regardless of
 * which brand each side carries. This class only affects text a human reads.
 */
public final class Brand {

    /** Resource bundled in each platform jar; rewritten by the VSS repackage. */
    public static final String RESOURCE = "lss-brand.properties";

    private static volatile String shortName = "LSS";
    private static volatile String displayName = "LOD Server Support";
    private static volatile String clientCommand = "lss";
    private static volatile String serverCommand = "lsslod";

    private Brand() {}

    /**
     * Load branding from {@link #RESOURCE} on the given class loader (each platform passes
     * its own so the correct jar's resource is read). Missing/blank values keep the LSS
     * defaults, so a jar without the resource — or a test — behaves exactly as LSS.
     */
    public static void load(ClassLoader loader) {
        try (InputStream in = loader.getResourceAsStream(RESOURCE)) {
            if (in == null) return;
            var props = new Properties();
            props.load(in);
            apply(props.getProperty("shortName"), props.getProperty("displayName"),
                    props.getProperty("clientCommand"), props.getProperty("serverCommand"));
        } catch (Exception e) {
            // Branding is cosmetic — a read failure must never break startup. Keep defaults.
            LSSLogger.warn("Could not read " + RESOURCE + "; using default branding");
        }
    }

    /** Test seam: set branding directly without a resource. */
    static void apply(String shortName, String displayName, String clientCommand, String serverCommand) {
        if (shortName != null && !shortName.isBlank()) Brand.shortName = shortName.trim();
        if (displayName != null && !displayName.isBlank()) Brand.displayName = displayName.trim();
        if (clientCommand != null && !clientCommand.isBlank()) Brand.clientCommand = clientCommand.trim();
        if (serverCommand != null && !serverCommand.isBlank()) Brand.serverCommand = serverCommand.trim();
    }

    /** Short acronym for chat/console/thread text: {@code "LSS"} or {@code "VSS"}. */
    public static String shortName() { return shortName; }

    /** Full display name: {@code "LOD Server Support"} or {@code "Voxy Server Side"}. */
    public static String displayName() { return displayName; }

    /** Fabric client command literal (no leading slash): {@code "lss"} or {@code "vss"}.
     *  Local only — never sent over the network. */
    public static String clientCommand() { return clientCommand; }

    /** Server admin command literal (no leading slash): {@code "lsslod"} or {@code "vsslod"}.
     *  Used by the Fabric dedicated-server command; Paper reads its own from plugin.yml.
     *  Local only — never sent over the network. */
    public static String serverCommand() { return serverCommand; }
}
