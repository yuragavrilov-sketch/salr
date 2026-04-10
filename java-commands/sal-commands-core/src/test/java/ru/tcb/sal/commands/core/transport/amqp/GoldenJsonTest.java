package ru.tcb.sal.commands.core.transport.amqp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.tcb.sal.commands.core.wire.RecordedMessage;
import ru.tcb.sal.commands.core.wire.WireTypeRegistry;

import java.io.IOException;
import java.io.InputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Проверяет, что golden JSON-файлы (снятые с .NET SAL, здесь —
 * синтетические) round-trip'ятся через {@link RecordedMessageConverter}
 * без потерь. Если тесты падают — это значит, что wire-формат
 * изменился и надо расследовать причину (не просто обновлять golden!).
 */
class GoldenJsonTest {

    private RecordedMessageConverter converter;
    private ObjectMapper plainMapper;

    @BeforeEach
    void setUp() {
        WireTypeRegistry registry = new WireTypeRegistry();
        converter = new RecordedMessageConverter(registry);
        plainMapper = converter.mapper();
    }

    @Test
    void simplePingCommand_roundTrips() throws IOException {
        byte[] golden = readResource("golden/simple-ping-command.json");

        RecordedMessage rm = converter.fromBytes(golden);
        byte[] written = converter.toBytes(rm);

        assertJsonTreesEqual(golden, written);
    }

    @Test
    void commandCompletedEvent_roundTrips() throws IOException {
        byte[] golden = readResource("golden/command-completed-event.json");

        RecordedMessage rm = converter.fromBytes(golden);
        byte[] written = converter.toBytes(rm);

        assertJsonTreesEqual(golden, written);
    }

    @Test
    void commandFailedEvent_roundTrips() throws IOException {
        byte[] golden = readResource("golden/command-failed-event.json");

        RecordedMessage rm = converter.fromBytes(golden);
        byte[] written = converter.toBytes(rm);

        assertJsonTreesEqual(golden, written);
    }

    @Test
    void simplePingCommand_preservesTopLevelFields() throws IOException {
        byte[] golden = readResource("golden/simple-ping-command.json");
        RecordedMessage rm = converter.fromBytes(golden);

        assertThat(rm.correlationId).isEqualTo("11111111-2222-3333-4444-555555555555");
        assertThat(rm.exchangeName).isEqualTo("CommandExchange");
        assertThat(rm.routingKey).isEqualTo("TCB.Test.PingCommand");
        assertThat(rm.sourceServiceId).isEqualTo("ru.tcb.test.AdapterA");
        assertThat(rm.priority).isEqualTo((byte) 5);
        assertThat(rm.additionalData).containsEntry("IsCommand", "");
        assertThat(rm.additionalData).containsKey("Session");
    }

    @Test
    void commandCompletedEvent_preservesTypoFields() throws IOException {
        byte[] golden = readResource("golden/command-completed-event.json");
        RecordedMessage rm = converter.fromBytes(golden);

        // payload как JsonNode, поля остались PascalCase с опечатками
        JsonNode payload = (JsonNode) rm.payload;
        JsonNode ctx = payload.get("Context");
        assertThat(ctx.get("ExcutionServiceId").asText()).isEqualTo("ru.tcb.test.AdapterB");
        assertThat(ctx.get("ExcutionDuration").asText()).isEqualTo("PT0.050S");
        assertThat(payload.get("ResultType").asText())
            .isEqualTo("TCB.Test.PingResult, TCB.Test.Contracts");
    }

    private byte[] readResource(String path) throws IOException {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(path)) {
            if (is == null) throw new IOException("Resource not found: " + path);
            return is.readAllBytes();
        }
    }

    private void assertJsonTreesEqual(byte[] expected, byte[] actual) throws IOException {
        JsonNode expectedTree = plainMapper.readTree(expected);
        JsonNode actualTree = plainMapper.readTree(actual);
        assertThat(actualTree)
            .withFailMessage(
                "JSON trees differ.\nExpected:\n%s\nActual:\n%s",
                prettyPrint(expectedTree), prettyPrint(actualTree))
            .isEqualTo(expectedTree);
    }

    private String prettyPrint(JsonNode tree) throws IOException {
        return plainMapper.writerWithDefaultPrettyPrinter().writeValueAsString(tree);
    }
}
