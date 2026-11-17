package dev.vox.lss;

import dev.vox.lss.testutil.SourcePaths;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Source wiring pins for the server map-ingestion request (map-policy plan A2/A3/A4 —
 * the {@code LoaderPermissionSeamContractTest} family). Only what no behavior test can
 * see stays here (review fold A12 dropped the statement-text pins the gametests and
 * {@code PaperMapPolicyTest} already cover):
 * <ul>
 *   <li>the SITE CENSUS over every production tree: no flag-less SessionConfig
 *       constructor (fully qualified spellings included), every {@code forSession} and
 *       Paper {@code sendSessionConfig} call passes the session's capabilities and the
 *       PER-PLAYER decision as its last two arguments, and Paper's capability-less
 *       encoder is not reachable from production;</li>
 *   <li>the per-player decision (map-ingestion permission plan §3.2): every
 *       {@code denyMapIngestion} a send passes is assigned from the helper
 *       ({@code denyMapIngestionFor} / {@code MapPolicy.decide}), and every remaining
 *       raw {@code requestNoMapIngestion()} read is on a named allowlist (the helper's
 *       own input, the sweep guard, the reload adoption, the diag line, the re-offer log
 *       text) — a send site that reads the server-wide value ignores the exemption;</li>
 *   <li>both production handshake cores ride the widest {@code HandshakeGate.evaluate}
 *       form and branch on {@code MAP_POLICY_REFUSED} explicitly (a new outcome would
 *       otherwise fall into a generic branch silently);</li>
 *   <li>the production wiring of the client write gate and the shared handshake mask.</li>
 * </ul>
 */
class MapPolicyWiringContractTest {

    private static String source(String repoPath) throws IOException {
        return Files.readString(SourcePaths.repoFile(repoPath));
    }

    private static String normalized(String repoPath) throws IOException {
        return source(repoPath).replaceAll("\\s+", " ");
    }

    /** Java source with comments removed (string and char literals kept verbatim), so a
     *  comment inside a call's argument list cannot split or fake an argument. */
    static String withoutComments(String src) {
        var out = new StringBuilder(src.length());
        int i = 0, n = src.length();
        while (i < n) {
            char c = src.charAt(i);
            if (c == '"' || c == '\'') {
                int j = i + 1;
                if (c == '"' && src.startsWith("\"\"\"", i)) { // text block
                    j = src.indexOf("\"\"\"", i + 3);
                    j = j < 0 ? n : j + 3;
                } else {
                    while (j < n && src.charAt(j) != c) j += src.charAt(j) == '\\' ? 2 : 1;
                    j = Math.min(n, j + 1);
                }
                out.append(src, i, j);
                i = j;
            } else if (src.startsWith("//", i)) {
                int j = src.indexOf('\n', i);
                i = j < 0 ? n : j;
            } else if (src.startsWith("/*", i)) {
                int j = src.indexOf("*/", i + 2);
                i = j < 0 ? n : j + 2;
                out.append(' ');
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    /** The argument list of every call starting at {@code needle}, split at top-level commas. */
    static List<List<String>> callArgs(String code, String needle) {
        var out = new ArrayList<List<String>>();
        int idx = 0;
        while ((idx = code.indexOf(needle, idx)) >= 0) {
            int open = idx + needle.length() - 1; // the needle ends with '('
            var args = new ArrayList<String>();
            int depth = 0, start = open + 1, end = open;
            for (int i = open; i < code.length(); i++) {
                char c = code.charAt(i);
                if (c == '"') { // skip string literals
                    i++;
                    while (i < code.length() && code.charAt(i) != '"') i += code.charAt(i) == '\\' ? 2 : 1;
                    continue;
                }
                if (c == '(' || c == '{' || c == '[') depth++;
                else if (c == ')' || c == '}' || c == ']') {
                    if (--depth == 0) { end = i; break; }
                } else if (c == ',' && depth == 1) {
                    args.add(code.substring(start, i).trim());
                    start = i + 1;
                }
            }
            String last = code.substring(start, end).trim();
            if (!last.isEmpty() || !args.isEmpty()) args.add(last);
            out.add(args);
            idx = end + 1;
        }
        return out;
    }

    /** A method's body text (balanced braces from the first '{' after the signature). */
    private static String body(String text, String signature) {
        int start = text.indexOf(signature);
        assertTrue(start >= 0, "missing " + signature);
        int open = text.indexOf('{', start), depth = 0;
        for (int i = open; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return text.substring(open, i + 1);
        }
        throw new AssertionError("unbalanced " + signature);
    }

    private static final String GLUE = "xplat/src/main/java/dev/vox/lss/networking/server/ServerReceiverGlue.java";
    private static final String PAPER_PLUGIN = "paper/src/main/java/dev/vox/lss/paper/LSSPaperPlugin.java";
    private static final String PAPER_HANDLER_FILE = "PaperPayloadHandler.java";
    private static final String PAYLOAD_FILE = "SessionConfigS2CPayload.java";
    private static final String[] PRODUCTION_TREES = {"xplat/src/main/java", "fabric/src/main/java",
            "neoforge/src/main/java", "sponge/src/main/java", "paper/src/main/java"};

    /** A constructor call, whether the type is imported or fully qualified. */
    private static final Pattern FLAG_LESS_CONSTRUCTOR =
            Pattern.compile("new\\s+(?:[\\w.]+\\.)?SessionConfigS2CPayload\\s*\\(");

    private static List<Path> productionFiles() throws IOException {
        var out = new ArrayList<Path>();
        for (String tree : PRODUCTION_TREES) {
            try (var walk = Files.walk(SourcePaths.repoFile(tree))) {
                walk.filter(x -> x.toString().endsWith(".java")).forEach(out::add);
            }
        }
        return out;
    }

    /** The session's capabilities, then the per-player decision — the last two arguments. */
    private static void assertCapabilitiesThenPolicy(Path file, List<String> args) {
        assertTrue(args.size() >= 2, file + ": " + args);
        String caps = args.get(args.size() - 2);
        String policy = args.get(args.size() - 1);
        assertTrue(caps.toLowerCase(java.util.Locale.ROOT).contains("capabilities")
                        || caps.equals("caps"),
                file + ": the second-to-last argument must be the SESSION's capabilities, got " + args);
        // Map-ingestion permission plan §3.2: the per-player decision (assigned from the
        // helper — pinned below), or the literal of the revocation push to a session WITHOUT
        // the bit (no flags field). Never the raw server-wide read: that ignores the exemption.
        assertTrue(policy.equals("denyMapIngestion") || policy.equals("true"),
                file + ": the last argument must be the per-player decision at send time (or the "
                        + "revocation push's literal), got " + args);
    }

    /** Every production assignment of the decision variable takes the helper's answer. */
    @Test
    void everyDecisionVariableIsAssignedFromTheHelper() throws IOException {
        int assignments = 0;
        var assign = Pattern.compile("boolean\\s+denyMapIngestion\\s*=([^;]*);");
        for (var f : productionFiles()) {
            var m = assign.matcher(withoutComments(Files.readString(f)));
            while (m.find()) {
                String rhs = m.group(1);
                assertTrue(rhs.contains("denyMapIngestionFor(") || rhs.contains("MapPolicy.decide(")
                                || rhs.trim().equals("mapPolicy.deny()"),
                        f + ": denyMapIngestion must come from the per-player helper, got " + rhs.trim());
                assignments++;
            }
        }
        assertTrue(assignments >= 13, "the census must see every send/decision site "
                + "(xplat 6, Paper 7), got " + assignments);
    }

    /** The raw server-wide read is allowed only where the per-player decision does not apply. */
    private static final List<Pattern> RAW_POLICY_READ_ALLOWLIST = List.of(
            // the helper's own input (both cores, both services, the Paper reply/sender/drain)
            Pattern.compile("MapPolicy\\.decide\\(\\s*(?:this\\.)?(?:lssConfig|config)\\.requestNoMapIngestion\\(\\)$"),
            // the reload adoption (constructor + reconcile): the policy, not a player
            Pattern.compile("privacy\\(\\)\\.requestNoMapIngestion\\(\\)$"),
            // the sweep's armed guard
            Pattern.compile("if \\(!config\\.requestNoMapIngestion\\(\\)$"),
            // the diag line renders while armed
            Pattern.compile("withMapPolicyLine\\(!config\\.requestNoMapIngestion\\(\\)$"),
            // the re-offer INFO line's wording (exempt vs disarmed), after the decision
            Pattern.compile("reofferReason\\(remembered, config\\.requireServicePermission\\(\\), "
                    + "denyMapIngestion, config\\.requestNoMapIngestion\\(\\)$"));

    @Test
    void everyRawPolicyReadIsAllowlisted() throws IOException {
        int reads = 0;
        String needle = "requestNoMapIngestion()";
        for (var f : productionFiles()) {
            String code = withoutComments(Files.readString(f)).replaceAll("\\s+", " ");
            int idx = 0;
            while ((idx = code.indexOf(needle, idx)) >= 0) {
                String upTo = code.substring(Math.max(0, idx - 120), idx + needle.length())
                        .replace("( ", "(");
                boolean allowed = RAW_POLICY_READ_ALLOWLIST.stream()
                        .anyMatch(pt -> pt.matcher(upTo).find());
                assertTrue(allowed, f + ": a raw requestNoMapIngestion() read outside the allowlist "
                        + "ignores the per-player exemption — use the helper: ..." + upTo);
                reads++;
                idx += needle.length();
            }
        }
        assertTrue(reads >= 10, "the census must see the reads, got " + reads);
    }

    // ---- the site census (A2) ----

    @Test
    void noProductionCodeUsesAFlagLessSessionConfigConstructor() throws IOException {
        for (var f : productionFiles()) {
            if (f.getFileName().toString().equals(PAYLOAD_FILE)) continue; // its own factories + decode
            var m = FLAG_LESS_CONSTRUCTOR.matcher(withoutComments(Files.readString(f)));
            assertFalse(m.find(), f + " builds a SessionConfig without the session's capabilities — "
                    + "production sends go through forSession (or v16Legacy)");
        }
    }

    @Test
    void everyProductionForSessionCallPassesTheSessionCapabilitiesAndThePolicy() throws IOException {
        int calls = 0;
        for (var f : productionFiles()) {
            if (f.getFileName().toString().equals(PAYLOAD_FILE)) continue; // the definition
            for (var args : callArgs(withoutComments(Files.readString(f)), "forSession(")) {
                assertEquals(7, args.size(), f + ": the widest forSession form, got " + args);
                assertCapabilitiesThenPolicy(f, args);
                calls++;
            }
        }
        assertTrue(calls >= 6, "the census must see the xplat sites (reply, repush, two revocations, "
                + "dimension change, the sweep's flags push), got " + calls);
    }

    @Test
    void everyPaperSendPassesTheSessionCapabilitiesAndThePolicy() throws IOException {
        int sends = 0;
        for (var f : productionFiles()) {
            String code = withoutComments(Files.readString(f));
            if (f.getFileName().toString().equals(PAPER_HANDLER_FILE)) {
                assertFalse(Pattern.compile("public\\s+static\\s+byte\\[\\]\\s+encodeSessionConfig\\(\\s*int\\s+\\w+,"
                                        + "\\s*boolean\\s+\\w+,\\s*int\\s+\\w+,\\s*boolean\\s+\\w+\\s*\\)")
                                .matcher(code).find(),
                        "Paper's capability-less encoder stays package-private (test frames only)");
                continue;
            }
            assertFalse(code.contains("encodeSessionConfig("),
                    f + " encodes a SessionConfig outside PaperPayloadHandler — use sendSessionConfig");
            for (var args : callArgs(code, "PaperPayloadHandler.sendSessionConfig(")) {
                // 0.23.0 plan §3: the reason follows `enabled`; capabilities and the decision
                // stay last.
                assertEquals(8, args.size(), f + ": " + args);
                assertEquals("offReason", args.get(3), f + ": the reason the answer says \"not here\", got " + args);
                assertCapabilitiesThenPolicy(f, args);
                sends++;
            }
        }
        assertTrue(sends >= 2, "the handshake reply and the service's send lambda, got " + sends);
        // The Paper service's own send path: one sender call inside the recording wrapper,
        // and every wrapper call passes the session's capabilities and the decision.
        String service = withoutComments(source("paper/src/main/java/dev/vox/lss/paper/PaperRequestProcessingService.java"));
        var senderCalls = callArgs(service, "this.sessionConfigSender.send(");
        assertEquals(1, senderCalls.size(), "only the recording wrapper calls the sender");
        assertCapabilitiesThenPolicy(Path.of("PaperRequestProcessingService"), senderCalls.getFirst());
        var wrapped = callArgs(service.replace("private void sendSessionConfig(", "private void DECL("),
                " sendSessionConfig(");
        assertTrue(wrapped.size() >= 4, "repush, map revocation, flags push, dimension change");
        for (var args : wrapped) {
            assertEquals(4, args.size(), args.toString());
            assertCapabilitiesThenPolicy(Path.of("PaperRequestProcessingService"), args);
        }
        // 0.23.0 plan §3: the permission gate's revocation has its own wrapper (it is the one
        // send to a session that is about to be unregistered, and the one with the permission
        // reason).
        var revocations = callArgs(service.replace("private void sendGateRevocation(", "private void DECL("),
                " sendGateRevocation(");
        assertEquals(1, revocations.size(), "the gate sweep's revocation push");
        assertEquals(3, revocations.getFirst().size(), revocations.toString());
        assertCapabilitiesThenPolicy(Path.of("PaperRequestProcessingService"), revocations.getFirst());
    }

    @Test
    void theArgumentSplitterSeesThroughCommentsAndNesting() {
        String code = withoutComments("""
                x.forSession(a, f(b, c), // a comment, with (parens)
                        /* block, comment */ d.getCapabilities(), cfg.requestNoMapIngestion());
                """);
        assertEquals(List.of(List.of("a", "f(b, c)", "d.getCapabilities()", "cfg.requestNoMapIngestion()")),
                callArgs(code, "forSession("));
        assertTrue(FLAG_LESS_CONSTRUCTOR.matcher("new dev.vox.lss.networking.payloads.SessionConfigS2CPayload(1,"
                + " true, 2, true)").find(), "the census sees a fully qualified constructor");
    }

    // ---- the handshake rung (A3.2) ----

    @Test
    void bothProductionCoresUseTheWidestGateAndBranchOnTheRefusalExplicitly() throws IOException {
        for (String path : new String[]{GLUE, PAPER_PLUGIN}) {
            String src = normalized(path).replace("( ", "(");
            assertTrue(src.contains("ViaProbe.isMismatch(viaProtocol, nativeProtocol), denyMapIngestion);"),
                    path + " must ride the widest (9-arg) evaluate form");
            // Map-ingestion permission plan §3.2: the rung takes the PER-PLAYER decision,
            // whose exemption read is the gate's fail-closed member.
            assertTrue(src.contains("MapPolicy.decide(config.requestNoMapIngestion(), serviceGate::mapIngestionExempt)"),
                    path + " must assign the handshake decision from the helper with the gate's exemption read");
            assertTrue(src.contains("decision.outcome() == HandshakeGate.Outcome.MAP_POLICY_REFUSED"),
                    path + " must branch on the new outcome explicitly");
        }
        String glue = normalized(GLUE);
        int reply = glue.indexOf("responder.send(");
        int branch = glue.lastIndexOf("decision.outcome() == HandshakeGate.Outcome.MAP_POLICY_REFUSED");
        assertTrue(reply >= 0 && reply < branch, "the refusal's side effects follow its reply");
    }

    // ---- the client write gate (A4) ----

    @Test
    void everyMapBackendComposesTheServerWriteGateOnItsWritePaths() throws IOException {
        String xaero = source("xplat/src/main/java/dev/vox/lss/compat/XaeroSession.java");
        String jm = source("xplat/src/main/java/dev/vox/lss/compat/JourneyMapSession.java");
        for (String src : new String[]{xaero, jm}) {
            assertTrue(src.contains("dev.vox.lss.networking.client.ClientNetGlue::serverAllowsMapIngestion"),
                    "production init wires the connection's flag");
        }
        // The implementing offerColumn overload (the others delegate into it).
        for (String method : new String[]{"XaeroTileExtractor.LevelFacts level, VoxelColumnData columnData) {",
                "int reportBackpressure() {", "void pump() {"}) {
            assertTrue(body(xaero, method).contains("this.serverAllowsWrites.getAsBoolean()"),
                    "Xaero " + method + " composes the server gate");
        }
        for (String method : new String[]{"void offerColumn(", "void offerEncoded(Entry entry) {",
                "int reportBackpressure() {", "void pump() {"}) {
            assertTrue(body(jm, method).contains("this.serverAllowsWrites.getAsBoolean()"),
                    "JourneyMap " + method + " composes the server gate");
        }
    }

    @Test
    void bothClientHandshakeSendsUseTheSharedCapabilityMask() throws IOException {
        String glue = source("xplat/src/main/java/dev/vox/lss/networking/client/ClientNetGlue.java");
        var sends = callArgs(withoutComments(glue), "new HandshakeC2SPayload(");
        assertEquals(2, sends.size(), "the join/ladder announce and the LAN-host self-handshake");
        for (var send : sends) {
            assertTrue(String.join(",", send).contains("handshakeCapabilities()"), send.toString());
        }
        assertTrue(glue.contains("dev.vox.lss.compat.ModCompat.isMapOnlyIngestion());"),
                "the session gate learns whether a map mod is the only consumer");
    }
}
