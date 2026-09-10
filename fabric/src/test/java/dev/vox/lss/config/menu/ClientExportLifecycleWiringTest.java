package dev.vox.lss.config.menu;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import static org.junit.jupiter.api.Assertions.*;

class ClientExportLifecycleWiringTest {
    private static ClassNode read(String name) throws Exception {
        try (var input = ClientExportLifecycleWiringTest.class.getClassLoader().getResourceAsStream(name + ".class")) {
            assertNotNull(input); var node = new ClassNode(); new ClassReader(input).accept(node, 0); return node;
        }
    }
    private static void noSourceCapture(ClassNode node) {
        int completions = 0;
        for (var method : node.methods) if (method.name.startsWith("lambda$exportDiagnostics$")) {
            completions++;
            for (Type argument : Type.getArgumentTypes(method.desc)) {
                String value = argument.getDescriptor();
                assertTrue(value.equals("Ldev/vox/lss/networking/client/LifecycleFeedback$Ticket;")
                        || value.equals("Ljava/lang/String;") || value.equals("Ljava/nio/file/Path;")
                        || value.equals("Ljava/lang/Throwable;"), "unexpected async export capture: " + value);
            }
        }
        assertEquals(2, completions, "both actual worker and queued client completion must be inspected");
    }
    @Test void actualExportClosuresRetainOnlyTicketAndResult() throws Exception {
        noSourceCapture(read("dev/vox/lss/networking/client/ClientCommandActions"));
    }
    @Test void originalConsumerCaptureWouldFail() throws Exception {
        var node = read("dev/vox/lss/networking/client/ClientCommandActions");
        var method = node.methods.stream().filter(m -> m.name.startsWith("lambda$exportDiagnostics$")).findFirst().orElseThrow();
        method.desc = "(Ljava/util/function/Consumer;)V";
        assertThrows(AssertionError.class, () -> noSourceCapture(node));
    }
    @Test void clientOwnerAndCurrentIdentityAreCheckedBeforeCallbackTake() throws Exception {
        var node = read("dev/vox/lss/networking/client/ClientStatus");
        var method = node.methods.stream().filter(m -> m.name.equals("completeExportFeedback")).findFirst().orElseThrow();
        int index=0, owner=-1, identity=-1, take=-1, callback=-1, unlock=-1;
        for (var instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call) {
                if (call.name.equals("isSameThread")) owner=index;
                if (call.name.equals("checkLifecycle")) identity=index;
                if (call.name.equals("take")) take=index;
                if (call.owner.equals("java/util/function/Consumer") && call.name.equals("accept")) callback=index;
            }
            if (instruction.getOpcode()==Opcodes.MONITOREXIT && take>=0 && callback<0) unlock=index;
            index++;
        }
        assertTrue(owner>=0 && owner<identity && identity<take && take<unlock && unlock<callback);
    }
    @Test void immutableTicketCannotRetainScreenSourceOrWorld() throws Exception {
        for (var field : Class.forName("dev.vox.lss.networking.client.LifecycleFeedback$Ticket").getRecordComponents())
            assertEquals(long.class, field.getType());
    }
    private static void submissionFailuresRelease(ClassNode node) {
        var method=node.methods.stream().filter(m -> m.name.equals("exportDiagnostics")).findFirst().orElseThrow();
        for(String type:java.util.List.of("java/util/concurrent/RejectedExecutionException", "java/lang/RuntimeException", "java/lang/Error")) {
            var handler=method.tryCatchBlocks.stream().filter(h -> type.equals(h.type)).findFirst().orElseThrow();
            boolean released=false;
            for(var instruction=handler.handler.getNext(); instruction!=null; instruction=instruction.getNext()) {
                if(instruction instanceof MethodInsnNode call && call.name.equals("releaseExportFeedback")) released=true;
                int opcode=instruction.getOpcode();
                if(opcode==Opcodes.GOTO || opcode==Opcodes.RETURN || opcode==Opcodes.ATHROW) break;
            }
            assertTrue(released, "actual submission handler must release reservation: "+type);
        }
    }
    @Test void actualSubmissionExceptionHandlersReleaseReservation() throws Exception {
        submissionFailuresRelease(read("dev/vox/lss/networking/client/ClientCommandActions"));
    }
    @Test void removedBusyCatchReleaseWouldFail() throws Exception {
        var node=read("dev/vox/lss/networking/client/ClientCommandActions");
        var method=node.methods.stream().filter(m -> m.name.equals("exportDiagnostics")).findFirst().orElseThrow();
        var handler=method.tryCatchBlocks.stream().filter(h -> "java/util/concurrent/RejectedExecutionException".equals(h.type)).findFirst().orElseThrow();
        for(var instruction=handler.handler.getNext(); instruction!=null; instruction=instruction.getNext()) {
            if(instruction instanceof MethodInsnNode call && call.name.equals("releaseExportFeedback")) {method.instructions.remove(instruction);break;}
        }
        assertThrows(AssertionError.class, () -> submissionFailuresRelease(node));
    }
}
