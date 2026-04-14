package ru.copperside.sal.commands.core.transport;

import ru.copperside.sal.commands.core.transport.amqp.RecordedMessageConverter;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * In-memory transport for testing. Published messages are routed to
 * registered consumers by exchange name. No RabbitMQ needed.
 */
public class InMemoryCommandTransport implements CommandTransport {

    private final Map<String, Consumer<RecordedMessageConverter.AmqpWireMessage>> consumers
        = new ConcurrentHashMap<>();

    @Override
    public CompletableFuture<Void> publish(RecordedMessageConverter.AmqpWireMessage message) {
        Consumer<RecordedMessageConverter.AmqpWireMessage> consumer = consumers.get(message.exchange());
        if (consumer != null) {
            try {
                consumer.accept(message);
            } catch (Exception e) {
                return CompletableFuture.failedFuture(e);
            }
        }
        return CompletableFuture.completedFuture(null);
    }

    public void registerConsumer(String exchange, Consumer<RecordedMessageConverter.AmqpWireMessage> consumer) {
        consumers.put(exchange, consumer);
    }
}
