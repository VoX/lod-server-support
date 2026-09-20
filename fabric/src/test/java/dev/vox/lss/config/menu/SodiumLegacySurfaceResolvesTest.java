package dev.vox.lss.config.menu;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The review-scoped "golden arm" (sodium-options-page-generations-plan.md §4): every
 * member the LEGACY STACK binds by name ({@link LegacySodiumPage#SURFACE} — the
 * reflective builder, the constructor hook's shadowed field + ctor, the deep-link's
 * {@code createScreen}/{@code currentPage}) is checked against the REAL Sodium 0.6/0.7
 * bytecode, and every member the MODERN deep-link binds
 * ({@link SodiumConfigScreens#MODERN_SURFACE}) against the line's own 0.8+ artifact —
 * both read as zips with ASM from the jars Gradle's plain {@code sodiumLegacyGolden} /
 * {@code sodiumModernGolden} configurations resolved (never on any classpath: the stub
 * packages would shadow them, and a Sodium jar on the T1 runtime would register as a
 * mod). Descriptor-agnostic on purpose (MC type names differ per line) — except the one
 * overload that matters: 0.7 has TWO {@code setTooltip/1}s and the resolver must pick
 * the {@code Component} one, so the non-JDK-parameter overload's existence is asserted
 * here and the preference itself in {@code LegacySodiumPageTest}.
 *
 * <p>Modrinth's NeoForge artifacts can be jarjar WRAPPERS (the 1.21.10 0.7.3 build: the
 * mod's classes sit in {@code META-INF/jarjar/<mod>.jar}, the outer jar holds only
 * metadata + nested libraries) — {@link #openGolden} descends into the nested jar that
 * carries the caffeine prefix, so the check reads real classes on every artifact shape.
 *
 * <p>Offline boxes skip (assumption); under {@code CI=true} a missing jar FAILS — a
 * mistyped coordinate must never void the golden arm silently (review). The modern arm
 * is only expected where the line pins a 0.8+ artifact ({@code lss.sodiumModernGoldenExpected}).
 */
class SodiumLegacySurfaceResolvesTest {

    @Test
    void everyLegacySurfaceMemberExistsInTheRealJar() throws IOException {
        Path jar = goldenJar("lss.sodiumLegacyGoldenJar", true);
        String prefix = SodiumGeneration.CAFFEINE_PREFIX.replace('.', '/');
        List<String> missing = new ArrayList<>();
        try (ZipFile zip = openGolden(jar, SodiumGeneration.resourceOf(
                SodiumGeneration.CAFFEINE_PREFIX + SodiumGeneration.LEGACY_SCREEN_SUFFIX))) {
            Map<String, ClassNode> nodes = new HashMap<>();
            check(zip, prefix, LegacySodiumPage.SURFACE, nodes, missing);
            // The 0.7 overload hazard: a second setTooltip/1 whose parameter is NOT a JDK
            // type (Function<T,Component>) — if the jar has such an overload, the resolver's
            // Component preference is what keeps the page alive; pin its existence so the
            // preference test's premise is real on this jar.
            ClassNode builder = nodes.get(LegacySodiumPage.OPTION_IMPL_BUILDER);
            assertTrue(builder != null && builder.methods.stream().anyMatch(m -> m.name.equals("setTooltip")
                            && Type.getArgumentTypes(m.desc).length == 1
                            && !Type.getArgumentTypes(m.desc)[0].getInternalName().startsWith("java/")),
                    "OptionImpl$Builder must carry a setTooltip/1 whose parameter is the (non-JDK) Component"
                            + " type — the resolver prefers it over 0.7's Function overload");
        }
        assertTrue(missing.isEmpty(), "LegacySodiumPage.SURFACE members absent from " + jar + ": " + missing);
    }

    @Test
    void everyModernDeepLinkMemberExistsInTheLinesSodium() throws IOException {
        Path jar = goldenJar("lss.sodiumModernGoldenJar",
                "true".equals(System.getProperty("lss.sodiumModernGoldenExpected", "false")));
        String prefix = SodiumGeneration.CAFFEINE_PREFIX.replace('.', '/');
        List<String> missing = new ArrayList<>();
        try (ZipFile zip = openGolden(jar, SodiumGeneration.resourceOf(SodiumGeneration.MODERN_ENTRY_POINT))) {
            check(zip, prefix, SodiumConfigScreens.MODERN_SURFACE, new HashMap<>(), missing);
            // and the probe's own premise: the public config API entry point is there
            if (zip.getEntry(SodiumGeneration.resourceOf(SodiumGeneration.MODERN_ENTRY_POINT)) == null) {
                missing.add(SodiumGeneration.MODERN_ENTRY_POINT + " (the MODERN probe resource)");
            }
        }
        assertTrue(missing.isEmpty(), "SodiumConfigScreens.MODERN_SURFACE members absent from " + jar + ": " + missing);
    }

    /**
     * The zip to read classes from: the jar itself when it carries the generation's
     * probe class ({@code SodiumOptionsGUI} / the 0.8 {@code ConfigEntryPoint} — a bare
     * package-prefix test is not enough: the 0.7.3 NeoForge wrapper ships a few
     * {@code net/caffeinemc/mods/sodium} entries flat and the client classes nested),
     * else the first {@code META-INF/jarjar/*.jar} nested inside it that does (extracted
     * to a temp file — {@link ZipFile} needs a real file). A jar with neither is
     * returned as-is so every member reports missing, naming the jar.
     */
    private static ZipFile openGolden(Path jar, String probeEntry) throws IOException {
        try (ZipFile outer = new ZipFile(jar.toFile())) {
            if (outer.getEntry(probeEntry) != null) {
                return new ZipFile(jar.toFile());
            }
            for (ZipEntry e : Collections.list(outer.entries())) {
                if (!e.getName().startsWith("META-INF/jarjar/") || !e.getName().endsWith(".jar")) {
                    continue;
                }
                Path tmp = Files.createTempFile("sodium-golden-nested", ".jar");
                tmp.toFile().deleteOnExit();
                try (InputStream in = outer.getInputStream(e)) {
                    Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
                }
                ZipFile nested = new ZipFile(tmp.toFile());
                if (nested.getEntry(probeEntry) != null) {
                    return nested;
                }
                nested.close();
            }
        }
        return new ZipFile(jar.toFile());
    }

    @Test
    void legacyStatusReturnMembersExistInActualSodium() throws IOException {
        Path jar = goldenJar("lss.sodiumLegacyGoldenJar", true);
        try (ZipFile zip = openGolden(jar, "net/caffeinemc/mods/sodium/client/gui/SodiumOptionsGUI.class")) {
            statusMethods(zip, "client/gui/SodiumOptionsGUI", "getAllOptions");
            statusMethods(zip, "client/gui/options/OptionImpl", "getValue", "hasChanged", "reset", "setValue", "getStorage", "getName");
            statusMethods(zip, "client/gui/options/storage/OptionStorage", "getData");
        }
    }

    @Test
    void modernStatusReturnMembersExistInActualSodium() throws IOException {
        Path jar = goldenJar("lss.sodiumModernGoldenJar",
                "true".equals(System.getProperty("lss.sodiumModernGoldenExpected", "false")));
        try (ZipFile zip = openGolden(jar, "net/caffeinemc/mods/sodium/api/config/ConfigEntryPoint.class")) {
            statusMethods(zip, "client/config/structure/StatefulOption", "getValidatedValue", "hasChanged", "resetFromBinding", "modifyValue");
            for (String[] field : List.of(new String[]{"client/config/ConfigManager", "CONFIG"}, new String[]{"client/config/structure/Config", "options"})) {
                ClassNode node = read(zip, "net/caffeinemc/mods/sodium/" + field[0] + ".class");
                assertTrue(node != null && node.fields.stream().anyMatch(f -> f.name.equals(field[1])), field[0] + "#" + field[1]);
            }
        }
    }

    private static void statusMethods(ZipFile zip, String owner, String... names) {
        ClassNode node = read(zip, "net/caffeinemc/mods/sodium/" + owner + ".class");
        for (String name : names) assertTrue(node != null && node.methods.stream().anyMatch(m -> m.name.equals(name)), owner + "#" + name);
    }

    private static Path goldenJar(String property, boolean expected) {
        String jarPath = System.getProperty(property, "");
        boolean present = !jarPath.isBlank() && Files.isRegularFile(Path.of(jarPath));
        if ("true".equals(System.getenv("CI")) && expected) {
            assertTrue(present, "CI must resolve " + property + " (gradle.properties) — a mistyped"
                    + " coordinate silently voids the golden arm");
        }
        Assumptions.assumeTrue(present, property + " not resolved (offline?) — skipping the real-bytecode check");
        return Path.of(jarPath);
    }

    private static void check(ZipFile zip, String prefix, List<LegacySodiumPage.Member> surface,
                              Map<String, ClassNode> nodes, List<String> missing) {
        Set<String> impactNames = Arrays.stream(Impact.values()).map(Enum::name).collect(Collectors.toSet());
        for (LegacySodiumPage.Member m : surface) {
            ClassNode node = nodes.computeIfAbsent(m.owner(), owner -> read(zip, prefix + owner.replace('.', '/') + ".class"));
            if (node == null) {
                missing.add(m.owner() + " (class)");
                continue;
            }
            boolean ok = switch (m.kind()) {
                case STATIC_METHOD -> hasMethod(node, m.name(), m.arity(), true);
                case METHOD -> hasMethod(node, m.name(), m.arity(), false);
                case CONSTRUCTOR -> hasMethod(node, "<init>", m.arity(), false);
                case INTERFACE_METHOD -> (node.access & Opcodes.ACC_INTERFACE) != 0
                        && hasMethod(node, m.name(), m.arity(), false);
                // An enum row checks the constants the catalog's Impact maps BY NAME (a renamed
                // Sodium constant would throw at valueOf → no page).
                case ENUM -> (node.access & Opcodes.ACC_ENUM) != 0
                        && node.fields.stream().map(f -> f.name).collect(Collectors.toSet()).containsAll(impactNames);
                case FIELD -> node.fields.stream().anyMatch(f -> f.name.equals(m.name()));
            };
            if (!ok) {
                missing.add(m.owner() + "#" + m.name() + "/" + m.arity() + " (" + m.kind() + ")");
            }
        }
    }

    private static ClassNode read(ZipFile zip, String entryName) {
        ZipEntry e = zip.getEntry(entryName);
        if (e == null) {
            return null;
        }
        try (InputStream in = zip.getInputStream(e)) {
            ClassNode n = new ClassNode();
            new ClassReader(in.readAllBytes()).accept(n, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG);
            return n;
        } catch (IOException ex) {
            throw new java.io.UncheckedIOException(ex);
        }
    }

    private static boolean hasMethod(ClassNode node, String name, int arity, boolean isStatic) {
        for (MethodNode m : node.methods) {
            if (!m.name.equals(name) || Type.getArgumentTypes(m.desc).length != arity) {
                continue;
            }
            if (((m.access & Opcodes.ACC_STATIC) != 0) != isStatic) {
                continue;
            }
            if ((m.access & Opcodes.ACC_PUBLIC) == 0 && !name.equals("<init>")) {
                continue;
            }
            return true;
        }
        return false;
    }
}
