package dev.vox.lss.neoforge;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal classfile reader for the Sodium config-API contract tests: constant pool,
 * class access + name, interface list, field table, method table. Exists because the
 * walker and its compile-only {@code net.caffeinemc} stubs CANNOT be reflected on in
 * this MC-free JUnit JVM — any member access links the class, and verification resolves
 * MC types ({@code Component}, {@code ResourceLocation}) that are not on the test
 * classpath. Reading the classfile directly needs no linking and sees exactly the
 * descriptors the runtime linker will resolve. (The repo pattern: the Paper module's
 * FoliaWiringContractTest walks constant pools the same way.)
 */
final class ClassfileSurface {

    static final int ACC_PUBLIC = 0x0001;
    static final int ACC_PRIVATE = 0x0002;
    static final int ACC_INTERFACE = 0x0200;
    static final int ACC_ABSTRACT = 0x0400;
    static final int ACC_SYNTHETIC = 0x1000;
    static final int ACC_ENUM = 0x4000;

    record Member(int access, String name, String descriptor) {}

    record Surface(int access, String name, List<String> interfaces,
                   List<Member> fields, List<Member> methods) {}

    private ClassfileSurface() {}

    static Surface parse(InputStream raw) throws IOException {
        var in = new DataInputStream(raw);
        if (in.readInt() != 0xCAFEBABE) throw new IOException("not a class file");
        in.skipNBytes(4); // minor + major
        int cpCount = in.readUnsignedShort();
        String[] utf8 = new String[cpCount];
        int[] classNameIdx = new int[cpCount];
        for (int i = 1; i < cpCount; i++) {
            int tag = in.readUnsignedByte();
            switch (tag) {
                case 1 -> utf8[i] = in.readUTF();
                case 7 -> classNameIdx[i] = in.readUnsignedShort();
                case 8, 16, 19, 20 -> in.skipNBytes(2);
                case 15 -> in.skipNBytes(3);
                case 3, 4, 9, 10, 11, 12, 17, 18 -> in.skipNBytes(4);
                case 5, 6 -> { in.skipNBytes(8); i++; } // long/double take two cp slots
                default -> throw new IOException("unknown constant-pool tag " + tag);
            }
        }
        int access = in.readUnsignedShort();
        String name = utf8[classNameIdx[in.readUnsignedShort()]];
        in.skipNBytes(2); // super_class
        int ifaceCount = in.readUnsignedShort();
        var interfaces = new ArrayList<String>(ifaceCount);
        for (int i = 0; i < ifaceCount; i++) {
            interfaces.add(utf8[classNameIdx[in.readUnsignedShort()]]);
        }
        return new Surface(access, name, interfaces, readMembers(in, utf8), readMembers(in, utf8));
    }

    private static List<Member> readMembers(DataInputStream in, String[] utf8) throws IOException {
        int count = in.readUnsignedShort();
        var out = new ArrayList<Member>(count);
        for (int i = 0; i < count; i++) {
            int access = in.readUnsignedShort();
            String name = utf8[in.readUnsignedShort()];
            String descriptor = utf8[in.readUnsignedShort()];
            skipAttributes(in);
            out.add(new Member(access, name, descriptor));
        }
        return out;
    }

    private static void skipAttributes(DataInputStream in) throws IOException {
        int count = in.readUnsignedShort();
        for (int i = 0; i < count; i++) {
            in.skipNBytes(2);
            in.skipNBytes(Integer.toUnsignedLong(in.readInt()));
        }
    }
}
