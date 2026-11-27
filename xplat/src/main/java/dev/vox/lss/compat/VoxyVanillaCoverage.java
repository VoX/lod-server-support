package dev.vox.lss.compat;

import dev.vox.lss.common.LSSLogger;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.ref.WeakReference;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Fill the loaded vanilla border from vanilla itself, independently of the remote source.
 * Runs only while an LSS or DH terrain session is active and {@code lod.voxy_border_fill}
 * is on, so plain Voxy users (singleplayer, vanilla servers) keep Voxy's own behaviour.
 * Its backlog gate sits above DH's, so real vanilla border terrain never queues behind
 * far DH approximations.
 */
final class VoxyVanillaCoverage {
    static final int LOOKUPS_PER_TICK=128,MAX_TRACKED=32_768;
    static final int BACKLOG_GATE=2048;
    static final int SUBMISSIONS_PER_TICK=16,SUBMISSIONS_PER_SECOND=256;
    static final long RETRY_INTERVAL=1_000_000_000L;
    interface Access {
        Object destination(ClientLevel level) throws Throwable;
        boolean ingest(LevelChunk chunk) throws Throwable;
        int backlog();
    }
    private record Retry(WeakReference<LevelChunk> chunk,long due) {}
    private final Access access;
    private final Map<Long,WeakReference<LevelChunk>> accepted=new LinkedHashMap<>();
    private final Map<Long,Retry> retries=new LinkedHashMap<>();
    private ClientLevel level;
    private Object destination;
    private int centerX,centerZ,radius=-1,sweepX,sweepZ;
    private long creditAt;
    private double credit=SUBMISSIONS_PER_TICK;
    private ClientLevel arrivalLevel;
    private final Map<Long,WeakReference<LevelChunk>> arrivals=new LinkedHashMap<>();
    private boolean disabled;
    private int tickSubmissions,tickLookups,tickCandidates;
    /** Chunks held back because the sky light Voxy would read is not there yet (#429), with
     *  the time each was first seen so. With no deadline a stuck hold must be visible. */
    private final Map<Long,Long> skyWaiting=new LinkedHashMap<>();

    VoxyVanillaCoverage(Access access) {this.access=access;}

    /** Optional ABI domain: unavailable support must never disable ordinary raw ingestion. */
    static VoxyVanillaCoverage resolve(VoxyCompat.ClassResolver resolver) {
        try {
            Class<?> id=resolver.resolve("me.cortex.voxy.commonImpl.WorldIdentifier");
            var nullable=id.getMethod("getNullable");
            if(nullable.getReturnType().isPrimitive())return null;
            var lookup=MethodHandles.publicLookup();
            MethodHandle identify=lookup.unreflect(id.getMethod("of",Level.class))
                    .asType(MethodType.methodType(Object.class,Level.class));
            MethodHandle engine=lookup.unreflect(nullable).asType(MethodType.methodType(Object.class,Object.class));
            Class<?> instance=resolver.resolve("me.cortex.voxy.commonImpl.VoxyInstance");
            MethodHandle current=lookup.unreflect(resolver.resolve("me.cortex.voxy.commonImpl.VoxyCommon").getMethod("getInstance"))
                    .asType(MethodType.methodType(Object.class));
            MethodHandle enabled=lookup.unreflect(instance.getMethod("isIngestEnabled",id))
                    .asType(MethodType.methodType(boolean.class,Object.class,Object.class));
            MethodHandle ingest=lookup.unreflect(resolver.resolve("me.cortex.voxy.common.world.service.VoxelIngestService")
                    .getMethod("tryAutoIngestChunk",LevelChunk.class));
            if(!ingest.type().equals(MethodType.methodType(boolean.class,LevelChunk.class)))return null;
            return new VoxyVanillaCoverage(new Access() {
                public Object destination(ClientLevel level) throws Throwable {
                    Object key=(Object)identify.invokeExact((Level)level);
                    Object owner=(Object)current.invokeExact();
                    return key==null || owner==null || !(boolean)enabled.invokeExact(owner,key)?null:(Object)engine.invokeExact(key);
                }
                public boolean ingest(LevelChunk chunk) throws Throwable {return (boolean)ingest.invokeExact(chunk);}
                public int backlog() {return VoxyCompat.dhIngestBacklog();}
            });
        } catch(ReflectiveOperationException | RuntimeException | LinkageError unavailable) {return null;}
    }

    void afterRender() {pumpClient(false);}
    void clientTick() {pumpClient(true);}
    private void pumpClient(boolean newTick) {
        var mc=Minecraft.getInstance();
        if(mc==null || mc.level==null || mc.player==null || mc.gameRenderer==null
                || !dev.vox.lss.config.LSSClientConfig.CONFIG.receiveServerLods()
                || !dev.vox.lss.config.LSSClientConfig.CONFIG.voxyBorderFill()
                || !terrainSessionActive()) {clear();return;}
        var camera=mc.gameRenderer.getMainCamera().position();
        if(newTick)tick(mc.level,camera.x,camera.z,mc.options.getEffectiveRenderDistance(),System.nanoTime());
        else pump(mc.level,camera.x,camera.z,mc.options.getEffectiveRenderDistance(),System.nanoTime());
    }

    static boolean terrainSessionActive() {
        return dev.vox.lss.networking.client.ClientNetGlue.getRequestManager()!=null
                || dev.vox.lss.networking.client.DhClientSession.terrainSessionActive();
    }

    /** Load events cover the moving frontier without repeatedly restarting a scan.
     * Whole vanilla chunks are also prepared inside the draw radius for later exit. */
    void loaded(ClientLevel world,LevelChunk chunk) {
        if(arrivalLevel!=world){arrivals.clear();arrivalLevel=world;}
        arrivals.put(chunk.getPos().toLong(),new WeakReference<>(chunk));trim(arrivals);
    }
    /** Main client thread only; lookups never load/generate a chunk. */
    void tick(ClientLevel current,double cameraX,double cameraZ,int distance,long now) {
        tickSubmissions=0;tickLookups=0;tickCandidates=0;
        pump(current,cameraX,cameraZ,distance,now);
    }
    /** Render-end retry shares the tick budget, after vanilla applied its light queue. */
    void pump(ClientLevel current,double cameraX,double cameraZ,int distance,long now) {
        if(current==null){clear();return;}
        if(disabled)return;
        try {
            Object engine=access.destination(current);
            if(engine==null){accepted.clear();retries.clear();skyWaiting.clear();VoxyLightStats.setBorderWaiting(0,0L);level=null;destination=null;return;}
            if(current!=level || engine!=destination) {
                accepted.clear();retries.clear();skyWaiting.clear();radius=-1;credit=SUBMISSIONS_PER_TICK;creditAt=now;
                if(arrivalLevel!=current)arrivals.clear();
                level=current;destination=engine;arrivalLevel=current;
            }
            int r=Math.max(2,Math.min(64,distance))+3;
            int cx=(int)Math.floor(cameraX/16),cz=(int)Math.floor(cameraZ/16);
            if(radius!=r || cx!=centerX || cz!=centerZ) {
                boolean first=radius<0;
                radius=r;centerX=cx;centerZ=cz;
                // Keep the cursor in world coordinates: a relative index skips rows on recenter.
                sweepX=first?cx-r:Math.max(cx-r,Math.min(cx+r,sweepX));
                sweepZ=first?cz-r:Math.max(cz-r,Math.min(cz+r,sweepZ));
                accepted.keySet().removeIf(p->!inSquare(p));retries.keySet().removeIf(p->!inSquare(p));
                arrivals.keySet().removeIf(p->!inSquare(p));skyWaiting.keySet().removeIf(p->!inSquare(p));
            }
            credit=Math.min(SUBMISSIONS_PER_TICK,credit+Math.max(0,now-creditAt)*SUBMISSIONS_PER_SECOND/1e9);creditAt=now;
            int border=Math.max(2,Math.min(64,distance));
            if(access.backlog()>BACKLOG_GATE){publishSkyWaiting(cameraX,cameraZ,border);return;}
            int submitted=tickSubmissions,lookups=tickLookups;
            int events=Math.min(32,arrivals.size());
            for(int i=0;i<events && credit>=1 && submitted<SUBMISSIONS_PER_TICK;i++) {
                var iterator=arrivals.entrySet().iterator();var entry=iterator.next();
                long position=entry.getKey();var reference=entry.getValue();iterator.remove();
                var chunk=reference.get();if(chunk==null)continue;
                int x=(int)position,z=(int)(position>>32);
                if(!atBorder(x,z,cameraX,cameraZ,Math.max(2,Math.min(64,distance))))continue;
                if(!ready(position,chunk,now)){if(!accepted.containsKey(position))arrivals.put(position,reference);continue;}
                if(access.backlog()>BACKLOG_GATE) {arrivals.put(position,reference);break;}
                submit(position,chunk,now);submitted++;tickSubmissions=submitted;
                if(!accepted.containsKey(position))arrivals.put(position,reference);
            }
            var chunks=current.getChunkSource();
            for(;tickCandidates<256 && lookups<LOOKUPS_PER_TICK;tickCandidates++) {
                int x=sweepX,z=sweepZ;
                if(++sweepZ>centerZ+radius) {
                    sweepZ=centerZ-radius;
                    if(++sweepX>centerX+radius)sweepX=centerX-radius;
                }
                if(!atBorder(x,z,cameraX,cameraZ,Math.max(2,Math.min(64,distance))))continue;
                long position=ChunkPos.asLong(x,z);
                lookups++;tickLookups=lookups;
                LevelChunk chunk=chunks.getChunk(x,z,ChunkStatus.FULL,false);
                if(chunk==null){accepted.remove(position);retries.remove(position);arrivals.remove(position);skyWaiting.remove(position);continue;}
                if(!ready(position,chunk,now))continue;
                if(credit<1 || submitted>=SUBMISSIONS_PER_TICK || access.backlog()>BACKLOG_GATE) {
                    arrivals.put(position,new WeakReference<>(chunk));trim(arrivals);continue;
                }
                submit(position,chunk,now);submitted++;tickSubmissions=submitted;
            }
            publishSkyWaiting(cameraX,cameraZ,border);
        } catch(Throwable failure) {
            if(failure instanceof Error error && !(error instanceof LinkageError) && !(error instanceof AssertionError))throw error;
            clear();disabled=failure instanceof LinkageError;
            LSSLogger.warn("Voxy vanilla border retry deferred; ordinary LOD ingestion remains active",failure);
        }
    }
    private boolean ready(long position,LevelChunk chunk,long now) {
        // Cheap map checks first: an accepted chunk never pays the per-section light scan,
        // and neither does one Voxy refused whose retry is not due (the scan clones a
        // layer per section on some light engines).
        var held=accepted.get(position);if(held!=null && held.get()==chunk)return false;
        var retry=retries.get(position);
        if(retry!=null && retry.chunk.get()==chunk && now<retry.due)return false;
        int state=lightState(level,chunk);
        if(state==SKY_UNREADABLE) {
            if(skyWaiting.putIfAbsent(position,now)==null){VoxyLightStats.addBorderHold();trim(skyWaiting);}
        } else skyWaiting.remove(position);
        return state==LIGHT_READY;
    }
    static final int LIGHT_READY=0,LIGHT_PENDING=1,SKY_UNREADABLE=2;
    /** Mirror Voxy's per-section readiness, independent of unrelated load/unload light work. */
    static boolean lightReady(ClientLevel level,LevelChunk chunk) {return lightState(level,chunk)==LIGHT_READY;}
    /**
     * {@link #LIGHT_READY} when Voxy can read this chunk's light now. A hand-over is
     * permanent (an accepted chunk is never submitted again), so a chunk that is not ready
     * is skipped and looked at again on the next pump, with no deadline: a late hand-over
     * stores darkness for good, while a gap closes by itself.
     *
     * <p>{@link #SKY_UNREADABLE} is the #429 case: every section reports "light and data",
     * and yet the sky layer Voxy is about to read is null. Vanilla always has that layer
     * for such a section, so nothing changes there. ScalableLux 0.1.0.1 reports "light and
     * data" as soon as a chunk packet's light is attached and returns null from its
     * sky-light reader until the client's light pass has built the chunk's emptiness map;
     * a pump between the two handed Voxy a null layer, which Voxy stores as sky light 0:
     * black strips at the edge of the render distance.
     */
    static int lightState(ClientLevel level,LevelChunk chunk) {
        var light=level.getLightEngine();
        boolean sky=level.dimensionType().hasSkyLight();
        for(int i=0;i<chunk.getSections().length;i++) {
            var section=chunk.getSections()[i];if(section==null || section.hasOnlyAir())continue;
            var p=net.minecraft.core.SectionPos.of(chunk.getPos(),chunk.getSectionYFromSectionIndex(i));
            if(!light.lightOnInColumn(net.minecraft.core.SectionPos.getZeroNode(p.asLong())) || light.getDebugSectionType(net.minecraft.world.level.LightLayer.BLOCK,p)
                    !=net.minecraft.world.level.lighting.LayerLightSectionStorage.SectionType.LIGHT_AND_DATA)return LIGHT_PENDING;
            if(sky && light.getDebugSectionType(net.minecraft.world.level.LightLayer.SKY,p)
                    !=net.minecraft.world.level.lighting.LayerLightSectionStorage.SectionType.LIGHT_AND_DATA)return LIGHT_PENDING;
        }
        // A second pass, after every cheap test passed: the read Voxy itself will make.
        // Not in dimensions without sky light (the listener there always returns null),
        // and not for all-air sections (no layer is legitimate above the terrain).
        if(sky) {
            var listener=light.getLayerListener(net.minecraft.world.level.LightLayer.SKY);
            for(int i=0;i<chunk.getSections().length;i++) {
                var section=chunk.getSections()[i];if(section==null || section.hasOnlyAir())continue;
                if(listener.getDataLayerData(net.minecraft.core.SectionPos.of(chunk.getPos(),chunk.getSectionYFromSectionIndex(i)))==null)return SKY_UNREADABLE;
            }
        }
        return LIGHT_READY;
    }
    /** A chunk waits only while the fill would hand it over: once the player has moved on
     *  and it is no longer at the border nothing probes it again, so it must not stay in
     *  the gauge (which exists to show a hold that does not end). */
    private void publishSkyWaiting(double cameraX,double cameraZ,int border) {
        if(!skyWaiting.isEmpty())skyWaiting.keySet().removeIf(p->!atBorder((int)(long)p,(int)(p>>32),cameraX,cameraZ,border));
        long oldest=Long.MAX_VALUE;
        for(long since:skyWaiting.values())oldest=Math.min(oldest,since);
        VoxyLightStats.setBorderWaiting(skyWaiting.size(),skyWaiting.isEmpty()?0L:oldest);
    }
    private void submit(long position,LevelChunk chunk,long now) throws Throwable {
        credit-=1;
        skyWaiting.remove(position);
        if(access.ingest(chunk) || entirelyEmpty(chunk)) {
            retries.remove(position);accepted.put(position,new WeakReference<>(chunk));trim(accepted);
        } else {retries.put(position,new Retry(new WeakReference<>(chunk),now+RETRY_INTERVAL));trim(retries);}
    }

    /** Closest point on a block-space chunk box, with one chunk of inward overlap. */
    static boolean atBorder(int x,int z,double cameraX,double cameraZ,int distance) {
        double dx=Math.max(0,Math.max(x*16.0-1-cameraX,cameraX-(x*16.0+17)));
        double dz=Math.max(0,Math.max(z*16.0-1-cameraZ,cameraZ-(z*16.0+17)));
        double inner=Math.max(0,distance-1)*16.0;
        return dx*dx+dz*dz>=inner*inner;
    }
    private boolean inSquare(long p) {
        return Math.abs((long)(int)p-centerX)<=radius && Math.abs((long)(int)(p>>32)-centerZ)<=radius;
    }
    private static void trim(Map<Long,?> map) {if(map.size()>MAX_TRACKED)map.remove(map.keySet().iterator().next());}
    private static boolean entirelyEmpty(LevelChunk chunk) {
        var sections=chunk.getSections();
        if(sections==null)return false;
        for(var section:sections)if(section!=null && !section.hasOnlyAir())return false;
        return true;
    }
    void clear() {
        level=null;arrivalLevel=null;destination=null;radius=-1;sweepX=0;sweepZ=0;creditAt=0;credit=SUBMISSIONS_PER_TICK;accepted.clear();retries.clear();arrivals.clear();
        // The waiting list goes with the world; the counts stay until the client disconnects.
        if(!skyWaiting.isEmpty()){skyWaiting.clear();VoxyLightStats.setBorderWaiting(0,0L);}
    }
    int skyWaitingForTest() {return skyWaiting.size();}
    int tracked() {return accepted.size()+retries.size()+arrivals.size();}
}
