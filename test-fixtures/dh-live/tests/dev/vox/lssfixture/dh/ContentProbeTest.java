package dev.vox.lssfixture.dh;

import dev.vox.lss.api.VoxelColumnData;
import dev.vox.lss.common.dh.*;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.objenesis.ObjenesisStd;
import java.lang.reflect.Field;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Runs separately under Fabric's real JUnit loader; observer failures cannot escape into Session.receive. */
class ContentProbeTest {
    @TempDir Path dir;
    Object previous;
    String capture;
    ClassLoader context;
    @BeforeAll static void bootstrap() {SharedConstants.tryDetectVersion();Bootstrap.bootStrap();}
    @BeforeEach void setup() throws Exception {
        context=Thread.currentThread().getContextClassLoader();Thread.currentThread().setContextClassLoader(getClass().getClassLoader());
        Field field=Minecraft.class.getDeclaredField("instance");field.setAccessible(true);previous=field.get(null);
        var mc=new ObjenesisStd().newInstance(Minecraft.class);var level=mock(ClientLevel.class);
        doReturn(ContentProbe.DIM).when(level).dimension();mc.level=level;field.set(null,mc);
        ContentProbe.callbackFailure.set(null);ContentProbe.captureBuffers.clear();ContentProbe.captureSequence.set(0);
        capture=System.getProperty("lss.rig.captureDir");System.setProperty("lss.rig.captureDir",dir.toString());
    }
    @AfterEach void cleanup() throws Exception {
        Field field=Minecraft.class.getDeclaredField("instance");field.setAccessible(true);field.set(null,previous);
        if(capture==null)System.clearProperty("lss.rig.captureDir");else System.setProperty("lss.rig.captureDir",capture);
        ContentProbe.callbackFailure.set(null);ContentProbe.captureBuffers.clear();ContentProbe.captureSequence.set(0);
        Thread.currentThread().setContextClassLoader(context);
    }
    byte[] split() throws Exception {
        byte[] dto=java.nio.ByteBuffer.allocate(8).putLong(ContentProbe.TILE.packed()).array();
        return new DhProtocolCodec(DhProfile.OFFICIAL_16,DhLimits.DEFAULT).encode(new DhMessage.Split(123,dto,true));
    }
    @Test void captureLimitReportsFixtureFailureWithoutEscapingTheDecodeCallback() throws Exception {
        ContentProbe.captureSequence.set(256);byte[] bytes=split();
        assertDoesNotThrow(()->ContentProbe.received(bytes));
        assertInstanceOf(IllegalStateException.class,ContentProbe.callbackFailure.get());
    }
    @Test void writeFailureReportsFixtureFailureWithoutEscapingTheDecodeCallback() throws Exception {
        System.setProperty("lss.rig.captureDir",dir.resolve("missing").toString());byte[] bytes=split();
        assertDoesNotThrow(()->ContentProbe.received(bytes));
        assertInstanceOf(java.nio.file.NoSuchFileException.class,ContentProbe.callbackFailure.get());
    }
    @Test void disconnectWithoutALevelIsNotAProductionFailure() throws Exception {
        Minecraft.getInstance().level=null;byte[] bytes=split();
        assertDoesNotThrow(()->ContentProbe.received(bytes));assertNull(ContentProbe.callbackFailure.get());
    }
    @Test void aMissingNativeSectionCannotSatisfyTheRemovalAirExpectation() {
        assertNull(ContentProbe.state(new VoxelColumnData(new VoxelColumnData.SectionData[0],123),0,350,0));
        assertEquals(Blocks.AIR.defaultBlockState(),ContentProbe.expected(2,350));
        assertNotEquals(ContentProbe.expected(2,350),ContentProbe.state(new VoxelColumnData(new VoxelColumnData.SectionData[0],123),0,350,0));
    }
}
