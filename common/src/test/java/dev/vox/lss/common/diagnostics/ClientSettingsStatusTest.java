package dev.vox.lss.common.diagnostics;

import dev.vox.lss.common.config.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ClientSettingsStatusTest {
    @TempDir Path directory;
    @Test void saveReloadAndReconnectHaveDistinctSafeExportStates() throws Exception {
        var store = new SettingsStore<ClientSettings>(directory, "lss", SettingsSchema.client());
        var handle = new SettingsHandle<>(store);
        Object connection = new Object(); handle.beginSession(connection);
        var saved = store.saveDraft(store.read().hash(), Map.of("lod.receive", false,
                "integrations.map_ingestion.enabled", false,
                "cache.address_aliases", List.of(List.of("private.example", "secret.example")))).configured();
        var before = capture(saved, handle);
        assertFalse(before.saved().receptionEnabled());
        assertTrue(before.configured().receptionEnabled());
        assertTrue(before.effective().receptionEnabled());
        assertEquals(List.of("cache.address_aliases", "integrations.map_ingestion.enabled", "lod.receive"), before.pendingReload());
        handle.commit(handle.prepareReload());
        var accepted = capture(saved, handle);
        assertTrue(accepted.pendingReload().isEmpty());
        assertFalse(accepted.effective().receptionEnabled());
        assertFalse(accepted.configured().mapIngestionEnabled());
        assertTrue(accepted.effective().mapIngestionEnabled());
        assertEquals(List.of("cache.address_aliases", "integrations.map_ingestion.enabled"), accepted.pendingReconnect());
        var snapshot = new ClientStatusSnapshot(ClientStatusSnapshot.SCHEMA_VERSION, 1, 0, true, true,
                false, true, true, false, 20, 512, 128, 0, 0, 0, 0, 0, 0, 0, 0,
                ClientStatusSnapshot.Discovery.NEGOTIATED, ClientStatusSnapshot.Availability.DISABLED,
                null, DiagnosticVersions.unknown(), accepted);
        String json = DiagnosticExport.clientJson(snapshot);
        assertTrue(json.contains("\"schemaVersion\": 7"), "the reason, the renderer's answer and the settings problems (0.23.0) bumped the snapshot schema to 7");
        assertTrue(json.contains("pendingReconnect"));
        assertFalse(json.contains("private.example")); assertFalse(json.contains("secret.example"));
        handle.endSession(connection);
        var reconnected = capture(saved, handle);
        assertTrue(reconnected.pendingReconnect().isEmpty());
        assertFalse(reconnected.effective().mapIngestionEnabled());
    }
    @Test void arbitraryStringsCannotMasqueradeAsPendingSettingsPaths() {
        var defaults = SettingsSchema.client().defaults();
        var status = ClientSettingsStatus.capture(true, defaults, defaults, defaults,
                Set.of("cache.address_aliases", "private.example", "/home/person/config"));
        assertEquals(List.of("cache.address_aliases"), status.pendingReconnect());
    }
    @Test void failedFutureRemainsDistinctFromPublicationUntilIdenticalRetrySucceeds() throws Exception {
        var store = new SettingsStore<ClientSettings>(directory, "lss", SettingsSchema.client());
        var handle = new SettingsHandle<>(store);
        var saved = store.saveDraft(store.read().hash(), Map.of("lod.receive", false)).configured();
        try (var reload = new SettingsReload<>(handle)) {
            var receipt = new java.util.concurrent.CompletableFuture<Void>();
            var entered = new java.util.concurrent.CountDownLatch(1);
            var future = reload.reload(Runnable::run, (before, after, revision) -> { entered.countDown(); return receipt; });
            assertTrue(entered.await(10, java.util.concurrent.TimeUnit.SECONDS));
            var published = capture(saved, handle);
            assertFalse(published.effective().receptionEnabled());
            assertEquals(1,published.publishedRevision());
            assertEquals(0,published.adoptedRevision());
            assertEquals(List.of("lod.receive"),published.pendingAdoption());
            receipt.completeExceptionally(new IllegalStateException("private.example /private/path"));
            assertEquals(SettingsReload.Status.RECONCILIATION_FAILED,future.get(10,java.util.concurrent.TimeUnit.SECONDS).status());
            assertEquals(published,capture(saved,handle));
            var repaired=reload.reload(Runnable::run,(before,after,revision)->java.util.concurrent.CompletableFuture.completedFuture(null))
                    .get(10,java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(SettingsReload.Status.APPLIED,repaired.status());
            var adopted=capture(saved,handle);
            assertEquals(adopted.publishedRevision(),adopted.adoptedRevision());
            assertTrue(adopted.pendingAdoption().isEmpty());
        }
    }
    private ClientSettingsStatus capture(ClientSettings saved, SettingsHandle<ClientSettings> handle) {
        var s=handle.state();return ClientSettingsStatus.capture(true,saved,s.configured(),s.effective(),s.pendingReconnect(),s.revision(),handle.adoptedRevision(),
                handle.pendingAdoption()==null?Set.of():handle.pendingAdoption().paths());
    }
}
