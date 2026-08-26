package dev.vox.lss.neoforge;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The stub-parity golden (2-Opus fold of the NeoForge Sodium 0.8+ page): every
 * compile-only {@code net.caffeinemc} stub in this module must be a DESCRIPTOR SUBSET
 * of the REAL sodium-neoforge artifact, because the shipped walker's call sites are
 * compiled against the stubs but resolve at runtime against Sodium's real classes — a
 * drifted stub compiles and ships green and throws {@code NoSuchMethodError}/
 * {@code AbstractMethodError} at config-harvest time. The Fabric walker has this pin by
 * construction ({@code modCompileOnly} against the real jar); this test is the NeoForge
 * module's equivalent, against the {@code sodiumNeoGolden} configuration (the line's
 * pinned {@code sodium_version} with the {@code -neoforge} classifier — build.gradle
 * derives it, the fabric golden-arm discipline: offline boxes skip, CI must resolve).
 *
 * <p>Checked per stub classfile, discovered from the classes dir so a NEW stub file is
 * covered without a list edit: the real class exists; interface/enum kind matches;
 * every non-private non-synthetic stub method exists in the real class with the
 * identical descriptor (enum specials excluded — constants are compared as fields
 * instead); and a stub method the walker INHERITS as a default must not be abstract in
 * the real interface (the {@code registerConfigEarly} AbstractMethodError trap).
 */
class SodiumNeoGoldenParityTest {

    private static final String PROBE = "net/caffeinemc/mods/sodium/api/config/ConfigEntryPoint.class";

    @Test
    void everyStubMemberExistsInTheRealSodiumWithTheSameDescriptor() throws Exception {
        Path jar = goldenJar();
        List<Path> stubClasses = stubClassFiles();
        assertTrue(stubClasses.size() >= 15,
                "suspiciously few stub classfiles (" + stubClasses.size() + ") — did the stub package move?");
        List<String> problems = new ArrayList<>();
        try (ZipFile zip = openGolden(jar)) {
            for (Path stubFile : stubClasses) {
                ClassfileSurface.Surface stub;
                try (InputStream in = Files.newInputStream(stubFile)) {
                    stub = ClassfileSurface.parse(in);
                }
                ZipEntry entry = zip.getEntry(stub.name() + ".class");
                if (entry == null) {
                    problems.add(stub.name() + ": class absent from the real artifact");
                    continue;
                }
                ClassfileSurface.Surface real;
                try (InputStream in = zip.getInputStream(entry)) {
                    real = ClassfileSurface.parse(in);
                }
                compare(stub, real, problems);
            }
        }
        assertTrue(problems.isEmpty(),
                "stub drift vs " + jar.getFileName() + ":\n  " + String.join("\n  ", problems));
    }

    private static void compare(ClassfileSurface.Surface stub, ClassfileSurface.Surface real,
                                List<String> problems) {
        for (int kindBit : new int[] {ClassfileSurface.ACC_INTERFACE, ClassfileSurface.ACC_ENUM}) {
            if ((stub.access() & kindBit) != (real.access() & kindBit)) {
                problems.add(stub.name() + ": class kind differs (stub 0x"
                        + Integer.toHexString(stub.access()) + " vs real 0x"
                        + Integer.toHexString(real.access()) + ")");
            }
        }
        boolean isEnum = (stub.access() & ClassfileSurface.ACC_ENUM) != 0;
        Map<String, Integer> realMethods = new HashMap<>();
        for (ClassfileSurface.Member m : real.methods()) {
            realMethods.put(m.name() + m.descriptor(), m.access());
        }
        for (ClassfileSurface.Member m : stub.methods()) {
            if ((m.access() & (ClassfileSurface.ACC_PRIVATE | ClassfileSurface.ACC_SYNTHETIC)) != 0
                    || m.name().equals("<clinit>")
                    // enum plumbing: the implicit ctor's shape follows the CONSTANTS'
                    // args (real ones may carry payloads) and $values is compiler-owned
                    || (isEnum && (m.name().equals("<init>") || m.name().equals("$values")))) {
                continue;
            }
            Integer realAccess = realMethods.get(m.name() + m.descriptor());
            if (realAccess == null) {
                problems.add(stub.name() + "." + m.name() + m.descriptor()
                        + ": absent from the real class (NoSuchMethodError at config harvest)");
            } else if ((m.access() & ClassfileSurface.ACC_ABSTRACT) == 0
                    && (realAccess & ClassfileSurface.ACC_ABSTRACT) != 0) {
                problems.add(stub.name() + "." + m.name()
                        + ": stub default/concrete but REAL is abstract (AbstractMethodError"
                        + " — the walker inherits, never overrides)");
            }
        }
        if (isEnum) {
            Set<String> realFields = new HashSet<>();
            for (ClassfileSurface.Member f : real.fields()) {
                realFields.add(f.name());
            }
            for (ClassfileSurface.Member f : stub.fields()) {
                if ((f.access() & ClassfileSurface.ACC_ENUM) != 0 && !realFields.contains(f.name())) {
                    problems.add(stub.name() + "." + f.name()
                            + ": enum constant absent from the real enum (valueOf throws)");
                }
            }
        }
    }

    /** All compiled stub classfiles, walked from the classes dir the test classpath
     *  serves them from — a NEW stub file is covered without a list edit. */
    private static List<Path> stubClassFiles() throws Exception {
        var url = SodiumNeoGoldenParityTest.class.getResource("/" + PROBE);
        assertNotNull(url, "the stub classes must be on the test classpath");
        assertEquals("file", url.getProtocol(),
                "main classes must be a directory on the test classpath, got " + url);
        Path root = Path.of(url.toURI());
        for (int i = PROBE.split("/").length; i > 0; i--) {
            root = root.getParent();
        }
        try (var files = Files.walk(root.resolve("net/caffeinemc"))) {
            return files.filter(f -> f.toString().endsWith(".class")).toList();
        }
    }

    /** The fabric SodiumLegacySurfaceResolvesTest golden discipline: offline boxes skip
     *  (assumption); under CI=true a missing jar FAILS — a mistyped coordinate must not
     *  void the golden arm silently. */
    private static Path goldenJar() {
        String jarPath = System.getProperty("lss.sodiumNeoGoldenJar", "");
        boolean present = !jarPath.isBlank() && Files.isRegularFile(Path.of(jarPath));
        boolean expected = "true".equals(System.getProperty("lss.sodiumNeoGoldenExpected", "false"));
        if ("true".equals(System.getenv("CI")) && expected) {
            assertTrue(present, "CI must resolve lss.sodiumNeoGoldenJar (derived from"
                    + " gradle.properties sodium_version) — a mistyped coordinate silently"
                    + " voids the golden arm");
        }
        Assumptions.assumeTrue(present,
                "lss.sodiumNeoGoldenJar not resolved (offline?) — skipping the stub-parity check");
        return Path.of(jarPath);
    }

    /** The zip to read real classes from: the jar itself when it carries the probe class,
     *  else the first META-INF/jarjar nested jar that does (sodium-neoforge nests its mod
     *  jar; extracted to a temp file — ZipFile needs a real file). */
    private static ZipFile openGolden(Path jar) throws IOException {
        try (ZipFile outer = new ZipFile(jar.toFile())) {
            if (outer.getEntry(PROBE) != null) {
                return new ZipFile(jar.toFile());
            }
            for (ZipEntry e : Collections.list(outer.entries())) {
                if (!e.getName().startsWith("META-INF/jarjar/") || !e.getName().endsWith(".jar")) {
                    continue;
                }
                Path tmp = Files.createTempFile("sodium-neo-golden-nested", ".jar");
                tmp.toFile().deleteOnExit();
                try (InputStream in = outer.getInputStream(e)) {
                    Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
                }
                ZipFile nested = new ZipFile(tmp.toFile());
                if (nested.getEntry(PROBE) != null) {
                    return nested;
                }
                nested.close();
            }
        }
        return new ZipFile(jar.toFile());
    }
}
