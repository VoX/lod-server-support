package dev.vox.lss.common.config;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import static java.nio.file.StandardOpenOption.*;

/** Adopted-path IO. Reads never rewrite; creation and explicit draft saves are separate transactions. */
public final class SettingsStore<T> {
    private static final Map<Path,Object> DIRECTORY_LOCKS=new ConcurrentHashMap<>();
    private final Path directory;
    private final String prefix;
    private final SettingsSchema<T> schema;
    private final YamlSettingsCodec<T> codec;
    private final Consumer<String> report;
    private final Object lock;
    private Path selected;
    private Path legacySource;
    private boolean explicitPath;
    private final Boundary boundary;
    /** Test fault/race injection runs after the verified temp exists, before final conflict checks. */
    @FunctionalInterface public interface Boundary { void beforePublish(Path temporary,Path destination)throws IOException; }
    public SettingsStore(Path directory,String prefix,SettingsSchema<T> schema) { this(directory,prefix,schema,s->{},(t,d)->{}); }
    public SettingsStore(Path directory,String prefix,SettingsSchema<T> schema,Consumer<String> report) { this(directory,prefix,schema,report,(t,d)->{}); }
    public SettingsStore(Path directory,String prefix,SettingsSchema<T> schema,Consumer<String> report,Boundary boundary) {
        if(!Set.of("lss","vss").contains(prefix))throw new IllegalArgumentException("Brand prefix must be lss or vss");
        this.directory=directory.toAbsolutePath().normalize();this.prefix=prefix;this.schema=schema;this.codec=new YamlSettingsCodec<>(schema,prefix);
        this.report=report;this.boundary=boundary;this.lock=DIRECTORY_LOCKS.computeIfAbsent(this.directory,p->new Object());
    }
    /** Explicit file helper for staging/fixtures; never searches unrelated config directories. */
    public static <T> SettingsStore<T> atPath(Path path,SettingsSchema<T> schema) {
        Path absolute=path.toAbsolutePath().normalize();
        var store=new SettingsStore<>(absolute.getParent(),absolute.getFileName().toString().startsWith("vss-")?"vss":"lss",schema);
        store.selected=absolute;store.explicitPath=true;
        return store;
    }
    public SettingsSchema<T> schema(){return schema;}
    public YamlSettingsCodec<T> codec(){return codec;}
    public Path path(){synchronized(lock){return selected==null?candidate(prefix,"yaml"):selected;}}
    /** Client experimental keys live beside the SELECTED main file and share its brand prefix,
     *  so a jar swap that adopts the other brand's main file adopts its sidecar too. */
    public Path sidecarPath() {
        if(!schema.isClient())return null;
        synchronized(lock) {
            Path main=path();String name=main.getFileName().toString();
            String stem=name.endsWith(".yaml")?name.substring(0,name.length()-5):name;
            stem=stem.endsWith("-config")?stem.substring(0,stem.length()-7)+"-experimental":stem+"-experimental";
            return main.resolveSibling(stem+".yaml");
        }
    }
    private byte[] readSidecar()throws IOException {
        Path sidecar=sidecarPath();
        return sidecar!=null&&Files.exists(sidecar)?readBounded(sidecar):null;
    }
    private String currentHash()throws IOException {return YamlSettingsCodec.hash(readBounded(selected),readSidecar());}
    public YamlSettingsCodec.Document<T> initialize()throws IOException {
        synchronized(lock) {
            if(selected!=null&&Files.exists(selected))return reportInitial(splitDevelopmentKeys(read()));
            if(selected==null)select();
            if(Files.exists(selected))return reportInitial(splitDevelopmentKeys(read()));
            Files.createDirectories(directory);
            if(legacySource!=null) migrate();else createFresh();
            return reportInitial(read());
        }
    }
    /** A main file written by a development build may still carry experimental keys that
     *  older releases reject. Move them out once at startup, so a downgrade before any
     *  Apply still reads the main file. Best effort: on failure the file stays readable here. */
    private YamlSettingsCodec.Document<T> splitDevelopmentKeys(YamlSettingsCodec.Document<T> document) {
        if(!codec.mainCarriesSidecarKeys(document.bytes()))return document;
        try {return publishSplit(document,codec.editSplit(document,Map.of()));}
        catch(IOException|RuntimeException e) {
            report.accept("Experimental keys remain in the main settings file: "+e.getMessage());
            return document;
        }
    }
    private YamlSettingsCodec.Document<T> reportInitial(YamlSettingsCodec.Document<T> document) {
        ReloadFeedback.normalizationLines(document.normalizations()).forEach(report);
        if(!document.normalizations().isEmpty())report.accept("YAML was left unchanged.");
        if(!document.inactivePaths().isEmpty())report.accept("Inactive on this platform: "+String.join(", ",document.inactivePaths()));
        return document;
    }
    public YamlSettingsCodec.Document<T> read()throws IOException {
        synchronized(lock) {
            if(selected==null)throw new SettingsException("Settings store has not been initialized");
            if(!Files.exists(selected))throw new SettingsException("Settings file is missing: "+selected);
            return codec.parse(readBounded(selected),readSidecar());
        }
    }
    /** Menu persistence changes the document only; it never publishes a runtime snapshot.
     *  The hash covers main + sidecar. The sidecar is published first: if the main rewrite
     *  then fails, the sidecar (which overrides main) already carries the requested values. */
    public YamlSettingsCodec.Document<T> saveDraft(String baseHash,Map<String,Object> changes)throws IOException {
        synchronized(lock) {
            var disk=read();
            if(!disk.hash().equals(baseHash))throw new SettingsException("Settings changed on disk; reopen or explicitly rebase the retained draft");
            if(changes.isEmpty())return disk;
            return publishSplit(disk,codec.editSplit(disk,changes));
        }
    }
    /** Caller holds the lock. Sidecar first; if the main rewrite then fails, the sidecar is
     *  restored so the pair never ends half-applied (which would also look like a conflict). */
    private YamlSettingsCodec.Document<T> publishSplit(YamlSettingsCodec.Document<T> disk,YamlSettingsCodec.Output output)throws IOException {
        String expected=disk.hash();
        boolean sidecarChanged=!Arrays.equals(output.sidecar(),disk.sidecar());
        if(sidecarChanged) {
            publishSidecar(output.sidecar(),expected);
            expected=YamlSettingsCodec.hash(disk.bytes(),output.sidecar());
        }
        if(!Arrays.equals(output.main(),disk.bytes())) {
            Path temp=writeTemp(output.main());
            try {
                codec.parse(readBounded(temp));boundary.beforePublish(temp,selected);
                if(!Files.exists(selected)||!currentHash().equals(expected))throw new SettingsException("Settings changed on disk; draft was retained without replacing the file");
                // Portable hash+rename cannot exclude an uncooperative editor in the last race window.
                move(temp,selected);
                forceDirectory();
            } catch(IOException|RuntimeException failure) {
                if(sidecarChanged) {
                    try {publishSidecar(disk.sidecar(),expected);}
                    catch(IOException|RuntimeException rollback) {failure.addSuppressed(rollback);}
                }
                throw failure;
            } finally {Files.deleteIfExists(temp);}
        }
        return codec.parse(output.main(),output.sidecar());
    }
    /** Replace or delete the sidecar after re-checking the combined hash. */
    private void publishSidecar(byte[] bytes,String expectedHash)throws IOException {
        Path sidecar=sidecarPath();
        if(bytes==null) {
            if(!currentHash().equals(expectedHash))throw new SettingsException("Settings changed on disk; draft was retained without replacing the file");
            Files.deleteIfExists(sidecar);forceDirectory();return;
        }
        Path temp=writeTemp(bytes);
        try {
            YamlSettingsCodec.sidecarTree(readBounded(temp));boundary.beforePublish(temp,sidecar);
            if(!Files.exists(selected)||!currentHash().equals(expectedHash))throw new SettingsException("Settings changed on disk; draft was retained without replacing the file");
            move(temp,sidecar);
            forceDirectory();
        } finally {Files.deleteIfExists(temp);}
    }
    private static void move(Path temp,Path target)throws IOException {
        try { Files.move(temp,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING); }
        catch(AtomicMoveNotSupportedException e){throw new SettingsException("Atomic settings replacement is unsupported on this filesystem; move the configuration to a supported local filesystem",e);}
    }
    /** {@code sidecar} is null when the sidecar file does not exist. */
    public record SourceIdentity(long size,FileTime modified,Object fileKey,SourceIdentity sidecar) {}
    private SourceIdentity identity()throws IOException {
        var a=Files.readAttributes(selected,BasicFileAttributes.class);
        Path path=sidecarPath();SourceIdentity side=null;
        if(path!=null&&Files.exists(path)) {
            var b=Files.readAttributes(path,BasicFileAttributes.class);
            side=new SourceIdentity(b.size(),b.lastModifiedTime(),b.fileKey(),null);
        }
        return new SourceIdentity(a.size(),a.lastModifiedTime(),a.fileKey(),side);
    }
    /** Last full hash check runs on the IO worker, never on a tick/client owner. */
    public SourceIdentity verifySource(String hash)throws IOException {
        synchronized(lock) {
            SourceIdentity before=identity();
            if(!matches(hash))throw new SettingsException("Settings changed on disk during reload; retry");
            SourceIdentity after=identity();
            if(!before.equals(after))throw new SettingsException("Settings changed on disk during reload; retry");
            return after;
        }
    }
    /** Cheap metadata check narrows the final queue window; this is not a filesystem CAS. */
    public boolean identityMatches(SourceIdentity expected)throws IOException {
        synchronized(lock){return Files.exists(selected)&&expected.equals(identity());}
    }
    public boolean matches(String hash)throws IOException {synchronized(lock){return selected!=null&&Files.exists(selected)&&currentHash().equals(hash);}}
    private void select() {
        List<Path> yaml=List.of(candidate(prefix,"yaml"),candidate(other(),"yaml"));
        List<Path> json=List.of(candidate(prefix,"json"),candidate(other(),"json"));
        var existing=new ArrayList<Path>();for(Path p:yaml)if(Files.exists(p))existing.add(p);for(Path p:json)if(Files.exists(p))existing.add(p);
        for(Path p:yaml)if(Files.exists(p)){selected=p;break;}
        if(selected==null)for(Path p:json)if(Files.exists(p)){legacySource=p;selected=p.resolveSibling(p.getFileName().toString().replaceFirst("\\.json$",".yaml"));break;}
        if(selected==null)selected=yaml.getFirst();
        if(existing.size()>1)report.accept("Multiple settings candidates exist; selected "+(legacySource==null?selected:legacySource));
    }
    private void createFresh()throws IOException {
        byte[] output=codec.defaults();codec.parse(output);
        Path temp=writeTemp(output);
        try {
            boundary.beforePublish(temp,selected);checkNoYaml();installNew(temp,selected);forceDirectory();
            report.accept("Created settings "+selected);
        }finally{Files.deleteIfExists(temp);}
    }
    private void migrate()throws IOException {
        byte[] original=readBounded(legacySource);
        var result=LegacySettingsMigration.migrate(original,schema);
        var emitted=new LinkedHashMap<>(result.values());
        var oldKeys=LegacySettingsMigration.readJson(original).keySet();
        // Fresh platform templates omit inactive groups; migration need not invent absent ones.
        if(!schema.isClient()) {
            // A key the templates only show commented out (0.22 and older reject it) must not
            // be written by a migration either: the migrated file would not load on those
            // versions, and following the template's "delete the #" would then duplicate it.
            // (The commented line sits above a key in the template, not at the end of its
            // group: this output is re-emitted, and a comment block that ends a group comes
            // out at column 0, where deleting the "#" would make a top-level key.)
            if(!oldKeys.contains("showPermissionRefusal"))emitted.remove("service.show_permission_refusal");
            if(!schema.isPaper()) {if(!oldKeys.contains("updateEvents"))emitted.remove("paper.update_events");if(((Map<?,?>)emitted.get("lod.distance.by_world")).isEmpty())emitted.remove("lod.distance.by_world");}
            if(schema.isPaper()) {
                if(!oldKeys.contains("lodStoreBackfill"))emitted.remove("storage.lod_store.backfill.enabled");
                if(!oldKeys.contains("lodStoreBackfillColumnsPerSecond"))emitted.remove("storage.lod_store.backfill.columns_per_second");
            }
        }
        // Experimental keys never enter the main file; an existing sidecar is never replaced.
        var split=codec.generateSplit(emitted);
        byte[] output=split.main();
        Path backup=backup(original);
        Path temp=writeTemp(output);
        try {
            codec.parse(readBounded(temp));boundary.beforePublish(temp,selected);
            if(!Arrays.equals(original,readBounded(legacySource)))throw new SettingsException("Legacy JSON changed during migration; retry at next startup");
            Path sidecar=sidecarPath();
            if(split.sidecar()!=null&&!Files.exists(sidecar)) {
                Path sideTemp=writeTemp(split.sidecar());
                try {installNew(sideTemp,sidecar);}finally{Files.deleteIfExists(sideTemp);}
            }
            checkNoYaml();installNew(temp,selected);forceDirectory();
            report.accept("Migrated "+legacySource+" to "+selected+"; backup "+backup+"; ignored keys="+result.ignoredKeys()+"; normalized paths="+result.normalizations().stream().map(SettingsSchema.Normalization::path).toList());
            ReloadFeedback.normalizationLines(result.normalizations()).forEach(report);
            result.warnings().forEach(report);
        }finally{Files.deleteIfExists(temp);}
    }
    private Path backup(byte[] bytes)throws IOException {
        for(int n=0;n<Integer.MAX_VALUE;n++) {
            Path target=legacySource.resolveSibling(legacySource.getFileName()+".migrated.bak"+(n==0?"":"."+n));
            try {writeExclusive(target,bytes);}catch(FileAlreadyExistsException e){continue;}
            if(!Arrays.equals(bytes,readBounded(target)))throw new SettingsException("Migration backup verification failed; source remains unchanged");
            forceDirectory();return target;
        }
        throw new SettingsException("Cannot allocate a unique migration backup");
    }
    private void checkNoYaml() {
        if(explicitPath) {if(Files.exists(selected))throw new SettingsException("Settings target appeared during creation; existing bytes were preserved");return;}
        if(Files.exists(candidate(prefix,"yaml"))||Files.exists(candidate(other(),"yaml")))throw new SettingsException("A YAML settings file appeared during creation; retry at next startup");
    }
    /** Hard-link installation is create-only even when another process races the target. */
    static void installNew(Path temporary,Path target)throws IOException {
        try {Files.createLink(target,temporary);}
        catch(FileAlreadyExistsException e){throw new SettingsException("Settings target appeared during creation; existing bytes were preserved",e);}
        catch(UnsupportedOperationException|FileSystemException e){throw new SettingsException("Create-only atomic settings installation is unsupported here; use a filesystem supporting same-directory hard links",e);}
    }
    private Path writeTemp(byte[] bytes)throws IOException {
        Path temp=Files.createTempFile(directory,".lss-settings-",".tmp");
        try(var channel=FileChannel.open(temp,WRITE,TRUNCATE_EXISTING)) {writeAll(channel,bytes);channel.force(true);}
        catch(IOException e){Files.deleteIfExists(temp);throw e;}
        return temp;
    }
    private static void writeExclusive(Path path,byte[] bytes)throws IOException {
        try(var channel=FileChannel.open(path,WRITE,CREATE_NEW)){writeAll(channel,bytes);channel.force(true);}
    }
    private static void writeAll(FileChannel channel,byte[] bytes)throws IOException {ByteBuffer b=ByteBuffer.wrap(bytes);while(b.hasRemaining())channel.write(b);}
    static byte[] readBounded(Path path)throws IOException {try(var in=Files.newInputStream(path)){byte[] b=in.readNBytes(YamlSettingsCodec.MAX_BYTES+1);if(b.length>YamlSettingsCodec.MAX_BYTES)throw new SettingsException("Settings file exceeds 1 MiB");return b;}}
    private void forceDirectory() {
        // Some Windows filesystems do not expose directory handles; each data file was forced.
        try(var channel=FileChannel.open(directory,READ)){channel.force(true);}catch(IOException|UnsupportedOperationException ignored){}
    }
    private String other(){return prefix.equals("lss")?"vss":"lss";}
    private Path candidate(String brand,String extension){return directory.resolve(brand+"-"+schema.side()+"-config."+extension);}
}
