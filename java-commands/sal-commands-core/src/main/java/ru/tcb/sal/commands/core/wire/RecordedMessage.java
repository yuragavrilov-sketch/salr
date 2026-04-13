package ru.tcb.sal.commands.core.wire;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Internal Java representation of a wire message. NOT directly JSON-serializable.
 * The converter maps between this struct and (AMQP body, properties, headers).
 *
 * <p>For commands: payload = the command object (or JsonNode from incoming).
 * For results: payload = CommandCompletedEvent or CommandFailedEvent (or JsonNode).
 */
public class RecordedMessage {
    public String correlationId;
    public String exchangeName;
    public String routingKey;
    public String sourceServiceId;
    public String messageId;
    public int priority;
    public Instant timeStamp;
    public Object payload;
    public String contentType;
    public String acceptLanguage = "ru-RU";
    public Map<String, String> additionalData = new LinkedHashMap<>();
}
