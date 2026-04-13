package ru.tcb.sal.commands.core.bus;

import java.time.Instant;
import java.util.Collection;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public class CorrelationStore {

    public record Pending(
        String correlationId,
        Instant expireAt,
        CompletableFuture<?> future,
        Class<?> expectedResultType
    ) {}

    private final ConcurrentHashMap<String, Pending> pending = new ConcurrentHashMap<>();

    public void register(String cid, Pending p) { pending.put(cid, p); }
    public Pending remove(String cid) { return pending.remove(cid); }

    public Collection<Pending> expired(Instant now) {
        return pending.values().stream()
            .filter(p -> p.expireAt().isBefore(now))
            .toList();
    }

    public int size() { return pending.size(); }
}
