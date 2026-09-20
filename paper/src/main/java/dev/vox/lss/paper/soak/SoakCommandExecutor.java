package dev.vox.lss.paper.soak;

import com.mojang.brigadier.context.ContextChain;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;

/** Dev-only command checks for independent soak tick callbacks, never nested commands.
 * Minecraft drains its command queue synchronously at these callers. A missing callback
 * fails strict gamerule checks. Generic dispatch does not promise asynchronous effects or
 * require a positive result: idempotent cleanup (kill with no entities) may legitimately fail.
 */
public final class SoakCommandExecutor {
    private SoakCommandExecutor() {}

    public record Result(boolean success, int value) {}

    /** Parse and executable-chain validation; result success reports the actual callback. */
    public static Result executeForResult(Commands commands, CommandSourceStack source, String command) {
        String bare = command.startsWith("/") ? command.substring(1) : command;
        boolean[] seen = {false};
        boolean[] failed = {false};
        int[] value = {0};
        var recordingSource = source.withCallback((success, result) -> {
            seen[0] = true;
            failed[0] |= !success;
            value[0] = result;
        });
        var parsed = commands.getDispatcher().parse(bare, recordingSource);
        try {
            Commands.validateParseResults(parsed);
        } catch (CommandSyntaxException e) {
            throw new IllegalArgumentException("Rejected soak command: " + command, e);
        }
        if (ContextChain.tryFlatten(parsed.getContext().build(bare)).isEmpty()) {
            throw new IllegalArgumentException("Incomplete soak command: " + command);
        }
        commands.performCommand(parsed, bare);
        return new Result(seen[0] && !failed[0], value[0]);
    }

    /** Dispatch-only acceptance; scenario checks must prove generic command effects. */
    public static boolean dispatch(Commands commands, CommandSourceStack source, String command) {
        executeForResult(commands, source, command);
        return true;
    }

    /** Setter AND exact readback. Dimension-qualified commands keep their prefix for the
     * query, so Paper's per-world rules cannot pass by reading only the console's world. */
    public static boolean setGamerule(Commands commands, CommandSourceStack source, String command) {
        int split = command.lastIndexOf(' ');
        if (split < 0) throw new IllegalArgumentException("Missing gamerule value: " + command);
        String literal = command.substring(split + 1);
        int expected = switch (literal) {
            case "true" -> 1;
            case "false" -> 0;
            default -> Integer.parseInt(literal);
        };
        Result set = executeForResult(commands, source, command);
        if (!set.success()) return false;
        Result read = executeForResult(commands, source, command.substring(0, split));
        return read.success() && read.value() == expected;
    }
}
