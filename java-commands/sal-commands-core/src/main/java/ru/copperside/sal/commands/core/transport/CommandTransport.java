package ru.copperside.sal.commands.core.transport;

import ru.copperside.sal.commands.core.transport.amqp.RecordedMessageConverter;
import java.util.concurrent.CompletableFuture;

public interface CommandTransport {
    CompletableFuture<Void> publish(RecordedMessageConverter.AmqpWireMessage message);
}
