package dev.vox.lssfixture.smoke;
import dev.vox.lss.api.*;
import dev.vox.lss.networking.client.ClientNetGlue;
import dev.vox.lss.networking.payloads.VoxelColumnS2CPayload;
import dev.vox.lss.networking.payloads.ZstdWireSupport;
import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.nio.file.*;
import com.google.gson.Gson;
/** One-session native observer; second join uses a separate fresh owned process/root. */
public final class SmokeClient implements ClientModInitializer,VoxelColumnConsumer {
 private record Capture(long id,long arrival,Object connection,ClientLevel level,VoxelColumnS2CPayload payload,byte[] raw) {}
 private static final IdentityCaptures<Capture> CAPTURES=new IdentityCaptures<>(8192);
 private static final ThreadLocal<Capture> DECODING=new ThreadLocal<>();
 private static final AtomicLong IDS=new AtomicLong();
 private static final ArrayBlockingQueue<Map<String,Object>> EVENTS=new ArrayBlockingQueue<>(256);
 private static volatile boolean running=true,stop,overflow,handshake,body;
 private static volatile Object owner;private static volatile ClientLevel level;
 private static String run,connection,phase;private static Path root;
 private static void emit(Map<String,Object> values){var row=new LinkedHashMap<String,Object>(values);row.put("run_id",run);row.put("connection_id",connection);row.put("time_ns",System.nanoTime());if(!EVENTS.offer(row))overflow=true;}
 @Override public void onInitializeClient(){
  run=System.getProperty("lss.rig.runId","");connection=System.getProperty("lss.smoke.connection","");phase=System.getProperty("lss.smoke.phase","");root=Path.of(System.getProperty("lss.smoke.evidence",""));
  if(!run.matches("[A-Za-z0-9_-]+")||!connection.startsWith(run+"-")||!Set.of("first","second").contains(phase)||!root.isAbsolute()||Files.isSymbolicLink(root))throw new IllegalStateException("owned smoke context required");
  Thread writer=new Thread(()->{try(var out=Files.newBufferedWriter(root.resolve("client-"+phase+".jsonl"))){Gson json=new Gson();while(running||!EVENTS.isEmpty()){var row=EVENTS.poll(100,TimeUnit.MILLISECONDS);if(row!=null){out.write(json.toJson(row));out.newLine();out.flush();}stop=Files.exists(root.resolve("stop-"+phase));}}catch(Exception e){overflow=true;}},"LSS-SmokeEvidence");writer.setDaemon(true);writer.start();
  Runtime.getRuntime().addShutdownHook(new Thread(()->{emit(new LinkedHashMap<>(Map.of("event","client_closed","overflow",overflow)));running=false;try{writer.join(2000);}catch(InterruptedException e){Thread.currentThread().interrupt();}}));
  LSSApi.registerColumnConsumer(this);
 }
 public static void tick(){var mc=Minecraft.getInstance();if(stop){mc.stop();return;}if(mc.level==null||mc.getConnection()==null)return;
  if(owner==null){owner=mc.getConnection();level=mc.level;}
  if(owner!=mc.getConnection()||level!=mc.level)throw new IllegalStateException("unexpected session replacement in one-phase client");
  if(!handshake&&ClientNetGlue.hasReceivedSessionConfig()&&ClientNetGlue.isServerEnabled()){
   if(ClientNetGlue.getSessionVersion()!=20)throw new IllegalStateException("native session is not v20");handshake=true;emit(new LinkedHashMap<>(Map.of("event","client_handshake","protocol",20,"phase",phase)));}
 }
 public static void wire(VoxelColumnS2CPayload payload){
  if(!handshake||owner==null||payload.chunkX()!=8||payload.chunkZ()!=8)return;
  byte[] raw=payload.shippedSections().clone();if(payload.codec()==1){long size=ZstdWireSupport.declaredContentSize(raw);if(size<=0||size>16*1024*1024)throw new IllegalStateException("invalid body size");raw=ZstdWireSupport.decompress(raw,(int)size);}else if(payload.codec()!=0)throw new IllegalStateException("unexpected wire codec");
  var capture=new Capture(IDS.incrementAndGet(),System.nanoTime(),owner,level,payload,raw);CAPTURES.put(payload,capture);
  emit(new LinkedHashMap<>(Map.of("event","wire_capture","wire_capture_id",capture.id(),"arrival_ns",capture.arrival(),"source",payload.source(),"chunk_x",8,"chunk_z",8,"column_timestamp",payload.columnTimestamp(),"body_hex",HexFormat.of().formatHex(raw))));
 }
 public static void decoding(VoxelColumnS2CPayload payload){DECODING.set(CAPTURES.take(payload));}
 public static void decodingFinished(){DECODING.remove();}
 @Override public void onVoxelColumnReceived(ClientLevel delivery,ResourceKey<Level> dimension,int x,int z,VoxelColumnData data){
  Capture wire=DECODING.get();DECODING.remove();var receipt=LSSApi.captureIngestFailureHandle();
  if(x!=8||z!=8||dimension!=Level.OVERWORLD||body)return;
  if(wire==null||receipt==null||!receipt.isActive()||wire.connection()!=owner||wire.level()!=delivery||delivery!=level){if(receipt!=null&&receipt.isActive())receipt.report();return;}
  int[][] points={{128,64,128},{129,64,128},{128,65,128}};var decoded=new LinkedHashMap<String,String>();
  for(int i=0;i<points.length;i++){var p=points[i];boolean matched=false;for(var section:data.sections())if(section.sectionY()==Math.floorDiv(p[1],16)){
   var state=section.section().getBlockState(p[0]&15,p[1]&15,p[2]&15);matched=state.is(i==0?Blocks.DIAMOND_BLOCK:Blocks.AIR);}
   if(!matched)throw new IllegalStateException("independent smoke block mismatch");decoded.put(p[0]+","+p[1]+","+p[2],i==0?"minecraft:diamond_block":"minecraft:air");}
  if(!receipt.isActive())return;body=true;
  var row=new LinkedHashMap<String,Object>();row.put("event","body_"+phase);row.put("phase",phase);row.put("dimension","minecraft:overworld");row.put("chunk_x",x);row.put("chunk_z",z);row.put("decoded_blocks",decoded);row.put("wire_association","exact");row.put("wire_capture_id",wire.id());row.put("body_id",wire.id());row.put("body_bytes",wire.raw().length);row.put("body_hex",HexFormat.of().formatHex(wire.raw()));
  try{row.put("body_sha256",HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(wire.raw())));}catch(Exception e){throw new IllegalStateException(e);}
  row.put("column_timestamp",data.columnTimestamp());row.put("source",wire.payload().source());row.put("received_ns",wire.arrival());row.put("lease_active",true);emit(row);
 }
}
