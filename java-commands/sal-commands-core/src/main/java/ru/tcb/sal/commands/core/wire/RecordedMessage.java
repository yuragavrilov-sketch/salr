package ru.tcb.sal.commands.core.wire;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Зеркало .NET TCB.Infrastructure.Message.RecordedMessage — транспортный
 * конверт шины. Поля публичные и lowerCamelCase; Jackson сериализует
 * их в PascalCase через локальную политику naming в RecordedMessageConverter.
 *
 * <p>Не record, потому что .NET-сторона ожидает mutable POJO-форму
 * и мы сами иногда заполняем поля поэтапно.
 */
public class RecordedMessage {
    public String correlationId;
    public String exchangeName;
    public String routingKey;
    public String sourceServiceId;
    public String messageId;
    public byte priority;
    public Instant timeStamp;
    public Instant expireDate;
    public Object payload;
    public Map<String, String> additionalData = new LinkedHashMap<>();
}
