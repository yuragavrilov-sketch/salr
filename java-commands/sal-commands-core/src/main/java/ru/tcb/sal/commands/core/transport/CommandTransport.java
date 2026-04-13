package ru.tcb.sal.commands.core.transport;

import ru.tcb.sal.commands.core.transport.amqp.RecordedMessageConverter;
import java.util.concurrent.CompletableFuture;

public interface CommandTransport {
    CompletableFuture<Void> publish(RecordedMessageConverter.AmqpWireMessage message);
}
