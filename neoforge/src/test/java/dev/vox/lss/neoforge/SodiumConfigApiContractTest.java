package dev.vox.lss.neoforge;

import org.junit.jupiter.api.Test;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the Sodium 0.8+ options-page wiring on NeoForge (LSSConfigMenu's javadoc):
 * sodium-neoforge's ConfigLoaderForge discovers config users by reading the
 * {@code sodium:config_api_user} MODPROPERTY from neoforge.mods.toml and
 * reflectively instantiating the named class as a {@code ConfigEntryPoint}. Three
 * legs, each guarding a distinct silent-failure mode: a dropped TOML property
 * (page silently absent), a renamed walker class (ConfigLoaderForge crashes the
 * config harvest), and a walker that stops implementing the interface or loses
 * its no-arg constructor (instantiation fails at Sodium's feet). The stubs
 * themselves must never SHIP — release_check's NEOFORGE_FORBIDDEN pins
 * {@code net/caffeinemc/} out of the jars.
 */
class SodiumConfigApiContractTest {

    private static final String WALKER = "dev.vox.lss.config.LSSConfigMenu";
    private static final String ENTRY_POINT_IFACE = "net.caffeinemc.mods.sodium.api.config.ConfigEntryPoint";

    @Test
    void theTomlDeclaresTheWalkerAsTheConfigApiUser() throws Exception {
        try (InputStream in = SodiumConfigApiContractTest.class
                .getResourceAsStream("/META-INF/neoforge.mods.toml")) {
            assertNotNull(in, "neoforge.mods.toml on the test classpath");
            String toml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(toml.contains("[modproperties.lss]"),
                    "the modproperties table must exist — ConfigLoaderForge reads it");
            assertTrue(toml.contains("\"sodium:config_api_user\"=\"" + WALKER + "\""),
                    "the property must name the walker verbatim");
        }
    }

    @Test
    void theWalkerImplementsTheEntryPointWithANoArgConstructor() throws Exception {
        // The walker cannot be REFLECTED on here: any member access links the class,
        // and verification resolves the MC types (Component, ResourceLocation) its
        // method bodies reference — absent from this plain-JUnit classpath. So the
        // two walker pins are read straight off the classfile (the repo's
        // FoliaWiringContractTest pattern): the interfaces list and the ctor table
        // need no linking and cannot drift from what ConfigLoaderForge will see.
        ParsedClass walker = parse("/" + WALKER.replace('.', '/') + ".class");
        assertTrue(walker.interfaces().contains(ENTRY_POINT_IFACE.replace('.', '/')),
                "LSSConfigMenu must implement " + ENTRY_POINT_IFACE
                        + " (the stub mirrors the real interface; the runtime link is by name)");
        assertTrue(walker.methods().stream().anyMatch(m ->
                        m.name().equals("<init>") && m.descriptor().equals("()V")
                                && (m.access() & 0x0001) != 0),
                "ConfigLoaderForge instantiates reflectively — public no-arg ctor required");
        // The stub interface must carry BOTH methods with the real shapes: the abstract
        // late hook (we implement it) and the default early hook (we inherit it). The
        // stubs reference only stub types, so reflection is safe on THEM.
        Class<?> iface = Class.forName(ENTRY_POINT_IFACE);
        assertEquals(2, iface.getMethods().length, "exactly the two entry-point methods");
        assertTrue(iface.getMethod("registerConfigEarly",
                        Class.forName("net.caffeinemc.mods.sodium.api.config.structure.ConfigBuilder"))
                .isDefault(), "registerConfigEarly stays a default method");
    }

    private record ParsedMethod(int access, String name, String descriptor) {}
    private record ParsedClass(List<String> interfaces, List<ParsedMethod> methods) {}

    /** Minimal classfile read: constant pool, interface list, method table. */
    private static ParsedClass parse(String resource) throws IOException {
        try (InputStream raw = SodiumConfigApiContractTest.class.getResourceAsStream(resource)) {
            assertNotNull(raw, resource + " on the test classpath");
            var in = new DataInputStream(raw);
            if (in.readInt() != 0xCAFEBABE) throw new IOException("not a class file: " + resource);
            in.readInt(); // minor+major
            int cpCount = in.readUnsignedShort();
            String[] utf8 = new String[cpCount];
            int[] classNameIdx = new int[cpCount];
            for (int i = 1; i < cpCount; i++) {
                int tag = in.readUnsignedByte();
                switch (tag) {
                    case 1 -> utf8[i] = in.readUTF();
                    case 7 -> classNameIdx[i] = in.readUnsignedShort();
                    case 8, 16, 19, 20 -> in.skipBytes(2);
                    case 15 -> in.skipBytes(3);
                    case 3, 4, 9, 10, 11, 12, 17, 18 -> in.skipBytes(4);
                    case 5, 6 -> { in.skipBytes(8); i++; }
                    default -> throw new IOException("unknown cp tag " + tag);
                }
            }
            in.skipBytes(6); // access, this_class, super_class
            int ifaceCount = in.readUnsignedShort();
            var interfaces = new ArrayList<String>();
            for (int i = 0; i < ifaceCount; i++) {
                interfaces.add(utf8[classNameIdx[in.readUnsignedShort()]]);
            }
            int fieldCount = in.readUnsignedShort();
            for (int i = 0; i < fieldCount; i++) {
                in.skipBytes(6);
                skipAttributes(in);
            }
            int methodCount = in.readUnsignedShort();
            var methods = new ArrayList<ParsedMethod>();
            for (int i = 0; i < methodCount; i++) {
                int access = in.readUnsignedShort();
                String name = utf8[in.readUnsignedShort()];
                String descriptor = utf8[in.readUnsignedShort()];
                skipAttributes(in);
                methods.add(new ParsedMethod(access, name, descriptor));
            }
            return new ParsedClass(interfaces, methods);
        }
    }

    private static void skipAttributes(DataInputStream in) throws IOException {
        int count = in.readUnsignedShort();
        for (int i = 0; i < count; i++) {
            in.skipBytes(2);
            in.skipBytes(in.readInt());
        }
    }
}
