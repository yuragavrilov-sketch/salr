package ru.tcb.sal.commands.core.transport.amqp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import ru.tcb.sal.commands.core.transport.CommandTransport;

import java.util.concurrent.CompletableFuture;

public class SpringAmqpCommandTransport implements CommandTransport {

    private static final Logger log = LoggerFactory.getLogger(SpringAmqpCommandTransport.class);

    private final RabbitTemplate rabbitTemplate;

    public SpringAmqpCommandTransport(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    @Override
    public CompletableFuture<Void> publish(RecordedMessageConverter.AmqpWireMessage wire) {
        try {
            MessageProperties props = new MessageProperties();
            if (wire.contentType() != null) props.setContentType(wire.contentType());
            if (wire.correlationId() != null) props.setCorrelationId(wire.correlationId());
            if (wire.messageId() != null) props.setMessageId(wire.messageId());
            props.setPriority(wire.priority());
            props.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
            if (wire.timestamp() != null) props.setTimestamp(wire.timestamp());
            if (wire.expiration() != null) props.setExpiration(wire.expiration());
            if (wire.headers() != null) wire.headers().forEach(props::setHeader);

            Message message = new Message(wire.body(), props);
            rabbitTemplate.send(wire.exchange(), wire.routingKey(), message);

            log.info("[TRANSPORT] Published: exchange='{}' routingKey='{}' correlationId={}",
                wire.exchange(), wire.routingKey(), wire.correlationId());
            return CompletableFuture.completedFuture(null);
        } catch (Exception e) {
            log.error("[TRANSPORT] Publish failed: {}", e.getMessage(), e);
            return CompletableFuture.failedFuture(e);
        }
    }
}
