package dev.vox.lss.common.processing;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Accumulates {@link SendAction} responses into per-player batches for efficient
 * wire transmission. Reusable across ticks via {@link #clear()}.
 */
public final class SendActionBatcher {
    private final Map<UUID, PlayerBatch> batches = new HashMap<>();

    private static final int INITIAL_CAPACITY = 64;

    public void add(UUID playerUuid, byte responseType, int requestId) {
        var batch = batches.computeIfAbsent(playerUuid, k -> new PlayerBatch());
        batch.add(responseType, requestId);
    }

    public boolean isEmpty() {
        return batches.isEmpty();
    }

    public void forEach(BatchConsumer consumer) {
        for (var entry : batches.entrySet()) {
            var batch = entry.getValue();
            if (batch.count > 0) {
                consumer.accept(entry.getKey(), batch.types, batch.requestIds, batch.count);
            }
        }
    }

    public void clear() {
        batches.clear();
    }

    @FunctionalInterface
    public interface BatchConsumer {
        void accept(UUID playerUuid, byte[] responseTypes, int[] requestIds, int count);
    }

    private static final class PlayerBatch {
        byte[] types = new byte[INITIAL_CAPACITY];
        int[] requestIds = new int[INITIAL_CAPACITY];
        int count;

        void add(byte responseType, int requestId) {
            if (count >= types.length) {
                types = Arrays.copyOf(types, types.length * 2);
                requestIds = Arrays.copyOf(requestIds, requestIds.length * 2);
            }
            types[count] = responseType;
            requestIds[count] = requestId;
            count++;
        }
    }
}
