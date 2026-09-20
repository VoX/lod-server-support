package dev.vox.lssfixture.xaeromap;
import java.nio.file.*;
import java.io.OutputStream;
import java.io.PrintStream;
/** Subprocess entrypoint: real writer/close protocol, deliberately unavailable logs. */
public final class NativeCloseProcessControl {
 public static void main(String[] args)throws Exception {
  System.out.close();System.err.close();
  Path root=Path.of(args[0]),output=root.resolve("evidence/xaero-map.jsonl");String mode=args[1];
  Thread writer=new Thread(()->{try{Files.writeString(output,"{\"event\":\"observer_closed\",\"run_id\":\"R\",\"time_ns\":"+System.nanoTime()+",\"pending\":0,\"overflow\":false}\n");Thread.sleep(150);}catch(Exception e){throw new RuntimeException(e);}});
  writer.start();if(!mode.equals("livewriter"))writer.join();
  try{NativeCloseHandshake.finish(output,"R",!mode.equals("unrequested"),writer.isAlive(),mode.equals("overflow"),mode.equals("pending")?1:0);}
  catch(Throwable failure){NativeCloseHandshake.failed(output,"R",failure);System.exit(2);}
 }
}
