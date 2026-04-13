package ru.tcb.sal.demo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Service;
import ru.tcb.sal.commands.core.transport.amqp.RecordedMessageConverter;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ResultListenerService {

    private static final Logger log = LoggerFactory.getLogger(ResultListenerService.class);

    public record CapturedResult(
        String correlationId,
        String exchange,
        String routingKey,
        String contentType,
        String bodyJson,
        Instant receivedAt
    ) {}

    private final RecordedMessageConverter converter;
    private final IncomingCommandHandler commandHandler;
    private final ConcurrentHashMap<String, CapturedResult> results = new ConcurrentHashMap<>();

    public ResultListenerService(RecordedMessageConverter converter,
                                  IncomingCommandHandler commandHandler) {
        this.converter = converter;
        this.commandHandler = commandHandler;
        log.info("[INIT] ResultListenerService created");
    }

    @RabbitListener(queues = "#{salAdapterQueue.name}")
    public void onMessage(Message message) {
        String exchange = message.getMessageProperties().getReceivedExchange();
        String routingKey = message.getMessageProperties().getReceivedRoutingKey();
        String contentType = message.getMessageProperties().getContentType();
        String correlationId = message.getMessageProperties().getCorrelationId();
        String messageId = message.getMessageProperties().getMessageId();
        int bodyLen = message.getBody() != null ? message.getBody().length : 0;

        log.info("[RECV] Message arrived: exchange='{}' routingKey='{}' correlationId={} messageId={} bodySize={}",
            exchange, routingKey, correlationId, messageId, bodyLen);
        log.debug("[RECV] contentType='{}'", contentType);
        log.debug("[RECV] AMQP headers: {}", message.getMessageProperties().getHeaders());

        // Route: CommandExchange → incoming command, handle it
        if ("CommandExchange".equals(exchange)) {
            log.info("[RECV] INCOMING COMMAND from CommandExchange, delegating to handler");
            commandHandler.handleCommand(message);
            return;
        }

        try {
            String bodyJson = new String(message.getBody(), StandardCharsets.UTF_8);
            log.debug("[RECV] Body (first 500 chars): {}",
                bodyJson.length() > 500 ? bodyJson.substring(0, 500) + "..." : bodyJson);

            CapturedResult result = new CapturedResult(
                correlationId,
                exchange,
                routingKey,
                contentType,
                bodyJson,
                Instant.now()
            );

            if (correlationId != null) {
                results.put(correlationId, result);
                log.info("[RECV] Stored result for correlationId={}, total results in store: {}",
                    correlationId, results.size());
            } else {
                log.warn("[RECV] Message has no correlationId, cannot store. exchange={} routingKey={}",
                    exchange, routingKey);
            }

            if (exchange != null && exchange.contains("CommandCompletedEvent")) {
                log.info("[RECV] COMPLETED: correlationId={} contentType={}", correlationId, contentType);
            } else if (exchange != null && exchange.contains("CommandFailedEvent")) {
                log.warn("[RECV] FAILED: correlationId={} contentType={}", correlationId, contentType);
            } else {
                log.info("[RECV] OTHER: exchange={} correlationId={}", exchange, correlationId);
            }

        } catch (Exception e) {
            log.error("[RECV] Error processing message: correlationId={} error={}",
                correlationId, e.getMessage(), e);
        }
    }

    public CapturedResult getResult(String correlationId) {
        return results.get(correlationId);
    }

    public Map<String, CapturedResult> getAllResults() {
        return Map.copyOf(results);
    }

    public int resultCount() {
        return results.size();
    }
}
