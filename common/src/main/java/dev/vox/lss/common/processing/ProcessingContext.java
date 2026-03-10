package dev.vox.lss.common.processing;

import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Shared dependencies for the processing-thread collaborators
 * ({@link OffThreadProcessor}, {@link IncomingRequestRouter}).
 * Created once by {@link OffThreadProcessor} and passed to each collaborator.
 */
record ProcessingContext(
        ConcurrentLinkedQueue<SendAction> sendActions,
        ConcurrentLinkedQueue<OffThreadProcessor.GenerationTicketRequest> generationTicketRequests,
        ProcessingDiagnostics diagnostics,
        SequenceCounter sequence
) {}
