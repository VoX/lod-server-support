package dev.vox.lss.config.menu;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import java.util.ArrayList;
import static org.junit.jupiter.api.Assertions.*;

/** Every native screen dialect must rebase the parent's cached binding before displaying it. */
class StatusScreenReturnBindingTest {
    @Test void closingRefreshesTheCapturedBindingBeforeShowingTheParent() throws Exception {
        try (var input = getClass().getClassLoader().getResourceAsStream("dev/vox/lss/networking/client/ClientStatusScreen.class")) {
            assertNotNull(input);
            var calls = new ArrayList<String>();
            new ClassReader(input).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                    if (!name.equals("onClose") || !descriptor.equals("()V")) return null;
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
                            if (opcode == Opcodes.GETFIELD && owner.equals("dev/vox/lss/networking/client/ClientStatusScreen")
                                    && name.equals("onReturn") && descriptor.equals("Ljava/lang/Runnable;")) calls.add("captured binding");
                        }
                        @Override public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean iface) {
                            if (owner.equals("java/lang/Runnable") && name.equals("run") && descriptor.equals("()V")) calls.add("refresh");
                            if (owner.equals("net/minecraft/client/Minecraft") && java.util.Set.of("setScreen", "setScreenAndShow").contains(name)) calls.add("show parent");
                        }
                    };
                }
            }, 0);
            assertEquals(java.util.List.of("captured binding", "refresh", "show parent"), calls,
                    "native parent widgets must observe the refreshed binding before the screen is shown");
        }
    }
}
