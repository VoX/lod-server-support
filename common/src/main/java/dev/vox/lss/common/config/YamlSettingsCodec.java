package dev.vox.lss.common.config;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.security.*;
import java.util.*;
import org.snakeyaml.engine.v2.api.*;
import org.snakeyaml.engine.v2.api.lowlevel.*;
import org.snakeyaml.engine.v2.common.*;
import org.snakeyaml.engine.v2.events.*;
import org.snakeyaml.engine.v2.nodes.*;
import org.snakeyaml.engine.v2.schema.JsonSchema;

/** Strict, bounded representation-tree codec. No JavaBean or arbitrary tag construction. */
public final class YamlSettingsCodec<T> {
    public static final int MAX_BYTES = 1_048_576, MAX_DEPTH = 32, MAX_NODES = 50_000;
    private static final LoadSettings LOAD = LoadSettings.builder().setParseComments(true)
            .setSchema(new JsonSchema()).setAllowDuplicateKeys(false).setMaxAliasesForCollections(0)
            .setCodePointLimit(MAX_BYTES).build();
    private static final DumpSettings DUMP = DumpSettings.builder().setDumpComments(true)
            .setSchema(new JsonSchema()).setIndent(2).setDefaultFlowStyle(FlowStyle.BLOCK).build();
    private final SettingsSchema<T> schema;
    private final String brandPrefix;
    public YamlSettingsCodec(SettingsSchema<T> schema) { this(schema, "lss"); }
    public YamlSettingsCodec(SettingsSchema<T> schema, String brandPrefix) {
        this.schema = Objects.requireNonNull(schema);
        if (!Set.of("lss", "vss").contains(brandPrefix)) throw new IllegalArgumentException("Unknown settings brand");
        this.brandPrefix = brandPrefix;
    }

    /** Experimental client keys live in a sidecar file, so the main file stays readable by
     *  older versions that reject unknown keys (one user-approved exception: the renamed
     *  {@code integrations.map_ingestion} group, written by new files and saves — map-ingestion
     *  plan §2.4). Reads still accept them in the main file
     *  (development builds wrote them there); every write moves them out. */
    public static final List<String> SIDECAR_PATHS = List.of("lod.source", "lod.dh.sync_on_load",
            "lod.dh.real_time_updates", "lod.voxy_border_fill", "integrations.map_ingestion.nether_store_mib",
            "far_players.extend_far_plane");
    /** Retired keys are tolerated on read and removed from the main file by the next write. */
    public static final List<String> RETIRED_PATHS = List.of("lod.dh.refresh_seconds", "lod.dh.profile");

    /** {@code bytes} is the main file; {@code sidecar} is null when no sidecar file exists. */
    public record Document<T>(T configured, T normalized, List<SettingsSchema.Normalization> normalizations,
                              List<String> inactivePaths, String hash, byte[] bytes, byte[] sidecar) {
        public Document { normalizations = List.copyOf(normalizations); inactivePaths = List.copyOf(inactivePaths); bytes = bytes.clone(); sidecar = sidecar == null ? null : sidecar.clone(); }
        public Document(T configured, T normalized, List<SettingsSchema.Normalization> normalizations,
                        List<String> inactivePaths, String hash, byte[] bytes) {
            this(configured, normalized, normalizations, inactivePaths, hash, bytes, null);
        }
        @Override public byte[] bytes() { return bytes.clone(); }
        @Override public byte[] sidecar() { return sidecar == null ? null : sidecar.clone(); }
        /** The {@code paths} this document itself sets (in the main file, or the client
         *  sidecar), as opposed to values the schema default supplied. Exact paths only:
         *  a renamed group's old spelling is not looked up. */
        public Set<String> declaredPaths(Collection<String> paths) {
            Map<String,Object> main = readTree(bytes);
            Map<String,Object> side = Map.of();
            // A sidecar may be comments only, or unreadable (parse then ignored it):
            // nothing is declared there, and nothing is logged a second time.
            if (sidecar != null) try { side = readTree(sidecar); } catch (RuntimeException ignored) { }
            var out = new LinkedHashSet<String>();
            for (String path : paths)
                if (SettingsSchema.lookup(main, path) != null || SettingsSchema.lookup(side, path) != null) out.add(path);
            return Collections.unmodifiableSet(out);
        }
    }
    /** Main and sidecar contents to publish. A null sidecar means the file must not exist. */
    public record Output(byte[] main, byte[] sidecar) {
        public Output { main = main.clone(); sidecar = sidecar == null ? null : sidecar.clone(); }
        @Override public byte[] main() { return main.clone(); }
        @Override public byte[] sidecar() { return sidecar == null ? null : sidecar.clone(); }
    }
    public Document<T> parse(byte[] bytes) { return parse(bytes, null); }
    public Document<T> parse(byte[] bytes, byte[] sidecar) {
        Map<String,Object> tree = readTree(bytes);
        SettingsSchema.Decoded<T> result = null;
        if (sidecar != null) {
            if (!schema.isClient()) throw new SettingsException("Only client settings have an experimental sidecar");
            // The experimental file must never take the main settings down: an unreadable
            // or invalid sidecar (e.g. written by a newer build) falls back to defaults.
            try { result = schema.decode(merge(tree, sidecarTree(sidecar))); }
            catch (RuntimeException e) { // SettingsException, YAML syntax errors, wrong value types
                dev.vox.lss.common.LSSLogger.warn("Experimental client settings file ignored: " + e.getMessage());
            }
        }
        if (result == null) result = schema.decode(tree);
        return new Document<>(result.configured(), result.normalized(), result.normalizations(), result.inactivePaths(), hash(bytes, sidecar), bytes, sidecar);
    }
    public byte[] defaults() {
        String template = schema.template();
        if (brandPrefix.equals("vss")) template = template.replace("/lsslod", "/vsslod")
                .replace("/lss", "/vss").replace("LSS", "VSS")
                .replace("lss-lod/", "vss-lod/").replace(".lss/", ".vss/");
        return template.getBytes(StandardCharsets.UTF_8);
    }

    /** Main-file bytes only; client callers that persist must use {@link #editSplit}. */
    public byte[] edit(Document<T> document, Map<String,Object> changes) { return editSplit(document, changes).main(); }

    /** Only explicit changed paths are replaced; untouched scalar styles and comments survive.
     *  Client sidecar paths never stay in the main file: they go to the sidecar, which holds
     *  only nondefault values plus any value the old main file still carried (so a failed
     *  main rewrite cannot revert an explicit choice). */
    public Output editSplit(Document<T> document, Map<String,Object> requested) {
        // Renamed groups: alias keys in a change set are written under their canonical path.
        var changes = schema.canonicalChanges(requested);
        var values = new LinkedHashMap<>(schema.values(document.configured()));
        values.putAll(changes);
        // Emit the validated requested values, whose numeric classes match the schema.
        // Normalized/clamped values remain separate and must never overwrite the draft.
        var configured = schema.values(schema.fromValues(values).configured());
        boolean client = schema.isClient();
        MappingNode root = (MappingNode) compose(document.bytes());
        boolean touched = false;
        // Only a save that changes a MAIN-file value canonicalizes renamed groups: the startup
        // development-key split edits with an empty change set, and a sidecar-only change (the
        // experimental DH toggles) must leave the main file readable by older builds.
        boolean mainChange = changes.keySet().stream().anyMatch(path -> !SIDECAR_PATHS.contains(path));
        if (client && mainChange) touched = canonicalizeAliasGroups(root, configured);
        for (var entry : changes.entrySet()) {
            if (client && SIDECAR_PATHS.contains(entry.getKey())) continue;
            replace(root, entry.getKey().split("\\."), 0, node(configured.get(entry.getKey())));
            touched = true;
        }
        byte[] sidecar = null;
        if (client) {
            var previousMain = readTree(document.bytes());
            var keep = new LinkedHashMap<String,Object>();
            for (String path : SIDECAR_PATHS)
                if (!Objects.equals(configured.get(path), schema.defaultValues().get(path))
                        || SettingsSchema.lookup(previousMain, path) != null) keep.put(path, configured.get(path));
            var stale = new ArrayList<>(SIDECAR_PATHS); stale.addAll(RETIRED_PATHS);
            for (String path : stale)
                if (SettingsSchema.lookup(previousMain, path) != null) { remove(root, path.split("\\."), 0); touched = true; }
            if (!keep.isEmpty()) sidecar = sidecarBytes(keep);
        }
        byte[] main = touched ? emit(root) : document.bytes();
        parse(main);
        if (sidecar != null) parse(main, sidecar);
        return new Output(main, sidecar);
    }
    /** Replaces each renamed group with the template's canonical group (its comments
     *  included) carrying the configured values; a document holding both drops the alias. */
    private boolean canonicalizeAliasGroups(MappingNode root, Map<String,Object> configured) {
        boolean changed = false;
        for (var group : SettingsSchema.CLIENT_ALIAS_GROUPS.entrySet()) {
            String[] alias = group.getKey().split("\\."), canonical = group.getValue().split("\\.");
            MappingNode parent = mappingAt(root, alias, alias.length - 1);
            int aliasIndex = parent == null ? -1 : indexOf(parent, alias[alias.length - 1]);
            if (aliasIndex < 0) continue;
            int canonicalIndex = indexOf(parent, canonical[canonical.length - 1]);
            if (canonicalIndex >= 0) {
                // Both groups: the configured values merged them (canonical wins, the alias
                // fills leaves the canonical group lacks) — keep every merged leaf in the
                // canonical group before the alias goes, or a save would reset alias-only
                // values to their defaults (review).
                if (parent.getValue().get(canonicalIndex).getValueNode() instanceof MappingNode canonicalGroup) {
                    String prefix = group.getValue() + ".";
                    for (var leaf : configured.entrySet())
                        if (leaf.getKey().startsWith(prefix) && !SIDECAR_PATHS.contains(leaf.getKey()))
                            replace(canonicalGroup, leaf.getKey().substring(prefix.length()).split("\\."), 0, node(leaf.getValue()));
                }
                parent.getValue().remove(aliasIndex);
                changed = true;
                continue;
            }
            MappingNode templateParent = mappingAt((MappingNode) compose(defaults()), canonical, canonical.length - 1);
            NodeTuple templatePair = templateParent.getValue().get(indexOf(templateParent, canonical[canonical.length - 1]));
            var templateGroup = (MappingNode) templatePair.getValueNode();
            String prefix = group.getValue() + ".";
            for (var leaf : configured.entrySet())
                if (leaf.getKey().startsWith(prefix) && !SIDECAR_PATHS.contains(leaf.getKey()))
                    replace(templateGroup, leaf.getKey().substring(prefix.length()).split("\\."), 0, node(leaf.getValue()));
            parent.getValue().set(aliasIndex, templatePair);
            changed = true;
        }
        return changed;
    }
    private static MappingNode mappingAt(MappingNode root, String[] path, int depth) {
        MappingNode current = root;
        for (int i = 0; i < depth; i++) {
            int index = indexOf(current, path[i]);
            if (index < 0 || !(current.getValue().get(index).getValueNode() instanceof MappingNode next)) return null;
            current = next;
        }
        return current;
    }
    private static int indexOf(MappingNode mapping, String key) {
        var pairs = mapping.getValue();
        for (int i = 0; i < pairs.size(); i++)
            if (pairs.get(i).getKeyNode() instanceof ScalarNode scalar && scalar.getValue().equals(key)) return i;
        return -1;
    }
    public byte[] generate(Map<String,Object> values) { return edit(parse(defaults()), values); }
    public Output generateSplit(Map<String,Object> values) { return editSplit(parse(defaults()), values); }

    private byte[] sidecarBytes(Map<String,Object> values) {
        var root = new MappingNode(Tag.MAP, new ArrayList<>(), FlowStyle.BLOCK);
        for (var entry : values.entrySet()) replace(root, entry.getKey().split("\\."), 0, node(entry.getValue()));
        String header = "# Experimental client settings. They live in this separate file so the main client\n"
                + "# settings file stays readable by older versions. Defaults are omitted and deleting\n"
                + "# this file restores them. /" + brandPrefix + " reload applies edits; lod.source needs a reconnect.\n";
        return (header + new String(emit(root), StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8);
    }
    private static byte[] emit(MappingNode root) {
        return new Present(DUMP).emitToString(new Serialize(DUMP).serializeOne(root).iterator()).getBytes(StandardCharsets.UTF_8);
    }

    /** Combined identity; equal to the main-file hash whenever no sidecar exists. */
    public static String hash(byte[] main, byte[] sidecar) {
        return sidecar == null ? hash(main) : hash((hash(main) + "\n" + hash(sidecar)).getBytes(StandardCharsets.UTF_8));
    }
    /** A sidecar may be empty (comments only). Paths other than {@link #SIDECAR_PATHS}
     *  (retired keys, or keys of a newer build) are dropped with a warning, never fatal. */
    static Map<String,Object> sidecarTree(byte[] bytes) {
        var node = composeOptional(bytes);
        // A comments-only document composes to nothing, an implicit null scalar, or (with
        // comment parsing on) an empty comment-tagged mapping.
        if (node.isEmpty() || node.get() instanceof ScalarNode scalar && Tag.NULL.equals(scalar.getTag())
                || node.get() instanceof MappingNode map && map.getValue().isEmpty() && Tag.COMMENT.equals(map.getTag())) return Map.of();
        Object value = value(node.get(), "");
        if (!(value instanceof Map<?,?> map)) throw new SettingsException("Experimental settings sidecar must be a mapping");
        @SuppressWarnings("unchecked") var tree = (Map<String,Object>) map;
        return knownSidecar(tree, "");
    }
    private static Map<String,Object> knownSidecar(Map<String,Object> tree, String prefix) {
        var out = new LinkedHashMap<String,Object>();
        for (var e : tree.entrySet()) {
            String path = prefix + e.getKey();
            if (SIDECAR_PATHS.contains(path)) { out.put(e.getKey(), e.getValue()); continue; }
            boolean group = !e.getKey().contains(".") && SIDECAR_PATHS.stream().anyMatch(p -> p.startsWith(path + "."));
            if (group && e.getValue() instanceof Map<?,?> nested) {
                @SuppressWarnings("unchecked") var child = (Map<String,Object>) nested;
                out.put(e.getKey(), knownSidecar(child, path + "."));
                continue;
            }
            if (!RETIRED_PATHS.contains(path))
                dev.vox.lss.common.LSSLogger.warn("Unknown experimental client setting ignored: " + path);
        }
        return out;
    }
    /** True when the main file still carries experimental or retired keys (development builds). */
    public boolean mainCarriesSidecarKeys(byte[] main) {
        if (!schema.isClient()) return false;
        var tree = readTree(main);
        for (String path : SIDECAR_PATHS) if (SettingsSchema.lookup(tree, path) != null) return true;
        for (String path : RETIRED_PATHS) if (SettingsSchema.lookup(tree, path) != null) return true;
        return false;
    }
    /** Sidecar values override the same paths in the main tree. */
    private static Map<String,Object> merge(Map<String,Object> main, Map<String,Object> sidecar) {
        var out = mutable(main);
        mergeInto(out, sidecar);
        return out;
    }
    @SuppressWarnings("unchecked")
    private static void mergeInto(Map<String,Object> target, Map<String,Object> source) {
        for (var e : source.entrySet()) {
            if (e.getValue() instanceof Map<?,?> nested && target.get(e.getKey()) instanceof Map<?,?> existing) {
                var child = mutable((Map<String,Object>) existing);
                mergeInto(child, (Map<String,Object>) nested);
                target.put(e.getKey(), child);
            } else target.put(e.getKey(), e.getValue() instanceof Map<?,?> m ? mutable((Map<String,Object>) m) : e.getValue());
        }
    }
    @SuppressWarnings("unchecked")
    private static Map<String,Object> mutable(Map<String,Object> map) {
        var out = new LinkedHashMap<String,Object>();
        map.forEach((k, v) -> out.put(k, v instanceof Map<?,?> m ? mutable((Map<String,Object>) m) : v));
        return out;
    }

    public static void validateStructure(byte[] bytes) { compose(bytes); }

    private static Node compose(byte[] bytes) {
        return composeOptional(bytes).orElseThrow(() -> new SettingsException("Settings file is empty"));
    }
    private static Optional<Node> composeOptional(byte[] bytes) {
        String input = utf8(bytes);
        try {
            int depth=0, count=0, documents=0;
            for (Event event : new Parse(LOAD).parseString(input)) {
                if (event instanceof AliasEvent) throw new SettingsException("YAML aliases are not allowed");
                if (event instanceof NodeEvent n && n.getAnchor().isPresent()) throw new SettingsException("YAML anchors are not allowed");
                if (event instanceof ScalarEvent s && s.getTag().isPresent()
                        || event instanceof CollectionStartEvent c && c.getTag().isPresent()) throw new SettingsException("Explicit YAML tags are not allowed");
                if (event instanceof DocumentStartEvent && ++documents > 1) throw new SettingsException("Only one YAML document is allowed");
                if (event instanceof CollectionStartEvent) {
                    if (++depth > MAX_DEPTH) throw new SettingsException("YAML nesting exceeds " + MAX_DEPTH);
                    if (++count > MAX_NODES) throw new SettingsException("YAML node count exceeds " + MAX_NODES);
                } else if (event instanceof CollectionEndEvent) depth--;
                else if (event instanceof ScalarEvent && ++count > MAX_NODES) throw new SettingsException("YAML node count exceeds " + MAX_NODES);
            }
            return new Compose(LOAD).composeString(input);
        } catch (SettingsException e) { throw e; }
        catch (RuntimeException e) { throw new SettingsException("Invalid YAML syntax; check indentation, keys and scalar values", e); }
    }
    public static Map<String,Object> readTree(byte[] bytes) {
        Object value = value(compose(bytes), "");
        if (!(value instanceof Map<?,?>)) throw new SettingsException("Settings document must be a nonempty mapping");
        @SuppressWarnings("unchecked") var map = (Map<String,Object>)value;
        if (map.isEmpty()) throw new SettingsException("Settings document must be a nonempty mapping");
        return map;
    }
    private static Object value(Node node, String path) {
        if (node instanceof MappingNode map) {
            if (!Tag.MAP.equals(node.getTag())) throw new SettingsException("Invalid mapping tag at " + path);
            var result = new LinkedHashMap<String,Object>();
            for (NodeTuple pair : map.getValue()) {
                if (!(pair.getKeyNode() instanceof ScalarNode key) || !Tag.STR.equals(key.getTag())) throw new SettingsException("Mapping keys must be strings at " + path);
                if (key.getValue().equals("<<")) throw new SettingsException("YAML merge keys are not allowed");
                if (result.containsKey(key.getValue())) throw new SettingsException("Duplicate mapping key at " + path);
                result.put(key.getValue(), value(pair.getValueNode(), path.isEmpty() ? key.getValue() : path + "." + key.getValue()));
            }
            return Collections.unmodifiableMap(result);
        }
        if (node instanceof SequenceNode sequence) {
            if (!Tag.SEQ.equals(node.getTag())) throw new SettingsException("Invalid sequence tag at " + path);
            var result = new ArrayList<Object>();
            for (int i=0;i<sequence.getValue().size();i++) result.add(value(sequence.getValue().get(i), path + "[" + i + "]"));
            return List.copyOf(result);
        }
        if (!(node instanceof ScalarNode scalar)) throw new SettingsException("Unsupported YAML node at " + path);
        if (Tag.STR.equals(scalar.getTag())) return scalar.getValue();
        if (Tag.BOOL.equals(scalar.getTag()) && (scalar.getValue().equals("true") || scalar.getValue().equals("false"))) return Boolean.valueOf(scalar.getValue());
        if (Tag.INT.equals(scalar.getTag()) || Tag.FLOAT.equals(scalar.getTag())) {
            if(scalar.getValue().length()>128)throw new SettingsException("Numeric scalar is too large at " + path);
            try { return Tag.INT.equals(scalar.getTag()) ? new BigInteger(scalar.getValue()) : new BigDecimal(scalar.getValue()); }
            catch (NumberFormatException e) { throw new SettingsException("A finite number is required at " + path); }
        }
        throw new SettingsException("Null or unsupported scalar at " + path);
    }
    private static Node node(Object value) {
        if (value instanceof Map<?,?> map) {
            var pairs = new ArrayList<NodeTuple>();
            map.forEach((k,v) -> pairs.add(new NodeTuple(node(k.toString()), node(v))));
            return new MappingNode(Tag.MAP,pairs,FlowStyle.BLOCK);
        }
        if (value instanceof List<?> list) return new SequenceNode(Tag.SEQ,new ArrayList<>(list.stream().map(YamlSettingsCodec::node).toList()),FlowStyle.BLOCK);
        if (value instanceof Boolean) return new ScalarNode(Tag.BOOL,value.toString(),ScalarStyle.PLAIN);
        if (value instanceof Number) return new ScalarNode(value instanceof Float || value instanceof Double || value instanceof BigDecimal ? Tag.FLOAT : Tag.INT,value.toString(),ScalarStyle.PLAIN);
        if (value instanceof String text) return new ScalarNode(Tag.STR,text,ScalarStyle.DOUBLE_QUOTED);
        throw new SettingsException("Null or unsupported draft value");
    }
    private static void replace(MappingNode mapping, String[] path, int index, Node replacement) {
        var pairs = mapping.getValue();
        for (int i=0;i<pairs.size();i++) {
            NodeTuple pair=pairs.get(i);
            if (((ScalarNode)pair.getKeyNode()).getValue().equals(path[index])) {
                if (index+1==path.length) {
                    Node old=pair.getValueNode(); replacement.setBlockComments(old.getBlockComments());
                    // A flow collection's trailing inline comment cannot follow a new block
                    // collection end event (the emitter is already expecting the next key).
                    // Attach it to the unchanged key, so it remains beside the same path.
                    if ((replacement instanceof MappingNode || replacement instanceof SequenceNode)
                            && old.getInLineComments()!=null && !old.getInLineComments().isEmpty()) {
                        var comments=new ArrayList<org.snakeyaml.engine.v2.comments.CommentLine>();
                        if(pair.getKeyNode().getInLineComments()!=null)comments.addAll(pair.getKeyNode().getInLineComments());
                        comments.addAll(old.getInLineComments());pair.getKeyNode().setInLineComments(comments);
                    } else replacement.setInLineComments(old.getInLineComments());
                    replacement.setEndComments(old.getEndComments());
                    pairs.set(i,new NodeTuple(pair.getKeyNode(),replacement));
                } else replace((MappingNode)pair.getValueNode(),path,index+1,replacement);
                return;
            }
        }
        Node child=replacement;
        if (index+1<path.length) { var next=new MappingNode(Tag.MAP,new ArrayList<>(),FlowStyle.BLOCK); replace(next,path,index+1,replacement); child=next; }
        pairs.add(new NodeTuple(new ScalarNode(Tag.STR,path[index],ScalarStyle.PLAIN),child));
    }
    private static void remove(MappingNode mapping,String[] path,int index) {
        var pairs=mapping.getValue();
        for(int i=0;i<pairs.size();i++) {
            var pair=pairs.get(i);
            if(!((ScalarNode)pair.getKeyNode()).getValue().equals(path[index]))continue;
            if(index+1==path.length)pairs.remove(i);
            else if(pair.getValueNode() instanceof MappingNode child) {
                remove(child,path,index+1);if(child.getValue().isEmpty())pairs.remove(i);
            }
            return;
        }
    }
    static String utf8(byte[] bytes) {
        if (bytes.length>MAX_BYTES) throw new SettingsException("Settings file exceeds 1 MiB");
        try { return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString(); }
        catch (CharacterCodingException e) { throw new SettingsException("Settings file must be valid UTF-8",e); }
    }
    public static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }
}
