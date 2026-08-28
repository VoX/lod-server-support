package dev.vox.lss.paper;

import org.junit.jupiter.api.Test;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Folia wiring pins, enforced at the constant-pool level: the legacy BukkitScheduler family
 * throws UnsupportedOperationException on Folia, so ANY reference to it anywhere in the
 * plugin is a Folia-fatal regression; and LSSPaperPlugin must route lifecycle ingress through
 * the mailbox (enqueue*), never the pump-only direct methods (on Folia its callers run on
 * region threads).
 *
 * <p>The scan walks EVERY production class under {@code dev/vox/lss/paper} — including
 * nested/anonymous classes and the soak package (it ships in soakShadowJar and runs on
 * Folia). That breadth is load-bearing: the original BukkitRunnable pump lived in an
 * anonymous EnableSteps class, and a revert compiles its only {@code org/bukkit/scheduler}
 * constants into {@code LSSPaperPlugin$1$1.class} — a top-level-only scan stays green while
 * every Folia server fails to enable the plugin. Checking CONSTANT_Class entries (not just
 * method-ref owners) matters for the same reason: an anonymous {@code BukkitRunnable}
 * subclass's {@code runTaskTimer} call is owned by the subclass itself, but its superclass
 * constant names {@code org/bukkit/scheduler/BukkitRunnable}. A coverage canary asserts the
 * scan still sees the GlobalRegionScheduler pump, so a refactor that moves the pump out of
 * the scanned set fails loudly instead of silently shrinking coverage.
 */
class FoliaWiringContractTest {

    private record MethodRef(String owner, String name) {}

    private record ClassPool(String className, List<MethodRef> methodRefs,
                              List<String> classConstants, List<String> utf8Strings) {}

    /** Root of the production classes on the test classpath (build/classes/java/main). */
    private static Path mainClassesPackageDir() throws URISyntaxException {
        var url = FoliaWiringContractTest.class.getResource("/dev/vox/lss/paper/LSSPaperPlugin.class");
        assertTrue(url != null && "file".equals(url.getProtocol()),
                "main classes must be a directory on the test classpath, got " + url);
        return Path.of(url.toURI()).getParent();
    }

    /** Every production class under dev/vox/lss/paper, nested + soak included. */
    private static List<ClassPool> allProductionClassPools() throws IOException, URISyntaxException {
        var out = new ArrayList<ClassPool>();
        try (Stream<Path> files = Files.walk(mainClassesPackageDir())) {
            for (Path p : files.filter(f -> f.toString().endsWith(".class")).toList()) {
                try (InputStream in = Files.newInputStream(p)) {
                    out.add(parsePool(p.getFileName().toString(), in));
                }
            }
        }
        assertTrue(out.size() >= 15, "suspiciously few classes scanned (" + out.size()
                + ") — did the classes root move?");
        return out;
    }

    private static ClassPool parsePool(String name, InputStream raw) throws IOException {
        var in = new DataInputStream(raw);
        if (in.readInt() != 0xCAFEBABE) throw new IOException("not a class file: " + name);
        in.readUnsignedShort(); // minor
        in.readUnsignedShort(); // major
        int cpCount = in.readUnsignedShort();
        String[] utf8 = new String[cpCount];
        int[] classNameIdx = new int[cpCount];
        int[] natNameIdx = new int[cpCount];
        record Ref(int classIdx, int natIdx) {}
        var refs = new ArrayList<Ref>();
        for (int i = 1; i < cpCount; i++) {
            int tag = in.readUnsignedByte();
            switch (tag) {
                case 1 -> utf8[i] = in.readUTF();
                case 7 -> classNameIdx[i] = in.readUnsignedShort();
                case 9, 10, 11 -> refs.add(new Ref(in.readUnsignedShort(), in.readUnsignedShort()));
                case 12 -> { natNameIdx[i] = in.readUnsignedShort(); in.readUnsignedShort(); }
                case 8, 16, 19, 20 -> in.readUnsignedShort();
                case 15 -> { in.readUnsignedByte(); in.readUnsignedShort(); }
                case 3, 4, 17, 18 -> in.readInt();
                case 5, 6 -> { in.readLong(); i++; } // long/double take two pool slots
                default -> throw new IOException("unexpected constant tag " + tag + " in " + name);
            }
        }
        var methodRefs = new ArrayList<MethodRef>();
        for (var r : refs) {
            String owner = utf8[classNameIdx[r.classIdx()]];
            String member = utf8[natNameIdx[r.natIdx()]];
            if (owner != null && member != null) methodRefs.add(new MethodRef(owner, member));
        }
        var classConstants = new ArrayList<String>();
        for (int idx : classNameIdx) {
            if (idx != 0 && utf8[idx] != null) classConstants.add(utf8[idx]);
        }
        var utf8Strings = new ArrayList<String>();
        for (String u : utf8) {
            if (u != null) utf8Strings.add(u);
        }
        return new ClassPool(name, methodRefs, classConstants, utf8Strings);
    }

    @Test
    void noLegacySchedulerReferencesAnywhereInThePlugin() throws Exception {
        boolean sawPumpScheduler = false;
        for (var pool : allProductionClassPools()) {
            for (String cls : pool.classConstants()) {
                assertFalse(cls.startsWith("org/bukkit/scheduler/"),
                        pool.className() + " references class " + cls + " (legacy scheduler throws on Folia)");
                if (cls.startsWith("io/papermc/paper/threadedregions/scheduler/")) {
                    sawPumpScheduler = true;
                }
            }
            for (var ref : pool.methodRefs()) {
                assertFalse(ref.owner().startsWith("org/bukkit/scheduler/"),
                        pool.className() + " references " + ref.owner() + "#" + ref.name()
                                + " (legacy scheduler throws on Folia)");
            }
        }
        // Coverage canary: the pump's GlobalRegionScheduler wiring must be inside the scanned
        // set — if a refactor moves it elsewhere, fail loudly rather than silently scan less.
        assertTrue(sawPumpScheduler,
                "no scanned class references the paper threadedregions schedulers — the scan "
                        + "no longer covers the pump wiring");
    }

    @Test
    void pluginRoutesLifecycleThroughTheMailbox() throws Exception {
        String service = "dev/vox/lss/paper/PaperRequestProcessingService";
        boolean sawEnqueueRegister = false;
        boolean sawEnqueueRemove = false;
        for (var pool : allProductionClassPools()) {
            // The plugin entry point AND its nested/anonymous classes — listeners and
            // EnableSteps lambdas are where region-thread callers live.
            if (!pool.className().startsWith("LSSPaperPlugin")) continue;
            for (var ref : pool.methodRefs()) {
                if (!service.equals(ref.owner())) continue;
                sawEnqueueRegister |= ref.name().equals("enqueueRegister");
                sawEnqueueRemove |= ref.name().equals("enqueueRemove");
                assertFalse(ref.name().equals("registerPlayer"),
                        pool.className() + " calls the pump-only registerPlayer (region-thread callers must enqueue)");
                assertFalse(ref.name().equals("removePlayer"),
                        pool.className() + " calls the pump-only removePlayer (region-thread callers must enqueue)");
            }
        }
        assertTrue(sawEnqueueRegister, "handshake registrar must enqueue through the mailbox");
        assertTrue(sawEnqueueRemove, "quit listener must enqueue through the mailbox");
    }

    /** Folia review 2026-08-27 R12: the constant-pool scan is blind to REFLECTIVE use —
     *  {@code Class.forName("org.bukkit.scheduler...")} compiles to a CONSTANT_String
     *  the class/method-ref walks never see. This closes exactly that hole for the
     *  scheduler family (dotted reflective form; the slashed form rides the class
     *  constants above). REMAINING recorded blind spots (kept as notes, not scans:
     *  each needs semantic analysis a pool walk cannot do): (a) other Folia-fatal API
     *  families — off-region {@code Entity#teleport}, {@code World#save}, cross-region
     *  {@code getChunkAt} — are not scanned, the thread a call runs ON is invisible
     *  here; (b) the scan reads build/classes, not the shadowJar — shaded third-party
     *  code is release_check's forbidden-scan domain; (c) a call wrapped in
     *  enqueueRuntimeTask is indistinguishable from a direct call (see the census
     *  allowlist below). */
    @Test
    void noReflectiveSchedulerUseAnywhereInThePlugin() throws Exception {
        for (var pool : allProductionClassPools()) {
            for (String u : pool.utf8Strings()) {
                assertFalse(u.startsWith("org.bukkit.scheduler"),
                        pool.className() + " carries the string constant \"" + u
                                + "\" — reflective legacy-scheduler use is as Folia-fatal "
                                + "as the compile-time reference");
            }
        }
    }

    /** Folia review 2026-08-27 R11: the mailbox pin above covers two methods on one
     *  class-name prefix — a NEW listener/messenger class calling the pump-only
     *  surface from a region thread (exactly the D3 bug the mailbox exists to
     *  prevent) used to compile, pass :paper:test, and corrupt the pump-owned
     *  non-concurrent maps on the first Folia quit. This census forbids the whole
     *  pump-only method list to every class OUTSIDE the allowlist. The allowlist is
     *  classes whose refs are pump-legal: the service itself (self-refs + lambdas),
     *  and classes whose calls run inside enqueued runtime tasks or pump callbacks
     *  (the pool walk cannot see the enqueueRuntimeTask wrapper — recorded
     *  limitation). Growing the allowlist is a REVIEW decision, not a fix. */
    @Test
    void onlyAllowlistedClassesTouchThePumpOnlySurface() throws Exception {
        String service = "dev/vox/lss/paper/PaperRequestProcessingService";
        var pumpOnly = java.util.Set.of("registerPlayer", "removePlayer",
                "repushSessionConfig", "runServiceGateSweeps", "unregisterForServiceGate",
                "getFarPlayerService");
        var allowPrefixes = List.of(
                "PaperRequestProcessingService", // the pump itself (+ nested/lambdas)
                "LSSPaperPlugin",   // enqueued runtime tasks (prefs, gate) + pump callbacks
                "PaperCommands",    // /lsslod set + diag route through enqueueRuntimeTask
                "PaperSoakMetricsExporter", // pump-driven snapshot exporter
                "PaperSoakScenarioDriver",  // pump-hopped driver
                "PaperSoakBridge");
        for (var pool : allProductionClassPools()) {
            if (allowPrefixes.stream().anyMatch(pool.className()::startsWith)) continue;
            for (var ref : pool.methodRefs()) {
                assertFalse(service.equals(ref.owner()) && pumpOnly.contains(ref.name()),
                        pool.className() + " calls the pump-only " + ref.name()
                                + " — region-thread-reachable classes must marshal through "
                                + "the lifecycle mailbox or enqueueRuntimeTask");
            }
        }
    }
}
