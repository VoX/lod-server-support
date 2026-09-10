package dev.vox.lss.networking.client;

import java.util.HashMap;
import java.util.function.Consumer;

/** Bounded owner-side callbacks; async work receives only immutable tickets.
 * Callers may use this monitor to serialize lifecycle checks with registration/take.
 * Consumers are returned, never invoked under this object's monitor. */
final class LifecycleFeedback<T> {
    public record Ticket(long id, long lifecycle) {}
    private record Pending<T>(long lifecycle, Consumer<T> callback) {}
    private final int capacity;
    private final HashMap<Long, Pending<T>> pending = new HashMap<>();
    private long sequence;
    public LifecycleFeedback(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("capacity must be positive");
        this.capacity = capacity;
    }
    public synchronized Ticket reserve(long captured, long current, Consumer<T> callback) {
        if (captured != current || pending.size() >= capacity) return null;
        java.util.Objects.requireNonNull(callback);
        long id = Math.incrementExact(sequence);
        sequence = id;
        pending.put(id, new Pending<>(captured, callback));
        return new Ticket(id, captured);
    }
    public synchronized Consumer<T> take(Ticket ticket, long current) {
        Pending<T> entry = pending.remove(ticket.id());
        return entry != null && entry.lifecycle() == ticket.lifecycle()
                && ticket.lifecycle() == current ? entry.callback() : null;
    }
    public synchronized void release(Ticket ticket) { pending.remove(ticket.id()); }
    public synchronized void clear() { pending.clear(); }
    public synchronized int pendingCount() { return pending.size(); }
}
