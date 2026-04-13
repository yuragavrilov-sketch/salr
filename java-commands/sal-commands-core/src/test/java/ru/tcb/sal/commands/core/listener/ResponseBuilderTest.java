package ru.tcb.sal.commands.core.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import ru.tcb.sal.commands.core.wire.RecordedMessage;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class ResponseBuilderTest {

    private final ResponseBuilder builder = new ResponseBuilder(new ObjectMapper(), "TestAdapter");

    @Test
    void buildCompleted_hasCorrectExchangeAndRouting() {
        RecordedMessage cmd = makeCmd();
        RecordedMessage response = builder.buildCompleted(cmd, new ObjectMapper().createObjectNode(),
            "TCB.Test.PingResult, TCB.Test", Duration.ofMillis(50), null);

        assertThat(response.exchangeName).isEqualTo("TCB.Infrastructure.Command.CommandCompletedEvent");
        assertThat(response.routingKey).isEqualTo("CallerAdapter");
        assertThat(response.correlationId).isEqualTo("cid-1");
        assertThat(response.contentType).isEqualTo(
            "TCB.Infrastructure.Command.CommandCompletedEvent, TCB.Infrastructure");
    }

    @Test
    void buildCompleted_contextHasDotNetFormat() throws Exception {
        RecordedMessage cmd = makeCmd();
        RecordedMessage response = builder.buildCompleted(cmd, new ObjectMapper().createObjectNode(),
            "TCB.Test.PingResult, TCB.Test", Duration.ofMillis(239), null);

        ObjectMapper m = new ObjectMapper();
        ObjectNode body = (ObjectNode) m.readTree(m.writeValueAsBytes(response.payload));
        ObjectNode ctx = (ObjectNode) body.get("Context");

        assertThat(ctx.get("Priority").asText()).isEqualTo("Normal");
        assertThat(ctx.get("ExcutionServiceId").asText()).isEqualTo("TestAdapter");
        assertThat(ctx.get("ExcutionDuration").asText()).startsWith("00:00:00.");
    }

    @Test
    void buildFailed_hasExceptionData() throws Exception {
        RecordedMessage cmd = makeCmd();
        RecordedMessage response = builder.buildFailed(cmd, new RuntimeException("fail"),
            Duration.ofMillis(10), null);

        assertThat(response.exchangeName).isEqualTo("TCB.Infrastructure.Command.CommandFailedEvent");

        ObjectMapper m = new ObjectMapper();
        ObjectNode body = (ObjectNode) m.readTree(m.writeValueAsBytes(response.payload));
        assertThat(body.get("ExceptionData").get("Message").asText()).isEqualTo("fail");
    }

    @Test
    void formatDotNetTimeSpan_correctFormat() {
        assertThat(ResponseBuilder.formatDotNetTimeSpan(Duration.ofMillis(239)))
            .isEqualTo("00:00:00.2390000");
        assertThat(ResponseBuilder.formatDotNetTimeSpan(Duration.ZERO))
            .isEqualTo("00:00:00.0000000");
        assertThat(ResponseBuilder.formatDotNetTimeSpan(Duration.ofSeconds(3, 500_000_000)))
            .isEqualTo("00:00:03.5000000");
    }

    private RecordedMessage makeCmd() {
        RecordedMessage cmd = new RecordedMessage();
        cmd.correlationId = "cid-1";
        cmd.routingKey = "TCB.Test.PingCommand";
        cmd.sourceServiceId = "CallerAdapter";
        cmd.priority = 5;
        cmd.timeStamp = Instant.parse("2026-04-13T10:00:00Z");
        return cmd;
    }
}
