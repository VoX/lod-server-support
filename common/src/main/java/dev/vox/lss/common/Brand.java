package dev.vox.lss.common;

import java.io.InputStream;
import java.util.Properties;

/**
 * Runtime display branding. Defaults to <b>LOD Server Support</b>; the Voxy Server Side
 * repackage ({@code voxyJar}) rewrites the bundled {@code lss-brand.properties} resource,
 * and each platform's entrypoint calls {@link #load} as its FIRST action — before any
 * service (and thus any branded thread name, log line, or command) is created.
 *
 * <p><b>Never on the wire.</b> The mod id, protocol version, channel ids ({@code lss:*}),
 * and payload formats are all untouched by branding, so an LSS client and a VSS server
 * (or vice versa) stay fully wire-compatible regardless of which brand each side carries.
 * Branding affects text a human reads plus a small set of LOCAL names — config filenames
 * ({@code SettingsStore} YAML selection and legacy migration), the LOD-store directory
 * ({@code LodStores.brandedStoreDir}), and the client cache dot-dir — each with
 * adopt-the-other-brand's-existing-file fallback so a jar swap keeps its data.
 */
public final class Brand {

    /** Resource bundled in each platform jar; rewritten by the VSS repackage. */
    public static final String RESOURCE = "lss-brand.properties";

    private static volatile String shortName = "LSS";
    private static volatile String displayName = "LOD Server Support";
    private static volatile String clientCommand = "lss";
    private static volatile String serverCommand = "lsslod";
    /** A second client command name this build also answers to, or "" (0.23.0 plan §4): the
     *  Voxy Server Side client registers {@code /lss} beside {@code /vss}, because every
     *  player-facing document says {@code /lss status}. Data, not code: the two builds' classes
     *  stay byte-identical. */
    private static volatile String alsoClientCommand = "";

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
            String also = props.getProperty("alsoClientCommand");
            alsoClientCommand = also == null ? "" : also.trim();
        } catch (Exception e) {
            // Branding is cosmetic — a read failure must never break startup. Keep defaults.
            LSSLogger.warn("Could not read " + RESOURCE + "; using default branding");
        }
    }

    /** Test seam: the second client command name ("" for none). */
    static void applyAlsoClientCommand(String also) {
        alsoClientCommand = also == null ? "" : also.trim();
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

    /** Lowercased acronym for LOCAL names ({@code "lss"} / {@code "vss"}): operator
     *  messages naming the brand-preferred config file, and (as literals kept in sync
     *  with this) the store directory / cache dot-dir. Never a wire or identity token. */
    public static String lowerShortName() {
        return shortName.toLowerCase(java.util.Locale.ROOT);
    }

    /** Full display name: {@code "LOD Server Support"} or {@code "Voxy Server Side"}. */
    public static String displayName() { return displayName; }

    /** Fabric client command literal (no leading slash): {@code "lss"} or {@code "vss"}.
     *  Local only — never sent over the network. */
    public static String clientCommand() { return clientCommand; }

    /** Every client command name this build answers to: its own first (the one its help and
     *  messages print), then the extra one when the build has one and it differs. */
    public static java.util.List<String> clientCommands() {
        String own = clientCommand, also = alsoClientCommand;
        return also.isBlank() || also.equals(own) ? java.util.List.of(own) : java.util.List.of(own, also);
    }

    /** Server admin command literal (no leading slash): {@code "lsslod"} or {@code "vsslod"}.
     *  Used by the Fabric dedicated-server command; Paper reads its own from plugin.yml.
     *  Local only — never sent over the network. */
    public static String serverCommand() { return serverCommand; }
}
