package ru.copperside.sal.commands.core.bus;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class TimeoutWatcher {

    private static final Logger log = LoggerFactory.getLogger(TimeoutWatcher.class);

    private final CorrelationStore store;
    private final ScheduledExecutorService scheduler;

    public TimeoutWatcher(CorrelationStore store) {
        this.store = store;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "sal-timeout-watcher");
            t.setDaemon(true);
            return t;
        });
    }

    public void start() {
        scheduler.scheduleWithFixedDelay(this::sweep, 30, 30, TimeUnit.SECONDS);
        log.info("[TIMEOUT] Watcher started (30s interval)");
    }

    public void stop() {
        scheduler.shutdown();
        log.info("[TIMEOUT] Watcher stopped");
    }

    private void sweep() {
        var expired = store.expired(Instant.now());
        for (var p : expired) {
            if (store.remove(p.correlationId()) != null) {
                p.future().completeExceptionally(
                    new TimeoutException("Command " + p.correlationId() + " timed out"));
                log.warn("[TIMEOUT] Expired: correlationId={}", p.correlationId());
            }
        }
    }
}
