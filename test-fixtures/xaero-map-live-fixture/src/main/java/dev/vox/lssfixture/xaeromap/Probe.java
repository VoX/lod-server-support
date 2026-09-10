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
 private static volatile boolean activeSave;
 private static int mapFrames; // render-thread only
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
    System.err.println("[XAERO-MAP-FIXTURE] CLOSED overflow="+OVERFLOW.get()+" pending="+EVENTS.size());
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
 public static void renderedScreen(Object screen){
  if(!ENABLED)return;
  boolean map=screen!=null&&screen.getClass().getName().equals("xaero.map.gui.GuiMap");
  if(!map){mapFrames=0;return;}
  if(mapFrames<2)emit("map_screen_rendered",Map.of("screen",screen.getClass().getName(),"frame",++mapFrames));
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
 }catch(Throwable t){failure(t);}}
 public static void texture(Object chunk,Object processor){if(!ENABLED)return;try{
  int tx=number(chunk,"getX"),tz=number(chunk,"getZ");if((tx!=7&&tx!=8)||tz!=4)return;
  Object region=call(chunk,"getInRegion");REGIONS.add(region);
  boolean nativeWriter=StackWalker.getInstance().walk(frames->frames.anyMatch(f->f.getClassName().equals("xaero.map.MapWriter")&&f.getMethodName().equals("writeChunk")));
  Object texture=call(chunk,"getLeafTexture");ByteBuffer source=((ByteBuffer)call(texture,"getDirectColorBuffer")).duplicate();source.clear();
  if(source.remaining()>1024*1024)throw new IllegalStateException("oversized native color buffer");byte[] bytes=new byte[source.remaining()];source.get(bytes);
  var row=new LinkedHashMap<String,Object>();row.put("save_interval_ms",call(processor,"getSaveTime"));row.put("tile_chunk_x",tx);row.put("tile_chunk_z",tz);row.put("native_writer",nativeWriter);row.put("native_save_active",activeSave);row.put("buffer_bytes",bytes.length);row.put("buffer_sha256",hash(bytes));row.put("buffer_base64",Base64.getEncoder().encodeToString(bytes));
  row.put("region_load_state",call(region,"getLoadState"));row.put("region_resting",call(region,"isResting"));row.put("region_paused",call(region,"isWritingPaused"));row.put("last_visited",call(region,"getLastVisited"));
  int x=tx==7?31:32;Object tile=call(chunk,"getTile",x&3,0);var pixels=new ArrayList<List<Integer>>();
  if(tile==null)return;
  for(int bx=0;bx<16;bx++)for(int bz=0;bz<16;bz++){Object block=call(tile,"getBlock",bx,bz);pixels.add(List.of(number(block,"getHeight"),number(block,"getTopHeight"),number(block,"getVerticalSlope"),number(block,"getDiagonalSlope"),number(block,"getParametres")));}
  row.put("chunk_x",x);row.put("chunk_z",16);row.put("pixels",pixels);emit("native_texture",row);
 }catch(Throwable t){failure(t);}}
 public static void saveBegin(Object region){if(!ENABLED||!REGIONS.contains(region)||!Files.isRegularFile(Path.of(System.getProperty("lss.xaeromap.arm")))||!PAUSE_USED.compareAndSet(false,true))return;try{
  Object monitor=region.getClass().getField("writerThreadPauseSync").get(region);
  if(!(boolean)call(region,"isWritingPaused")||Thread.holdsLock(monitor))throw new IllegalStateException("native save pause premise absent or pause monitor retained");
  SAVING.set(true);activeSave=true;emit("save_pause_begin",Map.of("native_paused",true,"holds_pause_monitor",false,"region_x",number(region,"getRegionX"),"region_z",number(region,"getRegionZ")));
  long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
  while(System.nanoTime()<deadline)java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
  emit("save_pause_release",Map.of("native_paused",call(region,"isWritingPaused")));
 }catch(Throwable t){failure(t);}}
 public static void saveEnd(Object region,boolean result){if(!ENABLED||!SAVING.get())return;emit("native_save_return",Map.of("success",result));activeSave=false;SAVING.remove();}
}
