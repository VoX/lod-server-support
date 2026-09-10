package dev.vox.lssfixture.elytra;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.GameType;
import dev.vox.lss.networking.client.ClientNetGlue;
import com.google.gson.Gson;
import java.util.*;
/** Observes actual native session and completed submissions; never mutates pose. */
public final class Probe {
 private static final Gson JSON=new Gson();
 private static final Map<UUID,Long> LAST=new HashMap<>();
 private static final String RUN=System.getProperty("lss.rig.runId","");
 private static final String ROLE=Boolean.getBoolean("lss.rig.elytraTarget")?"target":"observer";
 private static boolean connectRequested;
 private static Object connection,level;private static long targetAt,sequence;private static boolean announced;
 private static synchronized boolean session(){
  var mc=Minecraft.getInstance();if(mc.level==null||mc.player==null||mc.getConnection()==null)return false;
  if(!ClientNetGlue.hasReceivedSessionConfig()||!ClientNetGlue.isServerEnabled()||ClientNetGlue.getSessionVersion()!=20)return false;
  if(!RUN.matches("[A-Za-z0-9_-]+"))throw new IllegalStateException("owned Elytra run identity missing");
  if(connection==null){connection=mc.getConnection();level=mc.level;}
  if(connection!=mc.getConnection()||level!=mc.level)throw new IllegalStateException("Elytra session replaced; attempt invalid");
  if(!announced){announced=true;emit("LSS_ELYTRA_SESSION",new LinkedHashMap<>(Map.of("player",mc.player.getName().getString(),"uuid",mc.player.getUUID().toString(),"protocol",20)));}
  return true;
 }
 private static synchronized void emit(String tag,Map<String,Object> data){data.put("run_id",RUN);data.put("role",ROLE);data.put("connection_id",RUN+"-"+ROLE);data.put("nano_time",System.nanoTime());data.put("sequence",++sequence);System.out.println(tag+" "+JSON.toJson(data));}
 public static void tick(){
  var client=Minecraft.getInstance();
  if(ROLE.equals("observer")&&!connectRequested&&client.level==null&&client.screen instanceof net.minecraft.client.gui.screens.TitleScreen){
   String endpoint=System.getProperty("lss.rig.initialEndpoint","");
   if(!endpoint.matches("(?:\\[::1\\]|127\\.0\\.0\\.1):[0-9]{1,5}"))throw new IllegalStateException("explicit owned loopback endpoint required");
   connectRequested=true;var address=net.minecraft.client.multiplayer.resolver.ServerAddress.parseString(endpoint);
   var data=new net.minecraft.client.multiplayer.ServerData("LSS owned Elytra fixture",endpoint,net.minecraft.client.multiplayer.ServerData.Type.OTHER);
   net.minecraft.client.gui.screens.ConnectScreen.startConnecting(new net.minecraft.client.gui.screens.TitleScreen(),client,address,data,false,null);
  }
  if(!session())return;long now=System.nanoTime();if(now-targetAt<100_000_000L)return;targetAt=now;
  var mc=Minecraft.getInstance();var p=mc.player;var row=new LinkedHashMap<String,Object>();row.put("uuid",p.getUUID().toString());row.put("on_ground",p.onGround());row.put("fall_flying",p.isFallFlying());row.put("crouching",p.isCrouching());row.put("elytra",p.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA));row.put("survival",mc.gameMode.getPlayerMode()==GameType.SURVIVAL);row.put("x",p.getX());row.put("y",p.getY());row.put("z",p.getZ());row.put("eye_y",p.getEyeY());row.put("mouse_grabbed",mc.mouseHandler.isMouseGrabbed());row.put("yaw",p.getYRot());row.put("pitch",p.getXRot());emit(ROLE.equals("target")?"LSS_ELYTRA_NATIVE":"LSS_ELYTRA_CAMERA",row);
 }
 public static void submitted(Object object,double distance,Vec3 position){
  if(!session())return;
  if(!(object instanceof LivingEntity e)||!object.getClass().getName().equals("dev.vox.lss.networking.client.FarPlayerRenderer$Proxy"))throw new AssertionError("unexpected proxy type");
  long now=System.nanoTime();if(now-LAST.getOrDefault(e.getUUID(),0L)<100_000_000L)return;LAST.put(e.getUUID(),now);
  var mc=Minecraft.getInstance();var row=new LinkedHashMap<String,Object>();row.put("uuid",e.getUUID().toString());row.put("entity_id",e.getId());row.put("native_absent",mc.level.getPlayerByUUID(e.getUUID())==null);row.put("elytra",e.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA));row.put("crouching",e.isCrouching());row.put("fall_flying",e.isFallFlying());row.put("pose",e.getPose().name());row.put("distance",distance);row.put("x",position.x);row.put("y",position.y);row.put("z",position.z);emit("LSS_ELYTRA_SUBMIT",row);
 }
}
