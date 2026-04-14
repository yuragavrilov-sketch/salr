package ru.copperside.sal.commands.core.transport.amqp;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import ru.copperside.sal.commands.core.wire.CommandCompletedEvent;
import ru.copperside.sal.commands.core.wire.CommandFailedEvent;
import ru.copperside.sal.commands.core.wire.RecordedMessage;
import ru.copperside.sal.commands.core.wire.WireTypeRegistry;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Converts between {@link RecordedMessage} (internal Java struct) and the
 * real .NET SAL wire format: AMQP properties/headers + raw body bytes.
 *
 * <p>Key insight from real wire analysis: .NET SAL does NOT serialize
 * RecordedMessage as a single JSON blob. Instead:
 * <ul>
 *   <li>AMQP body = raw JSON of command payload (for commands) or
 *       CommandCompletedEvent/CommandFailedEvent (for results)</li>
 *   <li>AMQP content_type = .NET AssemblyQualifiedName of the payload type</li>
 *   <li>AMQP headers["Additional-Data"] = nested JSON string with Session,
 *       IsCommand, SourceServiceId</li>
 *   <li>AMQP headers["Accept-Language"] = locale</li>
 *   <li>correlationId, messageId, priority, timestamp = standard AMQP properties</li>
 * </ul>
 *
 * <p>The ObjectMapper used here does NOT force any naming strategy — field names
 * in JSON match whatever the .NET class declares (MercID, GateId, Is3DS, etc.).
 */
public class RecordedMessageConverter {

    private final ObjectMapper mapper;
    private final WireTypeRegistry registry;

    public RecordedMessageConverter(WireTypeRegistry registry) {
        this.registry = registry;
        this.mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_PROPERTIES);
    }

    /**
     * Represents the AMQP-level message: body bytes + properties + headers.
     * This is what goes into / comes from RabbitMQ channel operations.
     */
    public record AmqpWireMessage(
        byte[] body,
        String exchange,
        String routingKey,
        String contentType,
        String correlationId,
        String messageId,
        int priority,
        int deliveryMode,
        Date timestamp,
        String expiration,
        Map<String, Object> headers
    ) {}

    /**
     * Convert internal RecordedMessage to AMQP wire format for publishing.
     */
    public AmqpWireMessage toAmqp(RecordedMessage rm) {
        try {
            byte[] body = mapper.writeValueAsBytes(rm.payload);

            Map<String, Object> headers = new LinkedHashMap<>();
            if (!rm.additionalData.isEmpty()) {
                headers.put("Additional-Data", mapper.writeValueAsString(rm.additionalData));
            }
            if (rm.acceptLanguage != null) {
                headers.put("Accept-Language", rm.acceptLanguage);
            }

            String contentType = rm.contentType;
            if (contentType == null && rm.payload != null) {
                Class<?> payloadClass = rm.payload.getClass();
                if (registry.isRegistered(payloadClass)) {
                    contentType = registry.assemblyQualifiedName(payloadClass);
                }
            }

            return new AmqpWireMessage(
                body,
                rm.exchangeName,
                rm.routingKey,
                contentType,
                rm.correlationId,
                rm.messageId,
                rm.priority,
                2,  // deliveryMode = persistent
                rm.timeStamp != null ? Date.from(rm.timeStamp) : null,
                null,  // expiration
                headers
            );
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to build AMQP message", e);
        }
    }

    /**
     * Convert incoming AMQP message to internal RecordedMessage.
     * Payload is left as JsonNode — call {@link #readPayloadAs} to resolve to concrete type.
     */
    public RecordedMessage fromAmqp(AmqpWireMessage amqp) {
        RecordedMessage rm = new RecordedMessage();
        rm.exchangeName = amqp.exchange();
        rm.routingKey = amqp.routingKey();
        rm.contentType = amqp.contentType();
        rm.correlationId = amqp.correlationId();
        rm.messageId = amqp.messageId();
        rm.priority = amqp.priority();
        rm.timeStamp = amqp.timestamp() != null ? amqp.timestamp().toInstant() : null;

        // Parse Additional-Data header (nested JSON string)
        if (amqp.headers() != null) {
            Object additionalDataRaw = amqp.headers().get("Additional-Data");
            if (additionalDataRaw instanceof String adStr && !adStr.isEmpty()) {
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, String> ad = mapper.readValue(adStr, Map.class);
                    rm.additionalData = ad;
                    rm.sourceServiceId = ad.get("SourceServiceId");
                } catch (IOException e) {
                    // Tolerate corrupted Additional-Data
                    rm.additionalData = new LinkedHashMap<>();
                }
            }
            Object langRaw = amqp.headers().get("Accept-Language");
            if (langRaw instanceof String lang) {
                rm.acceptLanguage = lang;
            }
        }

        // Parse body as JsonNode — type resolution comes later
        if (amqp.body() != null && amqp.body().length > 0) {
            try {
                rm.payload = mapper.readTree(amqp.body());
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to parse AMQP body as JSON", e);
            }
        }

        return rm;
    }

    /**
     * Resolve payload JsonNode into a concrete Java type.
     */
    public <T> T readPayloadAs(RecordedMessage rm, Class<T> targetType) {
        if (rm.payload == null) return null;
        if (targetType.isInstance(rm.payload)) return targetType.cast(rm.payload);
        if (rm.payload instanceof JsonNode node) {
            try {
                return mapper.treeToValue(node, targetType);
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to convert payload to " + targetType.getName(), e);
            }
        }
        try {
            byte[] bytes = mapper.writeValueAsBytes(rm.payload);
            return mapper.readValue(bytes, targetType);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to convert payload to " + targetType.getName(), e);
        }
    }

    /**
     * Resolve payload as CommandCompletedEvent.
     */
    public CommandCompletedEvent readAsCompleted(RecordedMessage rm) {
        return readPayloadAs(rm, CommandCompletedEvent.class);
    }

    /**
     * Resolve payload as CommandFailedEvent.
     */
    public CommandFailedEvent readAsFailed(RecordedMessage rm) {
        return readPayloadAs(rm, CommandFailedEvent.class);
    }

    /**
     * Convert a Spring AMQP Message to AmqpWireMessage.
     * Used by listener containers to bridge Spring AMQP into our wire model.
     */
    public AmqpWireMessage fromSpringMessage(org.springframework.amqp.core.Message message) {
        var props = message.getMessageProperties();
        java.util.Map<String, Object> headers = new java.util.LinkedHashMap<>();
        if (props.getHeaders() != null) {
            props.getHeaders().forEach((k, v) -> {
                if (v != null) headers.put(k, v.toString());
            });
        }
        return new AmqpWireMessage(
            message.getBody(),
            props.getReceivedExchange(),
            props.getReceivedRoutingKey(),
            props.getContentType(),
            props.getCorrelationId(),
            props.getMessageId(),
            props.getPriority() != null ? props.getPriority() : 0,
            props.getReceivedDeliveryMode() != null ? props.getReceivedDeliveryMode().ordinal() + 1 : 2,
            props.getTimestamp(),
            props.getExpiration(),
            headers
        );
    }

    public ObjectMapper mapper() { return mapper; }
    public WireTypeRegistry registry() { return registry; }
}
