package ru.copperside.sal.commands.core.bus;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.copperside.sal.commands.api.*;
import ru.copperside.sal.commands.core.session.SessionSerializer;
import ru.copperside.sal.commands.core.transport.CommandTransport;
import ru.copperside.sal.commands.core.transport.amqp.RecordedMessageConverter;
import ru.copperside.sal.commands.core.wire.RecordedMessage;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

public class CommandBusImpl implements CommandBus {

    private static final Logger log = LoggerFactory.getLogger(CommandBusImpl.class);

    private final CommandTransport transport;
    private final RecordedMessageConverter converter;
    private final SessionSerializer sessionSerializer;
    private final CorrelationStore store;
    private final String adapterName;
    private final Duration defaultTimeout;
    private final AtomicLong messageIdCounter = new AtomicLong(1);

    public CommandBusImpl(CommandTransport transport,
                           RecordedMessageConverter converter,
                           SessionSerializer sessionSerializer,
                           CorrelationStore store,
                           String adapterName,
                           Duration defaultTimeout) {
        this.transport = transport;
        this.converter = converter;
        this.sessionSerializer = sessionSerializer;
        this.store = store;
        this.adapterName = adapterName;
        this.defaultTimeout = defaultTimeout;
    }

    @Override
    public <R extends CommandResult> R execute(CommandWithResult<R> command) {
        return executeAsync(command).join();
    }

    @Override
    public <R extends CommandResult> CompletableFuture<R> executeAsync(CommandWithResult<R> command) {
        return executeAsync(command, defaultTimeout, CommandPriority.NORMAL);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <R extends CommandResult> CompletableFuture<R> executeAsync(
            CommandWithResult<R> command, Duration timeout, CommandPriority priority) {

        String correlationId = UUID.randomUUID().toString();
        Instant expireAt = Instant.now().plus(timeout);

        CompletableFuture<R> future = new CompletableFuture<>();
        store.register(correlationId, new CorrelationStore.Pending(
            correlationId, expireAt, future, command.resultType()));

        RecordedMessage rm = buildRecordedMessage(command, correlationId, priority);
        RecordedMessageConverter.AmqpWireMessage wire = converter.toAmqp(rm);

        log.info("[BUS] Sending: correlationId={} routingKey='{}' contentType='{}'",
            correlationId, rm.routingKey, rm.contentType);

        transport.publish(wire).whenComplete((ack, publishEx) -> {
            if (publishEx != null) {
                store.remove(correlationId);
                future.completeExceptionally(new RuntimeException(
                    "Publish failed: " + publishEx.getMessage(), publishEx));
                log.error("[BUS] Publish failed: correlationId={}", correlationId, publishEx);
            }
        });

        return future.orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS)
            .whenComplete((r, ex) -> {
                if (ex instanceof TimeoutException) {
                    store.remove(correlationId);
                    log.warn("[BUS] Timeout: correlationId={}", correlationId);
                }
            });
    }

    @Override
    public String publish(Command command) {
        String correlationId = UUID.randomUUID().toString();
        RecordedMessage rm = buildRecordedMessage(command, correlationId, CommandPriority.NORMAL);
        RecordedMessageConverter.AmqpWireMessage wire = converter.toAmqp(rm);
        transport.publish(wire);
        log.info("[BUS] Fire-and-forget: correlationId={} routingKey='{}'",
            correlationId, rm.routingKey);
        return correlationId;
    }

    private RecordedMessage buildRecordedMessage(Command command, String correlationId,
                                                   CommandPriority priority) {
        String wireName = converter.registry().wireName(command.getClass());
        String assemblyQualified = converter.registry().assemblyQualifiedName(command.getClass());

        RecordedMessage rm = new RecordedMessage();
        rm.correlationId = correlationId;
        rm.exchangeName = "CommandExchange";
        rm.routingKey = wireName;
        rm.contentType = assemblyQualified;
        rm.messageId = String.valueOf(messageIdCounter.incrementAndGet());
        rm.priority = priority.asByte();
        rm.timeStamp = Instant.now();
        rm.sourceServiceId = adapterName;
        rm.acceptLanguage = "ru-RU";
        rm.payload = command;

        rm.additionalData = new LinkedHashMap<>();
        ObjectNode sessionNode = sessionSerializer.mapper().createObjectNode();
        sessionNode.put("SessionId", "");
        sessionNode.put("OperationId", messageIdCounter.get());
        rm.additionalData.put("Session", sessionSerializer.serialize(sessionNode));
        rm.additionalData.put("IsCommand", "");
        rm.additionalData.put("SourceServiceId", adapterName);

        return rm;
    }
}
