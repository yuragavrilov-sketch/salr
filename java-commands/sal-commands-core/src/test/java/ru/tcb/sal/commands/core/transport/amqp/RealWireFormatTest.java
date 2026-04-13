package ru.tcb.sal.commands.core.transport.amqp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.tcb.sal.commands.core.wire.CommandCompletedEvent;
import ru.tcb.sal.commands.core.wire.CommandFailedEvent;
import ru.tcb.sal.commands.core.wire.RecordedMessage;
import ru.tcb.sal.commands.core.wire.WireTypeRegistry;

import java.io.IOException;
import java.io.InputStream;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests converter against REAL wire dumps from production .NET SAL,
 * captured via sal-commands-wire-probe.
 */
class RealWireFormatTest {

    private RecordedMessageConverter converter;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        WireTypeRegistry registry = new WireTypeRegistry();
        converter = new RecordedMessageConverter(registry);
        mapper = converter.mapper();
    }

    // ========== Command (msg-001) ==========

    @Test
    void command_fromAmqp_parsesAllProperties() throws IOException {
        JsonNode dump = loadDump("golden/msg-001-TCB.MercLibrary.Client.Commands.GetConfigurationCommand.json");
        RecordedMessageConverter.AmqpWireMessage amqp = dumpToAmqp(dump);
        RecordedMessage rm = converter.fromAmqp(amqp);

        assertThat(rm.exchangeName).isEqualTo("CommandExchange");
        assertThat(rm.routingKey).isEqualTo("TCB.MercLibrary.Client.Commands.GetConfigurationCommand");
        assertThat(rm.contentType).isEqualTo("TCB.MercLibrary.Client.Commands.GetConfigurationCommand, TCB.MercLibrary.Client");
        assertThat(rm.correlationId).isEqualTo("060afc81-1b8e-478a-bdfe-d5e949d395c0");
        assertThat(rm.messageId).isEqualTo("171478030");
        assertThat(rm.priority).isEqualTo(5);
        assertThat(rm.timeStamp).isNotNull();
    }

    @Test
    void command_fromAmqp_parsesAdditionalData() throws IOException {
        JsonNode dump = loadDump("golden/msg-001-TCB.MercLibrary.Client.Commands.GetConfigurationCommand.json");
        RecordedMessageConverter.AmqpWireMessage amqp = dumpToAmqp(dump);
        RecordedMessage rm = converter.fromAmqp(amqp);

        assertThat(rm.additionalData).containsKey("Session");
        assertThat(rm.additionalData).containsEntry("IsCommand", "");
        assertThat(rm.sourceServiceId).isEqualTo("WorkFlowMashineMain");
        assertThat(rm.acceptLanguage).isEqualTo("ru-RU");
    }

    @Test
    void command_fromAmqp_parsesBody() throws IOException {
        JsonNode dump = loadDump("golden/msg-001-TCB.MercLibrary.Client.Commands.GetConfigurationCommand.json");
        RecordedMessageConverter.AmqpWireMessage amqp = dumpToAmqp(dump);
        RecordedMessage rm = converter.fromAmqp(amqp);

        assertThat(rm.payload).isInstanceOf(JsonNode.class);
        JsonNode body = (JsonNode) rm.payload;
        assertThat(body.get("MercID").asText()).isEqualTo("103337");
    }

    // ========== CommandCompletedEvent (msg-002) ==========

    @Test
    void completed_fromAmqp_parsesProperties() throws IOException {
        JsonNode dump = loadDump("golden/msg-002-WorkFlowMashineMain.json");
        RecordedMessageConverter.AmqpWireMessage amqp = dumpToAmqp(dump);
        RecordedMessage rm = converter.fromAmqp(amqp);

        assertThat(rm.exchangeName).isEqualTo("TCB.Infrastructure.Command.CommandCompletedEvent");
        assertThat(rm.routingKey).isEqualTo("WorkFlowMashineMain");
        assertThat(rm.contentType).isEqualTo("TCB.Infrastructure.Command.CommandCompletedEvent, TCB.Infrastructure");
        assertThat(rm.correlationId).isEqualTo("060afc81-1b8e-478a-bdfe-d5e949d395c0");
    }

    @Test
    void completed_fromAmqp_parsesEventBody() throws IOException {
        JsonNode dump = loadDump("golden/msg-002-WorkFlowMashineMain.json");
        RecordedMessageConverter.AmqpWireMessage amqp = dumpToAmqp(dump);
        RecordedMessage rm = converter.fromAmqp(amqp);

        CommandCompletedEvent event = converter.readAsCompleted(rm);

        assertThat(event.resultType).isEqualTo(
            "TCB.MercLibrary.Client.Results.GetConfigurationCommandResult, TCB.MercLibrary.Client");
        assertThat(event.result).isNotNull();
        assertThat(event.context).isNotNull();
        assertThat(event.context.commandType)
            .isEqualTo("TCB.MercLibrary.Client.Commands.GetConfigurationCommand");
        assertThat(event.context.correlationId)
            .isEqualTo("060afc81-1b8e-478a-bdfe-d5e949d395c0");
        assertThat(event.context.sourceServiceId).isEqualTo("WorkFlowMashineMain");
        assertThat(event.context.priority).isEqualTo("Normal");
        assertThat(event.context.excutionDuration).isEqualTo("00:00:00.2392809");
        assertThat(event.context.excutionServiceId).isEqualTo("TCB.MercLibrary");
    }

    // ========== CommandFailedEvent (msg-003) ==========

    @Test
    void failed_fromAmqp_parsesProperties() throws IOException {
        JsonNode dump = loadDump("golden/msg-003-WorkFlowMashineMain.json");
        RecordedMessageConverter.AmqpWireMessage amqp = dumpToAmqp(dump);
        RecordedMessage rm = converter.fromAmqp(amqp);

        assertThat(rm.exchangeName).isEqualTo("TCB.Infrastructure.Command.CommandFailedEvent");
        assertThat(rm.routingKey).isEqualTo("WorkFlowMashineMain");
        assertThat(rm.contentType).isEqualTo("TCB.Infrastructure.Command.CommandFailedEvent, TCB.Infrastructure");
    }

    @Test
    void failed_fromAmqp_parsesEventBody() throws IOException {
        JsonNode dump = loadDump("golden/msg-003-WorkFlowMashineMain.json");
        RecordedMessageConverter.AmqpWireMessage amqp = dumpToAmqp(dump);
        RecordedMessage rm = converter.fromAmqp(amqp);

        CommandFailedEvent event = converter.readAsFailed(rm);

        assertThat(event.exceptionData).isNotNull();
        assertThat(event.exceptionData.exceptionType).isEqualTo("Fatal");
        assertThat(event.exceptionData.code).isEqualTo("FatalException");
        assertThat(event.exceptionData.message).isEqualTo("Sequence contains no matching element");
        assertThat(event.exceptionData.adapterName).isEqualTo("TCB.MercLibrary");
        assertThat(event.exceptionData.sourceType).isEqualTo("CommandHandler");
        assertThat(event.exceptionData.properties).isNotNull();

        assertThat(event.context).isNotNull();
        assertThat(event.context.commandType)
            .isEqualTo("TCB.MercLibrary.Client.Commands.GetFeeCommand");
        assertThat(event.context.priority).isEqualTo("Normal");
        assertThat(event.context.excutionDuration).isEqualTo("00:00:00.2431967");
    }

    // ========== Helpers ==========

    /**
     * Converts a wire-probe dump JSON into an AmqpWireMessage that the converter can parse.
     * This simulates what Spring AMQP's MessageListener would provide.
     */
    private RecordedMessageConverter.AmqpWireMessage dumpToAmqp(JsonNode dump) {
        JsonNode envelope = dump.get("envelope");
        JsonNode props = dump.get("properties");
        JsonNode body = dump.get("body");

        String bodyUtf8 = body.get("utf8").asText();

        Map<String, Object> headers = new LinkedHashMap<>();
        JsonNode hdrs = props.path("headers");
        hdrs.fields().forEachRemaining(e -> headers.put(e.getKey(), e.getValue().asText()));

        Long epochSeconds = props.has("timestampEpochSeconds") && !props.get("timestampEpochSeconds").isNull()
            ? props.get("timestampEpochSeconds").asLong() : null;
        Date timestamp = epochSeconds != null ? new Date(epochSeconds * 1000L) : null;

        return new RecordedMessageConverter.AmqpWireMessage(
            bodyUtf8.getBytes(java.nio.charset.StandardCharsets.UTF_8),
            envelope.get("exchange").asText(),
            envelope.get("routingKey").asText(),
            props.has("contentType") && !props.get("contentType").isNull()
                ? props.get("contentType").asText() : null,
            props.has("correlationId") && !props.get("correlationId").isNull()
                ? props.get("correlationId").asText() : null,
            props.has("messageId") && !props.get("messageId").isNull()
                ? props.get("messageId").asText() : null,
            props.has("priority") && !props.get("priority").isNull()
                ? props.get("priority").asInt() : 0,
            props.has("deliveryMode") && !props.get("deliveryMode").isNull()
                ? props.get("deliveryMode").asInt() : 2,
            timestamp,
            props.has("expiration") && !props.get("expiration").isNull()
                ? props.get("expiration").asText() : null,
            headers
        );
    }

    private JsonNode loadDump(String path) throws IOException {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(path)) {
            if (is == null) throw new IOException("Resource not found: " + path);
            return mapper.readTree(is);
        }
    }
}
