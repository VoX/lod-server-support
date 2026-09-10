package dev.vox.lss.config.menu;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import java.util.ArrayList;
import static org.junit.jupiter.api.Assertions.*;
/** Pins the real 1.21.1 Screen background-before-widgets contract: foreground must follow super. */
class StatusScreenDrawOrderTest {
    @Test void foregroundIsDrawnAfterBaseScreenAndItsBackground() throws Exception {
        try (var input = getClass().getClassLoader().getResourceAsStream("dev/vox/lss/networking/client/ClientStatusScreen.class")) {
            assertNotNull(input);
            var calls = new ArrayList<String>();
            new ClassReader(input).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override public MethodVisitor visitMethod(int access,String name,String descriptor,String signature,String[] exceptions) {
                    if (!name.equals("render")) return null;
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override public void visitMethodInsn(int opcode,String owner,String method,String descriptor,boolean iface) {
                            if (opcode == Opcodes.INVOKESPECIAL && owner.equals("net/minecraft/client/gui/screens/Screen")) calls.add("base");
                            if (owner.startsWith("net/minecraft/client/gui/GuiGraphics")
                                    && java.util.Set.of("drawString","drawCenteredString","text","centeredText").contains(method)) calls.add("text");
                        }
                    };
                }
            },0);
            assertTrue(calls.contains("base") && calls.contains("text"));
            assertTrue(calls.indexOf("base") < calls.indexOf("text"), "base render may draw/blur background before widgets");
        }
    }
    @Test void escapeReturnsOnReleaseSoTheParentCannotCloseOnThatSameKeyPair() throws Exception {
        try (var input = getClass().getClassLoader().getResourceAsStream("dev/vox/lss/networking/client/ClientStatusScreen.class")) {
            assertNotNull(input);
            var calls = new java.util.HashMap<String, java.util.List<String>>();
            new ClassReader(input).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                    if (!name.equals("keyPressed") && !name.equals("keyReleased")) return null;
                    var events = new ArrayList<String>();
                    calls.put(name, events);
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override public void visitMethodInsn(int opcode, String owner, String method, String descriptor, boolean iface) {
                            if (owner.endsWith("/ScreenEscapeRelease")) events.add(method);
                            if (method.equals("onClose")) events.add("close");
                        }
                    };
                }
            }, 0);
            assertEquals(java.util.List.of("press"), calls.get("keyPressed"));
            assertEquals(java.util.List.of("release", "close"), calls.get("keyReleased"));
        }
    }
}
