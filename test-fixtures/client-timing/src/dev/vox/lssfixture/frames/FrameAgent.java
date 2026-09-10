package dev.vox.lssfixture.frames;

import java.lang.instrument.*;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.objectweb.asm.*;

/** Frame start-to-start observer; target class/method/descriptors are profile pins. */
public final class FrameAgent {
    public static void premain(String ignored, Instrumentation instrumentation) throws Exception {
        instrumentation.appendToBootstrapClassLoaderSearch(new java.util.jar.JarFile(System.getProperty("lss.rig.frameObserverJar")));
        FrameRecorder.start();
        instrumentation.addTransformer(new ClassFileTransformer() {
            @Override public byte[] transform(ClassLoader loader,String name,Class<?> type,java.security.ProtectionDomain domain,byte[] bytes) {
                if (!java.util.Objects.equals(name,System.getProperty("lss.rig.frameClass"))) return null;
                try {
                    String checksum=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
                    if (!checksum.equals(System.getProperty("lss.rig.frameClassSha256"))) {
                        FrameRecorder.event("unverified_frame_class",checksum);return null;
                    }
                    ClassReader reader=new ClassReader(bytes);ClassWriter writer=new ClassWriter(reader,ClassWriter.COMPUTE_MAXS);
                    int[] found={0};
                    reader.accept(new ClassVisitor(Opcodes.ASM9,writer) {
                        @Override public MethodVisitor visitMethod(int access,String name,String descriptor,String signature,String[] exceptions) {
                            MethodVisitor delegate=super.visitMethod(access,name,descriptor,signature,exceptions);
                            if (!name.equals(System.getProperty("lss.rig.frameMethod")) || !descriptor.equals(System.getProperty("lss.rig.frameDescriptor"))) return delegate;
                            found[0]++;
                            return new MethodVisitor(Opcodes.ASM9,delegate) {
                                @Override public void visitCode() {
                                    super.visitCode();mv.visitMethodInsn(Opcodes.INVOKESTATIC,"dev/vox/lssfixture/frames/FrameRecorder","frame","()V",false);
                                }
                            };
                        }
                    },0);
                    if(found[0]!=1){FrameRecorder.event("missing_frame_descriptor",checksum);return null;}
                    FrameRecorder.event("frame_transform_applied",checksum);return writer.toByteArray();
                }catch(Exception failure){FrameRecorder.event("frame_transform_failed","");return null;}
            }
        });
    }
}
