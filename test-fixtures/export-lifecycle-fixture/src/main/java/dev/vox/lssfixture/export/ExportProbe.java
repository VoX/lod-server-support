package dev.vox.lssfixture.export;

import dev.vox.lss.common.Brand;
import dev.vox.lss.networking.client.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.*;
import net.minecraft.network.chat.Component;
import java.lang.reflect.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.LoggerFactory;

/** Run-owned observer. Only immutable primitive observations cross the worker boundary. */
public final class ExportProbe {
    private static final AtomicBoolean arm = new AtomicBoolean();
    private static volatile CountDownLatch release;
    private static CountDownLatch entered;
    private static volatile boolean held, returned, failed;
    private static int state, round, completions, screenSuccess, chatSuccess, busy;
    private static int oldCompletions, oldScreen, oldChat;
    private static long started, phase;
    private static boolean initialRequested, connecting;
    private static java.lang.ref.WeakReference<Object> oldWorld, oldConnection;
    private static Object oldDimension;
    private static void log(String event){LoggerFactory.getLogger("LSS-ExportFixture").info("[EXPORT-FIXTURE] {} run={}",event,System.getProperty("lss.rig.runId"));}
    private static void require(boolean okay,String message){if(!okay)throw new IllegalStateException(message);}
    private static Object field(Object obj,Class<?> type,String name)throws Exception{var f=type.getDeclaredField(name);f.setAccessible(true);return f.get(obj);}
    private static Object callbacks()throws Exception{return field(null,ClientStatus.class,"EXPORT_FEEDBACK");}
    private static int pending()throws Exception{Object registry=callbacks();synchronized(registry){return ((java.util.Map<?,?>)field(registry,registry.getClass(),"pending")).size();}}
    private static void assertScreenCapture(Object screen)throws Exception{
        Object registry=callbacks();synchronized(registry){
            var entries=(java.util.Map<?,?>)field(registry,registry.getClass(),"pending");require(entries.size()==1,"one actual screen sink");
            Object entry=entries.values().iterator().next();Object callback=field(entry,entry.getClass(),"callback");boolean found=false;
            for(var capture:callback.getClass().getDeclaredFields()){if(Modifier.isStatic(capture.getModifiers()))continue;capture.setAccessible(true);if(capture.get(callback)==screen)found=true;}
            require(found,"actual pending callback must capture actual status screen");
        }
    }
    private static ThreadPoolExecutor executor()throws Exception{return (ThreadPoolExecutor)field(null,Class.forName("dev.vox.lss.common.diagnostics.DiagnosticExport"),"IO");}
    private static void advance(int next){state=next;phase=System.nanoTime();}
    private static void fail(Throwable t){failed=true;var latch=release;if(latch!=null)latch.countDown();log("FAIL_"+t.getClass().getSimpleName());LoggerFactory.getLogger("LSS-ExportFixture").error("fixture failure",t);}
    public static void beforeWrite(){
        if(!arm.compareAndSet(true,false))return;
        try{require(Thread.currentThread().getName().equals("LSS-DiagnosticExport"),"actual export worker required");log(round==0?"SCREEN_REAL_IO_HELD":"COMMAND_REAL_IO_HELD");held=true;require(release.await(90,TimeUnit.SECONDS),"held IO timeout");}
        catch(Throwable t){fail(t);throw new IllegalStateException(t);}
    }
    public static void afterWrite(java.nio.file.Path path,byte[] captured){
        try{require(captured.length<=64*1024 && java.nio.file.Files.size(path)<=64*1024,"bounded actual export");require(java.util.Arrays.equals(captured,java.nio.file.Files.readAllBytes(path)),"actual immutable export bytes");if(held)returned=true;}
        catch(Throwable t){fail(t);throw new IllegalStateException(t);}
    }
    public static void completed(){require(Minecraft.getInstance().isSameThread(),"completion owner");completions++;}
    public static void screenFeedback(String text){if(text.startsWith("Diagnostics exported:"))screenSuccess++;if(text.startsWith("Diagnostics exporter busy"))busy++;}
    public static void chatFeedback(String text){if(text.startsWith("Diagnostics exported:"))chatSuccess++;}
    private static void screenExport(){var mc=Minecraft.getInstance();var screen=new ClientStatusScreen(null);mc.setScreen(screen);((Button)screen.children().getFirst()).onPress();}
    private static void commandExport(){var mc=Minecraft.getInstance();var screen=new ChatScreen("");mc.setScreen(screen);screen.handleChatInput("/"+Brand.clientCommand()+" diagnostics export",false);}
    private static void connect(){
        var mc=Minecraft.getInstance();String endpoint=System.getProperty("lss.rig.exportEndpoint","");
        require(endpoint.matches("(?:\\[::1\\]|127\\.0\\.0\\.1):[0-9]{1,5}"),"explicit loopback endpoint required");
        var address=net.minecraft.client.multiplayer.resolver.ServerAddress.parseString(endpoint);
        var data=new net.minecraft.client.multiplayer.ServerData("LSS disposable export fixture",endpoint,net.minecraft.client.multiplayer.ServerData.Type.OTHER);
        ConnectScreen.startConnecting(new TitleScreen(),mc,address,data,false,null);connecting=true;
    }
    private static boolean ready(){var mc=Minecraft.getInstance();return mc.level!=null&&mc.player!=null&&mc.getConnection()!=null&&ClientNetGlue.hasReceivedSessionConfig()&&ClientNetGlue.getSessionVersion()==20&&ClientStatus.latest()!=null;}
    private static void startHeld()throws Exception{
        var mc=Minecraft.getInstance();oldWorld=new java.lang.ref.WeakReference<>(mc.level);oldConnection=new java.lang.ref.WeakReference<>(mc.getConnection());oldDimension=mc.level.dimension();
        oldCompletions=completions;oldScreen=screenSuccess;oldChat=chatSuccess;held=false;returned=false;release=new CountDownLatch(1);arm.set(true);
        if(round==0){screenExport();assertScreenCapture(mc.screen);}else commandExport();advance(1);
    }
    public static void tick(){
        if(System.getProperty("lss.rig.runId","").isBlank()||failed||state==99)return;
        try{
            var mc=Minecraft.getInstance();long now=System.nanoTime();if(started==0){started=now;phase=now;}
            require(now-started<TimeUnit.SECONDS.toNanos(360),"total deadline");require(now-phase<TimeUnit.SECONDS.toNanos(100),"phase deadline "+state);
            if(!initialRequested&&mc.level==null&&mc.screen instanceof TitleScreen){initialRequested=true;connect();return;}
            switch(state){
                case 0 -> {if(ready()){log("READY_V20");connecting=false;startHeld();}}
                case 1 -> {if(held){require(pending()==1,"one real callback reservation");log(round==0?"SCREEN_SINK_RESERVED":"COMMAND_SINK_RESERVED");mc.getConnection().getConnection().disconnect(Component.literal("Owned export lifecycle test"));advance(2);}}
                case 2 -> {if(mc.level==null){require(pending()==0,"disconnect must immediately clear callback references");require(!returned,"IO must remain held through retirement");log(round==0?"SCREEN_DISCONNECT_DROPPED_SINK":"COMMAND_DISCONNECT_DROPPED_SINK");connecting=false;advance(3);}}
                case 3 -> {if(!connecting){connect();return;}if(ready()){
                    require(mc.level!=oldWorld.get()&&mc.getConnection()!=oldConnection.get(),"new native world and connection");require(mc.level.dimension().equals(oldDimension),"same dimension");require(pending()==0,"replacement must not restore sink");
                    oldWorld=null;oldConnection=null;oldDimension=null;log(round==0?"SCREEN_NATIVE_SAME_DIMENSION_REPLACEMENT":"COMMAND_NATIVE_SAME_DIMENSION_REPLACEMENT");release.countDown();advance(4);
                }}
                case 4 -> {if(returned&&completions==oldCompletions+1){require(screenSuccess==oldScreen&&chatSuccess==oldChat,"retired export delivered feedback");require(pending()==0,"old completion retained callback");log(round==0?"SCREEN_OLD_COMPLETION_SUPPRESSED":"COMMAND_OLD_COMPLETION_SUPPRESSED");held=false;oldCompletions=completions;if(round==0)screenExport();else commandExport();advance(5);}}
                case 5 -> {if(completions==oldCompletions+1){require(pending()==0,"fresh completion retained callback");require(chatSuccess==oldChat+1,"fresh chat export exactly once");require(screenSuccess==oldScreen+(round==0?1:0),"fresh screen route exactly once");log(round==0?"SCREEN_FRESH_SUCCESS":"COMMAND_FRESH_SUCCESS");if(round++==0){startHeld();}else{advance(6);}}}
                case 6 -> {var io=executor();if(io.getActiveCount()==0&&io.getQueue().isEmpty()){
                    release=new CountDownLatch(1);entered=new CountDownLatch(1);io.execute(()->{entered.countDown();try{if(!release.await(30,TimeUnit.SECONDS))throw new IllegalStateException("rejection blocker timeout");}catch(InterruptedException e){Thread.currentThread().interrupt();}});
                    advance(61);
                }}
                case 61 -> {if(entered.getCount()==0){
                    var io=executor();io.execute(()->{});require(io.getQueue().size()==1,"real executor queue full");oldScreen=busy;screenExport();require(busy==oldScreen+1,"actual rejected submission busy feedback");require(pending()==0,"actual rejection released registry reservation");log("REAL_SUBMISSION_REJECTED_AND_RELEASED");release.countDown();advance(7);
                }}
                case 7 -> {if(executor().getActiveCount()==0&&executor().getQueue().isEmpty()){oldChat=chatSuccess;oldCompletions=completions;commandExport();advance(8);}}
                case 8 -> {if(completions==oldCompletions+1){require(chatSuccess==oldChat+1&&pending()==0,"post-rejection command recovery");log("POST_REJECTION_COMMAND_SUCCESS");log("PASS");advance(99);}}
                default -> throw new IllegalStateException("unknown state");
            }
        }catch(Throwable t){fail(t);}
    }
    private ExportProbe(){}
}
