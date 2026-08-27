package dev.vox.lss.neoforge;

import dev.vox.lss.common.LSSConstants;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static dev.vox.lss.neoforge.NeoForgeModuleContractTest.read;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The loader-seam invariants (plan §1.2), source-pinned because no test JVM
 * can execute NeoForge networking: the C2S size bound, LoaderServices
 * completeness, and the sendIfListening containment.
 *
 * <p><b>The interop matrix these pins encode</b> (verified live at N-3's
 * smokes; each cell names its mechanism):
 * <ul>
 *   <li>NeoForge client → vanilla / LSS-less server: the handshake is an
 *       unprompted FIRST send and NeoForge THROWS on unannounced channels —
 *       {@code sendToServer}'s containment makes it a silent no-op (Fabric
 *       parity).</li>
 *   <li>NeoForge server → vanilla / Fabric-no-LSS client: S2C sends only ever
 *       follow a client handshake, but {@code sendToPlayer} still carries the
 *       {@code hasChannel} pre-check (defense in depth for re-handshake races).</li>
 *   <li>NeoForge client → Fabric LSS server (upstream #1913 exposure): our own
 *       handshake arms the session; if announcement is one-way-broken the
 *       contained send degrades the client to LSS-inert — never a crash.</li>
 *   <li>Fabric client → NeoForge LSS server: `.optional()` keeps login alive;
 *       the channels negotiate normally (the N-2 boot smoke's arm).</li>
 * </ul>
 */
class NeoForgeLoaderSeamContractTest {

    /**
     * The C2S ≤ 32 KiB loader bound (plan §1.2): the want-set batch is
     * MAX_BATCH_CHUNK_REQUESTS × 16 B (packed pos + client timestamp) plus a
     * small envelope; NeoForge refuses larger serverbound custom payloads at
     * the vanilla bound. Build-time pin so a future budget raise cannot
     * silently cross it (the WantSetBudgetInvariantTest pattern).
     */
    @Test
    void wantSetBatchStaysUnderTheNeoForgeC2SBound() {
        int entryBytes = 8 + 8;
        int envelopeMargin = 256;
        int worstCase = LSSConstants.MAX_BATCH_CHUNK_REQUESTS * entryBytes + envelopeMargin;
        assertTrue(worstCase < 32768,
                "the worst-case want-set batch (" + worstCase + " B) must stay under the"
                        + " 32 KiB serverbound custom-payload bound — raising"
                        + " MAX_BATCH_CHUNK_REQUESTS past this breaks every NeoForge client");
    }

    /**
     * LoaderServices completeness (the reflective-completeness-pin pattern,
     * source-flavored — MC param types keep the impl un-instantiable in a bare
     * JUnit JVM): every abstract method the xplat interface declares must be
     * overridden by the NeoForge impl, so an interface growth on the fabric
     * side cannot leave this loader compiling against a default that throws.
     */
    @Test
    void neoForgeImplOverridesEveryLoaderServicesMethod() throws IOException {
        String iface = read("xplat/src/main/java/dev/vox/lss/platform/LoaderServices.java");
        String impl = read("neoforge/src/main/java/dev/vox/lss/platform/NeoForgeLoaderServices.java");
        // (?:default\s+)? — panel MAJOR (service-gate plan §8 O1-M3): a DEFAULT method
        // was invisible to this scan, so a loader impl forgetting to override
        // checkPermission would ship the gate permanently inert with a green suite.
        Matcher m = Pattern.compile(
                "^\\s{4}(?:default\\s+)?(?:boolean|void|Path|String|int|long)\\s+(\\w+)\\(",
                Pattern.MULTILINE)
                .matcher(iface);
        int found = 0;
        while (m.find()) {
            String method = m.group(1);
            found++;
            assertTrue(Pattern.compile("public\\s+\\w+[\\w<>.\\[\\]]*\\s+" + method + "\\(")
                            .matcher(impl).find(),
                    "NeoForgeLoaderServices must override LoaderServices." + method);
        }
        assertTrue(found >= 7, "the interface-method scan went blind (found " + found
                + ") — fix this test's regex before trusting it");
    }

    /** The sendIfListening containment (plan §1.2): both send paths must carry the
     *  hasChannel pre-check / throw containment — without it a NeoForge client
     *  joining a vanilla server crashes on the handshake send (the unannounced-channel
     *  throw — plan §1.2's loader-difference list). */
    @Test
    void sendPathsContainTheUnannouncedChannelThrow() throws IOException {
        String impl = read("neoforge/src/main/java/dev/vox/lss/platform/NeoForgeLoaderServices.java");
        assertTrue(impl.contains("hasChannel(payload.type())"),
                "sendToPlayer must pre-check channel negotiation (Fabric-parity silent no-op)");
        assertTrue(impl.contains("catch (UnsupportedOperationException"),
                "the pre-check race window still needs the throw contained");
        // The CLIENT half (N-3): the unprompted-first-send containment — a null
        // connection or an un-negotiated channel must be a silent no-op.
        String client = read(
                "neoforge/src/main/java/dev/vox/lss/platform/NeoForgeClientLoaderServices.java");
        assertTrue(client.contains("connection == null")
                        && client.contains("!connection.hasChannel(payload.type())"),
                "sendToServer must pre-check the connection + channel (the unannounced-channel crash class)");
        assertTrue(client.contains("throw new IllegalStateException(\"no connection"),
                "a null connection must THROW (Fabric parity — the manager's send-failure"
                        + " ladder depends on it; round-3 review)");
        assertTrue(client.contains("catch (UnsupportedOperationException"),
                "the client send's race window needs the throw contained too");
    }

    /** N-3 wiring pins: the reflective bootstrap target exists under the exact name the
     *  entrypoint loads, and the far-player RENDER-PATH cut keeps its precise form —
     *  the capability arm term is shared xplat code the neoforge module must not
     *  suppress (the bit is the PREFS CARRIER; plan §N-3/§6.2 wire-safety list). */
    @Test
    void clientBootstrapExistsUnderTheReflectiveName() throws IOException {
        String mod = read("neoforge/src/main/java/dev/vox/lss/neoforge/LSSNeoMod.java");
        assertTrue(mod.contains(
                        "CLIENT_BOOTSTRAP_CLASS = \"dev.vox.lss.neoforge.LSSNeoClientBootstrap\""),
                "the reflective constant must name the bootstrap class");
        assertTrue(NeoForgeModuleContractTest.exists(
                        "neoforge/src/main/java/dev/vox/lss/neoforge/LSSNeoClientBootstrap.java"),
                "the bootstrap class must exist (a rename strands the client half inert"
                        + " with only an INFO line)");
        String renderer = read(
                "neoforge/src/main/java/dev/vox/lss/networking/client/FarPlayerRenderer.java");
        assertTrue(!renderer.contains("capabilityBit") && !renderer.contains("FarPlayerClientSupport"),
                "the render-path cut must stay RENDER-ONLY — arm/capability state is"
                        + " shared xplat code this twin must never touch");
    }

    /**
     * The NeoForge client wiring of the loader-neutral lifecycle (sweep C N2): the
     * xplat pins cover ClientNetGlue's three call sites, but nothing pinned that THIS
     * loader registers the tick pump, the disconnect settle and the compat init —
     * deleting any of the three kills the Xaero bridge, far players and the client
     * session with every test green.
     */
    @Test
    void neoForgeClientWiresTheLifecycleTickAndCompatInit() throws IOException {
        String boot = read("neoforge/src/main/java/dev/vox/lss/neoforge/LSSNeoClientBootstrap.java");
        assertTrue(Pattern.compile("ClientTickEvent\\.Post\\.class,\\s*e -> ClientNetGlue\\.onEndClientTick\\(\\)").matcher(boot).find(),
                "the end-of-client-tick pump must be registered on ClientTickEvent.Post");
        assertTrue(Pattern.compile("ClientPlayerNetworkEvent\\.LoggingOut\\.class,\\s*e -> ClientNetGlue\\.onDisconnect\\(\\)").matcher(boot).find(),
                "the disconnect settle must be registered on ClientPlayerNetworkEvent.LoggingOut");
        assertTrue(Pattern.compile("ClientPlayerNetworkEvent\\.LoggingIn\\.class,\\s*e -> ClientNetGlue\\.onJoin\\(\\)").matcher(boot).find(),
                "the join hook must be registered on ClientPlayerNetworkEvent.LoggingIn");
        assertTrue(Pattern.compile("RenderFrameEvent\\.Post\\.class,\\s*e -> ClientNetGlue\\.onRenderFrame\\(\\)").matcher(boot).find(),
                "the per-frame Xaero rebuild slice must stay registered (plan §17) — without it"
                        + " rebuilds silently bunch back onto the client tick and stutter");
        assertTrue(boot.contains("ModCompat.init();"), "the compat ladder (Xaero, Voxy) must be initialized");
    }
}
