package dev.vox.lss.common.config;

import java.io.IOException;
import java.util.*;

/** Stable revisioned publication point. Platform owners alone call commit and session methods. */
public final class SettingsHandle<T> implements AutoCloseable {
    public record State<T>(T configured,T effective,T boot,T session,long revision,
                           Set<String> pendingRestart,Set<String> pendingReconnect) {
        public State {pendingRestart=Set.copyOf(pendingRestart);pendingReconnect=Set.copyOf(pendingReconnect);}
        public Set<String> pendingPaths(){var p=new LinkedHashSet<>(pendingRestart);p.addAll(pendingReconnect);return Set.copyOf(p);}
    }
    public record Prepared<T>(long baseRevision,long lifecycle,long request,YamlSettingsCodec.Document<T> document, SettingsStore.SourceIdentity sourceIdentity) {}
    public record Commit<T>(T previous,T effective,Set<String> changedPaths,Set<String> pendingRestart,
                            Set<String> pendingReconnect,List<SettingsSchema.Normalization> normalizations,List<String> inactivePaths,boolean unchanged) {
        public Commit {changedPaths=Set.copyOf(changedPaths);pendingRestart=Set.copyOf(pendingRestart);pendingReconnect=Set.copyOf(pendingReconnect);normalizations=List.copyOf(normalizations);inactivePaths=List.copyOf(inactivePaths);}
        public Set<String> pendingPaths(){var p=new LinkedHashSet<>(pendingRestart);p.addAll(pendingReconnect);return Set.copyOf(p);}
    }
    /** Published policy still awaiting subsystem receipts. Identity prevents late lifecycle acknowledgements. */
    public record Adoption<T>(T previous, T effective, long revision, Set<String> paths) {
        public Adoption { paths = Set.copyOf(paths); }
    }
    private volatile Adoption<T> pendingAdoption;
    private volatile long adoptedRevision;
    private final SettingsStore<T> store;
    private final SettingsSchema<T> schema;
    private volatile State<T> state;
    /** Schema paths the ACCEPTED file itself sets (the startup file, then every committed
     *  reload), as opposed to values that come from the schema default. Published with
     *  the commit, also when no value changed: writing a default explicitly is a choice. */
    private volatile Set<String> declaredPaths;
    private long lifecycle=1,sequence,activeRequest;
    private boolean closed;
    private Object connection;
    public SettingsHandle(SettingsStore<T> store)throws IOException {
        this.store=store;schema=store.schema();var initial=store.initialize();
        T active=initial.normalized();state=new State<>(initial.configured(),active,active,active,0,Set.of(),Set.of());
        declaredPaths=initial.declaredPaths(schema.descriptorsByPath().keySet());
    }
    /** Whether the accepted settings file itself sets {@code path} (see {@link #declaredPaths}). */
    public boolean declares(String path){return declaredPaths.contains(path);}
    public SettingsStore<T> store(){return store;}
    public SettingsSchema<T> schema(){return schema;}
    public State<T> state(){return state;}
    public Adoption<T> pendingAdoption(){return pendingAdoption;}
    public long adoptedRevision(){return adoptedRevision;}
    public synchronized void acknowledge(Adoption<T> adoption) {
        if (!closed && adoption != null && pendingAdoption == adoption) {
            adoptedRevision = adoption.revision();
            pendingAdoption = null;
        }
    }
    public synchronized boolean busy(){return activeRequest!=0;}
    /** Blocking bounded IO, intended for the caller's bounded settings worker. */
    public Prepared<T> prepareReload()throws IOException {
        final long revision,life,request;
        synchronized(this){checkOpen();if(activeRequest!=0)throw new SettingsException("Settings reload is busy");activeRequest=++sequence;request=activeRequest;revision=state.revision();life=lifecycle;}
        try {var document=store.read();var identity=store.verifySource(document.hash());synchronized(this){checkCurrent(revision,life,request);}return new Prepared<>(revision,life,request,document,identity);}
        catch(IOException|RuntimeException e){synchronized(this){if(activeRequest==request)activeRequest=0;}throw e;}
    }
    public synchronized void cancelPrepared(Prepared<T> prepared){if(prepared!=null&&prepared.request()==activeRequest&&prepared.lifecycle()==lifecycle)activeRequest=0;}
    /** Cancel pending IO on deadline; a late completion cannot acquire a newer request's slot. */
    public synchronized void cancelReload(){activeRequest=0;sequence++;}
    public synchronized Commit<T> commit(Prepared<T> prepared)throws IOException {
        try {
            checkCurrent(prepared.baseRevision(),prepared.lifecycle(),prepared.request());
            if(!store.identityMatches(prepared.sourceIdentity()))throw new SettingsException("Settings changed on disk during reload; retry to read the newer file");
            State<T> old=state;
            T requested=prepared.document().configured(),normalized=prepared.document().normalized();
            Map<String,Object> wanted=schema.values(normalized), effective=new LinkedHashMap<>(wanted);
            Map<String,Object> boot=schema.values(old.boot()),session=schema.values(old.session());
            var restart=new LinkedHashSet<String>();var reconnect=new LinkedHashSet<String>();
            for(var d:schema.descriptors()) {
                if(!schema.applicable(d)){effective.put(d.path(),schema.values(old.effective()).get(d.path()));continue;}
                if(d.timing()==SettingsSchema.Timing.R) {effective.put(d.path(),boot.get(d.path()));if(!Objects.equals(wanted.get(d.path()),boot.get(d.path())))restart.add(d.path());}
                if(d.timing()==SettingsSchema.Timing.S&&connection!=null) {effective.put(d.path(),session.get(d.path()));if(!Objects.equals(wanted.get(d.path()),session.get(d.path())))reconnect.add(d.path());}
            }
            var merged=schema.fromValues(effective);
            if(!merged.normalizations().isEmpty())throw new SettingsException("Reload would split a dependent settings group; keep correlated values compatible with active restart/session values");
            T next=merged.normalized();var changed=diff(schema.values(old.effective()),schema.values(next));
            boolean unchanged=Objects.equals(old.configured(),requested)&&changed.isEmpty()&&old.pendingRestart().equals(restart)&&old.pendingReconnect().equals(reconnect);
            if(!unchanged)state=new State<>(requested,next,old.boot(),connection==null?next:old.session(),old.revision()+1,restart,reconnect);
            declaredPaths=prepared.document().declaredPaths(schema.descriptorsByPath().keySet());
            // Publication is not an acknowledgement. Keep failed/partial owner work even when
            // the file is identical, or a newer candidate reverts some already-adopted values.
            T previous = pendingAdoption == null ? old.effective() : pendingAdoption.previous();
            var reconcile = new LinkedHashSet<>(changed);
            if (pendingAdoption != null) reconcile.addAll(pendingAdoption.paths());
            if (!reconcile.isEmpty()) pendingAdoption = new Adoption<>(previous, state.effective(), state.revision(), reconcile);
            else adoptedRevision = state.revision();
            return new Commit<>(previous,state.effective(),reconcile,restart,reconnect,prepared.document().normalizations(),prepared.document().inactivePaths(),unchanged);
        }finally{cancelPrepared(prepared);}
    }
    /** Same physical connection means no adoption (including play/configuration listener replacement). */
    public synchronized void beginSession(Object physicalConnection) {
        checkOpen();Objects.requireNonNull(physicalConnection);
        if(connection==physicalConnection)return;
        connection=physicalConnection;adoptSession();
    }
    public synchronized void endSession(Object physicalConnection) {
        if(connection!=physicalConnection)return;
        connection=null;adoptSession();
    }
    private void adoptSession() {
        // Cancels parses captured against the old connection boundary.
        lifecycle++;activeRequest=0;
        State<T> old=state;var wanted=schema.values(schema.fromValues(schema.values(old.configured())).normalized());
        var values=new LinkedHashMap<>(schema.values(old.effective()));
        for(var d:schema.descriptors())if(d.timing()==SettingsSchema.Timing.S)values.put(d.path(),wanted.get(d.path()));
        T next=schema.fromValues(values).normalized();
        state=new State<>(old.configured(),next,old.boot(),next,old.revision(),old.pendingRestart(),Set.of());
        if (pendingAdoption != null)
            pendingAdoption = new Adoption<>(pendingAdoption.previous(), next, state.revision(), pendingAdoption.paths());
    }
    private Set<String> diff(Map<String,Object> before,Map<String,Object> after){var out=new LinkedHashSet<String>();for(String key:after.keySet())if(!Objects.equals(before.get(key),after.get(key)))out.add(key);return out;}
    private void checkOpen(){if(closed)throw new SettingsException("Settings lifecycle has stopped");}
    private void checkCurrent(long revision,long life,long request){checkOpen();if(state.revision()!=revision||lifecycle!=life||activeRequest!=request)throw new SettingsException("Settings reload was cancelled or superseded by a lifecycle change");}
    @Override public synchronized void close(){closed=true;lifecycle++;activeRequest=0;pendingAdoption=null;}
}
