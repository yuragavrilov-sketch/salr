package ru.copperside.sal.demo;

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

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Handles incoming commands from .NET adapters. Builds and sends
 * CommandCompletedEvent / CommandFailedEvent in the exact .NET wire format.
 *
 * <p>For the demo, acts as a generic "echo" handler: wraps the received
 * command payload into the result, adding processing metadata.
 */
@Service
public class IncomingCommandHandler {

    private static final Logger log = LoggerFactory.getLogger(IncomingCommandHandler.class);

    private static final String COMPLETED_EXCHANGE = "TCB.Infrastructure.Command.CommandCompletedEvent";
    private static final String COMPLETED_CONTENT_TYPE = "TCB.Infrastructure.Command.CommandCompletedEvent, TCB.Infrastructure";
    private static final String FAILED_EXCHANGE = "TCB.Infrastructure.Command.CommandFailedEvent";
    private static final String FAILED_CONTENT_TYPE = "TCB.Infrastructure.Command.CommandFailedEvent, TCB.Infrastructure";

    // .NET-style timestamp format (no trailing Z, local time)
    private static final DateTimeFormatter DOTNET_TS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    // .NET-style with offset
    private static final DateTimeFormatter DOTNET_TS_OFFSET = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSSSXXX");
    // .NET TimeSpan format
    private static final String TIMESPAN_FORMAT = "%02d:%02d:%02d.%07d";

    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;
    private final String adapterName;
    private final AtomicLong messageIdCounter = new AtomicLong(1000);

    public IncomingCommandHandler(
            RabbitTemplate rabbitTemplate,
            @Value("${demo.adapter-name}") String adapterName) {
        this.rabbitTemplate = rabbitTemplate;
        this.objectMapper = new ObjectMapper();
        this.adapterName = adapterName;
    }

    /**
     * Process an incoming command message and send back a CommandCompletedEvent.
     */
    @SuppressWarnings("unchecked")
    public void handleCommand(Message message) {
        Instant startTime = Instant.now();
        String correlationId = message.getMessageProperties().getCorrelationId();
        String commandType = message.getMessageProperties().getReceivedRoutingKey();
        String bodyJson = new String(message.getBody(), StandardCharsets.UTF_8);

        log.info("[HANDLER] Received command: type='{}' correlationId={} bodySize={}",
            commandType, correlationId, message.getBody().length);
        log.debug("[HANDLER] Body: {}", bodyJson);

        // Extract Additional-Data
        String additionalDataStr = null;
        String sourceServiceId = null;
        String sessionBase64 = null;
        try {
            Object adRaw = message.getMessageProperties().getHeaders().get("Additional-Data");
            if (adRaw instanceof String s) {
                additionalDataStr = s;
                Map<String, String> ad = objectMapper.readValue(s, Map.class);
                sourceServiceId = ad.get("SourceServiceId");
                sessionBase64 = ad.get("Session");
                log.info("[HANDLER] SourceServiceId='{}' (routing key for response)", sourceServiceId);
            }
        } catch (Exception e) {
            log.warn("[HANDLER] Failed to parse Additional-Data: {}", e.getMessage());
        }

        if (sourceServiceId == null || sourceServiceId.isBlank()) {
            log.error("[HANDLER] No SourceServiceId — cannot route response. Dropping command.");
            return;
        }

        try {
            // Build echo result: wrap the incoming payload
            ObjectNode result = objectMapper.createObjectNode();
            result.put("Echo", bodyJson);
            result.put("ProcessedBy", adapterName);
            result.put("ProcessedAt", Instant.now().toString());
            result.put("CommandType", commandType);

            Duration executionDuration = Duration.between(startTime, Instant.now());

            // Determine ResultType — for echo, use a generic name
            String resultType = commandType.replace("Command", "Result")
                .replace("Commands", "Results");
            // Add assembly (take from content_type if available, or use the command type's namespace)
            String contentTypeHeader = message.getMessageProperties().getContentType();
            String assembly = "JavaDemoAdapter";
            if (contentTypeHeader != null && contentTypeHeader.contains(",")) {
                assembly = contentTypeHeader.substring(contentTypeHeader.indexOf(",") + 1).trim();
            }
            String resultTypeAssemblyQualified = resultType + ", " + assembly;

            // Build CommandCompletedEvent body (matching .NET wire format exactly)
            ObjectNode eventBody = objectMapper.createObjectNode();
            eventBody.put("ResultType", resultTypeAssemblyQualified);
            eventBody.set("Result", result);
            eventBody.set("AdditionalData", objectMapper.createObjectNode());
            eventBody.set("Context", buildContext(
                commandType, correlationId, sourceServiceId,
                message.getMessageProperties().getTimestamp(),
                executionDuration));

            byte[] eventBytes = objectMapper.writeValueAsBytes(eventBody);

            // Build AMQP message with correct properties
            MessageProperties props = new MessageProperties();
            props.setContentType(COMPLETED_CONTENT_TYPE);
            props.setCorrelationId(correlationId);
            props.setMessageId(String.valueOf(messageIdCounter.incrementAndGet()));
            props.setPriority(5);
            props.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
            props.setTimestamp(new Date());

            // Additional-Data for response: Session passthrough + our SourceServiceId
            Map<String, String> responseAd = new LinkedHashMap<>();
            if (sessionBase64 != null) {
                responseAd.put("Session", sessionBase64);
            }
            responseAd.put("SourceServiceId", adapterName);
            props.setHeader("Additional-Data", objectMapper.writeValueAsString(responseAd));
            props.setHeader("Accept-Language", "ru-RU");

            Message response = new Message(eventBytes, props);

            log.info("[HANDLER] Sending CommandCompletedEvent: exchange='{}' routingKey='{}' correlationId={} bodySize={}",
                COMPLETED_EXCHANGE, sourceServiceId, correlationId, eventBytes.length);
            log.debug("[HANDLER] Response body: {}", new String(eventBytes, StandardCharsets.UTF_8));

            rabbitTemplate.send(COMPLETED_EXCHANGE, sourceServiceId, response);

            log.info("[HANDLER] Response sent successfully. correlationId={} duration={}ms",
                correlationId, executionDuration.toMillis());

        } catch (Exception e) {
            log.error("[HANDLER] Failed to process command: correlationId={} error={}",
                correlationId, e.getMessage(), e);
            sendFailedEvent(message, sourceServiceId, sessionBase64, correlationId, commandType, startTime, e);
        }
    }

    private ObjectNode buildContext(String commandType, String correlationId,
                                    String sourceServiceId, Date originalTimestamp,
                                    Duration executionDuration) {
        Instant now = Instant.now();
        ObjectNode ctx = objectMapper.createObjectNode();
        ctx.put("CommandType", commandType);
        ctx.put("CorrelationId", correlationId);
        ctx.put("SourceServiceId", sourceServiceId);

        // TimeStamp — .NET format without timezone (matches real dump: "2026-04-13T11:47:54")
        if (originalTimestamp != null) {
            ctx.put("TimeStamp", DOTNET_TS.format(originalTimestamp.toInstant().atOffset(ZoneOffset.ofHours(3))));
        } else {
            ctx.put("TimeStamp", DOTNET_TS.format(now.atOffset(ZoneOffset.ofHours(3))));
        }

        // Priority as string enum name (matches real dump: "Normal")
        ctx.put("Priority", "Normal");

        ctx.put("ExcutionServiceId", adapterName);   // .NET typo preserved

        // ExcutionTimeStamp with timezone offset (matches: "2026-04-13T11:47:54.50428+03:00")
        ctx.put("ExcutionTimeStamp", DOTNET_TS_OFFSET.format(now.atOffset(ZoneOffset.ofHours(3))));

        // ExcutionDuration as .NET TimeSpan (matches: "00:00:00.2392809")
        long totalNanos = executionDuration.toNanos();
        long hours = totalNanos / 3_600_000_000_000L;
        long minutes = (totalNanos % 3_600_000_000_000L) / 60_000_000_000L;
        long seconds = (totalNanos % 60_000_000_000L) / 1_000_000_000L;
        long ticks = (totalNanos % 1_000_000_000L) / 100L; // .NET ticks = 100ns
        ctx.put("ExcutionDuration", String.format(TIMESPAN_FORMAT, hours, minutes, seconds, ticks));

        ctx.put("SessionId", "");
        ctx.put("OperationId", "");

        return ctx;
    }

    private void sendFailedEvent(Message original, String sourceServiceId,
                                  String sessionBase64, String correlationId,
                                  String commandType, Instant startTime, Exception error) {
        try {
            Duration executionDuration = Duration.between(startTime, Instant.now());

            ObjectNode exceptionData = objectMapper.createObjectNode();
            exceptionData.put("ExceptionType", error.getClass().getName());
            exceptionData.put("Code", "JavaHandlerException");
            exceptionData.put("Message", error.getMessage());
            exceptionData.put("AdapterName", adapterName);
            exceptionData.put("SourceType", "IncomingCommandHandler");
            exceptionData.put("SourcePath", commandType);
            exceptionData.put("SessionId", "");
            exceptionData.put("SourceId", correlationId);
            exceptionData.put("TimeStamp", Instant.now().toString());
            exceptionData.set("Properties", objectMapper.createObjectNode()
                .put("Type", error.getClass().getName()));
            exceptionData.putNull("InnerException");

            ObjectNode eventBody = objectMapper.createObjectNode();
            eventBody.set("ExceptionData", exceptionData);
            eventBody.set("AdditionalData", objectMapper.createObjectNode());
            eventBody.set("Context", buildContext(
                commandType, correlationId, sourceServiceId,
                original.getMessageProperties().getTimestamp(),
                executionDuration));

            byte[] eventBytes = objectMapper.writeValueAsBytes(eventBody);

            MessageProperties props = new MessageProperties();
            props.setContentType(FAILED_CONTENT_TYPE);
            props.setCorrelationId(correlationId);
            props.setMessageId(String.valueOf(messageIdCounter.incrementAndGet()));
            props.setPriority(5);
            props.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
            props.setTimestamp(new Date());

            Map<String, String> responseAd = new LinkedHashMap<>();
            if (sessionBase64 != null) responseAd.put("Session", sessionBase64);
            responseAd.put("SourceServiceId", adapterName);
            props.setHeader("Additional-Data", objectMapper.writeValueAsString(responseAd));
            props.setHeader("Accept-Language", "ru-RU");

            Message response = new Message(eventBytes, props);
            rabbitTemplate.send(FAILED_EXCHANGE, sourceServiceId, response);

            log.warn("[HANDLER] Sent CommandFailedEvent: correlationId={} error='{}'",
                correlationId, error.getMessage());
        } catch (Exception e2) {
            log.error("[HANDLER] Failed to send error response: {}", e2.getMessage(), e2);
        }
    }
}
