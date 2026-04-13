package ru.tcb.sal.commands.core.bus;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import static org.assertj.core.api.Assertions.assertThat;

class CorrelationStoreTest {

    @Test
    void registerAndRemove_returnsOriginal() {
        CorrelationStore store = new CorrelationStore();
        CompletableFuture<Object> future = new CompletableFuture<>();
        store.register("cid-1", new CorrelationStore.Pending("cid-1",
            Instant.now().plusSeconds(60), future, Object.class));
        assertThat(store.size()).isEqualTo(1);
        CorrelationStore.Pending removed = store.remove("cid-1");
        assertThat(removed).isNotNull();
        assertThat(removed.future()).isSameAs(future);
        assertThat(store.size()).isEqualTo(0);
    }

    @Test
    void remove_unknown_returnsNull() {
        assertThat(new CorrelationStore().remove("unknown")).isNull();
    }

    @Test
    void expired_returnsOnlyExpired() {
        CorrelationStore store = new CorrelationStore();
        store.register("expired", new CorrelationStore.Pending("expired",
            Instant.now().minusSeconds(10), new CompletableFuture<>(), Object.class));
        store.register("alive", new CorrelationStore.Pending("alive",
            Instant.now().plusSeconds(60), new CompletableFuture<>(), Object.class));
        var expired = store.expired(Instant.now());
        assertThat(expired).hasSize(1);
        assertThat(expired.iterator().next().correlationId()).isEqualTo("expired");
    }

    @Test
    void doubleRemove_secondReturnsNull() {
        CorrelationStore store = new CorrelationStore();
        store.register("cid", new CorrelationStore.Pending("cid",
            Instant.now().plusSeconds(60), new CompletableFuture<>(), Object.class));
        assertThat(store.remove("cid")).isNotNull();
        assertThat(store.remove("cid")).isNull();
    }
}
