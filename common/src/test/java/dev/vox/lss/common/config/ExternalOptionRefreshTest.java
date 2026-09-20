package dev.vox.lss.common.config;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ExternalOptionRefreshTest {
    static class Option {
        int live = 1, applied = 1, staged = 1, reloads, saves;
        Runnable open() {
            return ExternalOptionRefresh.capture(() -> live, () -> staged, () -> applied != staged,
                    () -> { applied = staged = live; reloads++; }, value -> staged = value);
        }
    }
    @Test void externalChangeRefreshesCleanBindingWithoutSaving() {
        var option = new Option(); var returned = option.open(); option.live = 2; returned.run();
        assertEquals(2, option.staged); assertEquals(2, option.applied); assertEquals(0, option.saves);
    }
    @Test void pendingDraftSurvivesRebaseOntoExternalValue() {
        var option = new Option(); option.staged = 3; var returned = option.open(); option.live = 2; returned.run();
        assertEquals(3, option.staged); assertEquals(2, option.applied);
    }
    @Test void draftAlreadyMatchingExternalValueBecomesClean() {
        var option = new Option(); option.staged = 2; var returned = option.open(); option.live = 2; returned.run();
        assertEquals(option.applied, option.staged);
    }
    @Test void unchangedLiveValueLeavesPendingEditsUntouched() {
        var option = new Option(); option.staged = 3; option.open().run();
        assertEquals(3, option.staged); assertEquals(0, option.reloads);
    }
    @Test void returnRunsOnceAndDoesNotTouchAnotherOption() {
        var option = new Option(); var unrelated = new Option(); unrelated.staged = 4;
        var returned = option.open(); option.live = 2; returned.run(); option.live = 3; returned.run();
        assertEquals(1, option.reloads); assertEquals(2, option.staged);
        assertEquals(4, unrelated.staged); assertEquals(0, unrelated.reloads);
    }
}
