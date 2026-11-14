package dev.vox.lss.common.config;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.snakeyaml.engine.v2.exceptions.MarkedYamlEngineException;
import org.snakeyaml.engine.v2.nodes.MappingNode;
import org.snakeyaml.engine.v2.nodes.Node;
import org.snakeyaml.engine.v2.nodes.NodeTuple;
import org.snakeyaml.engine.v2.nodes.ScalarNode;
import org.snakeyaml.engine.v2.nodes.Tag;

/**
 * Everything that is wrong with a settings file the strict reader refused, for the person who
 * has to fix it (0.23.0 plan §1).
 *
 * <p>This class never decides whether a file loads: {@link YamlSettingsCodec#parse} calls it
 * only after the strict read has failed. It lists every problem in file order, so the file is
 * fixed once, and adds at most one suggestion to an unknown setting. A suggestion is text:
 * nothing is ever loaded from it ([decision 0009]: the mod does not guess what a mistake meant).
 *
 * <p>No value from the file appears in a problem. A name the file's author typed can carry one
 * too (a mis-indented address becomes a key), so a name with dots is never printed, and an
 * unknown name under a group that holds a private setting is printed as {@code <private>}
 * unless it is a near match of a known name.
 */
public final class SettingsProblems {
    /** One problem; {@code line} is 1-based, 0 when the parser gave no position. */
    public record Problem(int line, String text) {
        public String render() { return line > 0 ? "line " + line + ": " + text : text; }
    }

    private static final String NEWER_VERSION_HINT = "Check the spelling. If a newer version of the mod wrote this line, delete it.";

    /** A setting of the other side, and the one on this side that does its job. Only pairs
     *  that cannot mislead: without an entry the hint just names the side. */
    static final java.util.Map<String, String> ON_THE_SERVER = java.util.Map.of(
            "far_players.enabled", "far_players.mode",
            "lod.distance_chunks", "lod.distance.default_chunks");
    static final java.util.Map<String, String> ON_THE_CLIENT = java.util.Map.of(
            "far_players.mode", "far_players.enabled",
            "lod.distance.default_chunks", "lod.distance_chunks");

    private SettingsProblems() {}

    /** The problems of {@code bytes}, never empty: when nothing more specific is found, the
     *  strict reader's own message is the one problem. Never throws. */
    public static List<Problem> collect(SettingsSchema<?> schema, byte[] bytes, RuntimeException refused) {
        String strict = refused.getMessage() == null ? "the settings file could not be read" : refused.getMessage();
        try {
            Node root;
            try { root = YamlSettingsCodec.composeForProblems(bytes); }
            catch (SettingsException syntax) {
                // One problem: after a syntax error nothing below it can be trusted.
                return List.of(new Problem(syntaxLine(syntax), syntax.getMessage()));
            }
            if (!(root instanceof MappingNode document)) return List.of(new Problem(0, strict));
            // A file for another schema version would report every key as unknown.
            if (strict.startsWith("config_version")) return List.of(new Problem(lineOf(document, "config_version"), strict));
            var problems = new ArrayList<Problem>();
            walk(schema, document, "", problems);
            return problems.isEmpty() ? List.of(new Problem(0, strict)) : List.copyOf(problems);
        } catch (RuntimeException unexpected) {
            return List.of(new Problem(0, strict));
        }
    }

    private static void walk(SettingsSchema<?> schema, MappingNode group, String prefix, List<Problem> out) {
        var seen = new java.util.HashSet<String>();
        for (NodeTuple pair : group.getValue()) {
            int line = line(pair.getKeyNode());
            if (!(pair.getKeyNode() instanceof ScalarNode key) || !Tag.STR.equals(key.getTag())) {
                out.add(new Problem(line, "a setting name must be plain text" + under(prefix)));
                continue;
            }
            String name = key.getValue();
            if (name.contains(".")) {
                out.add(new Problem(line, "a name" + under(prefix) + " contains a dot; settings are written as nested groups, one name per line"));
                continue;
            }
            String path = prefix.isEmpty() ? name : prefix + "." + name;
            if (path.equals("config_version")) continue;
            // A second copy of a known name: the strict reader stops at it. (An unknown name
            // written twice is reported as unknown, twice.)
            if (!seen.add(name) && (schema.knownLeaf(path) || schema.knownGroup(path))) {
                out.add(new Problem(line, path + " is written twice; keep one"));
                continue;
            }
            if (schema.knownLeaf(path)) {
                if (!schema.descriptorsByPath().containsKey(schema.canonicalPath(path))) continue; // retired: tolerated
                String wrong;
                try { wrong = schema.valueProblem(path, YamlSettingsCodec.valueForProblems(pair.getValueNode(), path, schema::booleanPath)); }
                catch (SettingsException unreadable) { wrong = unreadable.getMessage(); }
                if (wrong != null) out.add(new Problem(line, wrong));
                continue;
            }
            if (schema.knownGroup(path)) {
                if (pair.getValueNode() instanceof MappingNode nested) walk(schema, nested, path, out);
                else out.add(new Problem(line, path + " is a group of settings; its settings go on the lines below it, indented"));
                continue;
            }
            out.add(new Problem(line, unknown(schema, prefix, name)));
        }
    }

    private static String unknown(SettingsSchema<?> schema, String prefix, String name) {
        String path = prefix.isEmpty() ? name : prefix + "." + name;
        String near = nearest(siblings(schema, prefix), name);
        if (near != null) return "unknown setting " + path + ". Did you mean " + (prefix.isEmpty() ? near : prefix + "." + near) + "?";
        String shown = privateGroups(schema).contains(topLevel(path)) && !prefix.isEmpty() ? prefix + ".<private>" : path;
        // The hints below only name settings a schema itself knows, so they print the name.
        if (otherSide(schema).descriptorsByPath().containsKey(path)) {
            String here = (schema.isClient() ? ON_THE_CLIENT : ON_THE_SERVER).get(path);
            return "unknown setting " + path + ". That is a " + otherSide(schema).side() + " setting"
                    + (here == null ? ", not a " + schema.side() + " one." : "; the " + schema.side() + "'s is " + here + ".");
        }
        // The file before YAML was flat, so an old name belongs at the top. Inside a group only
        // a camelCase name is unmistakably an old one ("enabled" was the old service switch
        // and is also a perfectly good guess at a new name).
        if (prefix.isEmpty() || !name.equals(name.toLowerCase(Locale.ROOT)))
            for (var descriptor : schema.descriptors())
                if (name.equals(descriptor.legacyKey()))
                    return "unknown setting " + path + ". Its name in this file is " + descriptor.path() + "."
                            // The old file counted seconds where this one counts ticks.
                            + (name.endsWith("Seconds") && descriptor.path().endsWith("_ticks")
                                    ? " It counts ticks: 20 for each second." : "");
        return "unknown setting " + shown + ". " + NEWER_VERSION_HINT;
    }

    /** The names a file may use directly under {@code prefix}: settings and groups. */
    private static Set<String> siblings(SettingsSchema<?> schema, String prefix) {
        var names = new LinkedHashSet<String>();
        String start = prefix.isEmpty() ? "" : prefix + ".";
        for (String path : schema.descriptorsByPath().keySet()) {
            if (!path.startsWith(start)) continue;
            String rest = path.substring(start.length());
            int dot = rest.indexOf('.');
            names.add(dot < 0 ? rest : rest.substring(0, dot));
        }
        return names;
    }

    /** The single closest name within two edits (one for a short name); null when none, or when
     *  two names are equally close. */
    static String nearest(Set<String> names, String typed) {
        String lower = typed.toLowerCase(Locale.ROOT);
        int limit = lower.length() < 5 ? 1 : 2;
        String best = null; int bestDistance = limit + 1; boolean tie = false;
        for (String candidate : names) {
            int distance = edits(lower, candidate.toLowerCase(Locale.ROOT), limit);
            if (distance < bestDistance) { best = candidate; bestDistance = distance; tie = false; }
            else if (distance == bestDistance && distance <= limit) tie = true;
        }
        return tie ? null : best;
    }

    /** Levenshtein distance with adjacent transposition, capped: anything above {@code limit}
     *  returns {@code limit + 1}. */
    static int edits(String a, String b, int limit) {
        if (Math.abs(a.length() - b.length()) > limit) return limit + 1;
        int[] twoBack = new int[b.length() + 1], previous = new int[b.length() + 1], current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) previous[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                int best = Math.min(Math.min(current[j - 1], previous[j]) + 1, previous[j - 1] + cost);
                if (i > 1 && j > 1 && a.charAt(i - 1) == b.charAt(j - 2) && a.charAt(i - 2) == b.charAt(j - 1))
                    best = Math.min(best, twoBack[j - 2] + 1);
                current[j] = best;
            }
            int[] spare = twoBack; twoBack = previous; previous = current; current = spare;
        }
        return Math.min(previous[b.length()], limit + 1);
    }

    private static Set<String> privateGroups(SettingsSchema<?> schema) {
        var groups = new LinkedHashSet<String>();
        for (var descriptor : schema.descriptors()) if (descriptor.sensitive()) groups.add(topLevel(descriptor.path()));
        return groups;
    }

    private static String topLevel(String path) {
        int dot = path.indexOf('.');
        return dot < 0 ? path : path.substring(0, dot);
    }

    private static SettingsSchema<?> otherSide(SettingsSchema<?> schema) {
        return schema.isClient() ? SettingsSchema.server(false) : SettingsSchema.client();
    }

    private static String under(String prefix) { return prefix.isEmpty() ? "" : " under " + prefix; }

    private static int line(Node node) {
        return node.getStartMark().map(mark -> mark.getLine() + 1).orElse(0);
    }

    private static int lineOf(MappingNode document, String name) {
        for (NodeTuple pair : document.getValue())
            if (pair.getKeyNode() instanceof ScalarNode key && key.getValue().equals(name)) return line(key);
        return 0;
    }

    private static int syntaxLine(SettingsException syntax) {
        return syntax.getCause() instanceof MarkedYamlEngineException marked
                ? marked.getProblemMark().map(mark -> mark.getLine() + 1).orElse(0) : 0;
    }
}
