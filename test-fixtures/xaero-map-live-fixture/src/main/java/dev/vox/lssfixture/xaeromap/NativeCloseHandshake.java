package dev.vox.lssfixture.xaeromap;

import com.google.gson.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Only the native JVM publishes this receipt, after the evidence writer joins. */
final class NativeCloseHandshake {
 static final long WAIT_NANOS=10_000_000_000L;
 static final Gson JSON=new Gson();
 static final String RECEIPT="xaero-map-native-close.json", FAILURE="xaero-map-native-close-failed.json";
 static final List<String> CHECKS=List.of("fresh_body_received","bridge_write","boundary_continuity","shading_valid","save_race_safe");
 static JsonObject read(Path path,long max)throws Exception {
  if(Files.isSymbolicLink(path)||!Files.isRegularFile(path)||Files.size(path)>max)throw new IllegalStateException("invalid native close input");
  return JSON.fromJson(Files.readString(path),JsonObject.class);
 }
 static String sha(Path path)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));}
 static void atomic(Path path,JsonObject value)throws Exception {
  if(Files.exists(path,LinkOption.NOFOLLOW_LINKS))throw new IllegalStateException("native close output exists");
  Path tmp=path.resolveSibling(path.getFileName()+".tmp");
  Files.writeString(tmp,JSON.toJson(value)+"\n",StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
  Files.move(tmp,path,StandardCopyOption.ATOMIC_MOVE);
 }
 static void same(JsonObject value,String key,String expected){if(!value.has(key)||!expected.equals(value.get(key).getAsString()))throw new IllegalStateException("native close identity mismatch: "+key);}
 static void proofMatches(JsonObject proof,JsonObject request,Map<String,String> evidence)throws Exception {
  for(String key:List.of("run_id","run_hash","profile_hash","scenario_hash"))same(proof,key,request.get(key).getAsString());
  if(!proof.get("ready").getAsBoolean()||!proof.get("handshake").getAsBoolean()||proof.get("test_count").getAsInt()!=5||proof.getAsJsonArray("failures").size()!=0)throw new IllegalStateException("native close proof failed");
  for(String key:CHECKS)if(!proof.getAsJsonObject("assertions").get(key).getAsBoolean())throw new IllegalStateException("native close assertion failed");
  JsonObject report=proof.getAsJsonObject("map_report");
  if(!report.get("closed").getAsBoolean()||!report.get("passed").getAsBoolean()||report.getAsJsonArray("errors").size()!=0)throw new IllegalStateException("native close report failed");
  same(report,"run_id",request.get("run_id").getAsString());same(report,"run_hash",request.get("run_hash").getAsString());
  for(var row:evidence.entrySet())same(proof.getAsJsonObject("evidence"),row.getKey(),row.getValue());
 }
 static void finish(Path output,String run,boolean stopRequested,boolean writerAlive,boolean overflow,int pending)throws Exception {
  Path evidence=output.toAbsolutePath().normalize().getParent(),requestPath=evidence.resolve("xaero-map-stop-client");
  JsonObject request=read(requestPath,2048);same(request,"run_id",run);same(request,"phase","all_raw_checks_passed");
  JsonObject manifest=read(evidence.getParent().resolve("manifest.json"),8*1024*1024);
  for(String key:List.of("run_id","run_hash","profile_hash","scenario_hash"))same(request,key,manifest.get(key).getAsString());
  if(!stopRequested||writerAlive||overflow||pending!=0)throw new IllegalStateException("native writer did not close cleanly");
  long now=System.nanoTime(),requested=request.get("requested_ns").getAsLong();
  if(requested<=0||requested>=now)throw new IllegalStateException("native close request ordering");
  JsonObject receipt=new JsonObject();
  for(String key:List.of("run_id","run_hash","profile_hash","scenario_hash"))receipt.add(key,request.get(key));
  receipt.addProperty("requested_ns",requested);receipt.addProperty("time_ns",now);receipt.addProperty("proof_deadline_ns",now+WAIT_NANOS);
  receipt.addProperty("writer_joined",true);receipt.addProperty("overflow",false);receipt.addProperty("pending",0);
  receipt.addProperty("stream_sha256",sha(output));receipt.addProperty("request_sha256",sha(requestPath));
  Path close=evidence.resolve(RECEIPT);atomic(close,receipt);
  Map<String,String> bound=Map.of(RECEIPT,sha(close),"xaero-map.jsonl",sha(output),"xaero-map-stop-client",sha(requestPath));
  Path proof=evidence.getParent().resolve("proof.json");
  while(System.nanoTime()<now+WAIT_NANOS){
   if(Files.exists(proof)){proofMatches(read(proof,1024*1024),request,bound);return;}
   Thread.sleep(10);
  }
  throw new IllegalStateException("native close proof deadline expired");
 }
 static void failed(Path output,String run,Throwable failure){
  Path base=output.toAbsolutePath().normalize().getParent();
  JsonObject row=new JsonObject();row.addProperty("run_id",run);row.addProperty("time_ns",System.nanoTime());row.addProperty("error",failure.getClass().getSimpleName()+": "+failure.getMessage());
  try{atomic(base.resolve(FAILURE),row);return;}catch(Exception markerFailure){
   // Existing marker (even malformed) already fails strict collection.
   if(Files.exists(base.resolve(FAILURE),LinkOption.NOFOLLOW_LINKS))return;
  }
  try{
   // If marker creation fails, invalidate the accepted receipt atomically.
   // Its original digest and required clean fields can no longer validate.
   Path tmp=Files.createTempFile(base,"xaero-map-close-failure-",".tmp");
   Files.writeString(tmp,JSON.toJson(row)+"\n",StandardCharsets.UTF_8);
   Files.move(tmp,base.resolve(RECEIPT),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);return;
  }catch(Exception replacementFailure){
   try{Files.deleteIfExists(base.resolve(RECEIPT));return;}
   catch(Exception removalFailure){Runtime.getRuntime().halt(86);}
  }
 }
}
