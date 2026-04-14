package ru.copperside.sal.commands.core.listener;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.copperside.sal.commands.core.handler.CommandHandlerRegistry;
import ru.copperside.sal.commands.core.handler.HandlerBinding;
import ru.copperside.sal.commands.core.transport.CommandTransport;
import ru.copperside.sal.commands.core.transport.amqp.RecordedMessageConverter;
import ru.copperside.sal.commands.core.wire.RecordedMessage;

import java.time.Duration;
import java.time.Instant;

public class CommandListener {

    private static final Logger log = LoggerFactory.getLogger(CommandListener.class);

    private final CommandHandlerRegistry handlerRegistry;
    private final RecordedMessageConverter converter;
    private final ResponseBuilder responseBuilder;
    private final CommandTransport transport;

    public CommandListener(CommandHandlerRegistry handlerRegistry,
                            RecordedMessageConverter converter,
                            ResponseBuilder responseBuilder,
                            CommandTransport transport) {
        this.handlerRegistry = handlerRegistry;
        this.converter = converter;
        this.responseBuilder = responseBuilder;
        this.transport = transport;
    }

    public void onMessage(RecordedMessageConverter.AmqpWireMessage amqpMessage) {
        Instant startTime = Instant.now();
        RecordedMessage rm = converter.fromAmqp(amqpMessage);
        String commandType = rm.routingKey;

        log.info("[CMD] Received: type='{}' correlationId={}", commandType, rm.correlationId);

        HandlerBinding binding = handlerRegistry.find(commandType);
        if (binding == null) {
            log.error("[CMD] No handler for '{}'. Dropping.", commandType);
            return;
        }

        String sessionBase64 = rm.additionalData != null ? rm.additionalData.get("Session") : null;

        try {
            Object command = converter.readPayloadAs(rm, binding.commandClass());
            Object result;

            try {
                if (binding.hasContextParam()) {
                    result = binding.invoker().invoke(command, (Object) null);
                } else {
                    result = binding.invoker().invoke(command);
                }
            } catch (Throwable t) {
                throw (t instanceof Exception e) ? e : new RuntimeException(t);
            }

            Duration executionDuration = Duration.between(startTime, Instant.now());

            if (binding.isVoid()) {
                log.info("[CMD] Completed (void): correlationId={} duration={}ms",
                    rm.correlationId, executionDuration.toMillis());
                return;
            }

            String resultWireName = binding.resultClass() != null
                    && converter.registry().isRegistered(binding.resultClass())
                ? converter.registry().assemblyQualifiedName(binding.resultClass())
                : "System.Object, mscorlib";

            RecordedMessage response = responseBuilder.buildCompleted(
                rm, result, resultWireName, executionDuration, sessionBase64);
            transport.publish(converter.toAmqp(response));

            log.info("[CMD] Sent completed: correlationId={} duration={}ms",
                rm.correlationId, executionDuration.toMillis());

        } catch (Exception e) {
            Duration executionDuration = Duration.between(startTime, Instant.now());
            log.error("[CMD] Handler failed: correlationId={} error='{}'",
                rm.correlationId, e.getMessage(), e);

            RecordedMessage response = responseBuilder.buildFailed(
                rm, e, executionDuration, sessionBase64);
            transport.publish(converter.toAmqp(response));
        }
    }
}
