package dev.vox.lss.diagnostics;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import static org.junit.jupiter.api.Assertions.*;

/** Checks real compiled closures without linking or constructing native command recipients. */
class ServerExportLifetimeWiringTest {
    private static ClassNode read(String name) throws Exception {
        try (var input = ServerExportLifetimeWiringTest.class.getClassLoader().getResourceAsStream(name + ".class")) {
            assertNotNull(input); var node = new ClassNode(); new ClassReader(input).accept(node, 0); return node;
        }
    }
    private static void commandBoundary(ClassNode node) {
        var method = node.methods.stream().filter(m -> m.name.equals("exportDiagnostics")).findFirst().orElseThrow();
        boolean admitted = false, acknowledged = false;
        for (var insn : method.instructions) {
            if (insn instanceof MethodInsnNode call) {
                assertFalse(call.owner.startsWith("java/util/concurrent/"), "command must not attach asynchronous work");
                assertFalse(call.name.equals("getServer") || call.name.equals("execute"));
                if (call.name.equals("submitServer")) admitted = true;
                if (call.name.equals("target")) { assertTrue(admitted); acknowledged = true; }
            }
            if (insn instanceof InvokeDynamicInsnNode indy && indy.bsm.getOwner().equals("java/lang/invoke/LambdaMetafactory"))
                for (Type argument : Type.getArgumentTypes(indy.desc))
                    assertEquals("Ljava/lang/String;", argument.getDescriptor(), "only the synchronous immutable text supplier is permitted");
        }
        assertTrue(admitted && acknowledged);
        assertTrue(method.tryCatchBlocks.stream().anyMatch(h -> "java/util/concurrent/RejectedExecutionException".equals(h.type)));
    }
    @Test void actualCommandHasNoAsyncRequesterCapture() throws Exception {
        commandBoundary(read("dev/vox/lss/networking/server/LSSServerCommands"));
    }
    @Test void oldSourceOrSenderCapturingCompletionIsRejected() throws Exception {
        var node = read("dev/vox/lss/networking/server/LSSServerCommands");
        var method = node.methods.stream().filter(m -> m.name.equals("exportDiagnostics")).findFirst().orElseThrow();
        method.instructions.insert(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/util/concurrent/CompletableFuture",
                "whenComplete", "(Ljava/util/function/BiConsumer;)Ljava/util/concurrent/CompletableFuture;", false));
        assertThrows(AssertionError.class, () -> commandBoundary(node));
    }
    private static void workerBoundary(ClassNode node) {
        int completionRefs = 0, workers = 0;
        for (var method : node.methods) {
            if (method.name.startsWith("lambda$enqueueServer$")) {
                workers++;
                assertEquals("(Ljava/nio/file/Path;[BLjava/lang/String;)Ljava/nio/file/Path;", method.desc);
                assertTrue((method.access & Opcodes.ACC_STATIC) != 0);
            }
            if (method.name.equals("submitServer")) for (var insn : method.instructions)
                if (insn instanceof InvokeDynamicInsnNode indy && indy.bsm.getOwner().equals("java/lang/invoke/LambdaMetafactory")) {
                    completionRefs++;
                    assertEquals(0, Type.getArgumentTypes(indy.desc).length);
                    var implementation = (Handle) indy.bsmArgs[1];
                    assertEquals(Opcodes.H_INVOKESTATIC, implementation.getTag());
                    assertEquals("logServerCompletion", implementation.getName());
                    assertEquals("(Ljava/nio/file/Path;Ljava/lang/Throwable;)V", implementation.getDesc());
                }
        }
        assertEquals(1, workers); assertEquals(1, completionRefs);
        var logger = node.methods.stream().filter(m -> m.name.equals("logServerCompletion")).findFirst().orElseThrow();
        int successes = 0, failures = 0;
        for (var insn : logger.instructions) if (insn instanceof MethodInsnNode call) {
            if (call.owner.equals("dev/vox/lss/common/LSSLogger")) {
                assertEquals("(Ljava/lang/String;)V", call.desc, "never log exception or arbitrary throwable details");
                if (call.name.equals("info")) successes++;
                else {
                    assertEquals("warn", call.name); failures++;
                    var value = call.getPrevious();
                    while (value != null && value.getOpcode() < 0) value = value.getPrevious();
                    assertInstanceOf(LdcInsnNode.class, value);
                    assertEquals("Diagnostics export failed; check directory permissions and free space.",
                            ((LdcInsnNode) value).cst);
                }
            } else {
                assertEquals("java/lang/String", call.owner);
                assertEquals("valueOf", call.name); // javac's safe Path string conversion
                assertEquals("(Ljava/lang/Object;)Ljava/lang/String;", call.desc);
            }
        }
        assertEquals(1, successes); assertEquals(1, failures);
    }
    @Test void workerAndCompletionCarryOnlyImmutableInputs() throws Exception {
        workerBoundary(read("dev/vox/lss/common/diagnostics/DiagnosticExport"));
    }
    @Test void addedNativeWorkerCaptureIsRejected() throws Exception {
        var node = read("dev/vox/lss/common/diagnostics/DiagnosticExport");
        node.methods.stream().filter(m -> m.name.startsWith("lambda$enqueueServer$")).findFirst().orElseThrow()
                .desc = "(Ljava/lang/Object;)Ljava/nio/file/Path;";
        assertThrows(AssertionError.class, () -> workerBoundary(node));
    }
}
