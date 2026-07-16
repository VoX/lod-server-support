package dev.vox.lss.common.processing;

/**
 * One decoded want-set batch from a client, already range-filtered at ingress.
 * Immutable after construction (the array is never mutated), so it is safe to
 * peek from the main thread while the network thread may replace the mailbox
 * reference. An EMPTY batch is a meaningful message: "I want nothing" — it
 * replaces the backlog with nothing (the client's backpressure clear).
 */
public record IncomingBatch(IncomingRequest[] requests) {
    public int size() { return this.requests.length; }
}
