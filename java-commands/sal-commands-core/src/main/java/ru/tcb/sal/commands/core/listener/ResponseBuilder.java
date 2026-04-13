package ru.tcb.sal.commands.core.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ru.tcb.sal.commands.core.wire.RecordedMessage;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class ResponseBuilder {

    public static final String COMPLETED_EXCHANGE = "TCB.Infrastructure.Command.CommandCompletedEvent";
    public static final String COMPLETED_CONTENT_TYPE = "TCB.Infrastructure.Command.CommandCompletedEvent, TCB.Infrastructure";
    public static final String FAILED_EXCHANGE = "TCB.Infrastructure.Command.CommandFailedEvent";
    public static final String FAILED_CONTENT_TYPE = "TCB.Infrastructure.Command.CommandFailedEvent, TCB.Infrastructure";

    private static final DateTimeFormatter DOTNET_TS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    private static final DateTimeFormatter DOTNET_TS_OFFSET = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSSSXXX");

    private final ObjectMapper mapper;
    private final String adapterName;
    private final AtomicLong messageIdCounter = new AtomicLong(1000);

    public ResponseBuilder(ObjectMapper mapper, String adapterName) {
        this.mapper = mapper;
        this.adapterName = adapterName;
    }

    public RecordedMessage buildCompleted(RecordedMessage incomingCmd, Object result,
                                           String resultTypeAssemblyQualified,
                                           Duration executionDuration, String sessionBase64) {
        ObjectNode eventBody = mapper.createObjectNode();
        eventBody.put("ResultType", resultTypeAssemblyQualified);
        eventBody.set("Result", mapper.valueToTree(result));
        eventBody.set("AdditionalData", mapper.createObjectNode());
        eventBody.set("Context", buildContext(incomingCmd, executionDuration));

        return buildResponseMessage(incomingCmd, COMPLETED_EXCHANGE,
            COMPLETED_CONTENT_TYPE, eventBody, sessionBase64);
    }

    public RecordedMessage buildFailed(RecordedMessage incomingCmd, Throwable error,
                                        Duration executionDuration, String sessionBase64) {
        ObjectNode exData = mapper.createObjectNode();
        exData.put("ExceptionType", error.getClass().getName());
        exData.put("Code", "JavaHandlerException");
        exData.put("Message", error.getMessage());
        exData.put("AdapterName", adapterName);
        exData.put("SourceType", "CommandHandler");
        exData.put("SourcePath", incomingCmd.routingKey);
        exData.put("SessionId", "");
        exData.put("SourceId", incomingCmd.correlationId);
        exData.put("TimeStamp", Instant.now().toString());
        exData.set("Properties", mapper.createObjectNode().put("Type", error.getClass().getName()));
        exData.putNull("InnerException");

        ObjectNode eventBody = mapper.createObjectNode();
        eventBody.set("ExceptionData", exData);
        eventBody.set("AdditionalData", mapper.createObjectNode());
        eventBody.set("Context", buildContext(incomingCmd, executionDuration));

        return buildResponseMessage(incomingCmd, FAILED_EXCHANGE,
            FAILED_CONTENT_TYPE, eventBody, sessionBase64);
    }

    private RecordedMessage buildResponseMessage(RecordedMessage incomingCmd,
                                                  String exchange, String contentType,
                                                  ObjectNode eventBody, String sessionBase64) {
        RecordedMessage rm = new RecordedMessage();
        rm.exchangeName = exchange;
        rm.routingKey = incomingCmd.sourceServiceId;
        rm.correlationId = incomingCmd.correlationId;
        rm.contentType = contentType;
        rm.messageId = String.valueOf(messageIdCounter.incrementAndGet());
        rm.priority = incomingCmd.priority;
        rm.timeStamp = Instant.now();
        rm.payload = eventBody;
        rm.acceptLanguage = "ru-RU";
        rm.additionalData = new LinkedHashMap<>();
        if (sessionBase64 != null) rm.additionalData.put("Session", sessionBase64);
        rm.additionalData.put("SourceServiceId", adapterName);
        return rm;
    }

    private ObjectNode buildContext(RecordedMessage incomingCmd, Duration executionDuration) {
        Instant now = Instant.now();
        ObjectNode ctx = mapper.createObjectNode();
        ctx.put("CommandType", incomingCmd.routingKey);
        ctx.put("CorrelationId", incomingCmd.correlationId);
        ctx.put("SourceServiceId", incomingCmd.sourceServiceId != null ? incomingCmd.sourceServiceId : "");
        if (incomingCmd.timeStamp != null) {
            ctx.put("TimeStamp", DOTNET_TS.format(incomingCmd.timeStamp.atOffset(ZoneOffset.ofHours(3))));
        } else {
            ctx.put("TimeStamp", DOTNET_TS.format(now.atOffset(ZoneOffset.ofHours(3))));
        }
        ctx.put("Priority", "Normal");
        ctx.put("ExcutionServiceId", adapterName);
        ctx.put("ExcutionTimeStamp", DOTNET_TS_OFFSET.format(now.atOffset(ZoneOffset.ofHours(3))));
        ctx.put("ExcutionDuration", formatDotNetTimeSpan(executionDuration));
        ctx.put("SessionId", "");
        ctx.put("OperationId", "");
        return ctx;
    }

    static String formatDotNetTimeSpan(Duration duration) {
        long totalNanos = duration.toNanos();
        long hours = totalNanos / 3_600_000_000_000L;
        long minutes = (totalNanos % 3_600_000_000_000L) / 60_000_000_000L;
        long seconds = (totalNanos % 60_000_000_000L) / 1_000_000_000L;
        long ticks = (totalNanos % 1_000_000_000L) / 100L;
        return String.format("%02d:%02d:%02d.%07d", hours, minutes, seconds, ticks);
    }
}
