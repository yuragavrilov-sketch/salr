package ru.tcb.sal.commands.core.transport.amqp;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.tcb.sal.commands.api.Command;
import ru.tcb.sal.commands.api.annotation.WireName;
import ru.tcb.sal.commands.core.wire.RecordedMessage;
import ru.tcb.sal.commands.core.wire.WireTypeRegistry;

import java.time.Instant;
import java.util.LinkedHashMap;

import static org.assertj.core.api.Assertions.assertThat;

class RecordedMessageConverterTest {

    @WireName("TCB.Test.PingCommand")
    static class PingCommand implements Command {
        public String text;
        public int count;

        public PingCommand() {}
        public PingCommand(String text, int count) {
            this.text = text;
            this.count = count;
        }
    }

    private RecordedMessageConverter converter;

    @BeforeEach
    void setUp() {
        WireTypeRegistry registry = new WireTypeRegistry();
        registry.register(PingCommand.class);
        converter = new RecordedMessageConverter(registry);
    }

    @Test
    void serialize_producesPascalCaseFields() {
        RecordedMessage rm = new RecordedMessage();
        rm.correlationId = "cid-1";
        rm.exchangeName = "CommandExchange";
        rm.routingKey = "TCB.Test.PingCommand";
        rm.sourceServiceId = "ru.tcb.test.AdapterA";
        rm.messageId = "msg-1";
        rm.priority = (byte) 5;
        rm.timeStamp = Instant.parse("2026-04-10T12:00:00Z");
        rm.expireDate = Instant.parse("2026-04-10T12:02:00Z");
        rm.payload = new PingCommand("hello", 3);
        rm.additionalData.put("IsCommand", "");

        byte[] bytes = converter.toBytes(rm);
        String json = new String(bytes);

        assertThat(json).contains("\"CorrelationId\":\"cid-1\"");
        assertThat(json).contains("\"ExchangeName\":\"CommandExchange\"");
        assertThat(json).contains("\"RoutingKey\":\"TCB.Test.PingCommand\"");
        assertThat(json).contains("\"SourceServiceId\":\"ru.tcb.test.AdapterA\"");
        assertThat(json).contains("\"Priority\":5");
        assertThat(json).contains("\"TimeStamp\":\"2026-04-10T12:00:00Z\"");
        assertThat(json).contains("\"AdditionalData\":{\"IsCommand\":\"\"}");
        // Payload с PascalCase полями самой команды
        assertThat(json).contains("\"Text\":\"hello\"");
        assertThat(json).contains("\"Count\":3");
    }

    @Test
    void roundTrip_preservesAllFields() {
        RecordedMessage original = new RecordedMessage();
        original.correlationId = "cid-1";
        original.exchangeName = "CommandExchange";
        original.routingKey = "TCB.Test.PingCommand";
        original.sourceServiceId = "ru.tcb.test.AdapterA";
        original.messageId = "msg-1";
        original.priority = (byte) 5;
        original.timeStamp = Instant.parse("2026-04-10T12:00:00Z");
        original.expireDate = Instant.parse("2026-04-10T12:02:00Z");
        original.payload = new PingCommand("hello", 3);
        original.additionalData = new LinkedHashMap<>();
        original.additionalData.put("IsCommand", "");

        byte[] bytes = converter.toBytes(original);
        RecordedMessage decoded = converter.fromBytes(bytes);

        assertThat(decoded.correlationId).isEqualTo("cid-1");
        assertThat(decoded.exchangeName).isEqualTo("CommandExchange");
        assertThat(decoded.routingKey).isEqualTo("TCB.Test.PingCommand");
        assertThat(decoded.sourceServiceId).isEqualTo("ru.tcb.test.AdapterA");
        assertThat(decoded.priority).isEqualTo((byte) 5);
        assertThat(decoded.timeStamp).isEqualTo(Instant.parse("2026-04-10T12:00:00Z"));
        assertThat(decoded.expireDate).isEqualTo(Instant.parse("2026-04-10T12:02:00Z"));
        assertThat(decoded.additionalData).containsEntry("IsCommand", "");
        // Payload на этапе fromBytes — JsonNode (тип неизвестен без routingKey лукапа)
        assertThat(decoded.payload).isInstanceOf(JsonNode.class);
    }

    @Test
    void serialize_nullExpireDate_omitsOrWritesNull() {
        RecordedMessage rm = new RecordedMessage();
        rm.correlationId = "cid-1";
        rm.exchangeName = "CommandExchange";
        rm.routingKey = "TCB.Test.PingCommand";
        rm.priority = (byte) 5;
        rm.timeStamp = Instant.parse("2026-04-10T12:00:00Z");
        rm.payload = new PingCommand("x", 1);
        // expireDate = null

        byte[] bytes = converter.toBytes(rm);
        String json = new String(bytes);

        // .NET сериализует null поля как null (а не опускает)
        assertThat(json).contains("\"ExpireDate\":null");
    }
}
