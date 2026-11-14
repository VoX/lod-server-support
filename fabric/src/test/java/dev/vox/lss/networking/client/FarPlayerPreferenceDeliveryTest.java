package dev.vox.lss.networking.client;

import dev.vox.lss.common.farplayers.FarPlayerWire;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import static org.junit.jupiter.api.Assertions.*;

class FarPlayerPreferenceDeliveryTest {
    private static FarPlayerWire.Prefs hidden() { return new FarPlayerWire.Prefs(false,0,0,false,0); }
    private static FarPlayerWire.Prefs shared() { return new FarPlayerWire.Prefs(true,0,0,true,0); }
    private static final FarPlayerPreferenceDelivery.Outcome SENT=FarPlayerPreferenceDelivery.Outcome.SENT;
    private static final FarPlayerPreferenceDelivery.Outcome NO_CHANNEL=FarPlayerPreferenceDelivery.Outcome.NO_CHANNEL;

    // ---- 0.23.0 plan §2: an opt-out never waits for a session ----

    @Test void anOptOutGoesAtJoinWithoutASession() {
        var delivery=new FarPlayerPreferenceDelivery(); Object connection=new Object();
        var sent=new ArrayList<FarPlayerWire.Prefs>();
        delivery.joined(connection,true);
        assertEquals(SENT,delivery.accept(connection,hidden(),prefs->{sent.add(prefs);return SENT;}));
        assertEquals(java.util.List.of(hidden()),sent);
        assertEquals(FarPlayerPreferenceDelivery.Report.OPT_OUT_SENT,delivery.report(hidden()));
    }
    @Test void sharingOnNeverGoesWithoutASession() {
        // On an opt_in server a delivered "on" would show a player who is hidden today.
        var delivery=new FarPlayerPreferenceDelivery(); Object connection=new Object();
        delivery.joined(connection,true);
        assertEquals(NO_CHANNEL,delivery.accept(connection,shared(),prefs->{fail("an opt-in left without a session");return null;}));
        for(int i=0;i<60;i++)delivery.tick(connection,prefs->{fail("an opt-in left without a session");return null;});
        assertEquals(FarPlayerPreferenceDelivery.Report.NOT_SENT,delivery.report(shared()));
    }
    @Test void anOptOutWaitsForTheChannelAndRetries() {
        var delivery=new FarPlayerPreferenceDelivery(); Object connection=new Object();
        delivery.joined(connection,true);
        assertEquals(NO_CHANNEL,delivery.accept(connection,hidden(),prefs->NO_CHANNEL));
        assertTrue(delivery.pending());
        assertEquals(FarPlayerPreferenceDelivery.Report.NOT_SENT,delivery.report(hidden()),"nothing has reached a server yet");
        var sent=new ArrayList<FarPlayerWire.Prefs>();
        for(int i=0;i<20;i++)delivery.tick(connection,prefs->{sent.add(prefs);return SENT;});
        assertEquals(java.util.List.of(hidden()),sent);
        assertEquals(FarPlayerPreferenceDelivery.Report.OPT_OUT_SENT,delivery.report(hidden()));
    }
    @Test void switchingSharingOffDuringPlaySendsItAndSwitchingBackOnSaysTheServerKeepsTheOptOut() {
        var delivery=new FarPlayerPreferenceDelivery(); Object connection=new Object();
        var sent=new ArrayList<FarPlayerWire.Prefs>();
        delivery.joined(connection,true);
        delivery.accept(connection,shared(),prefs->{fail("no session");return null;});
        assertEquals(SENT,delivery.accept(connection,hidden(),prefs->{sent.add(prefs);return SENT;}));
        assertEquals(1,sent.size());
        // Back on: nothing may be sent, and the player is told the earlier "off" still holds.
        assertEquals(NO_CHANNEL,delivery.accept(connection,shared(),prefs->{fail("no session");return null;}));
        assertEquals(FarPlayerPreferenceDelivery.Report.OPT_OUT_KEPT,delivery.report(shared()));
        // Off again: the server already has it.
        assertEquals(SENT,delivery.accept(connection,hidden(),prefs->{fail("already delivered");return null;}));
    }
    @Test void aHandshakeDoesNotMakeTheClientForgetWhatTheServerHolds() {
        // 0.23.0 review: the discovery ladder re-sends the handshake every few seconds to a
        // server that never answers. The server still holds the opt-out it got at join, so
        // "on" saved after such a handshake must not read "no server has received it yet".
        var delivery=new FarPlayerPreferenceDelivery(); Object connection=new Object();
        var sent=new ArrayList<FarPlayerWire.Prefs>();
        delivery.joined(connection,true);
        assertEquals(SENT,delivery.accept(connection,hidden(),prefs->{sent.add(prefs);return SENT;}));
        delivery.handshake();
        assertEquals(NO_CHANNEL,delivery.accept(connection,shared(),prefs->{fail("no session");return null;}));
        assertEquals(FarPlayerPreferenceDelivery.Report.OPT_OUT_KEPT,delivery.report(shared()));
        // The same after a session that the server then took away: it keeps what it was sent.
        delivery.sessionReady(connection);
        assertEquals(SENT,delivery.accept(connection,hidden(),prefs->{sent.add(prefs);return SENT;}));
        delivery.handshake();
        assertEquals(NO_CHANNEL,delivery.accept(connection,shared(),prefs->{fail("no session");return null;}));
        assertEquals(FarPlayerPreferenceDelivery.Report.OPT_OUT_KEPT,delivery.report(shared()));
        // A delivered "share" ends it; a later lost session then has nothing to report.
        delivery.sessionReady(connection);
        assertEquals(SENT,delivery.accept(connection,shared(),prefs->{sent.add(prefs);return SENT;}));
        assertEquals(FarPlayerPreferenceDelivery.Report.SENT,delivery.report(shared()));
        delivery.handshake();
        delivery.accept(connection,shared(),prefs->{fail("no session");return null;});
        assertEquals(FarPlayerPreferenceDelivery.Report.NOT_SENT,delivery.report(shared()));
        assertEquals(java.util.List.of(hidden(),hidden(),shared()),sent);
        // A new join starts clean: another server holds nothing.
        delivery.joined(connection,true);
        delivery.accept(connection,shared(),prefs->{fail("no session");return null;});
        assertEquals(FarPlayerPreferenceDelivery.Report.NOT_SENT,delivery.report(shared()));
    }
    @Test void aWaitingOptOutIsDroppedWhenSharingIsSwitchedBackOn() {
        var delivery=new FarPlayerPreferenceDelivery(); Object connection=new Object();
        delivery.joined(connection,true);
        delivery.accept(connection,hidden(),prefs->NO_CHANNEL);
        delivery.accept(connection,shared(),prefs->{fail("no session");return null;});
        assertFalse(delivery.pending());
        for(int i=0;i<60;i++)delivery.tick(connection,prefs->{fail("a stale opt-out left after sharing was switched on");return null;});
        assertEquals(FarPlayerPreferenceDelivery.Report.NOT_SENT,delivery.report(shared()));
    }
    @Test void aSecondJoinOnTheSameConnectionSendsAgain() {
        // A proxy moves the player to another server on the same connection object.
        var delivery=new FarPlayerPreferenceDelivery(); Object connection=new Object();
        var sent=new ArrayList<FarPlayerWire.Prefs>();
        delivery.joined(connection,true);
        delivery.accept(connection,hidden(),prefs->{sent.add(prefs);return SENT;});
        delivery.joined(connection,true);
        assertEquals(FarPlayerPreferenceDelivery.Report.NOT_SENT,delivery.report(hidden()),"the new server knows nothing");
        delivery.accept(connection,hidden(),prefs->{sent.add(prefs);return SENT;});
        assertEquals(2,sent.size());
    }
    @Test void theSessionAnswerStillGetsItsOwnFrame() {
        // The server answers the frame after a session answer with the first roster.
        var delivery=new FarPlayerPreferenceDelivery(); Object connection=new Object();
        var sent=new ArrayList<FarPlayerWire.Prefs>();
        delivery.joined(connection,true);
        delivery.accept(connection,hidden(),prefs->{sent.add(prefs);return SENT;});
        delivery.sessionReady(connection);
        delivery.accept(connection,hidden(),prefs->{sent.add(prefs);return SENT;});
        assertEquals(2,sent.size());
        assertEquals(FarPlayerPreferenceDelivery.Report.SENT,delivery.report(hidden()));
        // And with a session, "on" goes too.
        delivery.accept(connection,shared(),prefs->{sent.add(prefs);return SENT;});
        assertEquals(3,sent.size());
        assertEquals(FarPlayerPreferenceDelivery.Report.SENT,delivery.report(shared()));
    }
    @Test void thePlayersOwnWorldSendsNothingWithoutASession() {
        var delivery=new FarPlayerPreferenceDelivery(); Object connection=new Object();
        delivery.joined(connection,false);
        assertEquals(NO_CHANNEL,delivery.accept(connection,hidden(),prefs->{fail("nobody to hide from");return null;}));
    }
    @Test void aFailedSendReportsARetry() {
        var delivery=new FarPlayerPreferenceDelivery(); Object connection=new Object();
        delivery.joined(connection,true);
        delivery.accept(connection,hidden(),prefs->FarPlayerPreferenceDelivery.Outcome.FAILED);
        assertEquals(FarPlayerPreferenceDelivery.Report.RETRYING,delivery.report(hidden()));
    }
    @Test void failedCombinedReceiveOffShareOffRetriesWithoutAnyAcquisition() {
        var delivery=new FarPlayerPreferenceDelivery(); Object connection=new Object();
        delivery.sessionReady(connection);
        assertEquals(FarPlayerPreferenceDelivery.Outcome.FAILED,
                delivery.accept(connection,hidden(),prefs->{throw new IllegalStateException("transport unavailable");}));
        assertTrue(delivery.pending());
        var sent=new ArrayList<FarPlayerWire.Prefs>();
        for(int i=0;i<19;i++) delivery.tick(connection,prefs->{sent.add(prefs);return FarPlayerPreferenceDelivery.Outcome.SENT;});
        assertTrue(sent.isEmpty(),"retry is bounded");
        delivery.tick(connection,prefs->{sent.add(prefs);return FarPlayerPreferenceDelivery.Outcome.SENT;});
        assertEquals(java.util.List.of(hidden()),sent);
        assertFalse(delivery.pending());
        assertEquals(FarPlayerPreferenceDelivery.Outcome.SENT,delivery.outcome());
    }
    @Test void replacementConnectionCannotReceiveOldPendingOptOut() {
        var delivery=new FarPlayerPreferenceDelivery(); Object a=new Object(),b=new Object();
        delivery.sessionReady(a);
        delivery.accept(a,hidden(),prefs->FarPlayerPreferenceDelivery.Outcome.FAILED);
        for(int i=0;i<40;i++)delivery.tick(b,prefs->{fail("old preference leaked to another server");return null;});
        assertFalse(delivery.pending());
        assertEquals(FarPlayerPreferenceDelivery.Outcome.NO_CHANNEL,delivery.outcome());
    }
    @Test void unknownChannelNeverClaimsSuccessfulDeliveryAndAnUnchangedReloadCannotErasePending() {
        var delivery=new FarPlayerPreferenceDelivery();Object connection=new Object();
        assertEquals(FarPlayerPreferenceDelivery.Outcome.NO_CHANNEL,
                delivery.accept(connection,hidden(),prefs->{fail("no valid session");return null;}));
        delivery.sessionReady(connection);
        delivery.accept(connection,hidden(),prefs->FarPlayerPreferenceDelivery.Outcome.NO_CHANNEL);
        assertTrue(delivery.pending());
        // An unchanged settings transaction performs no accept call; owner ticks retain pending.
        for(int i=0;i<20;i++)delivery.tick(connection,prefs->FarPlayerPreferenceDelivery.Outcome.SENT);
        assertEquals(FarPlayerPreferenceDelivery.Outcome.SENT,delivery.outcome());
    }
}
