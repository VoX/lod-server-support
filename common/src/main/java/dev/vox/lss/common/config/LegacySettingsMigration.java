package dev.vox.lss.common.config;

import com.google.gson.stream.*;
import java.io.*;
import java.math.BigDecimal;
import java.util.*;

/** One-time bounded JSON tree adapter; it never participates in active runtime reads. */
public final class LegacySettingsMigration {
    private LegacySettingsMigration() {}
    public record Result<T>(T settings, Map<String,Object> values, int ignoredKeys,
                            List<SettingsSchema.Normalization> normalizations, List<String> warnings) {}
    public static <T> Result<T> migrate(byte[] bytes,SettingsSchema<T> schema) {
        Map<String,Object> old=readJson(bytes);
        var values=new LinkedHashMap<>(schema.defaultValues());
        var recognized=new HashSet<String>();
        var warnings=new ArrayList<String>();
        for(var d:schema.descriptors()) {
            recognized.add(d.legacyKey());
            if(!old.containsKey(d.legacyKey()))continue;
            Object value=old.get(d.legacyKey());
            String path=d.path();
            if(path.equals("lod.distance.by_dimension")||path.equals("lod.distance.by_world")
                    ||path.equals("storage.lod_store.enabled")||path.endsWith("mib_per_second"))continue;
            if(path.equals("far_players.mode")) {
                String mode=value instanceof String s?s.trim().toLowerCase(Locale.ROOT):"off";
                value=switch(mode) { case "opt-in","optin","opt_in"->"opt_in"; case "on"->"on"; case "required"->"required"; default->"off"; };
            } else if(path.equals("privacy.xray.mode")) {
                String mode=value instanceof String s?s.trim().toLowerCase(Locale.ROOT):"auto";
                value=Set.of("auto","on","off").contains(mode)?mode:"auto";
            } else if(path.equals("generation.timeout_ticks")) {
                int seconds=value==null?60:SettingsSchema.integer(primitive(value,SettingsSchema.Kind.INTEGER,path),path);
                value=Math.max(1,Math.min(600,seconds))*20;
            } else if(path.equals("updates.dirty_broadcast_interval_ticks")) {
                int seconds=value==null?10:SettingsSchema.integer(primitive(value,SettingsSchema.Kind.INTEGER,path),path);
                value=seconds<=0?0:Math.min(300,seconds)*20;
            } else if(path.equals("compatibility.block_fallbacks.default")) {
                if(value==null||value instanceof String s&&s.isBlank())value="minecraft:stone";
            } else if(d.kind()==SettingsSchema.Kind.STRING_LIST) {
                if(value==null) {
                    value=path.equals("privacy.xray.hidden_blocks")?d.defaultValue():List.of();
                } else if(value instanceof List<?> list) value=legacyStrings(list,path);
            } else if(d.kind()==SettingsSchema.Kind.STRING_MAP) {
                var clean=new LinkedHashMap<String,Object>();
                if(value!=null) {
                    if(!(value instanceof Map<?,?> m))throw new SettingsException("Legacy mapping required for "+path);
                    int dropped=0;
                    for(var entry:m.entrySet()) {
                        if(!(entry.getKey() instanceof String k)||k.isBlank()||entry.getValue()==null){dropped++;continue;}
                        Object raw=entry.getValue();
                        if(!(raw instanceof String||raw instanceof Number||raw instanceof Boolean))throw new SettingsException("Legacy string mapping values must be primitive at "+path);
                        String text=raw.toString();
                        if(text.isBlank()){dropped++;continue;}
                        clean.put(k,text);
                    }
                    if(dropped>0)warnings.add(path+": removed "+dropped+" null/blank entries");
                }
                value=clean;
            } else if(d.kind()==SettingsSchema.Kind.STRING_GROUPS) {
                var groups=new ArrayList<List<?>>();
                if(value!=null) {
                    if(!(value instanceof List<?> list))throw new SettingsException("Legacy list required for "+path);
                    int dropped=0;
                    for(Object group:list) {
                        if(group instanceof List<?> g&&g.stream().allMatch(LegacySettingsMigration::stringPrimitive))groups.add(legacyStrings(g,path));
                        else dropped++;
                    }
                    if(dropped>0)warnings.add(path+": removed "+dropped+" structurally invalid groups");
                }
                value=groups;
            } else if(value==null)continue; // old primitive constructor defaults
            values.put(path,primitive(value,d.kind(),path));
        }
        if(!schema.isClient()) {
            // Freeze the three old vanilla radii before overlaying exact legacy overrides.
            // Old JSON clamped its radius to at least 1: a legacy 0 meant 1, never today's "off".
            int radius=Math.max(1,(Integer)values.get("lod.distance.default_chunks"));
            values.put("lod.distance.default_chunks",radius);
            var dimensions=new LinkedHashMap<String,Integer>();
            for(String dimension:List.of("minecraft:overworld","minecraft:the_nether","minecraft:the_end"))dimensions.put(dimension,radius);
            values.put("lod.distance.by_dimension",dimensions); values.put("lod.distance.by_world",Map.of());
            Object store=old.get("lodStore");
            values.put("storage.lod_store.enabled",store instanceof String s&&Set.of("on","full").contains(s.trim().toLowerCase(Locale.ROOT)));
            bandwidth(old,values,"PerPlayer",25,recognized);
            bandwidth(old,values,"Global",75,recognized);
            Object worldMap=old.get("lodDistanceChunksByWorld");
            if(worldMap!=null) {
                if(!(worldMap instanceof Map<?,?> map))throw new SettingsException("Legacy world distances must be a mapping");
                var worlds=new LinkedHashMap<String,Integer>();int dropped=0;boolean duplicated=false;
                for(var entry:map.entrySet()) {
                    if(!(entry.getKey() instanceof String key)||entry.getValue()==null) { dropped++;continue; }
                    key=key.trim();if(key.isEmpty()||key.length()>256){dropped++;continue;}
                    int v=SettingsSchema.integer(primitive(entry.getValue(),SettingsSchema.Kind.INTEGER,"lod.distance[entry]"),"lod.distance[entry]");
                    v=Math.max(1,Math.min(2048,v));
                    if(schema.isPaper())worlds.put(key,v);
                    if(SettingsSchema.canonicalIdentifier(key)){dimensions.put(key,v);duplicated=true;}else if(!schema.isPaper())dropped++;
                }
                values.put("lod.distance.by_dimension",dimensions); values.put("lod.distance.by_world",worlds);
                if(dropped>0)warnings.add("lod.distance: omitted "+dropped+" inert/invalid entries");
                if(schema.isPaper()&&duplicated)warnings.add("lod.distance: preserved exact world and dimension interpretations");
            }
        }
        if(schema.isClient()) {
            // Fresh files enable map ingestion; migration preserves the old behaviour, where an
            // absent enableXaeroMapBridge meant off (the storage.lod_store.enabled precedent).
            Object bridge=old.get("enableXaeroMapBridge");
            values.put("integrations.map_ingestion.enabled",bridge==null?Boolean.FALSE
                    :primitive(bridge,SettingsSchema.Kind.BOOLEAN,"integrations.map_ingestion.enabled"));
        }
        var decoded=schema.fromValues(values);
        // Migration intentionally emits normalized values; source bytes and backup retain originals.
        var normalized=schema.values(decoded.normalized());
        int ignored=(int)old.keySet().stream().filter(k->!recognized.contains(k)).count();
        var normalizations=new LinkedHashMap<String,SettingsSchema.Normalization>();
        for(var d:schema.descriptors()) {
            if(old.containsKey(d.legacyKey())&&!equivalent(old.get(d.legacyKey()),normalized.get(d.path()))) {
                Object requested=old.get(d.legacyKey());
                normalizations.put(d.path(),new SettingsSchema.Normalization(d.path(),d.sensitive()?"<private>":requested,d.sensitive()?"<private>":normalized.get(d.path())));
            }
        }
        for(var n:decoded.normalizations())normalizations.putIfAbsent(n.path(),n);
        return new Result<>(decoded.normalized(),normalized,ignored,List.copyOf(normalizations.values()),List.copyOf(warnings));
    }
    private static boolean stringPrimitive(Object value) { return value instanceof String||value instanceof Number||value instanceof Boolean; }
    private static List<String> legacyStrings(List<?> values,String path) {
        var strings=new ArrayList<String>();
        for(Object value:values) {
            if(value==null)continue;
            if(!stringPrimitive(value))throw new SettingsException("Legacy string list entries must be primitive at "+path);
            strings.add(value.toString());
        }
        return List.copyOf(strings);
    }
    /** Retain the lexical token for Gson-compatible number-to-string migration. */
    private static final class JsonNumber extends Number {
        private final String token;
        JsonNumber(String token){this.token=token;}
        @Override public int intValue(){return new BigDecimal(token).intValue();}
        @Override public long longValue(){return new BigDecimal(token).longValue();}
        @Override public float floatValue(){return Float.parseFloat(token);}
        @Override public double doubleValue(){return Double.parseDouble(token);}
        @Override public String toString(){return token;}
    }
    private static boolean equivalent(Object a,Object b) {
        if(a instanceof Number x&&b instanceof Number y)return new BigDecimal(x.toString()).compareTo(new BigDecimal(y.toString()))==0;
        if(a instanceof List<?> x&&b instanceof List<?> y){if(x.size()!=y.size())return false;for(int i=0;i<x.size();i++)if(!equivalent(x.get(i),y.get(i)))return false;return true;}
        if(a instanceof Map<?,?> x&&b instanceof Map<?,?> y){if(!x.keySet().equals(y.keySet()))return false;for(Object k:x.keySet())if(!equivalent(x.get(k),y.get(k)))return false;return true;}
        return Objects.equals(a,b);
    }
    private static void bandwidth(Map<String,Object> old,Map<String,Object> values,String suffix,int fallback,Set<String> recognized) {
        String modern="mbPerSecondLimit"+suffix, legacy="bytesPerSecondLimit"+suffix;
        recognized.add(legacy);
        double result=fallback;
        Object newer=old.get(modern),older=old.get(legacy);
        if(newer!=null) {
            double n=((Number)primitive(newer,SettingsSchema.Kind.NUMBER,"network.bandwidth")).doubleValue();
            if(n>=0)result=n;
            else if(older!=null) { double b=((Number)primitive(older,SettingsSchema.Kind.INTEGER,"network.bandwidth")).doubleValue();if(b>=0)result=b/1048576d; }
        } else if(older!=null) { double b=((Number)primitive(older,SettingsSchema.Kind.INTEGER,"network.bandwidth")).doubleValue();if(b>=0)result=b/1048576d; }
        values.put("network.bandwidth."+(suffix.equals("PerPlayer")?"per_player":"global")+"_mib_per_second",result);
    }
    private static Object primitive(Object value,SettingsSchema.Kind kind,String path) {
        if(kind==SettingsSchema.Kind.STRING&&stringPrimitive(value))return value.toString();
        if(value instanceof String s) {
            if(kind==SettingsSchema.Kind.BOOLEAN) {
                // Gson's old Boolean adapter accepted string values via parseBoolean.
                return Boolean.valueOf(s);
            }
            if(kind==SettingsSchema.Kind.INTEGER||kind==SettingsSchema.Kind.NUMBER) {
                if(s.length()>128)throw new SettingsException("Legacy numeric scalar is too large at "+path);
                try { value=new BigDecimal(s); }catch(NumberFormatException e){throw new SettingsException("Legacy finite number required at "+path);}
            }
        }
        if(kind==SettingsSchema.Kind.INTEGER)return SettingsSchema.integer(value,path);
        if(kind==SettingsSchema.Kind.NUMBER) {
            if(!(value instanceof Number n)||!Double.isFinite(n.doubleValue()))throw new SettingsException("Legacy finite number required at "+path);
        }
        return value;
    }
    /** Bounded legacy-Gson syntax; repeated root fields keep the old loader's last value. */
    public static Map<String,Object> readJson(byte[] bytes) {
        String text=YamlSettingsCodec.utf8(bytes);
        try {
            try(var reader=reader(text)) {
                int depth=0,count=0;var keys=new ArrayDeque<Set<String>>();var objects=new ArrayDeque<Boolean>();
                while(reader.peek()!=JsonToken.END_DOCUMENT) {
                    JsonToken token=reader.peek();
                    if(depth==0&&(count!=0||token!=JsonToken.BEGIN_OBJECT))
                        throw new SettingsException("Legacy JSON must contain one object");
                    switch(token) {
                        case BEGIN_OBJECT,BEGIN_ARRAY -> {
                            if(++depth>YamlSettingsCodec.MAX_DEPTH)throw new SettingsException("JSON nesting exceeds "+YamlSettingsCodec.MAX_DEPTH);
                            if(++count>YamlSettingsCodec.MAX_NODES)throw new SettingsException("JSON node count exceeds "+YamlSettingsCodec.MAX_NODES);
                            boolean object=token==JsonToken.BEGIN_OBJECT;objects.push(object);keys.push(new HashSet<>());
                            if(object)reader.beginObject();else reader.beginArray();
                        }
                        case END_OBJECT,END_ARRAY -> { depth--;objects.pop();keys.pop();if(token==JsonToken.END_OBJECT)reader.endObject();else reader.endArray(); }
                        case NAME -> { if(++count>YamlSettingsCodec.MAX_NODES)throw new SettingsException("JSON node count exceeds "+YamlSettingsCodec.MAX_NODES);if(!keys.peek().add(reader.nextName())&&depth>1)throw new SettingsException("Duplicate legacy mapping key"); }
                        case STRING,NUMBER -> { String scalar=reader.nextString();if(token==JsonToken.NUMBER&&scalar.length()>128)throw new SettingsException("JSON numeric scalar is too large");if(++count>YamlSettingsCodec.MAX_NODES)throw new SettingsException("JSON node count exceeds "+YamlSettingsCodec.MAX_NODES); }
                        case BOOLEAN -> { reader.nextBoolean();if(++count>YamlSettingsCodec.MAX_NODES)throw new SettingsException("JSON node count exceeds "+YamlSettingsCodec.MAX_NODES); }
                        case NULL -> { reader.nextNull();if(++count>YamlSettingsCodec.MAX_NODES)throw new SettingsException("JSON node count exceeds "+YamlSettingsCodec.MAX_NODES); }
                        default -> throw new SettingsException("Invalid JSON token");
                    }
                }
            }
            try(var reader=reader(text)) {
                Object tree=jsonValue(reader);
                if(!(tree instanceof Map<?,?>))throw new SettingsException("Legacy JSON must contain one object");
                @SuppressWarnings("unchecked") var result=(Map<String,Object>)tree;return result;
            }
        }catch(SettingsException e){throw e;}catch(IOException|IllegalStateException|NumberFormatException e){throw new SettingsException("Invalid legacy JSON; repair it before migration",e);}
    }
    private static JsonReader reader(String text) { var r=new JsonReader(new StringReader(text));r.setLenient(true);return r; }
    private static Object jsonValue(JsonReader r)throws IOException {
        return switch(r.peek()) {
            case BEGIN_OBJECT -> {var m=new LinkedHashMap<String,Object>();r.beginObject();while(r.hasNext())m.put(r.nextName(),jsonValue(r));r.endObject();yield m;}
            case BEGIN_ARRAY -> {var l=new ArrayList<Object>();r.beginArray();while(r.hasNext())l.add(jsonValue(r));r.endArray();yield l;}
            case STRING -> r.nextString();case NUMBER -> new JsonNumber(r.nextString());case BOOLEAN -> r.nextBoolean();case NULL -> {r.nextNull();yield null;}
            default -> throw new SettingsException("Empty/invalid legacy JSON");
        };
    }
}
