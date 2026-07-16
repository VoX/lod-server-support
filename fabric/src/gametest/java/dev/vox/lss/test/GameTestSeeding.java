package dev.vox.lss.test;

import dev.vox.lss.common.PositionUtil;
import dev.vox.lss.common.processing.IncomingBatch;
import dev.vox.lss.common.processing.IncomingRequest;
import dev.vox.lss.networking.server.PlayerRequestState;

/**
 * Shared want-set (protocol v17) seeding for the Tier-2 gametests.
 *
 * <p><b>Multiple positions MUST be seeded as ONE batch.</b> The ingress mailbox is
 * latest-wins by design: a second {@code offerIncomingBatch} supersedes the first outright
 * (the superseded entries are counted, never routed). Two sequential single-position seeds
 * therefore route only the LAST position — that is the protocol's semantics, not a test bug.
 * A test that wants two positions routed declares them together, exactly as the real client
 * re-declares its whole unsatisfied want-set every scan. Sequential seeds are only correct
 * when a routing cycle has provably consumed the first batch in between.
 *
 * <p>This is a plain {@code offerIncomingBatch} — the same thing
 * {@code RequestProcessingService.handleBatchRequest} does after the distance guard, with no
 * test seam. The main-thread probe reads the mailbox before the published want-set, so a
 * batch seeded before a {@code tick()} is probed on that tick and routed against its own
 * probes, exactly as a real declaration is.
 */
final class GameTestSeeding {

    private GameTestSeeding() {}

    /** Declare one want-set: {@code packed[i]} at {@code timestamps[i]}, as a single batch. */
    static void seedRequests(PlayerRequestState state, long[] packed, long[] timestamps) {
        var reqs = new IncomingRequest[packed.length];
        for (int i = 0; i < packed.length; i++) {
            reqs[i] = new IncomingRequest(PositionUtil.unpackX(packed[i]),
                    PositionUtil.unpackZ(packed[i]), timestamps[i]);
        }
        state.offerIncomingBatch(new IncomingBatch(reqs));
    }

    /** Single-position want-set declaration (the common case). */
    static void seedRequest(PlayerRequestState state, long packed, long timestamp) {
        seedRequests(state, new long[]{packed}, new long[]{timestamp});
    }

    /**
     * True when nothing this player declared is still awaiting a routing decision: the
     * mailbox is empty (the batch was taken) AND the backlog is empty (every entry got a
     * disposition or a slot). The v17 successor of {@code getIncomingRequestCount() == 0}
     * for the guarded-re-ask retry loops — a declaration lives in the mailbox until a cycle
     * takes it, then in the backlog until that cycle disposes of it.
     */
    static boolean noDeclarationOutstanding(PlayerRequestState state) {
        return state.peekIncomingBatch() == null && state.getBacklogSize() == 0;
    }
}
