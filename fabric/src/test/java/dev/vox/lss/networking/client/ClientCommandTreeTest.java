package dev.vox.lss.networking.client;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.vox.lss.common.ExtraCommandRootGuard;
import dev.vox.lss.testutil.SourcePaths;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The client command tree and the names it is registered under (0.23.0 plan §4, decision 0005):
 * the Voxy Server Side client answers to {@code /lss} beside its own {@code /vss}, with the same
 * tree, and the extra name steps aside for a server command of that name.
 *
 * <p>Nodes are parsed, never executed (executing runs against a live client).
 */
class ClientCommandTreeTest {
    private static LiteralArgumentBuilder<Object> tree(String root) {
        return ClientCommandActions.tree(root, LiteralArgumentBuilder::literal, source -> component -> { });
    }

    private static boolean reaches(CommandDispatcher<Object> dispatcher, String command) {
        var parse = dispatcher.parse(command, new Object());
        return parse.getReader().getRemaining().isEmpty() && parse.getExceptions().isEmpty()
                && !parse.getContext().getNodes().isEmpty();
    }

    @Test
    void everyNameGetsTheWholeTree() {
        var dispatcher = new CommandDispatcher<Object>();
        dispatcher.register(tree("vss"));
        dispatcher.register(tree("lss"));
        var expected = Set.of("clearcache", "reset", "reload", "status", "diagnostics", "diag", "trace");
        for (String root : List.of("vss", "lss")) {
            var children = dispatcher.getRoot().getChild(root).getChildren().stream()
                    .map(node -> node.getName()).collect(Collectors.toSet());
            assertEquals(expected, children, root);
            for (String sub : List.of("status", "reload", "clearcache", "diag", "trace", "diagnostics export",
                    "reset", "reset confirm", "reset voxy-force", "reset voxy-force confirm"))
                assertTrue(reaches(dispatcher, root + " " + sub), root + " " + sub);
        }
        assertNotSame(dispatcher.getRoot().getChild("vss"), dispatcher.getRoot().getChild("lss"),
                "one tree per name: a redirect would skip the root's requirement");
        assertNull(dispatcher.getRoot().getChild("lss").getRedirect());
    }

    @Test
    void theExtraNameStepsAsideForAServerCommandOfThatName() {
        // The Fabric shape: the requirement asks the guard, which answers once per command list.
        var guard = new ExtraCommandRootGuard();
        Object[] list = {new Object()};
        boolean[] present = {false};
        var dispatcher = new CommandDispatcher<Object>();
        dispatcher.register(tree("vss"));
        dispatcher.register(tree("lss").requires(source -> guard.allows(list[0], present[0])));

        assertTrue(reaches(dispatcher, "lss status"), "no server command called lss: the extra name works");
        present[0] = true; // the loader merged the client's own node into the same list
        assertTrue(reaches(dispatcher, "lss status"), "its own merged node is not a server command");

        list[0] = new Object(); // the server sent a list that has its own /lss
        assertFalse(reaches(dispatcher, "lss status"),
                "the root reads as unknown here, so the loader sends the command to the server");
        assertTrue(reaches(dispatcher, "vss status"), "the build's own name is never affected");
    }

    @Test
    void bothLoadersRegisterEveryNameAndGuardTheExtraOne() throws Exception {
        String fabric = Files.readString(SourcePaths.repoFile(
                "fabric/src/main/java/dev/vox/lss/networking/client/LSSClientCommands.java")).replaceAll("\\s+", " ");
        assertTrue(fabric.contains("for (String root : Brand.clientCommands())"));
        assertTrue(fabric.contains("if (!root.equals(Brand.clientCommand())) { tree.requires(source ->"
                + " EXTRA_ROOT.allows(ClientCommandActions.commandList(), ClientCommandActions.commandListHas(root))); }"),
                "Fabric merges into the server's list object: the guard answers once per list");
        String neoforge = Files.readString(SourcePaths.repoFile(
                "neoforge/src/main/java/dev/vox/lss/neoforge/LSSNeoClientBootstrap.java")).replaceAll("\\s+", " ");
        assertTrue(neoforge.contains("for (String root : Brand.clientCommands())"));
        assertTrue(neoforge.contains("if (!root.equals(Brand.clientCommand()) && ClientCommandActions.commandListHas(root)) continue;"),
                "NeoForge re-registers on every list, before the merge: the extra name is simply left out");
        // The VSS build's data names the extra command on every platform's jar.
        for (String build : List.of("fabric", "neoforge", "paper", "sponge"))
            assertTrue(Files.readString(SourcePaths.repoFile(build + "/build.gradle"))
                            .contains("clientCommand=vss\\nserverCommand=vsslod\\nalsoClientCommand=lss\\n"), build);
        // The LSS build has no second name.
        for (String build : List.of("fabric", "neoforge", "paper", "sponge"))
            assertFalse(Files.readString(SourcePaths.repoFile(build + "/src/main/resources/lss-brand.properties"))
                    .contains("alsoClientCommand"), build);
    }
}
