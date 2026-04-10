package ru.tcb.sal.commands.core.transport.amqp;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import ru.tcb.sal.commands.core.wire.RecordedMessage;
import ru.tcb.sal.commands.core.wire.WireTypeRegistry;

import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Bytes &lt;-&gt; {@link RecordedMessage}. Один из двух ключевых классов
 * wire-слоя (второй — {@code WireTypeRegistry}).
 *
 * <p>Локальный {@link ObjectMapper} с политиками:
 * <ul>
 *   <li>PropertyNamingStrategies.UPPER_CAMEL_CASE — поля в JSON PascalCase</li>
 *   <li>JavaTimeModule — {@code Instant}/{@code Duration} как ISO-8601 строки</li>
 *   <li>WRITE_DATES_AS_TIMESTAMPS=false — отключает числовые timestamps</li>
 *   <li>JsonInclude.ALWAYS — null сериализуются явно (как .NET Newtonsoft по умолчанию)</li>
 *   <li>FAIL_ON_UNKNOWN_PROPERTIES=false — толерантность к новым полям с .NET-стороны</li>
 * </ul>
 *
 * <p>На read-пути поле {@code payload} читается как {@code JsonNode} —
 * конкретный тип резолвится позже, когда известен {@code routingKey}
 * или {@code ResultType}.
 */
public class RecordedMessageConverter {

    private final ObjectMapper mapper;
    private final WireTypeRegistry registry;

    public RecordedMessageConverter(WireTypeRegistry registry) {
        this.registry = registry;
        this.mapper = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.UPPER_CAMEL_CASE)
            .registerModule(new JavaTimeModule())
            .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .setSerializationInclusion(JsonInclude.Include.ALWAYS);
    }

    public byte[] toBytes(RecordedMessage rm) {
        try {
            return mapper.writeValueAsBytes(rm);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to serialize RecordedMessage", e);
        }
    }

    public RecordedMessage fromBytes(byte[] bytes) {
        try {
            return mapper.readValue(bytes, RecordedMessage.class);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to deserialize RecordedMessage", e);
        }
    }

    /**
     * Конвертирует {@code rm.payload} (обычно {@link JsonNode} после {@link #fromBytes})
     * в конкретный тип {@code targetType}. Используется listener'ом,
     * когда по {@code routingKey} резолвлен ожидаемый класс команды.
     */
    public <T> T readPayloadAs(RecordedMessage rm, Class<T> targetType) {
        if (rm.payload == null) {
            return null;
        }
        if (targetType.isInstance(rm.payload)) {
            return targetType.cast(rm.payload);
        }
        if (rm.payload instanceof JsonNode node) {
            try {
                return mapper.treeToValue(node, targetType);
            } catch (IOException e) {
                throw new UncheckedIOException(
                    "Failed to convert payload to " + targetType.getName(), e);
            }
        }
        // Fallback: через JSON round-trip (для не-JsonNode объектов)
        try {
            byte[] bytes = mapper.writeValueAsBytes(rm.payload);
            return mapper.readValue(bytes, targetType);
        } catch (IOException e) {
            throw new UncheckedIOException(
                "Failed to convert payload to " + targetType.getName(), e);
        }
    }

    /**
     * Доступ к локальному ObjectMapper — нужен выше-стоящему коду
     * для конвертации payload (JsonNode) в конкретный тип команды/результата.
     */
    public ObjectMapper mapper() {
        return mapper;
    }

    public WireTypeRegistry registry() {
        return registry;
    }
}
