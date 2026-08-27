package dev.vox.lss.networking.client;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The shape of the shared {@code /lss reset} subtree (issue #4 follow-up).
 *
 * <p>Fabric and NeoForge used to hand-copy this tree, so the two could silently disagree
 * about which forms exist — the drift class the gametest-entrypoint and plugin.yml
 * contract tests exist to catch elsewhere. {@link ClientCommandActions#resetSubtree}
 * makes the drift unrepresentable: both loaders hang the SAME builder off their own root.
 * What is still worth pinning is the tree that builder produces.
 *
 * <p>The nodes are parsed, never EXECUTED: executing would run the real reset against a
 * live Minecraft client. Parsing is what proves {@code voxy-force} is a usable brigadier
 * literal (dashes are legal in unquoted literals — worth a tripwire, since a rename to
 * something brigadier rejects would only surface in-game).
 */
class ClientResetSubtreeTest {

    /** Source type is irrelevant to the tree's shape — use a bare Object. */
    private static CommandDispatcher<Object> dispatcherWithSubtree() {
        var dispatcher = new CommandDispatcher<Object>();
        dispatcher.register(LiteralArgumentBuilder.<Object>literal("lss")
                .then(ClientCommandActions.resetSubtree(
                        LiteralArgumentBuilder::literal, source -> component -> { })));
        return dispatcher;
    }

    private static void assertFullyParses(CommandDispatcher<Object> dispatcher, String command) {
        var parse = dispatcher.parse(command, new Object());
        assertTrue(parse.getReader().getRemaining().isEmpty(),
                "'" + command + "' did not fully parse — unconsumed: '"
                        + parse.getReader().getRemaining() + "'");
        assertTrue(parse.getExceptions().isEmpty(),
                "'" + command + "' parsed with errors: " + parse.getExceptions());
    }

    @Test
    void allFourResetFormsExistAndParse() {
        var dispatcher = dispatcherWithSubtree();
        for (String command : List.of(
                "lss reset",
                "lss reset confirm",
                "lss reset voxy-force",
                "lss reset voxy-force confirm")) {
            assertFullyParses(dispatcher, command);
        }
    }

    /** The two-stage shape is structural: {@code confirm} hangs UNDER {@code voxy-force},
     *  so there is no way to reach the forced wipe without passing through stage 1's
     *  literal. A flattened {@code reset voxy-force-confirm} would break that reading. */
    @Test
    void theForcedConfirmHangsUnderTheForcedLiteral() {
        var reset = dispatcherWithSubtree().getRoot().getChild("lss").getChild("reset");
        assertNotNull(reset.getChild("confirm"), "plain confirm must stay a direct child");
        var force = reset.getChild("voxy-force");
        assertNotNull(force, "the force literal is named 'voxy-force'");
        assertNotNull(force.getChild("confirm"),
                "stage 2 must be a CHILD of stage 1, not a sibling form");
        assertEquals(2, reset.getChildren().size(),
                "reset takes exactly 'confirm' and 'voxy-force': " + reset.getChildren());
        assertEquals(1, force.getChildren().size(),
                "voxy-force takes exactly 'confirm': " + force.getChildren());
    }

    /** The node→(confirmed, force) mapping, executed against the dispatch seam: the
     *  four forms must map to exactly the four flag pairs, or a loader could parse a
     *  command into the wrong stage (plan §3.2's sink pin). */
    @Test
    void everyFormDispatchesItsExactFlagPair() throws Exception {
        var calls = new java.util.ArrayList<String>();
        var dispatcher = new CommandDispatcher<Object>();
        dispatcher.register(LiteralArgumentBuilder.<Object>literal("lss")
                .then(ClientCommandActions.resetSubtree(
                        LiteralArgumentBuilder::literal, source -> component -> { },
                        (feedback, confirmed, force) ->
                                calls.add(confirmed + "/" + force))));
        for (String command : List.of(
                "lss reset",
                "lss reset confirm",
                "lss reset voxy-force",
                "lss reset voxy-force confirm")) {
            dispatcher.execute(command, new Object());
        }
        assertEquals(List.of("false/false", "true/false", "false/true", "true/true"), calls);
    }

    /** Both loaders must hang the SHARED subtree, not a hand-copied one — the whole
     *  point of the builder (source-pinned, the gametest-entrypoint contract shape). */
    @Test
    void bothLoadersUseTheSharedSubtree() throws Exception {
        for (String file : List.of(
                "fabric/src/main/java/dev/vox/lss/networking/client/LSSClientCommands.java",
                "neoforge/src/main/java/dev/vox/lss/neoforge/LSSNeoClientBootstrap.java")) {
            String source = java.nio.file.Files.readString(
                    dev.vox.lss.testutil.SourcePaths.repoFile(file));
            assertTrue(source.contains("ClientCommandActions.resetSubtree("),
                    file + " must build its reset tree through the shared subtree");
            assertFalse(source.contains("literal(\"voxy-force\")"),
                    file + " must not hand-roll the voxy-force literal beside the "
                            + "shared builder");
        }
    }

    /** Every node is executable — a literal with no command is a dead end in game. */
    @Test
    void everyNodeInTheSubtreeIsExecutable() {
        var reset = dispatcherWithSubtree().getRoot().getChild("lss").getChild("reset");
        assertNotNull(reset.getCommand(), "bare 'reset' must run");
        assertNotNull(reset.getChild("confirm").getCommand());
        assertNotNull(reset.getChild("voxy-force").getCommand(),
                "bare 'voxy-force' is stage 1 — it must run and report, not fail as unknown");
        assertNotNull(reset.getChild("voxy-force").getChild("confirm").getCommand());
    }
}
