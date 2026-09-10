package dev.vox.lss.networking.client;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LifecycleFeedbackTest {
    private static final class Owner {
        final LifecycleFeedback<String> callbacks = new LifecycleFeedback<>(2);
        final ArrayDeque<Runnable> queue = new ArrayDeque<>();
        final AtomicLong lifecycle = new AtomicLong(1);
        LifecycleFeedback.Ticket request(CompletableFuture<String> io, Consumer<String> callback) {
            var ticket = callbacks.reserve(lifecycle.get(), lifecycle.get(), callback);
            if (ticket == null) return null;
            io.whenComplete((result, error) -> queue.add(() -> {
                Consumer<String> sink = callbacks.take(ticket, lifecycle.get());
                if (sink != null) sink.accept(error == null ? result : "sanitized failure");
            }));
            return ticket;
        }
        void invalidate() { lifecycle.incrementAndGet(); callbacks.clear(); }
        void drain() { while (!queue.isEmpty()) queue.remove().run(); }
    }
    @Test void heldIoDisconnectDropsSinkImmediatelyAndNewSessionStillWorks() {
        var owner = new Owner(); var io = new CompletableFuture<String>(); var delivered = new ArrayList<String>();
        owner.request(io, delivered::add); assertEquals(1, owner.callbacks.pendingCount());
        owner.invalidate(); assertEquals(0, owner.callbacks.pendingCount());
        io.complete("old immutable export finished"); owner.drain(); assertTrue(delivered.isEmpty());
        var fresh = new CompletableFuture<String>(); owner.request(fresh, delivered::add);
        fresh.complete("new"); owner.drain(); assertEquals(java.util.List.of("new"), delivered);
    }
    @Test void queuedCompletionThenInvalidationDoesNotDeliver() {
        var owner = new Owner(); var io = new CompletableFuture<String>(); var delivered = new ArrayList<String>();
        owner.request(io, delivered::add); io.complete("old"); owner.invalidate(); owner.drain();
        assertTrue(delivered.isEmpty()); assertEquals(0, owner.callbacks.pendingCount());
    }
    @Test void sameDimensionIdentityChangeAtTakeRejectsWithoutEvent() {
        var owner = new Owner(); var io = new CompletableFuture<String>(); var delivered = new ArrayList<String>();
        owner.request(io, delivered::add); io.complete("old"); owner.lifecycle.incrementAndGet(); owner.drain();
        assertTrue(delivered.isEmpty()); assertEquals(0, owner.callbacks.pendingCount());
    }
    @Test void currentSuccessAndErrorDeliverOnceAndRelease() {
        var owner = new Owner(); var delivered = new ArrayList<String>();
        var success = new CompletableFuture<String>(); var error = new CompletableFuture<String>();
        owner.request(success, delivered::add); owner.request(error, delivered::add);
        success.complete("path"); error.completeExceptionally(new RuntimeException("private/world/address")); owner.drain();
        assertEquals(java.util.List.of("path", "sanitized failure"), delivered); assertEquals(0, owner.callbacks.pendingCount());
        owner.drain(); assertEquals(2, delivered.size());
    }
    @Test void boundedReservationRejectionAndSubmissionRelease() {
        var callbacks = new LifecycleFeedback<String>(2); Consumer<String> sink = ignored -> {};
        var a = callbacks.reserve(1, 1, sink); var b = callbacks.reserve(1, 1, sink);
        assertNull(callbacks.reserve(1, 1, sink)); assertEquals(2, callbacks.pendingCount());
        callbacks.release(a); assertEquals(1, callbacks.pendingCount());
        var c = callbacks.reserve(1, 1, sink); assertNotNull(c);
        callbacks.release(b); callbacks.release(c); assertEquals(0, callbacks.pendingCount());
        assertNull(callbacks.reserve(1, 2, sink)); assertEquals(0, callbacks.pendingCount());
    }
    @Test void oldTicketCannotConsumeNewRegistrationAndCallbacksMayReenter() {
        var callbacks = new LifecycleFeedback<String>(2); var old = callbacks.reserve(1, 1, ignored -> fail());
        callbacks.clear(); var fresh = callbacks.reserve(2, 2, ignored -> callbacks.clear());
        assertNull(callbacks.take(old, 2)); assertEquals(1, callbacks.pendingCount());
        var callback = callbacks.take(fresh, 2); assertNotNull(callback); callback.accept("okay");
        assertEquals(0, callbacks.pendingCount()); assertNull(callbacks.take(fresh, 2));
    }
    @Test void invalidationClearsReferencesWithoutWaitingForIoOrGc() throws Exception {
        var callbacks = new LifecycleFeedback<String>(2); var ticket = callbacks.reserve(1, 1, ignored -> fail());
        var thread = new Thread(callbacks::clear); thread.setDaemon(true); thread.start(); thread.join(5000);
        assertFalse(thread.isAlive(), "invalidation did not finish within bounded wait");
        assertEquals(0, callbacks.pendingCount()); assertNull(callbacks.take(ticket, 1));
    }
}
