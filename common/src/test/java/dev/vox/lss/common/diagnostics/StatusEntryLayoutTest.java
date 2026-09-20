package dev.vox.lss.common.diagnostics;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class StatusEntryLayoutTest {
    @Test void modernAvoidsDonationSearchOptionsAndAllThreeActionSlots() {
        var result = StatusEntryLayout.find(480,270,true,(x,y) -> x < 335 || y < 25 || y >= 190);
        assertNotNull(result);
        assertTrue(result.x() >= 335 && result.y() >= 30 && result.y()+result.height() < 190);
    }
    @Test void smallModernViewportUsesNarrowButtonWithoutOverlappingOptionPane() {
        var result = StatusEntryLayout.find(320,240,true,(x,y) -> x < 225 || y < 25 || y >= 160);
        assertNotNull(result);
        assertEquals(85,result.width());
        assertTrue(result.x() >= 225 && result.x()+result.width() <= 315);
    }
    @Test void legacyReservesItsFooterAndTabs() {
        var result = StatusEntryLayout.find(480,270,false,(x,y) -> y < 45 || y >= 240 || (x < 300 && y < 220));
        assertNotNull(result);
        assertTrue(result.y() >= 45 && result.y()+result.height() < 240);
    }
    @Test void noFreeSpaceNeverOverlaysAnExistingControl() {
        assertNull(StatusEntryLayout.find(320,240,true,(x,y) -> true));
    }
}
