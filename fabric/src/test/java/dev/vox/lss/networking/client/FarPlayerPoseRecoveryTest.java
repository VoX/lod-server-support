package dev.vox.lss.networking.client;

import com.mojang.blaze3d.vertex.PoseStack;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Real PoseStack recovery after a dispatcher/layer leaves an extra transformed frame. */
class FarPlayerPoseRecoveryTest {
    @Test void containedDrawFailureRestoresNextDrawAndOuterPass() throws Exception {
        var restore = FarPlayerRenderer.class.getDeclaredMethod("restorePose", PoseStack.class, PoseStack.Pose.class);
        var unwind = FarPlayerRenderer.class.getDeclaredMethod("unwindPose", PoseStack.class, PoseStack.Pose.class);
        restore.setAccessible(true);
        unwind.setAccessible(true);
        var poses = new PoseStack();
        var root = poses.last();
        poses.pushPose();
        poses.translate(3, 4, 5);
        var passMark = poses.last();
        try {
            poses.pushPose();
            poses.translate(100, 200, 300);
            throw new IllegalStateException("injected seated draw failure");
        } catch (IllegalStateException expected) {
            restore.invoke(null, poses, passMark);
        }
        assertSame(passMark, poses.last());
        assertEquals(3.0f, poses.last().pose().m30());
        assertEquals(4.0f, poses.last().pose().m31());
        assertEquals(5.0f, poses.last().pose().m32());
        unwind.invoke(null, poses, passMark);
        assertSame(root, poses.last());
        assertTrue(poses.clear(), "outer finally leaves only the original root");
    }
}
