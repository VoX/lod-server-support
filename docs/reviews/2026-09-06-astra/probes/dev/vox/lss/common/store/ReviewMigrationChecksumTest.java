package dev.vox.lss.common.store;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
/** Parent review probe using the existing schema-3 fixture and opaque translation seam. */
class ReviewMigrationChecksumTest {
 @TempDir Path tmp;
 private SqliteLodStore openCorruptLegacyRow() throws Exception {
  var fixture = new SqliteLodStoreMigrationTest(); fixture.tmp = tmp;
  Class<?> rowType = Class.forName(SqliteLodStoreMigrationTest.class.getName()+"$FixtureRow");
  var ctor = rowType.getDeclaredConstructor(long.class,byte[].class,boolean.class);ctor.setAccessible(true);
  Object row=ctor.newInstance(0L,new byte[]{1,2,3,4},false);
  var build=SqliteLodStoreMigrationTest.class.getDeclaredMethod("buildSchema3Store",String.class,List.class);build.setAccessible(true);
  build.invoke(fixture,"fp-test",List.of(row));
  var ds=new org.sqlite.SQLiteDataSource();ds.setUrl("jdbc:sqlite:"+tmp.resolve("store/store.db"));
  try(var c=ds.getConnection();var s=c.createStatement()) { s.executeUpdate("UPDATE lods_1 SET chash=chash+1"); }
  var open=SqliteLodStoreMigrationTest.class.getDeclaredMethod("open");open.setAccessible(true);
  return (SqliteLodStore)open.invoke(fixture);
 }
 @Test void controlNormalReadRejectsChecksumMismatch() throws Exception {
  var store=openCorruptLegacyRow();try { assertNull(store.get("minecraft:overworld",0L)); }finally{store.shutdown();}
 }
 @Test void migrationMustNotCertifyAChecksumMismatchedLegacyRow() throws Exception {
  var store=openCorruptLegacyRow();try {
   store.setLegacyMigrationTranslator(raw -> raw.clone());
   long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
   while(!store.migrationStatusToken().isEmpty() && System.nanoTime()<end)Thread.sleep(10);
   assertEquals("",store.migrationStatusToken(),"premise: migration completed");
   assertNull(store.get("minecraft:overworld",0L),"migration must reject the same checksum-mismatched row as normal read, not recompute a valid checksum over it");
  }finally{store.shutdown();}
 }
}
