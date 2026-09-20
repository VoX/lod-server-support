package dev.vox.lssfixture.timing;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.objectweb.asm.*;

/** Exact-descriptor Folia26.2-only observer. No blocking or product state changes. */
public final class TimingAgent {
    private static final String TARGET = "io/papermc/paper/threadedregions/TickRegions$ConcreteRegionTickHandle";
    private static final String RECORDER = "dev/vox/lssfixture/timing/TimingRecorder";
    private static final String REGION = "io/papermc/paper/threadedregions/ThreadedRegionizer$ThreadedRegion";
    public static void premain(String ignored, Instrumentation instrumentation) {
        try {
            // Paperclip's server loader does not delegate to the application
            // agent loader. The observer is MC-free and belongs in bootstrap.
            instrumentation.appendToBootstrapClassLoaderSearch(new java.util.jar.JarFile(
                new java.io.File(System.getProperty("lss.rig.observerJar"))));
        } catch (Exception error) { throw new IllegalStateException("cannot install isolated observer", error); }
        TimingRecorder.start();
        instrumentation.addTransformer(new ClassFileTransformer() {
            @Override public byte[] transform(ClassLoader loader, String name, Class<?> type,
                    java.security.ProtectionDomain domain, byte[] bytes) {
                if (!TARGET.equals(name)) return null;
                try {
                    String actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
                    if (!actual.equals(System.getProperty("lss.rig.foliaClassSha256"))) {
                        TimingRecorder.failure("unexpected_target_bytecode"); return null;
                    }
                    ClassReader reader = new ClassReader(bytes);
                    ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
                    int[] methods = {0};
                    reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
                        @Override public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
                            MethodVisitor base = super.visitMethod(access, name, desc, signature, exceptions);
                            if (name.equals("tickRegion") && desc.equals("(JJJ)V")) {
                                methods[0]++;
                                return new MethodVisitor(Opcodes.ASM9, base) {
                                    @Override public void visitCode() {
                                        super.visitCode();
                                        mv.visitMethodInsn(Opcodes.INVOKESTATIC,"io/papermc/paper/threadedregions/TickRegionScheduler","getCurrentRegion","()L"+REGION+";",false);
                                        mv.visitFieldInsn(Opcodes.GETFIELD,REGION,"id","J");
                                        mv.visitMethodInsn(Opcodes.INVOKESTATIC,RECORDER,"begin","(J)V",false);
                                    }
                                    @Override public void visitInsn(int opcode) {
                                        if (opcode == Opcodes.RETURN || opcode == Opcodes.ATHROW) {
                                            mv.visitInsn(opcode == Opcodes.ATHROW ? Opcodes.ICONST_1 : Opcodes.ICONST_0);
                                            mv.visitMethodInsn(Opcodes.INVOKESTATIC,RECORDER,"end","(Z)V",false);
                                        }
                                        super.visitInsn(opcode);
                                    }
                                };
                            }
                            if (name.equals("addTickTime") && desc.equals("(Lca/spottedleaf/common/time/TickTime;)V")) {
                                methods[0]++;
                                return new MethodVisitor(Opcodes.ASM9, base) {
                                    @Override public void visitCode() {
                                        super.visitCode();
                                        mv.visitMethodInsn(Opcodes.INVOKESTATIC,"io/papermc/paper/threadedregions/TickRegionScheduler","getCurrentRegion","()L"+REGION+";",false);
                                        mv.visitFieldInsn(Opcodes.GETFIELD,REGION,"id","J");
                                        for (String getter : new String[]{"scheduledTickStart","tickStart","tickEnd"}) {
                                            mv.visitVarInsn(Opcodes.ALOAD,1);
                                            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,"ca/spottedleaf/common/time/TickTime",getter,"()J",false);
                                        }
                                        mv.visitMethodInsn(Opcodes.INVOKESTATIC,RECORDER,"metric","(JJJJ)V",false);
                                    }
                                };
                            }
                            return base;
                        }
                    },0);
                    if (methods[0] != 2) { TimingRecorder.failure("missing_method_descriptor"); return null; }
                    TimingRecorder.applied(actual);
                    return writer.toByteArray();
                } catch (Exception e) { TimingRecorder.failure("transform_failed"); return null; }
            }
        });
    }
}
