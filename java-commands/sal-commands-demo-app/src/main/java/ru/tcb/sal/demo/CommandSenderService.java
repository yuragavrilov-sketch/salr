package ru.tcb.sal.demo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
    }

    /**
     * Send a command to the .NET SAL bus.
     *
     * @param commandTypeFull    .NET FullName, e.g. "TCB.MercLibrary.Client.Commands.GetConfigurationCommand"
     * @param assemblyQualified  AssemblyQualifiedName for content_type,
     *                           e.g. "TCB.MercLibrary.Client.Commands.GetConfigurationCommand, TCB.MercLibrary.Client"
     * @param payloadJson        raw JSON body of the command
     * @return correlationId for tracking the result
     */
    public String sendCommand(String commandTypeFull, String assemblyQualified, String payloadJson) {
        String correlationId = UUID.randomUUID().toString();

        RecordedMessage rm = new RecordedMessage();
        rm.correlationId = correlationId;
        rm.exchangeName = "CommandExchange";
        rm.routingKey = commandTypeFull;
        rm.contentType = assemblyQualified;
        rm.messageId = String.valueOf(messageIdCounter.incrementAndGet());
        rm.priority = 5;
        rm.timeStamp = Instant.now();
        rm.sourceServiceId = adapterName;
        rm.acceptLanguage = "ru-RU";

        // Parse payloadJson into JsonNode so toAmqp serialises it correctly (no double-encoding)
        try {
            rm.payload = objectMapper.readTree(payloadJson);
        } catch (Exception e) {
            throw new IllegalArgumentException("payloadJson is not valid JSON: " + e.getMessage(), e);
        }

        // Build AdditionalData with Session and markers
        rm.additionalData = new LinkedHashMap<>();
        ObjectNode sessionNode = sessionSerializer.mapper().createObjectNode();
        sessionNode.put("SessionId", "demo-" + correlationId.substring(0, 8));
        sessionNode.put("OperationId", messageIdCounter.get());
        rm.additionalData.put("Session", sessionSerializer.serialize(sessionNode));
        rm.additionalData.put("IsCommand", "");
        rm.additionalData.put("SourceServiceId", adapterName);

        // Convert to AMQP wire format
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

        Message message = new Message(wire.body(), props);
        rabbitTemplate.send(wire.exchange(), wire.routingKey(), message);

        return correlationId;
    }
}
