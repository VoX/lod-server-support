package dev.vox.lssfixture.xaeromap;

import com.google.gson.Gson;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Isolated native observer: all map ownership and pixel writes remain native/product-owned. */
public final class Probe {
 private static final String RUN=System.getProperty("lss.rig.runId","");
 private static final boolean ENABLED=Boolean.getBoolean("lss.xaeromap.enabled")&&!RUN.isBlank();
 private static final ArrayBlockingQueue<String> EVENTS=new ArrayBlockingQueue<>(4096);
 private static final AtomicBoolean OVERFLOW=new AtomicBoolean(),PAUSE_USED=new AtomicBoolean();
 private static final AtomicLong IDS=new AtomicLong();
 private static final Set<Object> REGIONS=ConcurrentHashMap.newKeySet();
 private static final ConcurrentHashMap<String,Method> METHODS=new ConcurrentHashMap<>();
 private static final ThreadLocal<Boolean> SAVING=ThreadLocal.withInitial(()->false);
 private static final AtomicBoolean CLIENT_STOP_REQUESTED=new AtomicBoolean();
 private static volatile boolean activeSave;
 private static volatile int saveRegionX,saveRegionZ;
 private static final AtomicBoolean SAVE_DEFERRED=new AtomicBoolean();
 private static final int PAUSE_MAX_MILLIS=Integer.getInteger("lss.xaeromap.pauseMaxMillis",15000);
 private static int mapFrames; // render-thread only
 private static final boolean COVERAGE_DIAGNOSTICS=Boolean.getBoolean("lss.xaeromap.coverageDiagnostics");
 private static int coverageFrames,coverageSamples; // client owner only; never reset on map close
 private static final Gson JSON=new Gson();
 static {
  if(ENABLED){
   Path output=Path.of(System.getProperty("lss.xaeromap.evidence"));
   Thread writer=new Thread(()->{
    try(var stream=Files.newBufferedWriter(output,StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW)){
     while(true){String row=EVENTS.take();if(row.equals("__CLOSE__"))break;stream.write(row);stream.newLine();stream.flush();}
     stream.write(JSON.toJson(Map.of("event","observer_closed","run_id",RUN,"time_ns",System.nanoTime(),"overflow",OVERFLOW.get(),"pending",EVENTS.size())));stream.newLine();stream.flush();
    }catch(Throwable failure){OVERFLOW.set(true);System.err.println("[XAERO-MAP-FIXTURE] EVIDENCE_FAILED "+failure.getClass().getName());}
   },"LSS-XaeroMap-Evidence");writer.setDaemon(true);writer.start();
   Runtime.getRuntime().addShutdownHook(new Thread(()->{
    try{if(!EVENTS.offer("__CLOSE__",3,TimeUnit.SECONDS))OVERFLOW.set(true);writer.join(3000);if(writer.isAlive())OVERFLOW.set(true);}catch(InterruptedException error){OVERFLOW.set(true);Thread.currentThread().interrupt();}
    try{NativeCloseHandshake.finish(output,RUN,CLIENT_STOP_REQUESTED.get(),writer.isAlive(),OVERFLOW.get(),EVENTS.size());}
    catch(Throwable failure){NativeCloseHandshake.failed(output,RUN,failure);}
   },"LSS-XaeroMap-Close"));
  }
 }
 private static Object call(Object target,String name,Object...args)throws Exception{
  String key=target.getClass().getName()+"#"+name+"#"+args.length;
  Method method=METHODS.get(key);
  if(method==null){
   for(Class<?> type=target.getClass();type!=null&&method==null;type=type.getSuperclass())
    for(Method found:type.getDeclaredMethods())if(found.getName().equals(name)&&found.getParameterCount()==args.length){method=found;break;}
   if(method==null||!method.trySetAccessible())throw new NoSuchMethodException(key);
   METHODS.put(key,method);
  }
  return method.invoke(target,args);
 }
 private static int number(Object target,String name)throws Exception{return ((Number)call(target,name)).intValue();}
 private static boolean target(int x,int z){return (x==31||x==32)&&z==16;}
 private static String hash(byte[] bytes)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
 private static void emit(String event,Map<String,Object> facts){
  var row=new LinkedHashMap<String,Object>(facts);row.put("event",event);row.put("run_id",RUN);row.put("time_ns",System.nanoTime());row.put("thread",Thread.currentThread().getName());row.put("overflow",OVERFLOW.get());
  if(!EVENTS.offer(JSON.toJson(row)))OVERFLOW.set(true);
 }
 private static void failure(Throwable error){emit("observer_failure",Map.of("error",error.getClass().getName(),"message",String.valueOf(error.getMessage())));}
 private static final ThreadLocal<Scan> SCAN=new ThreadLocal<>();
 private static final Map<Object,Long> NATIVE_SCANS=Collections.synchronizedMap(new IdentityHashMap<>());
 private static final class Scan {
  final Object writer; Object chunk; int x,z;
  Scan(Object writer){this.writer=writer;}
 }
 private static Object field(Object target,String name)throws Exception{
  for(Class<?> type=target.getClass();type!=null;type=type.getSuperclass()){
   try{var f=type.getDeclaredField(name);if(!f.trySetAccessible())throw new IllegalAccessException(name);return f.get(target);}
   catch(NoSuchFieldException missing){}
  }
  throw new NoSuchFieldException(name);
 }
 public static void nativeBegin(Object writer){if(ENABLED)SCAN.set(new Scan(writer));}
 public static void nativeTile(Object chunk,int x,int z){
  Scan scan=SCAN.get();if(scan==null||scan.chunk!=null)return;
  scan.chunk=chunk;scan.x=x;scan.z=z;
 }
 public static void nativeScanned(){if(!ENABLED)return;try{
  Scan scan=SCAN.get();if(scan==null||scan.chunk==null)throw new IllegalStateException("native scan has no actual tile group");
  int x=number(scan.chunk,"getX")*4+scan.x,z=number(scan.chunk,"getZ")*4+scan.z;
  if(!target(x,z))return;
  long now=System.nanoTime();NATIVE_SCANS.put(scan.chunk,now);
  emit("native_scan_completed",Map.of("chunk_x",x,"chunk_z",z,"scan_ns",now,"pixels",pixels(scan.chunk,x,z)));
 }catch(Throwable t){failure(t);}}
 public static void nativeGroupChecked(Object chunk,boolean changed){if(!ENABLED||changed||!NATIVE_SCANS.containsKey(chunk))return;try{
  Scan scan=SCAN.get();if(scan==null)return;
  textureObserved(chunk,field(scan.writer,"mapProcessor"),"unchanged_native_group");
 }catch(Throwable t){failure(t);}}
 public static void nativeEnd(){SCAN.remove();}
 private static boolean requestNativeStop(){
  if(CLIENT_STOP_REQUESTED.get())return true;
  try{
   Path expected=Path.of(System.getProperty("lss.xaeromap.evidence")).toAbsolutePath().normalize().resolveSibling("xaero-map-stop-client");
   Path request=Path.of(System.getProperty("lss.xaeromap.stop")).toAbsolutePath().normalize();
   if(!request.equals(expected))throw new IllegalStateException("native stop path outside exact run evidence");
   if(!Files.exists(request))return false;
   if(Files.isSymbolicLink(request)||!Files.isRegularFile(request)||Files.size(request)>1024)throw new IllegalStateException("invalid native stop request file");
   var value=JSON.fromJson(Files.readString(request),com.google.gson.JsonObject.class);
   if(!RUN.equals(value.get("run_id").getAsString())||!"all_raw_checks_passed".equals(value.get("phase").getAsString())||value.get("requested_ns").getAsLong()<=0)throw new IllegalStateException("native stop request identity/phase mismatch");
   if(!net.minecraft.client.Minecraft.getInstance().isSameThread())throw new IllegalStateException("native stop request off client owner");
   if(CLIENT_STOP_REQUESTED.compareAndSet(false,true)){
    emit("native_client_stop_requested",Map.of("client_thread",true,"request_run_id",RUN,"request_path","xaero-map-stop-client","requested_ns",value.get("requested_ns").getAsLong()));
    net.minecraft.client.Minecraft.getInstance().stop();
   }
   return true;
  }catch(Throwable t){failure(t);return false;}
 }
 private static int viewportFrames;
 public static void renderedScreen(Object screen){
  if(!ENABLED)return;
  if(requestNativeStop())return;
  boolean map=screen!=null&&screen.getClass().getName().equals("xaero.map.gui.GuiMap");
  if(!map){mapFrames=0;viewportFrames=0;return;}
  if(mapFrames<2)emit("map_screen_rendered",Map.of("screen",screen.getClass().getName(),"frame",++mapFrames));
  try{
   if(++viewportFrames>4096)throw new IllegalStateException("native viewport frame bound");
   var window=net.minecraft.client.Minecraft.getInstance().getWindow();
   var gui=(net.minecraft.client.gui.screens.Screen)screen;
   var zoom=(net.minecraft.client.gui.components.AbstractWidget)field(screen,"zoomOutButton");
   var row=new LinkedHashMap<String,Object>();
   row.put("viewport_frame",viewportFrames);row.put("camera_x",field(screen,"cameraX"));row.put("camera_z",field(screen,"cameraZ"));row.put("scale",field(screen,"scale"));
   row.put("width",window.getWidth());row.put("height",window.getHeight());
   row.put("window_x",window.getX());row.put("window_y",window.getY());
   row.put("screen_width",window.getScreenWidth());row.put("screen_height",window.getScreenHeight());
   row.put("gui_width",gui.width);row.put("gui_height",gui.height);
   row.put("zoom_out",List.of(zoom.getX(),zoom.getY(),zoom.getWidth(),zoom.getHeight()));
   row.put("zoom_active",zoom.active);row.put("zoom_visible",zoom.visible);
   var mouse=net.minecraft.client.Minecraft.getInstance().mouseHandler;
   row.put("mouse_x",mouse.xpos());row.put("mouse_y",mouse.ypos());
   row.put("zoom_mouse_over",zoom.isMouseOver(mouse.xpos()*gui.width/window.getScreenWidth(),mouse.ypos()*gui.height/window.getScreenHeight()));
   emit("map_viewport",row);
   coverageObserved(row);
  }catch(Throwable t){failure(t);}
 }
 /** Optional observation only: first completed map frame, then every15 map frames,
  * at most32 attempts per client process. FULL lookup with create=false never loads chunks.
  * No footprint/erosion classification is baked into the observed coordinate set. */
 private static void coverageObserved(Map<String,Object> viewport)throws Exception{
  if(!COVERAGE_DIAGNOSTICS||coverageSamples>=32)return;
  int frame=++coverageFrames;
  if((frame-1)%15!=0)return;
  int sample=++coverageSamples; // Failed/missing-world attempts also consume the bound.
  var client=net.minecraft.client.Minecraft.getInstance();
  if(!client.isSameThread())throw new IllegalStateException("chunk coverage observation off client owner");
  var row=new LinkedHashMap<String,Object>(viewport);
  row.put("coverage_sample",sample);row.put("coverage_frame",frame);row.put("client_thread",true);
  row.put("sample_started_ns",System.nanoTime());row.put("radius_chunks",6);
  row.put("lookup_status","FULL");row.put("create_missing",false);
  row.put("effective_render_distance",client.options.getEffectiveRenderDistance());
  row.put("configured_render_distance",client.options.renderDistance().get());
  var level=client.level;var player=client.player;
  row.put("world_present",level!=null);row.put("player_present",player!=null);
  if(level!=null&&player!=null){
   int centerX=player.chunkPosition().x,centerZ=player.chunkPosition().z;
   row.put("dimension",level.dimension().location().toString());
   row.put("player_x",player.getX());row.put("player_y",player.getY());row.put("player_z",player.getZ());
   row.put("player_chunk_x",centerX);row.put("player_chunk_z",centerZ);
   row.put("player_yaw",player.getYRot());row.put("player_pitch",player.getXRot());
   var camera=client.gameRenderer.getMainCamera();var cameraPosition=camera.getPosition();
   row.put("render_camera_position",List.of(cameraPosition.x,cameraPosition.y,cameraPosition.z));
   row.put("render_camera_yaw",camera.getYRot());row.put("render_camera_pitch",camera.getXRot());
   var loaded=new ArrayList<List<Integer>>();var empty=new ArrayList<List<Integer>>();var missing=new ArrayList<List<Integer>>();
   for(int dz=-6;dz<=6;dz++)for(int dx=-6;dx<=6;dx++){
    int x=centerX+dx,z=centerZ+dz;
    var chunk=level.getChunk(x,z,net.minecraft.world.level.chunk.status.ChunkStatus.FULL,false);
    var coordinate=List.of(x,z);
    if(chunk==null)missing.add(coordinate);
    else if(chunk instanceof net.minecraft.world.level.chunk.EmptyLevelChunk)empty.add(coordinate);
    else {
     if(chunk.getPos().x!=x||chunk.getPos().z!=z)throw new IllegalStateException("coverage chunk coordinate mismatch");
     loaded.add(coordinate);
    }
   }
   row.put("grid_cells",169);row.put("loaded_count",loaded.size());row.put("loaded_chunks",loaded);
   row.put("empty_chunks",empty);row.put("missing_chunks",missing);
  }
  row.put("sample_finished_ns",System.nanoTime());emit("map_chunk_coverage",row);
 }
 public static void wire(Object payload){if(!ENABLED)return;try{
  int x=number(payload,"chunkX"),z=number(payload,"chunkZ");if(!target(x,z))return;
  byte[] bytes=(byte[])call(payload,"shippedSections");
  emit("wire_body",Map.of("body_id",IDS.incrementAndGet(),"chunk_x",x,"chunk_z",z,"column_timestamp",call(payload,"columnTimestamp"),"body_bytes",bytes.length,"body_sha256",hash(bytes),"source",call(payload,"source")));
 }catch(Throwable t){failure(t);}}
 public static void bridge(Object tile,Object outcome){if(!ENABLED)return;try{
  int x=number(tile,"chunkX"),z=number(tile,"chunkZ");if(!target(x,z))return;
  var row=new LinkedHashMap<String,Object>();row.put("chunk_x",x);row.put("chunk_z",z);row.put("outcome",outcome.toString());row.put("native_save_active",activeSave);
  var client=net.minecraft.client.Minecraft.getInstance();
  row.put("native_chunk_loaded",client.level!=null&&client.level.getChunkSource().hasChunk(x,z));
  row.put("client_chunk_x",client.player==null?Integer.MIN_VALUE:client.player.chunkPosition().x);
  row.put("client_chunk_z",client.player==null?Integer.MIN_VALUE:client.player.chunkPosition().z);
  row.put("floor_y",call(tile,"floorY"));row.put("top_y",call(tile,"topY"));row.put("light",call(tile,"light"));
  Object[] states=(Object[])call(tile,"floorState");row.put("floor_state",Arrays.stream(states).map(String::valueOf).toList());emit("bridge_result",row);
  if(activeSave&&outcome.toString().equals("DEFERRED")&&x/32==saveRegionX&&z/32==saveRegionZ&&editedTarget(tile,x,states))SAVE_DEFERRED.set(true);
 }catch(Throwable t){failure(t);}}
 private static boolean editedTarget(Object tile,int chunkX,Object[] states)throws Exception{
  short[] heights=(short[])call(tile,"floorY");
  if(heights.length!=256||states.length!=256)return false;
  for(int bx=0;bx<16;bx++)for(int bz=0;bz<16;bz++){
   int i=bx*16+bz;
   if(heights[i]!=64+Math.floorMod(chunkX*16+bx-432,8))return false;
   if(!(states[i] instanceof net.minecraft.world.level.block.state.BlockState state)||!state.is(net.minecraft.world.level.block.Blocks.DIAMOND_BLOCK))return false;
  }
  return true;
 }
 public static void texture(Object chunk,Object processor){textureObserved(chunk,processor,"buffer_rebuild");}
 private static void textureObserved(Object chunk,Object processor,String observation){if(!ENABLED)return;try{
  int tx=number(chunk,"getX"),tz=number(chunk,"getZ");if((tx!=7&&tx!=8)||tz!=4)return;
  int x=tx==7?31:32;
  if(!NativeTargetEligibility.capture(call(chunk,"getTile",x&3,0)!=null,NATIVE_SCANS.containsKey(chunk)))return;
  Object region=call(chunk,"getInRegion");REGIONS.add(region);
  boolean nativeWriter=StackWalker.getInstance().walk(frames->frames.anyMatch(f->f.getClassName().equals("xaero.map.MapWriter")&&f.getMethodName().equals("writeChunk")));
  Object texture=call(chunk,"getLeafTexture");ByteBuffer source=((ByteBuffer)call(texture,"getDirectColorBuffer")).duplicate();source.clear();
  if(source.remaining()>1024*1024)throw new IllegalStateException("oversized native color buffer");byte[] bytes=new byte[source.remaining()];source.get(bytes);
  var row=new LinkedHashMap<String,Object>();row.put("save_interval_ms",call(processor,"getSaveTime"));row.put("tile_chunk_x",tx);row.put("tile_chunk_z",tz);row.put("native_writer",nativeWriter);row.put("observation",observation);row.put("native_scan_ns",NATIVE_SCANS.getOrDefault(chunk,0L));row.put("native_save_active",activeSave);row.put("buffer_bytes",bytes.length);row.put("buffer_sha256",hash(bytes));row.put("buffer_base64",Base64.getEncoder().encodeToString(bytes));
  row.put("region_load_state",call(region,"getLoadState"));row.put("region_resting",call(region,"isResting"));row.put("region_paused",call(region,"isWritingPaused"));row.put("last_visited",call(region,"getLastVisited"));
  var pixels=pixels(chunk,x,16);
  row.put("chunk_x",x);row.put("chunk_z",16);row.put("pixels",pixels);emit("native_texture",row);
  if(nativeWriter)NATIVE_SCANS.remove(chunk);

 }catch(Throwable t){failure(t);}}
 private static List<List<Integer>> pixels(Object chunk,int x,int z)throws Exception{
  Object tile=call(chunk,"getTile",x&3,z&3);var pixels=new ArrayList<List<Integer>>();
  if(tile==null)throw new IllegalStateException("observed native target tile absent");
  for(int bx=0;bx<16;bx++)for(int bz=0;bz<16;bz++){
   Object block=call(tile,"getBlock",bx,bz);
   pixels.add(List.of(number(block,"getHeight"),number(block,"getTopHeight"),number(block,"getVerticalSlope"),number(block,"getDiagonalSlope"),number(block,"getParametres")));
  }
  return pixels;
 }
 public static void saveBegin(Object region){if(!ENABLED||!REGIONS.contains(region)||!Files.isRegularFile(Path.of(System.getProperty("lss.xaeromap.arm")))||!PAUSE_USED.compareAndSet(false,true))return;try{
  Object monitor=region.getClass().getField("writerThreadPauseSync").get(region);
  if(!(boolean)call(region,"isWritingPaused")||Thread.holdsLock(monitor))throw new IllegalStateException("native save pause premise absent or pause monitor retained");
  if(PAUSE_MAX_MILLIS!=15000)throw new IllegalStateException("native save overlap requires declared 15000ms hard bound");
  SAVING.set(true);saveRegionX=number(region,"getRegionX");saveRegionZ=number(region,"getRegionZ");SAVE_DEFERRED.set(false);activeSave=true;
  emit("save_pause_begin",Map.of("native_paused",true,"holds_pause_monitor",false,"region_x",saveRegionX,"region_z",saveRegionZ,"pause_max_ms",PAUSE_MAX_MILLIS));
  long deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(PAUSE_MAX_MILLIS);
  while(!SAVE_DEFERRED.get()&&System.nanoTime()<deadline)java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
  emit("save_pause_release",Map.of("native_paused",call(region,"isWritingPaused"),"release_reason",SAVE_DEFERRED.get()?"target_deferred":"deadline"));
 }catch(Throwable t){failure(t);}}
 public static void saveEnd(Object region,boolean result){if(!ENABLED||!SAVING.get())return;emit("native_save_return",Map.of("success",result));activeSave=false;SAVING.remove();}
}
