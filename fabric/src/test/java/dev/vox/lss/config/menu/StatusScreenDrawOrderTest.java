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
                    if (!name.equals("extractRenderState")) return null;
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
    @Test void feedbackUsesThisLinesClientSystemMessageHelper() throws Exception {
        try (var input = getClass().getClassLoader().getResourceAsStream("dev/vox/lss/networking/client/ClientStatusScreen.class")) {
            assertNotNull(input);
            var calls = new ArrayList<String>();
            new ClassReader(input).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override public MethodVisitor visitMethod(int access,String name,String descriptor,String signature,String[] exceptions) {
                    if (!name.equals("feedback")) return null;
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override public void visitMethodInsn(int opcode,String owner,String method,String descriptor,boolean iface) {
                            if (owner.equals("net/minecraft/client/gui/components/ChatComponent")) calls.add(method + descriptor);
                        }
                    };
                }
            },0);
            assertEquals(java.util.List.of("addClientSystemMessage(Lnet/minecraft/network/chat/Component;)V"), calls);
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
    @Test void statusTextUsesOpaqueArgbOnTheNativeTextSubmissionApi() throws Exception {
        try (var input = getClass().getClassLoader().getResourceAsStream("dev/vox/lss/networking/client/ClientStatusScreen.class")) {
            assertNotNull(input);
            var node = new org.objectweb.asm.tree.ClassNode();
            new ClassReader(input).accept(node, 0);
            int textCalls = 0;
            for (var method : node.methods) {
                if (!method.name.equals("render") && !method.name.equals("extractRenderState")) continue;
                for (var instruction : method.instructions) {
                    if (!(instruction instanceof org.objectweb.asm.tree.MethodInsnNode call)
                            || !call.owner.startsWith("net/minecraft/client/gui/GuiGraphics")
                            || !java.util.Set.of("drawString", "drawCenteredString", "text", "centeredText").contains(call.name)) continue;
                    var color = call.getPrevious();
                    while (color != null && color.getOpcode() < 0) color = color.getPrevious();
                    Integer argb = color instanceof org.objectweb.asm.tree.LdcInsnNode literal && literal.cst instanceof Integer value
                            ? value : color != null && color.getOpcode() == Opcodes.ICONST_M1 ? -1 : null;
                    assertNotNull(argb, "text call must explicitly supply its opaque ARGB color");
                    assertEquals(255, argb >>> 24, "native text submission skips zero-alpha RGB colors");
                    textCalls++;
                }
            }
            assertEquals(3, textCalls, "title, body and page footer all require opaque alpha");
        }
    }
}
