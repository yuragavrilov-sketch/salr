package ru.tcb.sal.demo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import ru.tcb.sal.commands.core.session.SessionSerializer;
import ru.tcb.sal.commands.core.transport.amqp.RecordedMessageConverter;
import ru.tcb.sal.commands.core.wire.RecordedMessage;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class CommandSenderService {

    private static final Logger log = LoggerFactory.getLogger(CommandSenderService.class);

    private final RabbitTemplate rabbitTemplate;
    private final RecordedMessageConverter converter;
    private final SessionSerializer sessionSerializer;
    private final String adapterName;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AtomicLong messageIdCounter = new AtomicLong(1);

    public CommandSenderService(
            RabbitTemplate rabbitTemplate,
            RecordedMessageConverter converter,
            SessionSerializer sessionSerializer,
            @Value("${demo.adapter-name}") String adapterName) {
        this.rabbitTemplate = rabbitTemplate;
        this.converter = converter;
        this.sessionSerializer = sessionSerializer;
        this.adapterName = adapterName;
        log.info("[INIT] CommandSenderService created, adapterName={}", adapterName);
    }

    public String sendCommand(String commandTypeFull, String assemblyQualified, String payloadJson) {
        String correlationId = UUID.randomUUID().toString();
        long msgId = messageIdCounter.incrementAndGet();

        log.info("[SEND] Building command: correlationId={}, commandType={}, messageId={}",
            correlationId, commandTypeFull, msgId);
        log.debug("[SEND] Payload JSON: {}", payloadJson);

        RecordedMessage rm = new RecordedMessage();
        rm.correlationId = correlationId;
        rm.exchangeName = "CommandExchange";
        rm.routingKey = commandTypeFull;
        rm.contentType = assemblyQualified;
        rm.messageId = String.valueOf(msgId);
        rm.priority = 5;
        rm.timeStamp = Instant.now();
        rm.sourceServiceId = adapterName;
        rm.acceptLanguage = "ru-RU";

        try {
            rm.payload = objectMapper.readTree(payloadJson);
        } catch (Exception e) {
            log.error("[SEND] Invalid JSON payload: {}", e.getMessage());
            throw new IllegalArgumentException("payloadJson is not valid JSON: " + e.getMessage(), e);
        }

        rm.additionalData = new LinkedHashMap<>();
        ObjectNode sessionNode = sessionSerializer.mapper().createObjectNode();
        sessionNode.put("SessionId", "demo-" + correlationId.substring(0, 8));
        sessionNode.put("OperationId", msgId);
        String sessionBase64 = sessionSerializer.serialize(sessionNode);
        rm.additionalData.put("Session", sessionBase64);
        rm.additionalData.put("IsCommand", "");
        rm.additionalData.put("SourceServiceId", adapterName);

        log.debug("[SEND] AdditionalData keys: {}", rm.additionalData.keySet());
        log.debug("[SEND] Session base64 length: {} chars", sessionBase64.length());

        RecordedMessageConverter.AmqpWireMessage wire = converter.toAmqp(rm);

        MessageProperties props = new MessageProperties();
        props.setContentType(wire.contentType());
        props.setCorrelationId(wire.correlationId());
        props.setMessageId(wire.messageId());
        props.setPriority(wire.priority());
        props.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        if (wire.timestamp() != null) {
            props.setTimestamp(wire.timestamp());
        }
        if (wire.headers() != null) {
            wire.headers().forEach(props::setHeader);
        }

        log.info("[SEND] Publishing to exchange='{}' routingKey='{}' contentType='{}' priority={} correlationId={}",
            wire.exchange(), wire.routingKey(), wire.contentType(), wire.priority(), wire.correlationId());
        log.debug("[SEND] AMQP headers: {}", wire.headers() != null ? wire.headers().keySet() : "none");
        log.debug("[SEND] Body size: {} bytes", wire.body() != null ? wire.body().length : 0);

        try {
            Message message = new Message(wire.body(), props);
            rabbitTemplate.send(wire.exchange(), wire.routingKey(), message);
            log.info("[SEND] Published successfully. correlationId={}", correlationId);
        } catch (Exception e) {
            log.error("[SEND] Failed to publish: correlationId={}, error={}", correlationId, e.getMessage(), e);
            throw new RuntimeException("Failed to send command: " + e.getMessage(), e);
        }

        return correlationId;
    }
}
